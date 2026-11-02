package com.example.core.resilience

import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType

/**
 * Central configuration for the National Internet Resilience & Smart Global Connectivity system.
 * When [masterResilienceEnabled] is false, the entire resilience engine is bypassed and the app
 * behaves identically to its pre-upgrade baseline.
 */
data class ConnectivityConfig(
    val masterResilienceEnabled: Boolean = true,
    val lowDataMode: Boolean = false,
    val allowAutoServerSwitch: Boolean = true,
    val allowRuntimeVariants: Boolean = false,
    val multiNetworkAssistEnabled: Boolean = false,
    val domesticDiscoveryEnabled: Boolean = true,
    val relayFallbackEnabled: Boolean = true,
    val customDomesticEndpoints: List<String> = emptyList(),
    val dnsTimeoutMs: Long = 2_000L,
    val tcpTimeoutMs: Long = 2_500L,
    val tlsTimeoutMs: Long = 3_500L,
    val httpTimeoutMs: Long = 4_000L,
    val xferTimeoutMs: Long = 5_000L,
    val maxParallelProbes: Int = 3,
    val maxCycleDurationMs: Long = 10_000L,
    val minProbeCycleIntervalMs: Long = 5_000L,
    val probeCacheTtlMs: Long = 20_000L,
    val meteredTenMinByteCap: Long = 256L * 1024L,
    val hysteresisMinCycles: Int = 2,
    val hysteresisMinDwellMs: Long = 10_000L,
    val clockSkewThresholdSeconds: Long = 90L,
    val maxCoreRestartsPerTenMin: Int = 6,
    val circuitBreakerFailureThreshold: Int = 3,
    val circuitBreakerBaseCooldownMs: Long = 5 * 60_000L,
    val circuitBreakerMaxCooldownMs: Long = 60 * 60_000L,
    val tunnelVerifyStabilityWindowMs: Long = 5_000L
)

enum class Plane {
    UNDERLAY,
    OVERLAY
}

typealias ProbePlane = Plane

enum class ProbeLayer {
    IP,
    DNS,
    TLS,
    HTTP,
    XFER
}

enum class ProbeEndpointRole {
    INTERNATIONAL,
    DOMESTIC,
    TUNNEL_CHECK
}

data class ProbeEndpoint(
    val id: String,
    val role: ProbeEndpointRole,
    val providerGroup: String,
    val url: String,
    val host: String,
    val port: Int = 443,
    val expectedHttpStatuses: Set<Int> = setOf(204, 200),
    val expectedBodyPrefix: String? = null,
    val spkiPinSha256: String? = null,
    val priority: Int = 100
)

