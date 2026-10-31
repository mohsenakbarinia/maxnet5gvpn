"""Static validation module enforcing security, network hygiene and Xray compatibility."""

import base64
import ipaddress
import re
from typing import Callable, List, Optional
import uuid

from .config import DEFAULT_CONFIG, FreePipelineConfig
from .models import FreeServer
from .rejection_codes import RejectionCode

_RFC1123_LABEL_REGEX = re.compile(r"^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$", re.IGNORECASE)
_CGNAT_NET = ipaddress.ip_network("100.64.0.0/10")
_CONTROL_CHAR_REGEX = re.compile(r"[\x00-\x1f\x7f]")


def _has_control_chars(*strings: str) -> bool:
    """Returns True if any string contains ASCII control characters."""
    for s in strings:
        if s and _CONTROL_CHAR_REGEX.search(s):
            return True
    return False


def _check_ip_safety(ip_str: str) -> Optional[RejectionCode]:
    """Validates an IP literal against loopback, private, CGNAT, link-local, multicast, etc."""
    try:
        ip = ipaddress.ip_address(ip_str)
    except ValueError:
        return None  # Not an IP literal

    if ip.is_loopback:
        return RejectionCode.HOST_LOOPBACK
    if ip.is_link_local:
        return RejectionCode.HOST_LINK_LOCAL
    if ip.is_private:
        return RejectionCode.HOST_PRIVATE
    if ip.is_multicast:
        return RejectionCode.HOST_MULTICAST
    if ip.is_reserved:
        return RejectionCode.HOST_RESERVED
    if ip.is_unspecified:
        return RejectionCode.HOST_UNSPECIFIED

    # Check CGNAT specifically for IPv4
    if ip.version == 4 and ip in _CGNAT_NET:
        return RejectionCode.HOST_CGNAT

    # Check IPv4-mapped IPv6 (e.g. ::ffff:192.168.1.1)
    if ip.version == 6 and ip.ipv4_mapped:
        mapped_v4 = ip.ipv4_mapped
        if mapped_v4.is_loopback:
            return RejectionCode.HOST_LOOPBACK
        if mapped_v4.is_private:
            return RejectionCode.HOST_PRIVATE
        if mapped_v4 in _CGNAT_NET:
            return RejectionCode.HOST_CGNAT

    return None


def _is_valid_hostname(host: str) -> bool:
    """Validates RFC 1123 hostname syntax including IDN punycode handling."""
    if not host or len(host) > 253:
        return False

    # Convert IDN to punycode ASCII
    try:
        ascii_host = host.encode("idna").decode("ascii")
    except Exception:
        return False

    if len(ascii_host) > 253:
        return False

    # Strip trailing dot if present for FQDN
    clean = ascii_host[:-1] if ascii_host.endswith(".") else ascii_host
    labels = clean.split(".")
    if not labels or any(not label for label in labels):
        return False

    for label in labels:
        if len(label) > 63 or not _RFC1123_LABEL_REGEX.match(label):
            return False

    return True


def _is_valid_uuid(val: str) -> bool:
    """Verifies string is a valid standard UUID format."""
    try:
        u = uuid.UUID(val.strip())
        return str(u).lower() == val.strip().lower()
    except Exception:
        return False


def _is_valid_reality_pbk(pbk: str) -> bool:
    """Validates Reality x25519 32-byte public key (base64 or base64url)."""
    clean = pbk.strip()
    if not clean or len(clean) not in (43, 44):
        return False
    # Attempt base64url decode with padding
    pad = len(clean) % 4
    padded = clean + ("=" * (4 - pad) if pad else "")
    try:
        decoded = base64.urlsafe_b64decode(padded)
        return len(decoded) == 32
    except Exception:
        pass

    try:
        decoded = base64.b64decode(padded)
        return len(decoded) == 32
    except Exception:
        return False


def _is_valid_reality_sid(sid: str) -> bool:
    """Validates Reality shortId: even number of hex characters up to 16 hex chars (8 bytes)."""
    clean = sid.strip().lower()
    if not clean:
        return True  # Short ID is optional in Reality
    if len(clean) > 16 or len(clean) % 2 != 0:
        return False
    return bool(re.fullmatch(r"[0-9a-f]+", clean))


