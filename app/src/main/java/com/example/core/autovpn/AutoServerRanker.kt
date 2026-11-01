package com.example.core.autovpn

import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType
import com.example.domain.model.VpnProtocol
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent Server Selection & Ranking Engine.
 *
 * Evaluates VPN candidates strictly on measured connection quality:
 * 1. Real connection success (HTTP 204 proxy verification vs TCP handshake)
 * 2. Connection latency (lower latency yields exponentially higher score)
 * 3. Actual download performance (measured throughput in bps)
 * 4. Connection stability (historical success/failure ratio & consecutive failure penalty)
 * 5. Recent successful validation (freshness decay over time)
 * 6. Network compatibility (protocol/transport/TLS/Reality traversal resilience)
 *
 * Geographic location is NEVER prioritized or used in ranking.
 */
object AutoServerRanker {

    private const val MAX_REASONABLE_LATENCY_MS = 2500L
    private const val VALIDATION_FRESH_WINDOW_MS = 15 * 60 * 1000L // 15 minutes
    private const val HYSTERESIS_MIN_SCORE_GAIN_RATIO = 0.22 // 22% higher composite score required to switch
    private const val HYSTERESIS_MIN_LATENCY_IMPROVEMENT_MS = 50L
    private const val ACTIVE_TRAFFIC_BUSY_THRESHOLD_BPS = 64 * 1024L // 64 KB/s — avoid switching while user streams/downloads

    /**
     * Computes network compatibility score [0.0 .. 1.0] based on protocol, transport, and TLS/Reality properties.
     * Supports VLESS, VLESS Reality, VMess, and Trojan.
     */
    fun computeNetworkCompatibilityScore(profile: ServerProfile): Double {
        val isReality = profile.protocol == VpnProtocol.VLESS &&
            (profile.security == SecurityType.REALITY || profile.realityPublicKey.isNotBlank())

        val protocolScore = when {
            isReality -> 1.00
            profile.protocol == VpnProtocol.VLESS && profile.security == SecurityType.TLS -> 0.92
            profile.protocol == VpnProtocol.TROJAN && profile.security == SecurityType.TLS -> 0.88
            profile.protocol == VpnProtocol.VMESS && profile.security == SecurityType.TLS -> 0.85
            profile.protocol == VpnProtocol.VLESS -> 0.78
            profile.protocol == VpnProtocol.VMESS -> 0.74
            profile.protocol == VpnProtocol.TROJAN -> 0.76
            else -> 0.65
        }

        val transportBonus = when (profile.network) {
            TransportType.TCP -> if (isReality) 0.08 else 0.02
            TransportType.WS, TransportType.HTTPUPGRADE, TransportType.XHTTP -> 0.06
            TransportType.GRPC -> 0.05
            else -> 0.0
        }

        val portBonus = when (profile.port) {
            443, 8443, 2053, 2083, 2087, 2096 -> 0.05 // Standard TLS/CDN ports traverse restrictive firewalls best
            80, 8080, 8880, 2052, 2082 -> 0.02
            else -> 0.0
        }

        return (protocolScore + transportBonus + portBonus).coerceIn(0.1, 1.0)
    }

