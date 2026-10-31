"""Unit tests and fuzz testing for the parse module."""

import json
from pathlib import Path
import random
import string
import unittest

from free_config_pipeline.parse import parse_uri_or_json
from free_config_pipeline.rejection_codes import RejectionCode


class TestParse(unittest.TestCase):

    def setUp(self):
        vectors_path = Path(__file__).parent.parent / "testdata" / "parse_vectors.json"
        with open(vectors_path, "r", encoding="utf-8") as f:
            self.vectors = json.load(f)

    def test_parse_vectors(self):
        """Verifies standard positive and negative test vectors."""
        for v in self.vectors:
            name = v["name"]
            raw = v["raw"]
            should_parse = v["should_parse"]

            server, err = parse_uri_or_json(raw)

            if should_parse:
                self.assertIsNotNone(server, f"Vector {name} failed to parse: {err}")
                self.assertIsNone(err, f"Vector {name} had unexpected error: {err}")
                self.assertEqual(server.protocol, v["expected_protocol"], f"Vector {name} protocol mismatch")
                self.assertEqual(server.host, v["expected_host"], f"Vector {name} host mismatch")
                self.assertEqual(server.port, v["expected_port"], f"Vector {name} port mismatch")
                self.assertEqual(server.security, v["expected_security"], f"Vector {name} security mismatch")
                self.assertEqual(server.network, v["expected_network"], f"Vector {name} network mismatch")
            else:
                self.assertIsNone(server, f"Vector {name} should have failed but returned a server")
                self.assertIsNotNone(err, f"Vector {name} should have returned an error code")
                self.assertEqual(err.value, v["expected_rejection"], f"Vector {name} rejection code mismatch")

    def test_parser_fuzz_never_crashes(self):
        """Fuzz tests parser with random bytes, malformed URIs, null bytes and extreme lengths."""
        # 1. Null bytes and control characters
        fuzz_inputs = [
            "",
            "   ",
            "\x00\x00\x00",
            "vless://\x00@:443",
            "vmess://\xff\xfe\xfd",
            "trojan://password@\x00:443",
            "vless://" + "A" * 10000,
            "vmess://" + "B" * 10000,
            "trojan://" + "C" * 10000,
            "vless://not-a-uuid@host.com:999999999",
            "vmess://eyJpbnZhbGlkX2pzb24iOiB0cnVl",
            "vless://uuid@host.com:443?insecure=1",
            "vless://uuid@[::1:443",
            "vless://uuid@[:::1]:443",
        ]

        # 2. Random fuzz generation
        rng = random.Random(42)
        for _ in range(200):
            scheme = rng.choice(["vless://", "vmess://", "trojan://", "ss://", "garbage://", ""])
            random_str = "".join(rng.choices(string.printable, k=rng.randint(0, 300)))
            fuzz_inputs.append(scheme + random_str)

        for inp in fuzz_inputs:
            try:
                server, err = parse_uri_or_json(inp)
                # Ensure tuple is returned safely
                self.assertTrue((server is None and err is not None) or (server is not None and err is None))
            except Exception as e:
                self.fail(f"Parser crashed with unhandled exception on input {inp[:50]!r}: {e}")


if __name__ == "__main__":
    unittest.main()
