package com.example.core.resilience

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Tracks per-path circuit breakers (Section 11.4):
 * - Opens after [ConnectivityConfig.circuitBreakerFailureThreshold] (default 3) consecutive failures.
 * - Initial cooldown 5 minutes, doubling on repeated opens up to 60 minutes.
 * - Automatically resets to half-open when the underlying [TransportKind] changes (e.g., Wi-Fi -> Cellular)
 *   for network-dependent failures (never resets `AUTHENTICATION_FAILURE` or `CONFIGURATION_ERROR`).
 */
class CircuitBreakerRegistry(
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    data class BreakerEntry(
        val pathId: String,
        val consecutiveFailures: Int = 0,
        val openCount: Int = 0,
        val openUntilEpochMs: Long = 0L,
        val lastFailureCategory: FailureCategory? = null,
        val openedOnTransport: TransportKind = TransportKind.UNKNOWN
    )

    private val lock = Any()
    private val breakers = mutableMapOf<String, BreakerEntry>()

    fun recordSuccess(pathId: String) = synchronized(lock) {
        breakers.remove(pathId)
    }

    fun recordFailure(
        pathId: String,
        category: FailureCategory = FailureCategory.UNKNOWN_FAILURE,
        transportKind: TransportKind = TransportKind.UNKNOWN,
        config: ConnectivityConfig = ConnectivityConfig()
    ): BreakerEntry = synchronized(lock) {
        val now = clockMsProvider()
        val existing = breakers[pathId] ?: BreakerEntry(pathId = pathId)
        val newConsecutive = existing.consecutiveFailures + 1

        // Terminal auth/config errors open immediately for max cooldown
        if (FailureClassifier.isTerminalConfigurationOrAuthFailure(category)) {
            val updated = existing.copy(
                consecutiveFailures = newConsecutive,
                openCount = existing.openCount + 1,
                openUntilEpochMs = now + config.circuitBreakerMaxCooldownMs,
                lastFailureCategory = category,
                openedOnTransport = transportKind
            )
            breakers[pathId] = updated
            return@synchronized updated
        }

        if (newConsecutive >= config.circuitBreakerFailureThreshold) {
            val newOpenCount = existing.openCount + 1
            val multiplier = 2.0.pow((newOpenCount - 1).coerceIn(0, 4)).toLong()
            val cooldown = min(
                config.circuitBreakerBaseCooldownMs * multiplier,
                config.circuitBreakerMaxCooldownMs
            )
            val updated = existing.copy(
                consecutiveFailures = newConsecutive,
                openCount = newOpenCount,
                openUntilEpochMs = now + cooldown,
                lastFailureCategory = category,
                openedOnTransport = transportKind
            )
            breakers[pathId] = updated
            updated
        } else {
            val updated = existing.copy(
                consecutiveFailures = newConsecutive,
                lastFailureCategory = category,
                openedOnTransport = transportKind
            )
            breakers[pathId] = updated
            updated
        }
    }

    fun isOpen(pathId: String, currentTransport: TransportKind = TransportKind.UNKNOWN): Boolean = synchronized(lock) {
        val entry = breakers[pathId] ?: return@synchronized false
        val now = clockMsProvider()
        if (now >= entry.openUntilEpochMs) {
            return@synchronized false
        }
        // If transport kind changed and the failure was not auth/config, allow half-open retry
        if (currentTransport != TransportKind.UNKNOWN &&
            entry.openedOnTransport != TransportKind.UNKNOWN &&
            currentTransport != entry.openedOnTransport &&
            entry.lastFailureCategory != null &&
            !FailureClassifier.isTerminalConfigurationOrAuthFailure(entry.lastFailureCategory)
        ) {
            return@synchronized false
        }
        true
    }

    fun onTransportChanged(newTransport: TransportKind) = synchronized(lock) {
        val iterator = breakers.entries.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            val cat = entry.lastFailureCategory
            if (cat != null && !FailureClassifier.isTerminalConfigurationOrAuthFailure(cat)) {
                if (entry.openedOnTransport != newTransport) {
                    iterator.remove()
                }
            }
        }
    }

    fun resetAll() = synchronized(lock) {
        breakers.clear()
    }
}

/**
 * Pure, side-effect-free recovery decision engine implementing:
 * - The Section 11.3 Recovery Decision Matrix
 * - Exponential backoff with ±20% jitter (Section 11.4)
 * - Long-outage polling ladder: 30s -> 60s -> 2m -> 5m -> 10m -> 15m (Section 11.5)
 * - Restart-storm guard: max 6 core restarts per 10-minute rolling window (Section 11.6)
 */
