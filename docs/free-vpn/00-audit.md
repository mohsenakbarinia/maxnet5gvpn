# MAXNET5G Free VPN — Architecture Audit & Discovery Report
Document ID: `docs/free-vpn/00-audit.md`
Created: Part 1 / 9
Project: MAXNET5G VPN (Android Client & Collector Pipeline)

---

## 1. Executive Summary & Baseline Verification

### 1.1 Baseline Build & Test Execution
- **Build Status**: **SUCCESSFUL** (`compile_applet` passed cleanly).
- **Unit & Robolectric Tests**: **18 passing / 0 failing / 0 skipped** across all test suites:
  - `com.example.ExampleUnitTest`: 10 tests passed (5.111 s)
  - `com.example.ExampleRobolectricTest`: 5 tests passed (20.543 s)
  - `com.example.AutoFreeVpnSystemTest`: 3 tests passed (0.174 s)
- **Environment Notes**:
  - No `.git` metadata is mounted in this Cloud Build container (`fatal: not a git repository`), so commits are tracked via direct file generation according to section 0.6.
  - Linux JVM host has `/tmp/xray-linux/xray` binary and extracted geodata (`geoip.dat`, `geosite.dat`) allowing real native validation during JVM unit tests.

---

## 2. Comprehensive Codebase Audit (Section 1.2 Answers)

### 2.1 Stack and Build Setup
- **Languages**: 100% Kotlin (2.2.10) for Android app; Python (3.11) for background collection scripts.
- **Module Structure**: Single app module (`:app`) with native dependencies in `app/build/xray-native/` and `libs/libv2ray-api.jar`.
- **Toolchain Versions** (from `gradle/libs.versions.toml` & `app/build.gradle.kts`):
  - Android Gradle Plugin (AGP): `9.1.1`
  - Kotlin: `2.2.10` with KSP `2.3.5` and Compose Compiler plugin
  - Jetpack Compose BOM: `2024.09.00` (Material 3: `1.3.0`)
  - SDK Targets: `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36` (minor API level 1)
- **ABIs**: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.
- **R8 / ProGuard**: Rules configured in `app/proguard-rules.pro` keeping `libv2ray.**`, `go.Seq`, `com.v2ray.ang.**`, and Room entities.
- **Signing Setup**: `signingConfigs` define `debugConfig` (using `debug.keystore`) and `release` (injected via `KEYSTORE_PATH` env var).
- **Existing Workflows**:
  - `/.github/workflows/android-apk.yml`: Multi-arch release APK builder on JDK 21.
  - `/.github/workflows/auto-vpn-collector.yml`: 30-minute cron workflow executing `scripts/collect_and_validate_configs.py` and pushing to `public/api/v1/candidates.json`.

### 2.2 Xray Core Integration
- **Bundling & Version**:
  - Official upstream Xray-core `v26.10.29` (commit `0afec66`) packaged via `2dust/AndroidLibXrayLite` (AAR release SHA-256: `64fc529302dce728bde859f45d87a64f7289a5fbcca8ef962aae3d94c6e3fa82`).
  - Extracted by Gradle task `prepareXrayCoreNative` in `app/build.gradle.kts` (lines 14–83) into `build/xray-native/jniLibs` (`libgojni.so`) and `build/xray-native/assets` (`geoip.dat`, `geosite.dat`).
  - Java API bindings provided via `libs/libv2ray-api.jar` (`libv2ray.Libv2ray`, `libv2ray.CoreController`).
- **Exposed APIs** (`com.example.core.xray.XrayNativeBridge`):
  - `initCoreEnvironment(context)`: Initializes assets & XUDP base key via `Libv2ray.initCoreEnv`.
  - `startCore(context, configJson, tunFd, onStatusEmit)`: Instantiates `CoreController` via `Libv2ray.newCoreController`, hooks `ProcessFinder`, and executes `controller.startLoop(configJson, tunFd)`.
  - `stopCore()`: Calls `controller.stopLoop()`.
  - `validateConfigWithEngine(configJson)`: Executes `xray run -test -c` on host Linux runner.
  - `measureStandaloneOutboundDelay(context, configJson, testUrl)`: Invokes `Libv2ray.measureOutboundDelay(configJson, testUrl)`.
  - `measureActiveProxyDelay(localSocksPort, testUrl, timeoutMs)`: Dials `CoreController.measureDelay(testUrl)` or executes an RFC 1928 SOCKS5 tunnel request to `127.0.0.1:localSocksPort`.
  - `queryOutboundTrafficDelta()`: Queries `coreController.queryAllOutboundTrafficStats()` (returning `tag,direction,bytes;`).
