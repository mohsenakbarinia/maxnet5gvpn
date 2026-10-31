package com.example.core

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
import java.net.Proxy
import java.net.Socket
import java.net.HttpURLConnection
import java.net.URL

data class LatencyTestOutcome(
    val profileId: Long,
    val latencyMs: Long?,
    val measureType: LatencyMeasureType,
    val errorReason: String? = null
)

/**
 * Real Server Latency & Delay Tester.
 * - Never fabricates ping values.
 * - Clearly distinguishes between [LatencyMeasureType.TCP_HANDSHAKE] (socket connect time)
 *   and [LatencyMeasureType.PROXY_HTTP_DELAY] (end-to-end HTTP 204 delay through local SOCKS5 proxy).
 * - Uses a bounded concurrency semaphore (10 workers max) with full cancellation support.
 */
object LatencyTester {
    private const val MAX_CONCURRENT_WORKERS = 10
    private const val DEFAULT_TIMEOUT_MS = 3500

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
                errorReason = e.message ?: "Connection timeout"
            )
        }
    }

    suspend fun testProxyHttp204Delay(
        profile: ServerProfile,
        localSocksPort: Int,
        timeoutMs: Int = 4500
    ): LatencyTestOutcome = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        return@withContext try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localSocksPort))
            val url = URL("https://cp.cloudflare.com/generate_204")
            val conn = (url.openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                requestMethod = "HEAD"
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                LatencyTestOutcome(
                    profileId = profile.id,
                    latencyMs = elapsedMs,
                    measureType = LatencyMeasureType.PROXY_HTTP_DELAY
                )
            } else {
                // Fall back to real TCP handshake measurement and label it clearly
                testSingleTcpLatency(profile, timeoutMs)
            }
        } catch (_: Exception) {
            testSingleTcpLatency(profile, timeoutMs)
        }
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
