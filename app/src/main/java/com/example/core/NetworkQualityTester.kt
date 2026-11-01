package com.example.core

import com.example.domain.model.ConnectionQualityReport
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.QualityTestStage
import com.example.domain.model.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Dedicated Real Network Diagnostics & Quality Testing Engine (V2.5).
 *
 * Strictly separates and measures:
 * 1. VPN Tunnel Connectivity (local SOCKS5 RFC 1928 handshake when VPN is active).
 * 2. End-to-End Latency through VPN (`PROXY_HTTP_DELAY`) vs Endpoint TCP Latency (`TCP_HANDSHAKE`).
 * 3. Real Internet Reachability (HTTP 204 verification).
 * 4. Bounded Download Throughput (max 192 KB cap, no wasted data, zero fake values).
 * 5. Bounded Upload Throughput (64 KB cap, real socket timing, zero fake values).
 */
object NetworkQualityTester {

    private const val DOWNLOAD_TEST_BYTES = 196_608 // 192 KB bounded test
    private const val UPLOAD_TEST_BYTES = 65_536    // 64 KB bounded test
    private const val CONNECT_TIMEOUT_MS = 4500L
    private const val READ_WRITE_TIMEOUT_MS = 5500L

    suspend fun runQualityTest(
        profile: ServerProfile?,
        isVpnConnected: Boolean,
        localSocksPort: Int?,
        testUrl204: String = LatencyTester.DEFAULT_HTTP_204_URL,
        onStageUpdate: (ConnectionQualityReport) -> Unit
    ): ConnectionQualityReport = withContext(Dispatchers.IO) {
        val testedViaVpn = isVpnConnected && localSocksPort != null && localSocksPort > 0
        var report = ConnectionQualityReport(
            stage = QualityTestStage.TESTING_TUNNEL,
            testedThroughVpn = testedViaVpn,
            timestampMs = System.currentTimeMillis()
        )
        onStageUpdate(report)

        try {
            currentCoroutineContext().ensureActive()

            // STEP 1: Verify VPN Tunnel / Local SOCKS5 or Endpoint Socket
            val tunnelOk = if (testedViaVpn) {
                verifyLocalSocks5Handshake(localSocksPort!!)
            } else {
                false
            }

            report = report.copy(
                stage = QualityTestStage.TESTING_LATENCY,
                tunnelConnected = tunnelOk
            )
            onStageUpdate(report)
            currentCoroutineContext().ensureActive()

            // STEP 2 & 3: Measure Real Latency & Verify Internet Reachability
            var measuredLatencyMs: Long? = null
            var measuredType: LatencyMeasureType = LatencyMeasureType.UNTESTED
            var internetOk = false
            var errorNote: String? = null

            if (testedViaVpn && profile != null) {
                if (!tunnelOk) {
                    return@withContext report.copy(
                        stage = QualityTestStage.FAILED,
                        tunnelConnected = false,
                        internetReachable = false,
                        summaryMessageFa = "تونل محلی VPN پاسخگو نیست",
                        summaryMessageEn = "Local VPN tunnel is not responding",
                        errorDetail = "SOCKS5 127.0.0.1:$localSocksPort handshake failed",
                        timestampMs = System.currentTimeMillis()
                    ).also(onStageUpdate)
                }

                val proxyOutcome = LatencyTester.testProxyHttp204Delay(
                    profile = profile,
                    localSocksPort = localSocksPort,
                    testUrl = testUrl204,
                    timeoutMs = 5000
                )
                measuredLatencyMs = proxyOutcome.latencyMs
                measuredType = proxyOutcome.measureType
                internetOk = proxyOutcome.latencyMs != null
                errorNote = proxyOutcome.errorReason
            } else if (profile != null) {
                val tcpOutcome = LatencyTester.testSingleTcpLatency(
                    profile = profile,
                    timeoutMs = 3500
                )
                measuredLatencyMs = tcpOutcome.latencyMs
                measuredType = tcpOutcome.measureType
                errorNote = tcpOutcome.errorReason

                report = report.copy(
                    stage = QualityTestStage.TESTING_INTERNET,
                    latencyMs = measuredLatencyMs,
                    latencyType = measuredType
                )
                onStageUpdate(report)
                currentCoroutineContext().ensureActive()

                internetOk = measureDirectInternet204(testUrl204)
            } else {
                internetOk = measureDirectInternet204(testUrl204)
            }

            report = report.copy(
                stage = if (internetOk) QualityTestStage.TESTING_DOWNLOAD else QualityTestStage.COMPLETED,
                latencyMs = measuredLatencyMs,
                latencyType = measuredType,
                internetReachable = internetOk,
                errorDetail = if (!internetOk) errorNote else null
            )
            onStageUpdate(report)
            currentCoroutineContext().ensureActive()

            // If internet is not reachable through the tested path, never attempt or fake throughput
            if (!internetOk) {
                val msgFa = when {
                    testedViaVpn -> "تونل VPN برقرار است اما دسترسی به اینترنت آزاد تایید نشد (${errorNote ?: "عدم پاسخ سرور"})"
                    measuredLatencyMs != null -> "پینگ TCP سرور (${measuredLatencyMs} ms) برقرار است؛ برای تست کامل تونل، ابتدا به VPN متصل شوید"
                    profile == null -> "هیچ سروری انتخاب نشده است"
                    else -> "سرور انتخاب‌شده در دسترس نیست (${errorNote ?: "تایم‌اوت"})"
                }
                val msgEn = when {
                    testedViaVpn -> "VPN tunnel active, but internet access failed (${errorNote ?: "Timeout"})"
                    measuredLatencyMs != null -> "Server TCP reachable (${measuredLatencyMs} ms). Connect VPN for full tunnel test."
                    profile == null -> "No server profile selected"
                    else -> "Selected server is unreachable (${errorNote ?: "Timeout"})"
                }
                return@withContext report.copy(
                    stage = QualityTestStage.FAILED,
                    summaryMessageFa = msgFa,
                    summaryMessageEn = msgEn,
                    timestampMs = System.currentTimeMillis()
                ).also(onStageUpdate)
            }

            // STEP 4: Real Bounded Download Throughput Measurement
            val client = buildHttpClient(if (testedViaVpn) localSocksPort else null)
            val downBps = measureRealDownloadBps(client)

            report = report.copy(
                stage = QualityTestStage.TESTING_UPLOAD,
                downloadBps = downBps
            )
            onStageUpdate(report)
            currentCoroutineContext().ensureActive()

            // STEP 5: Real Bounded Upload Throughput Measurement
            val upBps = measureRealUploadBps(client)

            val summaryFa = if (testedViaVpn) {
                "اتصال تونل VPN و اینترنت آزاد با موفقیت تایید شد"
            } else {
                "دسترسی شبکه بررسی شد (بدون تونل VPN)"
            }
            val summaryEn = if (testedViaVpn) {
                "VPN tunnel and open internet access verified"
            } else {
                "Direct network reachability verified (VPN disconnected)"
            }

            return@withContext report.copy(
                stage = QualityTestStage.COMPLETED,
                downloadBps = downBps,
                uploadBps = upBps,
                summaryMessageFa = summaryFa,
                summaryMessageEn = summaryEn,
                timestampMs = System.currentTimeMillis()
            ).also(onStageUpdate)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return@withContext report.copy(
                stage = QualityTestStage.FAILED,
                summaryMessageFa = "خطا در تست شبکه: ${e.message ?: "نامشخص"}",
                summaryMessageEn = "Network test error: ${e.message ?: "Unknown"}",
                errorDetail = e.message,
                timestampMs = System.currentTimeMillis()
            ).also(onStageUpdate)
        }
    }

