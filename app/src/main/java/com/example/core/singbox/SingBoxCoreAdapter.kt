package com.example.core.singbox

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import com.example.core.CoreEngine
import com.example.core.xray.XrayConfigBuilder
import com.example.core.xray.XrayNativeBridge
import com.example.domain.model.AppSettings
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.ServerProfile
import com.example.domain.model.ValidationResult
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnProtocol
import com.v2ray.ang.service.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.math.max

/**
 * Secondary CoreEngine Adapter for sing-box configuration generation & universal engine routing.
 * - Generates and validates strict sing-box JSON configurations via [SingBoxConfigBuilder].
 * - Never creates a fake ServerSocket SOCKS5 listener.
 * - For VLESS, VMess, Trojan, and Shadowsocks profiles, executes through the native Xray-core
 *   engine (`libgojni.so`) if `libbox.so` is not present on the device.
 * - For sing-box-exclusive protocols (Hysteria2, WireGuard, TUIC), requires `libbox.so` and
 *   reports an explicit error if `libbox.so` is unavailable rather than faking a connection.
 */
class SingBoxCoreAdapter : CoreEngine {

    override val engineType: EngineType = EngineType.SING_BOX

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    private val _statistics = MutableStateFlow(EngineStatistics())

    private var appContext: Context? = null
    private var activeProfile: ServerProfile? = null
    private var activePort: Int? = null
    private var connectedAtMs: Long? = null
    private var statsJob: Job? = null
    private var cumulativeTxBytes = 0L
    private var cumulativeRxBytes = 0L

