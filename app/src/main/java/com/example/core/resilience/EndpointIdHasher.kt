package com.example.core.resilience

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Generates privacy-preserving truncated HMAC-SHA-256 identifiers (`endpointId`) for hosts, URLs,
 * and servers so that failure logs and diagnostics never store raw user server hostnames or IPs.
 */
object EndpointIdHasher {

    @Volatile
    private var secretKeyBytes: ByteArray = generateEphemeralKey()

    private fun generateEphemeralKey(): ByteArray {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * Optional initialization with a persisted per-install key so endpoint IDs stay consistent
     * across app restarts on the same device without ever exposing raw hosts.
     */
    fun initializeKey(persistedSecretHex: String?) {
        if (!persistedSecretHex.isNullOrBlank() && persistedSecretHex.length >= 32) {
            val decoded = runCatching {
                persistedSecretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }.getOrNull()
            if (decoded != null && decoded.size >= 16) {
                secretKeyBytes = decoded
            }
        }
    }

    fun exportKeyHex(): String =
        secretKeyBytes.joinToString("") { "%02x".format(it) }

    /**
     * Returns a 12-hex-character (48-bit) truncated HMAC-SHA-256 identifier prefixed with `ep_`.
     */
    fun hashEndpoint(hostOrUrl: String, port: Int? = null, transport: String? = null): String {
        val canonical = buildString {
            append(hostOrUrl.trim().lowercase())
            if (port != null) append(':').append(port)
            if (!transport.isNullOrBlank()) append('/').append(transport.trim().lowercase())
        }
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secretKeyBytes, "HmacSHA256"))
            val digest = mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
            val hex = digest.take(6).joinToString("") { "%02x".format(it) }
            "ep_$hex"
        } catch (_: Exception) {
            "ep_${canonical.hashCode().toUInt().toString(16)}"
        }
    }

    /**
     * Maps a numeric port to a safe coarse bucket for diagnostics (`443`, `80`, `8443`, `2053`, `other`).
     */
    fun portBucket(port: Int): String = when (port) {
        443 -> "443"
        80 -> "80"
        8443, 2053, 2083, 2087, 2096 -> "alt-tls"
        in 1..1023 -> "well-known"
        in 1024..65535 -> "high-port"
        else -> "unknown"
    }
}
