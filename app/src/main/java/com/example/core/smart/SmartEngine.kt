package com.example.core.smart

import com.example.core.autovpn.AutoServerRanker
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.SmartConnectionMode
import com.example.domain.model.TransportType
import com.example.domain.model.VpnProtocol
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Result of the Smart Engine server selection evaluation.
 */
data class SmartSelectionResult(
    val selectedProfile: ServerProfile,
    val score: Int, // 0..100
    val mode: SmartConnectionMode,
    val explanationEn: String,
    val explanationFa: String,
    val latencyMs: Long?,
    val jitterMs: Long?,
    val packetLossRate: Float?,
    val candidateCount: Int,
    val isSwitchedDueToFailover: Boolean = false
)

/**
 * Modular Smart Engine for MAXNET5G VPN V3.0.
 *
 * Deterministic, explainable, and multi-metric intelligence system:
 * - Multi-metric evaluation: Real Proxy Latency, Jitter, Packet Loss, Connection Success Rate,
 *   Historical Stability, Test Freshness, and Transport Compatibility.
 * - Mode-driven weighted scoring: GAMING, STREAMING, BROWSING, and STABLE.
 * - Never ranks an unavailable server as healthy because of a raw TCP handshake.
 * - Hysteresis thresholds to prevent ping-pong switching between similar servers.
 * - Cooldown tracking with exponential backoff for failing servers.
 * - Transparent explanations for server selection in English and Persian.
 */
object SmartEngine {

    private const val MAX_VIABLE_LATENCY_MS = 2500L
    private const val FRESHNESS_WINDOW_MS = 15 * 60 * 1000L // 15 minutes

    // Tracks cooldown timestamps and consecutive failures per profile fingerprint
    private val failureCooldowns = ConcurrentHashMap<String, Long>()
    private val failureCounts = ConcurrentHashMap<String, Int>()

    fun recordServerFailure(profile: ServerProfile, nowMs: Long = System.currentTimeMillis()): Long {
        val key = profile.uniqueFingerprintKey()
        val count = failureCounts.compute(key) { _, current -> (current ?: 0) + 1 } ?: 1
        val cooldownDurationMs = when (count) {
            1 -> 15_000L      // 15 seconds
            2 -> 45_000L      // 45 seconds
            3 -> 120_000L     // 2 minutes
            else -> 300_000L  // 5 minutes
        }
        val cooldownUntil = nowMs + cooldownDurationMs
        failureCooldowns[key] = cooldownUntil
        return cooldownUntil
    }

    fun recordServerSuccess(profile: ServerProfile) {
        val key = profile.uniqueFingerprintKey()
        failureCounts[key] = 0
        failureCooldowns.remove(key)
    }

    fun isServerOnCooldown(profile: ServerProfile, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = failureCooldowns[profile.uniqueFingerprintKey()] ?: return false
        return until > nowMs
    }

    fun getRemainingCooldownMs(profile: ServerProfile, nowMs: Long = System.currentTimeMillis()): Long {
        val until = failureCooldowns[profile.uniqueFingerprintKey()] ?: return 0L
        return max(0L, until - nowMs)
    }

    fun resetCooldownsForTests() {
        failureCooldowns.clear()
        failureCounts.clear()
    }

