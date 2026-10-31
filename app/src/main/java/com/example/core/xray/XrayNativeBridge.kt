package com.example.core.xray

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import android.util.Base64
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import libv2ray.ProcessFinder
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class OutboundTrafficDelta(
    val proxyUploadBytes: Long = 0L,
    val proxyDownloadBytes: Long = 0L,
    val directUploadBytes: Long = 0L,
    val directDownloadBytes: Long = 0L
) {
    val totalUploadBytes: Long get() = proxyUploadBytes + directUploadBytes
    val totalDownloadBytes: Long get() = proxyDownloadBytes + directDownloadBytes
}

/**
 * Production Native Bridge for Official Xray-core v26.10.29 (`2dust/AndroidLibXrayLite` Lib v40,
 * upstream `XTLS/Xray-core v26.10.29` commit `0afec66`).
 *
 * - On Android devices/emulators: executes in-process via `libgojni.so` (`Libv2ray` & `CoreController`).
 * - On Linux JVM test hosts (where Android Bionic `.so` cannot link into glibc): if an official
 *   upstream `xray` binary is present (`/tmp/xray-linux/xray` or `XRAY_CORE_BINARY`), executes the
 *   real upstream `xray` binary for authentic end-to-end integration verification.
 * - Never fakes or simulates an engine or connection state.
 */
object XrayNativeBridge {

    const val PINNED_XRAY_VERSION = "v26.10.29"
    const val PINNED_UPSTREAM_COMMIT = "0afec66"
    const val DEFAULT_DELAY_TEST_URL = "https://www.google.com/generate_204"
    const val FALLBACK_DELAY_TEST_URL = "https://cp.cloudflare.com/generate_204"

    private val jniLoaded = AtomicBoolean(false)
    private val envInitialized = AtomicBoolean(false)
    private val statusListenerRef = AtomicReference<((Long, String) -> Unit)?>(null)

    private var coreController: CoreController? = null
    private var hostProcess: Process? = null
    private var hostConfigFile: File? = null
    private var activeAssetDir: String = ""

    init {
        try {
            Libv2ray.touch()
            jniLoaded.set(true)
        } catch (_: Throwable) {
            jniLoaded.set(false)
        }
    }

    private fun resolveHostXrayBinary(): File? {
        if (jniLoaded.get()) return null
        val candidates = listOfNotNull(
            System.getenv("XRAY_CORE_BINARY"),
            System.getProperty("xray.core.binary"),
            "/tmp/xray-linux/xray"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() && it.canExecute() }
    }

    private fun resolveHostAssetDir(): String {
        val candidates = listOf(
            File("app/build/xray-native/assets"),
            File("build/xray-native/assets"),
            File("/tmp/xray-linux")
        )
        return candidates.firstOrNull { File(it, "geoip.dat").exists() }?.absolutePath
            ?: activeAssetDir
    }

    fun isJniLibraryLoaded(): Boolean = jniLoaded.get()

    fun isEngineAvailable(): Boolean {
        return jniLoaded.get() || resolveHostXrayBinary() != null
    }

    fun isNativeLibraryLoaded(): Boolean = isEngineAvailable()

    fun getEngineVersionInfo(): String {
        if (jniLoaded.get()) {
            return try {
                Libv2ray.checkVersionX()
            } catch (_: Throwable) {
                "AndroidLibXrayLite $PINNED_XRAY_VERSION ($PINNED_UPSTREAM_COMMIT)"
            }
        }
        val bin = resolveHostXrayBinary()
        if (bin != null) {
            return try {
                val proc = ProcessBuilder(bin.absolutePath, "version")
                    .redirectErrorStream(true)
                    .start()
                val line = proc.inputStream.bufferedReader().readLine() ?: ""
                proc.waitFor(3, TimeUnit.SECONDS)
                line.ifBlank { "Xray-core $PINNED_XRAY_VERSION ($PINNED_UPSTREAM_COMMIT)" }
            } catch (_: Throwable) {
                "Xray-core $PINNED_XRAY_VERSION ($PINNED_UPSTREAM_COMMIT)"
            }
        }
        return "Xray-core $PINNED_XRAY_VERSION (JNI not loaded in current JVM)"
    }

