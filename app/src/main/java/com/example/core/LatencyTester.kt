package com.example.core

import android.content.Context
import android.os.SystemClock
import com.example.core.xray.XrayConfigBuilder
import com.example.core.xray.XrayNativeBridge
import com.example.domain.model.AppSettings
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.ServerConnectionStatus
import com.example.domain.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class LatencyTestOutcome(
    val profileId: Long,
    val latencyMs: Long?,
    val measureType: LatencyMeasureType,
    val errorReason: String? = null,
    val tcpConnectMs: Long? = null,
    val connectionStatus: ServerConnectionStatus = when {
        latencyMs != null && latencyMs > 0L && measureType == LatencyMeasureType.PROXY_HTTP_DELAY -> ServerConnectionStatus.ALIVE
        measureType == LatencyMeasureType.TIMEOUT -> ServerConnectionStatus.FAILED
        latencyMs != null && latencyMs > 0L -> ServerConnectionStatus.ALIVE
        else -> ServerConnectionStatus.UNTESTED
    },
    val sampleLatenciesMs: List<Long> = emptyList(),
    val jitterMs: Long? = null,
    val packetLossRate: Float? = null,
    val successRate: Float? = null,
    val testedAtMs: Long = System.currentTimeMillis()
)

/**
 * Real Server Latency & Delay Tester.
 * - Never fabricates or artificially adjusts ping values.
 * - Strictly separates diagnostic metrics:
 *   1. **TCP Connect** (`tcpConnectMs`, [LatencyMeasureType.TCP_HANDSHAKE]): raw TCP socket connect time to `server:port`.
 *   2. **Real VPN Latency** (`latencyMs`, [LatencyMeasureType.PROXY_HTTP_DELAY]): complete HTTP/HTTPS 204 request-response
 *      duration through an isolated temporary or active Xray-core instance (1 warm-up + up to 3 samples, median).
 *   3. **Connection Status** ([ServerConnectionStatus]): whether a usable proxy connection was established.
 * - Never substitutes a TCP handshake result when a proxy HTTP 204 test fails.
 * - Uses bounded concurrency (4 concurrent Xray instances max for real proxy tests) with full cancellation support.
 */
object LatencyTester {
    const val MAX_CONCURRENT_XRAY_WORKERS = 4
    private const val MAX_CONCURRENT_TCP_WORKERS = 10
    private const val DEFAULT_TCP_TIMEOUT_MS = 3500
    const val DEFAULT_REAL_PROXY_TIMEOUT_MS = 9000
    const val DEFAULT_HTTP_204_URL = "https://www.gstatic.com/generate_204"
    const val FALLBACK_HTTP_204_URL = "https://cp.cloudflare.com/generate_204"

