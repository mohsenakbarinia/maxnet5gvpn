package com.example.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.domain.model.VpnConnectionState

/**
 * Android Quick Settings Tile allowing instant connect/disconnect from the notification shade.
 */
class MaxNetTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        val state = VpnController.connectionState.value
        val activeStates = setOf(
            VpnConnectionState.PREPARING,
            VpnConnectionState.STARTING_ENGINE,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.VERIFYING,
            VpnConnectionState.CONNECTED,
            VpnConnectionState.RECONNECTING
        )
        if (state in activeStates) {
            val stopIntent = Intent(this, MaxNetVpnService::class.java).apply {
                action = MaxNetVpnService.ACTION_DISCONNECT
            }
            startService(stopIntent)
        } else {
            val prep = VpnService.prepare(this)
            if (prep != null) {
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(
                        android.app.PendingIntent.getActivity(
                            this,
                            0,
                            activityIntent,
                            android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(activityIntent)
                }
            } else {
                val startIntent = Intent(this, MaxNetVpnService::class.java).apply {
                    action = MaxNetVpnService.ACTION_CONNECT
                }
                ContextCompat.startForegroundService(this, startIntent)
            }
        }
        syncTileState()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val connected = VpnController.connectionState.value == VpnConnectionState.CONNECTED
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "MAXNET5G"
        tile.updateTile()
    }
}
