# MAXNET5G — National Internet Resilience & Smart Global Connectivity Worklog

## Run Configuration
- `EXECUTION_MODE`: `FULL_AUTO`
- `TARGET_BRANCH`: `feature/national-internet-resilience`
- `REPORT_LANGUAGE`: `Persian`
- `ASK_USER_POLICY`: `BLOCKED_ONLY`

---

## Decisions & Assumptions

1. **Single-Process Architecture & Direct Binding**:
   - Audited `AndroidManifest.xml` and `MaxNetVpnService.kt`: all components run in the default application process. `VpnController` is an in-process singleton.
   - `MaxNetVpnService.establishTunInterface()` calls `builder.addDisallowedApplication(packageName)` in full-tunnel mode (and excludes `packageName` in `ALLOW_SELECTED` mode), meaning sockets created by the MAXNET5G process naturally bypass the TUN interface.
   - To guarantee underlay probing even across network transitions, `RealProbeRunner` explicitly binds underlay probes (`Plane.UNDERLAY`) to the active physical `android.net.Network` (`network.getAllByName`, `network.socketFactory`, `network.openConnection`), while overlay probes (`Plane.OVERLAY`) route through `java.net.Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localSocksPort))`.

2. **Zero-TUN-Teardown Core Restarts (Rule S-6 / Section 11.6)**:
   - `TProxyService` (`libhev-socks5-tunnel.so`) bridges the `tunInterface` fd to `127.0.0.1:localSocksPort`.
   - By adding `preferredLocalSocksPort: Int? = null` to `XrayCoreAdapter.start(...)`, Xray-core can restart on the exact same local SOCKS5 port during profile failover, relay fallback, or network handoff without closing `tunInterface` or restarting `TProxyService`. If the preferred port cannot be rebound after brief retries, `MaxNetVpnService` falls back to re-establishing the TUN + TProxy atomically.

3. **Default Probe Endpoints (`probe_endpoints.json`)**:
   - Shipped in `/app/src/main/assets/connectivity/probe_endpoints.json`.
   - `INTERNATIONAL`: 4 endpoints across 3 independent provider groups (`google`, `cloudflare`, `microsoft`).
   - `TUNNEL_CHECK`: 3 endpoints across 3 independent provider groups (`cloudflare`, `google`, `apple`).
   - `DOMESTIC`: Empty list `[]` by default per Rules A-2 and Section 6.2 (never hardcode government, banking, or commercial third-party services). Operators/users can configure explicit authorized domestic test endpoints in Settings (`SettingsDataStore`).

4. **Authorized Relay Modes (`Mode A` and `Mode A2`)**:
   - `Mode A`: User imports a full `VpnProfile` whose server/port is their relay.
   - `Mode A2`: User configures an `AuthorizedRelay` entry (`id`, `name`, `host`, `port`, `transportType`, `sniOrHostOverride`, `pinnedSpkiSha256`) and associates it with compatible profiles (`WS`, `GRPC`, `TCP`, `HTTPUPGRADE`, `XHTTP` over `TLS`/`REALITY`). At connection time, `XrayConfigBuilder` connects the outer transport to `relay.host:relay.port` while preserving the destination gateway's `sni`, `host`, and credentials.

5. **Master Kill-Switch (`masterResilienceEnabled`)**:
   - Stored in `ConnectivityConfig` (persisted via `SettingsDataStore`, default `true`).
   - When `false`, `SmartGlobalConnectivityEngine` and `AndroidNetworkMonitor` remain inert and `MaxNetVpnService` / `VpnController` execute the legacy pre-upgrade connection and `SmartFailoverManager` path verbatim.

---

## Phase Progress Log

- **Phase 0 (Audit & Baseline)**: Completed `/docs/connectivity/00_AUDIT.md`. Baseline unit tests (`SmartVpnV3UpgradeTest`) and `compile_applet` verified green.
- **Phase 1 (Foundations)**: Implementing `ConnectivityModels.kt`, `EndpointIdHasher.kt`, and `LogRedactor.kt` IP/host sanitization.
- **Phase 2 (Probing Framework)**: Implementing `probe_endpoints.json`, `ProbeEndpointRegistry.kt`, and `ProbeEngine.kt`.
- **Phase 3 (NationalInternetDetector & DomesticReachabilityDiscovery)**: Implementing 10-rule classifier, confidence formula, 2-cycle/10s hysteresis, and domestic reachability assessment.
- **Phase 4 (Failure Classification & Redacted Log)**: Implementing `FailureClassifier.kt` and 200-entry `FailureLog.kt`.
- **Phase 5 (Path Assessment & Tunnel Verification)**: Implementing `PathRegistry.kt`, `ServerReachabilityAssessor.kt`, and `TunnelHealthVerifier.kt`.
- **Phase 6 (Adaptive Recovery & State Machine)**: Implementing `CircuitBreakerRegistry.kt`, `RecoveryPlanner.kt`, and `SmartGlobalConnectivityEngine.kt`.
- **Phase 7 (Multi-Network Support)**: Implementing `AndroidNetworkMonitor.kt` and `VpnService.setUnderlyingNetworks` integration.
- **Phase 8 (Authorized Relay Support)**: Implementing `RelayHealthAssessor.kt`, `XrayConfigBuilder` relay override, and `/docs/connectivity/RELAY_SETUP.md`.
- **Phase 9–11 (Integration, Persian UI, Tests & Final Verification)**: Integrating with `SettingsDataStore`, `XrayCoreAdapter`, `MaxNetVpnService`, `VpnController`, `MaxNetViewModel`, `HomeScreen`, `DiagnosticsAndSettingsScreen`, and `NationalInternetResilienceTest.kt`.
