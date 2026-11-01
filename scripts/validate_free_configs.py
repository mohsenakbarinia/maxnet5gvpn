#!/usr/bin/env python3
"""
Validates and deduplicates `free-configs/configs.txt` and updates `free-configs/manifest.json`
with the exact SHA-256 content hash, config count, monotonic version, and UTC timestamp.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import ipaddress
import json
import re
import sys
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

SUPPORTED_SCHEMES = {"vless", "vmess", "trojan"}
SAFE_HOST_RE = re.compile(r"^[a-zA-Z0-9._:\-\[\]]{3,253}$")
SCRIPT_PATTERNS = (
    "<script",
    "javascript:",
    "vbscript:",
    "#!/",
    "powershell",
    "cmd.exe",
    "/bin/sh",
    "/bin/bash",
    "eval(",
)


def is_safe_public_host(raw_host: str) -> bool:
    host = raw_host.strip().lower().lstrip("[").rstrip("]")
    if not host or len(host) > 253 or not SAFE_HOST_RE.match(host):
        return False
    if host in {"localhost", "0.0.0.0", "::1", "::"} or host.endswith(
        (".local", ".internal", ".lan")
    ):
        return False
    try:
        ip = ipaddress.ip_address(host)
        if (
            ip.is_loopback
            or ip.is_private
            or ip.is_link_local
            or ip.is_multicast
            or ip.is_reserved
            or ip.is_unspecified
        ):
            return False
        # Reject CGNAT 100.64.0.0/10
        if isinstance(ip, ipaddress.IPv4Address) and ip in ipaddress.ip_network(
            "100.64.0.0/10"
        ):
            return False
        return True
    except ValueError:
        return "." in host


def contains_script_injection(text: str) -> bool:
    lower = text.lower()
    return any(pat in lower for pat in SCRIPT_PATTERNS)


def parse_and_fingerprint_uri(line: str) -> tuple[bool, str, str]:
    """
    Returns (is_valid, fingerprint_key, reason).
    """
    line = line.strip()
    if not line or contains_script_injection(line):
        return False, "", "Empty or unsafe script marker"

    if "://" not in line:
        return False, "", "Missing scheme://"

    scheme = line.split("://", 1)[0].lower()
    if scheme not in SUPPORTED_SCHEMES:
        return False, "", f"Unsupported protocol scheme: {scheme}"

    if scheme == "vmess":
        b64_part = line[len("vmess://") :].strip()
        padded = b64_part + "=" * ((4 - len(b64_part) % 4) % 4)
        try:
            raw_json = base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8")
            obj = json.loads(raw_json)
        except Exception as exc:
            return False, "", f"Invalid VMess Base64/JSON: {exc}"

        host = str(obj.get("add", "")).strip()
        try:
            port = int(obj.get("port", 0))
        except (ValueError, TypeError):
            return False, "", "Invalid VMess port"
        uid = str(obj.get("id", "")).strip()
        net = str(obj.get("net", "tcp")).strip().lower() or "tcp"
        tls = str(obj.get("tls", "none")).strip().lower() or "none"
        sni = str(obj.get("sni", "")).strip().lower()
        host_hdr = str(obj.get("host", "")).strip().lower()
        path = str(obj.get("path", "")).strip()

        if not is_safe_public_host(host):
            return False, "", f"Unsafe or private VMess host: {host}"
        if not (1 <= port <= 65535):
            return False, "", f"Out-of-range VMess port: {port}"
        if not uid or len(uid) < 8:
            return False, "", "Missing VMess UUID"

        fp = f"vmess|{host.lower()}|{port}|{uid.lower()}|{net}|{tls}|{sni}|{host_hdr}|{path}"
        return True, fp, "OK"

    # VLESS or Trojan URI
    try:
        parsed = urlparse(line)
    except Exception as exc:
        return False, "", f"Malformed URL: {exc}"

    credential = unquote(parsed.username or "").strip()
    host = (parsed.hostname or "").strip()
    try:
        port = parsed.port or 0
    except ValueError:
        return False, "", "Invalid port"

    if not is_safe_public_host(host):
        return False, "", f"Unsafe or private host: {host}"
    if not (1 <= port <= 65535):
        return False, "", f"Invalid port: {port}"
    if not credential or len(credential) > 256:
        return False, "", "Missing or oversized credential"

    qs = parse_qs(parsed.query, keep_blank_values=False)
    net = (qs.get("type", ["tcp"])[0] or "tcp").strip().lower()
    sec = (qs.get("security", ["none"])[0] or "none").strip().lower()
    if scheme == "trojan" and sec == "none":
        sec = "tls"
    sni = (qs.get("sni", [""])[0] or "").strip().lower()
    pbk = (qs.get("pbk", [""])[0] or "").strip()
    sid = (qs.get("sid", [""])[0] or "").strip().lower()
    flow = (qs.get("flow", [""])[0] or "").strip().lower()
    path = (qs.get("path", [""])[0] or qs.get("serviceName", [""])[0] or "").strip()

    if sec == "reality" and not pbk:
        return False, "", "VLESS Reality requires non-empty pbk"

    fp = f"{scheme}|{host.lower()}|{port}|{credential}|{net}|{sec}|{sni}|{pbk}|{sid}|{flow}|{path}"
    return True, fp, "OK"


def validate_configs_file(
    configs_path: Path,
    manifest_path: Path,
    update_manifest: bool = False,
    dedupe_in_place: bool = False,
) -> int:
    if not configs_path.exists():
        print(f"ERROR: {configs_path} does not exist", file=sys.stderr)
        return 1

    raw_bytes = configs_path.read_bytes()
    raw_text = raw_bytes.decode("utf-8", errors="replace")

    lines = raw_text.splitlines()
    valid_lines: list[str] = []
    seen_fingerprints: set[str] = set()
    duplicates_removed = 0
    invalid_entries: list[tuple[int, str]] = []

    for idx, raw_line in enumerate(lines, start=1):
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        ok, fp, reason = parse_and_fingerprint_uri(stripped)
        if not ok:
            invalid_entries.append((idx, reason))
            continue
        if fp in seen_fingerprints:
            duplicates_removed += 1
            continue
        seen_fingerprints.add(fp)
        valid_lines.append(stripped)

    if invalid_entries:
        for line_no, reason in invalid_entries:
            print(f"INVALID line {line_no}: {reason}", file=sys.stderr)
        return 1

    if not valid_lines:
        print("ERROR: No valid VPN configurations found in configs.txt", file=sys.stderr)
        return 1

    if dedupe_in_place and duplicates_removed > 0:
        header_comments = [l for l in lines if l.strip().startswith("#")]
        rebuilt = "\n".join(header_comments + valid_lines) + "\n"
        configs_path.write_text(rebuilt, encoding="utf-8")
        raw_bytes = configs_path.read_bytes()

    content_sha256 = hashlib.sha256(raw_bytes).hexdigest()

    existing_manifest: dict = {}
    if manifest_path.exists():
        try:
            existing_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except Exception:
            existing_manifest = {}

    prev_version = int(existing_manifest.get("version", 0))
    prev_sha = str(existing_manifest.get("sha256", ""))

    if update_manifest:
        new_version = prev_version if (prev_sha == content_sha256 and prev_version > 0) else max(1, prev_version + 1)
        updated_at = (
            existing_manifest.get("updated_at")
            if (prev_sha == content_sha256 and existing_manifest.get("updated_at"))
            else dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        )
        manifest_data = {
            "schema_version": 1,
            "version": new_version,
            "updated_at": updated_at,
            "config_count": len(valid_lines),
            "sha256": content_sha256,
            "repository": "https://github.com/mohsenakbarinia/maxnet5gvpn",
            "configs_path": "free-configs/configs.txt",
            "supported_protocols": ["VLESS", "VLESS_REALITY", "VMESS", "TROJAN"],
            "signature": existing_manifest.get("signature", ""),
        }
        manifest_path.write_text(
            json.dumps(manifest_data, indent=2) + "\n", encoding="utf-8"
        )
        print(
            f"OK: Validated {len(valid_lines)} configs ({duplicates_removed} duplicate(s) skipped). "
            f"Updated {manifest_path} -> version={new_version}, sha256={content_sha256}"
        )
        return 0

    # Verify mode (without --update-manifest)
    if not manifest_path.exists():
        print(f"ERROR: {manifest_path} is missing", file=sys.stderr)
        return 1

    manifest_sha = str(existing_manifest.get("sha256", "")).lower()
    manifest_count = int(existing_manifest.get("config_count", -1))
    if manifest_sha != content_sha256:
        print(
            f"ERROR: Manifest SHA-256 mismatch: expected {content_sha256}, got {manifest_sha}",
            file=sys.stderr,
        )
        return 1
    if manifest_count != len(valid_lines):
        print(
            f"ERROR: Manifest config_count mismatch: expected {len(valid_lines)}, got {manifest_count}",
            file=sys.stderr,
        )
        return 1

    print(
        f"OK: Verified {len(valid_lines)} configs against {manifest_path} (sha256={content_sha256})"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate free-configs/configs.txt and verify/update free-configs/manifest.json"
    )
    parser.add_argument(
        "--configs",
        default="free-configs/configs.txt",
        help="Path to configs.txt",
    )
    parser.add_argument(
        "--manifest",
        default="free-configs/manifest.json",
        help="Path to manifest.json",
    )
    parser.add_argument(
        "--update-manifest",
        action="store_true",
        help="Update manifest.json with computed SHA-256 and config count",
    )
    parser.add_argument(
        "--dedupe-in-place",
        action="store_true",
        help="Rewrite configs.txt without duplicate entries",
    )
    args = parser.parse_args()
    return validate_configs_file(
        configs_path=Path(args.configs),
        manifest_path=Path(args.manifest),
        update_manifest=args.update_manifest,
        dedupe_in_place=args.dedupe_in_place,
    )


if __name__ == "__main__":
    sys.exit(main())