    @Synchronized
    fun initCoreEnvironment(context: Context?): Result<Unit> {
        return try {
            if (context != null) {
                val appContext = context.applicationContext ?: context
                val filesDir = appContext.filesDir
                filesDir.mkdirs()
                activeAssetDir = filesDir.absolutePath
                extractBundledGeodataIfNeeded(appContext, filesDir)

                if (jniLoaded.get() && envInitialized.compareAndSet(false, true)) {
                    Seq.setContext(appContext)
                    val xudpKey = getOrCreateXudpBaseKey(filesDir)
                    Libv2ray.initCoreEnv(filesDir.absolutePath, xudpKey)
                }
            } else if (jniLoaded.get() && envInitialized.compareAndSet(false, true)) {
                val fallbackDir = File(System.getProperty("java.io.tmpdir", "/tmp"), "maxnet5g_xray")
                fallbackDir.mkdirs()
                activeAssetDir = fallbackDir.absolutePath
                Libv2ray.initCoreEnv(fallbackDir.absolutePath, getOrCreateXudpBaseKey(fallbackDir))
            }

            if (!isEngineAvailable()) {
                return Result.failure(
                    IllegalStateException(
                        "موتور نیتیو Xray-core ($PINNED_XRAY_VERSION) در این محیط بارگذاری نشد (libgojni.so)"
                    )
                )
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            envInitialized.set(false)
            Result.failure(t)
        }
    }

    private fun extractBundledGeodataIfNeeded(context: Context, targetDir: File) {
        if (File("/tmp/xray-linux/geoip.dat").exists()) {
            activeAssetDir = "/tmp/xray-linux"
            return
        }
        val assetNames = listOf("geoip.dat", "geosite.dat", "geoip-only-cn-private.dat")
        for (name in assetNames) {
            val outFile = File(targetDir, name)
            if (outFile.exists() && outFile.length() > 10_000L) continue
            try {
                context.assets.open(name).use { input ->
                    val tmp = File(targetDir, "$name.tmp")
                    tmp.outputStream().use { output -> input.copyTo(output) }
                    tmp.renameTo(outFile)
                }
            } catch (_: Throwable) {
                // Handled gracefully; mobasset also reads directly from APK assets
            }
        }
    }

    private fun getOrCreateXudpBaseKey(dir: File): String {
        return try {
            val keyFile = File(dir, ".xudp_base_key")
            if (keyFile.exists()) {
                val existing = keyFile.readText(Charsets.UTF_8).trim()
                if (existing.isNotEmpty()) return existing
            }
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            keyFile.writeText(encoded, Charsets.UTF_8)
            encoded
        } catch (_: Throwable) {
            "bWF4bmV0NWd2cG5feHVkcF9rZXlfMjAyNg"
        }
    }

    /**
     * Validates a generated Xray JSON configuration using the real Xray-core parser/engine when available.
     */
    fun validateConfigWithEngine(configJson: String): Result<Unit> {
        val hostBin = resolveHostXrayBinary()
        if (hostBin != null) {
            val tempFile = File.createTempFile("xray_validate_", ".json")
            return try {
                tempFile.writeText(configJson, Charsets.UTF_8)
                val pb = ProcessBuilder(hostBin.absolutePath, "run", "-test", "-c", tempFile.absolutePath)
                pb.environment()["XRAY_LOCATION_ASSET"] = resolveHostAssetDir()
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val finished = proc.waitFor(8, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    Result.failure(IllegalArgumentException("Xray config validation timed out"))
                } else if (proc.exitValue() != 0) {
                    val reason = output.lines()
                        .lastOrNull { it.contains("Failed to start") || it.contains("error", ignoreCase = true) }
                        ?: output.trim()
                    Result.failure(IllegalArgumentException("خطای اعتبارسنجی هسته Xray: $reason"))
                } else {
                    Result.success(Unit)
                }
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                tempFile.delete()
            }
        }
        return Result.success(Unit)
    }

    @Synchronized
    fun startCore(
        context: Context?,
        configJson: String,
        tunFd: Int = 0,
        onStatusEmit: ((Long, String) -> Unit)? = null
    ): Result<Unit> {
        stopCore()
        statusListenerRef.set(onStatusEmit)

        val initRes = initCoreEnvironment(context)
        if (initRes.isFailure) {
            return initRes
        }

        if (jniLoaded.get()) {
            return try {
                val controller = coreController ?: Libv2ray.newCoreController(object : CoreCallbackHandler {
                    override fun startup(): Long {
                        statusListenerRef.get()?.invoke(0L, "Xray-core startup complete")
                        return 0L
                    }

                    override fun shutdown(): Long {
                        statusListenerRef.get()?.invoke(0L, "Xray-core shutdown complete")
                        return 0L
                    }

                    override fun onEmitStatus(code: Long, status: String?): Long {
                        statusListenerRef.get()?.invoke(code, status.orEmpty())
                        return 0L
                    }
                }).also { created ->
                    if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching {
                            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                            if (cm != null) {
                                created.registerProcessFinder(
                                    ProcessFinder { network, srcIP, srcPort, destIP, destPort ->
                                        try {
                                            val proto = if (network.equals("udp", ignoreCase = true)) {
                                                OsConstants.IPPROTO_UDP
                                            } else {
                                                OsConstants.IPPROTO_TCP
                                            }
                                            cm.getConnectionOwnerUid(
                                                proto,
                                                InetSocketAddress(srcIP, srcPort.toInt()),
                                                InetSocketAddress(destIP, destPort.toInt())
                                            ).toLong()
                                        } catch (_: Throwable) {
                                            -1L
                                        }
                                    }
                                )
                            }
                        }
                    }
                    coreController = created
                }

                controller.startLoop(configJson, tunFd)
                if (!controller.isRunning) {
                    Result.failure(
                        IllegalStateException("Xray-core startLoop returned without entering running state")
                    )
                } else {
                    Result.success(Unit)
                }
            } catch (t: Throwable) {
                runCatching { coreController?.stopLoop() }
                Result.failure(
                    IllegalStateException(
                        "خطا در راه‌اندازی موتور Xray-core: ${t.message ?: t.javaClass.simpleName}",
                        t
                    )
                )
            }
        }

        val hostBin = resolveHostXrayBinary()
        if (hostBin != null) {
            return try {
                val check = validateConfigWithEngine(configJson)
                if (check.isFailure) return check

                val cfgFile = File.createTempFile("xray_runtime_", ".json")
                cfgFile.writeText(configJson, Charsets.UTF_8)
                hostConfigFile = cfgFile

                val pb = ProcessBuilder(hostBin.absolutePath, "run", "-c", cfgFile.absolutePath)
                pb.environment()["XRAY_LOCATION_ASSET"] = resolveHostAssetDir()
                if (tunFd > 0) {
                    pb.environment()["xray.tun.fd"] = tunFd.toString()
                }
                pb.redirectErrorStream(true)
                val proc = pb.start()
                hostProcess = proc

                Thread.sleep(180)
                if (!proc.isAlive) {
                    val errText = runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("")
                    cfgFile.delete()
                    hostProcess = null
                    hostConfigFile = null
                    Result.failure(
                        IllegalStateException("Xray-core exited immediately: ${errText.trim()}")
                    )
                } else {
                    Result.success(Unit)
                }
            } catch (t: Throwable) {
                stopCore()
                Result.failure(t)
            }
        }

        return Result.failure(
            IllegalStateException("موتور نیتیو Xray-core در دسترس نیست (libgojni.so بارگذاری نشده است)")
        )
    }

