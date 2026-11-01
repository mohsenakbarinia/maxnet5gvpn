package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.MaxNetDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.VpnRepository
import com.example.domain.model.AppSettings
import com.example.domain.model.ServerProfile
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.VpnConnectionState
import com.v2ray.ang.service.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Production Android [VpnService] implementation for MAXNET5G VPN.
 * - Starts Xray-core v26.10.29 (`libgojni.so`) on `127.0.0.1:<localSocksPort>`.
 * - Establishes a real TUN interface with IPv4 (`10.26.26.1/30`), optional IPv6 (`fd00:1::1/126`),
 *   DNS, MTU, and per-app split tunneling.
 * - Starts `TProxyService` (`libhev-socks5-tunnel.so`) to forward TUN packets into Xray's local SOCKS5 port.
 * - Excludes its own package AND calls `protect(fd)` via `Libv2ray` so outbound proxy sockets never loop into TUN.
 * - Verifies real HTTP 204 proxy traffic before publishing `CONNECTED`.
 * - Runs as a persistent foreground service with a Disconnect notification action.
 * - Handles network changes (Wi-Fi <-> Mobile Data) and [onRevoke] cleanly.
 */
class MaxNetVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
        private const val NOTIFICATION_CHANNEL_ID = "maxnet5g_vpn_channel"
        private const val NOTIFICATION_ID = 5001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var repository: VpnRepository

    override fun onCreate() {
        super.onCreate()
        val db = MaxNetDatabase.getInstance(applicationContext)
        val dataStore = SettingsDataStore(applicationContext)
        repository = VpnRepository(applicationContext, db.maxNetDao(), dataStore)
        createNotificationChannel()
        registerNetworkChangeCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    disconnectVpn()
                }
                return START_NOT_STICKY
            }
            ACTION_CONNECT, null -> {
                startForeground(NOTIFICATION_ID, buildForegroundNotification("Preparing Xray-core…"))
                serviceScope.launch {
                    connectVpn()
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private suspend fun connectVpn() {
        val settings = repository.settingsFlow.first()
        val rules = repository.getRoutingRulesSnapshot()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val currentMode = VpnController.selectedVpnMode.value

        if (currentMode == com.example.core.autovpn.VpnAppMode.MY_VPN) {
            val existingProfiles = repository.getAllProfilesSnapshot()
            val personalProfile = existingProfiles.find { it.id == settings.selectedProfileId }
                ?: existingProfiles.firstOrNull()
            if (personalProfile == null) {
                repository.appendLog("WARN", "VpnService", "Cannot start MY VPN: no personal profile configured")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            val sessionResult = VpnController.startSession(
                context = applicationContext,
                profile = personalProfile,
                settings = settings,
                rules = rules,
                ownership = com.example.core.autovpn.ConnectionOwnership.PERSONAL,
                establishTunAndForwarder = { localSocksPort ->
                    setupTunAndPacketForwarder(personalProfile, settings, localSocksPort)
                },
                protectSocket = { fd -> protect(fd) },
                verifyConnectivity = true,
                onStateChanged = { state ->
                    val stateLabel = when (state) {
                        VpnConnectionState.PREPARING -> "Preparing personal config…"
                        VpnConnectionState.STARTING_ENGINE -> "Starting Xray-core…"
                        VpnConnectionState.CONNECTING -> "Establishing TUN & SOCKS5…"
                        VpnConnectionState.VERIFYING -> "Verifying proxy connection…"
                        VpnConnectionState.CONNECTED -> "${personalProfile.remarks} (${personalProfile.protocol.displayName})"
                        VpnConnectionState.RECONNECTING -> "Reconnecting ${personalProfile.remarks}…"
                        VpnConnectionState.STOPPING -> "Stopping VPN…"
                        VpnConnectionState.ERROR -> "Connection failed"
                        VpnConnectionState.DISCONNECTED -> "Disconnected"
                    }
                    runCatching {
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildForegroundNotification(stateLabel)
                        )
                    }
                },
                onLog = { level, tag, msg ->
                    repository.appendLog(level, tag, msg, listOf(personalProfile.credential))
                }
            )

            if (sessionResult.isSuccess) {
                val latencySuffix = VpnController.diagnostics.value.verifiedProxyLatencyMs?.let { " • ${it}ms" } ?: ""
                if (VpnController.diagnostics.value.verifiedProxyLatencyMs != null) {
                    repository.updateProfileLatency(
                        personalProfile.id,
                        VpnController.diagnostics.value.verifiedProxyLatencyMs,
                        com.example.domain.model.LatencyMeasureType.PROXY_HTTP_DELAY
                    )
                }
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildForegroundNotification("${personalProfile.remarks} (${personalProfile.protocol.displayName}$latencySuffix)")
                )
            } else {
                TProxyService.stopTun2Socks()
                closeTunInterface()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }

        // FREE VPN mode — completely isolated from personal Room profiles
        val autoManager = VpnController.getOrCreateAutoFreeVpnManager(applicationContext)
        val outcome = autoManager.connectOneTapAuto(
            existingProfiles = emptyList(),
            fetchRemoteIfNeeded = true,
            onPersistAndSelectCandidate = { candidate, latencyMs ->
                val updated = if (latencyMs != null) candidate.copy(lastLatencyMs = latencyMs) else candidate
                VpnController.setActiveFreeProfile(updated)
                updated
            },
            onAttemptConnectAndVerify = { candidateProfile ->
                val sessionResult = VpnController.startSession(
                    context = applicationContext,
                    profile = candidateProfile,
                    settings = settings,
                    rules = rules,
                    ownership = com.example.core.autovpn.ConnectionOwnership.FREE,
                    establishTunAndForwarder = { localSocksPort ->
                        setupTunAndPacketForwarder(candidateProfile, settings, localSocksPort)
                    },
                    protectSocket = { fd -> protect(fd) },
                    verifyConnectivity = true,
                    onStateChanged = { state ->
                        val stateLabel = when (state) {
                            VpnConnectionState.PREPARING -> "Preparing Free VPN config…"
                            VpnConnectionState.STARTING_ENGINE -> "Starting Xray-core…"
                            VpnConnectionState.CONNECTING -> "Establishing TUN & SOCKS5…"
                            VpnConnectionState.VERIFYING -> "Verifying proxy connection…"
                            VpnConnectionState.CONNECTED -> "MAXNET5G Free VPN (${candidateProfile.protocol.displayName})"
                            VpnConnectionState.RECONNECTING -> "Switching Free VPN server…"
                            VpnConnectionState.STOPPING -> "Stopping VPN…"
                            VpnConnectionState.ERROR -> "Connection failed"
                            VpnConnectionState.DISCONNECTED -> "Disconnected"
                        }
                        runCatching {
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildForegroundNotification(stateLabel)
                            )
                        }
                    },
                    onLog = { level, tag, msg ->
                        repository.appendLog(level, tag, msg, listOf(candidateProfile.credential))
                    }
                )
                sessionResult.map { VpnController.diagnostics.value.verifiedProxyLatencyMs }
            },
            onStopFailedSession = {
                VpnController.stopSession()
                TProxyService.stopTun2Socks()
                closeTunInterface()
            },
            onLog = { level, tag, msg ->
                repository.appendLog(level, tag, msg)
            }
        )

        if (outcome.isSuccess) {
            val winner = outcome.getOrThrow().profile
            val latencySuffix = VpnController.diagnostics.value.verifiedProxyLatencyMs?.let { " • ${it}ms" } ?: ""
            notificationManager.notify(
                NOTIFICATION_ID,
                buildForegroundNotification("MAXNET5G Free VPN (${winner.protocol.displayName}$latencySuffix)")
            )
            // Start continuous background health & quality optimization while connected
            autoManager.startContinuousOptimization(
                getConnectionState = { VpnController.connectionState.value },
                getActiveTrafficBps = {
                    val s = VpnController.statistics.value
                    s.downloadSpeedBps + s.uploadSpeedBps
                },
                onHealthCheckActiveTunnel = {
                    val verifyUrl = "https://www.gstatic.com/generate_204"
                    val port = VpnController.statistics.value.localSocksPort
                    if (port != null && port > 0) {
                        val res = com.example.core.LatencyTester.testProxyHttp204Delay(winner, port, verifyUrl, 5000)
                        if (res.latencyMs != null) {
                            Result.success(res.latencyMs)
                        } else {
                            Result.failure(IllegalStateException(res.errorReason ?: "Tunnel unresponsive"))
                        }
                    } else {
                        Result.failure(IllegalStateException("No active SOCKS port"))
                    }
                },
                onTriggerFailoverReconnect = {
                    connectVpn()
                }
            )
        } else {
            TProxyService.stopTun2Socks()
            closeTunInterface()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun setupTunAndPacketForwarder(
        profile: ServerProfile,
        settings: AppSettings,
        localSocksPort: Int
    ): Boolean {
        TProxyService.stopTun2Socks()
        closeTunInterface()

        val pfd = establishTunInterface(profile, settings) ?: return false
        tunInterface = pfd

        val forwarderResult = TProxyService.startTun2Socks(
            context = applicationContext,
            tunFd = pfd.fd,
            socksPort = localSocksPort,
            mtu = settings.mtu.coerceIn(1280, 1500),
            enableIpv6 = settings.enableIpv6
        )

        return forwarderResult.fold(
            onSuccess = {
                repository.appendLog(
                    "INFO",
                    "TProxyService",
                    "Started native HevSocks5Tunnel on TUN fd=${pfd.fd} -> 127.0.0.1:$localSocksPort"
                )
                true
            },
            onFailure = { err ->
                repository.appendLog(
                    "ERROR",
                    "TProxyService",
                    "Failed to start HevSocks5Tunnel: ${err.message}"
                )
                closeTunInterface()
                false
            }
        )
    }

    private fun establishTunInterface(profile: ServerProfile, settings: AppSettings): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("MAXNET5G - ${profile.remarks}")
                .setMtu(settings.mtu.coerceIn(1280, 1500))
                .addAddress("10.26.26.1", 30)
                .addRoute("0.0.0.0", 0)

            // Configure primary and secondary DNS servers
            val dnsServer = settings.directDns.trim().takeIf {
                it.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
            } ?: "1.1.1.1"
            builder.addDnsServer(dnsServer)
            if (dnsServer != "8.8.8.8") {
                runCatching { builder.addDnsServer("8.8.8.8") }
            }

            if (settings.enableIpv6) {
                runCatching {
                    builder.addAddress("fd00:1::1", 126)
                    builder.addRoute("::", 0)
                    builder.addDnsServer("2606:4700:4700::1111")
                }
            }

            // Configure Per-App Split Tunneling (respecting mutual exclusivity of allow vs disallow)
            when (settings.splitTunnelMode) {
                SplitTunnelMode.ALL_APPS -> {
                    // Always exclude our own app so outbound proxy traffic does not loop into TUN
                    runCatching { builder.addDisallowedApplication(packageName) }
                }
                SplitTunnelMode.ALLOW_SELECTED -> {
                    val allowed = settings.splitTunnelPackages.filter { it != packageName }
                    if (allowed.isNotEmpty()) {
                        for (pkg in allowed) {
                            runCatching { builder.addAllowedApplication(pkg) }
                        }
                    } else {
                        runCatching { builder.addDisallowedApplication(packageName) }
                    }
                }
                SplitTunnelMode.DISALLOW_SELECTED -> {
                    runCatching { builder.addDisallowedApplication(packageName) }
                    for (pkg in settings.splitTunnelPackages) {
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val configureIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setConfigureIntent(configureIntent)
            builder.establish()
        } catch (e: Exception) {
            repository.appendLog("ERROR", "VpnService", "TUN interface establish failed: ${e.message}")
            null
        }
    }

    private suspend fun disconnectVpn() {
        val stats = VpnController.statistics.value
        val diag = VpnController.diagnostics.value
        if (stats.connectedSinceMs != null) {
            repository.recordConnectionSession(
                profileName = diag.activeProfileRemarks,
                protocol = "VPN",
                engine = diag.engineVersion,
                connectedAt = stats.connectedSinceMs,
                disconnectedAt = System.currentTimeMillis(),
                uploadBytes = stats.sessionUploadBytes,
                downloadBytes = stats.sessionDownloadBytes,
                statusNote = "Disconnected cleanly"
            )
        }
        VpnController.getOrCreateAutoFreeVpnManager(applicationContext)
            .stopContinuousOptimizationAndResetToIdle()
        VpnController.stopSession { level, tag, msg ->
            repository.appendLog(level, tag, msg)
        }
        closeTunInterface()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTunInterface() {
        try {
            tunInterface?.close()
        } catch (_: Exception) {}
        tunInterface = null
    }

    override fun onRevoke() {
        serviceScope.launch {
            repository.appendLog("WARN", "VpnService", "VPN permission revoked by Android system (onRevoke)")
            disconnectVpn()
        }
        super.onRevoke()
    }

    private fun registerNetworkChangeCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager = cm
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    serviceScope.launch {
                        val settings = repository.settingsFlow.first()
                        val profiles = repository.getAllProfilesSnapshot()
                        val selectedProfile = profiles.find { it.id == settings.selectedProfileId }
                            ?: profiles.firstOrNull()
                        if (settings.autoReconnect &&
                            selectedProfile != null &&
                            VpnController.connectionState.value == VpnConnectionState.CONNECTED
                        ) {
                            VpnController.reconnectWithExponentialBackoff(
                                establishTunAndForwarder = { port ->
                                    setupTunAndPacketForwarder(selectedProfile, settings, port)
                                },
                                protectSocket = { fd -> protect(fd) },
                                verifyConnectivity = true,
                                onLog = { level, tag, msg ->
                                    repository.appendLog(level, tag, msg, listOf(selectedProfile.credential))
                                }
                            )
                        }
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_notification_connected)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(subtitle: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MaxNetVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.vpn_notification_disconnect),
                disconnectIntent
            )
            .build()
    }

    override fun onDestroy() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        TProxyService.stopTun2Socks()
        closeTunInterface()
        serviceScope.cancel()
        super.onDestroy()
    }
}