- **Supported Transports & Security**:
  - Protocols: VLESS, VMess, Trojan, Shadowsocks (SIP002, 2022-blake3), WireGuard.
  - Transports: TCP, WebSocket, gRPC, HTTP/2, HTTPUpgrade, XHTTP / SplitHTTP, mKCP, QUIC.
  - Security: TLS, REALITY (`xtls-rprx-vision` flow, `pbk`, `sid`, `spx`), None.

### 2.3 Configuration Generation
- **Builder Class**: `com.example.core.xray.XrayConfigBuilder` (lines 1–824).
- **Architecture**: Programmatic JSON construction using typed models (`ServerProfile`, `AppSettings`, `RoutingRuleItem`).
- **Strict Validation**:
  - Enforces RFC 4122 UUID syntax for VLESS/VMess.
  - Validates REALITY parameters (`realityPublicKey` non-blank, `sni` present, `realityShortId` hex <= 16 chars).
  - Validates VLESS flow (`xtls-rprx-vision`) requiring TCP + TLS/REALITY.
  - Rebuilds config cleanly without string templates or unvalidated JSON passthrough.
- **Routing & DNS**:
  - Inbound: SOCKS5 inbound on `127.0.0.1:<localSocksPort>` (`sniffing.enabled = true`, `destOverride = ["http", "tls", "quic"]`).
  - DNS: Remote DoH (`https://1.1.1.1/dns-query`) and direct DNS (`1.1.1.1`, `8.8.8.8`) with `dnsQueryStrategy` ("UseIPv4").
  - Routing: Rules mapped based on `RoutingMode` (GLOBAL, RULE_BASED, DIRECT) with Iran LAN bypass (`geoip:private`, `geoip:ir`, `geosite:ir`).

### 2.4 VPN Engine & Tunnel Architecture
- **VpnService Implementation**: `com.example.vpn.MaxNetVpnService` (lines 1–455).
- **TUN Setup**:
  - Interface IP: `10.26.26.1/30` (IPv4) and `fd00:1::1/126` (optional IPv6).
  - Route: `0.0.0.0/0` and `::/0`.
  - MTU: Configurable (default 1500, clamped 1280..1500).
- **Packet Forwarding Bridge**:
  - Uses `com.v2ray.ang.service.TProxyService` (`libhev-socks5-tunnel.so`) to bridge TUN `ParcelFileDescriptor` to Xray's local SOCKS5 endpoint on `127.0.0.1:<localSocksPort>`.
- **Loopback Protection**:
  - `VpnService.Builder.addDisallowedApplication(packageName)` excludes MAXNET5G itself.
  - `VpnService.protect(fd)` protects Xray's outbound dialer sockets.
- **Process Model & IPC**:
  - Single application process (`com.aistudio.maxnet5gvpn.qxkrzp`).
  - Communication between UI and service is handled via singleton `VpnController` using reactive `StateFlow` (`connectionState`, `statistics`, `diagnostics`, `speedHistory`).
- **Lifecycle & Notifications**:
  - Foreground Service with persistent notification channel (`maxnet5g_vpn_channel`, `IMPORTANCE_LOW`).
  - Shows connected profile name, protocol, and real latency with an explicit "Disconnect" action.
  - Network changes monitored via `ConnectivityManager.NetworkCallback` with automatic exponential backoff reconnection.

