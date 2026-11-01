package com.example.core.autovpn

import com.example.domain.model.ServerProfile
import com.example.domain.model.VpnProtocol

/**
 * Top-level operating mode of MAXNET5G VPN.
 * - [FREE_VPN]: Uses exclusively repository-managed free configurations (`free-configs/configs.txt` & `manifest.json`).
 * - [MY_VPN]: Uses exclusively the user's personal configurations and subscriptions stored in Room.
 */
enum class VpnAppMode(
    val titleEn: String,
    val titleFa: String,
    val subtitleEn: String,
    val subtitleFa: String
) {
    FREE_VPN(
        titleEn = "FREE VPN",
        titleFa = "وی‌پی‌ان رایگان",
        subtitleEn = "Automatic GitHub Free Configs • One-Tap Smart Connect",
        subtitleFa = "دریافت خودکار از گیت‌هاب • اتصال هوشمند با یک لمس"
    ),
    MY_VPN(
        titleEn = "MY VPN",
        titleFa = "وی‌پی‌ان شخصی من",
        subtitleEn = "Personal Configs & Subscriptions • Full Control",
        subtitleFa = "کانفیگ‌ها و اشتراک‌های شخصی • مدیریت کامل"
    )
}

/**
 * Tracks single-engine session ownership across the shared Xray-core / VpnService pipeline.
 */
enum class ConnectionOwnership(
    val labelEn: String,
    val labelFa: String
) {
    NONE("Disconnected", "غیرمتصل"),
    FREE("Free VPN Active", "وی‌پی‌ان رایگان فعال"),
    PERSONAL("Personal VPN Active", "وی‌پی‌ان شخصی فعال")
}

/**
 * Cryptographically hashed metadata manifest (`free-configs/manifest.json`) from the
 * official MAXNET5G GitHub repository (`https://github.com/mohsenakbarinia/maxnet5gvpn`).
 */
data class FreeConfigManifest(
    val schemaVersion: Int = 1,
    val version: Int = 1,
    val updatedAt: String = "",
    val configCount: Int = 0,
    val sha256: String = "",
    val repository: String = "https://github.com/mohsenakbarinia/maxnet5gvpn",
    val configsPath: String = "free-configs/configs.txt",
    val signature: String = ""
)

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
        titleEn = "Syncing Free Configurations from GitHub...",
        titleFa = "در حال دریافت کانفیگ‌های رایگان از گیت‌هاب..."
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
 * Configurable public configuration source (official MAXNET5G GitHub repository feed).
 */
data class AutoVpnSourceFeed(
    val id: String,
    val name: String,
    val url: String,
    val manifestUrl: String? = null,
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
    val manifestVersion: Int = 1,
    val manifestUpdatedAt: String = "",
    val manifestSha256: String = "",
    val isUsingOfflineCache: Boolean = false,
    val lastSyncStatusEn: String = "Official GitHub Repository • Verified SHA-256",
    val lastSyncStatusFa: String = "مخزن رسمی گیت‌هاب • تاییدشده با SHA-256",
    val lastUpdatedMs: Long = 0L
)
