"""Historical persistence, scoring, expiry, and diversity selection engine."""

from collections import defaultdict
from dataclasses import asdict, dataclass, field
import datetime
import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

from .config import DEFAULT_CONFIG, FreePipelineConfig
from .models import FreeServer
from .runner_test import ServerTestStats


@dataclass
class ServerHistory:
    """Historical telemetry for a server candidate across pipeline executions."""

    first_seen: str
    last_seen: str
    last_ok: str
    consecutive_failures: int = 0
    rolling_success_bits: int = 0  # Bitfield representing up to 48 recent runs
    runs_recorded: int = 0
    ewma_latency_ms: float = 0.0

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ServerHistory":
        return cls(**data)


def load_history(history_file: Path) -> Dict[str, ServerHistory]:
    """Loads state/history.json if present, returning a mapping of server_id -> ServerHistory."""
    if not history_file.exists():
        return {}
    try:
        data = json.loads(history_file.read_text(encoding="utf-8"))
        return {k: ServerHistory.from_dict(v) for k, v in data.items() if isinstance(v, dict)}
    except Exception:
        return {}


def save_history(history_file: Path, history_map: Dict[str, ServerHistory]) -> None:
    """Persists historical telemetry to disk."""
    history_file.parent.mkdir(parents=True, exist_ok=True)
    raw = {k: v.to_dict() for k, v in history_map.items()}
    history_file.write_text(json.dumps(raw, indent=2), encoding="utf-8")


def update_history_and_expire(
    tested_results: List[Tuple[FreeServer, ServerTestStats]],
    history_map: Dict[str, ServerHistory],
    config: FreePipelineConfig = DEFAULT_CONFIG,
    now: Optional[datetime.datetime] = None,
) -> Tuple[Dict[str, ServerHistory], List[Tuple[FreeServer, ServerTestStats, float]]]:
    """Updates server histories with current test results, applies expiry, and computes final scores.

    Returns:
        (updated_history_map, list of (server, stats, score))
    """
    if now is None:
        now = datetime.datetime.now(datetime.timezone.utc)
    now_iso = now.isoformat()

    alpha = 0.3  # EWMA smoothing factor
    scored_candidates: List[Tuple[FreeServer, ServerTestStats, float]] = []

    current_test_ids = {s.id for s, _ in tested_results}

    # 1. Update tested servers
    for server, stats in tested_results:
        hist = history_map.get(server.id)
        if hist is None:
            hist = ServerHistory(
                first_seen=now_iso,
                last_seen=now_iso,
                last_ok=now_iso if stats.passed else now_iso,
                consecutive_failures=0 if stats.passed else 1,
                rolling_success_bits=1 if stats.passed else 0,
                runs_recorded=1,
                ewma_latency_ms=stats.latency_ms if stats.passed else 2000.0,
            )
        else:
            hist.last_seen = now_iso
            # Shift bits and append current result (up to 48 runs)
            bit = 1 if stats.passed else 0
            hist.rolling_success_bits = ((hist.rolling_success_bits << 1) | bit) & ((1 << 48) - 1)
            hist.runs_recorded = min(48, hist.runs_recorded + 1)

            if stats.passed:
                hist.last_ok = now_iso
                hist.consecutive_failures = 0
                if hist.ewma_latency_ms <= 0:
                    hist.ewma_latency_ms = stats.latency_ms
                else:
                    hist.ewma_latency_ms = (alpha * stats.latency_ms) + ((1.0 - alpha) * hist.ewma_latency_ms)
            else:
                hist.consecutive_failures += 1

        history_map[server.id] = hist

        # Check expiry
        if hist.consecutive_failures >= config.expire_consecutive_failures:
            continue

        try:
            last_ok_dt = datetime.datetime.fromisoformat(hist.last_ok.replace("Z", "+00:00"))
            age_hours = (now - last_ok_dt).total_seconds() / 3600.0
            if age_hours > config.expire_hours and not stats.passed:
                continue
        except Exception:
            pass

        if not stats.passed:
            continue

        # Compute ranking score
        # 1. Historical success ratio
        ones = bin(hist.rolling_success_bits).count("1")
        success_ratio_hist = ones / hist.runs_recorded if hist.runs_recorded > 0 else stats.success_ratio

        # 2. Latency score: clamp((2000 - latency_ms)/(2000 - 80), 0, 1)
        effective_latency = hist.ewma_latency_ms if hist.ewma_latency_ms > 0 else stats.latency_ms
        latency_score = max(0.0, min(1.0, (2000.0 - effective_latency) / (2000.0 - 80.0)))

        # 3. Stability score from jitter
        stability_score = max(0.0, min(1.0, 1.0 - (stats.jitter_ms / 500.0)))

        # 4. Throughput score
        throughput_score = max(0.0, min(1.0, stats.throughput_kbps / 2048.0))

        score = 100.0 * (
            0.45 * success_ratio_hist +
            0.30 * latency_score +
            0.15 * stability_score +
            0.10 * throughput_score
        )
        score = round(max(0.0, min(100.0, score)), 2)

        scored_candidates.append((server, stats, score))

    # 2. Clean up old entries from history_map that were absent and expired
    expired_ids = []
    for sid, h in history_map.items():
        if sid not in current_test_ids:
            try:
                last_ok_dt = datetime.datetime.fromisoformat(h.last_ok.replace("Z", "+00:00"))
                if (now - last_ok_dt).total_seconds() / 3600.0 > config.expire_hours:
                    expired_ids.append(sid)
            except Exception:
                pass
    for sid in expired_ids:
        del history_map[sid]

    return history_map, scored_candidates


def select_diverse_top_servers(
    scored_candidates: List[Tuple[FreeServer, ServerTestStats, float]],
    config: FreePipelineConfig = DEFAULT_CONFIG,
) -> List[Tuple[FreeServer, ServerTestStats, float]]:
    """Selects top N servers enforcing a 50% class diversity limit on (protocol, network, security).

    Never ranks by geography or location.
    """
    # Sort descending by score, then ascending by latency
    sorted_candidates = sorted(
        scored_candidates,
        key=lambda item: (-item[2], item[1].latency_ms),
    )

    max_published = config.max_published_servers
    target_count = min(len(sorted_candidates), max_published)
    if target_count == 0:
        return []

    # Maximum allowed items in any single (protocol, network, security) class
    # 50% of target count (or at least 1)
    max_per_class = max(1, int(target_count * config.max_class_percentage))

    selected: List[Tuple[FreeServer, ServerTestStats, float]] = []
    class_counts: Dict[Tuple[str, str, str], int] = defaultdict(int)
    overflow: List[Tuple[FreeServer, ServerTestStats, float]] = []

    # First pass: pick top candidates adhering to class cap
    for item in sorted_candidates:
        srv, _, _ = item
        class_key = (srv.protocol.upper(), srv.network.lower(), srv.security.lower())
        if class_counts[class_key] < max_per_class and len(selected) < target_count:
            selected.append(item)
            class_counts[class_key] += 1
        else:
            overflow.append(item)

    # Second pass: if diversity constraint leaves slots empty, fill remaining from overflow
    for item in overflow:
        if len(selected) >= target_count:
            break
        selected.append(item)

    return selected