### 2.5 Profiles and Subscriptions
- **Data Layer**:
  - Database: Android Room (`com.example.data.local.MaxNetDatabase`), version 1, SQLite cipher support.
  - DAO: `MaxNetDao` (`ProfileEntity`, `SubscriptionEntity`, `RoutingRuleEntity`, `ConnectionHistoryEntity`).
  - Preferences: Jetpack DataStore (`SettingsDataStore`) for user settings and `selectedProfileId`.
- **Subscription Management**:
  - Downloader: `SubscriptionRepository` (lines 1–645) with OkHttpClient.
  - Features: Follows up to 5 redirect hops (preventing HTTPS -> HTTP downgrade), strict TLS validation, UTF-8 parsing, user-info headers extraction, periodic WorkManager updates (`SubscriptionUpdateWorker`).
  - Parser: `ConfigParser` (lines 1–1109) supports Plain-text, Base64 (Standard & URL-safe), and JSON subscriptions.

### 2.6 Existing Delay & Ping Measurement
- **Latency Testing**: `com.example.core.LatencyTester` (lines 1–164).
- **Types**:
  1. `TCP_HANDSHAKE`: Raw TCP connect time to remote `host:port` (used for quick pre-filtering).
  2. `PROXY_HTTP_DELAY`: End-to-end HTTP 204 response time through the encrypted proxy tunnel (via `XrayNativeBridge.measureActiveProxyDelay` or `Libv2ray.measureOutboundDelay`).
- **Rules**: Never substitutes a TCP handshake for a failed proxy delay test.

### 2.7 App Shell & UI
- **Framework**: Jetpack Compose with Material 3.
- **Localization & Theming**:
  - Persian (`fa-IR`) with full RTL support and optional Persian digits (`usePersianDigits`).
  - English (`en-US`) with LTR support.
  - Theme: Deep midnight navy (`#080D19`), electric cyan (`#00DDF0`), emerald accent (`#00E69B`).
- **Navigation**: State-driven navigation via `MaxNetDestination` enum (Home, Profiles, Subscriptions, Routing, Diagnostics, Settings) with hardware `BackHandler` integration.

### 2.8 Conflicts & Constraints
- `VpnController` previously had a singleton reference to `activeProfile: ServerProfile?`. When connecting via Auto Free VPN, a server profile must either be synthesized or persisted in Room.
- In `MaxNetViewModel.kt`, `selectedProfileId` was tracked in `SettingsDataStore`. For the Free VPN feature, connection ownership must be distinct: the app must support connecting to auto-discovered free servers without disrupting the user's manual selection when switching between modes.

---

## 3. Reuse Points & Integration Map (Section 1.3)

| Symbol | Capability | Existing File & Class | Invocation Pattern | Threading / Process | Target Parts |
|---|---|---|---|---|---|
| **a** | Delay measurement for stopped config | `com.example.core.LatencyTester.testStandaloneProxyDelay` & `XrayNativeBridge.measureStandaloneOutboundDelay` | Direct call passing `ServerProfile` and test URL | `Dispatchers.IO`, in-process | PART 5 |
| **b** | Delay measurement on running core | `com.example.core.LatencyTester.testProxyHttp204Delay` & `XrayNativeBridge.measureActiveProxyDelay` | Connects to `127.0.0.1:localSocksPort` with RFC 1928 SOCKS5 handshake | `Dispatchers.IO`, in-process | PARTS 6, 7 |
| **c** | Engine start / stop / restart & state publication | `com.example.vpn.VpnController.startSession`, `stopSession`, `connectionState` | Coroutine suspension calls; observes `StateFlow<VpnConnectionState>` | `Dispatchers.IO`, main app process | PART 6 |
| **d** | Traffic statistics & speed history | `com.example.vpn.VpnController.statistics` & `XrayNativeBridge.queryOutboundTrafficDelta` | Emits `EngineStatistics` and rolling 60-second speed history | Background coroutine polling every 1000ms | PARTS 6, 7 |
| **e** | Profile model & config generator | `com.example.domain.model.ServerProfile` & `com.example.core.xray.XrayConfigBuilder` | `XrayConfigBuilder.buildAndValidate(...)` | Pure CPU / in-memory | PART 6 |
| **f** | Network-change callback utility | `com.example.vpn.MaxNetVpnService` network callback | `ConnectivityManager.registerNetworkCallback` with `NetworkCapabilities` | Android OS callbacks dispatched to coroutine scope | PART 7 |
| **g** | HTTP client, JSON, storage & WorkManager | `com.example.data.subscription.SubscriptionRepository`, `MaxNetDao`, `SettingsDataStore` | OkHttp client, Room database, DataStore preferences | `Dispatchers.IO` | PART 4 |
| **h** | Navigation, theme, localization & UI | `com.example.ui.screens.HomeScreen`, `com.example.ui.theme.MaxNetVpnTheme`, `MainActivity` | Jetpack Compose composables, M3 colors & typography | Android Main UI thread | PART 8 |

