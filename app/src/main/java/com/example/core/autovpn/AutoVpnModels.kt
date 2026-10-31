package com.example.core.autovpn

import com.example.domain.model.ServerProfile
import com.example.domain.model.VpnProtocol

/**
 * Lifecycle stages of the MAXNET5G Intelligent Auto Free VPN System.
 */
enum class AutoVpnStage(
    val titleEn: String,
    val titleFa: String
) {
    IDLE(
        titleEn = "Intelligent Auto-Connect Ready",
        titleFa = "آماده اتصال هوشمند خودکار"
    ),
    DISCOVERING_CONFIGS(
        titleEn = "Discovering Public Configurations...",
        titleFa = "در حال کشف خودکار کانفیگ‌های رایگان..."
    ),
    TESTING_CANDIDATES(
        titleEn = "Testing Candidate Servers in Parallel...",
        titleFa = "تست موازی سرورهای کاندید..."
    ),
    RANKING_SERVERS(
        titleEn = "Ranking Servers by Real Quality...",
        titleFa = "رتبه‌بندی هوشمند بر اساس کیفیت واقعی..."
    ),
    CONNECTING_BEST(
        titleEn = "Connecting to Optimal Server...",
        titleFa = "در حال اتصال به بهترین سرور..."
    ),
    VERIFYING_TUNNEL(
        titleEn = "Verifying Real VPN Tunnel (HTTP 204)...",
        titleFa = "تایید عملیاتی بودن تونل VPN..."
    ),
    CONNECTED(
        titleEn = "Connected • Auto-Optimized Tunnel",
        titleFa = "متصل • تونل هوشمند بهینه‌شده"
    ),
    FAILOVER_SWITCHING(
        titleEn = "Failover Active • Switching to Next Best Server...",
        titleFa = "تعویض هوشمند به سرور جایگزین..."
    ),
    OPTIMIZING(
        titleEn = "Continuous Health & Quality Optimization...",
        titleFa = "پایش و بهینه‌سازی پیوسته کیفیت اتصال..."
    ),
    EXHAUSTED_ERROR(
        titleEn = "All Candidate Servers Unreachable",
        titleFa = "عدم پاسخگویی سرورهای کاندید در شبکه فعلی"
    )
}

/**
 * Quality grade computed from real measured telemetry (never geographic preference).
 */
enum class AutoQualityGrade(
    val badgeEn: String,
    val badgeFa: String
) {
    EXCELLENT("Excellent", "عالی"),
    GOOD("Good", "خوب"),
    FAIR("Fair", "متوسط"),
    DEGRADED("Degraded", "ضعیف"),
    UNVERIFIED("Standby", "آماده تست")
}

/**
 * Configurable public configuration source (GitHub raw feeds or lightweight collection backend API).
 */
data class AutoVpnSourceFeed(
    val id: String,
    val name: String,
    val url: String,
    val isBackendJsonFeed: Boolean = false,
    val enabled: Boolean = true
)

/**
 * Measured quality metrics for a candidate configuration.
 * Ranking uses strictly non-geographic network metrics:
 * - Real connection success & recent validation timestamp
 * - Connection latency (ms)
 * - Actual download performance (bps)
 * - Connection stability (success vs failure ratio & consecutive failures)
 * - Network compatibility (protocol/transport/security suitability)
 */
data class CandidateQualityMetrics(
    val fingerprintKey: String,
    val protocol: VpnProtocol,
    val isReality: Boolean = false,
    val latencyMs: Long? = null,
    val proxyVerified: Boolean = false,
    val downloadBps: Long? = null,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val cooldownUntilMs: Long = 0L,
    val lastValidatedAtMs: Long = 0L,
    val stabilityRatio: Double = 1.0,
    val networkCompatibilityScore: Double = 0.8,
    val compositeRankScore: Double = 0.0,
    val qualityGrade: AutoQualityGrade = AutoQualityGrade.UNVERIFIED
) {
    fun isOnCooldown(nowMs: Long = System.currentTimeMillis()): Boolean {
        return cooldownUntilMs > nowMs
    }
}

/**
 * Paired candidate profile and its computed quality metrics.
 */
data class RankedVpnCandidate(
    val profile: ServerProfile,
    val metrics: CandidateQualityMetrics
)

/**
 * Immutable UI snapshot of the Intelligent Auto Free VPN System.
 */
data class AutoVpnStatusSnapshot(
    val stage: AutoVpnStage = AutoVpnStage.IDLE,
    val detailMessageEn: String = "One tap automatically discovers, tests, ranks, and connects.",
    val detailMessageFa: String = "با یک لمس، بهترین سرور رایگان به صورت خودکار کشف، تست و متصل می‌شود.",
    val discoveredCount: Int = 0,
    val testedCount: Int = 0,
    val totalToTestCount: Int = 0,
    val workingCandidateCount: Int = 0,
    val cachedValidatedCount: Int = 0,
    val activeCandidate: RankedVpnCandidate? = null,
    val failoverAttempt: Int = 0,
    val maxFailoverAttempts: Int = 5,
    val isContinuousOptimizationActive: Boolean = false,
    val lastUpdatedMs: Long = 0L
)
