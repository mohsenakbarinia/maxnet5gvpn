# MAXNET5G Free VPN Config Pipeline

Automated collection, static validation, multi-round runner probing, historical scoring, cryptographic signing, and multi-mirror publishing pipeline for MAXNET5G Smart Auto Free VPN.

---

## 1. Architecture Overview

The pipeline operates in two distinct, decoupled phases:

### Pipeline I: Collection, Parsing, and Static Sanitization (`collect.py`)
1. **Streaming Fetcher (`fetch.py`)**: Fetches remote feeds with hard size caps (default 10 MB per source), connection timeouts (10s), total stream timeouts (30s), redirect hop limits (max 3), and exponential backoff retry.
2. **Safe Universal Parser (`parse.py`)**: Parses `vless://`, `vmess://` (JSON & Base64), and `trojan://` links. Fuzz-tested against malformed and malicious inputs with zero unhandled exceptions.
3. **Canonical Normalizer (`normalize.py`)**: Strips remarks, normalizes casing, converts IDN hostnames to ASCII Punycode, and computes deterministic 16-hex canonical IDs based strictly on connection parameters.
4. **Static Security Validator (`validate.py`)**:
   - Blocks private (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), loopback (`127.0.0.0/8`, `::1`), link-local (`169.254.0.0/16`), CGNAT (`100.64.0.0/10`), multicast, and reserved IP literals.
   - Enforces RFC 1123 hostname syntax.
   - Validates UUIDs, Reality X25519 public keys, shortIds, and spiderX paths.
   - Restricts transports and TLS fingerprints to whitelists strictly matching bundled Xray-core v26.10.29.
5. **Deduplication & Diversity Limiter (`dedupe.py`)**: Deduplicates candidates by canonical ID across all sources and caps candidates to at most 3 per `(host, port)`.
6. **Execution Reporter (`report.py`)**: Emits structured run metrics and machine-readable rejection histograms.

### Pipeline II: Runner Testing, Ranking, Signing, and Publishing (`run.py`)
1. **Stage A Pre-Filter (`runner_test.py`)**: Fast, asynchronous DNS resolution and TCP reachability check (timeout 3.0s). Unreachable candidates are dropped immediately.
2. **Stage B Multi-Round Probing (`runner_test.py`)**:
   - Executes official Xray-core binary with programmatic configuration (one Xray process per batch of <= 100 configs, dedicated loopback SOCKS inbounds).
   - Conducts 3 probe rounds spaced 5s apart:
     1. HTTPS GET to `https://www.gstatic.com/generate_204` (fallback `https://cp.cloudflare.com/generate_204`), measuring Time to First Byte (TTFB).
     2. Integrity canary check: fetches `canary.txt` through the proxy tunnel and verifies its SHA-256 digest against the expected hash.
   - Rejects candidates failing HTTP 204 status, canary mismatch, success ratio < 2/3, or median latency > 3000 ms.
3. **Historical Telemetry & Expiry (`ranking.py`)**:
   - Tracks 48-run rolling success bitfield and EWMA latency in `state/history.json`.
   - Expires candidates after 6 consecutive failures or 48 hours without success.
4. **Scoring & Diversity Selection (`ranking.py`)**:
   - Score formula: `Score = 100 * (0.45 * SuccessRatio + 0.30 * LatencyScore + 0.15 * StabilityScore + 0.10 * ThroughputScore)`.
   - Enforces a 50% diversity ceiling on any single `(protocol, network, security)` class when alternatives exist.
   - Never performs geo-IP ranking or location sorting.
