package com.example.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.example.data.preferences.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts MAXNET5G VPN automatically on device boot when enabled in user settings
 * and Android VPN permission has already been granted.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settings = SettingsDataStore(context.applicationContext).settingsFlow.first()
                    if (settings.connectOnBoot && VpnService.prepare(context) == null) {
                        val svcIntent = Intent(context, MaxNetVpnService::class.java).apply {
                            this.action = MaxNetVpnService.ACTION_CONNECT
                        }
                        ContextCompat.startForegroundService(context, svcIntent)
                    }
                } catch (_: Exception) {
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