    /**
     * Evaluates a single candidate profile and returns updated [CandidateQualityMetrics] with composite score [0.0 .. 100.0].
     * Primary ranking uses **Real VPN Latency** (`measuredLatencyMs`), keeping diagnostic [measuredTcpConnectMs] separate.
     */
    fun evaluateCandidate(
        profile: ServerProfile,
        existingMetrics: CandidateQualityMetrics? = null,
        measuredLatencyMs: Long? = profile.lastLatencyMs,
        measuredTcpConnectMs: Long? = profile.tcpConnectMs ?: existingMetrics?.tcpConnectMs,
        proxyVerified: Boolean = existingMetrics?.proxyVerified ?: (profile.latencyType == com.example.domain.model.LatencyMeasureType.PROXY_HTTP_DELAY && profile.lastLatencyMs != null),
        measuredDownloadBps: Long? = existingMetrics?.downloadBps,
        nowMs: Long = System.currentTimeMillis()
    ): CandidateQualityMetrics {
        val key = profile.uniqueFingerprintKey()
        val isReality = profile.protocol == VpnProtocol.VLESS &&
            (profile.security == SecurityType.REALITY || profile.realityPublicKey.isNotBlank())

        val prevSuccess = existingMetrics?.successCount ?: 0
        val prevFailure = existingMetrics?.failureCount ?: 0
        val prevConsecutiveFailures = existingMetrics?.consecutiveFailures ?: 0
        val cooldownUntil = existingMetrics?.cooldownUntilMs ?: 0L

        val effectiveLatency = measuredLatencyMs ?: existingMetrics?.latencyMs
        val isReachable = effectiveLatency != null && effectiveLatency in 1..MAX_REASONABLE_LATENCY_MS

        val totalAttempts = prevSuccess + prevFailure
        val rawStability = if (totalAttempts > 0) {
            prevSuccess.toDouble() / totalAttempts.toDouble()
        } else if (isReachable) {
            0.85
        } else {
            0.40
        }

        // Penalize consecutive failures
        val consecutivePenalty = (prevConsecutiveFailures * 0.18).coerceAtMost(0.75)
        val stabilityRatio = (rawStability * (1.0 - consecutivePenalty)).coerceIn(0.0, 1.0)

        val compatibilityScore = computeNetworkCompatibilityScore(profile)

        // If on cooldown or unreachable without prior validation, score is heavily reduced
        if (cooldownUntil > nowMs) {
            return CandidateQualityMetrics(
                fingerprintKey = key,
                protocol = profile.protocol,
                isReality = isReality,
                latencyMs = effectiveLatency,
                tcpConnectMs = measuredTcpConnectMs,
                proxyVerified = proxyVerified,
                downloadBps = measuredDownloadBps,
                successCount = prevSuccess,
                failureCount = prevFailure,
                consecutiveFailures = prevConsecutiveFailures,
                cooldownUntilMs = cooldownUntil,
                lastValidatedAtMs = existingMetrics?.lastValidatedAtMs ?: 0L,
                stabilityRatio = stabilityRatio,
                networkCompatibilityScore = compatibilityScore,
                compositeRankScore = 0.0,
                qualityGrade = AutoQualityGrade.DEGRADED
            )
        }

        // 1. Connection Success Score (0..25 pts) — strongly prioritizes verified Real VPN Latency (HTTP 204 over Xray)
        val connectionSuccessPoints = when {
            proxyVerified && isReachable -> 25.0
            isReachable -> 18.0
            prevSuccess > 0 -> 8.0
            else -> 0.0
        }

        // 2. Real VPN Latency Score (0..30 pts) — lower Real VPN Latency yields higher score
        val latencyPoints = if (effectiveLatency != null && effectiveLatency > 0) {
            30.0 * exp(-effectiveLatency.toDouble() / 650.0)
        } else {
            0.0
        }

        // 3. Download Performance Score (0..15 pts)
        val downloadPoints = if (measuredDownloadBps != null && measuredDownloadBps > 0) {
            val ratio = (measuredDownloadBps.toDouble() / 2_000_000.0).coerceIn(0.0, 1.0)
            4.0 + 11.0 * ratio
        } else if (isReachable) {
            7.5
        } else {
            0.0
        }

        // 4. Stability Score (0..15 pts)
        val stabilityPoints = 15.0 * stabilityRatio

        // 5. Recent Validation Freshness Score (0..7.5 pts)
        val lastValidated = if (isReachable) {
            max(nowMs, existingMetrics?.lastValidatedAtMs ?: 0L)
        } else {
            existingMetrics?.lastValidatedAtMs ?: 0L
        }
        val freshnessPoints = if (lastValidated > 0L) {
            val ageMs = (nowMs - lastValidated).coerceAtLeast(0L)
            7.5 * exp(-ageMs.toDouble() / VALIDATION_FRESH_WINDOW_MS.toDouble())
        } else {
            0.0
        }

        // 6. Network Compatibility Score (0..7.5 pts)
        val compatibilityPoints = 7.5 * compatibilityScore

        val totalScore = (
            connectionSuccessPoints +
                latencyPoints +
                downloadPoints +
                stabilityPoints +
                freshnessPoints +
                compatibilityPoints
            ).coerceIn(0.0, 100.0)

        val grade = when {
            !isReachable -> AutoQualityGrade.UNVERIFIED
            totalScore >= 78.0 && (effectiveLatency ?: Long.MAX_VALUE) <= 220L -> AutoQualityGrade.EXCELLENT
            totalScore >= 60.0 && (effectiveLatency ?: Long.MAX_VALUE) <= 450L -> AutoQualityGrade.GOOD
            totalScore >= 40.0 -> AutoQualityGrade.FAIR
            else -> AutoQualityGrade.DEGRADED
        }

        return CandidateQualityMetrics(
            fingerprintKey = key,
            protocol = profile.protocol,
            isReality = isReality,
            latencyMs = effectiveLatency,
            tcpConnectMs = measuredTcpConnectMs,
            proxyVerified = proxyVerified,
            downloadBps = measuredDownloadBps,
            successCount = prevSuccess,
            failureCount = prevFailure,
            consecutiveFailures = prevConsecutiveFailures,
            cooldownUntilMs = cooldownUntil,
            lastValidatedAtMs = lastValidated,
            stabilityRatio = stabilityRatio,
            networkCompatibilityScore = compatibilityScore,
            compositeRankScore = totalScore,
            qualityGrade = grade
        )
    }

