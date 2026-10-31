"""Manifest construction, validation, signing, and safety net enforcement."""

import datetime
import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .config import DEFAULT_CONFIG, FreePipelineConfig
from .crypto_signing import sign_bytes, verify_signature
from .manifest_schema_validator import validate_manifest_dict
from .models import FreeServer
from .runner_test import ServerTestStats


class SafetyNetViolationError(Exception):
    """Raised when publication safety net criteria are breached."""
    pass


def build_manifest_dict(
    selected_servers: List[Tuple[FreeServer, ServerTestStats, float]],
    sequence: int,
    key_id: str,
    config: FreePipelineConfig = DEFAULT_CONFIG,
    now: Optional[datetime.datetime] = None,
) -> Dict[str, Any]:
    """Assembles a manifest dictionary conforming to manifest.schema.json."""
    if now is None:
        now = datetime.datetime.now(datetime.timezone.utc)

    gen_at = now.isoformat()
    valid_until = (now + datetime.timedelta(hours=config.manifest_validity_hours)).isoformat()

    servers_payload: List[Dict[str, Any]] = []

    for srv, stats, score in selected_servers:
        srv_dict = srv.to_dict()
        # Strip internal temporary pipeline fields
        srv_dict.pop("pipelineStats", None)

        srv_dict["stats"] = {
            "latency_ms": stats.latency_ms,
            "jitter_ms": stats.jitter_ms,
            "success_ratio": stats.success_ratio,
            "throughput_kbps": stats.throughput_kbps,
            "age_h": 0.0,
            "score": score,
        }
        servers_payload.append(srv_dict)

    manifest: Dict[str, Any] = {
        "schema": 1,
        "sequence": sequence,
        "generatedAt": gen_at,
        "validUntil": valid_until,
        "keyId": key_id,
        "pipeline": {
            "version": "1.0.0",
            "xrayVersion": config.xray_version,
        },
        "servers": servers_payload,
    }

    if config.probe_urls:
        manifest["probe"] = {"urls": config.probe_urls[:5]}

    return manifest


def check_safety_net(
    current_count: int,
    previous_count: Optional[int],
    config: FreePipelineConfig = DEFAULT_CONFIG,
) -> None:
    """Enforces safety nets preventing publication of severely depleted or collapsed server pools."""
    if current_count < config.min_servers:
        raise SafetyNetViolationError(
            f"Safety net triggered: candidate count ({current_count}) is below MIN_SERVERS ({config.min_servers})"
        )

    if previous_count is not None and previous_count > 0:
        drop_percent = ((previous_count - current_count) / float(previous_count)) * 100.0
        if drop_percent > config.drop_threshold_percent:
            raise SafetyNetViolationError(
                f"Safety net triggered: candidate count dropped by {drop_percent:.1f}% "
                f"({previous_count} -> {current_count}), exceeding drop threshold ({config.drop_threshold_percent}%)"
            )


def generate_and_sign_manifests(
    selected_servers: List[Tuple[FreeServer, ServerTestStats, float]],
    out_dir: Path,
    private_key_pem: str,
    public_key_pem: str,
    key_id: str,
    previous_manifest_path: Optional[Path] = None,
    config: FreePipelineConfig = DEFAULT_CONFIG,
    enforce_safety_net: bool = True,
) -> Tuple[Path, Path, Path, Path]:
    """Generates, validates, signs, and self-verifies manifest.v1.json and seed.v1.json.

    Returns:
        (manifest_json_path, manifest_sig_path, seed_json_path, seed_sig_path)
    """
    out_dir.mkdir(parents=True, exist_ok=True)

    previous_count = None
    next_sequence = 1

    if previous_manifest_path and previous_manifest_path.exists():
        try:
            prev_data = json.loads(previous_manifest_path.read_text(encoding="utf-8"))
            next_sequence = int(prev_data.get("sequence", 0)) + 1
            previous_count = len(prev_data.get("servers", []))
        except Exception:
            pass

    # Safety net check
    if enforce_safety_net:
        check_safety_net(
            current_count=len(selected_servers),
            previous_count=previous_count,
            config=config,
        )

    # 1. Full manifest (up to max_published_servers, default 300)
    manifest_dict = build_manifest_dict(
        selected_servers=selected_servers[:config.max_published_servers],
        sequence=next_sequence,
        key_id=key_id,
        config=config,
    )

    # Validate against schema
    is_valid, err = validate_manifest_dict(manifest_dict)
    if not is_valid:
        raise ValueError(f"Manifest schema validation failed: {err}")

    # Serialize canonical JSON bytes
    manifest_json_bytes = json.dumps(manifest_dict, indent=2).encode("utf-8")
    manifest_sig_b64 = sign_bytes(manifest_json_bytes, private_key_pem)

    # Self-verify signature
    if not verify_signature(manifest_json_bytes, manifest_sig_b64, public_key_pem):
        raise RuntimeError("Self-verification of manifest signature failed!")

    manifest_json_path = out_dir / "manifest.v1.json"
    manifest_sig_path = out_dir / "manifest.v1.json.sig"
    manifest_json_path.write_bytes(manifest_json_bytes)
    manifest_sig_path.write_text(manifest_sig_b64, encoding="utf-8")

    # 2. Seed manifest (top seed_server_count, default 100)
    seed_servers = selected_servers[:config.seed_server_count]
    seed_dict = build_manifest_dict(
        selected_servers=seed_servers,
        sequence=next_sequence,
        key_id=key_id,
        config=config,
    )
    is_valid, err = validate_manifest_dict(seed_dict)
    if not is_valid:
        raise ValueError(f"Seed manifest schema validation failed: {err}")

    seed_json_bytes = json.dumps(seed_dict, indent=2).encode("utf-8")
    seed_sig_b64 = sign_bytes(seed_json_bytes, private_key_pem)

    if not verify_signature(seed_json_bytes, seed_sig_b64, public_key_pem):
        raise RuntimeError("Self-verification of seed manifest signature failed!")

    seed_json_path = out_dir / "seed.v1.json"
    seed_sig_path = out_dir / "seed.v1.json.sig"
    seed_json_path.write_bytes(seed_json_bytes)
    seed_sig_path.write_text(seed_sig_b64, encoding="utf-8")

    return manifest_json_path, manifest_sig_path, seed_json_path, seed_sig_path
