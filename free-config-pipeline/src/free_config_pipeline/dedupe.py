"""Deduplication and diversity cap module for FreeServer candidates."""

from collections import defaultdict
from typing import Dict, List, Tuple

from .config import DEFAULT_CONFIG, FreePipelineConfig
from .models import FreeServer
from .rejection_codes import RejectionCode


def deduplicate_and_cap(
    servers: List[FreeServer],
    config: FreePipelineConfig = DEFAULT_CONFIG,
) -> Tuple[List[FreeServer], Dict[RejectionCode, int]]:
    """Deduplicates servers by canonical ID and applies (host, port) diversity caps.

    Returns:
        A tuple of (deduped_servers, rejection_counts)
    """
    seen_ids = set()
    host_port_counts: Dict[Tuple[str, int], int] = defaultdict(int)

    unique_servers: List[FreeServer] = []
    rejections: Dict[RejectionCode, int] = defaultdict(int)

    for server in servers:
        # Check canonical ID deduplication
        if server.id in seen_ids:
            rejections[RejectionCode.DUPLICATE_CONFIG] += 1
            continue

        # Check diversity cap per (host, port)
        hp_key = (server.host.lower(), server.port)
        if host_port_counts[hp_key] >= config.max_configs_per_host_port:
            rejections[RejectionCode.DIVERSITY_CAP_EXCEEDED] += 1
            continue

        # Accept server
        seen_ids.add(server.id)
        host_port_counts[hp_key] += 1
        unique_servers.append(server)

    return unique_servers, dict(rejections)
