package com.example.core.resilience

import android.net.Network
import com.example.core.autovpn.VpnAppMode
import com.example.domain.model.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Central Smart Global Connectivity Engine (Section 7 & Section 11).
 *
 * Coordinates:
 * - [NationalInternetDetector] and [DomesticReachabilityDiscovery]
 * - [PathRegistry] and [ServerReachabilityAssessor]
 * - [TunnelHealthVerifier]
 * - [RecoveryPlanner] and [CircuitBreakerRegistry]
 * - [FailureLog]
 * - [ConnectivityAdvisor] integration with `SmartEngine` / `SmartFailoverManager`
 * - Exact Persian UI status generation (`ResilienceUiSnapshot`)
 */
class SmartGlobalConnectivityEngine(
    val endpointRegistry: ProbeEndpointRegistry = ProbeEndpointRegistry(),
    val probeEngine: ProbeEngine = ProbeEngine(),
    val domesticDiscovery: DomesticReachabilityDiscovery = DomesticReachabilityDiscovery(
        endpointRegistry = endpointRegistry,
        probeEngine = probeEngine
    ),
    val nationalDetector: NationalInternetDetector = NationalInternetDetector(
        endpointRegistry = endpointRegistry,
        probeEngine = probeEngine,
        domesticDiscovery = domesticDiscovery
    ),
    val pathRegistry: PathRegistry = PathRegistry(),
    val reachabilityAssessor: ServerReachabilityAssessor = ServerReachabilityAssessor(),
    val tunnelVerifier: TunnelHealthVerifier = TunnelHealthVerifier(
        endpointRegistry = endpointRegistry,
        probeEngine = probeEngine
    ),
    val circuitBreakers: CircuitBreakerRegistry = CircuitBreakerRegistry(),
    val recoveryPlanner: RecoveryPlanner = RecoveryPlanner(circuitBreakers = circuitBreakers),
    val failureLog: FailureLog = FailureLog(),
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() }
) : ConnectivityAdvisor {

    private val lock = Any()
    private var currentConfig: ConnectivityConfig = ConnectivityConfig()
    private var currentRelays: List<AuthorizedRelay> = emptyList()
    private var activePath: ConnectivityPath? = null
    private var engineState: EngineResilienceState = EngineResilienceState.IDLE
    private var tunnelVerified: Boolean = false
    private var samePathAttemptCount: Int = 0
    private var longOutageStep: Int = 0
    private var nextRetryInMs: Long? = null
    private var terminalHeadlineOverride: String? = null
    private val relayReports = ConcurrentHashMap<String, RelayHealthReport>()

    val circuitBreakerRegistry: CircuitBreakerRegistry
        get() = circuitBreakers

    private val _uiSnapshot = MutableStateFlow(ResilienceUiSnapshot())
    val uiSnapshot: StateFlow<ResilienceUiSnapshot> = _uiSnapshot.asStateFlow()

    fun initializeAssets(context: android.content.Context) {
        endpointRegistry.loadFromAssetsIfAvailable(context.applicationContext)
    }

    fun updateConfig(config: ConnectivityConfig) {
        synchronized(lock) {
            currentConfig = config
        }
        endpointRegistry.setCustomDomesticUrls(config.customDomesticEndpoints)
        publishSnapshot()
    }

    fun getConfig(): ConnectivityConfig = synchronized(lock) { currentConfig }

    fun updateAuthorizedRelays(relays: List<AuthorizedRelay>) {
        synchronized(lock) {
            currentRelays = relays
        }
        publishSnapshot()
    }

    fun getAuthorizedRelays(): List<AuthorizedRelay> = synchronized(lock) { currentRelays }

    override fun getLatestAssessment(): NetworkAssessment =
        nationalDetector.assessmentFlow.value

    override fun isCircuitOpen(pathId: String, currentTransport: TransportKind): Boolean =
        circuitBreakers.isOpen(pathId, currentTransport)

    override fun rankCandidatePaths(
        candidates: List<ConnectivityPath>,
        assessment: NetworkAssessment
    ): List<ConnectivityPath> {
        val cfg = getConfig()
        val available = candidates.filter { path ->
            !circuitBreakers.isOpen(path.pathId, assessment.transportKind)
        }
        val pool = available.ifEmpty { candidates }
        return pool.sortedByDescending { path ->
            val lat = path.profile.lastLatencyMs ?: 350L
            val baseScore = (1000L - lat.coerceIn(0L, 950L)).toDouble() / 10.0
            val resilienceBonus = if (cfg.masterResilienceEnabled) {
                PathRegistry.transportResilienceBonus(path, assessment.state)
            } else {
                0.0
            }
            baseScore + resilienceBonus
        }
    }

    fun rankCandidateProfiles(
        mode: VpnAppMode,
        profiles: List<ServerProfile>
    ): List<ServerProfile> {
        if (profiles.isEmpty()) return emptyList()
        val cfg = getConfig()
        if (!cfg.masterResilienceEnabled) {
            return profiles.sortedBy { it.lastLatencyMs ?: Long.MAX_VALUE }
        }
        val assessment = getLatestAssessment()
        val paths = pathRegistry.buildCandidatePaths(
            primaryProfile = profiles.firstOrNull(),
            allProfiles = profiles,
            authorizedRelays = getAuthorizedRelays(),
            config = cfg,
            vpnMode = mode,
            networkHandleId = assessment.networkHandleId
        )
        return rankCandidatePaths(paths, assessment)
            .map { pathRegistry.materializeProfileForPath(it) }
            .distinctBy { "${it.id}_${it.address}_${it.port}" }
    }

    /**
     * Runs an underlay network assessment and updates the engine state.
     */
    suspend fun assessUnderlay(
        networkSnapshot: NetworkSnapshot,
        underlayNetwork: Network? = null,
        forceRefresh: Boolean = false
    ): NetworkAssessment {
        val cfg = getConfig()
        if (!cfg.masterResilienceEnabled) {
            return NetworkAssessment.INITIAL
        }
        synchronized(lock) {
            if (engineState == EngineResilienceState.IDLE) {
                engineState = EngineResilienceState.ASSESSING
            }
        }
        publishSnapshot()

        val assessment = nationalDetector.assessNetwork(
            snapshot = networkSnapshot,
            config = cfg,
            underlayNetwork = underlayNetwork,
            forceRefresh = forceRefresh
        )
        publishSnapshot()
        return assessment
    }

    /**
     * Selects the best starting [ConnectivityPath] for a connection request.
     * When [ConnectivityConfig.masterResilienceEnabled] is false, returns the direct path for
     * [requestedProfile] without probing or modification.
     */
    suspend fun selectInitialPath(
        requestedProfile: ServerProfile,
        allProfiles: List<ServerProfile>,
        vpnMode: VpnAppMode,
        networkSnapshot: NetworkSnapshot,
        underlayNetwork: Network? = null
    ): ConnectivityPath {
        val cfg = getConfig()
        val directPrimary = ConnectivityPath(
            pathId = "direct_${requestedProfile.id}_${requestedProfile.address}_${requestedProfile.port}",
            profile = requestedProfile,
            trustLevel = if (PathRegistry.isAutoDiscoveredUntrustedProfile(requestedProfile)) {
                PathTrustLevel.UNTRUSTED
            } else {
                PathTrustLevel.TRUSTED_USER
            },
            networkHandleId = networkSnapshot.networkHandleId
        )

        if (!cfg.masterResilienceEnabled) {
            synchronized(lock) {
                activePath = directPrimary
            }
            return directPrimary
        }

        synchronized(lock) {
            engineState = EngineResilienceState.ASSESSING
            tunnelVerified = false
            terminalHeadlineOverride = null
            nextRetryInMs = null
        }
        publishSnapshot()

        val assessment = nationalDetector.assessNetwork(
            snapshot = networkSnapshot,
            config = cfg,
            underlayNetwork = underlayNetwork,
            forceRefresh = false
        )

        val candidates = pathRegistry.buildCandidatePaths(
            primaryProfile = requestedProfile,
            allProfiles = allProfiles,
            authorizedRelays = getAuthorizedRelays(),
            config = cfg,
            vpnMode = vpnMode,
            networkHandleId = networkSnapshot.networkHandleId
        )

        val ranked = rankCandidatePaths(candidates, assessment)
        val chosen = if (assessment.state == NationalInternetState.DOMESTIC_ONLY_SUSPECTED) {
            // Under suspected domestic-only restriction, prefer an authorized relay path if configured
            ranked.firstOrNull { it.isViaRelay } ?: ranked.firstOrNull() ?: directPrimary
        } else {
            // Prefer the user's requested direct path if its circuit breaker is not open
            if (!circuitBreakers.isOpen(directPrimary.pathId, assessment.transportKind)) {
                directPrimary
            } else {
                ranked.firstOrNull() ?: directPrimary
            }
        }

        synchronized(lock) {
            activePath = chosen
            engineState = EngineResilienceState.CONNECTING
            samePathAttemptCount = 0
        }
        publishSnapshot()
        return chosen
    }

    /**
     * Transitions to [EngineResilienceState.VERIFYING] when the VPN core starts and then runs
     * [TunnelHealthVerifier.verifyTunnel] over the local SOCKS5 port.
     *
     * Honesty Rule (Section 1.2 H-2 / Section 8.2):
     * While verifying, the UI shows «در حال تأیید اتصال» (`VERIFYING_CONNECTION`), and transitions
     * to «اتصال امن برقرار شد» (`SECURE_CONNECTED`) ONLY after overlay verification succeeds.
     */
    suspend fun verifyEstablishedTunnel(
        path: ConnectivityPath,
        localSocksPort: Int,
        rxBytesDelta: Long = 0L,
        txBytesDelta: Long = 0L
    ): TunnelVerificationReport {
        val cfg = getConfig()
        synchronized(lock) {
            activePath = path
            engineState = EngineResilienceState.VERIFYING
            tunnelVerified = false
            nextRetryInMs = null
        }
        publishSnapshot()

        val report = tunnelVerifier.verifyTunnel(
            overlaySocksPort = localSocksPort,
            config = cfg,
            rxBytesDelta = rxBytesDelta,
            txBytesDelta = txBytesDelta
        )

        synchronized(lock) {
            if (report.verified) {
                tunnelVerified = true
                engineState = EngineResilienceState.CONNECTED
                samePathAttemptCount = 0
                longOutageStep = 0
                circuitBreakers.recordSuccess(path.pathId)
                path.relay?.let { relay ->
                    relayReports[relay.id] = RelayHealthReport(
                        relayId = relay.id,
                        endpointId = EndpointIdHasher.hashEndpoint(relay.host, relay.port, "relay"),
                        state = RelayHealthState.RELAY_FORWARDING_OK,
                        forwardingLatencyMs = report.medianOverlayLatencyMs,
                        timestampMs = clockMsProvider()
                    )
                }
            } else {
                tunnelVerified = false
                engineState = EngineResilienceState.DEGRADED
                path.relay?.let { relay ->
                    relayReports[relay.id] = RelayHealthReport(
                        relayId = relay.id,
                        endpointId = EndpointIdHasher.hashEndpoint(relay.host, relay.port, "relay"),
                        state = RelayHealthState.RELAY_REACHABLE,
                        failureCategory = report.dominantFailure ?: FailureCategory.TUNNEL_NO_TRAFFIC,
                        timestampMs = clockMsProvider()
                    )
                }
            }
        }
        publishSnapshot()
        return report
    }

    /**
     * Records a failure on [failedPath], updates the circuit breaker and [FailureLog],
     * and returns the planned [RecoveryAction].
     */
    fun onPathFailure(
        failedPath: ConnectivityPath,
        failureCategory: FailureCategory,
        rttMs: Long? = null,
        allProfiles: List<ServerProfile>,
        vpnMode: VpnAppMode = VpnAppMode.MY_VPN
    ): RecoveryAction {
        val cfg = getConfig()
        val assessment = nationalDetector.assessmentFlow.value
        val now = clockMsProvider()

        circuitBreakers.recordFailure(
            pathId = failedPath.pathId,
            category = failureCategory,
            transportKind = assessment.transportKind,
            config = cfg
        )

        val candidates = pathRegistry.buildCandidatePaths(
            primaryProfile = failedPath.profile,
            allProfiles = allProfiles,
            authorizedRelays = getAuthorizedRelays(),
            config = cfg,
            vpnMode = vpnMode,
            networkHandleId = assessment.networkHandleId
        )
        val rankedCandidates = rankCandidatePaths(candidates, assessment)

        val currentAttempts = synchronized(lock) { samePathAttemptCount }
        val currentOutageStep = synchronized(lock) { longOutageStep }

        val action = recoveryPlanner.planNextAction(
            assessment = assessment,
            currentPath = failedPath,
            failureCategory = failureCategory,
            samePathAttemptCount = currentAttempts,
            longOutageStep = currentOutageStep,
            candidatePaths = rankedCandidates,
            config = cfg
        )

        val outcome = when (action) {
            is RecoveryAction.RetrySamePath -> RecoveryOutcome.PENDING
            is RecoveryAction.SwitchPath -> RecoveryOutcome.SWITCHED
            is RecoveryAction.EnterLongOutageWait -> RecoveryOutcome.PENDING
            is RecoveryAction.HaltTerminal -> RecoveryOutcome.GAVE_UP
            is RecoveryAction.KeepCurrent -> RecoveryOutcome.RECOVERED
        }

        failureLog.record(
            FailureRecord(
                timestampEpochMs = now,
                networkType = assessment.transportKind,
                nationalInternetState = assessment.state,
                nationalStateConfidence = assessment.confidence,
                domesticReachable = assessment.domesticReachability.state,
                endpointId = EndpointIdHasher.hashEndpoint(
                    failedPath.effectiveHost,
                    failedPath.effectivePort,
                    failedPath.profile.network.name
                ),
                transport = failedPath.profile.network.name,
                portBucket = EndpointIdHasher.portBucket(failedPath.effectivePort),
                failureCategory = failureCategory,
                rttMs = rttMs,
                recoveryActionTaken = action.label,
                outcome = outcome
            )
        )

        synchronized(lock) {
            tunnelVerified = false
            when (action) {
                is RecoveryAction.RetrySamePath -> {
                    engineState = EngineResilienceState.RECOVERING
                    samePathAttemptCount += 1
                    nextRetryInMs = action.delayMs
                }
                is RecoveryAction.SwitchPath -> {
                    engineState = EngineResilienceState.RECOVERING
                    activePath = action.targetPath
                    samePathAttemptCount = 0
                    nextRetryInMs = null
                    recoveryPlanner.recordCoreRestart(now)
                }
                is RecoveryAction.EnterLongOutageWait -> {
                    engineState = EngineResilienceState.LONG_OUTAGE_WAIT
                    longOutageStep = (longOutageStep + 1).coerceAtMost(5)
                    nextRetryInMs = action.pollDelayMs
                }
                is RecoveryAction.HaltTerminal -> {
                    engineState = EngineResilienceState.TERMINAL_ERROR
                    terminalHeadlineOverride = action.userMessagePersian
                    nextRetryInMs = null
                }
                is RecoveryAction.KeepCurrent -> {
                    nextRetryInMs = null
                }
            }
        }
        publishSnapshot()
        return action
    }

    /**
     * Handles physical network changes (e.g., Wi-Fi <-> Mobile Data).
     * Clears stale DNS cache, resets network-dependent circuit breakers, and invalidates probe cache.
     */
    fun onUnderlyingNetworkChanged(newSnapshot: NetworkSnapshot) {
        reachabilityAssessor.clearDnsCache()
        probeEngine.invalidateCache()
        circuitBreakers.onTransportChanged(newSnapshot.transportKind)
        synchronized(lock) {
            if (engineState == EngineResilienceState.LONG_OUTAGE_WAIT &&
                newSnapshot.hasInternetCapability
            ) {
                longOutageStep = 0
                nextRetryInMs = null
                engineState = EngineResilienceState.RECOVERING
            }
        }
        publishSnapshot()
    }

    fun onStoppedByUser() {
        synchronized(lock) {
            engineState = EngineResilienceState.STOPPED_BY_USER
            tunnelVerified = false
            activePath = null
            samePathAttemptCount = 0
            longOutageStep = 0
            nextRetryInMs = null
            terminalHeadlineOverride = null
        }
        publishSnapshot()
    }

    fun resetToIdle() {
        synchronized(lock) {
            engineState = EngineResilienceState.IDLE
            tunnelVerified = false
            activePath = null
            samePathAttemptCount = 0
            longOutageStep = 0
            nextRetryInMs = null
            terminalHeadlineOverride = null
        }
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val assessment = nationalDetector.assessmentFlow.value
        val failures = failureLog.snapshot()
        val snap = synchronized(lock) {
            val isRelay = activePath?.isViaRelay == true
            val headline = computePersianHeadlineLocked(assessment, isRelay)
            val subtitle = computePersianSubtitleLocked(assessment, isRelay)
            val badge = if (isRelay && (engineState == EngineResilienceState.CONNECTED || engineState == EngineResilienceState.VERIFYING)) {
                PersianConnectivityStrings.VIA_RELAY_BADGE
            } else {
                null
            }
            ResilienceUiSnapshot(
                enabled = currentConfig.masterResilienceEnabled,
                engineState = engineState,
                networkAssessment = assessment,
                activePath = activePath,
                tunnelVerified = tunnelVerified,
                isViaRelay = isRelay,
                headlinePersian = headline,
                subtitlePersian = subtitle,
                badgePersian = badge,
                recentFailures = failures.take(20),
                relayReports = relayReports.toMap(),
                nextRetryInMs = nextRetryInMs
            )
        }
        _uiSnapshot.value = snap
    }

    private fun computePersianHeadlineLocked(
        assessment: NetworkAssessment,
        isViaRelay: Boolean
    ): String {
        terminalHeadlineOverride?.let { return it }

        return when (engineState) {
            EngineResilienceState.VERIFYING,
            EngineResilienceState.CONNECTING -> PersianConnectivityStrings.VERIFYING_CONNECTION

            EngineResilienceState.RECOVERING -> PersianConnectivityStrings.TESTING_PATHS

            EngineResilienceState.CONNECTED -> {
                if (!tunnelVerified) {
                    PersianConnectivityStrings.VERIFYING_CONNECTION
                } else if (isViaRelay) {
                    PersianConnectivityStrings.VIA_RELAY
                } else {
                    PersianConnectivityStrings.SECURE_CONNECTED
                }
            }

            EngineResilienceState.LONG_OUTAGE_WAIT -> {
                if (assessment.state == NationalInternetState.NO_NETWORK) {
                    PersianConnectivityStrings.NO_NETWORK
                } else {
                    PersianConnectivityStrings.NO_INTERNATIONAL_ROUTE
                }
            }

            else -> {
                when (assessment.state) {
                    NationalInternetState.NORMAL_INTERNATIONAL ->
                        PersianConnectivityStrings.NORMAL_INTERNATIONAL
                    NationalInternetState.RESTRICTED_INTERNATIONAL,
                    NationalInternetState.PARTIAL_INTERNATIONAL,
                    NationalInternetState.NETWORK_UNSTABLE ->
                        PersianConnectivityStrings.RESTRICTED_INTERNATIONAL
                    NationalInternetState.DOMESTIC_ONLY_SUSPECTED ->
                        PersianConnectivityStrings.DOMESTIC_ONLY_SUSPECTED
                    NationalInternetState.CAPTIVE_PORTAL_SUSPECTED ->
                        PersianConnectivityStrings.CAPTIVE_PORTAL
                    NationalInternetState.NO_NETWORK ->
                        PersianConnectivityStrings.NO_NETWORK
                    NationalInternetState.APP_BLOCKED_BY_OS,
                    NationalInternetState.UNKNOWN ->
                        PersianConnectivityStrings.NORMAL_INTERNATIONAL
                }
            }
        }
    }

    private fun computePersianSubtitleLocked(
        assessment: NetworkAssessment,
        isViaRelay: Boolean
    ): String {
        val stateLabel = when (assessment.state) {
            NationalInternetState.NORMAL_INTERNATIONAL ->
                PersianConnectivityStrings.NORMAL_INTERNATIONAL
            NationalInternetState.RESTRICTED_INTERNATIONAL,
            NationalInternetState.PARTIAL_INTERNATIONAL,
            NationalInternetState.NETWORK_UNSTABLE ->
                PersianConnectivityStrings.RESTRICTED_INTERNATIONAL
            NationalInternetState.DOMESTIC_ONLY_SUSPECTED ->
                PersianConnectivityStrings.DOMESTIC_ONLY_SUSPECTED
            NationalInternetState.CAPTIVE_PORTAL_SUSPECTED ->
                PersianConnectivityStrings.CAPTIVE_PORTAL
            NationalInternetState.NO_NETWORK ->
                PersianConnectivityStrings.NO_NETWORK
            else -> "در حال ارزیابی هوشمند وضعیت شبکه"
        }
        val confPct = (assessment.confidence * 100).toInt().coerceIn(0, 100)
        val relaySuffix = if (isViaRelay) " • ${PersianConnectivityStrings.VIA_RELAY_BADGE}" else ""
        return "$stateLabel (اطمینان: $confPct٪)$relaySuffix"
    }
}
