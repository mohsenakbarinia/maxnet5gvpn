"""Cryptographic signing and verification for MAXNET5G Free VPN manifests.

Uses ECDSA P-256 (prime256v1 / secp256r1) with SHA-256.
Signatures are formatted as standard ASN.1 DER (compatible with Android Java SHA256withECDSA).
"""

import base64
import os
from pathlib import Path
import subprocess
import tempfile
from typing import Tuple

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import Prehashed
    _HAS_CRYPTOGRAPHY = True
except ImportError:
    _HAS_CRYPTOGRAPHY = False


def generate_keypair() -> Tuple[str, str]:
    """Generates an ECDSA P-256 keypair.

    Returns:
        (private_key_pem, public_key_pem)
    """
    if _HAS_CRYPTOGRAPHY:
        private_key = ec.generate_private_key(ec.SECP256R1())
        priv_pem = private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        ).decode("utf-8")
        pub_pem = private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode("utf-8")
        return priv_pem, pub_pem

    # Fallback to OpenSSL CLI
    with tempfile.TemporaryDirectory() as td:
        ec_priv_path = Path(td) / "ec_priv.pem"
        pkcs8_priv_path = Path(td) / "priv.pem"
        pub_path = Path(td) / "pub.pem"

        subprocess.check_call(
            ["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(ec_priv_path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        subprocess.check_call(
            ["openssl", "pkcs8", "-topk8", "-nocrypt", "-in", str(ec_priv_path), "-out", str(pkcs8_priv_path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        subprocess.check_call(
            ["openssl", "ec", "-in", str(pkcs8_priv_path), "-pubout", "-out", str(pub_path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

        return pkcs8_priv_path.read_text(encoding="utf-8"), pub_path.read_text(encoding="utf-8")


def sign_bytes(data: bytes, private_key_pem: str) -> str:
    """Signs raw bytes with ECDSA P-256 and SHA-256.

    Returns:
        Base64-encoded ASN.1 DER signature.
    """
    if _HAS_CRYPTOGRAPHY:
        priv_key = serialization.load_pem_private_key(private_key_pem.encode("utf-8"), password=None)
        if not isinstance(priv_key, ec.EllipticCurvePrivateKey):
            raise ValueError("Private key is not an EllipticCurvePrivateKey")
        der_sig = priv_key.sign(data, ec.ECDSA(hashes.SHA256()))
        return base64.b64encode(der_sig).decode("ascii")

    # Fallback to OpenSSL CLI
    with tempfile.TemporaryDirectory() as td:
        priv_path = Path(td) / "priv.pem"
        priv_path.write_text(private_key_pem, encoding="utf-8")

        data_path = Path(td) / "data.bin"
        data_path.write_bytes(data)

        sig_path = Path(td) / "sig.der"
        subprocess.check_call(
            ["openssl", "dgst", "-sha256", "-sign", str(priv_path), "-out", str(sig_path), str(data_path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return base64.b64encode(sig_path.read_bytes()).decode("ascii")


def verify_signature(data: bytes, signature_b64: str, public_key_pem: str) -> bool:
    """Verifies base64 DER signature against raw bytes using ECDSA P-256 / SHA-256."""
    try:
        der_sig = base64.b64decode(signature_b64.strip())
    except Exception:
        return False

    if _HAS_CRYPTOGRAPHY:
        try:
            pub_key = serialization.load_pem_public_key(public_key_pem.encode("utf-8"))
            if not isinstance(pub_key, ec.EllipticCurvePublicKey):
                return False
            pub_key.verify(der_sig, data, ec.ECDSA(hashes.SHA256()))
            return True
        except Exception:
            return False

    # Fallback to OpenSSL CLI
    with tempfile.TemporaryDirectory() as td:
        pub_path = Path(td) / "pub.pem"
        pub_path.write_text(public_key_pem, encoding="utf-8")

        data_path = Path(td) / "data.bin"
        data_path.write_bytes(data)

        sig_path = Path(td) / "sig.der"
        sig_path.write_bytes(der_sig)

        res = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(pub_path), "-signature", str(sig_path), str(data_path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return res.returncode == 0


def extract_spki_base64(public_key_pem: str) -> str:
    """Extracts raw Base64 SubjectPublicKeyInfo from PEM format for Android embedding."""
    lines = [
        line.strip()
        for line in public_key_pem.strip().splitlines()
        if line.strip() and not line.startswith("-----")
    ]
    return "".join(lines)