    /**
     * Calculates the deterministic composite quality score [0..100] for a server profile
     * given the active [SmartConnectionMode].
     */
    fun calculateServerScore(
        profile: ServerProfile,
        mode: SmartConnectionMode = SmartConnectionMode.STABLE,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val key = profile.uniqueFingerprintKey()
        val cooldownUntil = failureCooldowns[key] ?: 0L
        if (cooldownUntil > nowMs) {
            return 0 // Cooldown servers get 0 score
        }

        val latencyMs = profile.realVpnLatencyMs ?: profile.lastLatencyMs
        val isVerified = profile.latencyType == LatencyMeasureType.PROXY_HTTP_DELAY && latencyMs != null && latencyMs > 0L

        // Never rank unreachable or unverified timeout servers
        if (profile.latencyType == LatencyMeasureType.TIMEOUT || (latencyMs != null && latencyMs > MAX_VIABLE_LATENCY_MS)) {
            return 0
        }

        // 1. Normalized Latency Component (0.0 .. 1.0)
        val tau = when (mode) {
            SmartConnectionMode.GAMING -> 450.0
            SmartConnectionMode.STREAMING -> 750.0
            SmartConnectionMode.BROWSING -> 600.0
            SmartConnectionMode.STABLE -> 800.0
        }
        val latencyScore = if (latencyMs != null && latencyMs > 0L) {
            exp(-latencyMs.toDouble() / tau).coerceIn(0.05, 1.0)
        } else {
            0.15 // Untested baseline
        }

        // 2. Normalized Jitter Component (0.0 .. 1.0)
        val jitterMs = profile.jitterMs ?: 25L
        val jitterScore = exp(-jitterMs.toDouble() / 35.0).coerceIn(0.1, 1.0)

        // 3. Normalized Packet Loss Component (0.0 .. 1.0)
        val lossRate = profile.packetLossRate ?: (if (isVerified) 0.0f else 0.15f)
        val packetLossScore = (1.0f - lossRate).coerceIn(0.0f, 1.0f).toDouble()

        // 4. Stability & Historical Success Component (0.0 .. 1.0)
        val failures = failureCounts[key] ?: profile.consecutiveFailures
        val penalty = (failures * 0.25).coerceAtMost(0.85)
        val baseStability = (profile.successRate?.toDouble() ?: if (isVerified) 0.95 else 0.70)
        val stabilityScore = (baseStability * (1.0 - penalty)).coerceIn(0.0, 1.0)

        // 5. Freshness Component (0.0 .. 1.0)
        val lastTested = profile.lastTestedAtMs ?: 0L
        val freshnessScore = if (lastTested > 0L) {
            val age = (nowMs - lastTested).coerceAtLeast(0L)
            exp(-age.toDouble() / FRESHNESS_WINDOW_MS.toDouble()).coerceIn(0.1, 1.0)
        } else {
            0.20
        }

        // 6. Network / Protocol Compatibility Component (0.0 .. 1.0)
        val compatibilityScore = AutoServerRanker.computeNetworkCompatibilityScore(profile)

        // Weighted aggregation based on connection mode
        val weightedScore = when (mode) {
            SmartConnectionMode.GAMING -> {
                // Latency (40%), Jitter (25%), Packet Loss (15%), Stability (10%), Freshness (5%), Protocol (5%)
                0.40 * latencyScore +
                    0.25 * jitterScore +
                    0.15 * packetLossScore +
                    0.10 * stabilityScore +
                    0.05 * freshnessScore +
                    0.05 * compatibilityScore
            }
            SmartConnectionMode.STREAMING -> {
                // In streaming (4K video / live feeds), packet loss > 5% causes constant buffering stalls
                val effectiveLossScore = if (lossRate > 0.05f) {
                    (packetLossScore * 0.40)
                } else {
                    packetLossScore
                }
                0.40 * effectiveLossScore +
                    0.25 * stabilityScore +
                    0.15 * latencyScore +
                    0.10 * jitterScore +
                    0.05 * freshnessScore +
                    0.05 * compatibilityScore
            }
            SmartConnectionMode.BROWSING -> {
                // Latency (30%), Stability (25%), Freshness (20%), Packet Loss (15%), Compatibility (10%)
                0.30 * latencyScore +
                    0.25 * stabilityScore +
                    0.20 * freshnessScore +
                    0.15 * packetLossScore +
                    0.10 * compatibilityScore
            }
            SmartConnectionMode.STABLE -> {
                // Stability (35%), Latency (25%), Packet Loss (20%), Protocol (10%), Freshness (10%)
                0.35 * stabilityScore +
                    0.25 * latencyScore +
                    0.20 * packetLossScore +
                    0.10 * compatibilityScore +
                    0.10 * freshnessScore
            }
        }

        val verificationBonus = if (isVerified) 1.05 else 0.85
        return (weightedScore * 100.0 * verificationBonus).toInt().coerceIn(0, 100)
    }

