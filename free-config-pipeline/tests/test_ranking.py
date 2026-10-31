"""Unit tests for ranking, history persistence, expiry, and class diversity capping."""

import datetime
import unittest

from free_config_pipeline.config import FreePipelineConfig
from free_config_pipeline.models import FreeServer
from free_config_pipeline.ranking import (
    select_diverse_top_servers,
    ServerHistory,
    update_history_and_expire,
)
from free_config_pipeline.runner_test import ServerTestStats


class TestRanking(unittest.TestCase):

    def _make_server(self, sid: str, proto: str = "VLESS", net: str = "tcp", sec: str = "reality") -> FreeServer:
        return FreeServer(
            id=sid,
            protocol=proto,
            host="node.example.com",
            port=443,
            credential="a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
            network=net,
            security=sec,
        )

    def test_ranking_scoring_and_ewma(self):
        server = self._make_server("srv1")
        stats = ServerTestStats(
            latency_ms=200.0,
            jitter_ms=10.0,
            success_ratio=1.0,
            throughput_kbps=2048.0,
            passed=True,
        )

        history_map = {}
        now = datetime.datetime.now(datetime.timezone.utc)

        history_map, scored = update_history_and_expire(
            tested_results=[(server, stats)],
            history_map=history_map,
            now=now,
        )

        self.assertEqual(len(scored), 1)
        _, _, score = scored[0]
        # With 200ms latency, 100% success, high throughput, score should be >= 90
        self.assertTrue(90.0 <= score <= 100.0)

        # Check EWMA latency recorded in history
        hist = history_map[server.id]
        self.assertEqual(hist.ewma_latency_ms, 200.0)
        self.assertEqual(hist.consecutive_failures, 0)

    def test_expiry_on_six_consecutive_failures(self):
        server = self._make_server("srv_fail")
        history_map = {
            server.id: ServerHistory(
                first_seen="2026-10-01T00:00:00+00:00",
                last_seen="2026-10-31T00:00:00+00:00",
                last_ok="2026-10-31T00:00:00+00:00",
                consecutive_failures=5,
                rolling_success_bits=0,
                runs_recorded=5,
            )
        }

        failing_stats = ServerTestStats(
            latency_ms=9999.0,
            jitter_ms=0.0,
            success_ratio=0.0,
            throughput_kbps=0.0,
            passed=False,
        )

        history_map, scored = update_history_and_expire(
            tested_results=[(server, failing_stats)],
            history_map=history_map,
        )

        # consecutive_failures becomes 6, should be expired / dropped from candidates
        self.assertEqual(history_map[server.id].consecutive_failures, 6)
        self.assertEqual(len(scored), 0)

    def test_diversity_capping_at_fifty_percent(self):
        """No single (proto, net, sec) class exceeds 50% when alternatives exist."""
        config = FreePipelineConfig(max_published_servers=10, max_class_percentage=0.50)

        candidates = []
        # Add 8 VLESS/tcp/reality servers
        for i in range(8):
            srv = self._make_server(f"vless_{i:02d}", proto="VLESS", net="tcp", sec="reality")
            stats = ServerTestStats(latency_ms=100.0 + i, jitter_ms=5.0, success_ratio=1.0, throughput_kbps=1000.0, passed=True)
            candidates.append((srv, stats, 90.0 - i))

        # Add 6 TROJAN/ws/tls servers (so alternatives exist to fill the 10-slot quota)
        for i in range(6):
            srv = self._make_server(f"trojan_{i:02d}", proto="TROJAN", net="ws", sec="tls")
            stats = ServerTestStats(latency_ms=250.0 + i, jitter_ms=10.0, success_ratio=1.0, throughput_kbps=1000.0, passed=True)
            candidates.append((srv, stats, 80.0 - i))

        selected = select_diverse_top_servers(candidates, config=config)
        self.assertEqual(len(selected), 10)

        # Count per class
        vless_count = sum(1 for s, _, _ in selected if s.protocol == "VLESS")
        trojan_count = sum(1 for s, _, _ in selected if s.protocol == "TROJAN")

        # In a list of 10, VLESS must be strictly capped to 50% (5) because alternatives exist
        self.assertEqual(vless_count, 5)
        self.assertEqual(trojan_count, 5)


if __name__ == "__main__":
    unittest.main()
