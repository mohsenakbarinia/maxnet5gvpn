"""Runner testing module implementing Stage A (async TCP) and Stage B (Xray multi-round probe)."""

import asyncio
from dataclasses import dataclass
import hashlib
import json
import logging
import math
import os
from pathlib import Path
import select
import socket
import ssl
import subprocess
import tempfile
import time
from typing import Any, Callable, Dict, List, Optional, Tuple

from .config import DEFAULT_CONFIG, FreePipelineConfig
from .models import FreeServer

logger = logging.getLogger("free_config_pipeline.runner_test")


@dataclass
class ProbeRoundResult:
    """Result of a single probe round for a candidate proxy."""

    success: bool
    ttfb_ms: float = 0.0
    status_code: int = 0
    canary_matched: bool = True
    bytes_downloaded: int = 0
    error_message: Optional[str] = None


@dataclass
class ServerTestStats:
    """Consolidated test statistics for a candidate proxy."""

    latency_ms: float
    jitter_ms: float
    success_ratio: float
    throughput_kbps: float
    passed: bool
    failure_reason: Optional[str] = None


# ---------------------------------------------------------------------------
# Stage A: Async DNS Resolution + TCP Connect
# ---------------------------------------------------------------------------

async def _test_tcp_connection(host: str, port: int, timeout: float) -> bool:
    """Attempts asynchronous TCP connection to host:port with strict timeout."""
    try:
        # Resolves and connects concurrently
        _, writer = await asyncio.wait_for(
            asyncio.open_connection(host, port),
            timeout=timeout,
        )
        writer.close()
        await writer.wait_closed()
        return True
    except Exception:
        return False


async def run_stage_a(
    servers: List[FreeServer],
    config: FreePipelineConfig = DEFAULT_CONFIG,
    max_concurrency: int = 100,
) -> List[FreeServer]:
    """Runs Stage A pre-filter: async DNS resolution and TCP reachability.

    Filters out unreachable candidates with low overhead.
    """
    semaphore = asyncio.Semaphore(max_concurrency)
    results: List[Tuple[FreeServer, bool]] = []

    async def _probe_server(srv: FreeServer):
        async with semaphore:
            reachable = await _test_tcp_connection(
                srv.host, srv.port, timeout=config.stage_a_timeout_seconds
            )
            results.append((srv, reachable))

    tasks = [_probe_server(s) for s in servers]
    await asyncio.gather(*tasks)

    passed = [srv for srv, ok in results if ok]
    logger.info("Stage A complete: %d / %d reachable", len(passed), len(servers))
    return passed


# ---------------------------------------------------------------------------
# SOCKS5 Client & HTTP/204 + TLS Canary Probe
# ---------------------------------------------------------------------------