    private fun verifyLocalSocks5Handshake(localSocksPort: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.soTimeout = 2000
                socket.connect(InetSocketAddress("127.0.0.1", localSocksPort), 2000)
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val reply = ByteArray(2)
                val read = inp.read(reply)
                read == 2 && reply[0] == 0x05.toByte() && reply[1] == 0x00.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildHttpClient(localSocksPort: Int?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(READ_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)

        if (localSocksPort != null && localSocksPort > 0) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localSocksPort)))
        }
        return builder.build()
    }

    private fun measureDirectInternet204(url: String): Boolean {
        return try {
            val client = buildHttpClient(null)
            val req = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                resp.code in 200..299
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun measureRealDownloadBps(client: OkHttpClient): Long? {
        val downloadUrls = listOf(
            "https://speed.cloudflare.com/__down?bytes=$DOWNLOAD_TEST_BYTES",
            "https://www.cloudflare.com/cdn-cgi/trace"
        )
        for (url in downloadUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                val startNs = System.nanoTime()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val stream: InputStream = response.body?.byteStream() ?: return@use
                    val buffer = ByteArray(8192)
                    var totalBytes = 0L
                    while (totalBytes < DOWNLOAD_TEST_BYTES) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        totalBytes += read
                    }
                    val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1_000_000L)
                    if (totalBytes > 0L) {
                        return (totalBytes * 1_000_000_000L) / elapsedNs
                    }
                }
            } catch (_: Exception) {
                // Try next fallback URL if any
            }
        }
        return null
    }

    private fun measureRealUploadBps(client: OkHttpClient): Long? {
        return try {
            val payload = ByteArray(UPLOAD_TEST_BYTES) { (it and 0x7F).toByte() }
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength(): Long = payload.size.toLong()
                override fun writeTo(sink: BufferedSink) {
                    sink.write(payload)
                    sink.flush()
                }
            }
            val req = Request.Builder()
                .url("https://speed.cloudflare.com/__up")
                .header("Cache-Control", "no-cache")
                .post(body)
                .build()
            val startNs = System.nanoTime()
            client.newCall(req).execute().use { response ->
                val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1_000_000L)
                if (response.isSuccessful) {
                    (payload.size.toLong() * 1_000_000_000L) / elapsedNs
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
