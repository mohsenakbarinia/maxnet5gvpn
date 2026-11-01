package com.example.domain.model

enum class VpnProtocol(val displayName: String, val scheme: String) {
    VLESS("VLESS", "vless"),
    VMESS("VMess", "vmess"),
    TROJAN("Trojan", "trojan"),
    SHADOWSOCKS("Shadowsocks", "ss"),
    HYSTERIA2("Hysteria2", "hysteria2"),
    WIREGUARD("WireGuard", "wireguard"),
    TUIC("TUIC", "tuic");

    companion object {
        fun fromScheme(scheme: String): VpnProtocol? {
            return when (scheme.lowercase().trim()) {
                "vless" -> VLESS
                "vmess" -> VMESS
                "trojan" -> TROJAN
                "ss", "shadowsocks" -> SHADOWSOCKS
                "hysteria2", "hy2" -> HYSTERIA2
                "wireguard", "wg" -> WIREGUARD
                "tuic" -> TUIC
                else -> null
            }
        }
    }
}

enum class TransportType(val wireName: String, val displayName: String) {
    TCP("tcp", "TCP"),
    WS("ws", "WebSocket"),
    GRPC("grpc", "gRPC"),
    H2("h2", "HTTP/2"),
    HTTPUPGRADE("httpupgrade", "HTTPUpgrade"),
    XHTTP("xhttp", "XHTTP / SplitHTTP"),
    MKCP("kcp", "mKCP"),
    QUIC("quic", "QUIC");

    companion object {
        fun fromWire(value: String?): TransportType {
            return when (value?.lowercase()?.trim()) {
                "ws", "websocket" -> WS
                "grpc", "gun" -> GRPC
                "h2", "http", "http2" -> H2
                "httpupgrade" -> HTTPUPGRADE
                "xhttp", "splithttp" -> XHTTP
                "kcp", "mkcp" -> MKCP
                "quic" -> QUIC
                else -> TCP
            }
        }
    }
}

enum class SecurityType(val wireName: String, val displayName: String) {
    NONE("none", "None"),
    TLS("tls", "TLS"),
    REALITY("reality", "REALITY");

    companion object {
        fun fromWire(value: String?): SecurityType {
            return when (value?.lowercase()?.trim()) {
                "tls", "xtls" -> TLS
                "reality" -> REALITY
                else -> NONE
            }
        }
    }
}

enum class EngineType(val id: String, val displayName: String) {
    XRAY_CORE("xray", "Xray-core (Primary)"),
    SING_BOX("sing-box", "sing-box (Universal)")
}

enum class RoutingMode(val id: String) {
    GLOBAL("global"),
    RULE_BASED("rule_based"),
    DIRECT("direct")
}

enum class SplitTunnelMode(val id: String) {
    ALL_APPS("all"),
    ALLOW_SELECTED("allow_selected"),
    DISALLOW_SELECTED("disallow_selected")
}

enum class VpnConnectionState {
    DISCONNECTED,
    PREPARING,
    STARTING_ENGINE,
    CONNECTING,
    VERIFYING,
    CONNECTED,
    RECONNECTING,
    STOPPING,
    ERROR
}

enum class LatencyMeasureType(val label: String) {
    UNTESTED("Untested"),
    TCP_HANDSHAKE("TCP Connect"),
    PROXY_HTTP_DELAY("Real VPN Latency"),
    TIMEOUT("Failed")
}

enum class ServerConnectionStatus(val labelEn: String, val labelFa: String) {
    UNTESTED("Untested", "تست‌نشده"),
    TESTING("Testing...", "در حال تست..."),
    ALIVE("Usable Proxy", "متصل / سالم"),
    FAILED("Unreachable", "ناموفق / خطا")
}

