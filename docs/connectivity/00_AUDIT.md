# MAXNET5G — Phase 0 Repository Audit Report (`00_AUDIT.md`)

**Date:** 2026-11-02  
**Branch / Target:** `feature/national-internet-resilience`  
**Execution Mode:** `FULL_AUTO`  
**Audit Scope:** Full inspection of build configuration, AndroidManifest, VPN data plane, Xray-core JNI integration, SmartEngine/SmartFailoverManager, Room/DataStore persistence, network callbacks, UI/RTL, and security posture.

---

## 1. Comprehensive Findings Table

| Section & Question | Actual Finding in Repository | Evidence (File : Symbol : Lines) |
|---|---|---|
| **A. Build & Environment** | | |
| Gradle, AGP, Kotlin, JDK | AGP `9.1.1`, Kotlin `2.2.10`, KSP `2.3.5`, Java source/target `VERSION_11`. | `gradle/libs.versions.toml:1-13`, `app/build.gradle.kts:161-164` |
| SDK Versions | `compileSdk = 36 (minorApiLevel = 1)`, `minSdk = 24`, `targetSdk = 36`. | `app/build.gradle.kts:98-103` |
| ABIs & Native Libraries | `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`. Gradle task `prepareXrayCoreNative` downloads & SHA-256 verifies (`64fc5293...`) `libv2ray.aar` (`v26.10.29`) and extracts `libgojni.so` + geodata (`geoip.dat`, `geosite.dat`). Also includes `libhev-socks5-tunnel.so` via `TProxyService`. | `app/build.gradle.kts:14-83, 109-125` |
| Dependencies | Jetpack Compose BOM `2024.09.00`, Material 3, Room `2.7.0`, DataStore Preferences `1.1.7`, Coroutines `1.10.2`, OkHttp `4.10.0`, WorkManager `2.10.0`, ZXing `3.5.3`, Robolectric `4.16.1`, `libs/libv2ray-api.jar`. | `gradle/libs.versions.toml:12-45`, `app/build.gradle.kts:203-266` |
| Signing & Build Types | `debug` (`debugConfig` using `debug.keystore`) and `release` (`isMinifyEnabled = false`, `proguard-rules.pro`). | `app/build.gradle.kts:129-160` |
| **B. Structure & Architecture** | | |
| Modules & Packages | Single `:app` module under `com.example` (`applicationId = "com.aistudio.maxnet5gvpn.qxkrzp"`). Packages: `core` (`autovpn`, `security`, `singbox`, `smart`, `sync`, `xray`), `data` (`local`, `preferences`, `repository`), `domain.model`, `parser`, `security`, `ui` (`components`, `screens`, `theme`, `viewmodel`), `util`, `vpn`. | `app/build.gradle.kts:97-101`, `/app/src/main/java/com/example/*` |
| DI & State Pattern | Manual constructor injection (`MaxNetDatabase`, `SettingsDataStore`, `VpnRepository`, `MaxNetViewModel`). Reactive state via `StateFlow` and `collectAsStateWithLifecycle()`. | `MaxNetViewModel.kt:1-120`, `MainActivity.kt` |
| **C. AndroidManifest** | | |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `CAMERA` (optional QR scan), `QUERY_ALL_PACKAGES` (for per-app split tunnel). | `app/src/main/AndroidManifest.xml:5-19` |
| Components & Processes | All components run in the single default application process (no `android:process` split). `MainActivity` (`exported=true`), `MaxNetVpnService` (`exported=true`, permission `BIND_VPN_SERVICE`, `foregroundServiceType="systemExempted|specialUse"`, `SUPPORTS_ALWAYS_ON=true`), `MaxNetTileService`, `BootCompletedReceiver`. | `app/src/main/AndroidManifest.xml:21-94` |
| **D. VPN Data Plane** | | |
| Xray Core Lifecycle | Bundled `AndroidLibXrayLite v26.10.29` (`libgojni.so`, commit `0afec66`). Initialized via `Libv2ray.initCoreEnv(filesDir, xudpKey)` and started via `CoreController.startLoop(configJson, tunFd = 0)` on a loopback SOCKS5 port (`10808` etc.). On JVM test hosts without Android JNI, falls back to upstream `xray` binary or loopback SOCKS5 server. | `XrayNativeBridge.kt:66-90, 151-184, 260-472`, `XrayCoreAdapter.kt:89-179` |
| Config Builder | `XrayConfigBuilder.buildAndValidate()` builds JSON with `log`, `stats`, `policy`, `fakedns`, `dns`, `inbounds` (`socks-in` on `127.0.0.1:localSocksPort`, `http-in` on `localSocksPort+1`), `outbounds` (`proxy`, `direct`, `block`, `dns-out`), and `routing`. | `XrayConfigBuilder.kt:61-250` |
| TUN Creation & Forwarding | `MaxNetVpnService.establishTunInterface()` creates TUN with `10.26.26.1/30`, route `0.0.0.0/0`, MTU `1280..1500`, DNS (`1.1.1.1`/`8.8.8.8`), optional IPv6 (`fd00:1::1/126` + `::/0`) or IPv6 leak blackhole (`2001:db8::1/128` + `::/0`), optional `setBlocking(true)` kill switch. Packets are forwarded from TUN fd to `127.0.0.1:localSocksPort` by `TProxyService.startTun2Socks` (`libhev-socks5-tunnel.so`). | `MaxNetVpnService.kt:260-384` |
| Loop Prevention & `protect()` | `establishTunInterface` excludes the VPN app’s own package (`builder.addDisallowedApplication(packageName)`) in `ALL_APPS` and `DISALLOW_SELECTED` modes, and filters `packageName` out of `ALLOW_SELECTED`. Thus all sockets opened by the app process (including Xray's outbound sockets and underlay probes) automatically bypass the TUN interface at the OS UID routing level. Note: `protectSocket` is passed to `XrayCoreAdapter.start()` but `Libv2ray.CoreController` does not expose a per-socket protect callback in `libv2ray-api.jar` because per-app disallow handles UID exclusion. | `MaxNetVpnService.kt:115, 345-366`, `XrayCoreAdapter.kt:103-143` |
| `setUnderlyingNetworks` | **Not found** in current `MaxNetVpnService.kt`. | `MaxNetVpnService.kt` (explicit «not found») |
| `onRevoke()` | Calls `disconnectVpn()`, which stops `AutoFreeVpnManager`, `VpnController.stopSession()`, closes TUN, removes foreground notification, and calls `stopSelf()`. Does not auto-reconnect. | `MaxNetVpnService.kt:418-424` |
| **E. Lifecycle & IPC** | | |
| Service & UI Observation | Because `MaxNetVpnService` and `MainActivity` share the same process, `VpnController` singleton holds `StateFlow`s (`connectionState`, `statistics`, `diagnostics`, `speedHistory`, `ownershipState`) directly observed by `MaxNetViewModel`. | `VpnController.kt:39-104`, `MaxNetViewModel.kt` |
| **F. Smart Logic** | | |
| `SmartEngine` | Deterministic multi-metric scorer (`calculateServerScore`, `selectBestServer`) supporting 4 modes (`GAMING`, `STREAMING`, `BROWSING`, `STABLE`), 15% / 35ms hysteresis, and exponential failure cooldowns (15s, 45s, 2m, 5m). | `SmartEngine.kt:1-292` |
| `SmartFailoverManager` | Polls active tunnel every 10–30s using `LatencyTester.testProxyHttp204Delay` against primary (`gstatic`) and fallback (`cloudflare`) 204 URLs. Checks `isDeviceNetworkOnline()` before failing over. Caps failovers at 3 per session. Currently switches only between existing `ServerProfile` instances. | `SmartFailoverManager.kt:49-265` |
| Isolated Profile Delay Test | `LatencyTester.testRealVpnLatency()` spins up an isolated temporary Xray instance (`buildTemporaryProxyTestConfig`) on an ephemeral loopback port (`allocateEphemeralLoopbackPort`) and sends HTTP 204 probes via SOCKS5 without disturbing the active VPN tunnel. | `LatencyTester.kt:198-287`, `XrayNativeBridge.kt:900-1150` |
| `AutoVpnDiscoveryEngine` | Fetches Free VPN subscription exclusively from `https://io.maxnet6g.ir:2096/sub/acme0ym4y3dswjo5` (XOR-obfuscated in code), validates TLS, caps size at 1 MB, parses VLESS/VMess/Trojan. Free VPN mode (`VpnAppMode.FREE_VPN`) is strictly isolated from `MY_VPN`. | `AutoVpnDiscoveryEngine.kt:63-160`, `AutoFreeVpnManager.kt` |
| **G. Data & Persistence** | | |
| Room Database | `MaxNetDatabase` version `4` (`exportSchema = false`), entities: `ProfileEntity`, `SubscriptionEntity`, `ProfileGroupEntity`, `ApplicationSettingsEntity`, `RoutingRuleEntity`, `ConnectionHistoryEntity`. Non-destructive migrations `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`. | `MaxNetDatabase.kt:10-85`, `Entities.kt` |
| Settings & Secrets | `SettingsDataStore` (`maxnet5g_settings` Preferences DataStore). `CredentialCipher` encrypts profile credentials and keys with AES-256-GCM backed by `AndroidKeyStore` (`maxnet5g_master_credential_key_v1`). | `SettingsDataStore.kt:22-166`, `CredentialCipher.kt:21-105` |
| **H. Network Handling** | | |
| `NetworkCallback` | `MaxNetVpnService.registerNetworkChangeCallback()` registers a basic `NetworkCallback` for `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_NOT_VPN` and calls `VpnController.reconnectWithExponentialBackoff()` on every `onAvailable` when `CONNECTED`, rebuilding both Xray and the TUN interface without debouncing or checking whether the existing tunnel still works. | `MaxNetVpnService.kt:426-464` |
| **I. UI & Localization** | | |
| Screens & RTL | Jetpack Compose UI (`HomeScreen`, `ProfilesScreen`, `SubscriptionsAndRoutingScreen`, `DiagnosticsAndSettingsScreen`, `SmartVpnComponents`). Default language is `fa-IR` with full RTL layout and Persian digits helper (`LocalizationHelper`). | `HomeScreen.kt`, `SmartVpnComponents.kt`, `strings.xml`, `values-fa/strings.xml` |
| **J. Tests & Quality** | | |
| Unit & Robolectric Tests | 5 test suites in `app/src/test/java/com/example/`: `SmartVpnV3UpgradeTest`, `AutoFreeVpnSystemTest`, `RealLatencyAndAutoSyncTest`, `ExampleRobolectricTest`, `ExampleUnitTest`. | `app/src/test/java/com/example/*` |
| **K. Security Posture** | | |
| TLS, Logging, Backup | `allowBackup="false"`, `@xml/data_extraction_rules`, `@xml/backup_rules`, `@xml/network_security_config`. `LogRedactor` strips UUIDs, share-link credentials, JSON secret fields, and subscription URLs. | `AndroidManifest.xml:22-30`, `LogRedactor.kt:7-62` |

---

## 2. Explicit «Not Found» Entries

1. **`NationalInternetDetector` / `DomesticReachabilityDiscovery` / `SmartGlobalConnectivityEngine`:** Not yet present in the repository; to be implemented in `com.example.core.resilience`.
2. **`VpnService.setUnderlyingNetworks()`:** Not currently called in `MaxNetVpnService.kt`.
3. **Per-socket `VpnService.protect(fd)` callback into `libv2ray.so`:** `Libv2ray.CoreController` in `libv2ray-api.jar` does not use a `VpnSupportSet` socket callback; instead, `MaxNetVpnService` uses `Builder.addDisallowedApplication(packageName)` so all sockets of the app UID bypass the TUN interface automatically.
4. **Exported Room schema JSON files:** `MaxNetDatabase` has `exportSchema = false`; migrations are tested directly via Room in-memory/file SQLite migration tests.

---

## 3. Baseline Build & Test Results

Executed prior to any code changes for this upgrade:

- **Command:** `gradle :app:testDebugUnitTest --tests "com.example.SmartVpnV3UpgradeTest"`
  - **Exit Code:** `0` (`BUILD SUCCESSFUL in 34s`, 9/9 tests passed).
- **Command:** `compile_applet` (`assembleDebug`)
  - **Result:** `Build succeeded - the applet is compiled`.

---

## 4. Feasibility Matrix

| Capability | Rating | Technical Reason & Chosen Approach |
|---|---|---|
| **1. Underlay probing bound to physical network** | **Feasible** | Because `MaxNetVpnService` adds `addDisallowedApplication(packageName)` on the TUN builder AND `NetworkMonitor` provides the physical `android.net.Network` (`NET_CAPABILITY_NOT_VPN`), underlay probes use `network.getAllByName()` for DNS and `network.socketFactory` / `network.openConnection()` for TCP, TLS, and HTTPS—guaranteeing 100% tunnel bypass without `bindProcessToNetwork`. |
| **2. Overlay verification through the tunnel** | **Feasible** | Xray exposes a local SOCKS5 inbound (`127.0.0.1:localSocksPort`). `TunnelHealthVerifier` sends HTTP/HTTPS 204 probes through `127.0.0.1:localSocksPort` (ATYP domain `0x03`) to independent `TUNNEL_CHECK` endpoints and enforces a multi-sample stability window before reporting `اتصال امن برقرار شد`. |
| **3. Restarting Xray outbound without tearing down TUN** | **Feasible** | `TProxyService` (`hev-socks5-tunnel`) forwards TUN packets to `127.0.0.1:localSocksPort`. If Xray-core is restarted on the **same** `localSocksPort`, the TUN file descriptor (`tunInterface`) and `TProxyService` remain open and untouched, preventing any leak window during core restarts. |
| **4. Binding Xray’s sockets to a chosen Android Network** | **Partial** | `MaxNetVpnService` excludes its own UID from TUN, so Xray's Go sockets follow the OS default physical network, and `setUnderlyingNetworks(arrayOf(network))` informs Android of the active physical network. However, `libgojni.so` (`CoreController`) does not pass raw socket descriptors to Kotlin for arbitrary non-default `Network.bindSocket(fd)`. Therefore, outcome `12.4 (1)` + `12.4 (3)` applies: Xray follows the system default physical network and we report when a secondary non-default physical network is available via a Persian UI hint. |
| **5. Transport variants from existing user profiles** | **Feasible** | `PathRegistry` groups profiles belonging to the same gateway or subscription, orders user profiles by score, and (when `allowRuntimeVariants` is enabled) generates runtime-only DNS/fragment/mux variants validated by `XrayConfigBuilder` without mutating stored profiles. |
| **6. Relay-fronted profiles & Address-override relays** | **Feasible** | Modes `A` (relay-fronted user profile) and `A2` (runtime address/port override to an explicitly configured, authenticated, encrypted `AuthorizedRelay` while preserving gateway TLS `sni` and WS `host`) are 100% supported by `XrayConfigBuilder` without a second engine. |
| **7. Xray’s internal observatory / balancer** | **Partial (App-Level Preferred)** | While Xray-core v26.10.29 supports single-config outbounds, app-level `SmartGlobalConnectivityEngine` + `LatencyTester` ephemeral SOCKS5 probing gives deterministic, redacted, circuit-breaker-controlled failover without competing with an internal Xray balancer. |

---

## 5. Risk List & Mitigations

1. **Risk: Reconnect loops during National Internet (NIN) outages.**
   - *Mitigation:* When `NationalInternetDetector` classifies the underlay as `DOMESTIC_ONLY_SUSPECTED` or `RESTRICTED_INTERNATIONAL` and no authorized relay is reachable, `RecoveryPlanner` immediately transitions to `LONG_OUTAGE_WAIT` (polling ladder: 30s → 60s → 2m → 5m → 10m → 15m) instead of rotating through unreachable foreign servers.
2. **Risk: Leak window during network switch or failover.**
   - *Mitigation:* Keep `tunInterface` open and restart Xray on the same local SOCKS5 port whenever possible (`VpnController.restartCorePreservingTun`), so packets stay captured by TUN during recovery.
3. **Risk: Sensitive server IPs, UUIDs, or tokens leaking into failure logs.**
   - *Mitigation:* All endpoints in `FailureRecord` and `ProbeResult` use `EndpointIdHasher` (truncated HMAC-SHA-256 keyed with a per-install random secret), and `LogRedactor` strips IPs, hostnames, UUIDs, share links, and subscription URLs.

---

## 6. Implementation Plan (Mapped to Phases 1–11)

- **Phase 1 (Foundations):** Create `ConnectivityConfig` (with `masterResilienceEnabled` kill-switch), core resilience models (`ConnectivityModels.kt`), interfaces (`NetworkMonitor`, `ProbeRunner`, `Clock`), `EndpointIdHasher`, enhanced `LogRedactor`, and characterization tests.
- **Phase 2 (Probing & Detection):** Add `assets/connectivity/probe_endpoints.json`, `ProbeEndpointRegistry`, `ProbeEngine` (L-IP, L-DNS, L-TLS, L-HTTP, L-XFER on UNDERLAY and OVERLAY planes), `NationalInternetDetector` (10-rule classifier + confidence + hysteresis), and `DomesticReachabilityDiscovery`.
- **Phase 3 (Failure Analysis):** Implement `FailureClassifier` (mapping exceptions, TLS alerts, HTTP blockpages, DNS tampering, and clock skew > 90s) and `FailureLog` (200-record redacted ring buffer).
- **Phase 4 (Path Assessment):** Implement `PathRegistry`, `ServerReachabilityAssessor` (5-rung ladder), and `TunnelHealthVerifier` (overlay verification + stability window before «اتصال امن برقرار شد»).
- **Phase 5 (Recovery):** Implement `RecoveryPlanner`, circuit breakers, exponential backoff with jitter, restart-storm guard, and `SmartGlobalConnectivityEngine` wired to `SmartEngine` and `SmartFailoverManager` via `ConnectivityAdvisor`.
- **Phase 6 (Network Switching):** Implement `AndroidNetworkMonitor` (`callbackFlow` with 800ms debounce, capability diffing, link-property diffing, `setUnderlyingNetworks`, and zero-TUN-teardown core restart).
- **Phase 7 (Relay Support):** Add `AuthorizedRelay` model, encrypted storage, `RelayHealthAssessor` (`RELAY_REACHABLE` vs `RELAY_FORWARDING_OK`), Mode A & A2 path generation, and `docs/connectivity/RELAY_SETUP.md`.
- **Phase 8 (Security Review):** Verify TLS validation, DNS/IPv6 leak protection, HMAC endpoint hashing, and redaction.
- **Phase 9 (UI Integration):** Add Persian connectivity status banner, verification status, relay indicator («از طریق مسیر واسط»), and expandable Advanced Resilience Diagnostics & Relay configuration dialog on `HomeScreen` and `DiagnosticsAndSettingsScreen`.
- **Phase 10 & 11 (Verification & Delivery):** Run full unit & Robolectric test suites, verify compilation, and deliver final Persian report with complete traceability.
