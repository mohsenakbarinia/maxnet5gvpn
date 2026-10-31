"""Validation module ensuring manifests strictly comply with manifest.schema.json."""

import datetime
import json
from pathlib import Path
import re
from typing import Any, Dict, List, Optional, Tuple

try:
    import jsonschema  # type: ignore
    _HAS_JSONSCHEMA = True
except ImportError:
    _HAS_JSONSCHEMA = False

SCHEMA_PATH = Path(__file__).resolve().parent.parent / "manifest.schema.json"


def validate_manifest_dict(manifest: Dict[str, Any]) -> Tuple[bool, Optional[str]]:
    """Validates manifest dictionary against schema definition.

    Returns:
        (is_valid, error_message_if_invalid)
    """
    # 1. If jsonschema is available, run formal schema validation
    if _HAS_JSONSCHEMA and SCHEMA_PATH.exists():
        try:
            schema_data = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
            jsonschema.validate(instance=manifest, schema=schema_data)
        except jsonschema.ValidationError as e:
            return False, f"JSON Schema validation error: {e.message}"
        except Exception as e:
            return False, f"Schema validation failed: {e}"

    # 2. Built-in comprehensive schema validation (runs in all environments)
    required_top = ["schema", "sequence", "generatedAt", "validUntil", "keyId", "pipeline", "servers"]
    for field in required_top:
        if field not in manifest:
            return False, f"Missing required top-level field: '{field}'"

    if manifest.get("schema") != 1:
        return False, f"Expected schema=1, got {manifest.get('schema')}"

    sequence = manifest.get("sequence")
    if not isinstance(sequence, int) or sequence < 1:
        return False, f"Sequence must be positive integer, got {sequence}"

    # Validate ISO-8601 timestamps
    for date_field in ["generatedAt", "validUntil"]:
        val = manifest.get(date_field)
        if not isinstance(val, str):
            return False, f"{date_field} must be ISO-8601 string"
        try:
            datetime.datetime.fromisoformat(val.replace("Z", "+00:00"))
        except Exception:
            return False, f"{date_field} invalid ISO-8601 date: {val}"

    key_id = manifest.get("keyId")
    if not isinstance(key_id, str) or not key_id.strip():
        return False, "keyId must be non-empty string"

    pipeline = manifest.get("pipeline")
    if not isinstance(pipeline, dict) or "version" not in pipeline or "xrayVersion" not in pipeline:
        return False, "pipeline must be object with 'version' and 'xrayVersion'"

    servers = manifest.get("servers")
    if not isinstance(servers, list):
        return False, "servers must be an array"

    if len(servers) > 300:
        return False, f"Servers count exceeds maximum allowed 300: {len(servers)}"

    # Probe validation if present
    if "probe" in manifest and manifest["probe"] is not None:
        probe = manifest["probe"]
        if not isinstance(probe, dict):
            return False, "probe must be an object"
        urls = probe.get("urls", [])
        if not isinstance(urls, list) or len(urls) > 5:
            return False, "probe.urls must be array of at most 5 URLs"
        for u in urls:
            if not isinstance(u, str) or not u.startswith("https://"):
                return False, f"Probe URL must start with https://: {u}"

    # Validate server items
    server_req_fields = ["id", "protocol", "host", "port", "credential", "security", "network", "stats"]
    stats_req_fields = ["latency_ms", "jitter_ms", "success_ratio", "throughput_kbps", "age_h", "score"]

    id_regex = re.compile(r"^[0-9a-f]{16}$")

    for idx, srv in enumerate(servers):
        if not isinstance(srv, dict):
            return False, f"Server #{idx} is not an object"

        for rf in server_req_fields:
            if rf not in srv:
                return False, f"Server #{idx} missing required field '{rf}'"

        srv_id = srv.get("id", "")
        if not id_regex.match(str(srv_id)):
            return False, f"Server #{idx} invalid 16-hex canonical id: '{srv_id}'"

        proto = srv.get("protocol")
        if proto not in ("VLESS", "VMESS", "TROJAN"):
            return False, f"Server #{idx} unsupported protocol: {proto}"

        port = srv.get("port")
        if not isinstance(port, int) or not (1 <= port <= 65535):
            return False, f"Server #{idx} invalid port: {port}"

        sec = srv.get("security")
        if sec not in ("tls", "reality"):
            return False, f"Server #{idx} security must be 'tls' or 'reality', got: {sec}"

        stats = srv.get("stats")
        if not isinstance(stats, dict):
            return False, f"Server #{idx} missing stats object"

        for sf in stats_req_fields:
            if sf not in stats:
                return False, f"Server #{idx} stats missing '{sf}'"

        score = stats.get("score")
        if not isinstance(score, (int, float)) or not (0 <= score <= 100):
            return False, f"Server #{idx} invalid score: {score}"

    # Check uncompressed payload size limit (250 KB)
    raw_json = json.dumps(manifest, separators=(",", ":"))
    if len(raw_json.encode("utf-8")) > 250 * 1024:
        return False, f"Uncompressed manifest size ({len(raw_json.encode('utf-8'))} bytes) exceeds 250 KB limit"

    return True, None