def validate_server(
    server: FreeServer,
    config: FreePipelineConfig = DEFAULT_CONFIG,
    dns_resolver: Optional[Callable[[str], List[str]]] = None,
) -> Optional[RejectionCode]:
    """Statically validates a FreeServer instance.

    Returns None if server is valid, or a RejectionCode enum explaining the rejection.
    """
    # 1. String hygiene & control characters
    if _has_control_chars(
        server.host,
        server.credential,
        server.sni,
        server.path,
        server.hostHeader,
        server.grpcServiceName,
        server.realityPublicKey,
        server.realityShortId,
        server.realitySpiderPath,
    ):
        return RejectionCode.CONTROL_CHARS

    # 2. Host validation
    host = server.host.strip()
    if not host:
        return RejectionCode.HOST_EMPTY
    if len(host) > config.max_field_length_host:
        return RejectionCode.HOST_TOO_LONG

    # Check if host is an IP literal
    ip_rejection = _check_ip_safety(host)
    if ip_rejection:
        return ip_rejection

    # If not an IP literal, validate RFC 1123 hostname
    try:
        ipaddress.ip_address(host)
        is_ip = True
    except ValueError:
        is_ip = False

    if not is_ip:
        if not _is_valid_hostname(host):
            return RejectionCode.HOST_INVALID

        # Optional DNS resolution safety check
        if dns_resolver is not None:
            try:
                resolved_ips = dns_resolver(host)
                for ip_str in resolved_ips:
                    dns_rejection = _check_ip_safety(ip_str)
                    if dns_rejection is not None:
                        return RejectionCode.HOST_RESOLVES_PRIVATE
            except Exception:
                # If DNS resolution throws an error during validation, reject or continue based on policy
                pass

    # 3. Port range
    if not (1 <= server.port <= 65535):
        return RejectionCode.PORT_RANGE

    # 4. Protocol & Credentials
    proto = server.protocol.strip().upper()
    if proto not in ("VLESS", "VMESS", "TROJAN"):
        return RejectionCode.SCHEME_UNSUPPORTED

    if not server.credential:
        return RejectionCode.CREDENTIAL_EMPTY

    if proto in ("VLESS", "VMESS"):
        if not _is_valid_uuid(server.credential):
            return RejectionCode.UUID_INVALID
    elif proto == "TROJAN":
        pwd = server.credential
        if len(pwd) > config.max_field_length_password:
            return RejectionCode.FIELD_TOO_LONG
        if not pwd.isprintable():
            return RejectionCode.TROJAN_PASSWORD_INVALID

    # Protocol-specific options
    if proto == "VLESS":
        if server.encryption.lower() != "none":
            return RejectionCode.VLESS_ENCRYPTION_UNSUPPORTED
    elif proto == "VMESS":
        if server.encryption.lower() in ("none", "zero"):
            return RejectionCode.VMESS_SECURITY_INVALID

    # 5. Security & TLS / Reality
    sec = server.security.strip().lower()
    if sec == "none":
        return RejectionCode.SECURITY_NONE
    if sec not in ("tls", "reality"):
        return RejectionCode.SECURITY_NONE

    if sec == "reality":
        if not _is_valid_reality_pbk(server.realityPublicKey):
            return RejectionCode.REALITY_PBK_INVALID
        if not server.sni.strip():
            return RejectionCode.REALITY_SNI_MISSING
        if not _is_valid_reality_sid(server.realityShortId):
            return RejectionCode.REALITY_SID_INVALID
        if len(server.realitySpiderPath) > config.max_field_length_spx:
            return RejectionCode.REALITY_SPX_TOO_LONG
        if server.flow.lower() not in config.allowed_vless_flows:
            return RejectionCode.REALITY_FLOW_INVALID

    # 6. TLS fingerprint & ALPN
    fp = server.fingerprint.strip().lower()
    if fp and fp not in config.allowed_tls_fingerprints:
        return RejectionCode.TLS_FINGERPRINT_INVALID

    for alpn_item in server.alpn:
        if alpn_item.lower() not in config.allowed_tls_alpn:
            return RejectionCode.TLS_ALPN_INVALID

    # 7. Transport
    net = server.network.strip().lower()
    if net not in config.allowed_transports:
        return RejectionCode.TRANSPORT_UNSUPPORTED

    # 8. Field lengths
    if len(server.sni) > config.max_field_length_sni:
        return RejectionCode.FIELD_TOO_LONG
    if len(server.path) > config.max_field_length_path:
        return RejectionCode.FIELD_TOO_LONG
    if len(server.grpcServiceName) > config.max_field_length_service_name:
        return RejectionCode.FIELD_TOO_LONG

    return None
