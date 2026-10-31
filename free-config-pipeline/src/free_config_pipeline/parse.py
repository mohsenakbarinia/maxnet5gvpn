"""Parsing module for VLESS, VMess, and Trojan share links and JSON outbounds."""

import base64
import json
import re
from typing import Any, Dict, List, Optional, Tuple
import urllib.parse

from .models import compute_canonical_id, FreeServer
from .rejection_codes import RejectionCode


def _safe_b64decode(raw: str) -> Optional[bytes]:
    """Safely decodes standard or URL-safe base64 string, adding padding if missing."""
    clean = raw.strip().replace(" ", "").replace("\n", "").replace("\r", "")
    if not clean:
        return None
    # Fix padding
    missing_padding = len(clean) % 4
    if missing_padding:
        clean += "=" * (4 - missing_padding)
    try:
        return base64.b64decode(clean, validate=False)
    except Exception:
        try:
            return base64.urlsafe_b64decode(clean)
        except Exception:
            return None


def _clean_host(raw_host: str) -> str:
    """Strips brackets from IPv6 and trims whitespace."""
    host = raw_host.strip()
    if host.startswith("[") and host.endswith("]"):
        return host[1:-1].strip()
    return host


def parse_uri_or_json(
    raw_input: str,
    default_origin: str = "MANIFEST"
) -> Tuple[Optional[FreeServer], Optional[RejectionCode]]:
    """Parses a single configuration URI or VMess JSON string into a FreeServer model.

    Never raises an uncaught exception on malformed or malicious inputs.
    """
    text = raw_input.strip()
    if not text:
        return None, RejectionCode.SCHEME_MALFORMED

    lower = text.lower()

    # 1. Check for VMess base64 or JSON
    if lower.startswith("vmess://"):
        return _parse_vmess_uri(text, default_origin)

    if lower.startswith("{") and "add" in lower and "port" in lower:
        return _parse_vmess_json(text, default_origin)

    # 2. Check for VLESS or Trojan URI
    if lower.startswith("vless://"):
        return _parse_vless_uri(text, default_origin)

    if lower.startswith("trojan://"):
        return _parse_trojan_uri(text, default_origin)

    # 3. Handle known unsupported schemes
    unsupported_schemes = ("ss://", "hysteria2://", "hy2://", "wireguard://", "wg://", "tuic://", "socks://", "http://", "https://")
    if any(lower.startswith(s) for s in unsupported_schemes):
        return None, RejectionCode.SCHEME_UNSUPPORTED

    return None, RejectionCode.SCHEME_MALFORMED


def _parse_vmess_uri(uri: str, origin: str) -> Tuple[Optional[FreeServer], Optional[RejectionCode]]:
    body = uri[8:].strip()
    # Strip any comment / fragment
    if "#" in body:
        body = body.split("#", 1)[0].strip()

    decoded_bytes = _safe_b64decode(body)
    if not decoded_bytes:
        return None, RejectionCode.SCHEME_MALFORMED

    try:
        decoded_text = decoded_bytes.decode("utf-8", errors="replace")
        return _parse_vmess_json(decoded_text, origin)
    except Exception:
        return None, RejectionCode.SCHEME_MALFORMED


