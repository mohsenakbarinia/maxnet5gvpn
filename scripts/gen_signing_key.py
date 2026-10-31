#!/usr/bin/env python3
"""Script to generate ECDSA P-256 signing keys for MAXNET5G Free VPN manifest signing.

Outputs:
1. Private Key (PKCS#8 PEM) for GitHub Actions secret FREE_CONFIG_SIGNING_KEY.
2. Public Key (SubjectPublicKeyInfo Base64) for Android app embedding.
"""

from pathlib import Path
import sys

# Ensure src/ is on path
repo_root = Path(__file__).resolve().parent.parent
pipeline_src = repo_root / "free-config-pipeline" / "src"
if str(pipeline_src) not in sys.path:
    sys.path.insert(0, str(pipeline_src))

from free_config_pipeline.crypto_signing import extract_spki_base64, generate_keypair


def main():
    print("=" * 70)
    print("MAXNET5G ECDSA P-256 Signing Key Generator")
    print("=" * 70)

    priv_pem, pub_pem = generate_keypair()
    spki_b64 = extract_spki_base64(pub_pem)

    print("\n[1] PRIVATE KEY (Add this to GitHub Repository Secrets as FREE_CONFIG_SIGNING_KEY):")
    print("-" * 70)
    print(priv_pem.strip())
    print("-" * 70)

    print("\n[2] PUBLIC KEY PEM:")
    print("-" * 70)
    print(pub_pem.strip())
    print("-" * 70)

    print("\n[3] PUBLIC KEY SPKI BASE64 (For Android FreeVpnRepository / KeyStore embedding):")
    print("-" * 70)
    print(spki_b64)
    print("-" * 70)
    print("\nKey generation completed successfully.")


if __name__ == "__main__":
    main()
