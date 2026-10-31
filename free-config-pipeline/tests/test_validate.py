"""Unit tests for the static validation module."""

import json
from pathlib import Path
from typing import List
import unittest

from free_config_pipeline.models import FreeServer
from free_config_pipeline.rejection_codes import RejectionCode
from free_config_pipeline.validate import validate_server


class TestValidate(unittest.TestCase):

    def setUp(self):
        vectors_path = Path(__file__).parent.parent / "testdata" / "validate_vectors.json"
        with open(vectors_path, "r", encoding="utf-8") as f:
            self.vectors = json.load(f)

    def test_validation_vectors(self):
        """Validates all test vectors from validate_vectors.json."""
        for v in self.vectors:
            name = v["name"]
            server_dict = v["server"]
            is_valid = v["is_valid"]
            expected_rej = v["expected_rejection"]

            # Populate defaults for missing fields in vector
            full_dict = {
                "id": server_dict.get("id", "testid"),
                "protocol": server_dict.get("protocol", "VLESS"),
                "host": server_dict.get("host", "example.com"),
                "port": int(server_dict.get("port", 443)),
                "credential": server_dict.get("credential", "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"),
                "flow": server_dict.get("flow", ""),
                "encryption": server_dict.get("encryption", "none"),
                "network": server_dict.get("network", "tcp"),
                "security": server_dict.get("security", "tls"),
                "sni": server_dict.get("sni", ""),
                "alpn": server_dict.get("alpn", []),
                "fingerprint": server_dict.get("fingerprint", "chrome"),
                "realityPublicKey": server_dict.get("realityPublicKey", ""),
                "realityShortId": server_dict.get("realityShortId", ""),
                "realitySpiderPath": server_dict.get("realitySpiderPath", ""),
                "hostHeader": server_dict.get("hostHeader", ""),
                "path": server_dict.get("path", ""),
                "grpcServiceName": server_dict.get("grpcServiceName", ""),
                "grpcMode": server_dict.get("grpcMode", "gun"),
            }
            server = FreeServer.from_dict(full_dict)
            err = validate_server(server)

            if is_valid:
                self.assertIsNone(err, f"Vector {name} should be valid but got error: {err}")
            else:
                self.assertIsNotNone(err, f"Vector {name} should be invalid but passed")
                self.assertEqual(err.value, expected_rej, f"Vector {name} rejection mismatch")

    def test_fake_dns_resolver_detects_private_ips(self):
        """Verifies fake DNS resolver triggers HOST_RESOLVES_PRIVATE when hostname maps to private IP."""
        server = FreeServer(
            id="canon_dns",
            protocol="VLESS",
            host="legit-looking-domain.internal",
            port=443,
            credential="a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
            security="tls",
            network="tcp",
            sni="legit-looking-domain.internal",
        )

        # 1. Resolver returning private IPv4
        def private_resolver(_: str) -> List[str]:
            return ["192.168.1.100"]

        err = validate_server(server, dns_resolver=private_resolver)
        self.assertEqual(err, RejectionCode.HOST_RESOLVES_PRIVATE)

        # 2. Resolver returning CGNAT IPv4
        def cgnat_resolver(_: str) -> List[str]:
            return ["100.64.5.12"]

        err = validate_server(server, dns_resolver=cgnat_resolver)
        self.assertEqual(err, RejectionCode.HOST_RESOLVES_PRIVATE)

        # 3. Resolver returning public IP
        def public_resolver(_: str) -> List[str]:
            return ["93.184.216.34"]

        err = validate_server(server, dns_resolver=public_resolver)
        self.assertIsNone(err)


if __name__ == "__main__":
    unittest.main()
