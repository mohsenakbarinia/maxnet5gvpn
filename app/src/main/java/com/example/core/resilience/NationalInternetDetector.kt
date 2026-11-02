package com.example.core.resilience

import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil

/**
 * Multi-signal, confidence-based National Internet & Global Connectivity detector.
 *
 * Implements the 10 ordered classification rules (Section 5.1), the pure confidence formula
 * (Section 5.2), and the 2-cycle / 10-second hysteresis filter (Section 5.3).
 */
class NationalInternetDetector(
    private val endpointRegistry: ProbeEndpointRegistry,
    private val probeEngine: ProbeEngine,
    private val domesticDiscovery: DomesticReachabilityDiscovery,
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var committedState: NationalInternetState = NationalInternetState.UNKNOWN
    private var committedStateSinceMs: Long = 0L
    private var pendingCandidateState: NationalInternetState? = null
    private var pendingCandidateFirstSeenMs: Long = 0L
    private var pendingCandidateConsecutiveCount: Int = 0

    // Rolling latency / loss history across the last 4 cycles for instability detection (R7)
    private val recentCycleLossRatios = ArrayDeque<Float>(4)
    private val recentCycleJitterRatios = ArrayDeque<Float>(4)

    private val _assessmentFlow = MutableStateFlow(NetworkAssessment.INITIAL)
    val assessmentFlow: StateFlow<NetworkAssessment> = _assessmentFlow.asStateFlow()

    /**
     * Runs an underlay assessment cycle and updates [assessmentFlow].
     */
    suspend fun assessNetwork(
        snapshot: NetworkSnapshot,
        config: ConnectivityConfig = ConnectivityConfig(),
        underlayNetwork: Network? = null,
        forceRefresh: Boolean = false
    ): NetworkAssessment {
        val now = clockMsProvider()

        // R1: If no active physical network or no internet capability -> NO_NETWORK immediately
        if (snapshot.transportKind == TransportKind.NONE || !snapshot.hasInternetCapability) {
            val noNetAssessment = applyHysteresis(
                rawCandidate = NationalInternetState.NO_NETWORK,
                confidence = 1.0f,
                domestic = DomesticReachability.NOT_TESTED,
                intlResults = emptyList(),
                snapshot = snapshot,
                config = config,
                nowMs = now
            )
            _assessmentFlow.value = noNetAssessment
            return noNetAssessment
        }

        val intlEndpoints = endpointRegistry.getInternationalEndpoints()
        val intlResults = probeEngine.runProbeCycle(
            endpoints = intlEndpoints,
            plane = Plane.UNDERLAY,
            maxLayer = ProbeLayer.HTTP,
            config = config,
            isMeteredNetwork = snapshot.isMetered,
            underlayNetwork = underlayNetwork,
            forceRefresh = forceRefresh
        )

        // Check if international connectivity is impaired; if so (or if configured), also check domestic
        val intlSuccesses = intlResults.count { it.success }
        val domesticReachability = if (intlSuccesses < intlResults.size || endpointRegistry.getDomesticEndpoints().isNotEmpty()) {
            domesticDiscovery.assessDomesticReachability(
                config = config,
                isMetered = snapshot.isMetered,
                underlayNetwork = underlayNetwork,
                forceRefresh = forceRefresh
            )
        } else {
            DomesticReachability.NOT_TESTED
        }

        val evaluation = classifyFromSignals(
            snapshot = snapshot,
            intlResults = intlResults,
            domesticReachability = domesticReachability,
            totalConfiguredIntl = intlEndpoints.size.coerceAtLeast(3),
            nowMs = now
        )

        val finalAssessment = applyHysteresis(
            rawCandidate = evaluation.state,
            confidence = evaluation.confidence,
            domestic = domesticReachability,
            intlResults = intlResults,
            snapshot = snapshot,
            config = config,
            nowMs = now
        )
        _assessmentFlow.value = finalAssessment
        return finalAssessment
    }

    /**
     * Resets hysteresis state (e.g., on physical network change or user disconnect).
     */
    fun reset() = synchronized(lock) {
        committedState = NationalInternetState.UNKNOWN
        committedStateSinceMs = 0L
        pendingCandidateState = null
        pendingCandidateFirstSeenMs = 0L
        pendingCandidateConsecutiveCount = 0
        recentCycleLossRatios.clear()
        recentCycleJitterRatios.clear()
        _assessmentFlow.value = NetworkAssessment.INITIAL
    }

    /**
     * Pure classification function implementing Rules R1..R10 (Section 5.1) and the
     * confidence formula (Section 5.2).
     */
    fun classifyFromSignals(
        snapshot: NetworkSnapshot,
        intlResults: List<ProbeResult>,
        domesticReachability: DomesticReachability,
        totalConfiguredIntl: Int = 4,
        nowMs: Long = clockMsProvider()
    ): NetworkAssessment {
        // R1: No active network or missing NET_CAPABILITY_INTERNET
        if (snapshot.transportKind == TransportKind.NONE || !snapshot.hasInternetCapability) {
            return NetworkAssessment(
                state = NationalInternetState.NO_NETWORK,
                rawCandidateState = NationalInternetState.NO_NETWORK,
                confidence = 1.0f,
                domesticReachability = domesticReachability,
                transportKind = snapshot.transportKind,
                networkHandleId = snapshot.networkHandleId,
                timestampMs = nowMs
            )
        }

        val testedCount = intlResults.size
        val successes = intlResults.filter { it.success }
        val failures = intlResults.filter { !it.success }
        val providerGroupsTested = intlResults.map { it.providerGroup }.distinct().size
        val providerGroupsSucceeded = successes.map { it.providerGroup }.distinct().size

        val latencies = successes.map { it.totalLatencyMs }.sorted()
        val medianLatency = if (latencies.isNotEmpty()) latencies[latencies.size / 2] else null
        val p95Latency = if (latencies.isNotEmpty()) {
            val idx = ceil(latencies.size * 0.95).toInt().coerceIn(1, latencies.size) - 1
            latencies[idx]
        } else {
            null
        }
        val lossRatio = if (testedCount > 0) failures.size.toFloat() / testedCount.toFloat() else 1f

        val dominantFailure = failures
            .mapNotNull { it.failureCategory }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        // R2: All probes fail with OS_NETWORK_BLOCKED (EPERM / EACCES / SecurityException)
        if (testedCount > 0 && failures.size == testedCount &&
            failures.all { it.failureCategory == FailureCategory.OS_NETWORK_BLOCKED }
        ) {
            return buildAssessment(
                state = NationalInternetState.APP_BLOCKED_BY_OS,
                confidence = 0.95f,
                domestic = domesticReachability,
                intlResults = intlResults,
                providerGroupsTested = providerGroupsTested,
                providerGroupsSucceeded = providerGroupsSucceeded,
                medianLatency = medianLatency,
                p95Latency = p95Latency,
                lossRatio = lossRatio,
                dominantFailure = FailureCategory.OS_NETWORK_BLOCKED,
                snapshot = snapshot,
                nowMs = nowMs
            )
        }

        // R3: Captive portal detected by HTTP redirect / 511 / portal response
        if (failures.any { it.failureCategory == FailureCategory.CAPTIVE_PORTAL_SUSPECTED }) {
            val portalCount = failures.count { it.failureCategory == FailureCategory.CAPTIVE_PORTAL_SUSPECTED }
            val agreement = portalCount.toFloat() / testedCount.coerceAtLeast(1).toFloat()
            val conf = computeConfidence(
                completedProbes = testedCount,
                requiredProbes = totalConfiguredIntl.coerceAtLeast(3),
                agreement = agreement.coerceAtLeast(0.75f),
                ageSeconds = 0f
            )
            return buildAssessment(
                state = NationalInternetState.CAPTIVE_PORTAL_SUSPECTED,
                confidence = conf,
                domestic = domesticReachability,
                intlResults = intlResults,
                providerGroupsTested = providerGroupsTested,
                providerGroupsSucceeded = providerGroupsSucceeded,
                medianLatency = medianLatency,
                p95Latency = p95Latency,
                lossRatio = lossRatio,
                dominantFailure = FailureCategory.CAPTIVE_PORTAL_SUSPECTED,
                snapshot = snapshot,
                nowMs = nowMs
            )
        }

        // R4: Fewer than 2 international probes completed -> UNKNOWN with confidence <= 0.4
        if (testedCount < 2) {
            val lowConf = computeConfidence(
                completedProbes = testedCount,
                requiredProbes = 3,
                agreement = 0.5f,
                ageSeconds = 0f
            ).coerceAtMost(0.4f)
            return buildAssessment(
                state = NationalInternetState.UNKNOWN,
                confidence = lowConf,
                domestic = domesticReachability,
                intlResults = intlResults,
                providerGroupsTested = providerGroupsTested,
                providerGroupsSucceeded = providerGroupsSucceeded,
                medianLatency = medianLatency,
                p95Latency = p95Latency,
                lossRatio = lossRatio,
                dominantFailure = dominantFailure,
                snapshot = snapshot,
                nowMs = nowMs
            )
        }

        // Record cycle metrics for instability tracking (R7)
        val jitterRatio = if (medianLatency != null && p95Latency != null && medianLatency > 0L) {
            p95Latency.toFloat() / medianLatency.toFloat()
        } else {
            1.0f
        }
        synchronized(lock) {
            if (recentCycleLossRatios.size >= 4) recentCycleLossRatios.removeFirst()
            recentCycleLossRatios.addLast(lossRatio)
            if (recentCycleJitterRatios.size >= 4) recentCycleJitterRatios.removeFirst()
            recentCycleJitterRatios.addLast(jitterRatio)
        }

        // R5: >= 2 of N (N >= 3) international probes across >= 2 provider groups succeed at L-HTTP
        // with median RTT < 1500 ms and loss < 20% -> NORMAL_INTERNATIONAL
        if (testedCount >= 3 &&
            successes.size >= 2 &&
            providerGroupsSucceeded >= 2 &&
            (medianLatency != null && medianLatency < 1500L) &&
            lossRatio < 0.20f
        ) {
            // Check R7 first if high jitter p95 > 3x median across cycles
            if (isUnstableAcrossRecentCycles(lossRatio, jitterRatio)) {
                val conf = computeConfidence(
                    completedProbes = testedCount,
                    requiredProbes = 3,
                    agreement = 0.8f,
                    ageSeconds = 0f
                )
                return buildAssessment(
                    state = NationalInternetState.NETWORK_UNSTABLE,
                    confidence = conf,
                    domestic = domesticReachability,
                    intlResults = intlResults,
                    providerGroupsTested = providerGroupsTested,
                    providerGroupsSucceeded = providerGroupsSucceeded,
                    medianLatency = medianLatency,
                    p95Latency = p95Latency,
                    lossRatio = lossRatio,
                    dominantFailure = dominantFailure,
                    snapshot = snapshot,
                    nowMs = nowMs
                )
            }

            val agreement = successes.size.toFloat() / testedCount.toFloat()
            val conf = computeConfidence(
                completedProbes = testedCount,
                requiredProbes = 3,
                agreement = agreement,
                ageSeconds = 0f
            )
            return buildAssessment(
                state = NationalInternetState.NORMAL_INTERNATIONAL,
                confidence = conf,
                domestic = domesticReachability,
                intlResults = intlResults,
                providerGroupsTested = providerGroupsTested,
                providerGroupsSucceeded = providerGroupsSucceeded,
                medianLatency = medianLatency,
                p95Latency = p95Latency,
                lossRatio = lossRatio,
                dominantFailure = null,
                snapshot = snapshot,
                nowMs = nowMs
            )
        }

        // R7: High packet loss (20%..70%) or p95 > 3x median across recent cycles -> NETWORK_UNSTABLE
        if (successes.isNotEmpty() && isUnstableAcrossRecentCycles(lossRatio, jitterRatio)) {
            val conf = computeConfidence(
                completedProbes = testedCount,
                requiredProbes = 3,
                agreement = 0.75f,
                ageSeconds = 0f
            )
            return buildAssessment(
                state = NationalInternetState.NETWORK_UNSTABLE,
                confidence = conf,
                domestic = domesticReachability,
                intlResults = intlResults,
                providerGroupsTested = providerGroupsTested,
                providerGroupsSucceeded = providerGroupsSucceeded,
                medianLatency = medianLatency,
                p95Latency = p95Latency,
                lossRatio = lossRatio,
                dominantFailure = dominantFailure,
                snapshot = snapshot,
                nowMs = nowMs
            )
        }

        // R6: >= 1 international probe succeeds, while >= 1 fails -> PARTIAL_INTERNATIONAL
        if (successes.isNotEmpty() && failures.isNotEmpty()) {
            val dominantRatio = maxOf(successes.size, failures.size).toFloat() / testedCount.toFloat()
            val conf = computeConfidence(
                completedProbes = testedCount,
                requiredProbes = 3,
                agreement = dominantRatio,
                ageSeconds = 0f
            )
            return buildAssessment(
                state = NationalInternetState.PARTIAL_INTERNATIONAL,
                confidence = conf,
                domestic = domesticReachability,
                intlResults = intlResults,
                providerGroupsTested = providerGroupsTested,
                providerGroupsSucceeded = providerGroupsSucceeded,
                medianLatency = medianLatency,
                p95Latency = p95Latency,
                lossRatio = lossRatio,
                dominantFailure = dominantFailure,
                snapshot = snapshot,
                nowMs = nowMs
            )
        }

        // If all international probes succeeded (e.g., testedCount == 2 or high RTT >= 1500ms)
        if (failures.isEmpty() && successes.isNotEmpty()) {
            val state = if (medianLatency != null && medianLatency >= 1500L) {
                NationalInternetState.NETWORK_UNSTABLE
            } else {
                NationalInternetState.NORMAL_INTERNATIONAL
            }
            val conf = computeConfidence(
                completedProbes = testedCount,
                requiredProbes = 3,
                agreement = 1.0f,
                ageSeconds = 0f
            )
            return buildAssessment(
                state = state,
                confidence = conf,
                domestic = domesticReachability,
                intlResults = intlResults,
                providerGroupsTested = providerGroupsTested,
                providerGroupsSucceeded = providerGroupsSucceeded,
                medianLatency = medianLatency,
                p95Latency = p95Latency,
                lossRatio = 0f,
                dominantFailure = null,
                snapshot = snapshot,
                nowMs = nowMs
            )
        }

        // All international probes failed (0 of N succeeded). Evaluate R8, R9, R10:
        if (testedCount >= 3 && successes.isEmpty()) {
            val state = when (domesticReachability.state) {
                // R8: 0 of N international succeed AND >= 1 configured domestic probe succeeds at L-HTTP
                DomesticReachabilityState.YES -> NationalInternetState.DOMESTIC_ONLY_SUSPECTED
                // R9: 0 of N international succeed AND domestic probes are NOT_TESTED -> RESTRICTED_INTERNATIONAL
                DomesticReachabilityState.NOT_TESTED -> NationalInternetState.RESTRICTED_INTERNATIONAL
                // R10: 0 of N international succeed AND all configured domestic probes also fail -> NO_NETWORK
                DomesticReachabilityState.NO -> NationalInternetState.NO_NETWORK
            }
            val conf = computeConfidence(
                completedProbes = testedCount,
                requiredProbes = 3,
                agreement = 1.0f,
                ageSeconds = 0f
            )
            return buildAssessment(
                state = state,
                confidence = conf,
                domestic = domesticReachability,
                intlResults = intlResults,
                providerGroupsTested = providerGroupsTested,
                providerGroupsSucceeded = 0,
                medianLatency = null,
                p95Latency = null,
                lossRatio = 1.0f,
                dominantFailure = dominantFailure ?: FailureCategory.INTERNATIONAL_ROUTE_UNAVAILABLE,
                snapshot = snapshot,
                nowMs = nowMs
            )
        }

        // Fallback when 2 probes ran and both failed
        val fallbackState = if (domesticReachability.state == DomesticReachabilityState.YES) {
            NationalInternetState.DOMESTIC_ONLY_SUSPECTED
        } else {
            NationalInternetState.RESTRICTED_INTERNATIONAL
        }
        val conf = computeConfidence(
            completedProbes = testedCount,
            requiredProbes = 3,
            agreement = 1.0f,
            ageSeconds = 0f
        )
        return buildAssessment(
            state = fallbackState,
            confidence = conf,
            domestic = domesticReachability,
            intlResults = intlResults,
            providerGroupsTested = providerGroupsTested,
            providerGroupsSucceeded = 0,
            medianLatency = null,
            p95Latency = null,
            lossRatio = 1.0f,
            dominantFailure = dominantFailure,
            snapshot = snapshot,
            nowMs = nowMs
        )
    }

    /**
     * Hysteresis filter (Section 5.3):
     * - Immediate transitions allowed for [NationalInternetState.NO_NETWORK] and initial transition
     *   out of [NationalInternetState.UNKNOWN].
     * - All other state transitions require either >= [ConnectivityConfig.hysteresisMinCycles]
     *   consecutive assessments OR >= [ConnectivityConfig.hysteresisMinDwellMs] elapsed dwell time.
     */
    fun applyHysteresis(
        rawCandidate: NationalInternetState,
        confidence: Float,
        domestic: DomesticReachability,
        intlResults: List<ProbeResult>,
        snapshot: NetworkSnapshot,
        config: ConnectivityConfig,
        nowMs: Long = clockMsProvider()
    ): NetworkAssessment = synchronized(lock) {
        val successes = intlResults.filter { it.success }
        val failures = intlResults.filter { !it.success }
        val providerGroupsTested = intlResults.map { it.providerGroup }.distinct().size
        val providerGroupsSucceeded = successes.map { it.providerGroup }.distinct().size
        val latencies = successes.map { it.totalLatencyMs }.sorted()
        val medianLatency = if (latencies.isNotEmpty()) latencies[latencies.size / 2] else null
        val p95Latency = if (latencies.isNotEmpty()) {
            val idx = ceil(latencies.size * 0.95).toInt().coerceIn(1, latencies.size) - 1
            latencies[idx]
        } else {
            null
        }
        val lossRatio = if (intlResults.isNotEmpty()) {
            failures.size.toFloat() / intlResults.size.toFloat()
        } else if (rawCandidate == NationalInternetState.NO_NETWORK) {
            1f
        } else {
            0f
        }
        val dominantFailure = failures
            .mapNotNull { it.failureCategory }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        val effectiveState: NationalInternetState
        if (rawCandidate == NationalInternetState.NO_NETWORK ||
            committedState == NationalInternetState.UNKNOWN ||
            rawCandidate == committedState
        ) {
            committedState = rawCandidate
            if (committedStateSinceMs == 0L) committedStateSinceMs = nowMs
            pendingCandidateState = null
            pendingCandidateConsecutiveCount = 0
            effectiveState = committedState
        } else {
            if (pendingCandidateState == rawCandidate) {
                pendingCandidateConsecutiveCount += 1
            } else {
                pendingCandidateState = rawCandidate
                pendingCandidateFirstSeenMs = nowMs
                pendingCandidateConsecutiveCount = 1
            }
            val dwellElapsed = nowMs - pendingCandidateFirstSeenMs
            if (pendingCandidateConsecutiveCount >= config.hysteresisMinCycles ||
                dwellElapsed >= config.hysteresisMinDwellMs
            ) {
                committedState = rawCandidate
                committedStateSinceMs = nowMs
                pendingCandidateState = null
                pendingCandidateConsecutiveCount = 0
            }
            effectiveState = committedState
        }

        NetworkAssessment(
            state = effectiveState,
            rawCandidateState = rawCandidate,
            confidence = confidence,
            domesticReachability = domestic,
            internationalTestedCount = intlResults.size,
            internationalSuccessCount = successes.size,
            providerGroupsTested = providerGroupsTested,
            providerGroupsSucceeded = providerGroupsSucceeded,
            medianLatencyMs = medianLatency,
            p95LatencyMs = p95Latency,
            lossRatio = lossRatio,
            dominantFailureCategory = dominantFailure,
            transportKind = snapshot.transportKind,
            networkHandleId = snapshot.networkHandleId,
            probeResults = intlResults,
            timestampMs = nowMs
        )
    }

    private fun isUnstableAcrossRecentCycles(currentLoss: Float, currentJitter: Float): Boolean =
        synchronized(lock) {
            if (currentJitter > 3.0f) return@synchronized true
            val moderateLossCycles = recentCycleLossRatios.count { it in 0.20f..0.70f }
            if (moderateLossCycles >= 2 && currentLoss in 0.20f..0.70f) {
                return@synchronized true
            }
            if (recentCycleJitterRatios.size >= 2 && recentCycleJitterRatios.all { it > 3.0f }) {
                return@synchronized true
            }
            false
        }

    private fun buildAssessment(
        state: NationalInternetState,
        confidence: Float,
        domestic: DomesticReachability,
        intlResults: List<ProbeResult>,
        providerGroupsTested: Int,
        providerGroupsSucceeded: Int,
        medianLatency: Long?,
        p95Latency: Long?,
        lossRatio: Float,
        dominantFailure: FailureCategory?,
        snapshot: NetworkSnapshot,
        nowMs: Long
    ): NetworkAssessment = NetworkAssessment(
        state = state,
        rawCandidateState = state,
        confidence = confidence,
        domesticReachability = domestic,
        internationalTestedCount = intlResults.size,
        internationalSuccessCount = intlResults.count { it.success },
        providerGroupsTested = providerGroupsTested,
        providerGroupsSucceeded = providerGroupsSucceeded,
        medianLatencyMs = medianLatency,
        p95LatencyMs = p95Latency,
        lossRatio = lossRatio,
        dominantFailureCategory = dominantFailure,
        transportKind = snapshot.transportKind,
        networkHandleId = snapshot.networkHandleId,
        probeResults = intlResults,
        timestampMs = nowMs
    )

    companion object {
        /**
         * Pure confidence formula (Section 5.2):
         * - `coverage = min(1.0, completedProbes / requiredProbes)`
         * - `agreement = fraction of completed probes consistent with chosen classification`
         * - `freshness = exp(-ageSeconds / 60.0)`
         * - `confidence = clamp(coverage * agreement * freshness, 0.0, 1.0)`
         */
        fun computeConfidence(
            completedProbes: Int,
            requiredProbes: Int = 3,
            agreement: Float,
            ageSeconds: Float = 0f
        ): Float {
            if (completedProbes <= 0 || requiredProbes <= 0) return 0f
            val coverage = (completedProbes.toFloat() / requiredProbes.toFloat()).coerceIn(0f, 1f)
            val clampedAgreement = agreement.coerceIn(0f, 1f)
            val freshness = kotlin.math.exp((-ageSeconds.coerceAtLeast(0f)) / 60.0).toFloat()
            return (coverage * clampedAgreement * freshness).coerceIn(0f, 1f)
        }
    }
}
