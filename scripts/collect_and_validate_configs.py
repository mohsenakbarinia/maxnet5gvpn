#!/usr/bin/env python3
"""
MAXNET5G Lightweight Automated VPN Configuration Collector & Validator
---------------------------------------------------------------------
Runs on free-tier GitHub Actions (or any lightweight cron worker) with zero paid server cost.
1. Fetches publicly available VLESS, VLESS Reality, VMess, and Trojan configurations
   from multiple configurable public sources.
2. Strictly sanitizes untrusted inputs (rejects scripts, private/loopback IPs, invalid ports).
3. Normalizes and deduplicates candidates by protocol + host + port + credential + transport.
4. Performs bounded parallel TCP handshake validation to filter out dead endpoints.
5. Writes the top validated candidates to `public/api/v1/candidates.json`.
"""

import base64
import concurrent.futures
import hashlib
import ipaddress
import json
import os
import re
import socket
import time
import urllib.parse
import urllib.request
from typing import Dict, List, Optional, Tuple

PUBLIC_SOURCES = [
    "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt",
    "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
    "https://raw.githubusercontent.com/mahdibland/ShadowsocksAggregator/master/Eternity",
    "https://raw.githubusercontent.com/yebekhe/TVC/main/subscriptions/xray/normal/mix",
]

SUPPORTED_SCHEMES = ("vless://", "vmess://", "trojan://")
SAFE_HOST_RE = re.compile(r"^[a-zA-Z0-9._:\-\[\]]{3,253}$")
FORBIDDEN_PATTERNS = ("<script", "javascript:", "#!/", "powershell", "cmd.exe", "/bin/sh", "eval(")
MAX_OUTPUT_CANDIDATES = 100
TCP_TIMEOUT_SECONDS = 2.0
MAX_PARALLEL_WORKERS = 24


def is_safe_public_host(host: str) -> bool:
    clean = host.strip().lower().strip("[]")
    if not clean or len(clean) > 253 or not SAFE_HOST_RE.match(clean):
        return False
    if clean in ("localhost", "0.0.0.0", "::1", "::") or clean.endswith((".local", ".internal", ".lan")):
        return False
    try:
        ip = ipaddress.ip_address(clean)
        return not (
            ip.is_private
            or ip.is_loopback
            or ip.is_link_local
            or ip.is_multicast
            or ip.is_reserved
            or ip.is_unspecified
        )
    except ValueError:
        return "." in clean


def fetch_source(url: str) -> str:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "MAXNET5G-Collector/2.5 (+https://github.com)",
            "Accept": "text/plain, */*;q=0.5",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            raw = resp.read(1024 * 1024).decode("utf-8", errors="ignore")
            if "://" not in raw:
                try:
                    decoded = base64.b64decode(raw.strip() + "===").decode("utf-8", errors="ignore")
                    if "://" in decoded:
                        return decoded
                except Exception:
                    pass
            return raw
    except Exception:
        return ""