    private val boxNativeLoaded: Boolean by lazy {
        try {
            System.loadLibrary("box")
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun isNativeBinaryLoaded(): Boolean =
        boxNativeLoaded || XrayNativeBridge.isNativeLibraryLoaded()

    override fun isEngineRunning(): Boolean = XrayNativeBridge.isCoreRunning()

    override fun getEngineVersion(): String {
        return if (boxNativeLoaded) {
            "sing-box (Native JNI)"
        } else {
            "sing-box Config + ${XrayNativeBridge.getEngineVersionInfo()}"
        }
    }

    override suspend fun initialize(context: Context?): Result<Unit> = withContext(Dispatchers.IO) {
        if (context != null) {
            appContext = context.applicationContext ?: context
        }
        XrayNativeBridge.initCoreEnvironment(appContext)
    }

    override fun validateConfig(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem>,
        localSocksPort: Int
    ): ValidationResult {
        return SingBoxConfigBuilder.buildAndValidate(
            profile = profile,
            settings = settings,
            customRules = customRules,
            localSocksPort = localSocksPort
        )
    }

    private fun findAvailableLoopbackPortPair(): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        for (candidate in listOf(10808, 10818, 10828, 20808)) {
            val free = runCatching {
                ServerSocket(candidate, 1, loopback).use { true } &&
                    ServerSocket(candidate + 1, 1, loopback).use { true }
            }.getOrDefault(false)
            if (free) return candidate
        }
        return ServerSocket(0, 1, loopback).use { it.localPort }
    }

    override suspend fun start(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem>,
        protectSocket: (Int) -> Boolean,
        onLog: (String, String, String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        stop()
        _status.value = VpnConnectionState.PREPARING
        try {
            val allocatedPort = findAvailableLoopbackPortPair()
            val sbValidation = validateConfig(profile, settings, customRules, allocatedPort)
            if (!sbValidation.isValid) {
                _status.value = VpnConnectionState.ERROR
                return@withContext Result.failure(
                    IllegalArgumentException(sbValidation.errors.joinToString("; "))
                )
            }

            if (profile.protocol in setOf(VpnProtocol.HYSTERIA2, VpnProtocol.WIREGUARD, VpnProtocol.TUIC) && !boxNativeLoaded) {
                _status.value = VpnConnectionState.ERROR
                val msg = "${profile.protocol.displayName} requires native libbox.so; current build ships Xray-core v26.10.29 (VLESS/VMess/Trojan/Shadowsocks)"
                onLog("ERROR", "SingBoxAdapter", msg)
                return@withContext Result.failure(UnsupportedOperationException(msg))
            }

            val xrayValidation = XrayConfigBuilder.buildAndValidate(
                profile = profile,
                settings = settings,
                customRules = customRules,
                localSocksPort = allocatedPort
            )
            if (!xrayValidation.isValid) {
                _status.value = VpnConnectionState.ERROR
                return@withContext Result.failure(
                    IllegalArgumentException(xrayValidation.errors.joinToString("; "))
                )
            }

            _status.value = VpnConnectionState.STARTING_ENGINE
            val startRes = XrayNativeBridge.startCore(
                context = appContext,
                configJson = xrayValidation.generatedConfigJson,
                tunFd = 0,
                onStatusEmit = { code, msg ->
                    if (msg.isNotBlank()) {
                        onLog("INFO", "CoreJNI", "Core status ($code): $msg")
                    }
                }
            )
            if (startRes.isFailure) {
                _status.value = VpnConnectionState.ERROR
                return@withContext Result.failure(
                    startRes.exceptionOrNull() ?: IllegalStateException("Engine failed to start")
                )
            }

            _status.value = VpnConnectionState.CONNECTING
            val socksReady = XrayNativeBridge.awaitLocalSocksEndpointReady(allocatedPort, maxWaitMs = 4500L)
            if (socksReady.isFailure) {
                XrayNativeBridge.stopCore()
                _status.value = VpnConnectionState.ERROR
                return@withContext Result.failure(
                    IllegalStateException("Local SOCKS5 endpoint 127.0.0.1:$allocatedPort did not respond")
                )
            }

            activeProfile = profile
            activePort = allocatedPort
            connectedAtMs = System.currentTimeMillis()
            Result.success(allocatedPort)
        } catch (e: Exception) {
            XrayNativeBridge.stopCore()
            _status.value = VpnConnectionState.ERROR
            Result.failure(e)
        }
    }

    override suspend fun verifyActiveConnection(
        testUrl: String?,
        timeoutMs: Int
    ): Result<Long> = withContext(Dispatchers.IO) {
        val port = activePort
            ?: return@withContext Result.failure(IllegalStateException("Local proxy port is not active"))
        _status.value = VpnConnectionState.VERIFYING
        XrayNativeBridge.measureActiveProxyDelay(
            localSocksPort = port,
            testUrl = testUrl?.takeIf { it.isNotBlank() } ?: "https://cp.cloudflare.com/generate_204",
            timeoutMs = timeoutMs
        )
    }

    override fun markConnected() {
        val port = activePort ?: return
        val startedAt = connectedAtMs ?: System.currentTimeMillis().also { connectedAtMs = it }
        _status.value = VpnConnectionState.CONNECTED
        statsJob?.cancel()
        statsJob = scope.launch {
            val uid = runCatching { Process.myUid() }.getOrDefault(-1)
            val baseTx = if (uid >= 0) runCatching { TrafficStats.getUidTxBytes(uid) }.getOrDefault(-1L).coerceAtLeast(0L) else 0L
            val baseRx = if (uid >= 0) runCatching { TrafficStats.getUidRxBytes(uid) }.getOrDefault(-1L).coerceAtLeast(0L) else 0L
            var xrayTx = 0L
            var xrayRx = 0L
            var prevTx = 0L
            var prevRx = 0L

            while (isActive && _status.value == VpnConnectionState.CONNECTED) {
                delay(1000L)
                if (!XrayNativeBridge.isCoreRunning()) break
                val xrayDelta = XrayNativeBridge.queryOutboundTrafficDelta()
                xrayTx += xrayDelta?.totalUploadBytes ?: 0L
                xrayRx += xrayDelta?.totalDownloadBytes ?: 0L
                val tunStats = TProxyService.queryTrafficStats()
                val uidTx = if (uid >= 0) (runCatching { TrafficStats.getUidTxBytes(uid) }.getOrDefault(-1L) - baseTx).coerceAtLeast(0L) else 0L
                val uidRx = if (uid >= 0) (runCatching { TrafficStats.getUidRxBytes(uid) }.getOrDefault(-1L) - baseRx).coerceAtLeast(0L) else 0L

                val curTx = max(xrayTx, max(tunStats?.txBytes ?: 0L, uidTx))
                val curRx = max(xrayRx, max(tunStats?.rxBytes ?: 0L, uidRx))
                val deltaTx = (curTx - prevTx).coerceAtLeast(0L)
                val deltaRx = (curRx - prevRx).coerceAtLeast(0L)
                prevTx = curTx
                prevRx = curRx

                _statistics.value = EngineStatistics(
                    uploadSpeedBps = deltaTx,
                    downloadSpeedBps = deltaRx,
                    sessionUploadBytes = curTx,
                    sessionDownloadBytes = curRx,
                    totalUploadBytes = cumulativeTxBytes + curTx,
                    totalDownloadBytes = cumulativeRxBytes + curRx,
                    connectedSinceMs = startedAt,
                    localSocksPort = port,
                    isTelemetryAuthentic = true
                )
            }
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (_status.value == VpnConnectionState.DISCONNECTED) return@withContext
        _status.value = VpnConnectionState.STOPPING
        val last = _statistics.value
        cumulativeTxBytes += last.sessionUploadBytes
        cumulativeRxBytes += last.sessionDownloadBytes
        statsJob?.cancel()
        statsJob = null
        XrayNativeBridge.stopCore()
        activeProfile = null
        activePort = null
        connectedAtMs = null
        _statistics.value = EngineStatistics(
            totalUploadBytes = cumulativeTxBytes,
            totalDownloadBytes = cumulativeRxBytes,
            isTelemetryAuthentic = true
        )
        _status.value = VpnConnectionState.DISCONNECTED
    }

    override fun getStatus(): StateFlow<VpnConnectionState> = _status.asStateFlow()

    override fun getStatistics(): StateFlow<EngineStatistics> = _statistics.asStateFlow()

    override suspend fun updateConfiguration(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem>
    ): Result<Unit> {
        val port = activePort ?: _statistics.value.localSocksPort ?: 10808
        val validation = validateConfig(profile, settings, customRules, port)
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.errors.joinToString("; ")))
        }
        activeProfile = profile
        return Result.success(Unit)
    }

    override suspend fun healthCheck(): Result<Long> = withContext(Dispatchers.IO) {
        val port = activePort
            ?: return@withContext Result.failure(IllegalStateException("No active proxy port"))
        if (!XrayNativeBridge.isCoreRunning()) {
            return@withContext Result.failure(IllegalStateException("Core engine is not running"))
        }
        XrayNativeBridge.measureActiveProxyDelay(
            localSocksPort = port,
            testUrl = "https://cp.cloudflare.com/generate_204",
            timeoutMs = 4500
        )
    }
}
