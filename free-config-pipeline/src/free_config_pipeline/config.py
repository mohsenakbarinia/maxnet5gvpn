"""Centralized pipeline configuration for MAXNET5G Free Config Pipeline."""

from dataclasses import dataclass, field
from typing import List, Set


@dataclass(frozen=True)
class FreePipelineConfig:
    """Tunables and validation limits for the collection and testing pipeline."""

    # Fetching limits (PART 2)
    size_cap_bytes: int = 10 * 1024 * 1024  # 10 MB per source stream
    max_redirects: int = 3
    connect_timeout_seconds: float = 10.0
    total_timeout_seconds: float = 30.0
    max_retries: int = 3
    backoff_factor_seconds: float = 1.0
    user_agent: str = "MAXNET5G-FreeConfigPipeline/1.0 (+https://github.com/maxnet5g)"

    # Diversity and deduplication limits
    max_configs_per_host_port: int = 3

    # Security policy
    allow_insecure_tls: bool = False

    # String hygiene field caps
    max_field_length_host: int = 253
    max_field_length_sni: int = 253
    max_field_length_path: int = 256
    max_field_length_service_name: int = 128
    max_field_length_password: int = 256
    max_field_length_spx: int = 64

    # Whitelists strictly aligned with bundled Xray-core v26.10.29 (see docs/free-vpn/00-audit.md)
    allowed_transports: Set[str] = field(
        default_factory=lambda: {"tcp", "ws", "grpc", "httpupgrade", "xhttp", "h2"}
    )
    allowed_tls_fingerprints: Set[str] = field(
        default_factory=lambda: {
            "chrome",
            "firefox",
            "safari",
            "ios",
            "android",
            "edge",
            "360",
            "qq",
            "random",
            "randomized",
        }
    )
    allowed_tls_alpn: Set[str] = field(
        default_factory=lambda: {"h2", "http/1.1", "h3"}
    )
    allowed_vless_flows: Set[str] = field(
        default_factory=lambda: {"", "xtls-rprx-vision", "xtls-rprx-vision-udp443"}
    )

    # Runner Testing Defaults (PART 3 Section 3.8)
    xray_version: str = "v26.10.29"
    # Pinned official release SHA-256 for Xray-linux-64.zip v26.10.29
    xray_sha256_linux_64: str = "72ec0b4e05b3eecfe0e159679234bfa1e43b1c676d1a1b55ea9470921473faec"

    stage_a_timeout_seconds: float = 3.0
    stage_b_rounds: int = 3
    stage_b_interval_seconds: float = 5.0
    run_budget_seconds: float = 25.0 * 60.0  # 25 min max execution budget

    # Quality and Rejection Thresholds
    latency_cap_ms: float = 3000.0
    min_success_ratio: float = 2.0 / 3.0  # At least 2/3 rounds successful
    max_batch_size: int = 100

    # Ranking, Expiry, and Diversity (Section 3.3)
    max_published_servers: int = 300
    seed_server_count: int = 100
    expire_consecutive_failures: int = 6
    expire_hours: float = 48.0
    max_class_percentage: float = 0.50  # Cap any (proto, network, security) class at 50%

    # Safety Net Thresholds (Section 3.5)
    min_servers: int = 20
    drop_threshold_percent: float = 70.0  # Reject publish if candidates drop > 70%

    # Manifest and Integrity
    manifest_validity_hours: int = 72
    probe_urls: List[str] = field(
        default_factory=lambda: [
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        ]
    )
    canary_url: str = ""
    expected_canary_sha256: str = ""


DEFAULT_CONFIG = FreePipelineConfig()
