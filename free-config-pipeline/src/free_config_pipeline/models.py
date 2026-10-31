"""Domain models for FreeServer matching PART 0 Section 0.5 contract."""

from dataclasses import asdict, dataclass, field
import hashlib
from typing import Any, Dict, List, Optional


@dataclass(frozen=True)
class FreeServer:
    """Immutable normalized representation of a Free VPN server candidate."""

    id: str  # 16 hex chars, canonical hash
    protocol: str  # VLESS | VMESS | TROJAN
    host: str
    port: int
    credential: str  # UUID for VLESS/VMess, Password for Trojan
    flow: str = ""
    encryption: str = "none"
    network: str = "tcp"  # tcp, ws, grpc, httpupgrade, xhttp, h2
    security: str = "tls"  # tls, reality
    sni: str = ""
    alpn: List[str] = field(default_factory=list)
    fingerprint: str = "chrome"
    realityPublicKey: str = ""
    realityShortId: str = ""
    realitySpiderPath: str = ""
    hostHeader: str = ""
    path: str = ""
    grpcServiceName: str = ""
    grpcMode: str = "gun"
    pipelineStats: Optional[Dict[str, Any]] = None
    origin: str = "MANIFEST"

    def to_dict(self) -> Dict[str, Any]:
        """Converts to JSON-serializable dictionary."""
        return asdict(self)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "FreeServer":
        """Instantiates from dictionary representation."""
        clean_data = dict(data)
        if "alpn" in clean_data and isinstance(clean_data["alpn"], list):
            clean_data["alpn"] = list(clean_data["alpn"])
        return cls(**clean_data)


def compute_canonical_id(
    protocol: str,
    host: str,
    port: int,
    credential: str,
    flow: str,
    network: str,
    security: str,
    sni: str,
    host_header: str,
    path: str,
    grpc_service_name: str,
    reality_public_key: str,
    reality_short_id: str,
    fingerprint: str,
    alpn: List[str],
) -> str:
    """Generates deterministic 16-hex canonical ID over connection-relevant fields only.

    Remarks and metadata are strictly excluded so labeling does not alter the ID.
    """
    sorted_alpn = ",".join(sorted(a.strip().lower() for a in alpn if a.strip()))
    raw = (
        f"{protocol.strip().upper()}|"
        f"{host.strip().lower()}|"
        f"{port}|"
        f"{credential.strip()}|"
        f"{flow.strip().lower()}|"
        f"{network.strip().lower()}|"
        f"{security.strip().lower()}|"
        f"{sni.strip().lower()}|"
        f"{host_header.strip().lower()}|"
        f"{path.strip()}|"
        f"{grpc_service_name.strip()}|"
        f"{reality_public_key.strip()}|"
        f"{reality_short_id.strip().lower()}|"
        f"{fingerprint.strip().lower()}|"
        f"{sorted_alpn}"
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]
