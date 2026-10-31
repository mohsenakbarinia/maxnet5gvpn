"""Integration test for the collection pipeline."""

import json
from pathlib import Path
import tempfile
import unittest

from free_config_pipeline.collect import run_collection_pipeline
from free_config_pipeline.config import FreePipelineConfig


class TestCollectIntegration(unittest.TestCase):

    def test_run_collection_pipeline_local(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)

            # Create a mock source file
            source_content = (
                "vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@vpn.example.com:443?encryption=none&security=reality&sni=dl.google.com&pbk=1111111111111111111111111111111111111111111&sid=1234&type=tcp#Node1\n"
                "vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@vpn.example.com:443?encryption=none&security=reality&sni=dl.google.com&pbk=1111111111111111111111111111111111111111111&sid=1234&type=tcp#NodeDuplicate\n"
                "vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@127.0.0.1:443?encryption=none&security=tls#Localhost\n"
                "ss://invalid-scheme@host:443\n"
            )
            mock_feed_file = tmp_path / "feed.txt"
            mock_feed_file.write_text(source_content, encoding="utf-8")

            sources_yml = tmp_path / "sources.yml"
            sources_yml.write_text(
                f"- name: test-local\n  url: file://{mock_feed_file.resolve()}\n  format: uri_list\n  enabled: true\n",
                encoding="utf-8",
            )

            out_candidates = tmp_path / "candidates.json"
            out_report = tmp_path / "report.json"

            servers, report = run_collection_pipeline(
                sources_file=sources_yml,
                output_candidates_file=out_candidates,
                output_report_file=out_report,
                config=FreePipelineConfig(),
            )

            # Verify outputs
            self.assertTrue(out_candidates.exists())
            self.assertTrue(out_report.exists())

            # Exactly 1 valid deduped server should remain (Node1; NodeDuplicate is deduped; Localhost rejected; SS rejected)
            self.assertEqual(len(servers), 1)
            self.assertEqual(servers[0].host, "vpn.example.com")

            # Check report
            self.assertEqual(report.total_fetched, 4)
            self.assertIn("HOST_LOOPBACK", report.rejection_histogram)
            self.assertIn("SCHEME_UNSUPPORTED", report.rejection_histogram)
            self.assertIn("DUPLICATE_CONFIG", report.rejection_histogram)


if __name__ == "__main__":
    unittest.main()
