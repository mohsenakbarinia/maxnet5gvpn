package com.example.core

import android.content.Context
import com.example.core.xray.XrayConfigBuilder
import com.example.core.xray.XrayNativeBridge
import com.example.domain.model.AppSettings
import com.example.domain.model.LatencyMeasureType
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
    val errorReason: String? = null
)

/**
 * Real Server Latency & Delay Tester.
 * - Never fabricates ping values.
 * - Clearly distinguishes between:
 *   1. [LatencyMeasureType.TCP_HANDSHAKE] (TCP socket connect time to server:port).
 *   2. [LatencyMeasureType.PROXY_HTTP_DELAY] (actual HTTP 204 delay through the encrypted Xray-core proxy).
 * - Never substitutes a TCP handshake result when a proxy HTTP 204 test fails.
 * - Uses a bounded concurrency semaphore (10 workers max) with full cancellation support.
 */
object LatencyTester {
    private const val MAX_CONCURRENT_WORKERS = 10
    private const val DEFAULT_TIMEOUT_MS = 3500
    const val DEFAULT_HTTP_204_URL = "https://cp.cloudflare.com/generate_204"

    /**
     * Measures raw TCP handshake latency (`connect()` to `profile.address:profile.port`).
     * Clearly labeled as [LatencyMeasureType.TCP_HANDSHAKE].
     */
    suspend fun testSingleTcpLatency(
        profile: ServerProfile,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): LatencyTestOutcome = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        return@withContext try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(profile.address, profile.port), timeoutMs)
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            LatencyTestOutcome(
                profileId = profile.id,
                latencyMs = elapsedMs,
                measureType = LatencyMeasureType.TCP_HANDSHAKE
            )
        } catch (e: Exception) {
            LatencyTestOutcome(
                profileId = profile.id,
                latencyMs = null,
                measureType = LatencyMeasureType.TIMEOUT,
                errorReason = e.message ?: "TCP connection timeout"
            )
        }
    }

    /**
     * Measures real end-to-end HTTP 204 delay through the currently running local SOCKS5 proxy.
     * Does NOT fall back to TCP handshake on failure — reports [LatencyMeasureType.TIMEOUT] with the real error.
     */
    suspend fun testProxyHttp204Delay(
        profile: ServerProfile,
        localSocksPort: Int,
        testUrl: String = DEFAULT_HTTP_204_URL,
        timeoutMs: Int = 5000
    ): LatencyTestOutcome = withContext(Dispatchers.IO) {
        val delayResult = XrayNativeBridge.measureActiveProxyDelay(
            localSocksPort = localSocksPort,
            testUrl = testUrl,
            timeoutMs = timeoutMs
        )
        delayResult.fold(
            onSuccess = { delayMs ->
                LatencyTestOutcome(
                    profileId = profile.id,
                    latencyMs = delayMs,
                    measureType = LatencyMeasureType.PROXY_HTTP_DELAY
                )
            },
            onFailure = { err ->
                LatencyTestOutcome(
                    profileId = profile.id,
                    latencyMs = null,
                    measureType = LatencyMeasureType.TIMEOUT,
                    errorReason = err.message ?: "Proxy HTTP 204 test failed"
                )
            }
        )
    }

    /**
     * Measures real end-to-end HTTP 204 delay for an arbitrary profile using Xray-core's
     * standalone `measureOutboundDelay` (without needing an active VPN session).
     */
    suspend fun testStandaloneProxyDelay(
        context: Context?,
        profile: ServerProfile,
        settings: AppSettings = AppSettings(),
        testUrl: String = DEFAULT_HTTP_204_URL
    ): LatencyTestOutcome = withContext(Dispatchers.IO) {
        val validation = XrayConfigBuilder.buildSpeedtestConfig(profile, settings)
        if (!validation.isValid) {
            return@withContext LatencyTestOutcome(
                profileId = profile.id,
                latencyMs = null,
                measureType = LatencyMeasureType.TIMEOUT,
                errorReason = validation.errors.firstOrNull() ?: "Invalid Xray configuration"
            )
        }
        val delayResult = XrayNativeBridge.measureStandaloneOutboundDelay(
            context = context,
            configJson = validation.generatedConfigJson,
            testUrl = testUrl
        )
        delayResult.fold(
            onSuccess = { delayMs ->
                LatencyTestOutcome(
                    profileId = profile.id,
                    latencyMs = delayMs,
                    measureType = LatencyMeasureType.PROXY_HTTP_DELAY
                )
            },
            onFailure = { err ->
                LatencyTestOutcome(
                    profileId = profile.id,
                    latencyMs = null,
                    measureType = LatencyMeasureType.TIMEOUT,
                    errorReason = err.message ?: "Outbound proxy delay test failed"
                )
            }
        )
    }

    suspend fun testProfilesConcurrently(
        profiles: List<ServerProfile>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        onEachCompleted: suspend (LatencyTestOutcome) -> Unit = {}
    ): List<LatencyTestOutcome> = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_WORKERS)
        profiles.map { profile ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val outcome = testSingleTcpLatency(profile, timeoutMs)
                    onEachCompleted(outcome)
                    outcome
                }
            }
        }.awaitAll()
    }
}