5. **Cryptographic Signing (`crypto_signing.py`)**:
   - Signs uncompressed `manifest.v1.json` and `seed.v1.json` using ECDSA P-256 / SHA-256 (ASN.1 DER format, compatible with Java's standard `SHA256withECDSA`).
   - Self-verifies signatures against the public key before publication.
6. **Safety Net Enforcement (`manifest.py`)**:
   - Aborts publication if total working servers fall below `MIN_SERVERS` (default 20).
   - Aborts publication if server count drops by > 70% compared to the previous published manifest.
7. **Publishing Workflow (`.github/workflows/free-configs.yml`)**:
   - Deploys signed manifests and canary to GitHub Pages.
   - Pushes an orphan data commit to `free-configs-data` branch for raw and jsDelivr CDN mirrors.
   - Purges jsDelivr CDN branch cache.
   - Runs a keep-alive heartbeat preventing 60-day GitHub scheduled action auto-disable.

---

## 2. One-Time Setup for Repository Owners

### A. Enable GitHub Pages
1. Go to repository **Settings** -> **Pages**.
2. Under **Build and deployment** -> **Source**, select **GitHub Actions**.

### B. Configure Workflow Permissions
1. Go to repository **Settings** -> **Actions** -> **General**.
2. Under **Workflow permissions**, select **Read and write permissions**.
3. Check the box **Allow GitHub Actions to create and approve pull requests**.

### C. Generate ECDSA P-256 Signing Keypair
Run the included key generator script locally:
```bash
python3 scripts/gen_signing_key.py
```
The script will output:
1. `PRIVATE KEY` (PKCS#8 PEM)
2. `PUBLIC KEY SPKI BASE64` (for Android app embedding)

### D. Add Repository Secrets
1. Go to repository **Settings** -> **Secrets and variables** -> **Actions** -> **New repository secret**.
2. Name: `FREE_CONFIG_SIGNING_KEY`
3. Value: Paste the full PKCS#8 PEM private key.
4. (Optional) Under **Variables**, add:
   - `FREE_CONFIG_KEY_ID`: `prod-1` (default: `prod-1`)

---

## 3. Manual Workflow Dispatch & Local Testing

### Local Dry-Run
```bash
# Run all unit and integration tests
python3 free-config-pipeline/run_tests.py

# Run collection pipeline
PYTHONPATH=free-config-pipeline/src python3 -m free_config_pipeline.collect \
  --sources free-config-pipeline/sources.yml \
  --out build/candidates.json \
  --report build/report.json

# Run runner tests, ranking, and test signing (dry-run)
PYTHONPATH=free-config-pipeline/src python3 -m free_config_pipeline.run \
  --candidates build/candidates.json \
  --state state/history.json \
  --out-dir build/dist \
  --dry-run
```

### GitHub Actions Manual Trigger
1. Go to **Actions** tab -> **MAXNET5G Free Config Pipeline**.
2. Click **Run workflow**.
3. Set `dry_run` to `false` and `publish` to `true` to execute a live update.

---

## 4. Key Rotation Procedure

To rotate signing keys without interrupting service to existing app installations:
1. Run `python3 scripts/gen_signing_key.py` to generate keypair `prod-2`.
2. Add `prod-2` public key into the Android client's list of trusted public keys alongside `prod-1` in `FreeVpnRepository.kt`.
3. Deploy an app update.
4. Once rolled out, update the GitHub repository secret `FREE_CONFIG_SIGNING_KEY` with the new private key and set `FREE_CONFIG_KEY_ID` to `prod-2`.
5. The pipeline immediately starts publishing with `keyId: prod-2`, verified seamlessly by updated clients.

---

## 5. Adding and Managing Sources

Configure remote endpoints in `free-config-pipeline/sources.yml`:
```yaml
- name: "source-name"
  url: "https://raw.githubusercontent.com/org/repo/branch/sub.txt"
  format: "base64"  # uri_list | base64 | clash_yaml | singbox_json
  enabled: true
  max_bytes: 5242880
  notes: "Description and maintainer notes"
```

---

## 6. Public Distribution Mirrors

Clients consume the signed manifest through multiple independent mirrors:
1. **GitHub Pages (Primary)**:
   `https://<username>.github.io/<repo>/manifest.v1.json`
2. **GitHub Raw Data Branch**:
   `https://raw.githubusercontent.com/<username>/<repo>/free-configs-data/manifest.v1.json`
3. **jsDelivr CDN Mirror**:
   `https://cdn.jsdelivr.net/gh/<username>/<repo>@free-configs-data/manifest.v1.json`

---

## 7. Attribution

All proxy endpoints collected by this pipeline are derived exclusively from public community subscriptions and repositories intended for public testing. The pipeline enforces strict static hygiene, drops malicious / private routing attempts, and validates cryptographic authenticity before distribution.