---

## 4. Planned Change List for Parts 2 to 9

1. **PART 2 (Pipeline I - Python)**:
   - `free-config-pipeline/config.py`: Centralized pipeline configuration (`FreePipelineConfig`).
   - `free-config-pipeline/sources.py`: Source fetchers with rate-limiting, error handling, and timeout safeguards.
   - `free-config-pipeline/parser.py`: Strict URI and Base64 parser for VLESS, VLESS Reality, VMess, and Trojan.
   - `free-config-pipeline/sanitizer.py`: Host safety verifier (loopback, private LAN, script injection filter).
   - `free-config-pipeline/normalize.py`: Normalization and deduplication by canonical hash.
   - `free-config-pipeline/test_pipeline_i.py`: Pytest suite with shared test vectors.

2. **PART 3 (Pipeline II - Python + GitHub Actions)**:
   - `free-config-pipeline/runner_tester.py`: Parallel Xray-core tester executing inside GitHub Actions runner.
   - `free-config-pipeline/signer.py`: Ed25519 or HMAC-SHA256 manifest signer for integrity validation.
   - `free-config-pipeline/manifest_builder.py`: Generates signed versioned JSON manifest (`manifest.json`).
   - `.github/workflows/auto-free-vpn-pipeline.yml`: Automated CI workflow building and publishing manifests to GitHub Pages / jsDelivr / raw mirrors.
   - `free-config-pipeline/test_pipeline_ii.py`: Tests for signing, manifest format, and runner test runner.

3. **PART 4 (Android Data Layer)**:
   - `app/src/main/java/com/example/freevpn/data/FreeServer.kt`: Immutable domain model matching the contract in Section 0.5.
   - `app/src/main/java/com/example/freevpn/data/ManifestModels.kt`: Typed representation of the signed manifest and mirrors.
   - `app/src/main/java/com/example/freevpn/data/ManifestVerifier.kt`: Verifies signature and timestamps of fetched manifests.
   - `app/src/main/java/com/example/freevpn/data/LocalStatsStore.kt`: Tracks per-server probe history, consecutive failures, and quarantines.
   - `app/src/main/java/com/example/freevpn/data/FreeConfigRepository.kt`: Mirror racing, caching, offline seed fallback, and refresh logic.
   - `app/src/test/java/com/example/freevpn/FreeConfigRepositoryTest.kt`: Unit tests for repository, signature verification, and stats store.

4. **PART 5 (Android On-Device Prober & Ranker)**:
   - `app/src/main/java/com/example/freevpn/probe/ServerProber.kt`: Parallel candidate tester using Xray native bridge before connecting.
   - `app/src/main/java/com/example/freevpn/probe/ServerRanker.kt`: Multi-factor ranking algorithm (latency, stability, compatibility, history).
   - `app/src/test/java/com/example/freevpn/ServerProberRankerTest.kt`: Tests for probe budgeting, scoring, and cancellation.

