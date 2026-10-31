"""Unit tests for the fetch module with a local HTTP test server."""

import base64
from http.server import BaseHTTPRequestHandler, HTTPServer
import threading
import time
import unittest

from free_config_pipeline.config import FreePipelineConfig
from free_config_pipeline.fetch import (
    extract_configs_from_content,
    fetch_raw_content,
    SizeCapExceededError,
    TooManyRedirectsError,
)


class MockHTTPHandler(BaseHTTPRequestHandler):
    flaky_count = 0

    def log_message(self, format, *args):
        # Suppress server logging during tests
        pass

    def do_GET(self):
        if self.path == "/normal":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"vless://uuid@example.com:443\ntrojan://pwd@example.com:443\n")

        elif self.path == "/base64":
            content = b"vless://uuid@example.com:443\n"
            b64_content = base64.b64encode(content)
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b64_content)

        elif self.path == "/huge":
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.end_headers()
            # Send 200 KB in chunks; ignore client disconnect when size cap triggers
            try:
                for _ in range(20):
                    self.wfile.write(b"X" * 10240)
            except (BrokenPipeError, ConnectionResetError):
                pass

        elif self.path.startswith("/redirect"):
            # Redirect to next until hop 5
            try:
                hop = int(self.path.split("=")[-1])
            except Exception:
                hop = 1
            self.send_response(302)
            self.send_header("Location", f"/redirect?hop={hop + 1}")
            self.end_headers()

        elif self.path == "/flaky":
            MockHTTPHandler.flaky_count += 1
            if MockHTTPHandler.flaky_count == 1:
                self.send_response(500)
                self.end_headers()
            else:
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.end_headers()
                self.wfile.write(b"recovered")
        else:
            self.send_response(404)
            self.end_headers()


class TestFetch(unittest.TestCase):
    server: HTTPServer
    server_thread: threading.Thread
    port: int

    @classmethod
    def setUpClass(cls):
        # Bind to ephemeral port on 127.0.0.1
        cls.server = HTTPServer(("127.0.0.1", 0), MockHTTPHandler)
        cls.port = cls.server.server_address[1]
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.server_thread.join()

    def test_fetch_normal_and_extract_uri_list(self):
        url = f"http://127.0.0.1:{self.port}/normal"
        raw = fetch_raw_content(url)
        items = extract_configs_from_content(raw, "uri_list")
        self.assertEqual(len(items), 2)
        self.assertTrue(items[0].startswith("vless://"))

    def test_fetch_and_extract_base64(self):
        url = f"http://127.0.0.1:{self.port}/base64"
        raw = fetch_raw_content(url)
        items = extract_configs_from_content(raw, "base64")
        self.assertEqual(len(items), 1)
        self.assertTrue(items[0].startswith("vless://"))

    def test_size_cap_abort(self):
        url = f"http://127.0.0.1:{self.port}/huge"
        # Configure small 10KB cap
        config = FreePipelineConfig(size_cap_bytes=10240)
        with self.assertRaises(SizeCapExceededError):
            fetch_raw_content(url, config=config)

    def test_redirect_limit(self):
        url = f"http://127.0.0.1:{self.port}/redirect?hop=1"
        config = FreePipelineConfig(max_redirects=3)
        with self.assertRaises(TooManyRedirectsError):
            fetch_raw_content(url, config=config)

    def test_retry_on_500(self):
        MockHTTPHandler.flaky_count = 0
        url = f"http://127.0.0.1:{self.port}/flaky"
        config = FreePipelineConfig(max_retries=3, backoff_factor_seconds=0.1)
        content = fetch_raw_content(url, config=config)
        self.assertEqual(content, b"recovered")


if __name__ == "__main__":
    unittest.main()