class RecoveryPlanner(
    private val circuitBreakers: CircuitBreakerRegistry = CircuitBreakerRegistry(),
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() },
    private val jitterProvider: () -> Double = { Random.Default.nextDouble(-0.20, 0.20) }
) {
    private val restartTimestampsLock = Any()
    private val restartTimestampsMs = ArrayDeque<Long>()

    fun recordCoreRestart(nowMs: Long = clockMsProvider()) = synchronized(restartTimestampsLock) {
        pruneOldRestartsLocked(nowMs)
        restartTimestampsMs.addLast(nowMs)
    }

    fun canRestartCore(
        config: ConnectivityConfig = ConnectivityConfig(),
        nowMs: Long = clockMsProvider()
    ): Boolean = synchronized(restartTimestampsLock) {
        pruneOldRestartsLocked(nowMs)
        restartTimestampsMs.size < config.maxCoreRestartsPerTenMin
    }

    fun getRecentCoreRestartCount(nowMs: Long = clockMsProvider()): Int = synchronized(restartTimestampsLock) {
        pruneOldRestartsLocked(nowMs)
        restartTimestampsMs.size
    }

    private fun pruneOldRestartsLocked(nowMs: Long) {
        val cutoff = nowMs - 10 * 60_000L
        while (restartTimestampsMs.isNotEmpty() && restartTimestampsMs.first() < cutoff) {
            restartTimestampsMs.removeFirst()
        }
    }

    /**
     * Computes exponential backoff with ±20% jitter:
     * `base = 2s, factor = 2.0, cap = 5 min`.
     */
    fun computeBackoffMs(attemptIndex: Int): Long {
        val exp = attemptIndex.coerceIn(0, 8)
        val rawMs = min(2_000L * (1L shl exp), 5 * 60_000L).toDouble()
        val jitter = jitterProvider().coerceIn(-0.20, 0.20)
        return (rawMs * (1.0 + jitter)).toLong().coerceIn(1_500L, 6 * 60_000L)
    }

    /**
     * Long-outage polling ladder (Section 11.5):
     * `30s -> 60s -> 2 min -> 5 min -> 10 min -> 15 min (cap)`.
     */
    fun computeLongOutagePollDelayMs(outageStep: Int): Long {
        val steps = longArrayOf(
            30_000L,
            60_000L,
            2 * 60_000L,
            5 * 60_000L,
            10 * 60_000L,
            15 * 60_000L
        )
        val idx = outageStep.coerceIn(0, steps.lastIndex)
        return steps[idx]
    }

    /**
     * Selects the next [RecoveryAction] given the current [NetworkAssessment], the active
     * [ConnectivityPath], the observed [FailureCategory], the current retry count on this path,
     * and the ranked list of candidate paths.
     */
    fun planNextAction(
        assessment: NetworkAssessment,
        currentPath: ConnectivityPath?,
        failureCategory: FailureCategory,
        samePathAttemptCount: Int,
        longOutageStep: Int,
        candidatePaths: List<ConnectivityPath>,
        config: ConnectivityConfig = ConnectivityConfig()
    ): RecoveryAction {
        // 1. Terminal configuration / authentication / certificate / clock skew checks (Section 11.3)
        if (FailureClassifier.isTerminalConfigurationOrAuthFailure(failureCategory)) {
            return RecoveryAction.HaltTerminal(
                category = failureCategory,
                userMessagePersian = PersianConnectivityStrings.CONFIG_OR_AUTH_ERROR
            )
        }

        if (failureCategory == FailureCategory.CLOCK_SKEW_SUSPECTED) {
            return RecoveryAction.HaltTerminal(
                category = FailureCategory.CLOCK_SKEW_SUSPECTED,
                userMessagePersian = PersianConnectivityStrings.CLOCK_SKEW_WARNING
            )
        }

        if (failureCategory == FailureCategory.CAPTIVE_PORTAL_SUSPECTED ||
            assessment.state == NationalInternetState.CAPTIVE_PORTAL_SUSPECTED
        ) {
            return RecoveryAction.HaltTerminal(
                category = FailureCategory.CAPTIVE_PORTAL_SUSPECTED,
                userMessagePersian = PersianConnectivityStrings.CAPTIVE_PORTAL
            )
        }

        // 2. If NO_NETWORK, pause in long-outage wait until OS network callback fires
        if (assessment.state == NationalInternetState.NO_NETWORK) {
            return RecoveryAction.EnterLongOutageWait(
                pollDelayMs = computeLongOutagePollDelayMs(longOutageStep),
                domesticOnlySuspected = false
            )
        }

        // 3. Restart-storm guard (Section 11.6): if core already restarted >= 6 times in 10m, enter wait
        if (!canRestartCore(config)) {
            return RecoveryAction.EnterLongOutageWait(
                pollDelayMs = computeLongOutagePollDelayMs(longOutageStep.coerceAtLeast(2)),
                domesticOnlySuspected = assessment.state == NationalInternetState.DOMESTIC_ONLY_SUSPECTED
            )
        }

        // 4. Filter available candidate paths whose circuit breaker is NOT open
        val availableAlternatives = candidatePaths.filter { candidate ->
            candidate.pathId != currentPath?.pathId &&
                !circuitBreakers.isOpen(candidate.pathId, assessment.transportKind)
        }

        // 5. Under DOMESTIC_ONLY_SUSPECTED: prioritize authorized relay paths first (Section 11.3)
        if (assessment.state == NationalInternetState.DOMESTIC_ONLY_SUSPECTED ||
            failureCategory == FailureCategory.DOMESTIC_ONLY_SUSPECTED
        ) {
            val relayCandidate = availableAlternatives.firstOrNull { it.isViaRelay }
            if (relayCandidate != null) {
                return RecoveryAction.SwitchPath(
                    targetPath = relayCandidate,
                    reason = "Domestic-only suspected; switching to authorized relay path",
                    viaRelay = true
                )
            }
            val directAlternative = if (config.allowAutoServerSwitch) {
                availableAlternatives.firstOrNull()
            } else {
                null
            }
            if (directAlternative != null && samePathAttemptCount >= 1) {
                return RecoveryAction.SwitchPath(
                    targetPath = directAlternative,
                    reason = "Testing remaining direct candidate before long-outage wait",
                    viaRelay = false
                )
            }
            return RecoveryAction.EnterLongOutageWait(
                pollDelayMs = computeLongOutagePollDelayMs(longOutageStep),
                domesticOnlySuspected = true
            )
        }

        // 6. DNS_FAILURE / DNS_TAMPERING_SUSPECTED: retry once with fresh DNS resolution before switching
        if ((failureCategory == FailureCategory.DNS_FAILURE ||
                failureCategory == FailureCategory.DNS_TAMPERING_SUSPECTED) &&
            currentPath != null &&
            samePathAttemptCount == 0 &&
            !circuitBreakers.isOpen(currentPath.pathId, assessment.transportKind)
        ) {
            return RecoveryAction.RetrySamePath(
                path = currentPath,
                delayMs = computeBackoffMs(0),
                refreshDns = true
            )
        }

        // 7. NETWORK_CHANGED: rebind / retry current path once before switching
        if (failureCategory == FailureCategory.NETWORK_CHANGED &&
            currentPath != null &&
            samePathAttemptCount == 0
        ) {
            return RecoveryAction.RetrySamePath(
                path = currentPath,
                delayMs = 1_500L,
                refreshDns = true,
                rebindNetwork = true
            )
        }

        // 8. Transient TCP_TIMEOUT / SERVER_UNREACHABLE on normal/partial network: allow 1 retry on same path
        if ((failureCategory == FailureCategory.TCP_TIMEOUT ||
                failureCategory == FailureCategory.SERVER_UNREACHABLE) &&
            currentPath != null &&
            samePathAttemptCount == 0 &&
            !circuitBreakers.isOpen(currentPath.pathId, assessment.transportKind)
        ) {
            return RecoveryAction.RetrySamePath(
                path = currentPath,
                delayMs = computeBackoffMs(0),
                refreshDns = false
            )
        }

        // 9. Switch to next best available candidate path (direct or relay)
        if (config.allowAutoServerSwitch && availableAlternatives.isNotEmpty()) {
            val preferred = when (failureCategory) {
                FailureCategory.TLS_HANDSHAKE_FAILURE,
                FailureCategory.TCP_RESET,
                FailureCategory.TCP_REFUSED -> {
                    // Prefer REALITY / WS / gRPC / Relay path over raw TCP on same port
                    availableAlternatives.firstOrNull {
                        it.isViaRelay || it.profile.network != currentPath?.profile?.network
                    } ?: availableAlternatives.first()
                }
                FailureCategory.RELAY_UNAVAILABLE -> {
                    availableAlternatives.firstOrNull { !it.isViaRelay } ?: availableAlternatives.first()
                }
                else -> availableAlternatives.first()
            }
            return RecoveryAction.SwitchPath(
                targetPath = preferred,
                reason = "Failover due to ${failureCategory.name}",
                viaRelay = preferred.isViaRelay
            )
        }

        // 10. All candidate paths exhausted or auto-switch disabled -> Long Outage Wait
        return RecoveryAction.EnterLongOutageWait(
            pollDelayMs = computeLongOutagePollDelayMs(longOutageStep),
            domesticOnlySuspected = assessment.state == NationalInternetState.DOMESTIC_ONLY_SUSPECTED
        )
    }
}
