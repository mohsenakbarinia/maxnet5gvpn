package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.AppSettings
import com.example.domain.model.BrandingConfig
import com.example.domain.model.DiagnosticSnapshot
import com.example.domain.model.RedactedLogEntry
import com.example.domain.model.ThemePreference
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun DiagnosticsScreen(
    snapshot: DiagnosticSnapshot,
    logs: List<RedactedLogEntry>,
    onExportReport: () -> String,
    onClearLogs: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.diagnostics_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClearLogs,
                    modifier = Modifier.testTag("clear_logs_button")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.clear_logs))
                }
                Button(
                    onClick = {
                        val report = onExportReport()
                        clipboard.setText(AnnotatedString(report))
                        onShowMessage("Copied sanitized diagnostic report (all credentials redacted)")
                    },
                    modifier = Modifier.testTag("export_diagnostics_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.export_diagnostics))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Live Engine & TUN Snapshot Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                DiagnosticRow("Connection State", snapshot.connectionState.name)
                DiagnosticRow("Selected Engine", snapshot.activeEngine.displayName)
                DiagnosticRow("Native Core Version", snapshot.engineVersion)
                DiagnosticRow("Selected Profile", snapshot.activeProfileRemarks)
                DiagnosticRow("Core Initialized", snapshot.coreInitialized.toString())
                DiagnosticRow("TUN Interface Active", snapshot.tunInterfaceActive.toString())
                DiagnosticRow("HevSocks5Tunnel Active", snapshot.tunForwarderActive.toString())
                DiagnosticRow(
                    "Local Loopback SOCKS5",
                    snapshot.localLoopbackPort?.let { "127.0.0.1:$it" } ?: "Inactive"
                )
                DiagnosticRow(
                    "Verified Proxy Delay",
                    snapshot.verifiedProxyLatencyMs?.let { "${it} ms (HTTP 204)" } ?: "N/A"
                )
                DiagnosticRow("Last Error", snapshot.lastError ?: "None")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = snapshot.bridgeArchitectureStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Redacted Logs List
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            if (logs.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No diagnostic log events recorded yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(logs, key = { it.id }) { log ->
                        val levelColor = when (log.level) {
                            "ERROR" -> CoralError
                            "WARN" -> com.example.ui.theme.AmberWarning
                            else -> CyanPrimary
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "[${log.level}] [${log.tag}]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = levelColor,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = log.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAndAboutScreen(
    settings: AppSettings,
    branding: BrandingConfig,
    onSetLanguage: (String) -> Unit,
    onSetTheme: (ThemePreference) -> Unit,
    onSetPersianDigits: (Boolean) -> Unit,
    onSetFontScale: (Float) -> Unit,
    onSetAutoReconnect: (Boolean) -> Unit,
    onSetConnectOnBoot: (Boolean) -> Unit,
    onToggleKillSwitch: (Boolean) -> Unit = {},
    onToggleDnsLeakProtection: (Boolean) -> Unit = {},
    onToggleIpv6LeakProtection: (Boolean) -> Unit = {},
    onToggleAutoFailover: (Boolean) -> Unit = {},
    onSetSecureDnsProvider: (String) -> Unit = {},
    onExportBackup: suspend () -> String,
    onRestoreBackup: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showBackupRestoreDialog by remember { mutableStateOf(false) }
    var backupJsonInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(14.dp))

        // Localization & Typography Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.language_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.languageCode.startsWith("en", ignoreCase = true),
                        onClick = { onSetLanguage("en-US") },
                        label = { Text(stringResource(R.string.lang_english)) },
                        modifier = Modifier.testTag("lang_en_button")
                    )
                    FilterChip(
                        selected = settings.languageCode.startsWith("fa", ignoreCase = true),
                        onClick = { onSetLanguage("fa-IR") },
                        label = { Text(stringResource(R.string.lang_persian)) },
                        modifier = Modifier.testTag("lang_fa_button")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.use_persian_digits), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings.usePersianDigits,
                        onCheckedChange = onSetPersianDigits
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${stringResource(R.string.font_scale_label)}: ${(settings.fontScale * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = settings.fontScale,
                    onValueChange = onSetFontScale,
                    valueRange = 0.85f..1.25f
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Theme & Appearance Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.theme_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.themePreference == ThemePreference.DARK,
                        onClick = { onSetTheme(ThemePreference.DARK) },
                        label = { Text(stringResource(R.string.theme_dark)) }
                    )
                    FilterChip(
                        selected = settings.themePreference == ThemePreference.AMOLED,
                        onClick = { onSetTheme(ThemePreference.AMOLED) },
                        label = { Text(stringResource(R.string.theme_amoled)) }
                    )
                    FilterChip(
                        selected = settings.themePreference == ThemePreference.LIGHT,
                        onClick = { onSetTheme(ThemePreference.LIGHT) },
                        label = { Text(stringResource(R.string.theme_light)) }
                    )
                    FilterChip(
                        selected = settings.themePreference == ThemePreference.SYSTEM,
                        onClick = { onSetTheme(ThemePreference.SYSTEM) },
                        label = { Text(stringResource(R.string.theme_system)) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Connection Resilience, Kill-Switch & Backup Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldAccent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Connection Resilience & Backup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.auto_reconnect), modifier = Modifier.weight(1f))
                    Switch(checked = settings.autoReconnect, onCheckedChange = onSetAutoReconnect)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.connect_on_boot), modifier = Modifier.weight(1f))
                    Switch(checked = settings.connectOnBoot, onCheckedChange = onSetConnectOnBoot)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Kill Switch (Block Non-VPN Traffic)", modifier = Modifier.weight(1f))
                    Switch(checked = settings.killSwitchEnabled, onCheckedChange = onToggleKillSwitch)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("DNS Leak Protection (DoH)", modifier = Modifier.weight(1f))
                    Switch(checked = settings.dnsLeakProtection, onCheckedChange = onToggleDnsLeakProtection)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("IPv6 Leak Protection (Block IPv6)", modifier = Modifier.weight(1f))
                    Switch(checked = settings.ipv6LeakProtection, onCheckedChange = onToggleIpv6LeakProtection)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto Failover (Zero-Drop Recovery)", modifier = Modifier.weight(1f))
                    Switch(checked = settings.autoFailoverEnabled, onCheckedChange = onToggleAutoFailover)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.always_on_killswitch_guide),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Android System VPN Settings")
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val json = onExportBackup()
                                clipboard.setText(AnnotatedString(json))
                                onShowMessage("Copied full JSON backup to clipboard")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_export))
                    }
                    OutlinedButton(
                        onClick = { showBackupRestoreDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_restore))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // About, Branding & Zero-Telemetry Privacy Guarantee Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.about_privacy_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${branding.appName} v1.0.0 • Scheme: ${branding.urlScheme}://",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = JetBrainsMonoFontFamily
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.privacy_statement),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onCheckUpdates,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("check_updates_button")
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.check_updates))
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    if (showBackupRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showBackupRestoreDialog = false },
            title = { Text(stringResource(R.string.backup_restore)) },
            text = {
                OutlinedTextField(
                    value = backupJsonInput,
                    onValueChange = { backupJsonInput = it },
                    label = { Text("Paste Backup JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRestoreBackup(backupJsonInput)
                        showBackupRestoreDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupRestoreDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
