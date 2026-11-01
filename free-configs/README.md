# MAXNET5G Free VPN Repository Configurations

This directory is the **single authoritative remote source** for the **FREE VPN** mode in the MAXNET5G Android application (`https://github.com/mohsenakbarinia/maxnet5gvpn`).

## Files

- `configs.txt`: Plain-text list of free VPN configurations (one share link per line, or Base64-encoded block). Comments starting with `#` and blank lines are ignored.
- `manifest.json`: Cryptographically hashed metadata manifest verified by the Android app before accepting `configs.txt`. Contains:
  - `schema_version`: Manifest format version (`1`)
  - `version`: Monotonically increasing integer version (anti-rollback protection)
  - `updated_at`: UTC ISO-8601 timestamp
  - `config_count`: Number of validated configurations in `configs.txt`
  - `sha256`: Exact SHA-256 hex digest of `configs.txt`
  - `repository`: `"https://github.com/mohsenakbarinia/maxnet5gvpn"`
  - `configs_path`: `"free-configs/configs.txt"`

## Supported Protocols

- **VLESS** (`vless://`)
- **VLESS Reality** (`vless://...&security=reality&pbk=...`)
- **VMess** (`vmess://`)
- **Trojan** (`trojan://`)

## How to Update Free Configurations

1. Edit `free-configs/configs.txt` directly on GitHub or in a local clone and paste new `vless://`, `vmess://`, or `trojan://` links.
2. Commit and push to `main` (or open a pull request).
3. The GitHub Actions workflow (`.github/workflows/validate-free-configs.yml`) automatically:
   - Parses and validates every URI against Xray-core requirements
   - Rejects malformed links, loopback/private LAN addresses, and unsafe inputs
   - Deduplicates configurations by protocol + host + port + credential + transport + security parameters
   - Calculates the SHA-256 content hash of `free-configs/configs.txt`
   - Updates `free-configs/manifest.json` with the new `version`, `updated_at`, `config_count`, and `sha256`

To validate and update `manifest.json` locally:

```bash
python3 scripts/validate_free_configs.py --update-manifest
```
