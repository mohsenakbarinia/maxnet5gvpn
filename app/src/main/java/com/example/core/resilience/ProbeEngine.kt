package com.example.core.resilience

import android.net.Network
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl. SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Abstraction for executing an individual endpoint probe. Allows deterministic unit testing
 * of [ProbeEngine], [NationalInternetDetector], and [TunnelHealthVerifier] without real network I/O.
 */
interface ProbeRunner {
    suspend fun executeProbe(
        endpoint: ProbeEndpoint,
        plane: Plane,
        maxLayer: ProbeLayer,
        config: ConnectivityConfig,
        underlayNetwork: Network? = null,
        overlaySocksPort: Int? = null
    ): ProbeResult
}

/**
 * Production [ProbeRunner] that performs layered network probes (`L-IP`, `L-DNS`, `L-TLS`,
 * `L-HTTP`, `L-XFER`) across either:
 * - [Plane.UNDERLAY]: bound directly to the physical [underlayNetwork] (or default process socket
 *   when MAXNET5G is excluded from its own TUN via `addDisallowedApplication(packageName)`).
 * - [Plane.OVERLAY]: routed through the local Xray SOCKS5 inbound (`127.0.0.1:overlaySocksPort`).
 *
 * Security invariants:
 * - Uses platform default [SSLSocketFactory] and strict RFC 2818 hostname verification.
 * - Never installs a trust-all `TrustManager` or permissive `HostnameVerifier`.
 */
