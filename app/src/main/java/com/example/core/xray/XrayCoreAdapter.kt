package com.example.core.xray

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import com.example.core.CoreEngine
import com.example.domain.model.AppSettings
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.ServerProfile
import com.example.domain.model.ValidationResult
import com.example.domain.model.VpnConnectionState
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.max

/**
 * Production CoreEngine Adapter for Xray-core v26.10.29 (AndroidLibXrayLite / libgojni.so).
 * - Validates and builds complete Xray JSON configurations via [XrayConfigBuilder].
 * - Starts a real Xray-core instance via [XrayNativeBridge] (`libv2ray.CoreController`).
 * - Verifies that Xray-core's local SOCKS5 inbound on `127.0.0.1:<localSocksPort>` is listening
 *   and completes an RFC 1928 SOCKS5 greeting.
 * - Verifies end-to-end encrypted proxy connectivity via HTTP 204 delay measurement.
 * - Queries real traffic statistics from Xray's `StatsService` (`queryAllOutboundTrafficStats`),
 *   `TProxyService` (`libhev-socks5-tunnel.so`), and Android UID `TrafficStats`.
 */
class XrayCoreAdapter(
    private val verifyReachabilityOnStart: Boolean = false,
    private val defaultVerificationUrl: String = "https://cp.cloudflare.com/generate_204"
) : CoreEngine {

    override val engineType: EngineType = EngineType.XRAY_CORE

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

    override fun isNativeBinaryLoaded(): Boolean = XrayNativeBridge.isNativeLibraryLoaded()

    override fun isEngineRunning(): Boolean = XrayNativeBridge.isCoreRunning()

    override fun getEngineVersion(): String = XrayNativeBridge.getEngineVersionInfo()

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
        return XrayConfigBuilder.buildAndValidate(
            profile = profile,
            settings = settings,
            customRules = customRules,
            localSocksPort = localSocksPort
        )
    }

    private suspend fun findAvailableLoopbackPortPair(preferredPort: Int? = null): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        if (preferredPort != null && preferredPort in 1024..65530) {
            for (attempt in 1..3) {
                val socksFree = runCatching {
                    ServerSocket(preferredPort, 1, loopback).use { true }
                }.getOrDefault(false)
                val httpFree = runCatching {
                    ServerSocket(preferredPort + 1, 1, loopback).use { true }
                }.getOrDefault(false)
                if (socksFree && httpFree) return preferredPort
                if (attempt < 3) delay(40L)
            }
        }
        for (candidate in listOf(10808, 10818, 10828, 20808, 30808)) {
            val socksFree = runCatching {
                ServerSocket(candidate, 1, loopback).use { true }
            }.getOrDefault(false)
            val httpFree = runCatching {
                ServerSocket(candidate + 1, 1, loopback).use { true }
            }.getOrDefault(false)
            if (socksFree && httpFree) return candidate
        }
        return ServerSocket(0, 1, loopback).use { it.localPort }
    }

    override suspend fun start(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem>,
        protectSocket: (Int) -> Boolean,
        onLog: (String, String, String) -> Unit,
        preferredLocalSocksPort: Int?
    ): Result<Int> = withContext(Dispatchers.IO) {
        stop()
        _status.value = VpnConnectionState.PREPARING

        try {
            val allocatedPort = findAvailableLoopbackPortPair(preferredLocalSocksPort)
            val validation = validateConfig(profile, settings, customRules, allocatedPort)
            if (!validation.isValid) {
                _status.value = VpnConnectionState.ERROR
                val errText = validation.errors.joinToString("; ")
                onLog("ERROR", "XrayCore", "Configuration validation failed: $errText")
                return@withContext Result.failure(IllegalArgumentException(errText))
            }

            if (verifyReachabilityOnStart) {
                onLog("INFO", "XrayCore", "Checking TCP reachability to ${profile.address}:${profile.port}…")
                Socket().use { probe ->
                    probe.tcpNoDelay = true
                    probe.connect(InetSocketAddress(profile.address, profile.port), 4500)
                }
            }

            _status.value = VpnConnectionState.STARTING_ENGINE
            onLog("INFO", "XrayCore", "Starting ${getEngineVersion()} on 127.0.0.1:$allocatedPort…")

            val coreStart = XrayNativeBridge.startCore(
                context = appContext,
                configJson = validation.generatedConfigJson,
                tunFd = 0,
                onStatusEmit = { code, msg ->
                    if (msg.isNotBlank()) {
                        onLog("INFO", "XrayCoreJNI", "Core status ($code): $msg")
                    }
                }
            )

            if (coreStart.isFailure) {
                _status.value = VpnConnectionState.ERROR
                val err = coreStart.exceptionOrNull() ?: IllegalStateException("Xray-core failed to start")
                onLog("ERROR", "XrayCore", "Engine start error: ${err.message}")
                return@withContext Result.failure(err)
            }

            _status.value = VpnConnectionState.CONNECTING
            val socksReady = XrayNativeBridge.awaitLocalSocksEndpointReady(allocatedPort, maxWaitMs = 4500L)
            if (socksReady.isFailure) {
                XrayNativeBridge.stopCore()
                _status.value = VpnConnectionState.ERROR
                val msg = socksReady.exceptionOrNull()?.message
                    ?: "Xray-core local SOCKS5 inbound on 127.0.0.1:$allocatedPort did not become ready"
                onLog("ERROR", "XrayCore", msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            activeProfile = profile
            activePort = allocatedPort
            connectedAtMs = System.currentTimeMillis()

            onLog(
                "INFO",
                "XrayCore",
                "Xray-core SOCKS5 inbound verified on 127.0.0.1:$allocatedPort (HTTP inbound: ${allocatedPort + 1})"
            )
            Result.success(allocatedPort)
        } catch (e: Exception) {
            XrayNativeBridge.stopCore()
            _status.value = VpnConnectionState.ERROR
            onLog("ERROR", "XrayCore", "Failed to start Xray-core: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun verifyActiveConnection(
        testUrl: String?,
        timeoutMs: Int
    ): Result<Long> = withContext(Dispatchers.IO) {
        val port = activePort
            ?: return@withContext Result.failure(IllegalStateException("Xray-core local SOCKS5 port is not active"))
        if (!XrayNativeBridge.isCoreRunning()) {
            return@withContext Result.failure(IllegalStateException("Xray-core engine is not running"))
        }
        _status.value = VpnConnectionState.VERIFYING
        val targetUrl = testUrl?.takeIf { it.isNotBlank() } ?: defaultVerificationUrl
        XrayNativeBridge.measureActiveProxyDelay(
            localSocksPort = port,
            testUrl = targetUrl,
            timeoutMs = timeoutMs
        )
    }

    override fun markConnected() {
        val port = activePort ?: return
        val startedAt = connectedAtMs ?: System.currentTimeMillis().also { connectedAtMs = it }
        _status.value = VpnConnectionState.CONNECTED
        startTelemetryCollection(port, startedAt)
    }

    private fun startTelemetryCollection(allocatedPort: Int, connectedAt: Long) {
        statsJob?.cancel()
        statsJob = scope.launch {
            val uid = runCatching { Process.myUid() }.getOrDefault(-1)
            val baseUidTx = if (uid >= 0) runCatching { TrafficStats.getUidTxBytes(uid) }.getOrDefault(-1L).coerceAtLeast(0L) else 0L
            val baseUidRx = if (uid >= 0) runCatching { TrafficStats.getUidRxBytes(uid) }.getOrDefault(-1L).coerceAtLeast(0L) else 0L

            var xraySessionTx = 0L
            var xraySessionRx = 0L
            var prevSessionTx = 0L
            var prevSessionRx = 0L

            while (isActive && _status.value == VpnConnectionState.CONNECTED) {
                delay(1000L)
                if (!XrayNativeBridge.isCoreRunning()) {
                    break
                }

                // 1. Query Xray-core internal StatsService delta (uplink to proxy, downlink from proxy)
                val xrayDelta = XrayNativeBridge.queryOutboundTrafficDelta()
                xraySessionTx += xrayDelta?.totalUploadBytes ?: 0L
                xraySessionRx += xrayDelta?.totalDownloadBytes ?: 0L

                // 2. Query TProxyService (hev-socks5-tunnel) cumulative TUN stats if active
                val tunStats = TProxyService.queryTrafficStats()
                val tunTx = tunStats?.txBytes ?: 0L
                val tunRx = tunStats?.rxBytes ?: 0L

                // 3. Query Android UID TrafficStats as a supplemental OS-level counter
                val uidTx = if (uid >= 0) {
                    (runCatching { TrafficStats.getUidTxBytes(uid) }.getOrDefault(-1L) - baseUidTx).coerceAtLeast(0L)
                } else 0L
                val uidRx = if (uid >= 0) {
                    (runCatching { TrafficStats.getUidRxBytes(uid) }.getOrDefault(-1L) - baseUidRx).coerceAtLeast(0L)
                } else 0L

                val currentSessionTx = max(xraySessionTx, max(tunTx, uidTx))
                val currentSessionRx = max(xraySessionRx, max(tunRx, uidRx))

                val speedUp = (currentSessionTx - prevSessionTx).coerceAtLeast(0L)
                val speedDown = (currentSessionRx - prevSessionRx).coerceAtLeast(0L)
                prevSessionTx = currentSessionTx
                prevSessionRx = currentSessionRx

                _statistics.value = EngineStatistics(
                    uploadSpeedBps = speedUp,
                    downloadSpeedBps = speedDown,
                    sessionUploadBytes = currentSessionTx,
                    sessionDownloadBytes = currentSessionRx,
                    totalUploadBytes = cumulativeTxBytes + currentSessionTx,
                    totalDownloadBytes = cumulativeRxBytes + currentSessionRx,
                    connectedSinceMs = connectedAt,
                    localSocksPort = allocatedPort,
                    isTelemetryAuthentic = true
                )
            }
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (_status.value == VpnConnectionState.DISCONNECTED) return@withContext
        _status.value = VpnConnectionState.STOPPING
        val lastStats = _statistics.value
        cumulativeTxBytes += lastStats.sessionUploadBytes
        cumulativeRxBytes += lastStats.sessionDownloadBytes

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
            ?: return@withContext Result.failure(IllegalStateException("No active Xray-core SOCKS5 port"))
        if (!XrayNativeBridge.isCoreRunning()) {
            return@withContext Result.failure(IllegalStateException("Xray-core native loop is not running"))
        }
        val socksOk = XrayNativeBridge.awaitLocalSocksEndpointReady(port, maxWaitMs = 1500L)
        if (socksOk.isFailure) {
            return@withContext Result.failure(
                IllegalStateException("Local SOCKS5 endpoint 127.0.0.1:$port is unresponsive")
            )
        }
        XrayNativeBridge.measureActiveProxyDelay(
            localSocksPort = port,
            testUrl = defaultVerificationUrl,
            timeoutMs = 4500
        )
    }
}
