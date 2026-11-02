package com.example.vpn

import android.content.Context
import com.example.core.CoreEngine
import com.example.core.autovpn.AutoFreeVpnManager
import com.example.core.autovpn.AutoVpnCacheStore
import com.example.core.autovpn.ConnectionOwnership
import com.example.core.autovpn.VpnAppMode
import com.example.core.resilience.ConnectivityPath
import com.example.core.resilience.EndpointIdHasher
import com.example.core.resilience.FailureClassifier
import com.example.core.resilience.FailureRecord
import com.example.core.resilience.PathRegistry
import com.example.core.resilience.PathTrustLevel
import com.example.core.resilience.PersianConnectivityStrings
import com.example.core.resilience.ProbePlane
import com.example.core.resilience.ResilienceJsonSerializer
import com.example.core.resilience.ResilienceUiSnapshot
import com.example.core.resilience.SmartGlobalConnectivityEngine
import com.example.core.singbox.SingBoxCoreAdapter
import com.example.core.xray.XrayCoreAdapter
import com.example.core.xray.XrayNativeBridge
import com.example.domain.model.AppSettings
import com.example.domain.model.DiagnosticSnapshot
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.ServerProfile
import com.example.domain.model.ValidationResult
import com.example.domain.model.VpnConnectionState
import com.example.security.LogRedactor
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

/**
 * Coordinates the Dual-Engine adapters (Xray-core v26.10.29 & sing-box), TUN-to-SOCKS5
 * native packet forwarding (`libhev-socks5-tunnel.so`), 9-stage connection state machine,
 * end-to-end HTTP 204 proxy verification, 60-second real traffic history,
 * exponential-backoff reconnection, Single-Engine Connection Ownership (`NONE` / `FREE` / `PERSONAL`),
 * the Intelligent Auto Free VPN Manager, and the Smart Global Connectivity & National Internet Resilience Engine.
 */
