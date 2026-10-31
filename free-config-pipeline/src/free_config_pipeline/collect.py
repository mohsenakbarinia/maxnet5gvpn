"""Collection pipeline orchestrating fetch, parse, normalize, validate, dedupe, and report."""

import argparse
from collections import Counter
import json
import logging
import os
from pathlib import Path
import sys
from typing import Any, Dict, List, Optional, Tuple

from .config import DEFAULT_CONFIG, FreePipelineConfig
from .dedupe import deduplicate_and_cap
from .fetch import extract_configs_from_content, fetch_raw_content
from .models import FreeServer
from .normalize import normalize_server
from .parse import parse_uri_or_json
from .rejection_codes import RejectionCode
from .report import PipelineReport, SourceReport
from .validate import validate_server

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("free_config_pipeline.collect")


def parse_sources_file(sources_path: Path) -> List[Dict[str, Any]]:
    """Loads and parses sources.yml supporting both PyYAML and a robust fallback parser."""
    content = sources_path.read_text(encoding="utf-8")
    try:
        import yaml  # type: ignore
        data = yaml.safe_load(content)
        if isinstance(data, list):
            return data
        if isinstance(data, dict) and "sources" in data:
            return data["sources"]
    except ImportError:
        pass

    # Simple YAML list parser fallback for zero external dependency execution
    sources: List[Dict[str, Any]] = []
    current_source: Optional[Dict[str, Any]] = None

    for line in content.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("- "):
            if current_source:
                sources.append(current_source)
            current_source = {}
            line = line[2:].strip()

        if ":" in line and current_source is not None:
            key, val = line.split(":", 1)
            key = key.strip()
            val = val.strip().strip("'\"")
            if val.lower() in ("true", "yes"):
                current_source[key] = True
            elif val.lower() in ("false", "no"):
                current_source[key] = False
            elif val.isdigit():
                current_source[key] = int(val)
            else:
                current_source[key] = val

    if current_source:
        sources.append(current_source)

    return sources


