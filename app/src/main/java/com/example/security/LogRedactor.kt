package com.example.security

/**
 * Redacts sensitive tokens, UUIDs, passwords, private keys, and subscription URLs
 * before any log entry is stored in memory or exported in a diagnostic report.
 */
object LogRedactor {
    private val uuidRegex = Regex(
        pattern = "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    )
    private val uriCredentialRegex = Regex(
        pattern = "(vless|vmess|trojan|ss|hysteria2|hy2|wireguard)://([^@\\s]+)@",
        option = RegexOption.IGNORE_CASE
    )
    private val jsonSecretFieldRegex = Regex(
        pattern = "\"(id|password|privateKey|publicKey|shortId|auth|uuid)\"\\s*:\\s*\"([^\"]+)\"",
        option = RegexOption.IGNORE_CASE
    )
    private val httpsUrlQueryRegex = Regex(
        pattern = "https?://[^\\s\"']+\\?[^\\s\"']+",
        option = RegexOption.IGNORE_CASE
    )
    private val subscriptionPathUrlRegex = Regex(
        pattern = "https?://[^/\\s\"']+(:\\d+)?/(?:[A-Za-z0-9_\\-]+/)?(sub|api|link|v1|v2|s)(?:/[^\\s\"']*)?",
        option = RegexOption.IGNORE_CASE
    )
    private val workersDevUrlRegex = Regex(
        pattern = "https?://[^\\s\"']*workers\\.dev[^\\s\"']*",
        option = RegexOption.IGNORE_CASE
    )
    private val maxnetSubUrlRegex = Regex(
        pattern = "https?://[^\\s\"']*maxnet6g\\.ir[^\\s\"']*",
        option = RegexOption.IGNORE_CASE
    )
    private val acmeTokenRegex = Regex(
        pattern = "\\bacme[0-9a-zA-Z]{10,24}\\b",
        option = RegexOption.IGNORE_CASE
    )
    private val ipv4Regex = Regex(
        pattern = "\\b(?!(?:127\\.0\\.0\\.1|0\\.0\\.0\\.0|10\\.0\\.0\\.\\d+|255\\.255\\.\\d+\\.\\d+)\\b)(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b"
    )
    private val ipv6Regex = Regex(
        pattern = "\\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\\b"
    )
    private val diagnosticHostParamRegex = Regex(
        pattern = "\\b(sni|host|address|server|peer)=([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})\\b",
        option = RegexOption.IGNORE_CASE
    )

    fun redact(raw: String, knownSecrets: Collection<String> = emptyList()): String {
        var sanitized = raw
        sanitized = maxnetSubUrlRegex.replace(sanitized, "[REDACTED-SUBSCRIPTION-URL]")
        sanitized = acmeTokenRegex.replace(sanitized, "[REDACTED-TOKEN]")
        sanitized = workersDevUrlRegex.replace(sanitized, "[REDACTED-SUBSCRIPTION-URL]")
        sanitized = uuidRegex.replace(sanitized, "[REDACTED-UUID]")
        sanitized = uriCredentialRegex.replace(sanitized) { match ->
            "${match.groupValues[1]}://[REDACTED-CREDENTIAL]@"
        }
        sanitized = jsonSecretFieldRegex.replace(sanitized) { match ->
            "\"${match.groupValues[1]}\": \"[REDACTED]\""
        }
        sanitized = httpsUrlQueryRegex.replace(sanitized, "[REDACTED-SUBSCRIPTION-URL]")
        sanitized = subscriptionPathUrlRegex.replace(sanitized, "[REDACTED-SUBSCRIPTION-URL]")
        for (secret in knownSecrets) {
            val trimmed = secret.trim()
            if (trimmed.length >= 4) {
                sanitized = sanitized.replace(trimmed, "[REDACTED]")
            }
        }
        return sanitized
    }

    /**
     * Redacts credentials, tokens, external IPv4/IPv6 literals, and caller-provided sensitive
     * hostnames before diagnostic export or display.
     */
    fun redactDiagnostics(
        raw: String,
        knownSecrets: Collection<String> = emptyList(),
        sensitiveHosts: Collection<String> = emptyList()
    ): String {
        if (raw.isEmpty()) return raw
        var sanitized = redact(raw, knownSecrets)
            .replace(ipv4Regex, "[REDACTED-IP]")
            .replace(ipv6Regex, "[REDACTED-IPV6]")
            .replace(diagnosticHostParamRegex) { match ->
                "${match.groupValues[1]}=[REDACTED-HOST]"
            }
        for (host in sensitiveHosts) {
            val trimmed = host.trim()
            if (trimmed.length >= 4 && !trimmed.equals("localhost", ignoreCase = true)) {
                sanitized = sanitized.replace(trimmed, "[REDACTED-HOST]", ignoreCase = true)
            }
        }
        return sanitized
    }
}