5. **PART 6 (Android Engine Adapter & Connection Ownership)**:
   - `app/src/main/java/com/example/freevpn/engine/ConnectionOwner.kt`: StateFlow indicating whether connection is `FREE`, `MANUAL`, or `NONE`.
   - `app/src/main/java/com/example/freevpn/engine/FreeVpnEngine.kt`: Adapter wrapping `VpnController` and `MaxNetVpnService` with clean ownership hooks.
   - `app/src/main/java/com/example/freevpn/engine/ServerProfileAdapter.kt`: Converts `FreeServer` to `ServerProfile` for seamless execution by `XrayConfigBuilder`.
   - `app/src/test/java/com/example/freevpn/FreeVpnEngineTest.kt`: Tests verifying start, stop, telemetry, and ownership exclusivity.

6. **PART 7 (Android Orchestrator & State Machine)**:
   - `app/src/main/java/com/example/freevpn/core/FreeVpnState.kt`: Phase state machine (IDLE, FETCHING, PROBING, CONNECTING, CONNECTED, RECONNECTING, ERROR).
   - `app/src/main/java/com/example/freevpn/core/ConnectionMonitor.kt`: Monitors live tunnel health, traffic stalls, and network transitions.
   - `app/src/main/java/com/example/freevpn/core/FreeVpnOrchestrator.kt`: Main coordinating engine handling one-tap connect, failover, and standby rotation.
   - `app/src/test/java/com/example/freevpn/FreeVpnOrchestratorTest.kt`: State machine tests, failover simulations, and network recovery tests.

7. **PART 8 (Android UI Integration)**:
   - `app/src/main/java/com/example/freevpn/ui/FreeVpnControls.kt`: Minimal M3 UI components (power button, ping display, status badge).
   - `app/src/main/java/com/example/ui/screens/HomeScreen.kt`: Additive hooks to bind `FreeVpnOrchestrator` to the main dashboard.
   - `app/src/main/res/values/strings.xml` & `strings-fa.xml`: Bilingual Persian and English string resources for the Free VPN flow.

8. **PART 9 (Hardening & End-to-End Verification)**:
   - `docs/free-vpn/SECURITY-AUDIT.md`: Threat model analysis, input sanitization audit, credential protection review.
   - `docs/free-vpn/FINAL-REPORT.md`: Final completion report and deployment guide.
   - End-to-end integration tests verifying zero regressions on manual profiles and subscriptions.

---

## 5. Assumptions, Risks & Proposed Interface Refinements

### 5.1 Assumptions
1. Free manifests will be published to public mirrors (e.g. GitHub Pages, GitHub raw, and jsDelivr CDN) requiring no custom hosted backend.
2. The device will ship with an offline bundled seed manifest (`assets/free_vpn_seed.json`) so the very first "Tap to Connect" works even before network mirrors are queried.
3. Untrusted public servers might go down at any time, requiring the standby server pool and automatic failover to remain active in memory.

### 5.2 Key Technical Risks
1. **Host-Side vs Device-Side Xray JNI in Tests**: On Linux test runners, Bionic `libgojni.so` cannot link against glibc; we mitigate this by maintaining the existing architecture pattern where Linux test runners invoke the compiled Linux `xray` binary for real validation.
2. **Untrusted Data Exploitation**: Public configs could attempt to inject malicious Xray parameters or spoof DNS. Mitigated by reconstructing every config from whitelisted typed fields through `XrayConfigBuilder` and never executing raw JSON strings.
3. **Connection State Contention**: User might tap "Connect" on Free VPN while a manual subscription profile is active. Additive `ConnectionOwner` will track ownership and confirm before preempting manual sessions.

### 5.3 Interface Contracts (Part 0 Section 0.5) Compatibility
The interface contracts specified in PART 0 Section 0.5 align cleanly with the codebase:
- `FreeServer`: Maps directly to `ServerProfile` through `ServerProfileAdapter`.
- `FreeConfigRepository`: Integrates with OkHttp and Room/DataStore.
- `FreeVpnEngine`: Bridges to `VpnController`.
- **Minor Recommendation**: To ensure maximum testability under JVM Robolectric where raw tun file descriptors are virtualized, allow `FreeVpnEngine.measureRunningDelay` to accept a configurable timeout and fallback to `VpnController.diagnostics.value.verifiedProxyLatencyMs`.

---
*Audit completed by Senior Android & Network Architecture Agent. Ready for Part 2.*
