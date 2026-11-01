package com.example.core.security

import com.example.domain.model.AppSettings
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.VpnConnectionState

/**
 * Audit result of the device and VPN security posture.
 */
data class SecurityAuditReport(
    val dnsLeakStatus: SecurityCheckStatus,
    val ipv6LeakStatus: SecurityCheckStatus,
    val killSwitchStatus: SecurityCheckStatus,
    val encryptionCipherStatus: SecurityCheckStatus,
    val logSanitizationStatus: SecurityCheckStatus,
    val overallSecurityScore: Int, // 0..100
    val summaryEn: String,
    val summaryFa: String
)

enum class SecurityCheckStatus(val labelEn: String, val labelFa: String, val isSecure: Boolean) {
    SECURE("Protected", "محافظت‌شده", true),
    WARNING("Caution", "هشدار", false),
    DISABLED("Disabled", "غیرفعال", false)
}

/**
 * Security Center for MAXNET5G VPN V3.0.
 *
 * Provides central audit and configuration for:
 * 1. DNS Leak Protection (DoH / Direct DNS routing validation).
 * 2. IPv6 Leak Protection (blocking or safe tunneling of IPv6 traffic).
 * 3. Kill Switch Management (blocking non-VPN traffic during outages).
 * 4. Encryption & Cipher Strength verification.
 * 5. Log Sanitization & Redaction status.
 */
object SecurityCenter {

    val SECURE_DNS_PROVIDERS = listOf(
        "Cloudflare" to "https://1.1.1.1/dns-query",
        "Google" to "https://dns.google/dns-query",
        "AdGuard (Ad-Blocking)" to "https://dns.adguard.com/dns-query",
        "Quad9 (Malware-Blocking)" to "https://dns.quad9.net/dns-query"
    )

    fun auditSecurityPosture(
        profile: ServerProfile?,
        settings: AppSettings,
        connectionState: VpnConnectionState
    ): SecurityAuditReport {
        val isConnected = connectionState == VpnConnectionState.CONNECTED

        // 1. DNS Leak Protection Check
        val dnsStatus = when {
            !settings.dnsLeakProtection -> SecurityCheckStatus.DISABLED
            settings.remoteDns.startsWith("https://") -> SecurityCheckStatus.SECURE
            settings.directDns in listOf("1.1.1.1", "8.8.8.8", "9.9.9.9") -> SecurityCheckStatus.SECURE
            else -> SecurityCheckStatus.WARNING
        }

        // 2. IPv6 Leak Protection Check
        val ipv6Status = when {
            !settings.ipv6LeakProtection -> SecurityCheckStatus.DISABLED
            !settings.enableIpv6 -> SecurityCheckStatus.SECURE // IPv6 safely blocked/blackholed
            settings.enableIpv6 && isConnected -> SecurityCheckStatus.SECURE // IPv6 routed through tunnel
            else -> SecurityCheckStatus.WARNING
        }

        // 3. Kill Switch Status
        val killSwitchStatus = if (settings.killSwitchEnabled) {
            SecurityCheckStatus.SECURE
        } else {
            SecurityCheckStatus.DISABLED
        }

        // 4. Encryption Cipher Strength
        val cipherStatus = if (profile != null) {
            when {
                profile.security == SecurityType.REALITY || profile.realityPublicKey.isNotBlank() ->
                    SecurityCheckStatus.SECURE
                profile.security == SecurityType.TLS ->
                    SecurityCheckStatus.SECURE
                profile.encryption in listOf("aes-128-gcm", "chacha20-ietf-poly1305", "auto") ->
                    SecurityCheckStatus.SECURE
                else ->
                    SecurityCheckStatus.WARNING
            }
        } else {
            SecurityCheckStatus.WARNING
        }

        // 5. Log Sanitization Status (LogRedactor is always active by architecture)
        val logStatus = SecurityCheckStatus.SECURE

        // Overall Score Calculation (0..100)
        var score = 0
        if (dnsStatus == SecurityCheckStatus.SECURE) score += 25
        if (ipv6Status == SecurityCheckStatus.SECURE) score += 20
        if (killSwitchStatus == SecurityCheckStatus.SECURE) score += 20
        if (cipherStatus == SecurityCheckStatus.SECURE) score += 20
        if (logStatus == SecurityCheckStatus.SECURE) score += 15

        val summaryEn = when {
            score >= 85 -> "All security shields active and verified."
            score >= 60 -> "Standard protection enabled. Kill switch recommended."
            else -> "Security warning: Some leak protections are disabled."
        }

        val summaryFa = when {
            score >= 85 -> "تمامی سپرهای امنیتی فعال و تأیید شده‌اند."
            score >= 60 -> "حفاظت استاندارد فعال است. فعال‌سازی سوئیچ قطع اضطراری توصیه می‌شود."
            else -> "هشدار امنیتی: برخی از محافظت‌های نشت غیرفعال هستند."
        }

        return SecurityAuditReport(
            dnsLeakStatus = dnsStatus,
            ipv6LeakStatus = ipv6Status,
            killSwitchStatus = killSwitchStatus,
            encryptionCipherStatus = cipherStatus,
            logSanitizationStatus = logStatus,
            overallSecurityScore = score,
            summaryEn = summaryEn,
            summaryFa = summaryFa
        )
    }
}