enum class SmartConnectionMode(
    val id: String,
    val titleEn: String,
    val titleFa: String,
    val descriptionEn: String,
    val descriptionFa: String,
    val iconEmoji: String
) {
    GAMING(
        id = "gaming",
        titleEn = "Gaming Mode",
        titleFa = "حالت گیمینگ",
        descriptionEn = "Ultra-low latency & minimal jitter for competitive gaming",
        descriptionFa = "کمترین پینگ و حداقل نوسان (Jitter) ویژه بازی‌های آنلاین",
        iconEmoji = "🎮"
    ),
    STREAMING(
        id = "streaming",
        titleEn = "Streaming Mode",
        titleFa = "حالت استریم",
        descriptionEn = "High sustained throughput & zero packet loss for 4K video",
        descriptionFa = "حداکثر سرعت پایدار و بدون قطعی برای تماشای ویدیو و استریم",
        iconEmoji = "🎬"
    ),
    BROWSING(
        id = "browsing",
        titleEn = "Browsing Mode",
        titleFa = "حالت وبگردی",
        descriptionEn = "Responsive DNS resolution & optimized battery efficiency",
        descriptionFa = "پاسخ‌دهی سریع صفحات وب، ضد تبلیغ و مصرف بهینه باتری",
        iconEmoji = "🌐"
    ),
    STABLE(
        id = "stable",
        titleEn = "Stable Mode",
        titleFa = "حالت پایدار",
        descriptionEn = "Maximum reliability, historical stability & anti-censorship",
        descriptionFa = "بالاترین پایداری، مقاومت در برابر فیلترینگ و حداقل جابجایی سرور",
        iconEmoji = "🛡️"
    );

    companion object {
        fun fromId(id: String?): SmartConnectionMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: STABLE
        }
    }
}

enum class ThemePreference {
    SYSTEM,
    DARK,
    AMOLED,
    LIGHT
}

@androidx.compose.runtime.Immutable
data class ServerProfile(
    val id: Long = 0L,
    val remarks: String,
    val groupName: String = "Default",
    val subscriptionId: Long? = null,
    val protocol: VpnProtocol = VpnProtocol.VLESS,
    val address: String,
    val port: Int,
    val credential: String, // UUID for VLESS/VMess/TUIC, Password for Trojan/SS/Hysteria2, PrivateKey for WG
    val encryption: String = "none", // e.g. none, auto, aes-128-gcm, chacha20-ietf-poly1305, 2022-blake3-aes-128-gcm
    val alterId: Int = 0,
    val flow: String = "", // e.g. xtls-rprx-vision
    val network: TransportType = TransportType.TCP,
    val headerType: String = "none",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val security: SecurityType = SecurityType.NONE,
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "chrome",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "",
    val wireGuardPublicKey: String = "",
    val wireGuardReserved: String = "",
    val wireGuardLocalAddress: String = "172.16.0.2/32",
    val allowInsecure: Boolean = false,
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false,
    val sortOrder: Int = 0,
    val lastLatencyMs: Long? = null,
    val tcpConnectMs: Long? = null,
    val jitterMs: Long? = null,
    val packetLossRate: Float? = null,
    val successRate: Float? = null,
    val healthScore: Int? = null,
    val consecutiveFailures: Int = 0,
    val lastFailureTimestampMs: Long? = null,
    val latencyType: LatencyMeasureType = LatencyMeasureType.UNTESTED,
    val lastTestedAtMs: Long? = null,
    val userCustomizedRemarks: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val connectionStatus: ServerConnectionStatus
        get() = when {
            latencyType == LatencyMeasureType.PROXY_HTTP_DELAY && lastLatencyMs != null && lastLatencyMs > 0L ->
                ServerConnectionStatus.ALIVE
            latencyType == LatencyMeasureType.TIMEOUT ->
                ServerConnectionStatus.FAILED
            lastLatencyMs != null && lastLatencyMs > 0L ->
                ServerConnectionStatus.ALIVE
            else ->
                ServerConnectionStatus.UNTESTED
        }

    /**
     * Primary Real VPN Latency (HTTP request-response time through Xray outbound).
     * Never returns a raw TCP handshake value as Real VPN Latency.
     */
    val realVpnLatencyMs: Long?
        get() = when {
            latencyType == LatencyMeasureType.PROXY_HTTP_DELAY && lastLatencyMs != null && lastLatencyMs > 0L ->
                lastLatencyMs
            latencyType == LatencyMeasureType.TCP_HANDSHAKE ->
                null
            else ->
                lastLatencyMs?.takeIf { it > 0L }
        }

    /**
     * Separate diagnostic TCP Connect time (server:port socket handshake).
     */
    val diagnosticTcpConnectMs: Long?
        get() = tcpConnectMs ?: if (latencyType == LatencyMeasureType.TCP_HANDSHAKE) lastLatencyMs else null

    fun uniqueFingerprintKey(): String {
        return "${protocol.name}|${address.trim().lowercase()}|$port|${credential.trim()}|${network.wireName}|${security.wireName}|${host.trim().lowercase()}|${path.trim()}|${serviceName.trim()}|${sni.trim().lowercase()}|${realityPublicKey.trim()}|${realityShortId.trim()}"
    }
}