    private inline fun measureElapsedRealtimeMs(block: () -> Unit): Long {
        val startElapsed = try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            0L
        }
        val startNano = System.nanoTime()
        block()
        val endElapsed = try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            0L
        }
        val endNano = System.nanoTime()
        val elapsedDelta = endElapsed - startElapsed
        val nanoDelta = (endNano - startNano) / 1_000_000L
        return if (elapsedDelta > 0L) elapsedDelta else nanoDelta.coerceAtLeast(1L)
    }

    /**
     * Measures raw diagnostic TCP socket connection time (`connect()` to `profile.address:profile.port`)
     * using [SystemClock.elapsedRealtime]. Returns `null` on failure.
     */
    suspend fun measureDiagnosticTcpConnectMs(
        profile: ServerProfile,
        timeoutMs: Int = DEFAULT_TCP_TIMEOUT_MS
    ): Long? = measureDiagnosticTcpConnectMs(profile.address, profile.port, timeoutMs)

    suspend fun measureDiagnosticTcpConnectMs(
        address: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TCP_TIMEOUT_MS
    ): Long? = withContext(Dispatchers.IO) {
        try {
            val elapsedMs = measureElapsedRealtimeMs {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(address, port), timeoutMs)
                }
            }
            elapsedMs.coerceAtLeast(1L)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Measures raw TCP handshake latency (`connect()` to `profile.address:profile.port`).
     * Clearly labeled as [LatencyMeasureType.TCP_HANDSHAKE] and sets `tcpConnectMs`.
     */
    suspend fun testSingleTcpLatency(
        profile: ServerProfile,
        timeoutMs: Int = DEFAULT_TCP_TIMEOUT_MS
    ): LatencyTestOutcome = withContext(Dispatchers.IO) {
        return@withContext try {
            val elapsedMs = measureElapsedRealtimeMs {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(profile.address, profile.port), timeoutMs)
                }
            }.coerceAtLeast(1L)
            LatencyTestOutcome(
                profileId = profile.id,
                latencyMs = elapsedMs,
                measureType = LatencyMeasureType.TCP_HANDSHAKE,
                tcpConnectMs = elapsedMs,
                connectionStatus = ServerConnectionStatus.UNTESTED,
                testedAtMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            LatencyTestOutcome(
                profileId = profile.id,
                latencyMs = null,
                measureType = LatencyMeasureType.TIMEOUT,
                tcpConnectMs = null,
                connectionStatus = ServerConnectionStatus.FAILED,
                errorReason = e.message ?: "TCP connection timeout",
                testedAtMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * Measures real end-to-end HTTP 204 delay through the currently running local SOCKS5 proxy
     * (1 warm-up + up to 3 samples, median result).
     * Does NOT fall back to TCP handshake on failure — reports [LatencyMeasureType.TIMEOUT] with the real error.
     */
    suspend fun testProxyHttp204Delay(
        profile: ServerProfile,
        localSocksPort: Int,
        testUrl: String = DEFAULT_HTTP_204_URL,
        timeoutMs: Int = DEFAULT_REAL_PROXY_TIMEOUT_MS,
        performWarmup: Boolean = true,
        sampleCount: Int = 3
    ): LatencyTestOutcome = withContext(Dispatchers.IO) {
        val tcpDiagnostic = measureDiagnosticTcpConnectMs(profile, minOf(timeoutMs / 3, DEFAULT_TCP_TIMEOUT_MS))
        val delayResult = XrayNativeBridge.measureActiveProxyDelay(
            localSocksPort = localSocksPort,
            testUrl = testUrl,
            timeoutMs = timeoutMs,
            performWarmup = performWarmup,
            sampleCount = sampleCount
        )
        delayResult.fold(
            onSuccess = { delayMs ->
                LatencyTestOutcome(
                    profileId = profile.id,
                    latencyMs = delayMs,
                    measureType = LatencyMeasureType.PROXY_HTTP_DELAY,
                    tcpConnectMs = tcpDiagnostic,
                    connectionStatus = ServerConnectionStatus.ALIVE,
                    sampleLatenciesMs = listOf(delayMs),
                    testedAtMs = System.currentTimeMillis()
                )
            },
            onFailure = { err ->
                LatencyTestOutcome(
                    profileId = profile.id,
                    latencyMs = null,
                    measureType = LatencyMeasureType.TIMEOUT,
                    tcpConnectMs = tcpDiagnostic,
                    connectionStatus = ServerConnectionStatus.FAILED,
                    errorReason = err.message ?: "Proxy HTTP 204 test failed",
                    testedAtMs = System.currentTimeMillis()
                )
            }
        )
    }

    /**
     * Performs REAL proxy latency testing for a server configuration without disrupting any active VPN tunnel:
     * 1. Measures diagnostic server-port TCP connection time (`tcpConnectMs`) separately.
     * 2. Starts a temporary isolated Xray-core instance using the server's actual configuration on an ephemeral local SOCKS5 port.
     * 3. Excludes Xray startup time from network latency.
     * 4. Performs 1 optional warm-up request + up to 3 HTTP/HTTPS 204 requests through that proxy to
     *    `https://www.gstatic.com/generate_204` (or configurable fallback `https://cp.cloudflare.com/generate_204`).
     * 5. Records the **median** successful HTTP 204 round-trip duration as `latencyMs` ([LatencyMeasureType.PROXY_HTTP_DELAY]).
     * 6. Never substitutes `tcpConnectMs` if the proxy HTTP 204 request fails.
     */
    suspend fun testRealVpnLatency(
        context: Context?,
        profile: ServerProfile,
        settings: AppSettings = AppSettings(),
        primaryUrl: String = settings.connectivityCheckUrl.ifBlank { DEFAULT_HTTP_204_URL },
        fallbackUrl: String = settings.connectivityFallbackUrl.ifBlank { FALLBACK_HTTP_204_URL },
        timeoutMs: Int = DEFAULT_REAL_PROXY_TIMEOUT_MS,
        performWarmup: Boolean = true,
        sampleCount: Int = 3
    ): LatencyTestOutcome = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()

        // 1. Measure diagnostic TCP connect time to server:port
        val tcpMs = measureDiagnosticTcpConnectMs(
            profile = profile,
            timeoutMs = minOf(DEFAULT_TCP_TIMEOUT_MS, (timeoutMs / 3).coerceAtLeast(1500))
        )

        // 2. Build isolated temporary Xray SOCKS5 configuration on an ephemeral loopback port
        val ephemeralPort = XrayNativeBridge.allocateEphemeralLoopbackPort(
            excludePorts = setOf(10808, 10809, 20808, 20809)
        )
        val tempConfigValidation = XrayConfigBuilder.buildTemporaryProxyTestConfig(
            profile = profile,
            settings = settings,
            localSocksPort = ephemeralPort
        )
        if (!tempConfigValidation.isValid) {
            return@withContext LatencyTestOutcome(
                profileId = profile.id,
                latencyMs = null,
                measureType = LatencyMeasureType.TIMEOUT,
                tcpConnectMs = tcpMs,
                connectionStatus = ServerConnectionStatus.FAILED,
                errorReason = tempConfigValidation.errors.firstOrNull() ?: "Invalid Xray configuration",
                testedAtMs = nowMs
            )
        }

        val speedtestConfigValidation = XrayConfigBuilder.buildSpeedtestConfig(profile, settings)
        val speedtestJson = if (speedtestConfigValidation.isValid) {
            speedtestConfigValidation.generatedConfigJson
        } else {
            tempConfigValidation.generatedConfigJson
        }

        // 3. Execute real proxy HTTP 204 test through temporary Xray-core instance
        val proxyResult = XrayNativeBridge.measureRealVpnProxyLatency(
            context = context,
            temporarySocksConfigJson = tempConfigValidation.generatedConfigJson,
            localSocksPort = ephemeralPort,
            speedtestConfigJson = speedtestJson,
            primaryUrl = primaryUrl,
            fallbackUrl = fallbackUrl,
            overallTimeoutMs = timeoutMs.coerceIn(3000, 15000),
            performWarmup = performWarmup,
            sampleCount = sampleCount
        )

        proxyResult.fold(
            onSuccess = { measurement ->
                LatencyTestOutcome(
                    profileId = profile.id,
                    latencyMs = measurement.medianLatencyMs,
                    measureType = LatencyMeasureType.PROXY_HTTP_DELAY,
                    tcpConnectMs = tcpMs,
                    connectionStatus = ServerConnectionStatus.ALIVE,
                    sampleLatenciesMs = measurement.sampleLatenciesMs,
                    jitterMs = measurement.jitterMs,
                    packetLossRate = measurement.packetLossRate,
                    successRate = measurement.successRate,
                    testedAtMs = System.currentTimeMillis()
                )
            },
            onFailure = { err ->
                LatencyTestOutcome(
                    profileId = profile.id,
                    latencyMs = null,
                    measureType = LatencyMeasureType.TIMEOUT,
                    tcpConnectMs = tcpMs,
                    connectionStatus = ServerConnectionStatus.FAILED,
                    errorReason = err.message ?: "Real Xray proxy HTTP 204 test failed",
                    jitterMs = null,
                    packetLossRate = 1.0f,
                    successRate = 0.0f,
                    testedAtMs = System.currentTimeMillis()
                )
            }
        )
    }

    /**
     * Backwards-compatible alias that delegates to [testRealVpnLatency] so all callers get
     * multi-sample median HTTP 204 latency through an isolated temporary Xray-core instance.
     */
    suspend fun testStandaloneProxyDelay(
        context: Context?,
        profile: ServerProfile,
        settings: AppSettings = AppSettings(),
        testUrl: String = DEFAULT_HTTP_204_URL,
        timeoutMs: Int = DEFAULT_REAL_PROXY_TIMEOUT_MS,
        performWarmup: Boolean = true,
        sampleCount: Int = 3
    ): LatencyTestOutcome = testRealVpnLatency(
        context = context,
        profile = profile,
        settings = settings,
        primaryUrl = testUrl,
        fallbackUrl = FALLBACK_HTTP_204_URL,
        timeoutMs = timeoutMs,
        performWarmup = performWarmup,
        sampleCount = sampleCount
    )

    /**
     * Tests multiple server configurations with bounded concurrency ([maxConcurrency] = 4 by default)
     * using real Xray-core proxy HTTP 204 latency testing ([testRealVpnLatency]).
     */
    suspend fun testProfilesConcurrently(
        profiles: List<ServerProfile>,
        timeoutMs: Int = DEFAULT_REAL_PROXY_TIMEOUT_MS,
        context: Context? = null,
        settings: AppSettings = AppSettings(),
        maxConcurrency: Int = MAX_CONCURRENT_XRAY_WORKERS,
        performWarmup: Boolean = true,
        sampleCount: Int = 3,
        customProbe: (suspend (ServerProfile) -> LatencyTestOutcome)? = null,
        onEachCompleted: suspend (LatencyTestOutcome) -> Unit = {}
    ): List<LatencyTestOutcome> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency.coerceIn(1, MAX_CONCURRENT_TCP_WORKERS))
        profiles.map { profile ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val outcome = if (customProbe != null) {
                        customProbe(profile)
                    } else {
                        testRealVpnLatency(
                            context = context,
                            profile = profile,
                            settings = settings,
                            timeoutMs = timeoutMs,
                            performWarmup = performWarmup,
                            sampleCount = sampleCount
                        )
                    }
                    onEachCompleted(outcome)
                    outcome
                }
            }
        }.awaitAll()
    }
}
