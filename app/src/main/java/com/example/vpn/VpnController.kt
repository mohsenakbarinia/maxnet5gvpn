package com.example.vpn

import com.example.core.CoreEngine
import com.example.core.singbox.SingBoxCoreAdapter
import com.example.core.xray.XrayCoreAdapter
import com.example.domain.model.AppSettings
import com.example.domain.model.DiagnosticSnapshot
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
import kotlinx.coroutines.launch

/**
 * Coordinates the Dual-Engine adapters (Xray-core & sing-box), TUN interface status,
 * 60-second real traffic history, and exponential-backoff reconnection.
 */
object VpnController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val xrayEngine: CoreEngine = XrayCoreAdapter()
    private val singBoxEngine: CoreEngine = SingBoxCoreAdapter()

    private var currentEngine: CoreEngine = xrayEngine

    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _statistics = MutableStateFlow(EngineStatistics())
    val statistics: StateFlow<EngineStatistics> = _statistics.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticSnapshot())
    val diagnostics: StateFlow<DiagnosticSnapshot> = _diagnostics.asStateFlow()

    // 60-second rolling real traffic speed history (Pairs of uploadBps to downloadBps)
    private val _speedHistory = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    val speedHistory: StateFlow<List<Pair<Long, Long>>> = _speedHistory.asStateFlow()

    private var statsCollectorJob: Job? = null
    private var activeProfile: ServerProfile? = null
    private var activeSettings: AppSettings = AppSettings()
    private var activeRules: List<RoutingRuleItem> = emptyList()
    private var tunEstablished: Boolean = false

    fun getEngine(type: EngineType): CoreEngine {
        return when (type) {
            EngineType.XRAY_CORE -> xrayEngine
            EngineType.SING_BOX -> singBoxEngine
        }
    }

    fun validateConfiguration(
        profile: ServerProfile,
        settings: AppSettings,
        rules: List<RoutingRuleItem>
    ): ValidationResult {
        return getEngine(settings.activeEngine).validateConfig(profile, settings, rules)
    }

    suspend fun startSession(
        profile: ServerProfile,
        settings: AppSettings,
        rules: List<RoutingRuleItem>,
        tunActive: Boolean,
        protectSocket: (Int) -> Boolean = { true },
        onLog: (String, String, String) -> Unit = { _, _, _ -> }
    ): Result<Int> {
        val selectedEngine = getEngine(settings.activeEngine)
        currentEngine = selectedEngine
        activeProfile = profile
        activeSettings = settings
        activeRules = rules
        tunEstablished = tunActive

        _connectionState.value = VpnConnectionState.CONNECTING
        updateDiagnosticSnapshot(lastError = null)

        selectedEngine.initialize()
        val startResult = selectedEngine.start(profile, settings, rules, protectSocket)

        return startResult.fold(
            onSuccess = { localPort ->
                _connectionState.value = VpnConnectionState.CONNECTED
                onLog(
                    "INFO",
                    "VpnController",
                    "Started ${selectedEngine.engineType.displayName} on 127.0.0.1:$localPort for '${profile.remarks}'"
                )
                observeEngineTelemetry(selectedEngine)
                updateDiagnosticSnapshot(localPort = localPort, lastError = null)
                Result.success(localPort)
            },
            onFailure = { error ->
                _connectionState.value = VpnConnectionState.ERROR
                val msg = error.message ?: "Engine failed to start"
                onLog("ERROR", "VpnController", "Start failed: $msg")
                updateDiagnosticSnapshot(localPort = null, lastError = msg)
                Result.failure(error)
            }
        )
    }

    private fun observeEngineTelemetry(engine: CoreEngine) {
        statsCollectorJob?.cancel()
        _speedHistory.value = emptyList()
        statsCollectorJob = scope.launch {
            engine.getStatistics().collect { stat ->
                _statistics.value = stat
                val updatedHistory = (_speedHistory.value + (stat.uploadSpeedBps to stat.downloadSpeedBps)).takeLast(60)
                _speedHistory.value = updatedHistory
            }
        }
    }

    suspend fun stopSession(onLog: (String, String, String) -> Unit = { _, _, _ -> }) {
        if (_connectionState.value == VpnConnectionState.DISCONNECTED) return
        _connectionState.value = VpnConnectionState.DISCONNECTING
        statsCollectorJob?.cancel()
        currentEngine.stop()
        tunEstablished = false
        _connectionState.value = VpnConnectionState.DISCONNECTED
        _statistics.value = EngineStatistics()
        _speedHistory.value = emptyList()
        onLog("INFO", "VpnController", "VPN tunnel & core engine stopped cleanly")
        updateDiagnosticSnapshot(localPort = null, lastError = null)
    }

    suspend fun reconnectWithExponentialBackoff(
        onLog: (String, String, String) -> Unit = { _, _, _ -> }
    ) {
        val profile = activeProfile ?: return
        if (_connectionState.value != VpnConnectionState.CONNECTED &&
            _connectionState.value != VpnConnectionState.RECONNECTING
        ) return

        _connectionState.value = VpnConnectionState.RECONNECTING
        var delayMs = 1000L
        for (attempt in 1..3) {
            onLog("INFO", "VpnController", "Network switch detected; reconnecting (attempt $attempt/3)…")
            currentEngine.stop()
            val res = currentEngine.start(profile, activeSettings, activeRules)
            if (res.isSuccess) {
                _connectionState.value = VpnConnectionState.CONNECTED
                observeEngineTelemetry(currentEngine)
                updateDiagnosticSnapshot(localPort = res.getOrNull(), lastError = null)
                onLog("INFO", "VpnController", "Reconnected successfully on attempt $attempt")
                return
            }
            delay(delayMs)
            delayMs *= 2
        }
        _connectionState.value = VpnConnectionState.ERROR
        updateDiagnosticSnapshot(localPort = null, lastError = "Failed to reconnect after 3 attempts")
    }

    private fun updateDiagnosticSnapshot(localPort: Int? = _statistics.value.localSocksPort, lastError: String?) {
        val nativeStatus = if (currentEngine.isNativeBinaryLoaded()) {
            "Native JNI Core + Android VpnService TUN"
        } else {
            "Android VpnService TUN + Local Loopback SOCKS5 Adapter (127.0.0.1)"
        }
        _diagnostics.value = DiagnosticSnapshot(
            connectionState = _connectionState.value,
            activeEngine = currentEngine.engineType,
            activeProfileRemarks = activeProfile?.remarks ?: "None",
            coreInitialized = _connectionState.value == VpnConnectionState.CONNECTED,
            tunInterfaceActive = tunEstablished && _connectionState.value == VpnConnectionState.CONNECTED,
            localLoopbackPort = localPort,
            bridgeArchitectureStatus = nativeStatus,
            lastError = lastError
        )
    }
}
