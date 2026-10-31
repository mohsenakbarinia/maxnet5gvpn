"""Unit and integration tests for Xray configuration generation and probe verification."""

import unittest

from free_config_pipeline.config import FreePipelineConfig
from free_config_pipeline.models import FreeServer
from free_config_pipeline.runner_test import (
    calculate_stats,
    generate_xray_test_config,
    ProbeRoundResult,
    test_batch_with_xray,
)


class TestXrayProbe(unittest.TestCase):

    def setUp(self):
        self.config = FreePipelineConfig()
        self.sample_servers = [
            FreeServer(
                id="0123456789abcdef",
                protocol="VLESS",
                host="vless.example.com",
                port=443,
                credential="a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
                network="tcp",
                security="reality",
                sni="dl.google.com",
                realityPublicKey="1111111111111111111111111111111111111111111",
                realityShortId="123456",
            ),
            FreeServer(
                id="1234567890abcdef",
                protocol="TROJAN",
                host="trojan.example.com",
                port=443,
                credential="Password123",
                network="ws",
                security="tls",
                sni="trojan.example.com",
            ),
        ]

    def test_generate_xray_test_config_structure(self):
        cfg = generate_xray_test_config(self.sample_servers, base_port=10800)

        # Check inbounds
        inbounds = cfg.get("inbounds", [])
        self.assertEqual(len(inbounds), 2)
        self.assertEqual(inbounds[0]["tag"], "socks_0")
        self.assertEqual(inbounds[0]["port"], 10800)
        self.assertEqual(inbounds[1]["tag"], "socks_1")
        self.assertEqual(inbounds[1]["port"], 10801)

        # Check outbounds
        outbounds = cfg.get("outbounds", [])
        self.assertEqual(len(outbounds), 3)  # 2 proxies + 1 direct fallback
        self.assertEqual(outbounds[0]["tag"], "proxy_0")
        self.assertEqual(outbounds[0]["protocol"], "vless")
        self.assertEqual(outbounds[1]["tag"], "proxy_1")
        self.assertEqual(outbounds[1]["protocol"], "trojan")
        self.assertEqual(outbounds[2]["tag"], "direct")

        # Check routing
        rules = cfg.get("routing", {}).get("rules", [])
        self.assertEqual(len(rules), 2)
        self.assertEqual(rules[0]["inboundTag"], ["socks_0"])
        self.assertEqual(rules[0]["outboundTag"], "proxy_0")

    def test_calculate_stats_positive(self):
        rounds = [
            ProbeRoundResult(success=True, ttfb_ms=120.0, status_code=204),
            ProbeRoundResult(success=True, ttfb_ms=130.0, status_code=204),
            ProbeRoundResult(success=True, ttfb_ms=125.0, status_code=204),
        ]
        stats = calculate_stats(rounds, self.config)
        self.assertTrue(stats.passed)
        self.assertEqual(stats.latency_ms, 125.0)
        self.assertEqual(stats.success_ratio, 1.0)
        self.assertTrue(stats.jitter_ms >= 0)

    def test_calculate_stats_low_success_ratio(self):
        # 1 success out of 3 = 0.33 < 0.67
        rounds = [
            ProbeRoundResult(success=True, ttfb_ms=120.0, status_code=204),
            ProbeRoundResult(success=False, error_message="Timeout"),
            ProbeRoundResult(success=False, error_message="Timeout"),
        ]
        stats = calculate_stats(rounds, self.config)
        self.assertFalse(stats.passed)
        self.assertIn("Success ratio", str(stats.failure_reason))

    def test_calculate_stats_high_latency(self):
        # Latency > 3000 ms
        rounds = [
            ProbeRoundResult(success=True, ttfb_ms=3200.0, status_code=204),
            ProbeRoundResult(success=True, ttfb_ms=3100.0, status_code=204),
            ProbeRoundResult(success=True, ttfb_ms=3150.0, status_code=204),
        ]
        stats = calculate_stats(rounds, self.config)
        self.assertFalse(stats.passed)
        self.assertIn("latency", str(stats.failure_reason).lower())

    def test_batch_probe_canary_rejection(self):
        # Mock probe function rejecting on canary mismatch
        def mock_probe_canary_fail(_port: int) -> ProbeRoundResult:
            return ProbeRoundResult(
                success=False,
                status_code=204,
                canary_matched=False,
                error_message="Canary SHA-256 mismatch",
            )

        cfg = FreePipelineConfig(stage_b_rounds=1, stage_b_interval_seconds=0)
        results = test_batch_with_xray(
            batch=[self.sample_servers[0]],
            config=cfg,
            probe_fn=mock_probe_canary_fail,
        )
        self.assertEqual(len(results), 1)
        srv, stats = results[0]
        self.assertFalse(stats.passed)


if __name__ == "__main__":
    unittest.main()