    /**
     * Records a successful connection or verification event on a candidate's metrics.
     */
    fun recordConnectionSuccess(
        profile: ServerProfile,
        existing: CandidateQualityMetrics?,
        verifiedLatencyMs: Long?,
        tcpConnectMs: Long? = profile.tcpConnectMs ?: existing?.tcpConnectMs,
        downloadBps: Long? = existing?.downloadBps,
        nowMs: Long = System.currentTimeMillis()
    ): CandidateQualityMetrics {
        val base = existing ?: evaluateCandidate(profile, nowMs = nowMs)
        val updated = base.copy(
            latencyMs = verifiedLatencyMs ?: base.latencyMs,
            tcpConnectMs = tcpConnectMs ?: base.tcpConnectMs,
            proxyVerified = true,
            downloadBps = downloadBps ?: base.downloadBps,
            successCount = base.successCount + 1,
            consecutiveFailures = 0,
            cooldownUntilMs = 0L,
            lastValidatedAtMs = nowMs
        )
        return evaluateCandidate(
            profile = profile,
            existingMetrics = updated,
            measuredLatencyMs = updated.latencyMs,
            measuredTcpConnectMs = updated.tcpConnectMs,
            proxyVerified = true,
            measuredDownloadBps = updated.downloadBps,
            nowMs = nowMs
        )
    }

    /**
     * Records a connection or verification failure and applies exponential per-server cooldown
     * so failed servers are skipped during failover.
     */
    fun recordConnectionFailure(
        profile: ServerProfile,
        existing: CandidateQualityMetrics?,
        tcpConnectMs: Long? = profile.tcpConnectMs ?: existing?.tcpConnectMs,
        nowMs: Long = System.currentTimeMillis()
    ): CandidateQualityMetrics {
        val base = existing ?: evaluateCandidate(profile, nowMs = nowMs)
        val newConsecutive = base.consecutiveFailures + 1
        // Exponential cooldown: 30s, 60s, 120s, 240s, max 10 min
        val shift = (newConsecutive - 1).coerceIn(0, 4)
        val cooldownDurationMs = min(30_000L * (1L shl shift), 10 * 60_000L)
        val updated = base.copy(
            tcpConnectMs = tcpConnectMs ?: base.tcpConnectMs,
            proxyVerified = false,
            failureCount = base.failureCount + 1,
            consecutiveFailures = newConsecutive,
            cooldownUntilMs = nowMs + cooldownDurationMs
        )
        return evaluateCandidate(
            profile = profile,
            existingMetrics = updated,
            measuredLatencyMs = null,
            measuredTcpConnectMs = updated.tcpConnectMs,
            proxyVerified = false,
            measuredDownloadBps = updated.downloadBps,
            nowMs = nowMs
        )
    }

