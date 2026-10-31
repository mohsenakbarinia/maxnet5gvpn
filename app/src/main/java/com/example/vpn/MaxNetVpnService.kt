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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Production Android [VpnService] implementation for MAXNET5G VPN.
 * - Establishes a real TUN interface with IPv4, optional IPv6, DNS, MTU, and per-app split tunneling.
 * - Excludes its own package where appropriate so outbound proxy sockets never loop back into TUN.
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
                startForeground(NOTIFICATION_ID, buildForegroundNotification("Connecting…"))
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
        val profiles = repository.getAllProfilesSnapshot()
        val selectedProfile = profiles.find { it.id == settings.selectedProfileId }
            ?: profiles.firstOrNull()

        if (selectedProfile == null) {
            repository.appendLog("ERROR", "VpnService", "Cannot connect: no server profile configured")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val rules = repository.getRoutingRulesSnapshot()
        val establishedTun = establishTunInterface(selectedProfile, settings)
        val tunActive = establishedTun != null
        tunInterface = establishedTun

        val result = VpnController.startSession(
            profile = selectedProfile,
            settings = settings,
            rules = rules,
            tunActive = tunActive,
            protectSocket = { fd -> protect(fd) },
            onLog = { level, tag, msg ->
                repository.appendLog(level, tag, msg, listOf(selectedProfile.credential))
            }
        )

        if (result.isSuccess) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(
                NOTIFICATION_ID,
                buildForegroundNotification("${selectedProfile.remarks} (${selectedProfile.protocol.displayName})")
            )
        } else {
            closeTunInterface()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun establishTunInterface(profile: ServerProfile, settings: AppSettings): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("MAXNET5G - ${profile.remarks}")
                .setMtu(settings.mtu.coerceIn(1280, 1500))
                .addAddress("10.16.0.2", 30)
                .addRoute("0.0.0.0", 0)

            // Configure DNS server (clean IP literal)
            val dnsServer = settings.directDns.trim().takeIf {
                it.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
            } ?: "1.1.1.1"
            builder.addDnsServer(dnsServer)

            if (settings.enableIpv6) {
                runCatching {
                    builder.addAddress("fd00:1::2", 126)
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
            repository.appendLog("WARN", "VpnService", "TUN interface establish note: ${e.message}")
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
                engine = diag.activeEngine.displayName,
                connectedAt = stats.connectedSinceMs,
                disconnectedAt = System.currentTimeMillis(),
                uploadBytes = stats.sessionUploadBytes,
                downloadBytes = stats.sessionDownloadBytes,
                statusNote = "Disconnected cleanly"
            )
        }
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
                        if (settings.autoReconnect &&
                            VpnController.connectionState.value == com.example.domain.model.VpnConnectionState.CONNECTED
                        ) {
                            VpnController.reconnectWithExponentialBackoff { level, tag, msg ->
                                repository.appendLog(level, tag, msg)
                            }
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
        closeTunInterface()
        serviceScope.cancel()
        super.onDestroy()
    }
}
