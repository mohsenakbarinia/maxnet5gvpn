package com.example.core.smart

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.core.LatencyTester
import com.example.domain.model.AppSettings
import com.example.domain.model.ServerProfile
import com.example.domain.model.SmartConnectionMode
import com.example.domain.model.VpnConnectionState
import com.example.vpn.VpnController
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Event published when an Auto Failover is evaluated or triggered.
 */
data class FailoverEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val failedServerRemarks: String,
    val replacementServerRemarks: String,
    val reasonEn: String,
    val reasonFa: String,
    val isSuccessful: Boolean,
    val attemptNumber: Int
)

/**
 * Intelligent Auto Failover & Smart Recovery Manager for MAXNET5G VPN V3.0.
 *
 * - Continuous, battery-aware connection monitoring.
 * - Distinguishes between local network outage vs. server failure.
 * - Dual-endpoint verification to prevent false-positive failovers.
 * - Safe single-session failover to the next best eligible server from [SmartEngine].
 * - Cooldown enforcement & max-retry caps to prevent reconnect loops.
 */
class SmartFailoverManager(
    private val context: Context,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onLog: (level: String, tag: String, message: String) -> Unit = { _, _, _ -> }
) {
    private val failoverMutex = Mutex()
    private val failoverAttempts = AtomicInteger(0)
    private var monitorJob: Job? = null

    private val _lastFailoverEvent = MutableStateFlow<FailoverEvent?>(null)
    val lastFailoverEvent: StateFlow<FailoverEvent?> = _lastFailoverEvent.asStateFlow()

    private val _isRecovering = MutableStateFlow(false)
    val isRecovering: StateFlow<Boolean> = _isRecovering.asStateFlow()

    companion object {
        const val MAX_FAILOVER_ATTEMPTS_PER_SESSION = 3
    }

    /**
     * Checks if the device has an active physical network interface (Wi-Fi, Cellular, Ethernet).
     */
    fun isDeviceNetworkOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val activeNet = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Starts continuous connection monitoring for the active VPN tunnel.
     * Monitoring interval adapts to the active [SmartConnectionMode].
     */
    fun startActiveMonitoring(
        profile: ServerProfile,
        settings: AppSettings,
        getCandidates: suspend () -> List<ServerProfile>,
        onTriggerReconnect: suspend (ServerProfile, String) -> Boolean
    ) {
        stopMonitoring()
        failoverAttempts.set(0)

        val intervalMs = when (settings.smartConnectionMode) {
            SmartConnectionMode.GAMING -> 10_000L      // 10s for competitive gaming
            SmartConnectionMode.STREAMING -> 15_000L   // 15s for streaming
            SmartConnectionMode.BROWSING -> 20_000L    // 20s for browsing
            SmartConnectionMode.STABLE -> 30_000L      // 30s for stable mode
        }

        monitorJob = externalScope.launch {
            onLog("INFO", "SmartFailover", "Started active monitoring (${intervalMs / 1000}s interval) for '${profile.remarks}'")
            while (isActive && VpnController.connectionState.value == VpnConnectionState.CONNECTED) {
                delay(intervalMs)
                if (VpnController.connectionState.value != VpnConnectionState.CONNECTED) break

                // 1. Process liveness check
                val isCoreAlive = VpnController.getEngine(settings.activeEngine).isEngineRunning()
                if (!isCoreAlive) {
                    onLog("WARN", "SmartFailover", "Core engine died unexpectedly. Triggering recovery…")
                    handleFailure(
                        failedProfile = profile,
                        settings = settings,
                        reasonEn = "VPN engine process terminated unexpectedly",
                        reasonFa = "فرآیند هسته فیلترشکن متوقف شد",
                        getCandidates = getCandidates,
                        onTriggerReconnect = onTriggerReconnect
                    )
                    break
                }

                // 2. Real connectivity check through active proxy
                val localPort = VpnController.statistics.value.localSocksPort
                if (localPort != null && localPort > 0) {
                    val primaryCheck = LatencyTester.testProxyHttp204Delay(
                        profile = profile,
                        localSocksPort = localPort,
                        testUrl = settings.connectivityCheckUrl,
                        timeoutMs = 5000,
                        performWarmup = false,
                        sampleCount = 1
                    )

                    if (primaryCheck.connectionStatus == com.example.domain.model.ServerConnectionStatus.FAILED) {
                        // Check if device network is completely offline first
                        if (!isDeviceNetworkOnline()) {
                            onLog("WARN", "SmartFailover", "Device local network appears offline; holding current server without failover.")
                            continue
                        }

                        // Verify with fallback URL to avoid false positives
                        val fallbackCheck = LatencyTester.testProxyHttp204Delay(
                            profile = profile,
                            localSocksPort = localPort,
                            testUrl = settings.connectivityFallbackUrl,
                            timeoutMs = 5000,
                            performWarmup = false,
                            sampleCount = 1
                        )

                        if (fallbackCheck.connectionStatus == com.example.domain.model.ServerConnectionStatus.FAILED) {
                            onLog("WARN", "SmartFailover", "Sustained proxy check timeout on '${profile.remarks}'. Initiating Smart Failover…")
                            handleFailure(
                                failedProfile = profile,
                                settings = settings,
                                reasonEn = "Sustained proxy HTTP timeout (dual-endpoint check failed)",
                                reasonFa = "افت شدید کیفیت و عدم پاسخگویی سرور (بررسی دوگانه ناموفق)",
                                getCandidates = getCandidates,
                                onTriggerReconnect = onTriggerReconnect
                            )
                            break
                        }
                    } else {
                        // Success: Reset consecutive failure count for active profile
                        SmartEngine.recordServerSuccess(profile)
                    }
                }
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        _isRecovering.value = false
    }

    /**
     * Executes the safe failover state machine:
     * - Adds the failing server to failure cooldown.
     * - Selects the next best available server using [SmartEngine].
     * - Re-establishes the tunnel and verifies end-to-end traffic.
     */
    suspend fun handleFailure(
        failedProfile: ServerProfile,
        settings: AppSettings,
        reasonEn: String,
        reasonFa: String,
        getCandidates: suspend () -> List<ServerProfile>,
        onTriggerReconnect: suspend (ServerProfile, String) -> Boolean
    ): Boolean {
        if (!settings.autoFailoverEnabled) {
            onLog("INFO", "SmartFailover", "Auto Failover is disabled in settings; reporting disconnection.")
            return false
        }

        if (failoverAttempts.get() >= MAX_FAILOVER_ATTEMPTS_PER_SESSION) {
            onLog("WARN", "SmartFailover", "Max failover attempts ($MAX_FAILOVER_ATTEMPTS_PER_SESSION) reached. Stopping to prevent reconnect loops.")
            return false
        }

        return failoverMutex.withLock {
            _isRecovering.value = true
            val attempt = failoverAttempts.incrementAndGet()
            SmartEngine.recordServerFailure(failedProfile)

            try {
                val candidates = getCandidates().filter {
                    it.uniqueFingerprintKey() != failedProfile.uniqueFingerprintKey()
                }

                if (candidates.isEmpty()) {
                    onLog("WARN", "SmartFailover", "No alternative server candidates available for failover.")
                    _isRecovering.value = false
                    return@withLock false
                }

                val selection = SmartEngine.selectBestServer(
                    candidates = candidates,
                    mode = settings.smartConnectionMode
                )

                if (selection == null) {
                    onLog("WARN", "SmartFailover", "Smart Engine could not identify an eligible replacement server.")
                    _isRecovering.value = false
                    return@withLock false
                }

                val nextProfile = selection.selectedProfile
                onLog(
                    "INFO",
                    "SmartFailover",
                    "Failover #$attempt: Switching from '${failedProfile.remarks}' to '${nextProfile.remarks}' [${selection.explanationEn}]"
                )

                val success = onTriggerReconnect(nextProfile, "AUTO_FAILOVER: $reasonEn")

                val event = FailoverEvent(
                    failedServerRemarks = failedProfile.remarks,
                    replacementServerRemarks = nextProfile.remarks,
                    reasonEn = reasonEn,
                    reasonFa = reasonFa,
                    isSuccessful = success,
                    attemptNumber = attempt
                )
                _lastFailoverEvent.value = event

                if (success) {
                    onLog("INFO", "SmartFailover", "Failover successful: Restored connection through '${nextProfile.remarks}'")
                    SmartEngine.recordServerSuccess(nextProfile)
                } else {
                    onLog("ERROR", "SmartFailover", "Failover reconnect to '${nextProfile.remarks}' failed.")
                    SmartEngine.recordServerFailure(nextProfile)
                }

                _isRecovering.value = false
                success
            } catch (ce: CancellationException) {
                _isRecovering.value = false
                throw ce
            } catch (t: Throwable) {
                onLog("ERROR", "SmartFailover", "Failover execution exception: ${t.message}")
                _isRecovering.value = false
                false
            }
        }
    }
}
