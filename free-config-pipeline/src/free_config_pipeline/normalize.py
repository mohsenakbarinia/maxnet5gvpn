"""Normalization module for standardizing FreeServer representations."""

from typing import List
from .models import compute_canonical_id, FreeServer


def normalize_server(server: FreeServer) -> FreeServer:
    """Normalizes a FreeServer instance into canonical field encodings and recomputes canonical ID."""
    # Convert IDN hostname to punycode ASCII
    raw_host = server.host.strip()
    try:
        norm_host = raw_host.encode("idna").decode("ascii").lower()
    except Exception:
        norm_host = raw_host.lower()

    # Lowercase case-insensitive fields
    norm_protocol = server.protocol.strip().upper()
    norm_port = int(server.port)
    norm_credential = server.credential.strip()
    norm_flow = server.flow.strip().lower()
    norm_encryption = server.encryption.strip().lower()
    norm_network = server.network.strip().lower()
    norm_security = server.security.strip().lower()
    norm_sni = server.sni.strip().lower()

    # ALPN: sorted unique lowercased
    cleaned_alpn: List[str] = sorted(
        list({a.strip().lower() for a in server.alpn if a.strip()})
    )

    norm_fp = server.fingerprint.strip().lower() or "chrome"
    norm_pbk = server.realityPublicKey.strip()
    norm_sid = server.realityShortId.strip().lower()
    norm_spx = server.realitySpiderPath.strip()
    norm_host_header = server.hostHeader.strip()
    norm_path = server.path.strip()
    norm_grpc_svc = server.grpcServiceName.strip()
    norm_grpc_mode = server.grpcMode.strip().lower() or "gun"

    canon_id = compute_canonical_id(
        protocol=norm_protocol,
        host=norm_host,
        port=norm_port,
        credential=norm_credential,
        flow=norm_flow,
        network=norm_network,
        security=norm_security,
        sni=norm_sni,
        host_header=norm_host_header,
        path=norm_path,
        grpc_service_name=norm_grpc_svc,
        reality_public_key=norm_pbk,
        reality_short_id=norm_sid,
        fingerprint=norm_fp,
        alpn=cleaned_alpn,
    )

    return FreeServer(
        id=canon_id,
        protocol=norm_protocol,
        host=norm_host,
        port=norm_port,
        credential=norm_credential,
        flow=norm_flow,
        encryption=norm_encryption,
        network=norm_network,
        security=norm_security,
        sni=norm_sni,
        alpn=cleaned_alpn,
        fingerprint=norm_fp,
        realityPublicKey=norm_pbk,
        realityShortId=norm_sid,
        realitySpiderPath=norm_spx,
        hostHeader=norm_host_header,
        path=norm_path,
        grpcServiceName=norm_grpc_svc,
        grpcMode=norm_grpc_mode,
        pipelineStats=server.pipelineStats,
        origin=server.origin,
    )
