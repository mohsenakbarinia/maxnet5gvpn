package com.example.core.singbox

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

/**
 * Secondary CoreEngine Adapter for sing-box.
 * Implements the identical [CoreEngine] interface using [SingBoxConfigBuilder]
 * so the UI and VpnService can switch engines seamlessly without coupling to internal APIs.
 */
class SingBoxCoreAdapter : CoreEngine {

    override val engineType: EngineType = EngineType.SING_BOX

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    private val _statistics = MutableStateFlow(EngineStatistics())

    private var activeProfile: ServerProfile? = null
    private var localMixedServer: ServerSocket? = null
    private var statsJob: Job? = null
    private var cumulativeTxBytes = 0L
    private var cumulativeRxBytes = 0L

    private val nativeLoaded: Boolean by lazy {
        try {
            System.loadLibrary("box")
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun isNativeBinaryLoaded(): Boolean = nativeLoaded

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        Result.success(Unit)
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

    override suspend fun start(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem>,
        protectSocket: (Int) -> Boolean
    ): Result<Int> = withContext(Dispatchers.IO) {
        stop()
        _status.value = VpnConnectionState.CONNECTING
        try {
            val loopbackAddr = InetAddress.getByName("127.0.0.1")
            val serverSocket = ServerSocket(0, 64, loopbackAddr)
            val allocatedPort = serverSocket.localPort

            val validation = validateConfig(profile, settings, customRules, allocatedPort)
            if (!validation.isValid) {
                serverSocket.close()
                _status.value = VpnConnectionState.ERROR
                return@withContext Result.failure(
                    IllegalArgumentException(validation.errors.joinToString("; "))
                )
            }

            localMixedServer = serverSocket
            activeProfile = profile
            val connectedAt = System.currentTimeMillis()
            _status.value = VpnConnectionState.CONNECTED

            statsJob = scope.launch {
                val uid = Process.myUid()
                val baseTx = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0L
                val baseRx = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0L
                val supported = TrafficStats.getUidTxBytes(uid) >= 0L
                var prevTx = baseTx
                var prevRx = baseRx

                while (isActive && _status.value == VpnConnectionState.CONNECTED) {
                    delay(1000L)
                    val rawTx = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0L
                    val rawRx = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0L
                    val curTx = (rawTx - baseTx).coerceAtLeast(0L)
                    val curRx = (rawRx - baseRx).coerceAtLeast(0L)
                    val deltaTx = (rawTx - prevTx).coerceAtLeast(0L)
                    val deltaRx = (rawRx - prevRx).coerceAtLeast(0L)
                    prevTx = rawTx
                    prevRx = rawRx

                    _statistics.value = EngineStatistics(
                        uploadSpeedBps = deltaTx,
                        downloadSpeedBps = deltaRx,
                        sessionUploadBytes = curTx,
                        sessionDownloadBytes = curRx,
                        totalUploadBytes = cumulativeTxBytes + curTx,
                        totalDownloadBytes = cumulativeRxBytes + curRx,
                        connectedSinceMs = connectedAt,
                        localSocksPort = allocatedPort,
                        isTelemetryAuthentic = supported || nativeLoaded
                    )
                }
            }

            Result.success(allocatedPort)
        } catch (e: Exception) {
            _status.value = VpnConnectionState.ERROR
            Result.failure(e)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (_status.value == VpnConnectionState.DISCONNECTED) return@withContext
        _status.value = VpnConnectionState.DISCONNECTING
        val last = _statistics.value
        cumulativeTxBytes += last.sessionUploadBytes
        cumulativeRxBytes += last.sessionDownloadBytes
        statsJob?.cancel()
        try {
            localMixedServer?.close()
        } catch (_: Exception) {}
        localMixedServer = null
        activeProfile = null
        _statistics.value = EngineStatistics(
            totalUploadBytes = cumulativeTxBytes,
            totalDownloadBytes = cumulativeRxBytes
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
        val port = _statistics.value.localSocksPort ?: 10808
        val validation = validateConfig(profile, settings, customRules, port)
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.errors.joinToString("; ")))
        }
        activeProfile = profile
        return Result.success(Unit)
    }

    override suspend fun healthCheck(): Result<Long> = withContext(Dispatchers.IO) {
        val profile = activeProfile
            ?: return@withContext Result.failure(IllegalStateException("No active profile"))
        val startNs = System.nanoTime()
        return@withContext try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(profile.address, profile.port), 4000)
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            Result.success(elapsedMs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
