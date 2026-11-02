package com.example.core.resilience

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.math.abs

/**
 * Pure, side-effect-free classifier that maps exceptions, HTTP status codes, resolved IPs,
 * clock skew, and Xray-core diagnostic messages to a canonical [FailureCategory].
 */
object FailureClassifier {

    /**
     * Inspects resolved IP literals for a public hostname and detects potential DNS poisoning /
     * tampering (loopback, unspecified, RFC1918 private, CGNAT 100.64.0.0/10, link-local, or
     * documentation/reserved ranges such as 10.10.34.34-style sinkholes).
     */
    fun isSuspiciousResolvedIpForPublicHost(host: String, resolvedIp: String): Boolean {
        val cleanHost = host.trim().lowercase()
        if (cleanHost == "localhost" || isLiteralIp(cleanHost)) return false

        val addr = runCatching { InetAddress.getByName(resolvedIp) }.getOrNull() ?: return true
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }

        if (addr is Inet4Address) {
            val b = addr.address
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            val b2 = b[2].toInt() and 0xFF
            // 0.0.0.0/8
            if (b0 == 0) return true
            // 10.0.0.0/8 (includes well-known sinkholes like 10.10.34.34 / 10.10.34.35 / 10.10.34.36)
            if (b0 == 10) return true
            // 100.64.0.0/10 (Carrier-Grade NAT used as internal sinkhole for public domains)
            if (b0 == 100 && (b1 in 64..127)) return true
            // 127.0.0.0/8
            if (b0 == 127) return true
            // 169.254.0.0/16
            if (b0 == 169 && b1 == 254) return true
            // 172.16.0.0/12
            if (b0 == 172 && (b1 in 16..31)) return true
            // 192.168.0.0/16
            if (b0 == 192 && b1 == 168) return true
            // 198.18.0.0/15 (benchmarking / often used for fake-IP or ISP interception)
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
            // 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 (TEST-NET)
            if (b0 == 192 && b1 == 0 && b2 == 2) return true
            if (b0 == 198 && b1 == 51 && b2 == 100) return true
            if (b0 == 203 && b1 == 0 && b2 == 113) return true
        } else if (addr is Inet6Address) {
            val b = addr.address
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            // Unique local fc00::/7
            if ((b0 and 0xFE) == 0xFC) return true
            // Documentation 2001:db8::/32
            if (b0 == 0x20 && b1 == 0x01 && (b[2].toInt() and 0xFF) == 0x0D && (b[3].toInt() and 0xFF) == 0xB8) {
                return true
            }
        }
        return false
    }

    fun isLiteralIp(host: String): Boolean {
        val trimmed = host.trim().removePrefix("[").removeSuffix("]")
        val ipv4Pattern = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
        if (ipv4Pattern.matches(trimmed)) return true
        return trimmed.contains(':')
    }

    /**
     * Classifies an exception thrown during DNS, TCP, TLS, HTTP, or Xray operations.
     */
    fun classifyException(
        throwable: Throwable?,
        layer: ProbeLayer? = null,
        clockSkewSeconds: Long? = null,
        skewThresholdSeconds: Long = 90L
    ): FailureCategory {
        if (clockSkewSeconds != null && abs(clockSkewSeconds) > skewThresholdSeconds) {
            return FailureCategory.CLOCK_SKEW_SUSPECTED
        }
        if (throwable == null) return FailureCategory.UNKNOWN_FAILURE

        val root = unwrapRootCause(throwable)
        val message = buildString {
            append(throwable.message.orEmpty())
            append(' ')
            append(root.message.orEmpty())
        }.lowercase()

        // 1. OS / Permission / VPN lockdown block
        if (throwable is SecurityException ||
            root is SecurityException ||
            message.contains("eacces") ||
            message.contains("eperm") ||
            message.contains("operation not permitted") ||
            message.contains("permission denied") ||
            message.contains("network is restricted by os")
        ) {
            return FailureCategory.OS_NETWORK_BLOCKED
        }

        // 2. DNS failures
        if (throwable is UnknownHostException || root is UnknownHostException) {
            return if (message.contains("tamper") || message.contains("sinkhole") || message.contains("poison")) {
                FailureCategory.DNS_TAMPERING_SUSPECTED
            } else {
                FailureCategory.DNS_FAILURE
            }
        }

        // 3. TLS / Certificate failures
        if (throwable is SSLPeerUnverifiedException ||
            root is SSLPeerUnverifiedException ||
            throwable is CertificateException ||
            root is CertificateException ||
            throwable is CertPathValidatorException ||
            root is CertPathValidatorException ||
            hasCauseOf<CertificateException>(throwable) ||
            hasCauseOf<CertPathValidatorException>(throwable) ||
            message.contains("certificate") ||
            message.contains("certpath") ||
            message.contains("hostname") && message.contains("not verified") ||
            message.contains("pin verification")
        ) {
            if (root is CertificateExpiredException ||
                root is CertificateNotYetValidException ||
                message.contains("expired") ||
                message.contains("not yet valid")
            ) {
                if (clockSkewSeconds != null && abs(clockSkewSeconds) > skewThresholdSeconds) {
                    return FailureCategory.CLOCK_SKEW_SUSPECTED
                }
            }
            return FailureCategory.TLS_CERTIFICATE_INVALID
        }

        if (throwable is SSLHandshakeException ||
            root is SSLHandshakeException ||
            throwable is SSLException ||
            root is SSLException ||
            layer == ProbeLayer.TLS && (throwable is EOFException || root is EOFException)
        ) {
            return FailureCategory.TLS_HANDSHAKE_FAILURE
        }

        // 4. TCP Reset / Refused / Unreachable / Timeout
        if (message.contains("econnreset") ||
            message.contains("connection reset") ||
            message.contains("broken pipe")
        ) {
            return FailureCategory.TCP_RESET
        }

        if (message.contains("econnrefused") || message.contains("connection refused")) {
            return FailureCategory.TCP_REFUSED
        }

        if (throwable is NoRouteToHostException ||
            root is NoRouteToHostException ||
            message.contains("enetunreach") ||
            message.contains("ehostunreach") ||
            message.contains("network is unreachable") ||
            message.contains("no route to host")
        ) {
            return FailureCategory.SERVER_UNREACHABLE
        }

        if (throwable is SocketTimeoutException ||
            root is SocketTimeoutException ||
            message.contains("etimedout") ||
            message.contains("timed out") ||
            message.contains("timeout")
        ) {
            return when (layer) {
                ProbeLayer.DNS -> FailureCategory.DNS_FAILURE
                ProbeLayer.TLS -> FailureCategory.TLS_HANDSHAKE_FAILURE
                else -> FailureCategory.TCP_TIMEOUT
            }
        }

        if (throwable is ConnectException || root is ConnectException) {
            return FailureCategory.SERVER_UNREACHABLE
        }

        if (throwable is SocketException || root is SocketException || throwable is IOException) {
            if (layer == ProbeLayer.TLS) return FailureCategory.TLS_HANDSHAKE_FAILURE
            return FailureCategory.TRANSPORT_UNAVAILABLE
        }

        return FailureCategory.UNKNOWN_FAILURE
    }

    /**
     * Classifies an unexpected HTTP response from a probe endpoint.
     */
    fun classifyHttpResponse(
        statusCode: Int,
        expectedStatuses: Set<Int>,
        locationHeader: String? = null,
        bodyPreview: String? = null,
        expectedBodyPrefix: String? = null
    ): FailureCategory? {
        if (statusCode in expectedStatuses) {
            if (expectedBodyPrefix != null && bodyPreview != null && !bodyPreview.startsWith(expectedBodyPrefix)) {
                return FailureCategory.HTTP_BLOCKPAGE_SUSPECTED
            }
            return null
        }

        if (statusCode in 300..399) {
            val loc = locationHeader.orEmpty().lowercase()
            if (loc.contains("login") ||
                loc.contains("portal") ||
                loc.contains("hotspot") ||
                loc.contains("auth") ||
                loc.contains("captive")
            ) {
                return FailureCategory.CAPTIVE_PORTAL_SUSPECTED
            }
            // Redirect on a generate_204 endpoint is a classic captive portal or ISP blockpage signal
            if (204 in expectedStatuses) {
                return FailureCategory.CAPTIVE_PORTAL_SUSPECTED
            }
            return FailureCategory.HTTP_BLOCKPAGE_SUSPECTED
        }

        if (statusCode == 401 || statusCode == 407 || statusCode == 511) {
            return FailureCategory.CAPTIVE_PORTAL_SUSPECTED
        }

        if (statusCode == 403 || statusCode == 451) {
            return FailureCategory.HTTP_BLOCKPAGE_SUSPECTED
        }

        return FailureCategory.TRANSPORT_UNAVAILABLE
    }

    /**
     * Classifies an Xray-core startup/handshake/runtime error message.
     */
    fun classifyVpnCoreError(rawMessage: String?): FailureCategory {
        if (rawMessage.isNullOrBlank()) return FailureCategory.VPN_HANDSHAKE_FAILURE
        val msg = rawMessage.lowercase()
        return when {
            msg.contains("invalid user") ||
                msg.contains("authentication") ||
                msg.contains("bad password") ||
                msg.contains("unauthorized") ||
                msg.contains("uuid") && msg.contains("invalid") -> FailureCategory.AUTHENTICATION_FAILURE

            msg.contains("config") && (msg.contains("invalid") || msg.contains("parse") || msg.contains("error")) ||
                msg.contains("unsupported protocol") ||
                msg.contains("missing port") -> FailureCategory.CONFIGURATION_ERROR

            msg.contains("certificate") || msg.contains("x509") -> FailureCategory.TLS_CERTIFICATE_INVALID
            msg.contains("tls") || msg.contains("handshake") || msg.contains("reality") -> FailureCategory.TLS_HANDSHAKE_FAILURE
            msg.contains("reset by peer") || msg.contains("econnreset") -> FailureCategory.TCP_RESET
            msg.contains("connection refused") || msg.contains("econnrefused") -> FailureCategory.TCP_REFUSED
            msg.contains("no such host") || msg.contains("dns") -> FailureCategory.DNS_FAILURE
            msg.contains("timeout") || msg.contains("deadline exceeded") || msg.contains("i/o timeout") -> FailureCategory.TCP_TIMEOUT
            msg.contains("unreachable") -> FailureCategory.SERVER_UNREACHABLE
            else -> FailureCategory.VPN_HANDSHAKE_FAILURE
        }
    }

    /**
     * Determines whether a failure category is a non-recoverable user/server configuration or
     * authentication defect that must halt retries immediately (Rule 11.3 / AC-8).
     */
    fun isTerminalConfigurationOrAuthFailure(category: FailureCategory): Boolean =
        category == FailureCategory.AUTHENTICATION_FAILURE ||
            category == FailureCategory.CONFIGURATION_ERROR

    private fun unwrapRootCause(throwable: Throwable): Throwable {
        var current = throwable
        var depth = 0
        while (current.cause != null && current.cause !== current && depth < 12) {
            current = current.cause!!
            depth++
        }
        return current
    }

    private inline fun <reified T : Throwable> hasCauseOf(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 12) {
            if (current is T) return true
            current = current.cause
            depth++
        }
        return false
    }
}