data class ProbeResult(
    val endpointId: String,
    val rawEndpointKey: String,
    val role: ProbeEndpointRole,
    val providerGroup: String,
    val plane: Plane,
    val layerReached: ProbeLayer?,
    val success: Boolean,
    val dnsLatencyMs: Long? = null,
    val tcpLatencyMs: Long? = null,
    val tlsLatencyMs: Long? = null,
    val httpLatencyMs: Long? = null,
    val totalLatencyMs: Long = 0L,
    val httpStatusCode: Int? = null,
    val bytesTransferred: Long = 0L,
    val throughputBps: Long? = null,
    val resolvedIps: List<String> = emptyList(),
    val clockSkewSeconds: Long? = null,
    val failureCategory: FailureCategory? = null,
    val errorSummary: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class NationalInternetState {
    NORMAL_INTERNATIONAL,
    RESTRICTED_INTERNATIONAL,
    DOMESTIC_ONLY_SUSPECTED,
    PARTIAL_INTERNATIONAL,
    NETWORK_UNSTABLE,
    NO_NETWORK,
    CAPTIVE_PORTAL_SUSPECTED,
    APP_BLOCKED_BY_OS,
    UNKNOWN
}

typealias NationalConnectivityState = NationalInternetState

enum class DomesticReachabilityState {
    YES,
    NO,
    NOT_TESTED
}

data class DomesticReachability(
    val state: DomesticReachabilityState,
    val testedCount: Int = 0,
    val successCount: Int = 0,
    val medianLatencyMs: Long? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    companion object {
        val YES = DomesticReachability(DomesticReachabilityState.YES, testedCount = 1, successCount = 1)
        val NO = DomesticReachability(DomesticReachabilityState.NO, testedCount = 1, successCount = 0)
        val NOT_TESTED = DomesticReachability(DomesticReachabilityState.NOT_TESTED)
    }
}

data class NetworkAssessment(
    val state: NationalInternetState,
    val rawCandidateState: NationalInternetState = state,
    val confidence: Float,
    val domesticReachability: DomesticReachability = DomesticReachability.NOT_TESTED,
    val internationalTestedCount: Int = 0,
    val internationalSuccessCount: Int = 0,
    val providerGroupsTested: Int = 0,
    val providerGroupsSucceeded: Int = 0,
    val medianLatencyMs: Long? = null,
    val p95LatencyMs: Long? = null,
    val lossRatio: Float = 0f,
    val dominantFailureCategory: FailureCategory? = null,
    val transportKind: TransportKind = TransportKind.UNKNOWN,
    val networkHandleId: Long? = null,
    val probeResults: List<ProbeResult> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
) {
    val confidencePercent: Int
        get() = (confidence * 100f).toInt().coerceIn(0, 100)

    companion object {
        val INITIAL = NetworkAssessment(
            state = NationalInternetState.UNKNOWN,
            confidence = 0f
        )
    }
}

enum class FailureCategory {
    // Required categories (Section 10.1)
    DNS_FAILURE,
    TCP_TIMEOUT,
    TCP_REFUSED,
    TLS_HANDSHAKE_FAILURE,
    VPN_HANDSHAKE_FAILURE,
    SERVER_UNREACHABLE,
    TRANSPORT_UNAVAILABLE,
    NETWORK_CHANGED,
    DOMESTIC_ONLY_SUSPECTED,
    INTERNATIONAL_ROUTE_UNAVAILABLE,
    RELAY_UNAVAILABLE,
    AUTHENTICATION_FAILURE,
    CONFIGURATION_ERROR,
    UNKNOWN_FAILURE,

    // Recommended fine-grained categories (Section 10.1)
    DNS_TAMPERING_SUSPECTED,
    TCP_RESET,
    TLS_CERTIFICATE_INVALID,
    CAPTIVE_PORTAL_SUSPECTED,
    HTTP_BLOCKPAGE_SUSPECTED,
    THROTTLING_SUSPECTED,
    CLOCK_SKEW_SUSPECTED,
    OS_NETWORK_BLOCKED,
    TUNNEL_NO_TRAFFIC
}

enum class RecoveryOutcome {
    RECOVERED,
    SWITCHED,
    GAVE_UP,
    PENDING
}

enum class TransportKind {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
    NONE,
    UNKNOWN
}

typealias NetworkType = TransportKind

data class FailureRecord(
    val timestampEpochMs: Long,
    val networkType: TransportKind,
    val nationalInternetState: NationalInternetState = NationalInternetState.UNKNOWN,
    val nationalStateConfidence: Float = 0f,
    val domesticReachable: DomesticReachabilityState = DomesticReachabilityState.NOT_TESTED,
    val endpointId: String,
    val transport: String,
    val portBucket: String,
    val failureCategory: FailureCategory,
    val rttMs: Long? = null,
    val recoveryActionTaken: String = "NONE",
    val outcome: RecoveryOutcome = RecoveryOutcome.PENDING,
    val sanitizedDetail: String = recoveryActionTaken
) {
    val category: FailureCategory
        get() = failureCategory
}

enum class PathTrustLevel {
    TRUSTED_USER,
    UNTRUSTED
}

enum class RelayMode {
    /** Mode A: A complete user-configured ServerProfile whose endpoint is an authorized relay. */
    FULL_PROFILE,
    /** Mode A2: Runtime address/port override to an authorized relay while preserving gateway SNI/Host. */
    ENDPOINT_OVERRIDE,
    /** Alias for Mode A2 traffic forwarder override. */
    MODE_A2_TRAFFIC_FORWARDER
}

enum class RelayHealthState {
    UNTESTED,
    RELAY_REACHABLE,
    RELAY_FORWARDING_OK,
    RELAY_UNAVAILABLE
}

data class AuthorizedRelay(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val mode: RelayMode = RelayMode.ENDPOINT_OVERRIDE,
    val supportedTransports: Set<TransportType> = setOf(
        TransportType.WS,
        TransportType.GRPC,
        TransportType.TCP,
        TransportType.H2,
        TransportType.HTTPUPGRADE,
        TransportType.XHTTP
    ),
    val requireTlsOrReality: Boolean = true,
    val sniOverride: String? = null,
    val pinnedSpkiSha256: String? = null,
    val enabled: Boolean = true,
    val associatedProfileIds: Set<Long> = emptySet(), // empty = eligible for any compatible TRUSTED_USER profile
    val ownerAttested: Boolean = true
) {
    val address: String
        get() = host

    val isEnabled: Boolean
        get() = enabled

    val targetServerIds: Set<String>
        get() = associatedProfileIds.map { it.toString() }.toSet()
}

data class RelayHealthReport(
    val relayId: String,
    val endpointId: String,
    val state: RelayHealthState,
    val tcpTlsLatencyMs: Long? = null,
    val forwardingLatencyMs: Long? = null,
    val failureCategory: FailureCategory? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ConnectivityPath(
    val pathId: String,
    val profile: ServerProfile,
    val relay: AuthorizedRelay? = null,
    val isRuntimeVariant: Boolean = false,
    val variantLabel: String? = null,
    val trustLevel: PathTrustLevel = PathTrustLevel.TRUSTED_USER,
    val networkHandleId: Long? = null
) {
    val isViaRelay: Boolean get() = relay != null
    val viaRelayId: String? get() = relay?.id
    val effectiveHost: String get() = relay?.host ?: profile.address
    val effectivePort: Int get() = relay?.port ?: profile.port
    val transportVariant: String
        get() = variantLabel ?: "${profile.protocol.name.lowercase()}:${profile.network.name.lowercase()}:${profile.security.name.lowercase()}"
}

enum class AssessmentRung {
    DNS_CHECK,
    TCP_CONNECT,
    TLS_HANDSHAKE,
    VPN_CORE_DELAY,
    STABILITY_CHECK
}

data class PathAssessmentResult(
    val path: ConnectivityPath,
    val endpointId: String,
    val reachable: Boolean,
    val highestRungReached: AssessmentRung?,
    val dnsLatencyMs: Long? = null,
    val tcpLatencyMs: Long? = null,
    val tlsLatencyMs: Long? = null,
    val vpnDelayMs: Long? = null,
    val stabilityPassRatio: Float = 0f,
    val compositeScore: Double = 0.0,
    val failureCategory: FailureCategory? = null,
    val relayHealth: RelayHealthReport? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class TunnelVerificationReport(
    val verified: Boolean,
    val probesAttempted: Int,
    val probesSucceeded: Int,
    val providerGroupsSucceeded: Int,
    val medianOverlayLatencyMs: Long? = null,
    val rxBytesDelta: Long = 0L,
    val txBytesDelta: Long = 0L,
    val dominantFailure: FailureCategory? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class EngineResilienceState {
    IDLE,
    ASSESSING,
    CONNECTING,
    VERIFYING,
    CONNECTED,
    DEGRADED,
    RECOVERING,
    LONG_OUTAGE_WAIT,
    TERMINAL_ERROR,
    STOPPED_BY_USER
}

sealed class RecoveryAction(val label: String) {
    data class KeepCurrent(val reason: String) : RecoveryAction("KEEP_CURRENT")
    data class RetrySamePath(
        val path: ConnectivityPath,
        val delayMs: Long,
        val refreshDns: Boolean = false,
        val rebindNetwork: Boolean = false
    ) : RecoveryAction("RETRY_SAME_PATH")

    data class SwitchPath(
        val targetPath: ConnectivityPath,
        val reason: String,
        val viaRelay: Boolean = targetPath.isViaRelay
    ) : RecoveryAction("SWITCH_PATH")

    data class EnterLongOutageWait(
        val pollDelayMs: Long,
        val domesticOnlySuspected: Boolean
    ) : RecoveryAction("LONG_OUTAGE_WAIT")

    data class HaltTerminal(
        val category: FailureCategory,
        val userMessagePersian: String
    ) : RecoveryAction("HALT_TERMINAL")
}

data class NetworkSnapshot(
    val networkHandleId: Long?,
    val transportKind: TransportKind,
    val hasInternetCapability: Boolean,
    val isValidated: Boolean,
    val isNotVpn: Boolean = true,
    val isMetered: Boolean = false,
    val dnsServers: List<String> = emptyList(),
    val interfaceName: String? = null,
    val hasIpv4: Boolean = true,
    val hasIpv6: Boolean = false,
    val underlyingNetwork: android.net.Network? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val networkHandle: Long?
        get() = networkHandleId

    val networkType: TransportKind
        get() = transportKind

    val hasValidatedCapability: Boolean
        get() = isValidated

    companion object {
        val NONE = NetworkSnapshot(
            networkHandleId = null,
            transportKind = TransportKind.NONE,
            hasInternetCapability = false,
            isValidated = false,
            isNotVpn = true,
            isMetered = false
        )
    }
}

/**
 * Exact Persian UI representations required by Section 16.1 of the specification.
 */
object PersianConnectivityStrings {
    const val NORMAL_INTERNATIONAL = "وضعیت شبکه: اینترنت بین‌الملل در دسترس"
    const val RESTRICTED_INTERNATIONAL = "وضعیت شبکه: اینترنت بین‌الملل محدود یا ناپایدار"
    const val DOMESTIC_ONLY_SUSPECTED = "وضعیت شبکه: احتمال محدودیت به شبکه داخلی"
    const val TESTING_PATHS = "در حال بررسی مسیرهای جایگزین..."
    const val VIA_RELAY = "اتصال از طریق مسیر واسط برقرار شد"
    const val CONNECTED_VIA_RELAY = VIA_RELAY
    const val VIA_RELAY_BADGE = "از طریق مسیر واسط"
    const val NO_INTERNATIONAL_ROUTE = "در حال حاضر هیچ مسیر بین‌المللی قابل دسترس نیست"
    const val VERIFYING_CONNECTION = "در حال تأیید اتصال"
    const val SECURE_CONNECTED = "اتصال امن برقرار شد"
    const val CONNECTED_VERIFIED = SECURE_CONNECTED
    const val CAPTIVE_PORTAL = "نیاز به ورود به پورتال شبکه (Captive Portal)"
    const val CLOCK_SKEW_WARNING = "تنظیم ساعت دستگاه نادرست است و ممکن است باعث خطای TLS شود"
    const val CONFIG_OR_AUTH_ERROR = "خطا در پیکربندی یا احراز هویت سرور"
    const val NO_NETWORK = "اتصال به هیچ شبکه‌ای برقرار نیست"
}

data class ResilienceUiSnapshot(
    val enabled: Boolean = true,
    val engineState: EngineResilienceState = EngineResilienceState.IDLE,
    val networkAssessment: NetworkAssessment = NetworkAssessment.INITIAL,
    val activePath: ConnectivityPath? = null,
    val tunnelVerified: Boolean = false,
    val isViaRelay: Boolean = false,
    val headlinePersian: String = PersianConnectivityStrings.NORMAL_INTERNATIONAL,
    val subtitlePersian: String = "",
    val badgePersian: String? = null,
    val recentFailures: List<FailureRecord> = emptyList(),
    val relayReports: Map<String, RelayHealthReport> = emptyMap(),
    val nextRetryInMs: Long? = null
) {
    val connectivityState: NationalInternetState
        get() = networkAssessment.state

    val confidencePercent: Int
        get() = networkAssessment.confidencePercent

    val persianTitle: String
        get() = headlinePersian

    val persianSubtitle: String
        get() = subtitlePersian

    val isConnectedViaRelay: Boolean
        get() = isViaRelay

    val internationalReachable: Boolean
        get() = networkAssessment.state == NationalInternetState.NORMAL_INTERNATIONAL ||
            networkAssessment.state == NationalInternetState.PARTIAL_INTERNATIONAL ||
            networkAssessment.internationalSuccessCount > 0

    val domesticReachable: DomesticReachabilityState
        get() = networkAssessment.domesticReachability.state

    val activeNetworkType: TransportKind
        get() = networkAssessment.transportKind
}

/**
 * Seam for integrating SmartGlobalConnectivityEngine with SmartEngine and SmartFailoverManager.
 */
interface ConnectivityAdvisor {
    fun getLatestAssessment(): NetworkAssessment
    fun isCircuitOpen(pathId: String, currentTransport: TransportKind): Boolean
    fun rankCandidatePaths(
        candidates: List<ConnectivityPath>,
        assessment: NetworkAssessment
    ): List<ConnectivityPath>
}
