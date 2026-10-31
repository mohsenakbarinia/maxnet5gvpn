"""Report generation module producing structured metrics and rejection histograms."""

from collections import Counter
from dataclasses import asdict, dataclass, field
import datetime
import json
from typing import Any, Dict, List, Optional

from .rejection_codes import RejectionCode


@dataclass
class SourceReport:
    """Metrics for an individual source endpoint."""

    name: str
    url: str
    enabled: bool
    status: str  # SUCCESS | FAILED | SKIPPED
    error_message: Optional[str] = None
    bytes_downloaded: int = 0
    items_fetched: int = 0
    items_parsed: int = 0
    items_valid: int = 0


@dataclass
class PipelineReport:
    """Consolidated pipeline execution report."""

    timestamp: str = field(
        default_factory=lambda: datetime.datetime.now(datetime.timezone.utc).isoformat()
    )
    total_sources: int = 0
    successful_sources: int = 0
    failed_sources: int = 0
    total_fetched: int = 0
    total_parsed: int = 0
    total_valid: int = 0
    total_candidates: int = 0
    sources: List[SourceReport] = field(default_factory=list)
    rejection_histogram: Dict[str, int] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "timestamp": self.timestamp,
            "total_sources": self.total_sources,
            "successful_sources": self.successful_sources,
            "failed_sources": self.failed_sources,
            "total_fetched": self.total_fetched,
            "total_parsed": self.total_parsed,
            "total_valid": self.total_valid,
            "total_candidates": self.total_candidates,
            "sources": [asdict(s) for s in self.sources],
            "rejection_histogram": dict(sorted(self.rejection_histogram.items())),
        }

    def to_json(self, indent: int = 2) -> str:
        return json.dumps(self.to_dict(), indent=indent)