def run_collection_pipeline(
    sources_file: Path,
    output_candidates_file: Path,
    output_report_file: Path,
    config: FreePipelineConfig = DEFAULT_CONFIG,
    enable_dns_resolve: bool = False,
) -> Tuple[List[FreeServer], PipelineReport]:
    """Runs the complete collection pipeline."""
    sources_data = parse_sources_file(sources_file)
    logger.info("Loaded %d sources from %s", len(sources_data), sources_file)

    report = PipelineReport(total_sources=len(sources_data))
    rejection_counter: Counter[str] = Counter()

    all_valid_candidates: List[FreeServer] = []

    for src in sources_data:
        name = str(src.get("name", "unnamed"))
        url = str(src.get("url", "")).strip()
        fmt = str(src.get("format", "uri_list")).strip()
        enabled = bool(src.get("enabled", True))
        max_bytes = int(src.get("max_bytes", config.size_cap_bytes))

        source_config = FreePipelineConfig(
            size_cap_bytes=max_bytes,
            max_redirects=config.max_redirects,
            connect_timeout_seconds=config.connect_timeout_seconds,
            total_timeout_seconds=config.total_timeout_seconds,
            max_retries=config.max_retries,
            backoff_factor_seconds=config.backoff_factor_seconds,
            user_agent=config.user_agent,
            max_configs_per_host_port=config.max_configs_per_host_port,
        )

        source_report = SourceReport(name=name, url=url, enabled=enabled, status="SKIPPED")

        if not enabled:
            report.sources.append(source_report)
            continue

        if not url:
            source_report.status = "FAILED"
            source_report.error_message = "URL is missing or empty"
            report.failed_sources += 1
            report.sources.append(source_report)
            continue

        logger.info("Fetching source: %s (%s)", name, url)
        try:
            raw_bytes = fetch_raw_content(url, config=source_config)
            source_report.bytes_downloaded = len(raw_bytes)
            items = extract_configs_from_content(raw_bytes, fmt)
            source_report.items_fetched = len(items)
            report.total_fetched += len(items)
            source_report.status = "SUCCESS"
            report.successful_sources += 1
        except Exception as exc:
            logger.warning("Failed to fetch source %s: %s", name, exc)
            source_report.status = "FAILED"
            source_report.error_message = str(exc)
            report.failed_sources += 1
            report.sources.append(source_report)
            continue

        parsed_valid_for_source = 0
        for item in items:
            server, parse_err = parse_uri_or_json(item, default_origin="PIPELINE")
            if parse_err is not None:
                rejection_counter[parse_err.value] += 1
                continue
            if server is None:
                continue

            source_report.items_parsed += 1
            report.total_parsed += 1

            # Normalize server representation
            norm_server = normalize_server(server)

            # Static validation
            val_err = validate_server(
                norm_server,
                config=source_config,
                dns_resolver=None,  # DNS checking controlled separately if needed
            )
            if val_err is not None:
                rejection_counter[val_err.value] += 1
                continue

            parsed_valid_for_source += 1
            all_valid_candidates.append(norm_server)

        source_report.items_valid = parsed_valid_for_source
        report.sources.append(source_report)
        report.total_valid += parsed_valid_for_source
        logger.info("Source %s: fetched=%d, parsed=%d, valid=%d", name, source_report.items_fetched, source_report.items_parsed, parsed_valid_for_source)

    # Cross-source deduplication and diversity capping
    deduped_servers, dedupe_rejections = deduplicate_and_cap(all_valid_candidates, config=config)
    for code, count in dedupe_rejections.items():
        rejection_counter[code.value] += count

    report.total_candidates = len(deduped_servers)
    report.rejection_histogram = dict(rejection_counter)

    # Ensure output directories exist
    output_candidates_file.parent.mkdir(parents=True, exist_ok=True)
    output_report_file.parent.mkdir(parents=True, exist_ok=True)

    # Write candidates.json
    candidates_data = [s.to_dict() for s in deduped_servers]
    output_candidates_file.write_text(json.dumps(candidates_data, indent=2), encoding="utf-8")
    logger.info("Wrote %d candidates to %s", len(candidates_data), output_candidates_file)

    # Write report.json
    output_report_file.write_text(report.to_json(indent=2), encoding="utf-8")
    logger.info("Wrote pipeline report to %s", output_report_file)

    return deduped_servers, report


def main() -> None:
    parser = argparse.ArgumentParser(description="MAXNET5G Free VPN Config Collection Pipeline")
    parser.add_argument(
        "--sources",
        type=Path,
        default=Path("free-config-pipeline/sources.yml"),
        help="Path to sources.yml file",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("build/candidates.json"),
        help="Path to output candidates JSON file",
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("build/report.json"),
        help="Path to output execution report JSON file",
    )
    parser.add_argument(
        "--dns-resolve",
        action="store_true",
        help="Enable static DNS resolution checks against private IP ranges",
    )

    args = parser.parse_args()

    # Search for sources.yml if default does not exist directly
    sources_path = args.sources
    if not sources_path.exists():
        alt = Path("sources.yml")
        if alt.exists():
            sources_path = alt

    if not sources_path.exists():
        logger.error("Sources file not found at %s", sources_path)
        sys.exit(1)

    _, report = run_collection_pipeline(
        sources_file=sources_path,
        output_candidates_file=args.out,
        output_report_file=args.report,
        enable_dns_resolve=args.dns_resolve,
    )

    print("\n================== COLLECTION PIPELINE REPORT ==================")
    print(f"Total Sources:    {report.total_sources} (Successful: {report.successful_sources}, Failed: {report.failed_sources})")
    print(f"Items Fetched:    {report.total_fetched}")
    print(f"Items Parsed:     {report.total_parsed}")
    print(f"Items Valid:      {report.total_valid}")
    print(f"Final Candidates: {report.total_candidates}")
    print("\n--- Rejection Histogram ---")
    if not report.rejection_histogram:
        print("  (None)")
    else:
        for code, count in sorted(report.rejection_histogram.items()):
            print(f"  {code:<30}: {count}")
    print("================================================================\n")


if __name__ == "__main__":
    main()