    @Synchronized
    fun stopCore() {
        try {
            coreController?.let { ctrl ->
                if (ctrl.isRunning) {
                    ctrl.stopLoop()
                }
            }
        } catch (_: Throwable) {
        }
        try {
            hostProcess?.let { proc ->
                if (proc.isAlive) {
                    proc.destroy()
                    if (!proc.waitFor(1500, TimeUnit.MILLISECONDS)) {
                        proc.destroyForcibly()
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            hostProcess = null
            hostConfigFile?.delete()
            hostConfigFile = null
        }
    }

    fun isCoreRunning(): Boolean {
        if (jniLoaded.get()) {
            return try {
                coreController?.isRunning == true
            } catch (_: Throwable) {
                false
            }
        }
        return hostProcess?.isAlive == true
    }

    /**
     * Verifies that the local SOCKS5 listener on 127.0.0.1:[localSocksPort] is accepting connections
     * and responds with a valid RFC 1928 SOCKS5 method selection reply (`0x05, 0x00`).
     */
    fun awaitLocalSocksEndpointReady(localSocksPort: Int, maxWaitMs: Long = 4000L): Result<Unit> {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            if (!isCoreRunning()) {
                return Result.failure(
                    IllegalStateException("موتور Xray-core در حین راه‌اندازی متوقف شد")
                )
            }
            try {
                Socket().use { sock ->
                    sock.tcpNoDelay = true
                    sock.soTimeout = 1000
                    sock.connect(InetSocketAddress("127.0.0.1", localSocksPort), 800)
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()
                    // RFC 1928: VER=0x05, NMETHODS=0x01, METHOD=0x00 (NO AUTH)
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val reply = ByteArray(2)
                    val read = inp.read(reply)
                    if (read == 2 && reply[0] == 0x05.toByte() && reply[1] == 0x00.toByte()) {
                        return Result.success(Unit)
                    }
                    lastError = IllegalStateException("Unexpected SOCKS5 greeting reply: read=$read")
                }
            } catch (t: Throwable) {
                lastError = t
                Thread.sleep(100)
            }
        }
        return Result.failure(
            IllegalStateException(
                "پورت محلی SOCKS5 موتور Xray (127.0.0.1:$localSocksPort) آماده نشد: ${lastError?.message ?: "timeout"}",
                lastError
            )
        )
    }

    /**
     * Measures real HTTP 204 round-trip latency through the active Xray instance.
     * Never uses a plain TCP socket handshake to the remote server as a substitute.
     */
    fun measureActiveProxyDelay(
        localSocksPort: Int,
        testUrl: String = DEFAULT_DELAY_TEST_URL,
        timeoutMs: Int = 6000
    ): Result<Long> {
        if (!isCoreRunning()) {
            return Result.failure(IllegalStateException("Xray-core is not running"))
        }

        // 1. On Android JNI, try CoreController.measureDelay first (dials directly through coreInstance)
        if (jniLoaded.get()) {
            val ctrl = coreController
            if (ctrl != null && ctrl.isRunning) {
                try {
                    val delay = ctrl.measureDelay(testUrl)
                    if (delay > 0L) {
                        return Result.success(delay)
                    }
                } catch (_: Throwable) {
                    // Fall through to SOCKS5 HTTP verification below
                }
            }
        }

        // 2. Perform real RFC 1928 SOCKS5 CONNECT + HTTP/HTTPS 204 request through 127.0.0.1:localSocksPort
        val urlsToTry = if (testUrl == DEFAULT_DELAY_TEST_URL) {
            listOf(DEFAULT_DELAY_TEST_URL, FALLBACK_DELAY_TEST_URL).distinct()
        } else {
            listOf(testUrl)
        }
        var lastException: Throwable? = null
        for (candidateUrl in urlsToTry) {
            val startNs = System.nanoTime()
            try {
                val parsedUrl = URL(candidateUrl)
                val scheme = parsedUrl.protocol.lowercase()
                val host = parsedUrl.host
                val port = if (parsedUrl.port > 0) parsedUrl.port else if (scheme == "https") 443 else 80
                val path = parsedUrl.file.ifBlank { "/" }

                Socket().use { rawSocket ->
                    rawSocket.tcpNoDelay = true
                    rawSocket.soTimeout = timeoutMs
                    rawSocket.connect(InetSocketAddress("127.0.0.1", localSocksPort), timeoutMs)

                    val sockOut = rawSocket.getOutputStream()
                    val sockIn = rawSocket.getInputStream()

                    // RFC 1928 Greeting: VER=5, NMETHODS=1, METHOD=0 (No Auth)
                    sockOut.write(byteArrayOf(0x05, 0x01, 0x00))
                    sockOut.flush()
                    val greetReply = ByteArray(2)
                    if (sockIn.read(greetReply) != 2 || greetReply[0] != 0x05.toByte() || greetReply[1] != 0x00.toByte()) {
                        throw IllegalStateException("SOCKS5 method negotiation failed on 127.0.0.1:$localSocksPort")
                    }

                    // RFC 1928 CONNECT request: use ATYP=0x01 (IPv4), ATYP=0x04 (IPv6), or ATYP=0x03 (DOMAINNAME)
                    val cleanHost = host.trim().removeSurrounding("[", "]")
                    val isIpv4 = cleanHost.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
                    val isIpv6 = cleanHost.contains(":")
                    val addrPayload: ByteArray
                    val atyp: Byte
                    when {
                        isIpv4 -> {
                            atyp = 0x01
                            addrPayload = java.net.InetAddress.getByName(cleanHost).address
                        }
                        isIpv6 -> {
                            atyp = 0x04
                            addrPayload = java.net.InetAddress.getByName(cleanHost).address
                        }
                        else -> {
                            atyp = 0x03
                            val hb = cleanHost.toByteArray(Charsets.UTF_8)
                            addrPayload = ByteArray(1 + hb.size).apply {
                                this[0] = hb.size.toByte()
                                System.arraycopy(hb, 0, this, 1, hb.size)
                            }
                        }
                    }

                    val connectReq = ByteArray(4 + addrPayload.size + 2)
                    connectReq[0] = 0x05
                    connectReq[1] = 0x01 // CONNECT
                    connectReq[2] = 0x00 // RSV
                    connectReq[3] = atyp
                    System.arraycopy(addrPayload, 0, connectReq, 4, addrPayload.size)
                    connectReq[4 + addrPayload.size] = ((port shr 8) and 0xFF).toByte()
                    connectReq[5 + addrPayload.size] = (port and 0xFF).toByte()
                    sockOut.write(connectReq)
                    sockOut.flush()

                    val hdr = ByteArray(4)
                    if (sockIn.read(hdr) != 4) {
                        throw IllegalStateException("SOCKS5 CONNECT reply truncated from Xray-core")
                    }
                    val rep = hdr[1].toInt() and 0xFF
                    if (rep != 0x00) {
                        throw IllegalStateException("SOCKS5 outbound CONNECT failed (REP=0x${rep.toString(16)})")
                    }
                    val toSkip = when (hdr[3].toInt() and 0xFF) {
                        0x01 -> 4 + 2 // IPv4 + Port
                        0x03 -> (sockIn.read() and 0xFF) + 2 // Domain length + Port
                        0x04 -> 16 + 2 // IPv6 + Port
                        else -> throw IllegalStateException("Unknown SOCKS5 ATYP=${hdr[3]}")
                    }
                    var remaining = toSkip
                    val discard = ByteArray(32)
                    while (remaining > 0) {
                        val r = sockIn.read(discard, 0, minOf(remaining, discard.size))
                        if (r <= 0) throw IllegalStateException("Unexpected EOF skipping SOCKS5 BND.ADDR")
                        remaining -= r
                    }

                    val tunnelSocket: Socket = if (scheme == "https") {
                        val sslFactory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                        (sslFactory.createSocket(rawSocket, host, port, true) as javax.net.ssl.SSLSocket).apply {
                            soTimeout = timeoutMs
                            startHandshake()
                        }
                    } else {
                        rawSocket
                    }

                    val hostHeader = if ((scheme == "http" && port != 80) || (scheme == "https" && port != 443)) {
                        "$host:$port"
                    } else {
                        host
                    }
                    val httpReq = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: MAXNET5G-VPN/1.0\r\nConnection: close\r\n\r\n"
                    val tunOut = tunnelSocket.getOutputStream()
                    tunOut.write(httpReq.toByteArray(Charsets.UTF_8))
                    tunOut.flush()

                    val statusLine = tunnelSocket.getInputStream().bufferedReader().readLine()
                        ?: throw IllegalStateException("Empty HTTP response over proxy tunnel")
                    val parts = statusLine.split(" ")
                    val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: -1
                    if (statusCode == 204 || statusCode == 200) {
                        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                        return Result.success(elapsedMs)
                    } else {
                        lastException = IllegalStateException("HTTP status $statusCode ($statusLine) from $candidateUrl")
                    }
                }
            } catch (t: Throwable) {
                lastException = t
            }
        }

        return Result.failure(
            IllegalStateException(
                "تست واقعی ترافیک از طریق پروکسی Xray ناموفق بود: ${lastException?.message ?: "No response through tunnel"}",
                lastException
            )
        )
    }

    /**
     * Measures real outbound proxy delay for an arbitrary Xray JSON configuration without
     * affecting the main VPN session, using `Libv2ray.measureOutboundDelay`.
     */
    fun measureStandaloneOutboundDelay(
        context: Context?,
        configJson: String,
        testUrl: String = DEFAULT_DELAY_TEST_URL
    ): Result<Long> {
        initCoreEnvironment(context)
        if (jniLoaded.get()) {
            return try {
                val delay = Libv2ray.measureOutboundDelay(configJson, testUrl)
                if (delay > 0L) {
                    Result.success(delay)
                } else {
                    Result.failure(IllegalStateException("Xray measureOutboundDelay returned $delay ms"))
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
        return Result.failure(IllegalStateException("Native Libv2ray.measureOutboundDelay requires Android runtime"))
    }

    /**
     * Queries and resets Xray-core's internal outbound traffic stats (`coreController.queryAllOutboundTrafficStats()`).
     * Format returned by AndroidLibXrayLite: `tag,direction,value;tag,direction,value;`
     */
    fun queryOutboundTrafficDelta(): OutboundTrafficDelta? {
        if (!jniLoaded.get()) return null
        val ctrl = coreController ?: return null
        if (!ctrl.isRunning) return null
        return try {
            val raw = ctrl.queryAllOutboundTrafficStats().orEmpty()
            var proxyUp = 0L
            var proxyDown = 0L
            var directUp = 0L
            var directDown = 0L

            if (raw.isNotBlank()) {
                for (entry in raw.split(";")) {
                    if (entry.isBlank()) continue
                    val parts = entry.split(",")
                    if (parts.size != 3) continue
                    val tag = parts[0].trim()
                    val direction = parts[1].trim()
                    val bytes = parts[2].trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    when {
                        tag == "proxy" && direction == "uplink" -> proxyUp += bytes
                        tag == "proxy" && direction == "downlink" -> proxyDown += bytes
                        tag == "direct" && direction == "uplink" -> directUp += bytes
                        tag == "direct" && direction == "downlink" -> directDown += bytes
                    }
                }
            }
            OutboundTrafficDelta(
                proxyUploadBytes = proxyUp,
                proxyDownloadBytes = proxyDown,
                directUploadBytes = directUp,
                directDownloadBytes = directDown
            )
        } catch (_: Throwable) {
            null
        }
    }
}