enum class SubscriptionFormat(val labelFa: String, val labelEn: String) {
    PLAIN_TEXT("متن ساده (لینک‌های اشتراک)", "Plain-text share links"),
    BASE64_STANDARD("Base64 استاندارد", "Standard Base64"),
    BASE64_URL_SAFE("Base64 ایمن (URL-Safe)", "URL-safe Base64"),
    JSON("فرمت JSON", "JSON configuration"),
    INVALID("پاسخ نامعتبر", "Invalid response")
}

sealed class ClipboardInputType {
    data object Empty : ClipboardInputType()
    data class SubscriptionUrl(
        val url: String,
        val suggestedTitle: String,
        val isHttps: Boolean
    ) : ClipboardInputType()
    data class SingleShareLink(
        val protocol: VpnProtocol,
        val uri: String
    ) : ClipboardInputType()
    data class BulkShareLinks(
        val links: List<String>
    ) : ClipboardInputType()
    data class Base64Subscription(
        val rawBase64: String,
        val decodedText: String,
        val isUrlSafe: Boolean
    ) : ClipboardInputType()
    data class JsonSubscription(
        val rawJson: String
    ) : ClipboardInputType()
    data class Unrecognized(
        val rawSnippet: String,
        val reasonFa: String,
        val reasonEn: String
    ) : ClipboardInputType()
}

data class SubscriptionImportReport(
    val title: String = "نتیجه دریافت اشتراک",
    val sourceType: String = "SUBSCRIPTION", // SUBSCRIPTION or CLIPBOARD
    val detectedFormat: SubscriptionFormat = SubscriptionFormat.PLAIN_TEXT,
    val totalReceivedCount: Int = 0,
    val newImportedCount: Int = 0,
    val duplicateCount: Int = 0,
    val invalidCount: Int = 0,
    val statusMessageFa: String = "",
    val statusMessageEn: String = "",
    val isSuccess: Boolean = false,
    val httpStatusCode: Int? = null,
    val subscriptionItem: SubscriptionItem? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class SubscriptionItem(
    val id: Long = 0L,
    val title: String,
    val url: String,
    val enabledAutoUpdate: Boolean = true,
    val updateIntervalHours: Int = 24,
    val lastUpdatedAt: Long? = null,
    val lastStatus: String = "Never updated",
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireTimestampSec: Long? = null,
    val supportUrl: String = "",
    val webPageUrl: String = "",
    val announcement: String = "",
    val profileCount: Int = 0,
    val etag: String = "",
    val lastModifiedHeader: String = ""
)

enum class RoutingRuleType(val displayName: String) {
    DOMAIN("Domain / Suffix"),
    IP_CIDR("IP / CIDR"),
    GEOIP("GeoIP Code"),
    GEOSITE("GeoSite Category")
}

enum class OutboundTag(val wireTag: String, val displayName: String) {
    PROXY("proxy", "Proxy (VPN)"),
    DIRECT("direct", "Direct (Bypass)"),
    BLOCK("block", "Block (Reject)")
}

data class RoutingRuleItem(
    val id: Long = 0L,
    val name: String,
    val type: RoutingRuleType,
    val value: String,
    val outboundTag: OutboundTag,
    val isEnabled: Boolean = true
)

data class ConnectionHistoryItem(
    val id: Long = 0L,
    val profileName: String,
    val protocol: String,
    val engine: String,
    val connectedAt: Long,
    val disconnectedAt: Long?,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val statusNote: String,
    val failoverReason: String? = null,
    val activeMode: String = "STABLE",
    val averageLatencyMs: Long? = null
)

data class AppSettings(
    val selectedProfileId: Long? = null,
    val activeEngine: EngineType = EngineType.XRAY_CORE,
    val routingMode: RoutingMode = RoutingMode.RULE_BASED,
    val bypassLan: Boolean = true,
    val bypassCountryCode: String = "IR",
    val blockAds: Boolean = false,
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "1.1.1.1",
    val dnsQueryStrategy: String = "UseIPv4",
    val enableIpv6: Boolean = false,
    val enableFakeDns: Boolean = false,
    val mtu: Int = 1500,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val splitTunnelPackages: Set<String> = emptySet(),
    val languageCode: String = "fa-IR",
    val themePreference: ThemePreference = ThemePreference.DARK,
    val usePersianDigits: Boolean = true,
    val fontScale: Float = 1.0f,
    val autoReconnect: Boolean = true,
    val connectOnBoot: Boolean = false,
    val onboardingCompleted: Boolean = true,
    val profileSortMode: String = "DEFAULT",
    val connectivityCheckUrl: String = "https://www.gstatic.com/generate_204",
    val connectivityFallbackUrl: String = "https://cp.cloudflare.com/generate_204",
    val smartConnectionMode: SmartConnectionMode = SmartConnectionMode.STABLE,
    val smartConnectEnabled: Boolean = true,
    val autoFailoverEnabled: Boolean = true,
    val killSwitchEnabled: Boolean = false,
    val dnsLeakProtection: Boolean = true,
    val ipv6LeakProtection: Boolean = true,
    val secureDnsProvider: String = "Cloudflare"
)

enum class QualityTestStage {
    IDLE,
    TESTING_TUNNEL,
    TESTING_LATENCY,
    TESTING_INTERNET,
    TESTING_DOWNLOAD,
    TESTING_UPLOAD,
    COMPLETED,
    FAILED
}

data class ConnectionQualityReport(
    val stage: QualityTestStage = QualityTestStage.IDLE,
    val testedThroughVpn: Boolean = false,
    val tunnelConnected: Boolean = false,
    val internetReachable: Boolean = false,
    val latencyMs: Long? = null,
    val latencyType: LatencyMeasureType = LatencyMeasureType.UNTESTED,
    val downloadBps: Long? = null,
    val uploadBps: Long? = null,
    val summaryMessageFa: String = "",
    val summaryMessageEn: String = "",
    val errorDetail: String? = null,
    val timestampMs: Long = 0L
)

data class EngineStatistics(
    val uploadSpeedBps: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val sessionUploadBytes: Long = 0L,
    val sessionDownloadBytes: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val totalDownloadBytes: Long = 0L,
    val connectedSinceMs: Long? = null,
    val localSocksPort: Int? = null,
    val isTelemetryAuthentic: Boolean = true
)

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val generatedConfigJson: String = ""
)