class RealProbeRunner(
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() }
) : ProbeRunner {

    override suspend fun executeProbe(
        endpoint: ProbeEndpoint,
        plane: Plane,
        maxLayer: ProbeLayer,
        config: ConnectivityConfig,
        underlayNetwork: Network?,
        overlaySocksPort: Int?
    ): ProbeResult = withContext(Dispatchers.IO) {
        val startMs = clockMsProvider()
        val hashedId = EndpointIdHasher.hashEndpoint(endpoint.url, endpoint.port, plane.name)

        var dnsLatencyMs: Long? = null
        var tcpLatencyMs: Long? = null
        var tlsLatencyMs: Long? = null
        var httpLatencyMs: Long? = null
        var highestLayer: ProbeLayer? = null
        val resolvedIps = mutableListOf<String>()

        try {
            // 1. L-DNS (for UNDERLAY, resolve explicitly via physical Network if available;
            // for OVERLAY, SOCKS5h resolves or we measure connection directly)
            val targetInetAddress: InetAddress?
            if (plane == Plane.UNDERLAY) {
                val dnsStart = clockMsProvider()
                val addresses: Array<InetAddress> = if (underlayNetwork != null) {
                    underlayNetwork.getAllByName(endpoint.host)
                } else {
                    InetAddress.getAllByName(endpoint.host)
                }
                dnsLatencyMs = (clockMsProvider() - dnsStart).coerceAtLeast(1L)
                if (addresses.isEmpty()) {
                    return@withContext ProbeResult(
                        endpointId = hashedId,
                        rawEndpointKey = endpoint.id,
                        role = endpoint.role,
                        providerGroup = endpoint.providerGroup,
                        plane = plane,
                        layerReached = null,
                        success = false,
                        dnsLatencyMs = dnsLatencyMs,
                        totalLatencyMs = (clockMsProvider() - startMs).coerceAtLeast(1L),
                        failureCategory = FailureCategory.DNS_FAILURE,
                        errorSummary = "Empty DNS response",
                        timestampMs = clockMsProvider()
                    )
                }
                for (addr in addresses) {
                    val hostAddr = addr.hostAddress ?: continue
                    resolvedIps.add(hostAddr)
                    if (FailureClassifier.isSuspiciousResolvedIpForPublicHost(endpoint.host, hostAddr)) {
                        return@withContext ProbeResult(
                            endpointId = hashedId,
                            rawEndpointKey = endpoint.id,
                            role = endpoint.role,
                            providerGroup = endpoint.providerGroup,
                            plane = plane,
                            layerReached = ProbeLayer.DNS,
                            success = false,
                            dnsLatencyMs = dnsLatencyMs,
                            totalLatencyMs = (clockMsProvider() - startMs).coerceAtLeast(1L),
                            resolvedIps = resolvedIps,
                            failureCategory = FailureCategory.DNS_TAMPERING_SUSPECTED,
                            errorSummary = "Suspicious non-public IP returned for public host",
                            timestampMs = clockMsProvider()
                        )
                    }
                }
                highestLayer = ProbeLayer.DNS
                targetInetAddress = addresses.first()
                if (maxLayer == ProbeLayer.DNS) {
                    return@withContext ProbeResult(
                        endpointId = hashedId,
                        rawEndpointKey = endpoint.id,
                        role = endpoint.role,
                        providerGroup = endpoint.providerGroup,
                        plane = plane,
                        layerReached = ProbeLayer.DNS,
                        success = true,
                        dnsLatencyMs = dnsLatencyMs,
                        totalLatencyMs = (clockMsProvider() - startMs).coerceAtLeast(1L),
                        resolvedIps = resolvedIps,
                        timestampMs = clockMsProvider()
                    )
                }
            } else {
                targetInetAddress = null
            }

            // 2. L-IP (TCP connect)
            val tcpStart = clockMsProvider()
            val rawSocket: Socket = when (plane) {
                Plane.UNDERLAY -> {
                    val sock = underlayNetwork?.socketFactory?.createSocket() ?: Socket()
                    val sockAddr = InetSocketAddress(
                        targetInetAddress ?: InetAddress.getByName(endpoint.host),
                        endpoint.port
                    )
                    sock.soTimeout = config.tcpTimeoutMs.toInt()
                    sock.connect(sockAddr, config.tcpTimeoutMs.toInt())
                    sock
                }
                Plane.OVERLAY -> {
                    val port = overlaySocksPort
                        ?: return@withContext ProbeResult(
                            endpointId = hashedId,
                            rawEndpointKey = endpoint.id,
                            role = endpoint.role,
                            providerGroup = endpoint.providerGroup,
                            plane = plane,
                            layerReached = null,
                            success = false,
                            totalLatencyMs = (clockMsProvider() - startMs).coerceAtLeast(1L),
                            failureCategory = FailureCategory.TRANSPORT_UNAVAILABLE,
                            errorSummary = "Overlay SOCKS port unavailable",
                            timestampMs = clockMsProvider()
                        )
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                    val sock = Socket(proxy)
                    sock.soTimeout = config.tcpTimeoutMs.toInt()
                    // Unresolved address instructs SOCKS5 proxy to resolve remotely (SOCKS5h)
                    val remoteAddr = InetSocketAddress.createUnresolved(endpoint.host, endpoint.port)
                    sock.connect(remoteAddr, config.tcpTimeoutMs.toInt())
                    sock
                }
            }

            rawSocket.use { tcpSocket ->
                tcpLatencyMs = (clockMsProvider() - tcpStart).coerceAtLeast(1L)
                highestLayer = ProbeLayer.IP
                if (maxLayer == ProbeLayer.IP) {
                    return@withContext ProbeResult(
                        endpointId = hashedId,
                        rawEndpointKey = endpoint.id,
                        role = endpoint.role,
                        providerGroup = endpoint.providerGroup,
                        plane = plane,
                        layerReached = ProbeLayer.IP,
                        success = true,
                        dnsLatencyMs = dnsLatencyMs,
                        tcpLatencyMs = tcpLatencyMs,
                        totalLatencyMs = (clockMsProvider() - startMs).coerceAtLeast(1L),
                        resolvedIps = resolvedIps,
                        timestampMs = clockMsProvider()
                    )
                }

                // 3. L-TLS (TLS handshake + strict RFC 2818 hostname verification + optional SPKI pin)
                val tlsStart = clockMsProvider()
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslFactory.createSocket(
                    tcpSocket,
                    endpoint.host,
                    endpoint.port,
                    true
                ) as SSLSocket
                sslSocket.soTimeout = config.tlsTimeoutMs.toInt()
                val sslParams: SSLParameters = sslSocket.sslParameters
                sslParams.serverNames = listOf(SNIHostName(endpoint.host))
                sslParams.endpointIdentificationAlgorithm = "HTTPS"
                sslSocket.sslParameters = sslParams
                sslSocket.startHandshake()

                val session = sslSocket.session
                val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
                if (!verifier.verify(endpoint.host, session)) {
                    return@withContext ProbeResult(
                        endpointId = hashedId,
                        rawEndpointKey = endpoint.id,
                        role = endpoint.role,
                        providerGroup = endpoint.providerGroup,
                        plane = plane,
                        layerReached = ProbeLayer.IP,
                        success = false,
                        dnsLatencyMs = dnsLatencyMs,
                        tcpLatencyMs = tcpLatencyMs,
                        totalLatencyMs = (clockMsProvider() - startMs).coerceAtLeast(1L),
                        resolvedIps = resolvedIps,
                        failureCategory = FailureCategory.TLS_CERTIFICATE_INVALID,
                        errorSummary = "Hostname verification failed",
                        timestampMs = clockMsProvider()
                    )
                }

                // Optional SPKI SHA-256 pin verification
                if (!endpoint.spkiPinSha256.isNullOrBlank()) {
                    val certs = session.peerCertificates
                    val matchesPin = certs.any { cert ->
                        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
                        val b64 = Base64.encodeToString(digest, Base64.NO_WRAP)
                        b64.equals(endpoint.spkiPinSha256.removePrefix("sha256/"), ignoreCase = false)
                    }
                    if (!matchesPin) {
                        return@withContext ProbeResult(
                            endpointId = hashedId,
                            rawEndpointKey = endpoint.id,
                            role = endpoint.role,
                            providerGroup = endpoint.providerGroup,
                            plane = plane,
                            layerReached = ProbeLayer.IP,
                            success = false,
                            dnsLatencyMs = dnsLatencyMs,
                            tcpLatencyMs = tcpLatencyMs,
                            totalLatencyMs = (clockMsProvider() - startMs).coerceAtLeast(1L),
                            resolvedIps = resolvedIps,
                            failureCategory = FailureCategory.TLS_CERTIFICATE_INVALID,
                            errorSummary = "SPKI pin mismatch",
                            timestampMs = clockMsProvider()
                        )
                    }
                }

                tlsLatencyMs = (clockMsProvider() - tlsStart).coerceAtLeast(1L)
                highestLayer = ProbeLayer.TLS
                if (maxLayer == ProbeLayer.TLS) {
                    return@withContext ProbeResult(
                        endpointId = hashedId,
                        rawEndpointKey = endpoint.id,
                        role = endpoint.role,
                        providerGroup = endpoint.providerGroup,
                        plane = plane,
                        layerReached = ProbeLayer.TLS,
                        success = true,
                        dnsLatencyMs = dnsLatencyMs,
                        tcpLatencyMs = tcpLatencyMs,
                        tlsLatencyMs = tlsLatencyMs,
                        totalLatencyMs = (clockMsProvider() - startMs).coerceAtLeast(1L),
                        resolvedIps = resolvedIps,
                        timestampMs = clockMsProvider()
                    )
                }

                // 4. L-HTTP / L-XFER over established TLS socket
                val httpStart = clockMsProvider()
                sslSocket.soTimeout = if (maxLayer == ProbeLayer.XFER) {
                    config.xferTimeoutMs.toInt()
                } else {
                    config.httpTimeoutMs.toInt()
                }

                val uri = URI(endpoint.url)
                val pathAndQuery = buildString {
                    append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                    if (!uri.rawQuery.isNullOrEmpty()) {
                        append('?').append(uri.rawQuery)
                    }
                }

                val writer = OutputStreamWriter(sslSocket.outputStream, Charsets.UTF_8)
                writer.write("GET $pathAndQuery HTTP/1.1\r\n")
                writer.write("Host: ${endpoint.host}\r\n")
                writer.write("User-Agent: ConnectivityCheck/1.0\r\n")
                writer.write("Accept: */*\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.flush()

                val reader = BufferedReader(InputStreamReader(sslSocket.inputStream, Charsets.UTF_8))
                val statusLine = reader.readLine()
                    ?: return@withContext ProbeResult(
                        endpointId = hashedId,
                        rawEndpointKey = endpoint.id,
                        role = endpoint.role,
                        providerGroup = endpoint.providerGroup,
                        plane = plane,
                        layerReached = ProbeLayer.TLS,
                        success = false,
                        dnsLatencyMs = dnsLatencyMs,
                        tcpLatencyMs = tcpLatencyMs,
                        tlsLatencyMs = tlsLatencyMs,
                        totalLatencyMs = (clockMsProvider() - startMs).coerceAtLeast(1L),
                        resolvedIps = resolvedIps,
                        failureCategory = FailureCategory.TRANSPORT_UNAVAILABLE,
                        errorSummary = "Empty HTTP status line",
                        timestampMs = clockMsProvider()
                    )

                val statusCode = parseHttpStatusCode(statusLine)
                var locationHeader: String? = null
                var dateHeader: String? = null
                var headerBytes = statusLine.length.toLong()

                while (true) {
                    val line = reader.readLine() ?: break
                    headerBytes += line.length + 2
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim().lowercase()
                        val value = line.substring(colon + 1).trim()
                        if (key == "location") locationHeader = value
                        if (key == "date") dateHeader = value
                    }
                }

                val clockSkewSeconds = parseHttpDateSkewSeconds(dateHeader, clockMsProvider())

                // Read up to 16 KB (or 2 KB in lowDataMode / HTTP mode)
                val maxReadBytes = when {
                    config.lowDataMode -> 1024
                    maxLayer == ProbeLayer.XFER -> 16 * 1024
                    else -> 2048
                }
                val charBuf = CharArray(maxReadBytes)
                var totalChars = 0
                while (totalChars < maxReadBytes) {
                    val read = reader.read(charBuf, totalChars, maxReadBytes - totalChars)
                    if (read <= 0) break
                    totalChars += read
                }
                val bodyPreview = if (totalChars > 0) String(charBuf, 0, totalChars) else ""
                val totalBytesTransferred = headerBytes + totalChars

                httpLatencyMs = (clockMsProvider() - httpStart).coerceAtLeast(1L)
                val totalMs = (clockMsProvider() - startMs).coerceAtLeast(1L)

                // Check clock skew first if > threshold
                if (clockSkewSeconds != null && kotlin.math.abs(clockSkewSeconds) > config.clockSkewThresholdSeconds) {
                    return@withContext ProbeResult(
                        endpointId = hashedId,
                        rawEndpointKey = endpoint.id,
                        role = endpoint.role,
                        providerGroup = endpoint.providerGroup,
                        plane = plane,
                        layerReached = ProbeLayer.HTTP,
                        success = false,
                        dnsLatencyMs = dnsLatencyMs,
                        tcpLatencyMs = tcpLatencyMs,
                        tlsLatencyMs = tlsLatencyMs,
                        httpLatencyMs = httpLatencyMs,
                        totalLatencyMs = totalMs,
                        httpStatusCode = statusCode,
                        bytesTransferred = totalBytesTransferred,
                        resolvedIps = resolvedIps,
                        clockSkewSeconds = clockSkewSeconds,
                        failureCategory = FailureCategory.CLOCK_SKEW_SUSPECTED,
                        errorSummary = "Clock skew ${clockSkewSeconds}s exceeds threshold",
                        timestampMs = clockMsProvider()
                    )
                }

                val httpFailure = FailureClassifier.classifyHttpResponse(
                    statusCode = statusCode,
                    expectedStatuses = endpoint.expectedHttpStatuses,
                    locationHeader = locationHeader,
                    bodyPreview = bodyPreview,
                    expectedBodyPrefix = endpoint.expectedBodyPrefix
                )

                val throughputBps = if (httpLatencyMs != null && httpLatencyMs > 0) {
                    (totalBytesTransferred * 8_000L) / httpLatencyMs
                } else {
                    null
                }

                return@withContext ProbeResult(
                    endpointId = hashedId,
                    rawEndpointKey = endpoint.id,
                    role = endpoint.role,
                    providerGroup = endpoint.providerGroup,
                    plane = plane,
                    layerReached = if (httpFailure == null) maxLayer else ProbeLayer.HTTP,
                    success = httpFailure == null,
                    dnsLatencyMs = dnsLatencyMs,
                    tcpLatencyMs = tcpLatencyMs,
                    tlsLatencyMs = tlsLatencyMs,
                    httpLatencyMs = httpLatencyMs,
                    totalLatencyMs = totalMs,
                    httpStatusCode = statusCode,
                    bytesTransferred = totalBytesTransferred,
                    throughputBps = throughputBps,
                    resolvedIps = resolvedIps,
                    clockSkewSeconds = clockSkewSeconds,
                    failureCategory = httpFailure,
                    errorSummary = if (httpFailure != null) "HTTP $statusCode" else null,
                    timestampMs = clockMsProvider()
                )
            }
        } catch (t: Throwable) {
            val failedAtLayer = when (highestLayer) {
                null -> if (plane == Plane.UNDERLAY) ProbeLayer.DNS else ProbeLayer.IP
                ProbeLayer.DNS -> ProbeLayer.IP
                ProbeLayer.IP -> ProbeLayer.TLS
                ProbeLayer.TLS -> ProbeLayer.HTTP
                else -> ProbeLayer.HTTP
            }
            val category = FailureClassifier.classifyException(
                throwable = t,
                layer = failedAtLayer,
                skewThresholdSeconds = config.clockSkewThresholdSeconds
            )
            return@withContext ProbeResult(
                endpointId = hashedId,
                rawEndpointKey = endpoint.id,
                role = endpoint.role,
                providerGroup = endpoint.providerGroup,
                plane = plane,
                layerReached = highestLayer,
                success = false,
                dnsLatencyMs = dnsLatencyMs,
                tcpLatencyMs = tcpLatencyMs,
                tlsLatencyMs = tlsLatencyMs,
                httpLatencyMs = httpLatencyMs,
                totalLatencyMs = (clockMsProvider() - startMs).coerceAtLeast(1L),
                resolvedIps = resolvedIps,
                failureCategory = category,
                errorSummary = t.javaClass.simpleName,
                timestampMs = clockMsProvider()
            )
        }
    }

    companion object {
        fun parseHttpStatusCode(statusLine: String): Int {
            val parts = statusLine.trim().split(' ')
            if (parts.size >= 2) {
                return parts[1].toIntOrNull() ?: -1
            }
            return -1
        }

        fun parseHttpDateSkewSeconds(dateHeader: String?, localEpochMs: Long): Long? {
            if (dateHeader.isNullOrBlank()) return null
            return runCatching {
                val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("GMT")
                val serverDate = sdf.parse(dateHeader.trim()) ?: return null
                (localEpochMs - serverDate.time) / 1000L
            }.getOrNull()
        }
    }
}

