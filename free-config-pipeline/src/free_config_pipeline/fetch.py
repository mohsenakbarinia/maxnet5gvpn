"""Robust streaming fetcher with size capping, redirects limit and exponential backoff."""

import base64
import json
import logging
import re
import socket
import ssl
import time
from typing import Any, Dict, List, Optional, Tuple
import urllib.error
import urllib.parse
import urllib.request

from .config import DEFAULT_CONFIG, FreePipelineConfig

logger = logging.getLogger(__name__)


class SizeCapExceededError(Exception):
    """Raised when streaming response exceeds allowed byte limit."""
    pass


class TooManyRedirectsError(Exception):
    """Raised when redirect limit is exceeded."""
    pass


class InsecureRedirectError(Exception):
    """Raised when redirect attempts to downgrade protocol."""
    pass


class _CountingRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Redirect handler that enforces max redirect hops and protocols."""

    def __init__(self, max_redirects: int = 3, enforce_https: bool = False):
        super().__init__()
        self.max_redirects = max_redirects
        self.enforce_https = enforce_https
        self.redirect_count = 0

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        self.redirect_count += 1
        if self.redirect_count > self.max_redirects:
            raise TooManyRedirectsError(f"Exceeded max redirects ({self.max_redirects})")

        parsed = urllib.parse.urlparse(newurl)
        if self.enforce_https and parsed.scheme.lower() != "https":
            raise InsecureRedirectError(f"Insecure redirect to {parsed.scheme}")

        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_raw_content(
    url: str,
    config: FreePipelineConfig = DEFAULT_CONFIG,
    enforce_https: bool = False,
) -> bytes:
    """Streams content from URL with strict size cap, timeouts, and exponential backoff retry.

    Aborts immediately during streaming if the transferred bytes exceed size_cap_bytes.
    """
    last_error: Optional[Exception] = None

    for attempt in range(config.max_retries):
        try:
            redirect_handler = _CountingRedirectHandler(
                max_redirects=config.max_redirects,
                enforce_https=enforce_https,
            )
            opener = urllib.request.build_opener(redirect_handler)
            req = urllib.request.Request(
                url=url,
                headers={"User-Agent": config.user_agent},
            )

            # Enforce connect timeout
            with opener.open(req, timeout=config.connect_timeout_seconds) as resp:
                # Check Content-Length header if provided
                cl_header = resp.headers.get("Content-Length")
                if cl_header:
                    try:
                        content_length = int(cl_header.strip())
                        if content_length > config.size_cap_bytes:
                            raise SizeCapExceededError(
                                f"Content-Length {content_length} exceeds size cap {config.size_cap_bytes}"
                            )
                    except ValueError:
                        pass

                # Stream in chunks while tracking total size
                chunks: List[bytes] = []
                total_bytes = 0
                chunk_size = 64 * 1024  # 64 KB
                start_time = time.monotonic()

                while True:
                    if (time.monotonic() - start_time) > config.total_timeout_seconds:
                        raise TimeoutError(f"Total stream duration exceeded {config.total_timeout_seconds}s")

                    chunk = resp.read(chunk_size)
                    if not chunk:
                        break

                    total_bytes += len(chunk)
                    if total_bytes > config.size_cap_bytes:
                        raise SizeCapExceededError(
                            f"Downloaded bytes ({total_bytes}) exceeded size cap ({config.size_cap_bytes})"
                        )
                    chunks.append(chunk)

                return b"".join(chunks)

        except (urllib.error.HTTPError, urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError) as exc:
            last_error = exc
            # Do not retry on client 4xx errors except 429
            if isinstance(exc, urllib.error.HTTPError) and 400 <= exc.code < 500 and exc.code != 429:
                raise exc

            if attempt < config.max_retries - 1:
                sleep_duration = config.backoff_factor_seconds * (2 ** attempt)
                logger.warning("Fetch attempt %d failed for %s: %s. Retrying in %.1fs...", attempt + 1, url, exc, sleep_duration)
                time.sleep(sleep_duration)
            else:
                raise exc
        except (SizeCapExceededError, TooManyRedirectsError, InsecureRedirectError):
            # Non-recoverable policy violations
            raise

    if last_error:
        raise last_error
    raise RuntimeError(f"Failed to fetch {url}")


def extract_configs_from_content(raw_bytes: bytes, content_format: str) -> List[str]:
    """Extracts raw candidate config items from downloaded payload according to format.

    Supported formats:
    - uri_list: newline-delimited share URIs
    - base64: base64-encoded newline-delimited share URIs
    - clash_yaml: YAML containing proxies list
    - singbox_json: JSON containing outbounds list
    """
    fmt = content_format.strip().lower()
    text = raw_bytes.decode("utf-8", errors="replace")

    if fmt == "base64":
        clean_b64 = text.strip().replace("\n", "").replace("\r", "")
        pad = len(clean_b64) % 4
        if pad:
            clean_b64 += "=" * (4 - pad)
        try:
            decoded = base64.b64decode(clean_b64).decode("utf-8", errors="replace")
            text = decoded
        except Exception:
            pass
        return [line.strip() for line in text.splitlines() if line.strip() and not line.strip().startswith("#")]

    if fmt == "uri_list":
        return [line.strip() for line in text.splitlines() if line.strip() and not line.strip().startswith("#")]

    if fmt == "singbox_json":
        try:
            data = json.loads(text)
            outbounds = data.get("outbounds", []) if isinstance(data, dict) else []
            results = []
            for item in outbounds:
                if isinstance(item, dict):
                    results.append(json.dumps(item))
            return results
        except Exception:
            return []

    if fmt == "clash_yaml":
        # Check if PyYAML is installed, else parse basic proxy items
        try:
            import yaml  # type: ignore
            data = yaml.safe_load(text)
            proxies = data.get("proxies", []) if isinstance(data, dict) else []
            return [json.dumps(p) for p in proxies if isinstance(p, dict)]
        except ImportError:
            # Fallback regex extraction of proxy lines or simple JSON
            items = []
            for block in re.split(r"\n\s*-\s*", text):
                clean_block = block.strip()
                if "type:" in clean_block or "server:" in clean_block:
                    items.append(clean_block)
            return items

    # Default fallback: split lines
    return [line.strip() for line in text.splitlines() if line.strip() and not line.strip().startswith("#")]
