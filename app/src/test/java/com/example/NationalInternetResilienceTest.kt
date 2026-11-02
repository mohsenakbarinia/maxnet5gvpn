package com.example

import android.net.Network
import com.example.core.autovpn.VpnAppMode
import com.example.core.resilience.AuthorizedRelay
import com.example.core.resilience.CircuitBreakerRegistry
import com.example.core.resilience.ConnectivityConfig
import com.example.core.resilience.ConnectivityPath
import com.example.core.resilience.DomesticReachability
import com.example.core.resilience.DomesticReachabilityDiscovery
import com.example.core.resilience.DomesticReachabilityState
import com.example.core.resilience.EndpointIdHasher
import com.example.core.resilience.FailureCategory
import com.example.core.resilience.FailureClassifier
import com.example.core.resilience.FailureLog
import com.example.core.resilience.FailureRecord
import com.example.core.resilience.NationalInternetDetector
import com.example.core.resilience.NationalInternetState
import com.example.core.resilience.NetworkAssessment
import com.example.core.resilience.NetworkSnapshot
import com.example.core.resilience.PathRegistry
import com.example.core.resilience.PathTrustLevel
import com.example.core.resilience.PersianConnectivityStrings
import com.example.core.resilience.Plane
import com.example.core.resilience.ProbeEndpoint
import com.example.core.resilience.ProbeEndpointRegistry
import com.example.core.resilience.ProbeEndpointRole
import com.example.core.resilience.ProbeEngine
import com.example.core.resilience.ProbeLayer
import com.example.core.resilience.ProbeResult
import com.example.core.resilience.ProbeRunner
import com.example.core.resilience.RecoveryAction
import com.example.core.resilience.RecoveryPlanner
import com.example.core.resilience.RelayHealthState
import com.example.core.resilience.RelayMode
import com.example.core.resilience.ResilienceJsonSerializer
import com.example.core.resilience.SmartGlobalConnectivityEngine
import com.example.core.resilience.TransportKind
import com.example.core.resilience.TunnelHealthVerifier
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType
import com.example.domain.model.VpnProtocol
import com.example.security.LogRedactor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class NationalInternetResilienceTest {

    private fun buildProbeResult(
        id: String,
        role: ProbeEndpointRole = ProbeEndpointRole.INTERNATIONAL,
        providerGroup: String,
        success: Boolean,
        latencyMs: Long = 80L,
        failureCategory: FailureCategory? = null
    ): ProbeResult {
        return ProbeResult(
            endpointId = EndpointIdHasher.hashEndpoint("https://$providerGroup.example.com", 443, role.name),
            rawEndpointKey = id,
            role = role,
            providerGroup = providerGroup,
            plane = Plane.UNDERLAY,
            layerReached = if (success) ProbeLayer.HTTP else ProbeLayer.IP,
            success = success,
            totalLatencyMs = latencyMs,
            httpStatusCode = if (success) 204 else null,
            failureCategory = if (success) null else (failureCategory ?: FailureCategory.TCP_TIMEOUT)
        )
    }

    @Test
    fun nationalInternetDetector_classifiesAll10RulesAndConfidence() {
        val registry = ProbeEndpointRegistry()
        val probeEngine = ProbeEngine()
        val domesticDiscovery = DomesticReachabilityDiscovery(registry, probeEngine)
        val detector = NationalInternetDetector(registry, probeEngine, domesticDiscovery)

        val onlineSnapshot = NetworkSnapshot(
            networkHandleId = 100L,
            transportKind = TransportKind.WIFI,
            hasInternetCapability = true,
            isValidated = true,
            isNotVpn = true,
            isMetered = false
        )

        // R1: NO_NETWORK when snapshot has no active network
        val offlineSnapshot = NetworkSnapshot.NONE
        val r1 = detector.classifyFromSignals(offlineSnapshot, emptyList(), DomesticReachability.NOT_TESTED)
        assertEquals(NationalInternetState.NO_NETWORK, r1.state)
        assertEquals(100, r1.confidencePercent)

        // R4: UNKNOWN when fewer than 2 international probes have completed
        val r4 = detector.classifyFromSignals(onlineSnapshot, emptyList(), DomesticReachability.NOT_TESTED)
        assertEquals(NationalInternetState.UNKNOWN, r4.state)
        assertTrue(r4.confidence <= 0.4f)

        // R5: NORMAL_INTERNATIONAL when >= 2 of 3 across >= 2 provider groups succeed with RTT < 1500ms
        val normalProbes = listOf(
            buildProbeResult("g1", providerGroup = "google", success = true, latencyMs = 90L),
            buildProbeResult("c1", providerGroup = "cloudflare", success = true, latencyMs = 85L),
            buildProbeResult("m1", providerGroup = "microsoft", success = true, latencyMs = 110L)
        )
        val r5 = detector.classifyFromSignals(onlineSnapshot, normalProbes, DomesticReachability.NOT_TESTED)
        assertEquals(NationalInternetState.NORMAL_INTERNATIONAL, r5.state)
        assertTrue(r5.confidencePercent >= 80)

        // R6: PARTIAL_INTERNATIONAL when 1 provider group succeeds and others fail
        val partialProbes = listOf(
            buildProbeResult("g1", providerGroup = "google", success = true, latencyMs = 140L),
            buildProbeResult("c1", providerGroup = "cloudflare", success = false, failureCategory = FailureCategory.TCP_TIMEOUT),
            buildProbeResult("m1", providerGroup = "microsoft", success = false, failureCategory = FailureCategory.TCP_RESET)
        )
        val r6 = detector.classifyFromSignals(onlineSnapshot, partialProbes, DomesticReachability.NOT_TESTED)
        assertEquals(NationalInternetState.PARTIAL_INTERNATIONAL, r6.state)

        // R8: DOMESTIC_ONLY_SUSPECTED when all >= 3 international probes fail AND domesticReachability == YES
        val allFailedIntl = listOf(
            buildProbeResult("g1", providerGroup = "google", success = false, failureCategory = FailureCategory.TCP_TIMEOUT),
            buildProbeResult("c1", providerGroup = "cloudflare", success = false, failureCategory = FailureCategory.TCP_RESET),
            buildProbeResult("m1", providerGroup = "microsoft", success = false, failureCategory = FailureCategory.TLS_HANDSHAKE_FAILURE)
        )
        val r8 = detector.classifyFromSignals(onlineSnapshot, allFailedIntl, DomesticReachability.YES)
        assertEquals(NationalInternetState.DOMESTIC_ONLY_SUSPECTED, r8.state)

        // R9: RESTRICTED_INTERNATIONAL when all >= 3 international probes fail AND domesticReachability == NOT_TESTED
        // Never claims DOMESTIC_ONLY_SUSPECTED without positive domestic evidence!
        val r9 = detector.classifyFromSignals(onlineSnapshot, allFailedIntl, DomesticReachability.NOT_TESTED)
        assertEquals(NationalInternetState.RESTRICTED_INTERNATIONAL, r9.state)

        // R10: NO_NETWORK when all international AND all configured domestic probes fail
        val r10 = detector.classifyFromSignals(onlineSnapshot, allFailedIntl, DomesticReachability.NO)
        assertEquals(NationalInternetState.NO_NETWORK, r10.state)
    }

    @Test
    fun nationalInternetDetector_enforcesTwoCycleOr10sHysteresis() {
        var now = 1_000_000L
        val registry = ProbeEndpointRegistry()
        val probeEngine = ProbeEngine()
        val domesticDiscovery = DomesticReachabilityDiscovery(registry, probeEngine, clockMsProvider = { now })
        val detector = NationalInternetDetector(registry, probeEngine, domesticDiscovery, clockMsProvider = { now })

        val onlineSnapshot = NetworkSnapshot(
            networkHandleId = 100L,
            transportKind = TransportKind.WIFI,
            hasInternetCapability = true,
            isValidated = true
        )
        val normalProbes = listOf(
            buildProbeResult("g1", providerGroup = "google", success = true),
            buildProbeResult("c1", providerGroup = "cloudflare", success = true),
            buildProbeResult("m1", providerGroup = "microsoft", success = true)
        )
        val restrictedProbes = listOf(
            buildProbeResult("g1", providerGroup = "google", success = false),
            buildProbeResult("c1", providerGroup = "cloudflare", success = false),
            buildProbeResult("m1", providerGroup = "microsoft", success = false)
        )
        val cfg = ConnectivityConfig()

        // First evaluation transitions from UNKNOWN to NORMAL_INTERNATIONAL immediately
        val first = detector.applyHysteresis(
            rawCandidate = NationalInternetState.NORMAL_INTERNATIONAL,
            confidence = 0.9f,
            domestic = DomesticReachability.NOT_TESTED,
            intlResults = normalProbes,
            snapshot = onlineSnapshot,
            config = cfg,
            nowMs = now
        )
        assertEquals(NationalInternetState.NORMAL_INTERNATIONAL, first.state)

        // Single cycle of restricted probes within 2 seconds does NOT flip state yet (hysteresis holds NORMAL_INTERNATIONAL)
        now += 2_000L
        val transient = detector.applyHysteresis(
            rawCandidate = NationalInternetState.RESTRICTED_INTERNATIONAL,
            confidence = 0.9f,
            domestic = DomesticReachability.NOT_TESTED,
            intlResults = restrictedProbes,
            snapshot = onlineSnapshot,
            config = cfg,
            nowMs = now
        )
        assertEquals(NationalInternetState.NORMAL_INTERNATIONAL, transient.state)

        // Second consecutive cycle of restricted probes confirms transition to RESTRICTED_INTERNATIONAL
        now += 2_000L
        val confirmed = detector.applyHysteresis(
            rawCandidate = NationalInternetState.RESTRICTED_INTERNATIONAL,
            confidence = 0.9f,
            domestic = DomesticReachability.NOT_TESTED,
            intlResults = restrictedProbes,
            snapshot = onlineSnapshot,
            config = cfg,
            nowMs = now
        )
        assertEquals(NationalInternetState.RESTRICTED_INTERNATIONAL, confirmed.state)
    }

    @Test
    fun domesticReachabilityDiscovery_returnsNotTestedByDefaultAndAssessesExplicitEndpoints() = runBlocking {
        val fakeRunner = object : ProbeRunner {
            override suspend fun executeProbe(
                endpoint: ProbeEndpoint,
                plane: Plane,
                maxLayer: ProbeLayer,
                config: ConnectivityConfig,
                underlayNetwork: Network?,
                overlaySocksPort: Int?
            ): ProbeResult {
                val ok = endpoint.url.contains("reachable-domestic")
                return buildProbeResult(
                    id = endpoint.id,
                    role = ProbeEndpointRole.DOMESTIC,
                    providerGroup = endpoint.providerGroup,
                    success = ok
                )
            }
        }
        val registry = ProbeEndpointRegistry()
        val probeEngine = ProbeEngine(runner = fakeRunner)
        val discovery = DomesticReachabilityDiscovery(registry, probeEngine)
        val config = ConnectivityConfig()

        // Default empty list -> NOT_TESTED
        val defaultRes = discovery.assessDomesticReachability(config, forceRefresh = true)
        assertEquals(DomesticReachabilityState.NOT_TESTED, defaultRes.state)

        // Reachable explicit domestic endpoint -> YES
        registry.setCustomDomesticUrls(listOf("https://reachable-domestic.example.ir/health"))
        val yesRes = discovery.assessDomesticReachability(config, forceRefresh = true)
        assertEquals(DomesticReachabilityState.YES, yesRes.state)

        // Unreachable explicit domestic endpoint -> NO
        registry.setCustomDomesticUrls(listOf("https://offline-domestic.example.ir/health"))
        val noRes = discovery.assessDomesticReachability(config, forceRefresh = true)
        assertEquals(DomesticReachabilityState.NO, noRes.state)
    }

    @Test
    fun failureClassifier_accuratelyMapsDnsTamperingClockSkewResetTlsAndPortal() {
        // 1. DNS poisoning / sinkhole IP detection for public domains
        assertTrue(FailureClassifier.isSuspiciousResolvedIpForPublicHost("cp.cloudflare.com", "10.10.34.34"))
        assertTrue(FailureClassifier.isSuspiciousResolvedIpForPublicHost("www.gstatic.com", "127.0.0.1"))
        assertFalse(FailureClassifier.isSuspiciousResolvedIpForPublicHost("cp.cloudflare.com", "104.16.132.229"))

        // 2. TCP Reset / Refused / Timeout / TLS Certificate / Handshake exceptions
        assertEquals(
            FailureCategory.DNS_FAILURE,
            FailureClassifier.classifyException(UnknownHostException("Unable to resolve host"), ProbeLayer.DNS)
        )
        assertEquals(
            FailureCategory.TCP_TIMEOUT,
            FailureClassifier.classifyException(SocketTimeoutException("connect timed out"), ProbeLayer.IP)
        )
        assertEquals(
            FailureCategory.TCP_REFUSED,
            FailureClassifier.classifyException(IOException("Connection refused (ECONNREFUSED)"), ProbeLayer.IP)
        )
        assertEquals(
            FailureCategory.TCP_RESET,
            FailureClassifier.classifyException(IOException("Connection reset by peer (ECONNRESET)"), ProbeLayer.IP)
        )
        assertEquals(
            FailureCategory.TLS_CERTIFICATE_INVALID,
            FailureClassifier.classifyException(SSLPeerUnverifiedException("Hostname not verified"), ProbeLayer.TLS)
        )
        assertEquals(
            FailureCategory.TLS_HANDSHAKE_FAILURE,
            FailureClassifier.classifyException(SSLHandshakeException("Remote host terminated the handshake"), ProbeLayer.TLS)
        )

        // 3. Clock skew > 90s during TLS failure
        assertEquals(
            FailureCategory.CLOCK_SKEW_SUSPECTED,
            FailureClassifier.classifyException(
                throwable = SSLHandshakeException("certificate not yet valid"),
                layer = ProbeLayer.TLS,
                clockSkewSeconds = 180L
            )
        )

        // 4. Captive portal / ISP block page redirect
        assertEquals(
            FailureCategory.CAPTIVE_PORTAL_SUSPECTED,
            FailureClassifier.classifyHttpResponse(
                statusCode = 302,
                expectedStatuses = setOf(204),
                locationHeader = "http://10.10.34.35/login"
            )
        )
    }

    @Test
    fun circuitBreakerAndRecoveryPlanner_preventLoopsAndRespectTerminalErrors() {
        var now = 5_000_000L
        val cb = CircuitBreakerRegistry(clockMsProvider = { now })
        val planner = RecoveryPlanner(
            circuitBreakers = cb,
            clockMsProvider = { now },
            jitterProvider = { 0.0 }
        )

        val profileA = ServerProfile(
            id = 1L,
            remarks = "Server A",
            protocol = VpnProtocol.VLESS,
            address = "198.51.100.1",
            port = 443,
            credential = "11111111-2222-3333-4444-555555555555",
            network = TransportType.WS,
            security = SecurityType.TLS
        )
        val profileB = profileA.copy(
            id = 2L,
            remarks = "Server B",
            address = "198.51.100.2",
            network = TransportType.GRPC
        )

        val pathA = PathRegistry.buildDirectPath(profileA)
        val pathB = PathRegistry.buildDirectPath(profileB)

        val normalAssessment = NetworkAssessment(
            state = NationalInternetState.NORMAL_INTERNATIONAL,
            confidence = 0.90f,
            domesticReachability = DomesticReachability.NOT_TESTED,
            internationalTestedCount = 3,
            internationalSuccessCount = 3,
            providerGroupsTested = 3,
            providerGroupsSucceeded = 3,
            medianLatencyMs = 80L,
            transportKind = TransportKind.WIFI
        )

        // 1. Terminal error (AUTHENTICATION_FAILURE) immediately halts retries
        val authDecision = planner.planNextAction(
            assessment = normalAssessment,
            currentPath = pathA,
            failureCategory = FailureCategory.AUTHENTICATION_FAILURE,
            samePathAttemptCount = 0,
            longOutageStep = 0,
            candidatePaths = listOf(pathA, pathB)
        )
        assertTrue(authDecision is RecoveryAction.HaltTerminal)

        // 2. 3 failures opens circuit breaker for pathA and switches to pathB
        cb.recordFailure(pathA.pathId, FailureCategory.TCP_TIMEOUT, TransportKind.WIFI)
        cb.recordFailure(pathA.pathId, FailureCategory.TCP_TIMEOUT, TransportKind.WIFI)
        assertFalse(cb.isOpen(pathA.pathId, TransportKind.WIFI))
        cb.recordFailure(pathA.pathId, FailureCategory.TCP_TIMEOUT, TransportKind.WIFI)
        assertTrue(cb.isOpen(pathA.pathId, TransportKind.WIFI))

        val switchDecision = planner.planNextAction(
            assessment = normalAssessment,
            currentPath = pathA,
            failureCategory = FailureCategory.TCP_TIMEOUT,
            samePathAttemptCount = 1,
            longOutageStep = 0,
            candidatePaths = listOf(pathA, pathB)
        )
        assertTrue(switchDecision is RecoveryAction.SwitchPath)
        assertEquals(pathB.pathId, (switchDecision as RecoveryAction.SwitchPath).targetPath.pathId)

        // 3. Transport change (WIFI -> CELLULAR) resets network-dependent circuit breaker
        cb.onTransportChanged(TransportKind.CELLULAR)
        assertFalse(cb.isOpen(pathA.pathId, TransportKind.CELLULAR))

        // 4. Restart-storm guard caps core restarts at 6 per 10-minute window
        repeat(6) {
            now += 5_000L
            planner.recordCoreRestart(now)
        }
        assertFalse(planner.canRestartCore(nowMs = now))

        // 5. Long-outage ladder escalates up to 15 minutes (900,000 ms)
        assertEquals(30_000L, planner.computeLongOutagePollDelayMs(0))
        assertEquals(60_000L, planner.computeLongOutagePollDelayMs(1))
        assertEquals(900_000L, planner.computeLongOutagePollDelayMs(5))
    }

    @Test
    fun pathRegistry_preservesGatewaySniInModeA2AndEnforcesTrustIsolation() = runBlocking {
        val personalProfile = ServerProfile(
            id = 10L,
            remarks = "Personal Edge",
            protocol = VpnProtocol.VLESS,
            address = "edge.foreign-gateway.example.com",
            port = 443,
            credential = "11111111-2222-3333-4444-555555555555",
            network = TransportType.WS,
            security = SecurityType.TLS,
            sni = "sni.foreign-gateway.example.com",
            host = "cdn.foreign-gateway.example.com",
            fingerprint = "chrome"
        )
        val freeProfile = personalProfile.copy(
            id = -5L,
            remarks = "Auto Free Node"
        )
        val relay = AuthorizedRelay(
            id = "relay_tehran_1",
            name = "Tehran Relay 1",
            host = "185.143.233.10",
            port = 8443,
            mode = RelayMode.MODE_A2_TRAFFIC_FORWARDER,
            enabled = true,
            ownerAttested = true
        )

        val config = ConnectivityConfig(
            masterResilienceEnabled = true,
            allowRuntimeVariants = true,
            relayFallbackEnabled = true
        )
        val pathRegistry = PathRegistry()

        // MY_VPN mode builds direct + runtime variants + Mode A2 relay paths
        val myVpnPaths = pathRegistry.buildCandidatePaths(
            primaryProfile = personalProfile,
            allProfiles = listOf(personalProfile),
            authorizedRelays = listOf(relay),
            config = config,
            vpnMode = VpnAppMode.MY_VPN
        )
        val relayPath = myVpnPaths.first { it.viaRelayId == relay.id }
        assertEquals(PathTrustLevel.TRUSTED_USER, relayPath.trustLevel)

        // Applying Mode A2 override replaces address & port with relay while preserving original gateway sni & host
        val overridden = PathRegistry.applyPathOverrides(personalProfile, relayPath, listOf(relay))
        assertEquals("185.143.233.10", overridden.address)
        assertEquals(8443, overridden.port)
        assertEquals("sni.foreign-gateway.example.com", overridden.sni)
        assertEquals("cdn.foreign-gateway.example.com", overridden.host)

        // FREE_VPN mode strictly isolates UNTRUSTED paths and never attaches personal AuthorizedRelays
        val freePaths = pathRegistry.buildCandidatePaths(
            primaryProfile = freeProfile,
            allProfiles = listOf(freeProfile),
            authorizedRelays = listOf(relay),
            config = config,
            vpnMode = VpnAppMode.FREE_VPN
        )
        assertTrue(freePaths.all { it.trustLevel == PathTrustLevel.UNTRUSTED })
        assertTrue(freePaths.none { it.viaRelayId != null })
    }

    @Test
    fun endpointIdHasherAndLogRedactor_sanitizeIpsHostsAndCredentials() {
        val ep1 = EndpointIdHasher.hashEndpoint("198.51.100.42", 443, "vless:ws:tls")
        assertTrue(ep1.startsWith("ep_"))
        assertEquals(15, ep1.length) // "ep_" + 12 hex chars
        assertEquals("443", EndpointIdHasher.portBucket(443))
        assertEquals("alt-tls", EndpointIdHasher.portBucket(8443))
        assertEquals("high-port", EndpointIdHasher.portBucket(10443))

        val rawDiagnostic = "Failed connecting to 198.51.100.42:443 (sni=secret-node.myserver.org) with id=11111111-2222-3333-4444-555555555555"
        val sanitized = LogRedactor.redactDiagnostics(rawDiagnostic)
        assertFalse(sanitized.contains("198.51.100.42"))
        assertFalse(sanitized.contains("secret-node.myserver.org"))
        assertFalse(sanitized.contains("11111111-2222-3333-4444-555555555555"))
        assertTrue(sanitized.contains("[REDACTED-IP]"))
        assertTrue(sanitized.contains("[REDACTED-HOST]"))
        assertTrue(sanitized.contains("[REDACTED-UUID]"))
    }

    @Test
    fun smartGlobalConnectivityEngine_respectsMasterKillSwitchAndPersianStrings() = runBlocking {
        val engine = SmartGlobalConnectivityEngine()
        engine.updateConfig(ConnectivityConfig(masterResilienceEnabled = false))

        val p1 = ServerProfile(
            id = 1L,
            remarks = "Server 1",
            protocol = VpnProtocol.VLESS,
            address = "203.0.113.10",
            port = 443,
            credential = "11111111-2222-3333-4444-555555555555",
            lastLatencyMs = 150L
        )
        val p2 = p1.copy(id = 2L, remarks = "Server 2", lastLatencyMs = 60L)

        // When masterResilienceEnabled = false, rankCandidateProfiles sorts purely by existing latency
        val ranked = engine.rankCandidateProfiles(VpnAppMode.MY_VPN, listOf(p1, p2))
        assertEquals(2L, ranked.first().id)

        // Verify Persian connectivity strings match exact specification
        assertEquals("وضعیت شبکه: اینترنت بین‌الملل در دسترس", PersianConnectivityStrings.NORMAL_INTERNATIONAL)
        assertEquals("وضعیت شبکه: اینترنت بین‌الملل محدود یا ناپایدار", PersianConnectivityStrings.RESTRICTED_INTERNATIONAL)
        assertEquals("وضعیت شبکه: احتمال محدودیت به شبکه داخلی", PersianConnectivityStrings.DOMESTIC_ONLY_SUSPECTED)
        assertEquals("اتصال امن برقرار شد", PersianConnectivityStrings.SECURE_CONNECTED)
        assertEquals("اتصال از طریق مسیر واسط برقرار شد", PersianConnectivityStrings.VIA_RELAY)
        assertEquals("از طریق مسیر واسط", PersianConnectivityStrings.VIA_RELAY_BADGE)

        // Verify AuthorizedRelay JSON round-trip
        val relay = AuthorizedRelay(
            id = "r1",
            name = "Relay 1",
            host = "185.10.20.30",
            port = 443,
            mode = RelayMode.MODE_A2_TRAFFIC_FORWARDER,
            enabled = true,
            associatedProfileIds = setOf(1L, 2L),
            ownerAttested = true
        )
        val json = ResilienceJsonSerializer.serializeAuthorizedRelays(listOf(relay))
        val parsed = ResilienceJsonSerializer.parseAuthorizedRelays(json)
        assertEquals(1, parsed.size)
        assertEquals("Relay 1", parsed.first().name)
        assertEquals("185.10.20.30", parsed.first().address)
        assertNotNull(parsed.first().targetServerIds)
    }
}