/**
 * Orchestrates probe cycles with:
 * - Single-flight mutex per plane/role
 * - Max [ConnectivityConfig.maxParallelProbes] concurrency
 * - Hard [ConnectivityConfig.maxCycleDurationMs] cycle timeout
 * - Result cache TTL ([ConnectivityConfig.probeCacheTtlMs])
 * - Rolling 10-minute metered data budget cap ([ConnectivityConfig.meteredTenMinByteCap])
 */
class ProbeEngine(
    private val runner: ProbeRunner = RealProbeRunner(),
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val cycleMutex = Mutex()
    private val cache = ConcurrentHashMap<String, List<ProbeResult>>()
    private val cacheTimestamp = ConcurrentHashMap<String, Long>()
    private val lastCycleEpochMs = AtomicLong(0L)

    // Rolling 10-minute byte window for metered networks
    private val byteWindowLock = Any()
    private val byteSamples = ArrayDeque<Pair<Long, Long>>() // (timestampMs, bytes)

    suspend fun runProbeCycle(
        endpoints: List<ProbeEndpoint>,
        plane: Plane,
        maxLayer: ProbeLayer = ProbeLayer.HTTP,
        config: ConnectivityConfig = ConnectivityConfig(),
        isMeteredNetwork: Boolean = false,
        underlayNetwork: Network? = null,
        overlaySocksPort: Int? = null,
        forceRefresh: Boolean = false
    ): List<ProbeResult> {
        if (endpoints.isEmpty()) return emptyList()

        val cacheKey = "${plane.name}_${endpoints.first().role.name}_${underlayNetwork?.networkHandle ?: 0L}"
        val now = clockMsProvider()

        if (!forceRefresh) {
            val cachedAt = cacheTimestamp[cacheKey]
            val cached = cache[cacheKey]
            if (cached != null && cachedAt != null && (now - cachedAt) < config.probeCacheTtlMs) {
                return cached
            }
            val sinceLast = now - lastCycleEpochMs.get()
            if (cached != null && sinceLast in 0 until config.minProbeCycleIntervalMs) {
                return cached
            }
        }

        return cycleMutex.withLock {
            val effectiveMaxLayer = if (
                config.lowDataMode ||
                (isMeteredNetwork && getRollingTenMinBytes(now) >= config.meteredTenMinByteCap)
            ) {
                if (maxLayer == ProbeLayer.XFER) ProbeLayer.HTTP else maxLayer
            } else {
                maxLayer
            }

            val semaphore = Semaphore(config.maxParallelProbes.coerceAtLeast(1))
            val results = withTimeoutOrNull(config.maxCycleDurationMs) {
                coroutineScope {
                    endpoints.map { ep ->
                        async {
                            semaphore.withPermit {
                                runner.executeProbe(
                                    endpoint = ep,
                                    plane = plane,
                                    maxLayer = effectiveMaxLayer,
                                    config = config,
                                    underlayNetwork = underlayNetwork,
                                    overlaySocksPort = overlaySocksPort
                                )
                            }
                        }
                    }.awaitAll()
                }
            } ?: emptyList()

            val finishNow = clockMsProvider()
            val totalCycleBytes = results.sumOf { it.bytesTransferred }
            if (totalCycleBytes > 0) {
                recordBytesUsed(finishNow, totalCycleBytes)
            }

            if (results.isNotEmpty()) {
                cache[cacheKey] = results
                cacheTimestamp[cacheKey] = finishNow
                lastCycleEpochMs.set(finishNow)
            }
            results
        }
    }

    fun invalidateCache() {
        cache.clear()
        cacheTimestamp.clear()
        lastCycleEpochMs.set(0L)
    }

    fun getRollingTenMinBytes(nowMs: Long = clockMsProvider()): Long = synchronized(byteWindowLock) {
        pruneOldByteSamplesLocked(nowMs)
        byteSamples.sumOf { it.second }
    }

    private fun recordBytesUsed(nowMs: Long, bytes: Long) = synchronized(byteWindowLock) {
        pruneOldByteSamplesLocked(nowMs)
        byteSamples.addLast(nowMs to bytes)
    }

    private fun pruneOldByteSamplesLocked(nowMs: Long) {
        val cutoff = nowMs - 10 * 60_000L
        while (byteSamples.isNotEmpty() && byteSamples.first().first < cutoff) {
            byteSamples.removeFirst()
        }
    }
}