def parse_candidate_line(line: str) -> Optional[Dict[str, object]]:
    uri = line.strip()
    if not uri or any(pat in uri.lower() for pat in FORBIDDEN_PATTERNS):
        return None
    lower = uri.lower()
    if not lower.startswith(SUPPORTED_SCHEMES):
        return None

    try:
        if lower.startswith("vmess://"):
            b64_part = uri[8:].strip()
            padded = b64_part + "=" * ((4 - len(b64_part) % 4) % 4)
            payload = json.loads(base64.urlsafe_b64decode(padded).decode("utf-8", errors="ignore"))
            host = str(payload.get("add", "")).strip()
            port = int(payload.get("port", 0))
            uid = str(payload.get("id", "")).strip()
            net = str(payload.get("net", "tcp")).strip().lower()
            tls = str(payload.get("tls", "")).strip().lower()
            if not is_safe_public_host(host) or not (1 <= port <= 65535) or not uid:
                return None
            fp_raw = f"vmess|{host.lower()}|{port}|{uid}|{net}|{tls}"
            return {
                "protocol": "VMESS",
                "host": host,
                "port": port,
                "uri": uri,
                "fingerprint": hashlib.sha256(fp_raw.encode("utf-8")).hexdigest()[:24],
                "network_compatibility": 0.88 if tls == "tls" else 0.75,
            }

        parsed = urllib.parse.urlSplit(uri) if hasattr(urllib.parse, "urlSplit") else urllib.parse.urlsplit(uri)
        scheme = parsed.scheme.lower()
        if "@" not in parsed.netloc:
            return None
        cred, hostport = parsed.netloc.rsplit("@", 1)
        if not cred:
            return None
        if ":" not in hostport:
            return None
        host, port_str = hostport.rsplit(":", 1)
        port = int(port_str)
        if not is_safe_public_host(host) or not (1 <= port <= 65535):
            return None

        qs = urllib.parse.parse_qs(parsed.query)
        security = (qs.get("security", ["none"])[0] or "none").lower()
        pbk = qs.get("pbk", [""])[0]
        is_reality = scheme == "vless" and (security == "reality" or bool(pbk))
        if security == "reality" and not pbk:
            return None

        proto_label = "VLESS_REALITY" if is_reality else scheme.upper()
        fp_raw = f"{proto_label}|{host.lower()}|{port}|{cred}|{security}|{pbk}"
        compat = 1.0 if is_reality else (0.92 if security == "tls" else 0.78)
        return {
            "protocol": proto_label,
            "host": host.strip("[]"),
            "port": port,
            "uri": uri,
            "fingerprint": hashlib.sha256(fp_raw.encode("utf-8")).hexdigest()[:24],
            "network_compatibility": compat,
        }
    except Exception:
        return None


def measure_tcp_latency_ms(candidate: Dict[str, object]) -> Optional[Tuple[int, Dict[str, object]]]:
    host = str(candidate["host"])
    port = int(candidate["port"])
    start = time.monotonic()
    try:
        with socket.create_connection((host, port), timeout=TCP_TIMEOUT_SECONDS):
            elapsed_ms = int((time.monotonic() - start) * 1000)
            if 1 <= elapsed_ms <= 2500:
                return elapsed_ms, candidate
    except Exception:
        pass
    return None


def main() -> None:
    deduped: Dict[str, Dict[str, object]] = {}
    for source_url in PUBLIC_SOURCES:
        body = fetch_source(source_url)
        for line in body.splitlines():
            parsed = parse_candidate_line(line)
            if parsed and parsed["fingerprint"] not in deduped:
                deduped[str(parsed["fingerprint"])] = parsed

    candidates = list(deduped.values())[:250]
    validated: List[Dict[str, object]] = []

    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_PARALLEL_WORKERS) as pool:
        futures = [pool.submit(measure_tcp_latency_ms, c) for c in candidates]
        for fut in concurrent.futures.as_completed(futures):
            res = fut.result()
            if res is not None:
                latency_ms, cand = res
                cand_out = {
                    "protocol": cand["protocol"],
                    "uri": cand["uri"],
                    "latency_ms": latency_ms,
                    "network_compatibility": cand["network_compatibility"],
                }
                validated.append(cand_out)

    validated.sort(key=lambda item: (int(item.get("latency_ms", 9999)), -float(item.get("network_compatibility", 0.0))))
    top_candidates = validated[:MAX_OUTPUT_CANDIDATES]

    output_path = os.path.join("public", "api", "v1", "candidates.json")
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    if top_candidates:
        payload = {
            "schema_version": "1.0",
            "generated_at_epoch": int(time.time()),
            "generated_by": "MAXNET5G Lightweight Auto-VPN Collector",
            "supported_protocols": ["VLESS", "VLESS Reality", "VMess", "Trojan"],
            "candidates": top_candidates,
        }
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2)


if __name__ == "__main__":
    main()
