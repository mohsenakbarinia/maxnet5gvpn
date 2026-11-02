package com.example.core.resilience

import android.net.Network
import com.example.domain.model.SecurityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Assesses reachability of a [ConnectivityPath] using the 5-rung assessment ladder (Section 8.1)
 * and evaluates two-level health of [AuthorizedRelay]s (`RELAY_REACHABLE` vs `RELAY_FORWARDING_OK`,
 * Section 12.2).
 */
class ServerReachabilityAssessor(
    private val vpnCoreDelayTester: (suspend (ConnectivityPath, Long) -> Long?)? = null,
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    private data class CachedDnsEntry(
        val addresses: List<InetAddress>,
        val timestampMs: Long
    )

    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val dnsCacheTtlMs = 60_000L

    fun clearDnsCache() {
        dnsCache.clear()
    }

    /**
     * Evaluates a [ConnectivityPath] up the 5-rung ladder:
     * 1. Rung 1: DNS resolution (with 60s positive cache; skipped if literal IP)
     * 2. Rung 2: TCP connect to effective host:port
     * 3. Rung 3: TLS handshake (when profile uses TLS)
     * 4. Rung 4: Optional VPN core HTTP 204 delay test (if [runCoreDelayTest] is true and [vpnCoreDelayTester] provided)
     * 5. Rung 5: Stability sample ratio (when [stabilitySamples] > 1)
     */
    suspend fun assessPath(
        path: ConnectivityPath,
        config: ConnectivityConfig = ConnectivityConfig(),
        nationalState: NationalInternetState = NationalInternetState.NORMAL_INTERNATIONAL,
        underlayNetwork: Network? = null,
        runCoreDelayTest: Boolean = false,
        stabilitySamples: Int = 1
    ): PathAssessmentResult = withContext(Dispatchers.IO) {
        val host = path.effectiveHost.trim()
        val port = path.effectivePort
        val endpointId = EndpointIdHasher.hashEndpoint(host, port, path.profile.network.name)

        var dnsLatencyMs: Long? = null
        var tcpLatencyMs: Long? = null
        var tlsLatencyMs: Long? = null
        var vpnDelayMs: Long? = null
        var highestRung: AssessmentRung? = null

        try {
            // Rung 1: DNS Check
            val resolvedAddress: InetAddress = if (FailureClassifier.isLiteralIp(host)) {
                highestRung = AssessmentRung.DNS_CHECK
                InetAddress.getByName(host)
            } else {
                val dnsStart = clockMsProvider()
                val addresses = resolveHostWithCache(host, underlayNetwork)
                dnsLatencyMs = (clockMsProvider() - dnsStart).coerceAtLeast(1L)
                if (addresses.isEmpty()) {
                    return@withContext buildFailedResult(
                        path = path,
                        endpointId = endpointId,
                        highestRung = null,
                        failureCategory = FailureCategory.DNS_FAILURE,
                        nowMs = clockMsProvider()
                    )
                }
                val firstIp = addresses.first().hostAddress.orEmpty()
                if (FailureClassifier.isSuspiciousResolvedIpForPublicHost(host, firstIp)) {
                    return@withContext buildFailedResult(
                        path = path,
                        endpointId = endpointId,
                        highestRung = AssessmentRung.DNS_CHECK,
                        dnsLatencyMs = dnsLatencyMs,
                        failureCategory = FailureCategory.DNS_TAMPERING_SUSPECTED,
                        nowMs = clockMsProvider()
                    )
                }
                highestRung = AssessmentRung.DNS_CHECK
                addresses.first()
            }

            // Rung 2 & 3 (& 5 if multiple stability samples requested)
            val totalSamples = stabilitySamples.coerceIn(1, 3)
            var passedSamples = 0
            val sampleTcpRtts = mutableListOf<Long>()
            val sampleTlsRtts = mutableListOf<Long>()
            var lastSampleFailure: FailureCategory? = null

            for (sampleIdx in 0 until totalSamples) {
                try {
                    val tcpStart = clockMsProvider()
                    val rawSocket = underlayNetwork?.socketFactory?.createSocket() ?: Socket()
                    rawSocket.use { sock ->
                        sock.soTimeout = config.tcpTimeoutMs.toInt()
                        sock.connect(InetSocketAddress(resolvedAddress, port), config.tcpTimeoutMs.toInt())
                        val sampleTcp = (clockMsProvider() - tcpStart).coerceAtLeast(1L)
                        sampleTcpRtts.add(sampleTcp)
                        highestRung = AssessmentRung.TCP_CONNECT

                        // Rung 3: TLS probe when profile uses standard TLS (for REALITY, Xray handles REALITY handshake)
                        if (path.profile.security == SecurityType.TLS) {
                            val tlsStart = clockMsProvider()
                            val sni = path.relay?.sniOverride?.takeIf { it.isNotBlank() }
                                ?: path.profile.sni.ifBlank { path.profile.address }
                            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                            val sslSocket = sslFactory.createSocket(sock, sni, port, true) as SSLSocket
                            sslSocket.soTimeout = config.tlsTimeoutMs.toInt()
                            val params: SSLParameters = sslSocket.sslParameters
                            if (!FailureClassifier.isLiteralIp(sni)) {
                                params.serverNames = listOf(SNIHostName(sni))
                            }
                            sslSocket.sslParameters = params
                            sslSocket.startHandshake()
                            val sampleTls = (clockMsProvider() - tlsStart).coerceAtLeast(1L)
                            sampleTlsRtts.add(sampleTls)
                            highestRung = AssessmentRung.TLS_HANDSHAKE
                        }
                        passedSamples++
                    }
                } catch (sampleErr: Throwable) {
                    val layer = if (highestRung == AssessmentRung.TCP_CONNECT) ProbeLayer.TLS else ProbeLayer.IP
                    lastSampleFailure = FailureClassifier.classifyException(sampleErr, layer)
                }
            }

            if (passedSamples == 0) {
                val category = if (path.isViaRelay) {
                    FailureCategory.RELAY_UNAVAILABLE
                } else {
                    lastSampleFailure ?: FailureCategory.SERVER_UNREACHABLE
                }
                val relayReport = path.relay?.let { relay ->
                    RelayHealthReport(
                        relayId = relay.id,
                        endpointId = endpointId,
                        state = RelayHealthState.RELAY_UNAVAILABLE,
                        failureCategory = category,
                        timestampMs = clockMsProvider()
                    )
                }
                return@withContext PathAssessmentResult(
                    path = path,
                    endpointId = endpointId,
                    reachable = false,
                    highestRungReached = highestRung,
                    dnsLatencyMs = dnsLatencyMs,
                    failureCategory = category,
                    relayHealth = relayReport,
                    timestampMs = clockMsProvider()
                )
            }

            tcpLatencyMs = sampleTcpRtts.sorted()[sampleTcpRtts.size / 2]
            tlsLatencyMs = if (sampleTlsRtts.isNotEmpty()) {
                sampleTlsRtts.sorted()[sampleTlsRtts.size / 2]
            } else {
                null
            }
            val stabilityRatio = passedSamples.toFloat() / totalSamples.toFloat()
            if (totalSamples > 1 && passedSamples >= 2) {
                highestRung = AssessmentRung.STABILITY_CHECK
            }

            // Rung 4: Optional VPN core HTTP 204 delay test
            if (runCoreDelayTest && vpnCoreDelayTester != null) {
                val delayResult = runCatching {
                    vpnCoreDelayTester.invoke(path, config.httpTimeoutMs)
                }.getOrNull()
                if (delayResult != null && delayResult > 0L) {
                    vpnDelayMs = delayResult
                    highestRung = AssessmentRung.VPN_CORE_DELAY
                } else if (path.isViaRelay) {
                    // Relay TCP/TLS succeeded, but end-to-end forwarding to gateway failed!
                    // Rule 12.2: Distinguish RELAY_REACHABLE from RELAY_FORWARDING_OK
                    val relayReport = RelayHealthReport(
                        relayId = path.relay!!.id,
                        endpointId = endpointId,
                        state = RelayHealthState.RELAY_REACHABLE,
                        tcpTlsLatencyMs = (tcpLatencyMs ?: 0L) + (tlsLatencyMs ?: 0L),
                        forwardingLatencyMs = null,
                        failureCategory = FailureCategory.VPN_HANDSHAKE_FAILURE,
                        timestampMs = clockMsProvider()
                    )
                    return@withContext PathAssessmentResult(
                        path = path,
                        endpointId = endpointId,
                        reachable = false,
                        highestRungReached = AssessmentRung.TLS_HANDSHAKE,
                        dnsLatencyMs = dnsLatencyMs,
                        tcpLatencyMs = tcpLatencyMs,
                        tlsLatencyMs = tlsLatencyMs,
                        stabilityPassRatio = stabilityRatio,
                        failureCategory = FailureCategory.VPN_HANDSHAKE_FAILURE,
                        relayHealth = relayReport,
                        timestampMs = clockMsProvider()
                    )
                }
            }

            val relayReport = path.relay?.let { relay ->
                val relayState = if (vpnDelayMs != null && vpnDelayMs > 0L) {
                    RelayHealthState.RELAY_FORWARDING_OK
                } else {
                    RelayHealthState.RELAY_REACHABLE
                }
                RelayHealthReport(
                    relayId = relay.id,
                    endpointId = endpointId,
                    state = relayState,
                    tcpTlsLatencyMs = (tcpLatencyMs ?: 0L) + (tlsLatencyMs ?: 0L),
                    forwardingLatencyMs = vpnDelayMs,
                    failureCategory = null,
                    timestampMs = clockMsProvider()
                )
            }

            val effectiveRtt = vpnDelayMs ?: ((tcpLatencyMs ?: 150L) + (tlsLatencyMs ?: 0L))
            val latencyScore = (100.0 - (effectiveRtt.toDouble() / 15.0)).coerceIn(5.0, 100.0)
            val stabilityScore = stabilityRatio * 25.0
            val transportBonus = PathRegistry.transportResilienceBonus(path, nationalState)
            val compositeScore = (latencyScore + stabilityScore + transportBonus).coerceAtLeast(1.0)

            PathAssessmentResult(
                path = path,
                endpointId = endpointId,
                reachable = true,
                highestRungReached = highestRung,
                dnsLatencyMs = dnsLatencyMs,
                tcpLatencyMs = tcpLatencyMs,
                tlsLatencyMs = tlsLatencyMs,
                vpnDelayMs = vpnDelayMs,
                stabilityPassRatio = stabilityRatio,
                compositeScore = compositeScore,
                failureCategory = null,
                relayHealth = relayReport,
                timestampMs = clockMsProvider()
            )
        } catch (t: Throwable) {
            val category = FailureClassifier.classifyException(t, ProbeLayer.IP)
            buildFailedResult(
                path = path,
                endpointId = endpointId,
                highestRung = highestRung,
                dnsLatencyMs = dnsLatencyMs,
                tcpLatencyMs = tcpLatencyMs,
                tlsLatencyMs = tlsLatencyMs,
                failureCategory = if (path.isViaRelay) FailureCategory.RELAY_UNAVAILABLE else category,
                nowMs = clockMsProvider()
            )
        }
    }

    private fun resolveHostWithCache(host: String, underlayNetwork: Network?): List<InetAddress> {
        val now = clockMsProvider()
        val key = "${host.lowercase()}_${underlayNetwork?.networkHandle ?: 0L}"
        val cached = dnsCache[key]
        if (cached != null && (now - cached.timestampMs) < dnsCacheTtlMs) {
            return cached.addresses
        }
        val resolved = if (underlayNetwork != null) {
            underlayNetwork.getAllByName(host).toList()
        } else {
            InetAddress.getAllByName(host).toList()
        }
        if (resolved.isNotEmpty()) {
            dnsCache[key] = CachedDnsEntry(resolved, now)
        }
        return resolved
    }

    private fun buildFailedResult(
        path: ConnectivityPath,
        endpointId: String,
        highestRung: AssessmentRung?,
        dnsLatencyMs: Long? = null,
        tcpLatencyMs: Long? = null,
        tlsLatencyMs: Long? = null,
        failureCategory: FailureCategory,
        nowMs: Long
    ): PathAssessmentResult {
        val relayReport = path.relay?.let { relay ->
            RelayHealthReport(
                relayId = relay.id,
                endpointId = endpointId,
                state = RelayHealthState.RELAY_UNAVAILABLE,
                failureCategory = failureCategory,
                timestampMs = nowMs
            )
        }
        return PathAssessmentResult(
            path = path,
            endpointId = endpointId,
            reachable = false,
            highestRungReached = highestRung,
            dnsLatencyMs = dnsLatencyMs,
            tcpLatencyMs = tcpLatencyMs,
            tlsLatencyMs = tlsLatencyMs,
            stabilityPassRatio = 0f,
            compositeScore = 0.0,
            failureCategory = failureCategory,
            relayHealth = relayReport,
            timestampMs = nowMs
        )
    }
}