def _parse_vmess_json(json_text: str, origin: str) -> Tuple[Optional[FreeServer], Optional[RejectionCode]]:
    try:
        data = json.loads(json_text)
    except Exception:
        return None, RejectionCode.SCHEME_MALFORMED

    if not isinstance(data, dict):
        return None, RejectionCode.SCHEME_MALFORMED

    host = _clean_host(str(data.get("add", "")).strip())
    raw_port = str(data.get("port", "0")).strip()
    try:
        port = int(raw_port)
    except Exception:
        return None, RejectionCode.PORT_RANGE

    credential = str(data.get("id", "")).strip()

    # Network
    net = str(data.get("net", "tcp")).strip().lower()
    if net in ("ws", "websocket"):
        network = "ws"
    elif net in ("grpc", "gun"):
        network = "grpc"
    elif net in ("h2", "http", "http2"):
        network = "h2"
    elif net in ("httpupgrade",):
        network = "httpupgrade"
    elif net in ("xhttp", "splithttp"):
        network = "xhttp"
    else:
        network = "tcp"

    # Security
    tls = str(data.get("tls", "")).strip().lower()
    security = "tls" if tls in ("tls", "1", "true") else "none"

    sni = str(data.get("sni", data.get("host", ""))).strip()
    host_header = str(data.get("host", "")).strip()
    path = str(data.get("path", "")).strip()
    fp = str(data.get("fp", "chrome")).strip().lower() or "chrome"

    raw_alpn = str(data.get("alpn", "")).strip()
    alpn = [a.strip().lower() for a in raw_alpn.split(",") if a.strip()]

    # Scy & alterId
    raw_aid = str(data.get("aid", "0")).strip()
    try:
        alter_id = int(raw_aid)
    except Exception:
        alter_id = 0
    if alter_id != 0:
        return None, RejectionCode.VMESS_ALTER_ID_NONZERO

    scy = str(data.get("scy", "auto")).strip().lower()
    if scy in ("none", "zero"):
        return None, RejectionCode.VMESS_SECURITY_INVALID

    canon_id = compute_canonical_id(
        protocol="VMESS",
        host=host,
        port=port,
        credential=credential,
        flow="",
        network=network,
        security=security,
        sni=sni,
        host_header=host_header,
        path=path,
        grpc_service_name=path if network == "grpc" else "",
        reality_public_key="",
        reality_short_id="",
        fingerprint=fp,
        alpn=alpn,
    )

    server = FreeServer(
        id=canon_id,
        protocol="VMESS",
        host=host,
        port=port,
        credential=credential,
        flow="",
        encryption=scy,
        network=network,
        security=security,
        sni=sni,
        alpn=alpn,
        fingerprint=fp,
        realityPublicKey="",
        realityShortId="",
        realitySpiderPath="",
        hostHeader=host_header,
        path=path,
        grpcServiceName=path if network == "grpc" else "",
        grpcMode="gun",
        origin=origin,
    )
    return server, None


def _parse_vless_uri(uri: str, origin: str) -> Tuple[Optional[FreeServer], Optional[RejectionCode]]:
    try:
        # Strip scheme
        rest = uri[8:].strip()
        # Discard remark
        if "#" in rest:
            rest = rest.split("#", 1)[0].strip()

        # Split userinfo and hostport
        if "@" not in rest:
            return None, RejectionCode.SCHEME_MALFORMED

        credential, hostport_query = rest.rsplit("@", 1)
        credential = urllib.parse.unquote(credential).strip()

        # Split query string
        if "?" in hostport_query:
            hostport, query_str = hostport_query.split("?", 1)
        else:
            hostport, query_str = hostport_query, ""

        # Parse host and port
        host, port = _parse_host_port(hostport)
        if host is None or port is None:
            return None, RejectionCode.PORT_RANGE

        query_params = urllib.parse.parse_qs(query_str, keep_blank_values=True)
        q = {k.lower(): v[0].strip() for k, v in query_params.items() if v}

        # Check for allowInsecure
        insecure_str = q.get("allowinsecure", q.get("insecure", "0")).lower()
        if insecure_str in ("1", "true", "yes"):
            return None, RejectionCode.INSECURE_TLS

        # Network
        net = q.get("type", q.get("net", "tcp")).lower()
        if net in ("ws", "websocket"):
            network = "ws"
        elif net in ("grpc", "gun"):
            network = "grpc"
        elif net in ("h2", "http", "http2"):
            network = "h2"
        elif net in ("httpupgrade",):
            network = "httpupgrade"
        elif net in ("xhttp", "splithttp"):
            network = "xhttp"
        else:
            network = "tcp"

        # Security
        sec_str = q.get("security", "none").lower()
        pbk = q.get("pbk", "")
        if sec_str == "reality" or (pbk and sec_str in ("none", "")):
            security = "reality"
        elif sec_str in ("tls", "xtls"):
            security = "tls"
        else:
            security = "none"

        flow = q.get("flow", "").lower()
        encryption = q.get("encryption", "none").lower()
        sni = q.get("sni", q.get("peer", ""))
        host_header = q.get("host", "")
        path = urllib.parse.unquote(q.get("path", ""))
        service_name = q.get("servicename", q.get("service", ""))
        grpc_mode = q.get("mode", "gun").lower() or "gun"
        fp = q.get("fp", "chrome").lower() or "chrome"
        sid = q.get("sid", "").lower()
        spx = urllib.parse.unquote(q.get("spx", ""))

        raw_alpn = q.get("alpn", "")
        alpn = [a.strip().lower() for a in raw_alpn.split(",") if a.strip()]

        canon_id = compute_canonical_id(
            protocol="VLESS",
            host=host,
            port=port,
            credential=credential,
            flow=flow,
            network=network,
            security=security,
            sni=sni,
            host_header=host_header,
            path=path,
            grpc_service_name=service_name,
            reality_public_key=pbk,
            reality_short_id=sid,
            fingerprint=fp,
            alpn=alpn,
        )

        server = FreeServer(
            id=canon_id,
            protocol="VLESS",
            host=host,
            port=port,
            credential=credential,
            flow=flow,
            encryption=encryption,
            network=network,
            security=security,
            sni=sni,
            alpn=alpn,
            fingerprint=fp,
            realityPublicKey=pbk,
            realityShortId=sid,
            realitySpiderPath=spx,
            hostHeader=host_header,
            path=path,
            grpcServiceName=service_name,
            grpcMode=grpc_mode,
            origin=origin,
        )
        return server, None
    except Exception:
        return None, RejectionCode.SCHEME_MALFORMED


