"""Unit tests for ECDSA P-256 signing and verification."""

import unittest

from free_config_pipeline.crypto_signing import generate_keypair, sign_bytes, verify_signature


class TestCryptoSigning(unittest.TestCase):

    def test_keypair_generation_and_valid_signature(self):
        priv_pem, pub_pem = generate_keypair()
        self.assertIn("BEGIN PRIVATE KEY", priv_pem)
        self.assertIn("BEGIN PUBLIC KEY", pub_pem)

        data = b'{"hello": "world", "schema": 1}'
        sig_b64 = sign_bytes(data, priv_pem)
        self.assertTrue(len(sig_b64) > 30)

        # Verification must succeed
        valid = verify_signature(data, sig_b64, pub_pem)
        self.assertTrue(valid)

    def test_tampered_data_fails_verification(self):
        priv_pem, pub_pem = generate_keypair()
        data = b'{"sequence": 42}'
        sig_b64 = sign_bytes(data, priv_pem)

        # Alter one byte in payload
        tampered_data = b'{"sequence": 43}'
        self.assertFalse(verify_signature(tampered_data, sig_b64, pub_pem))

    def test_tampered_signature_fails_verification(self):
        priv_pem, pub_pem = generate_keypair()
        data = b'Sample payload'
        sig_b64 = sign_bytes(data, priv_pem)

        # Invert or alter a character in base64 signature
        bad_char = "B" if sig_b64[10] != "B" else "A"
        tampered_sig = sig_b64[:10] + bad_char + sig_b64[11:]
        self.assertFalse(verify_signature(data, tampered_sig, pub_pem))

    def test_wrong_key_fails_verification(self):
        priv1, pub1 = generate_keypair()
        priv2, pub2 = generate_keypair()

        data = b'Authentication payload'
        sig1 = sign_bytes(data, priv1)

        # Verify with wrong public key
        self.assertFalse(verify_signature(data, sig1, pub2))


if __name__ == "__main__":
    unittest.main()
