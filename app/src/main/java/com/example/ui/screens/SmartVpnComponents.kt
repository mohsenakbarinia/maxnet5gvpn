package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.resilience.AuthorizedRelay
import com.example.core.resilience.DomesticReachability
import com.example.core.resilience.NationalConnectivityState
import com.example.core.resilience.PersianConnectivityStrings
import com.example.core.resilience.RelayMode
import com.example.core.resilience.ResilienceJsonSerializer
import com.example.core.resilience.ResilienceUiSnapshot
import com.example.core.security.SecurityCenter
import com.example.core.security.SecurityAuditReport
import com.example.core.smart.SmartSelectionResult
import com.example.domain.model.AppSettings
import com.example.domain.model.ConnectionHistoryItem
import com.example.domain.model.SmartConnectionMode
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MidnightBorder
import com.example.ui.theme.MidnightSurface
import com.example.ui.theme.MidnightSurfaceVariant
import com.example.util.LocalizationHelper

/**
 * Interactive 4-mode Smart Profile Selector:
 * GAMING (minimal ping & jitter), STREAMING (max bandwidth), BROWSING (fast DNS), STABLE (resilience).
 */
@Composable
fun SmartConnectionModeSelectorCard(
    selectedMode: SmartConnectionMode,
    isFa: Boolean,
    onSelectMode: (SmartConnectionMode) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_connection_mode_selector"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightSurface),
        border = BorderStroke(1.dp, MidnightBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isFa) "حالت اتصال هوشمند (Smart Mode)" else "Smart Connection Mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = CyanPrimary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = if (isFa) selectedMode.titleFa else selectedMode.titleEn,
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4-Mode Chips Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SmartConnectionMode.values().forEach { mode ->
                    val isSelected = mode == selectedMode
                    val icon = when (mode) {
                        SmartConnectionMode.GAMING -> Icons.Default.Speed
                        SmartConnectionMode.STREAMING -> Icons.Default.Download
                        SmartConnectionMode.BROWSING -> Icons.Default.Public
                        SmartConnectionMode.STABLE -> Icons.Default.Security
                    }
                    val label = when (mode) {
                        SmartConnectionMode.GAMING -> if (isFa) "گیمینگ" else "Gaming"
                        SmartConnectionMode.STREAMING -> if (isFa) "استریم" else "Stream"
                        SmartConnectionMode.BROWSING -> if (isFa) "وب‌گردی" else "Browse"
                        SmartConnectionMode.STABLE -> if (isFa) "پایدار" else "Stable"
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectMode(mode) }
                            .testTag("smart_mode_${mode.name.lowercase()}"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) CyanPrimary.copy(alpha = 0.18f) else MidnightSurfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) CyanPrimary else MidnightBorder
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val desc = when (selectedMode) {
                SmartConnectionMode.GAMING ->
                    if (isFa) "🎮 اولویت پینگ کمینه و لرزش زیر ۱۰ میلی‌ثانیه برای بازی‌های آنلاین بدون تاخیر."
                    else "🎮 Prioritizes lowest latency & jitter <10ms for lag-free multiplayer gaming."
                SmartConnectionMode.STREAMING ->
                    if (isFa) "🎬 اولویت پهنای باند بالا و بدون افت بسته (Zero Loss) برای یوتیوب و ویدیو 4K."
                    else "🎬 Maximizes sustained throughput & zero packet loss for 4K video & media."
                SmartConnectionMode.BROWSING ->
                    if (isFa) "🌐 پاسخ‌دهی فوق‌العاده سریع DNS و صرفه‌جویی در مصرف باتری برای مرور وب."
                    else "🌐 Optimized for ultra-responsive DNS lookups & low power web navigation."
                SmartConnectionMode.STABLE ->
                    if (isFa) "🛡️ الگوریتم ضد فیلتر و بقای طولانی‌مدت اتصال بدون قطعی و بلاک شدن."
                    else "🛡️ Maximum anti-censorship resilience and rock-solid connection persistence."
            }

            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * Transparent & Explainable Decision Card:
 * Shows why the server was chosen, its score, jitter, packet loss, and candidate count.
 */
@Composable
fun SmartEngineDecisionCard(
    result: SmartSelectionResult,
    isFa: Boolean,
    usePersian: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_engine_decision_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightSurface),
        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isFa) "تحلیل هوشمند انتخاب سرور" else "Smart Engine Decision",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                val scoreColor = when {
                    result.score >= 80 -> EmeraldAccent
                    result.score >= 60 -> AmberWarning
                    else -> CoralError
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = scoreColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, scoreColor)
                ) {
                    Text(
                        text = "${if (isFa) "امتیاز:" else "Score:"} ${LocalizationHelper.formatDigits(result.score.toString(), usePersian)}/100",
                        style = MaterialTheme.typography.labelSmall,
                        color = scoreColor,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metrics row: Ping, Jitter, Loss, Candidates
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (isFa) "پینگ واقعی" else "Real Ping",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = result.latencyMs?.let { "${LocalizationHelper.formatDigits(it.toString(), usePersian)} ms" } ?: "--",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldAccent
                    )
                }

                Column {
                    Text(
                        text = if (isFa) "لرزش (Jitter)" else "Jitter",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = result.jitterMs?.let { "±${LocalizationHelper.formatDigits(it.toString(), usePersian)} ms" } ?: "±0 ms",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary
                    )
                }

                Column {
                    Text(
                        text = if (isFa) "افت بسته" else "Packet Loss",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val lossPct = ((result.packetLossRate ?: 0f) * 100).toInt()
                    Text(
                        text = "${LocalizationHelper.formatDigits(lossPct.toString(), usePersian)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = if (lossPct == 0) EmeraldAccent else AmberWarning
                    )
                }

                Column {
                    Text(
                        text = if (isFa) "بررسی‌شده" else "Evaluated",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${LocalizationHelper.formatDigits(result.candidateCount.toString(), usePersian)} ${if (isFa) "سرور" else "servers"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Explanation Text
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MidnightSurfaceVariant
            ) {
                Text(
                    text = if (isFa) result.explanationFa else result.explanationEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(10.dp)
                )
            }

            if (result.isSwitchedDueToFailover) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = AmberWarning.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AmberWarning,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isFa) "بازیابی خودکار: سرور قبلی غیرفعال شد و اتصال بدون وقفه به سرور پایدارتر منتقل گردید."
                            else "Auto-failover active: previous server degraded, seamless zero-drop transition applied.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberWarning,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Security Center Card:
 * Displays Shield status, overall security score (0..100), and quick management action.
 */
@Composable
fun SecurityCenterCard(
    audit: SecurityAuditReport,
    settings: AppSettings,
    isFa: Boolean,
    usePersian: Boolean,
    onOpenSecurityDialog: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("security_center_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightSurface),
        border = BorderStroke(1.dp, MidnightBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isFa) "مرکز امنیت و حفاظت نشت داده" else "Security & Leak Protection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = EmeraldAccent.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "${LocalizationHelper.formatDigits(audit.overallSecurityScore.toString(), usePersian)}% ${if (isFa) "ایمن" else "Secure"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldAccent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val dnsStatus = if (settings.dnsLeakProtection) "DNS: DoH" else "DNS: Direct"
                val ipv6Status = if (settings.ipv6LeakProtection) "IPv6: Blocked" else "IPv6: Open"
                val killSwitchStatus = if (settings.killSwitchEnabled) "KillSwitch: ON" else "KillSwitch: OFF"

                listOf(
                    dnsStatus to (if (settings.dnsLeakProtection) EmeraldAccent else AmberWarning),
                    ipv6Status to (if (settings.ipv6LeakProtection) EmeraldAccent else AmberWarning),
                    killSwitchStatus to (if (settings.killSwitchEnabled) EmeraldAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                ).forEach { (text, tint) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = tint.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = tint,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isFa) audit.summaryFa else audit.summaryEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onOpenSecurityDialog,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.5f)),
                    modifier = Modifier.testTag("manage_security_button")
                ) {
                    Text(
                        text = if (isFa) "مدیریت" else "Manage",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Security Center Configuration Dialog.
 */
@Composable
fun SecurityCenterDialog(
    settings: AppSettings,
    isFa: Boolean,
    onDismiss: () -> Unit,
    onToggleKillSwitch: (Boolean) -> Unit,
    onToggleDnsLeakProtection: (Boolean) -> Unit,
    onToggleIpv6LeakProtection: (Boolean) -> Unit,
    onSetSecureDnsProvider: (String) -> Unit,
    onToggleAutoFailover: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isFa) "مرکز امنیت و حفاظت نشت" else "Security Center",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. DNS Leak Protection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isFa) "مسدودسازی نشت DNS" else "DNS Leak Protection",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isFa) "هدایت تمام پرس‌وجوها به DoH امن برای جلوگیری از جاسوسی ISP"
                            else "Routes all queries through encrypted DoH to prevent ISP snooping.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.dnsLeakProtection,
                        onCheckedChange = onToggleDnsLeakProtection,
                        modifier = Modifier.testTag("toggle_dns_leak")
                    )
                }

                HorizontalDivider(color = MidnightBorder)

                // 2. IPv6 Leak Protection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isFa) "مسدودسازی نشت IPv6" else "IPv6 Leak Protection",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isFa) "مسدود کردن ترافیک IPv6 جهت جلوگیری از افشای IP واقعی اپراتور"
                            else "Blackholes IPv6 packets to prevent carrier IP disclosure.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.ipv6LeakProtection,
                        onCheckedChange = onToggleIpv6LeakProtection,
                        modifier = Modifier.testTag("toggle_ipv6_leak")
                    )
                }

                HorizontalDivider(color = MidnightBorder)

                // 3. Kill Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isFa) "کیل‌سوئیچ (Kill Switch)" else "VpnService Kill Switch",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isFa) "قطع کامل دسترسی اینترنت دستگاه در صورت قطع شدن غیرمنتظره وی‌پی‌ان"
                            else "Blocks all non-VPN device traffic if connection drops.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.killSwitchEnabled,
                        onCheckedChange = onToggleKillSwitch,
                        modifier = Modifier.testTag("toggle_kill_switch")
                    )
                }

                HorizontalDivider(color = MidnightBorder)

                // 4. Auto Failover
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isFa) "انتقال خودکار هنگام خرابی (Auto Failover)" else "Auto Failover",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isFa) "انتقال بدون وقفه تونل به سرور پشتیبان در صورت قطع شدن سرور فعلی"
                            else "Seamlessly switches to backup server if current one degrades.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.autoFailoverEnabled,
                        onCheckedChange = onToggleAutoFailover,
                        modifier = Modifier.testTag("toggle_auto_failover")
                    )
                }

                HorizontalDivider(color = MidnightBorder)

                // 5. Secure DNS Provider
                Column {
                    Text(
                        text = if (isFa) "ارائه‌دهنده DNS امن (DoH)" else "Secure DNS Provider (DoH)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Cloudflare", "Google", "AdGuard").forEach { provider ->
                            val isSelected = settings.secureDnsProvider.contains(provider, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSetSecureDnsProvider(provider) },
                                label = { Text(provider, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                                    selectedLabelColor = CyanPrimary
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(if (isFa) "تایید" else "Done", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * Connection History & Failover Logs Dialog.
 */
@Composable
fun ConnectionHistoryDialog(
    history: List<ConnectionHistoryItem>,
    isFa: Boolean,
    usePersian: Boolean,
    onDismiss: () -> Unit,
    onClearHistory: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = CyanPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isFa) "تاریخچه نشست‌ها و انتقال‌ها" else "Session History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (history.isNotEmpty()) {
                    IconButton(
                        onClick = onClearHistory,
                        modifier = Modifier.size(28.dp).testTag("clear_history_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear",
                            tint = CoralError,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        text = {
            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isFa) "هیچ سابقه‌ای ثبت نشده است" else "No connection history recorded yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(history) { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MidnightSurfaceVariant,
                            border = BorderStroke(1.dp, MidnightBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.profileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = CyanPrimary.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = item.protocol,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyanPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val durationSec = ((item.disconnectedAt ?: System.currentTimeMillis()) - item.connectedAt) / 1000L
                                    val durStr = "${durationSec / 60}m ${durationSec % 60}s"
                                    Text(
                                        text = "${if (isFa) "مدت:" else "Duration:"} $durStr",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    val downStr = LocalizationHelper.formatBytes(item.downloadBytes, usePersian)
                                    val upStr = LocalizationHelper.formatBytes(item.uploadBytes, usePersian)
                                    Text(
                                        text = "↓$downStr  ↑$upStr",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = EmeraldAccent
                                    )
                                }

                                if (!item.failoverReason.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "⚡ ${item.failoverReason}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AmberWarning
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(if (isFa) "بستن" else "Close", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * National Internet Resilience & Smart Global Connectivity Card (Section 12):
 * Displays evidence-based network classification, confidence score, domestic vs. international
 * reachability, authorized relay badge («از طریق مسیر واسط»), and expandable failure diagnostics.
 */
@Composable
fun NationalConnectivityResilienceCard(
    snapshot: ResilienceUiSnapshot,
    settings: AppSettings,
    isFa: Boolean,
    usePersian: Boolean,
    onRunAssessment: () -> Unit,
    onOpenRelayAndResilienceDialog: () -> Unit
) {
    var showFailureDrawer by remember { mutableStateOf(false) }

    val stateColor = when (snapshot.connectivityState) {
        NationalConnectivityState.NORMAL_INTERNATIONAL -> EmeraldAccent
        NationalConnectivityState.PARTIAL_INTERNATIONAL,
        NationalConnectivityState.RESTRICTED_INTERNATIONAL,
        NationalConnectivityState.NETWORK_UNSTABLE,
        NationalConnectivityState.CAPTIVE_PORTAL_SUSPECTED -> AmberWarning
        NationalConnectivityState.DOMESTIC_ONLY_SUSPECTED,
        NationalConnectivityState.NO_NETWORK,
        NationalConnectivityState.APP_BLOCKED_BY_OS -> CoralError
        NationalConnectivityState.UNKNOWN -> CyanPrimary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("national_connectivity_resilience_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightSurface),
        border = BorderStroke(1.dp, stateColor.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = stateColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isFa) "پایش هوشمند شبکه ملی و بین‌الملل" else "National & Global Network Resilience",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = snapshot.persianTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = stateColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("resilience_persian_status_title")
                        )
                    }
                }

                if (snapshot.confidencePercent > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = stateColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, stateColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "${if (isFa) "اطمینان:" else "Conf:"} ${LocalizationHelper.formatDigits(snapshot.confidencePercent.toString(), usePersian)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = stateColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (snapshot.isConnectedViaRelay) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = CyanPrimary.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f)),
                    modifier = Modifier.testTag("connected_via_relay_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${PersianConnectivityStrings.CONNECTED_VIA_RELAY} (${PersianConnectivityStrings.VIA_RELAY_BADGE})",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3-Badge Reachability Row: International | Domestic | Transport
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val intlColor = if (snapshot.internationalReachable) EmeraldAccent else CoralError
                val intlText = if (snapshot.internationalReachable) {
                    if (isFa) "بین‌الملل: در دسترس" else "Intl: Reachable"
                } else {
                    if (isFa) "بین‌الملل: محدود/نامشخص" else "Intl: Restricted"
                }

                val domColor = when (snapshot.domesticReachable) {
                    com.example.core.resilience.DomesticReachabilityState.YES -> AmberWarning
                    com.example.core.resilience.DomesticReachabilityState.NO -> CoralError
                    com.example.core.resilience.DomesticReachabilityState.NOT_TESTED -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val domText = when (snapshot.domesticReachable) {
                    com.example.core.resilience.DomesticReachabilityState.YES -> if (isFa) "داخلی: در دسترس" else "Domestic: YES"
                    com.example.core.resilience.DomesticReachabilityState.NO -> if (isFa) "داخلی: قطع" else "Domestic: NO"
                    com.example.core.resilience.DomesticReachabilityState.NOT_TESTED -> if (isFa) "داخلی: تست‌نشده" else "Domestic: N/A"
                }

                val netText = "Net: ${snapshot.activeNetworkType.name}"

                listOf(
                    intlText to intlColor,
                    domText to domColor,
                    netText to CyanPrimary
                ).forEach { (label, tint) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = tint.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 5.dp, horizontal = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = snapshot.persianSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onRunAssessment,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("run_resilience_assessment_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isFa) "سنجش وضعیت شبکه" else "Assess Network",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onOpenRelayAndResilienceDialog,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldAccent),
                    border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_relay_resilience_dialog_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isFa) "رله مجاز و پایداری" else "Relays & Resilience",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (snapshot.recentFailures.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = { showFailureDrawer = !showFailureDrawer },
                    modifier = Modifier.testTag("toggle_resilience_failure_drawer")
                ) {
                    Text(
                        text = if (showFailureDrawer) {
                            if (isFa) "بستن جزئیات فنی خطاها" else "Hide failure diagnostics"
                        } else {
                            if (isFa) "مشاهده جزئیات فنی (${snapshot.recentFailures.size} رویداد)" else "Show technical details (${snapshot.recentFailures.size})"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberWarning
                    )
                }

                AnimatedVisibility(
                    visible = showFailureDrawer,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MidnightSurfaceVariant,
                        border = BorderStroke(1.dp, MidnightBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            snapshot.recentFailures.take(5).forEach { rec ->
                                Text(
                                    text = "• [${rec.category.name}] (${rec.endpointId} / ${rec.portBucket}): ${rec.sanitizedDetail}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog for configuring Authorized Relays (Mode A / Mode A2), custom domestic HTTPS probe
 * endpoints, and National Internet Resilience switches.
 */
@Composable
fun AuthorizedRelayAndResilienceDialog(
    settings: AppSettings,
    isFa: Boolean,
    onDismiss: () -> Unit,
    onToggleMasterResilience: (Boolean) -> Unit,
    onToggleLowDataMode: (Boolean) -> Unit,
    onToggleRuntimeVariants: (Boolean) -> Unit,
    onToggleRelayFallback: (Boolean) -> Unit,
    onAddAuthorizedRelay: (AuthorizedRelay) -> Unit,
    onRemoveAuthorizedRelay: (String) -> Unit,
    onAddCustomDomesticEndpoint: (String) -> Unit,
    onRemoveCustomDomesticEndpoint: (String) -> Unit
) {
    val configuredRelays = remember(settings.authorizedRelaysJson) {
        ResilienceJsonSerializer.parseAuthorizedRelays(settings.authorizedRelaysJson)
    }
    var relayName by remember { mutableStateOf("") }
    var relayAddress by remember { mutableStateOf("") }
    var relayPortText by remember { mutableStateOf("443") }
    var ownerAttested by remember { mutableStateOf(false) }
    var domesticUrlInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("authorized_relay_resilience_dialog"),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Public, contentDescription = null, tint = CyanPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isFa) "پایداری شبکه ملی و رله‌های مجاز" else "National Resilience & Authorized Relays",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFa) "موتور هوشمند پایداری اینترنت ملی" else "Master Resilience Engine",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isFa) "تشخیص وضعیت شبکه، بازیابی چندمسیره و مدار قطع‌کننده"
                                else "Multi-signal network detection, path recovery & circuit breakers.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.resilienceMasterEnabled,
                            onCheckedChange = onToggleMasterResilience,
                            modifier = Modifier.testTag("toggle_master_resilience")
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFa) "حالت کم‌مصرف کاوش شبکه (Low-Data)" else "Low-Data Probing Mode",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isFa) "محدودسازی حجم کاوش به کمتر از ۲۵۶ کیلوبایت در ساعت"
                                else "Caps background probe budget on metered cellular networks.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.lowDataProbingMode,
                            onCheckedChange = onToggleLowDataMode,
                            modifier = Modifier.testTag("toggle_low_data_probing")
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFa) "بهینه‌سازی تطبیقی uTLS / ALPN در حافظه" else "Adaptive Runtime Transport Variants",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isFa) "امتحان اثرانگشت‌های TLS جایگزین بدون تغییر پروفایل ذخیره‌شده"
                                else "Tries compatible ALPN/uTLS variants in memory without altering Room DB.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.allowRuntimeTransportVariants,
                            onCheckedChange = onToggleRuntimeVariants,
                            modifier = Modifier.testTag("toggle_runtime_variants")
                        )
                    }
                }

                item {
                    HorizontalDivider(color = MidnightBorder)
                }

                // Authorized Relays Section (Mode A2)
                item {
                    Text(
                        text = if (isFa) "رله‌های واسط مجاز (Authorized Relays - Mode A2)" else "Authorized Relays (Mode A2)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isFa) {
                            "فقط سرورهای واسطی را وارد کنید که تحت مالکیت یا مجوز مستقیم شما هستند و ترافیک TLS/TCP را به سرور بین‌المللی شما فوروارد می‌کنند."
                        } else {
                            "Only add relay hosts owned or explicitly authorized by you that forward TCP/TLS to your international gateway."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(configuredRelays, key = { it.id }) { relay ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MidnightSurfaceVariant,
                        border = BorderStroke(1.dp, MidnightBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = relay.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${relay.address}:${relay.port} • ${relay.mode.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = CyanPrimary
                                )
                            }
                            IconButton(
                                onClick = { onRemoveAuthorizedRelay(relay.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove relay",
                                    tint = CoralError,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = relayName,
                        onValueChange = { relayName = it },
                        label = { Text(if (isFa) "نام رله مجاز" else "Relay Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("relay_name_input")
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = relayAddress,
                            onValueChange = { relayAddress = it },
                            label = { Text(if (isFa) "آدرس IP یا دامنه رله" else "Relay Host / IP") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(2f)
                                .testTag("relay_address_input")
                        )
                        OutlinedTextField(
                            value = relayPortText,
                            onValueChange = { relayPortText = it },
                            label = { Text(if (isFa) "پورت" else "Port") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("relay_port_input")
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ownerAttested = !ownerAttested }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = ownerAttested,
                            onCheckedChange = { ownerAttested = it },
                            modifier = Modifier.testTag("relay_owner_attested_checkbox")
                        )
                        Text(
                            text = if (isFa) "تأیید می‌کنم این رله تحت مالکیت یا مجوز مستقیم من است"
                            else "I attest this relay is owned or explicitly authorized by me",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = {
                            val port = relayPortText.trim().toIntOrNull() ?: 443
                            if (relayAddress.isNotBlank() && port in 1..65535 && ownerAttested) {
                                val id = "relay_${System.currentTimeMillis()}"
                                onAddAuthorizedRelay(
                                    AuthorizedRelay(
                                        id = id,
                                        name = relayName.trim().ifEmpty { "${relayAddress.trim()}:$port" },
                                        host = relayAddress.trim(),
                                        port = port,
                                        mode = RelayMode.MODE_A2_TRAFFIC_FORWARDER,
                                        enabled = true,
                                        ownerAttested = true
                                    )
                                )
                                relayName = ""
                                relayAddress = ""
                                relayPortText = "443"
                                ownerAttested = false
                            }
                        },
                        enabled = relayAddress.isNotBlank() && ownerAttested,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_authorized_relay_button")
                    ) {
                        Text(
                            text = if (isFa) "افزودن رله مجاز" else "Add Authorized Relay",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                item {
                    HorizontalDivider(color = MidnightBorder)
                }

                // Custom Domestic Probe Endpoints Section
                item {
                    Text(
                        text = if (isFa) "نقاط سنجش داخلی مجاز (فقط تشخیصی - HTTPS)" else "Custom Domestic HTTPS Probe Endpoints (Diagnostic Only)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = AmberWarning
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isFa) {
                            "این آدرس‌ها صرفاً برای تشخیص وضعیت «فقط شبکه داخلی» استفاده می‌شوند و هرگز به عنوان پروکسی یا رله استفاده نمی‌گردند."
                        } else {
                            "Used strictly to distinguish domestic-only connectivity from total network loss; never used for traffic tunneling."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(settings.customDomesticProbeUrls.toList(), key = { it }) { domUrl ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MidnightSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = domUrl,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onRemoveCustomDomesticEndpoint(domUrl) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove endpoint",
                                    tint = CoralError,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = domesticUrlInput,
                            onValueChange = { domesticUrlInput = it },
                            label = { Text("https://...") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("custom_domestic_url_input")
                        )
                        OutlinedButton(
                            onClick = {
                                if (domesticUrlInput.isNotBlank()) {
                                    onAddCustomDomesticEndpoint(domesticUrlInput.trim())
                                    domesticUrlInput = ""
                                }
                            },
                            modifier = Modifier.testTag("add_custom_domestic_url_button")
                        ) {
                            Text(if (isFa) "افزودن" else "Add")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(if (isFa) "بستن" else "Done", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    )
}
