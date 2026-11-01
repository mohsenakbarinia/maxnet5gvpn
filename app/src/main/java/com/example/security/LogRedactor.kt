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
}
