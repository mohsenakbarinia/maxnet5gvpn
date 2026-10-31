"""Unit tests for deduplication and diversity capping."""

import unittest

from free_config_pipeline.config import FreePipelineConfig
from free_config_pipeline.dedupe import deduplicate_and_cap
from free_config_pipeline.models import compute_canonical_id, FreeServer
from free_config_pipeline.normalize import normalize_server
from free_config_pipeline.parse import parse_uri_or_json
from free_config_pipeline.rejection_codes import RejectionCode


class TestDedupe(unittest.TestCase):

    def test_canonical_id_ignores_remarks_and_query_order(self):
        """Remarks and comments should never change the canonical ID."""
        uri1 = "vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@node.example.com:443?encryption=none&security=tls&type=ws&path=%2Fws#RemarkOne"
        uri2 = "vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@node.example.com:443?type=ws&security=tls&encryption=none&path=%2Fws#DifferentRemark"

        srv1, _ = parse_uri_or_json(uri1)
        srv2, _ = parse_uri_or_json(uri2)

        self.assertIsNotNone(srv1)
        self.assertIsNotNone(srv2)

        norm1 = normalize_server(srv1)
        norm2 = normalize_server(srv2)

        self.assertEqual(norm1.id, norm2.id)

    def test_deduplication_filters_identical_ids(self):
        """Cross-source identical configs are deduplicated by canonical ID."""
        server = FreeServer(
            id="canon_same_id",
            protocol="VLESS",
            host="vpn1.example.com",
            port=443,
            credential="a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
            security="tls",
            network="tcp",
        )

        servers = [server, server, server]
        deduped, rejections = deduplicate_and_cap(servers)

        self.assertEqual(len(deduped), 1)
        self.assertEqual(rejections.get(RejectionCode.DUPLICATE_CONFIG), 2)

    def test_diversity_cap_enforces_maximum_per_host_port(self):
        """Limits configs sharing the same host and port to max_configs_per_host_port."""
        config = FreePipelineConfig(max_configs_per_host_port=3)

        servers = []
        for i in range(5):
            servers.append(
                FreeServer(
                    id=f"canon_id_{i}",
                    protocol="VLESS",
                    host="shared-host.com",
                    port=443,
                    credential=f"a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6{i}",
                    security="tls",
                    network="tcp",
                )
            )

        deduped, rejections = deduplicate_and_cap(servers, config=config)

        self.assertEqual(len(deduped), 3)
        self.assertEqual(rejections.get(RejectionCode.DIVERSITY_CAP_EXCEEDED), 2)


if __name__ == "__main__":
    unittest.main()