data class DiagnosticSnapshot(
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val activeEngine: EngineType = EngineType.XRAY_CORE,
    val engineVersion: String = "Xray-core v26.10.29",
    val activeProfileRemarks: String = "None",
    val coreInitialized: Boolean = false,
    val tunInterfaceActive: Boolean = false,
    val tunForwarderActive: Boolean = false,
    val localLoopbackPort: Int? = null,
    val verifiedProxyLatencyMs: Long? = null,
    val bridgeArchitectureStatus: String = "Xray-core v26.10.29 (libgojni.so) + HevSocks5Tunnel (libhev-socks5-tunnel.so)",
    val lastError: String? = null
)

data class RedactedLogEntry(
    val id: Long = System.nanoTime(),
    val timestampMs: Long = System.currentTimeMillis(),
    val level: String,
    val tag: String,
    val message: String
)

data class BrandingConfig(
    val appName: String = "MAXNET5G VPN",
    val appId: String = "com.aistudio.maxnet5gvpn.qxkrzp",
    val urlScheme: String = "maxnet5g",
    val primaryColorHex: String = "#00E5FF",
    val accentColorHex: String = "#00E676",
    val defaultLanguage: String = "en-US",
    val defaultTheme: String = "DARK",
    val supportUrl: String = "https://maxnet5g.example.com/support",
    val privacyPolicyUrl: String = "https://maxnet5g.example.com/privacy",
    val termsUrl: String = "https://maxnet5g.example.com/terms",
    val updateManifestUrl: String = "https://maxnet5g.example.com/updates/manifest.json",
    val lockedMode: Boolean = false
)

data class UpdateManifest(
    val latestVersion: String,
    val minVersion: String,
    val apkUrl: String,
    val changelog: String,
    val sha256: String
)

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)