    /**
     * Ranks a collection of candidate profiles in descending order of composite quality score
     * and Real VPN Latency (`latencyMs`).
     * Excludes candidates currently on failure cooldown unless all candidates are on cooldown.
     */
    fun rankCandidates(
        profiles: List<ServerProfile>,
        metricsMap: Map<String, CandidateQualityMetrics>,
        nowMs: Long = System.currentTimeMillis()
    ): List<RankedVpnCandidate> {
        if (profiles.isEmpty()) return emptyList()

        val evaluated = profiles
            .distinctBy { it.uniqueFingerprintKey() }
            .map { profile ->
                val key = profile.uniqueFingerprintKey()
                val existing = metricsMap[key]
                val metrics = evaluateCandidate(
                    profile = profile,
                    existingMetrics = existing,
                    measuredLatencyMs = profile.lastLatencyMs ?: existing?.latencyMs,
                    measuredTcpConnectMs = profile.tcpConnectMs ?: existing?.tcpConnectMs,
                    proxyVerified = existing?.proxyVerified ?: (profile.latencyType == com.example.domain.model.LatencyMeasureType.PROXY_HTTP_DELAY && profile.lastLatencyMs != null),
                    measuredDownloadBps = existing?.downloadBps,
                    nowMs = nowMs
                )
                RankedVpnCandidate(profile = profile, metrics = metrics)
            }

        val activePool = evaluated.filter { !it.metrics.isOnCooldown(nowMs) }
        val poolToUse = activePool.ifEmpty { evaluated }

        return poolToUse.sortedWith(
            compareByDescending<RankedVpnCandidate> { it.metrics.compositeRankScore }
                .thenBy { it.metrics.latencyMs ?: Long.MAX_VALUE }
                .thenByDescending { it.metrics.networkCompatibilityScore }
        )
    }

    /**
     * Determines whether Continuous Optimization should seamlessly switch from [current] to [challenger].
     * Applies strict hysteresis to avoid unnecessary disconnections.
     */
    fun shouldSwitchToBetterCandidate(
        current: RankedVpnCandidate,
        challenger: RankedVpnCandidate,
        currentTrafficSpeedBps: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (current.profile.uniqueFingerprintKey() == challenger.profile.uniqueFingerprintKey()) {
            return false
        }
        if (challenger.metrics.isOnCooldown(nowMs)) {
            return false
        }
        // Never interrupt active user downloads/streaming
        if (currentTrafficSpeedBps > ACTIVE_TRAFFIC_BUSY_THRESHOLD_BPS) {
            return false
        }
        val currentLatency = current.metrics.latencyMs ?: 400L
        val challengerLatency = challenger.metrics.latencyMs ?: return false

        // If current server is already performing well (< 180ms and GOOD/EXCELLENT), do not switch
        if (currentLatency <= 180L && current.metrics.compositeRankScore >= 72.0) {
            return false
        }

        val scoreGainRatio = if (current.metrics.compositeRankScore > 1.0) {
            (challenger.metrics.compositeRankScore - current.metrics.compositeRankScore) / current.metrics.compositeRankScore
        } else {
            1.0
        }
        val latencyImprovement = currentLatency - challengerLatency

        return scoreGainRatio >= HYSTERESIS_MIN_SCORE_GAIN_RATIO &&
            latencyImprovement >= HYSTERESIS_MIN_LATENCY_IMPROVEMENT_MS
    }
}