object VpnController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val xrayEngine: CoreEngine = XrayCoreAdapter()
    private val singBoxEngine: CoreEngine = SingBoxCoreAdapter()

    private var currentEngine: CoreEngine = xrayEngine

    val connectivityEngine: SmartGlobalConnectivityEngine = SmartGlobalConnectivityEngine()
    val resilienceState: StateFlow<ResilienceUiSnapshot> = connectivityEngine.uiSnapshot

    @Volatile
    private var _autoFreeVpnManager: AutoFreeVpnManager? = null

    fun getOrCreateAutoFreeVpnManager(context: Context? = null): AutoFreeVpnManager {
        val existing = _autoFreeVpnManager
        if (existing != null) return existing
        synchronized(this) {
            val secondCheck = _autoFreeVpnManager
            if (secondCheck != null) return secondCheck
            val created = AutoFreeVpnManager(
                context = context?.applicationContext,
                cacheStore = AutoVpnCacheStore(context?.applicationContext)
            )
            _autoFreeVpnManager = created
            return created
        }
    }

    fun resetAutoFreeVpnManagerForTests() {
        _autoFreeVpnManager?.resetState()
        _ownershipState.value = ConnectionOwnership.NONE
        _activeFreeProfile.value = null
        _selectedVpnMode.value = VpnAppMode.FREE_VPN
        activeAllocatedSocksPort = null
        connectivityEngine.failureLog.clear()
        connectivityEngine.circuitBreakerRegistry.resetAll()
    }

    fun syncResilienceSettings(context: Context? = activeContext, settings: AppSettings = activeSettings) {
        if (context != null) {
            connectivityEngine.initializeAssets(context)
        }
        connectivityEngine.updateConfig(ResilienceJsonSerializer.toConnectivityConfig(settings))
        connectivityEngine.updateAuthorizedRelays(
            ResilienceJsonSerializer.parseAuthorizedRelays(settings.authorizedRelaysJson)
        )
    }

    private val _selectedVpnMode = MutableStateFlow(VpnAppMode.FREE_VPN)
    val selectedVpnMode: StateFlow<VpnAppMode> = _selectedVpnMode.asStateFlow()

    private val _ownershipState = MutableStateFlow(ConnectionOwnership.NONE)
    val ownershipState: StateFlow<ConnectionOwnership> = _ownershipState.asStateFlow()

    private val _activeFreeProfile = MutableStateFlow<ServerProfile?>(null)
    val activeFreeProfile: StateFlow<ServerProfile?> = _activeFreeProfile.asStateFlow()

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
    private var healthMonitorJob: Job? = null
    private var activeContext: Context? = null
    private var activeProfile: ServerProfile? = null
    private var activeSettings: AppSettings = AppSettings()
    private var activeRules: List<RoutingRuleItem> = emptyList()
    private var activeAllocatedSocksPort: Int? = null
    private var activeConnectivityPath: ConnectivityPath? = null
    private var tunEstablished: Boolean = false
    private var tunForwarderRunning: Boolean = false
    private var lastVerifiedProxyLatencyMs: Long? = null
    private var activeVerificationUrl: String? = null

    fun setSelectedVpnMode(mode: VpnAppMode) {
        _selectedVpnMode.value = mode
    }

    fun setActiveFreeProfile(profile: ServerProfile?) {
        _activeFreeProfile.value = profile
    }

    /**
     * Switches between [VpnAppMode.FREE_VPN] and [VpnAppMode.MY_VPN].
     * Enforces single-engine connection ownership:
     * - When switching from Free VPN to My VPN: stops Free VPN cleanly and releases active Free configuration.
     * - When switching from My VPN to Free VPN: stops Personal VPN cleanly while keeping Personal database untouched.
     */
    suspend fun switchMode(
        newMode: VpnAppMode,
        onLog: (String, String, String) -> Unit = { _, _, _ -> }
    ) {
        val previousMode = _selectedVpnMode.value
        _selectedVpnMode.value = newMode
        val currentOwner = _ownershipState.value

        if (newMode == VpnAppMode.MY_VPN && currentOwner == ConnectionOwnership.FREE) {
            onLog("INFO", "VpnController", "Switching from FREE VPN to MY VPN: stopping active Free VPN session cleanly")
            _autoFreeVpnManager?.stopContinuousOptimizationAndResetToIdle()
            stopSession(onLog)
            _activeFreeProfile.value = null
        } else if (newMode == VpnAppMode.FREE_VPN && currentOwner == ConnectionOwnership.PERSONAL) {
            onLog("INFO", "VpnController", "Switching from MY VPN to FREE VPN: stopping active Personal VPN session cleanly")
            stopSession(onLog)
        } else if (previousMode != newMode && _connectionState.value != VpnConnectionState.DISCONNECTED) {
            stopSession(onLog)
        }
    }

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

    /**
     * Executes the complete 9-state connection pipeline:
     * 1. PREPARING: Validate server profile & generate Xray JSON config.
     * 2. STARTING_ENGINE: Initialize & start Xray-core (`libgojni.so`).
     * 3. CONNECTING: Wait until local SOCKS5 endpoint `127.0.0.1:<localPort>` responds to RFC 1928 handshake,
     *    then establish Android VPN TUN interface and start `TProxyService` (`libhev-socks5-tunnel.so`).
     * 4. VERIFYING: Perform real HTTP 204 connectivity verification through the encrypted proxy tunnel.
     * 5. CONNECTED: Published ONLY if all previous stages succeed.
     */
    suspend fun startSession(
        context: Context? = null,
        profile: ServerProfile,
        settings: AppSettings,
        rules: List<RoutingRuleItem>,
        ownership: ConnectionOwnership = if (profile.id < 0L || profile.groupName == AutoVpnCacheStore.AUTO_CACHE_GROUP) {
            ConnectionOwnership.FREE
        } else {
            ConnectionOwnership.PERSONAL
        },
        selectedConnectivityPath: ConnectivityPath? = null,
        establishTunAndForwarder: suspend (Int) -> Boolean = { true },
        protectSocket: (Int) -> Boolean = { true },
        verifyConnectivity: Boolean = true,
        verificationUrl: String? = null,
        onStateChanged: (VpnConnectionState) -> Unit = {},
        onLog: (String, String, String) -> Unit = { _, _, _ -> }
    ): Result<Int> {
        healthMonitorJob?.cancel()
        statsCollectorJob?.cancel()

        // Stop any running engine process; keep activeAllocatedSocksPort so zero-TUN-teardown can reuse it
        if (_connectionState.value != VpnConnectionState.DISCONNECTED &&
            _connectionState.value != VpnConnectionState.PREPARING
        ) {
            currentEngine.stop()
        }

        val selectedEngine = getEngine(settings.activeEngine)
        currentEngine = selectedEngine
        activeContext = context?.applicationContext ?: context
        activeProfile = profile
        activeSettings = settings
        activeRules = rules
        lastVerifiedProxyLatencyMs = null
        activeVerificationUrl = verificationUrl

        syncResilienceSettings(activeContext, settings)

        val trustLevel = if (ownership == ConnectionOwnership.FREE) {
            PathTrustLevel.UNTRUSTED
        } else {
            PathTrustLevel.TRUSTED_USER
        }
        val activePath = selectedConnectivityPath ?: PathRegistry.buildDirectPath(profile, trustLevel)
        activeConnectivityPath = activePath
        val relays = ResilienceJsonSerializer.parseAuthorizedRelays(settings.authorizedRelaysJson)
        val effectiveProfile = if (settings.resilienceMasterEnabled && activePath.viaRelayId != null) {
            PathRegistry.applyPathOverrides(profile, activePath, relays)
        } else {
            profile
        }

        _ownershipState.value = ownership
        if (ownership == ConnectionOwnership.FREE) {
            _activeFreeProfile.value = profile
        } else {
            _activeFreeProfile.value = null
        }

        setState(VpnConnectionState.PREPARING, onStateChanged)
        updateDiagnosticSnapshot(localPort = null, lastError = null)

        val preValidation = selectedEngine.validateConfig(effectiveProfile, settings, rules)
        if (!preValidation.isValid) {
            val rawErr = preValidation.errors.joinToString("; ")
            val friendly = formatUserFriendlyError(
                rawError = rawErr,
                languageCode = settings.languageCode
            )
            recordResilienceFailure(effectiveProfile, activePath, rawErr, "PREPARING")
            _ownershipState.value = ConnectionOwnership.NONE
            setState(VpnConnectionState.ERROR, onStateChanged)
            onLog("ERROR", "VpnController", "Configuration invalid: $friendly")
            updateDiagnosticSnapshot(localPort = null, lastError = friendly)
            return Result.failure(IllegalArgumentException(friendly))
        }

        val initRes = selectedEngine.initialize(activeContext)
        if (initRes.isFailure) {
            val rawMsg = initRes.exceptionOrNull()?.message ?: "Failed to initialize Xray-core assets"
            val friendly = formatUserFriendlyError(rawMsg, settings.languageCode)
            recordResilienceFailure(effectiveProfile, activePath, rawMsg, "INITIALIZE")
            _ownershipState.value = ConnectionOwnership.NONE
            setState(VpnConnectionState.ERROR, onStateChanged)
            onLog("ERROR", "VpnController", friendly)
            updateDiagnosticSnapshot(localPort = null, lastError = friendly)
            return Result.failure(initRes.exceptionOrNull() ?: IllegalStateException(friendly))
        }

        setState(VpnConnectionState.STARTING_ENGINE, onStateChanged)
        updateDiagnosticSnapshot(localPort = null, lastError = null)

        val startResult = selectedEngine.start(
            profile = effectiveProfile,
            settings = settings,
            customRules = rules,
            protectSocket = protectSocket,
            onLog = onLog,
            preferredLocalSocksPort = activeAllocatedSocksPort
        )

        if (startResult.isFailure) {
            val err = startResult.exceptionOrNull() ?: IllegalStateException("Engine failed to start")
            val rawMsg = err.message ?: "Engine start failed"
            val friendly = formatUserFriendlyError(rawMsg, settings.languageCode)
            recordResilienceFailure(effectiveProfile, activePath, rawMsg, "STARTING_ENGINE")
            _ownershipState.value = ConnectionOwnership.NONE
            setState(VpnConnectionState.ERROR, onStateChanged)
            onLog("ERROR", "VpnController", "Engine start failed: $friendly")
            updateDiagnosticSnapshot(localPort = null, lastError = friendly)
            return Result.failure(IllegalStateException(friendly, err))
        }

        val localPort = startResult.getOrThrow()
        activeAllocatedSocksPort = localPort

        // Stage: CONNECTING (establish TUN interface + start hev-socks5-tunnel packet forwarder)
        setState(VpnConnectionState.CONNECTING, onStateChanged)
        updateDiagnosticSnapshot(localPort = localPort, lastError = null)

        val tunReady = try {
            establishTunAndForwarder(localPort)
        } catch (e: Exception) {
            onLog("ERROR", "VpnController", "TUN setup exception: ${e.message}")
            false
        }

        if (!tunReady) {
            selectedEngine.stop()
            _ownershipState.value = ConnectionOwnership.NONE
            val friendly = formatUserFriendlyError(
                "TUN interface or packet forwarder failed to initialize",
                settings.languageCode
            )
            recordResilienceFailure(effectiveProfile, activePath, "TUN setup failed", "CONNECTING")
            setState(VpnConnectionState.ERROR, onStateChanged)
            onLog("ERROR", "VpnController", friendly)
            updateDiagnosticSnapshot(localPort = null, lastError = friendly)
            return Result.failure(IllegalStateException(friendly))
        }

        tunEstablished = true
        tunForwarderRunning = TProxyService.isTun2SocksRunning()

        // Stage: VERIFYING (perform real proxy connectivity verification through Xray-core)
        if (verifyConnectivity) {
            setState(VpnConnectionState.VERIFYING, onStateChanged)
            updateDiagnosticSnapshot(localPort = localPort, lastError = null)
            onLog("INFO", "VpnController", "Verifying real proxy connectivity through 127.0.0.1:$localPort…")

            val verifyRes = selectedEngine.verifyActiveConnection(
                testUrl = verificationUrl,
                timeoutMs = 6000
            )
            if (verifyRes.isFailure) {
                val rawVerifyErr = verifyRes.exceptionOrNull()?.message
                    ?: "Proxy connectivity verification failed (HTTP 204 check timed out)"
                val friendly = formatUserFriendlyError(rawVerifyErr, settings.languageCode)
                onLog("ERROR", "VpnController", "Verification failed: $friendly")
                recordResilienceFailure(effectiveProfile, activePath, rawVerifyErr, "VERIFYING")
                TProxyService.stopTun2Socks()
                selectedEngine.stop()
                tunEstablished = false
                tunForwarderRunning = false
                _ownershipState.value = ConnectionOwnership.NONE
                setState(VpnConnectionState.ERROR, onStateChanged)
                updateDiagnosticSnapshot(localPort = null, lastError = friendly)
                return Result.failure(IllegalStateException(friendly, verifyRes.exceptionOrNull()))
            }

            lastVerifiedProxyLatencyMs = verifyRes.getOrNull()
            if (settings.resilienceMasterEnabled) {
                val statsSnap = selectedEngine.getStatistics().value
                connectivityEngine.verifyEstablishedTunnel(
                    path = activePath,
                    localSocksPort = localPort,
                    rxBytesDelta = statsSnap.sessionDownloadBytes,
                    txBytesDelta = statsSnap.sessionUploadBytes
                )
            }
            onLog(
                "INFO",
                "VpnController",
                "Proxy connectivity verified (${lastVerifiedProxyLatencyMs}ms HTTP 204 delay)"
            )
        }

        // Stage: CONNECTED (all checks passed!)
        selectedEngine.markConnected()
        connectivityEngine.circuitBreakerRegistry.recordSuccess(activePath.pathId)
        if (ownership == ConnectionOwnership.FREE && lastVerifiedProxyLatencyMs != null) {
            _activeFreeProfile.value = profile.copy(lastLatencyMs = lastVerifiedProxyLatencyMs)
        }
        setState(VpnConnectionState.CONNECTED, onStateChanged)
        val viaRelayTag = activePath.viaRelayId?.let { " [via AuthorizedRelay]" } ?: ""
        onLog(
            "INFO",
            "VpnController",
            "Connected '${profile.remarks}' [${ownership.name}]$viaRelayTag via ${selectedEngine.getEngineVersion()} on 127.0.0.1:$localPort"
        )
        observeEngineTelemetry(selectedEngine)
        startHealthMonitor(selectedEngine, localPort, onLog)
        updateDiagnosticSnapshot(localPort = localPort, lastError = null)
        return Result.success(localPort)
    }

    private fun recordResilienceFailure(
        profile: ServerProfile,
        path: ConnectivityPath,
        rawError: String,
        stageLabel: String
    ) {
        if (!activeSettings.resilienceMasterEnabled) return
        val category = FailureClassifier.classifyVpnCoreError(rawError)
        val assessment = connectivityEngine.getLatestAssessment()
        connectivityEngine.circuitBreakerRegistry.recordFailure(
            pathId = path.pathId,
            category = category,
            transportKind = assessment.transportKind
        )
        val detail = LogRedactor.redactDiagnostics("[$stageLabel] $rawError", listOf(profile.credential))
        connectivityEngine.failureLog.record(
            FailureRecord(
                timestampEpochMs = System.currentTimeMillis(),
                networkType = assessment.transportKind,
                nationalInternetState = assessment.state,
                nationalStateConfidence = assessment.confidence,
                domesticReachable = assessment.domesticReachability.state,
                endpointId = EndpointIdHasher.hashEndpoint(profile.address, profile.port, path.transportVariant),
                transport = path.transportVariant,
                portBucket = EndpointIdHasher.portBucket(profile.port),
                failureCategory = category,
                rttMs = null,
                recoveryActionTaken = stageLabel,
                sanitizedDetail = detail
            )
        )
    }

    private fun setState(
        newState: VpnConnectionState,
        onStateChanged: (VpnConnectionState) -> Unit = {}
    ) {
        _connectionState.value = newState
        onStateChanged(newState)
    }

    private fun observeEngineTelemetry(engine: CoreEngine) {
        statsCollectorJob?.cancel()
        _speedHistory.value = emptyList()
        statsCollectorJob = scope.launch {
            val rollingDeque = ArrayDeque<Pair<Long, Long>>(60)
            engine.getStatistics().collect { stat ->
                _statistics.value = stat
                if (rollingDeque.size >= 60) {
                    rollingDeque.removeFirst()
                }
                rollingDeque.addLast(stat.uploadSpeedBps to stat.downloadSpeedBps)
                _speedHistory.value = rollingDeque.toList()
            }
        }
    }

    private fun startHealthMonitor(
        engine: CoreEngine,
        localPort: Int,
        onLog: (String, String, String) -> Unit
    ) {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            while (isActive && _connectionState.value == VpnConnectionState.CONNECTED) {
                delay(5000L)
                if (_connectionState.value != VpnConnectionState.CONNECTED) break
                val coreAlive = engine.isEngineRunning()
                if (!coreAlive) {
                    onLog("ERROR", "VpnController", "Xray-core process exited unexpectedly on 127.0.0.1:$localPort")
                    if (activeSettings.autoReconnect) {
                        reconnectWithExponentialBackoff(onLog = onLog)
                    } else {
                        val msg = formatUserFriendlyError(
                            "Xray-core engine stopped unexpectedly",
                            activeSettings.languageCode
                        )
                        TProxyService.stopTun2Socks()
                        engine.stop()
                        tunEstablished = false
                        tunForwarderRunning = false
                        _ownershipState.value = ConnectionOwnership.NONE
                        _connectionState.value = VpnConnectionState.ERROR
                        updateDiagnosticSnapshot(localPort = null, lastError = msg)
                    }
                    break
                }
            }
        }
    }

    suspend fun stopSession(onLog: (String, String, String) -> Unit = { _, _, _ -> }) {
        if (_connectionState.value == VpnConnectionState.DISCONNECTED &&
            _ownershipState.value == ConnectionOwnership.NONE
        ) return
        _connectionState.value = VpnConnectionState.STOPPING
        updateDiagnosticSnapshot(localPort = _statistics.value.localSocksPort, lastError = null)

        healthMonitorJob?.cancel()
        healthMonitorJob = null
        statsCollectorJob?.cancel()
        statsCollectorJob = null

        TProxyService.stopTun2Socks()
        currentEngine.stop()

        activeAllocatedSocksPort = null
        activeConnectivityPath = null
        tunEstablished = false
        tunForwarderRunning = false
        lastVerifiedProxyLatencyMs = null
        _ownershipState.value = ConnectionOwnership.NONE
        _activeFreeProfile.value = null
        _connectionState.value = VpnConnectionState.DISCONNECTED
        _statistics.value = EngineStatistics()
        _speedHistory.value = emptyList()
        onLog("INFO", "VpnController", "VPN tunnel, TProxyService, and Xray-core stopped cleanly")
        updateDiagnosticSnapshot(localPort = null, lastError = null)
    }

    suspend fun reconnectWithExponentialBackoff(
        establishTunAndForwarder: suspend (Int) -> Boolean = { true },
        protectSocket: (Int) -> Boolean = { true },
        verifyConnectivity: Boolean = true,
        onLog: (String, String, String) -> Unit = { _, _, _ -> }
    ) {
        val profile = activeProfile ?: return
        if (_connectionState.value != VpnConnectionState.CONNECTED &&
            _connectionState.value != VpnConnectionState.RECONNECTING
        ) return

        _connectionState.value = VpnConnectionState.RECONNECTING
        updateDiagnosticSnapshot(localPort = _statistics.value.localSocksPort, lastError = null)

        val backoffDelaysMs = listOf(1000L, 2000L, 4000L, 8000L)
        for ((index, delayMs) in backoffDelaysMs.withIndex()) {
            val attempt = index + 1
            onLog(
                "INFO",
                "VpnController",
                "Reconnecting '${profile.remarks}' (attempt $attempt/${backoffDelaysMs.size})…"
            )
            // Stop only the engine process first; if it rebinds activeAllocatedSocksPort, TUN stays up
            currentEngine.stop()

            val relays = ResilienceJsonSerializer.parseAuthorizedRelays(activeSettings.authorizedRelaysJson)
            val path = activeConnectivityPath
            val effectiveProfile = if (activeSettings.resilienceMasterEnabled && path?.viaRelayId != null) {
                PathRegistry.applyPathOverrides(profile, path, relays)
            } else {
                profile
            }

            val res = currentEngine.start(
                profile = effectiveProfile,
                settings = activeSettings,
                customRules = activeRules,
                protectSocket = protectSocket,
                onLog = onLog,
                preferredLocalSocksPort = activeAllocatedSocksPort
            )
            if (res.isSuccess) {
                val port = res.getOrThrow()
                activeAllocatedSocksPort = port
                val tunOk = runCatching { establishTunAndForwarder(port) }.getOrDefault(false)
                val verifyOk = if (tunOk && verifyConnectivity) {
                    val v = currentEngine.verifyActiveConnection(activeVerificationUrl, 6000)
                    lastVerifiedProxyLatencyMs = v.getOrNull()
                    v.isSuccess
                } else {
                    tunOk
                }

                if (verifyOk) {
                    tunEstablished = true
                    tunForwarderRunning = TProxyService.isTun2SocksRunning()
                    currentEngine.markConnected()
                    _connectionState.value = VpnConnectionState.CONNECTED
                    observeEngineTelemetry(currentEngine)
                    startHealthMonitor(currentEngine, port, onLog)
                    updateDiagnosticSnapshot(localPort = port, lastError = null)
                    onLog("INFO", "VpnController", "Reconnected successfully on attempt $attempt")
                    return
                }
            }
            delay(delayMs)
        }

        TProxyService.stopTun2Socks()
        currentEngine.stop()
        activeAllocatedSocksPort = null
        tunEstablished = false
        tunForwarderRunning = false
        _ownershipState.value = ConnectionOwnership.NONE
        val failMsg = formatUserFriendlyError(
            "Failed to reconnect after ${backoffDelaysMs.size} attempts",
            activeSettings.languageCode
        )
        _connectionState.value = VpnConnectionState.ERROR
        updateDiagnosticSnapshot(localPort = null, lastError = failMsg)
    }

    fun formatUserFriendlyError(rawError: String, languageCode: String = activeSettings.languageCode): String {
        val isFa = languageCode.startsWith("fa", ignoreCase = true)
        val lower = rawError.lowercase()
        return when {
            lower.contains("uuid") -> if (isFa) {
                "شناسه UUID نامعتبر است: $rawError"
            } else {
                "Invalid UUID credential: $rawError"
            }
            lower.contains("reality") || lower.contains("pbk") || lower.contains("sni") -> if (isFa) {
                "خطا در تنظیمات امنیت REALITY/TLS: $rawError"
            } else {
                "REALITY / TLS configuration error: $rawError"
            }
            lower.contains("tls") || lower.contains("certificate") || lower.contains("handshake") -> if (isFa) {
                "خطا در نشست امنیتی TLS/REALITY با سرور ($rawError)"
            } else {
                "TLS / REALITY handshake failed ($rawError)"
            }
            lower.contains("timeout") || lower.contains("timed out") || lower.contains("http 204") -> if (isFa) {
                "عدم پاسخگویی سرور یا تایم‌اوت در تست اتصال واقعی (HTTP 204): $rawError"
            } else {
                "Server connection timed out during proxy verification: $rawError"
            }
            lower.contains("refused") || lower.contains("unreachable") || lower.contains("unresolved") || lower.contains("unknownhost") -> if (isFa) {
                "عدم دسترسی به آدرس یا پورت سرور: $rawError"
            } else {
                "Server host or port is unreachable: $rawError"
            }
            lower.contains("port") -> if (isFa) {
                "خطای پورت شبکه: $rawError"
            } else {
                "Network port error: $rawError"
            }
            else -> if (isFa) {
                "خطا در برقراری اتصال Xray-core: $rawError"
            } else {
                rawError
            }
        }
    }

    private fun updateDiagnosticSnapshot(
        localPort: Int? = _statistics.value.localSocksPort,
        lastError: String?
    ) {
        val xrayLoaded = XrayNativeBridge.isNativeLibraryLoaded()
        val hevLoaded = TProxyService.isNativeLibraryLoaded()
        val nativeStatus = buildString {
            append(currentEngine.getEngineVersion())
            append(" | ")
            append(if (xrayLoaded) "libgojni.so (Loaded)" else "Host/Process Mode")
            append(" + ")
            append(
                when {
                    tunForwarderRunning -> "HevSocks5Tunnel Active"
                    hevLoaded -> "libhev-socks5-tunnel.so (Ready)"
                    else -> "TUN Bridge"
                }
            )
        }
        val state = _connectionState.value
        val activeStates = setOf(
            VpnConnectionState.STARTING_ENGINE,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.VERIFYING,
            VpnConnectionState.CONNECTED,
            VpnConnectionState.RECONNECTING
        )
        _diagnostics.value = DiagnosticSnapshot(
            connectionState = state,
            activeEngine = currentEngine.engineType,
            engineVersion = currentEngine.getEngineVersion(),
            activeProfileRemarks = activeProfile?.remarks ?: "None",
            coreInitialized = state in activeStates && currentEngine.isEngineRunning(),
            tunInterfaceActive = tunEstablished && state == VpnConnectionState.CONNECTED,
            tunForwarderActive = tunForwarderRunning && state == VpnConnectionState.CONNECTED,
            localLoopbackPort = localPort,
            verifiedProxyLatencyMs = lastVerifiedProxyLatencyMs,
            bridgeArchitectureStatus = nativeStatus,
            lastError = lastError
        )
    }
}