def _parse_trojan_uri(uri: str, origin: str) -> Tuple[Optional[FreeServer], Optional[RejectionCode]]:
    try:
        rest = uri[9:].strip()
        if "#" in rest:
            rest = rest.split("#", 1)[0].strip()

        if "@" not in rest:
            return None, RejectionCode.SCHEME_MALFORMED

        password, hostport_query = rest.rsplit("@", 1)
        password = urllib.parse.unquote(password).strip()

        if "?" in hostport_query:
            hostport, query_str = hostport_query.split("?", 1)
        else:
            hostport, query_str = hostport_query, ""

        host, port = _parse_host_port(hostport)
        if host is None or port is None:
            return None, RejectionCode.PORT_RANGE

        query_params = urllib.parse.parse_qs(query_str, keep_blank_values=True)
        q = {k.lower(): v[0].strip() for k, v in query_params.items() if v}

        insecure_str = q.get("allowinsecure", q.get("insecure", "0")).lower()
        if insecure_str in ("1", "true", "yes"):
            return None, RejectionCode.INSECURE_TLS

        net = q.get("type", q.get("net", "tcp")).lower()
        if net in ("ws", "websocket"):
            network = "ws"
        elif net in ("grpc", "gun"):
            network = "grpc"
        elif net in ("h2", "http", "http2"):
            network = "h2"
        elif net in ("httpupgrade",):
            network = "httpupgrade"
        elif net in ("xhttp", "splithttp"):
            network = "xhttp"
        else:
            network = "tcp"

        sec_str = q.get("security", "tls").lower()
        pbk = q.get("pbk", "")
        if sec_str == "reality" or pbk:
            security = "reality"
        elif sec_str in ("tls", ""):
            security = "tls"
        else:
            security = "none"

        sni = q.get("sni", q.get("peer", ""))
        host_header = q.get("host", "")
        path = urllib.parse.unquote(q.get("path", ""))
        service_name = q.get("servicename", q.get("service", ""))
        grpc_mode = q.get("mode", "gun").lower() or "gun"
        fp = q.get("fp", "chrome").lower() or "chrome"
        sid = q.get("sid", "").lower()
        spx = urllib.parse.unquote(q.get("spx", ""))

        raw_alpn = q.get("alpn", "")
        alpn = [a.strip().lower() for a in raw_alpn.split(",") if a.strip()]

        canon_id = compute_canonical_id(
            protocol="TROJAN",
            host=host,
            port=port,
            credential=password,
            flow="",
            network=network,
            security=security,
            sni=sni,
            host_header=host_header,
            path=path,
            grpc_service_name=service_name,
            reality_public_key=pbk,
            reality_short_id=sid,
            fingerprint=fp,
            alpn=alpn,
        )

        server = FreeServer(
            id=canon_id,
            protocol="TROJAN",
            host=host,
            port=port,
            credential=password,
            flow="",
            encryption="none",
            network=network,
            security=security,
            sni=sni,
            alpn=alpn,
            fingerprint=fp,
            realityPublicKey=pbk,
            realityShortId=sid,
            realitySpiderPath=spx,
            hostHeader=host_header,
            path=path,
            grpcServiceName=service_name,
            grpcMode=grpc_mode,
            origin=origin,
        )
        return server, None
    except Exception:
        return None, RejectionCode.SCHEME_MALFORMED


def _parse_host_port(hostport: str) -> Tuple[Optional[str], Optional[int]]:
    clean = hostport.strip()
    if not clean:
        return None, None

    if clean.startswith("["):
        # Bracketed IPv6: [::1]:443
        if "]" not in clean:
            return None, None
        host_part, rest = clean[1:].split("]", 1)
        host = host_part.strip()
        if not rest.startswith(":"):
            return None, None
        port_part = rest[1:].strip()
    else:
        # Standard host:port
        if ":" not in clean:
            return None, None
        host, port_part = clean.rsplit(":", 1)
        host = host.strip()

    try:
        port = int(port_part)
        return host, port
    except Exception:
        return None, None