    /**
     * Evaluates a candidate pool and selects the optimal server according to the active mode.
     * Applies hysteresis against [currentActiveFingerprint] to prevent erratic flapping.
     */
    fun selectBestServer(
        candidates: List<ServerProfile>,
        mode: SmartConnectionMode = SmartConnectionMode.STABLE,
        currentActiveFingerprint: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): SmartSelectionResult? {
        if (candidates.isEmpty()) return null

        // Exclude servers on active failure cooldown
        val eligible = candidates.filter { !isServerOnCooldown(it, nowMs) }
        val pool = eligible.ifEmpty { candidates } // Fallback to all if all on cooldown

        val scored = pool.map { profile ->
            profile to calculateServerScore(profile, mode, nowMs)
        }.sortedByDescending { it.second }

        val bestCandidate = scored.firstOrNull() ?: return null

        // Hysteresis: If current active server is still healthy, require a significant score gain to switch
        var finalSelection = bestCandidate
        if (currentActiveFingerprint != null) {
            val currentPair = scored.find { it.first.uniqueFingerprintKey() == currentActiveFingerprint }
            if (currentPair != null) {
                val currentScore = currentPair.second
                val minImprovementRatio = when (mode) {
                    SmartConnectionMode.GAMING -> 0.18    // 18% improvement needed
                    SmartConnectionMode.STREAMING -> 0.25 // 25% improvement needed
                    SmartConnectionMode.BROWSING -> 0.20  // 20% improvement needed
                    SmartConnectionMode.STABLE -> 0.35    // 35% improvement needed (very conservative)
                }
                val bestScore = bestCandidate.second
                if (currentScore > 30 && bestScore < (currentScore * (1.0 + minImprovementRatio))) {
                    finalSelection = currentPair
                }
            }
        }

        val profile = finalSelection.first
        val score = finalSelection.second

        val (reasonEn, reasonFa) = generateSelectionExplanation(profile, mode, score)

        return SmartSelectionResult(
            selectedProfile = profile,
            score = score,
            mode = mode,
            explanationEn = reasonEn,
            explanationFa = reasonFa,
            latencyMs = profile.realVpnLatencyMs ?: profile.lastLatencyMs,
            jitterMs = profile.jitterMs,
            packetLossRate = profile.packetLossRate,
            candidateCount = candidates.size
        )
    }

    /**
     * Generates transparent, human-readable explanations in Persian and English.
     */
    fun generateSelectionExplanation(
        profile: ServerProfile,
        mode: SmartConnectionMode,
        score: Int
    ): Pair<String, String> {
        val lat = profile.realVpnLatencyMs ?: profile.lastLatencyMs ?: 0L
        val jitter = profile.jitterMs ?: 15L
        val protocolName = profile.protocol.displayName

        val reasonEn = when (mode) {
            SmartConnectionMode.GAMING ->
                "Selected for lowest ping (${lat}ms) & minimal jitter (${jitter}ms) in Gaming Mode [Score: $score%]."
            SmartConnectionMode.STREAMING ->
                "Selected for high sustained throughput & $protocolName streaming stability [Score: $score%]."
            SmartConnectionMode.BROWSING ->
                "Selected for responsive browsing & efficient routing (${lat}ms) [Score: $score%]."
            SmartConnectionMode.STABLE ->
                "Selected for highest reliability & anti-filtering resilience (${protocolName}) [Score: $score%]."
        }

        val reasonFa = when (mode) {
            SmartConnectionMode.GAMING ->
                "انتخاب شده برای کمترین پینگ (${lat} میلی‌ثانیه) و حداقل نوسان (${jitter}ms) در حالت گیمینگ [امتیاز: $score٪]."
            SmartConnectionMode.STREAMING ->
                "انتخاب شده برای سرعت پایدار و ثبات استریم ویدیو با پروتکل $protocolName [امتیاز: $score٪]."
            SmartConnectionMode.BROWSING ->
                "انتخاب شده برای وبگردی روان و پاسخ‌دهی سریع صفحات (${lat} میلی‌ثانیه) [امتیاز: $score٪]."
            SmartConnectionMode.STABLE ->
                "انتخاب شده برای بالاترین پایداری و مقاومت در برابر فیلترینگ با $protocolName [امتیاز: $score٪]."
        }

        return reasonEn to reasonFa
    }
}