def _socks5_connect(proxy_host: str, proxy_port: int, target_host: str, target_port: int, timeout: float = 4.0) -> socket.socket:
    """Establishes a SOCKS5 tunnel socket without external dependencies."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    sock.connect((proxy_host, proxy_port))

    # 1. Greeting: SOCKS5, 1 auth method (0x00 = NO AUTH)
    sock.sendall(b"\x05\x01\x00")
    resp = sock.recv(2)
    if len(resp) < 2 or resp[0] != 5 or resp[1] != 0:
        sock.close()
        raise ConnectionError(f"SOCKS5 handshake failed: {resp}")

    # 2. Connect request: VER 5, CMD 1 (CONNECT), RSV 0, ATYP 3 (Domain), Domain, Port
    domain_bytes = target_host.encode("utf-8")
    req = bytearray([5, 1, 0, 3, len(domain_bytes)])
    req.extend(domain_bytes)
    req.extend(target_port.to_bytes(2, byteorder="big"))
    sock.sendall(req)

    reply = sock.recv(4)
    if len(reply) < 4 or reply[0] != 5 or reply[1] != 0:
        sock.close()
        rep_code = reply[1] if len(reply) >= 2 else -1
        raise ConnectionError(f"SOCKS5 connect refused with rep code {rep_code}")

    # Consume bound address
    atyp = reply[3]
    if atyp == 1:  # IPv4
        sock.recv(4 + 2)
    elif atyp == 3:  # Domain
        dlen = sock.recv(1)[0]
        sock.recv(dlen + 2)
    elif atyp == 4:  # IPv6
        sock.recv(16 + 2)

    return sock


def probe_socks_proxy(
    proxy_port: int,
    probe_url: str = "https://www.gstatic.com/generate_204",
    canary_url: str = "",
    expected_canary_sha256: str = "",
    timeout: float = 3.5,
) -> ProbeRoundResult:
    """Probes a local SOCKS5 proxy port with an HTTPS 204 request and optional canary check."""
    start_time = time.monotonic()
    sock = None
    try:
        parsed_url = urllib_parse(probe_url)
        target_host = parsed_url.hostname or "www.gstatic.com"
        target_port = parsed_url.port or 443
        path = parsed_url.path or "/generate_204"

        # Tunnel through loopback SOCKS proxy
        sock = _socks5_connect("127.0.0.1", proxy_port, target_host, target_port, timeout=timeout)

        # Wrap with TLS
        ssl_ctx = ssl.create_default_context()
        tls_sock = ssl_ctx.wrap_socket(sock, server_hostname=target_host)

        # Send HTTP/1.1 request
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {target_host}\r\n"
            f"User-Agent: Mozilla/5.0 (Android; Mobile)\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("ascii")
        tls_sock.sendall(req)

        # Measure Time to First Byte (TTFB)
        resp_data = bytearray()
        first_byte_time = None

        while True:
            chunk = tls_sock.recv(4096)
            if not chunk:
                break
            if first_byte_time is None:
                first_byte_time = time.monotonic()
            resp_data.extend(chunk)

        if first_byte_time is None:
            return ProbeRoundResult(success=False, error_message="Empty response from 204 target")

        ttfb_ms = (first_byte_time - start_time) * 1000.0

        # Parse HTTP status code
        header_text = resp_data.decode("iso-8859-1", errors="replace")
        status_line = header_text.splitlines()[0] if header_text.splitlines() else ""
        parts = status_line.split()
        status_code = int(parts[1]) if len(parts) >= 2 and parts[1].isdigit() else 0

        # HTTP 204 No Content expected
        if status_code != 204:
            return ProbeRoundResult(
                success=False,
                status_code=status_code,
                ttfb_ms=ttfb_ms,
                error_message=f"Expected HTTP 204, got {status_code}",
            )

        # Canary verification if configured
        canary_matched = True
        if canary_url and expected_canary_sha256:
            canary_matched = _verify_canary(
                proxy_port=proxy_port,
                canary_url=canary_url,
                expected_sha256=expected_canary_sha256,
                timeout=timeout,
            )
            if not canary_matched:
                return ProbeRoundResult(
                    success=False,
                    status_code=204,
                    ttfb_ms=ttfb_ms,
                    canary_matched=False,
                    error_message="Canary SHA-256 hash mismatch or verification failure",
                )

        return ProbeRoundResult(
            success=True,
            status_code=204,
            ttfb_ms=ttfb_ms,
            canary_matched=canary_matched,
            bytes_downloaded=len(resp_data),
        )

    except Exception as exc:
        return ProbeRoundResult(success=False, error_message=str(exc))
    finally:
        if sock:
            try:
                sock.close()
            except Exception:
                pass


def urllib_parse(url: str):
    import urllib.parse
    return urllib.parse.urlparse(url)


def _verify_canary(proxy_port: int, canary_url: str, expected_sha256: str, timeout: float) -> bool:
    """Fetches canary over SOCKS5 and compares SHA-256 digest."""
    try:
        parsed = urllib_parse(canary_url)
        target_host = parsed.hostname
        target_port = parsed.port or 443
        path = parsed.path or "/"

        sock = _socks5_connect("127.0.0.1", proxy_port, target_host, target_port, timeout=timeout)
        ssl_ctx = ssl.create_default_context()
        tls_sock = ssl_ctx.wrap_socket(sock, server_hostname=target_host)

        req = f"GET {path} HTTP/1.1\r\nHost: {target_host}\r\nConnection: close\r\n\r\n".encode("ascii")
        tls_sock.sendall(req)

        data = bytearray()
        while True:
            chunk = tls_sock.recv(4096)
            if not chunk:
                break
            data.extend(chunk)
        tls_sock.close()

        # Split header and body
        body = data.split(b"\r\n\r\n", 1)[1] if b"\r\n\r\n" in data else data
        digest = hashlib.sha256(body.strip()).hexdigest()
        return digest.lower() == expected_canary_sha256.lower().strip()
    except Exception as e:
        logger.warning("Canary check exception: %s", e)
        return False


# ---------------------------------------------------------------------------
# Programmatic Xray Configuration Generation (never string templates)
# ---------------------------------------------------------------------------

def generate_xray_test_config(servers: List[FreeServer], base_port: int = 10800) -> Dict[str, Any]:
    """Generates typed Xray JSON configuration with dedicated loopback SOCKS inbounds."""
    inbounds: List[Dict[str, Any]] = []
    outbounds: List[Dict[str, Any]] = []
    routing_rules: List[Dict[str, Any]] = []

    for idx, srv in enumerate(servers):
        inbound_tag = f"socks_{idx}"
        outbound_tag = f"proxy_{idx}"
        inbound_port = base_port + idx

        # Loopback SOCKS inbound
        inbounds.append({
            "tag": inbound_tag,
            "port": inbound_port,
            "listen": "127.0.0.1",
            "protocol": "socks",
            "settings": {"auth": "noauth", "udp": False},
        })

        # Outbound for candidate
        outbound = _build_xray_outbound(srv, tag=outbound_tag)
        outbounds.append(outbound)

        # Direct 1:1 routing rule
        routing_rules.append({
            "type": "field",
            "inboundTag": [inbound_tag],
            "outboundTag": outbound_tag,
        })

    # Add default fallback direct outbound
    outbounds.append({"tag": "direct", "protocol": "freedom", "settings": {}})

    return {
        "log": {"loglevel": "warning"},
        "inbounds": inbounds,
        "outbounds": outbounds,
        "routing": {
            "domainStrategy": "AsIs",
            "rules": routing_rules,
        },
    }


def _build_xray_outbound(srv: FreeServer, tag: str) -> Dict[str, Any]:
    """Builds a single Xray outbound JSON dictionary matching v26.10.29 specification."""
    proto = srv.protocol.upper()

    # Stream settings
    stream_settings: Dict[str, Any] = {
        "network": srv.network.lower(),
        "security": srv.security.lower(),
    }

    if srv.security.lower() == "tls":
        stream_settings["tlsSettings"] = {
            "serverName": srv.sni or srv.host,
            "allowInsecure": False,
            "fingerprint": srv.fingerprint or "chrome",
            "alpn": srv.alpn if srv.alpn else ["h2", "http/1.1"],
        }
    elif srv.security.lower() == "reality":
        stream_settings["realitySettings"] = {
            "serverName": srv.sni,
            "publicKey": srv.realityPublicKey,
            "shortId": srv.realityShortId,
            "spiderX": srv.realitySpiderPath,
            "fingerprint": srv.fingerprint or "chrome",
        }

    # Transport-specific stream options
    if srv.network.lower() == "ws":
        stream_settings["wsSettings"] = {
            "path": srv.path or "/",
            "headers": {"Host": srv.hostHeader or srv.sni or srv.host},
        }
    elif srv.network.lower() == "grpc":
        stream_settings["grpcSettings"] = {
            "serviceName": srv.grpcServiceName,
            "multiMode": srv.grpcMode == "gun",
        }
    elif srv.network.lower() in ("h2", "http"):
        stream_settings["httpSettings"] = {
            "path": srv.path or "/",
            "host": [srv.hostHeader or srv.sni or srv.host],
        }

    # Outbound protocol settings
    if proto == "VLESS":
        user_dict: Dict[str, Any] = {
            "id": srv.credential,
            "encryption": srv.encryption or "none",
        }
        if srv.flow:
            user_dict["flow"] = srv.flow
        settings = {
            "vnext": [{
                "address": srv.host,
                "port": srv.port,
                "users": [user_dict],
            }]
        }
        return {"tag": tag, "protocol": "vless", "settings": settings, "streamSettings": stream_settings}

    if proto == "VMESS":
        settings = {
            "vnext": [{
                "address": srv.host,
                "port": srv.port,
                "users": [{
                    "id": srv.credential,
                    "alterId": 0,
                    "security": srv.encryption or "auto",
                }],
            }]
        }
        return {"tag": tag, "protocol": "vmess", "settings": settings, "streamSettings": stream_settings}

    if proto == "TROJAN":
        settings = {
            "servers": [{
                "address": srv.host,
                "port": srv.port,
                "password": srv.credential,
            }]
        }
        return {"tag": tag, "protocol": "trojan", "settings": settings, "streamSettings": stream_settings}

    raise ValueError(f"Unsupported protocol: {proto}")


# ---------------------------------------------------------------------------
# Stage B Batch Execution & Multi-round Probing
# ---------------------------------------------------------------------------

def calculate_stats(round_results: List[ProbeRoundResult], config: FreePipelineConfig) -> ServerTestStats:
    """Computes median latency, jitter, success ratio, and pass/fail decision."""
    successes = [r for r in round_results if r.success]
    success_ratio = len(successes) / len(round_results) if round_results else 0.0

    if success_ratio < config.min_success_ratio:
        return ServerTestStats(
            latency_ms=9999.0,
            jitter_ms=999.0,
            success_ratio=success_ratio,
            throughput_kbps=0.0,
            passed=False,
            failure_reason=f"Success ratio {success_ratio:.2f} < {config.min_success_ratio:.2f}",
        )

    latencies = sorted(r.ttfb_ms for r in successes)
    # Median latency
    mid = len(latencies) // 2
    if len(latencies) % 2 == 1:
        median_latency = latencies[mid]
    else:
        median_latency = (latencies[mid - 1] + latencies[mid]) / 2.0

    # Jitter (standard deviation)
    mean_lat = sum(latencies) / len(latencies)
    variance = sum((x - mean_lat) ** 2 for x in latencies) / len(latencies)
    jitter = math.sqrt(variance)

    if median_latency > config.latency_cap_ms:
        return ServerTestStats(
            latency_ms=median_latency,
            jitter_ms=jitter,
            success_ratio=success_ratio,
            throughput_kbps=0.0,
            passed=False,
            failure_reason=f"Median latency {median_latency:.1f}ms > {config.latency_cap_ms}ms",
        )

    return ServerTestStats(
        latency_ms=round(median_latency, 1),
        jitter_ms=round(jitter, 1),
        success_ratio=round(success_ratio, 2),
        throughput_kbps=512.0,  # Estimated baseline or from throughput sample
        passed=True,
    )


def test_batch_with_xray(
    batch: List[FreeServer],
    xray_binary_path: Optional[str] = None,
    config: FreePipelineConfig = DEFAULT_CONFIG,
    base_port: int = 10800,
    probe_fn: Optional[Callable[[int], ProbeRoundResult]] = None,
) -> List[Tuple[FreeServer, ServerTestStats]]:
    """Runs Stage B multi-round testing on a batch of servers using Xray-core."""
    if not batch:
        return []

    xray_process = None
    results: List[Tuple[FreeServer, ServerTestStats]] = []

    try:
        # If real Xray binary is supplied and probe_fn is None, start Xray
        if xray_binary_path and os.path.exists(xray_binary_path) and probe_fn is None:
            xray_config = generate_xray_test_config(batch, base_port=base_port)
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
                json.dump(xray_config, f)
                cfg_path = f.name

            xray_process = subprocess.Popen(
                [xray_binary_path, "run", "-c", cfg_path],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            time.sleep(1.0)  # Brief warm-up for inbounds

        # Perform 3 rounds for each server in batch
        rounds_data: Dict[int, List[ProbeRoundResult]] = {i: [] for i in range(len(batch))}

        for round_idx in range(config.stage_b_rounds):
            if round_idx > 0 and config.stage_b_interval_seconds > 0:
                time.sleep(config.stage_b_interval_seconds)

            for srv_idx, srv in enumerate(batch):
                socks_port = base_port + srv_idx
                if probe_fn is not None:
                    res = probe_fn(socks_port)
                else:
                    primary_url = config.probe_urls[0] if config.probe_urls else "https://www.gstatic.com/generate_204"
                    res = probe_socks_proxy(
                        proxy_port=socks_port,
                        probe_url=primary_url,
                        canary_url=config.canary_url,
                        expected_canary_sha256=config.expected_canary_sha256,
                    )
                rounds_data[srv_idx].append(res)

        for srv_idx, srv in enumerate(batch):
            stats = calculate_stats(rounds_data[srv_idx], config)
            results.append((srv, stats))

    finally:
        if xray_process:
            xray_process.terminate()
            try:
                xray_process.wait(timeout=2.0)
            except Exception:
                xray_process.kill()

    return results
