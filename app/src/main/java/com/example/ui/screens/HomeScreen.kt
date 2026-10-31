package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.AppSettings
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.RoutingMode
import com.example.domain.model.ServerProfile
import com.example.domain.model.VpnConnectionState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.util.LocalizationHelper
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    connectionState: VpnConnectionState,
    activeProfile: ServerProfile?,
    statistics: EngineStatistics,
    speedHistory: List<Pair<Long, Long>>,
    settings: AppSettings,
    onToggleConnect: () -> Unit,
    onTestActiveLatency: (ServerProfile) -> Unit,
    onAutoSelectBest: () -> Unit,
    onSwitchRoutingMode: (RoutingMode) -> Unit,
    onSwitchEngine: (EngineType) -> Unit,
    onToggleLanguage: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onCompleteOnboarding: () -> Unit
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectionState) {
        while (connectionState == VpnConnectionState.CONNECTED) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val usePersian = settings.usePersianDigits || settings.languageCode.startsWith("fa", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 600.dp)) {
            // Top Branding & Engine/Language Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = stringResource(R.string.app_name),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {
                            val next = if (settings.activeEngine == EngineType.XRAY_CORE) {
                                EngineType.SING_BOX
                            } else {
                                EngineType.XRAY_CORE
                            }
                            onSwitchEngine(next)
                        },
                        label = {
                            Text(
                                text = if (settings.activeEngine == EngineType.XRAY_CORE) "Xray-core" else "sing-box",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = stringResource(R.string.active_engine_label),
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("engine_switch_chip")
                    )

                    AssistChip(
                        onClick = onToggleLanguage,
                        label = {
                            Text(
                                text = if (settings.languageCode.startsWith("fa")) "EN" else "فا",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = stringResource(R.string.language_label),
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("language_toggle_chip")
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Onboarding Banner (shown until dismissed)
            if (!settings.onboardingCompleted) {
                OnboardingHeroCard(onDismiss = onCompleteOnboarding)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Large Animated Connect / Disconnect Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                ConnectionPowerOrb(
                    state = connectionState,
                    onClick = onToggleConnect
                )
            }

            // Connection Status Pill
            val statusText = when (connectionState) {
                VpnConnectionState.DISCONNECTED -> stringResource(R.string.state_disconnected)
                VpnConnectionState.PREPARING -> stringResource(R.string.state_preparing)
                VpnConnectionState.STARTING_ENGINE -> stringResource(R.string.state_starting_engine)
                VpnConnectionState.CONNECTING -> stringResource(R.string.state_connecting)
                VpnConnectionState.VERIFYING -> stringResource(R.string.state_verifying)
                VpnConnectionState.CONNECTED -> stringResource(R.string.state_connected)
                VpnConnectionState.RECONNECTING -> stringResource(R.string.state_reconnecting)
                VpnConnectionState.STOPPING -> stringResource(R.string.state_stopping)
                VpnConnectionState.ERROR -> stringResource(R.string.state_error)
            }
            val statusColor = when (connectionState) {
                VpnConnectionState.CONNECTED -> EmeraldAccent
                VpnConnectionState.PREPARING,
                VpnConnectionState.STARTING_ENGINE,
                VpnConnectionState.CONNECTING,
                VpnConnectionState.VERIFYING,
                VpnConnectionState.RECONNECTING,
                VpnConnectionState.STOPPING -> AmberWarning
                VpnConnectionState.ERROR -> CoralError
                VpnConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor.copy(alpha = 0.14f),
                    modifier = Modifier.testTag("connection_status_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Selected Server Profile Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToProfiles() }
                    .testTag("selected_server_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeProfile?.remarks ?: stringResource(R.string.no_profile_selected),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (activeProfile != null) {
                                    "${activeProfile.protocol.displayName} • ${activeProfile.network.displayName} • ${activeProfile.security.displayName} • ${activeProfile.address}:${
                                        LocalizationHelper.formatDigits(activeProfile.port.toString(), usePersian)
                                    }"
                                } else {
                                    stringResource(R.string.select_or_import_profile)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Real Latency Badge
                        if (activeProfile != null) {
                            val latencyLabel = when {
                                activeProfile.lastLatencyMs != null -> {
                                    "${LocalizationHelper.formatDigits(activeProfile.lastLatencyMs.toString(), usePersian)} ms"
                                }
                                activeProfile.latencyType == LatencyMeasureType.TIMEOUT -> stringResource(R.string.latency_timeout)
                                else -> stringResource(R.string.latency_untested)
                            }
                            val latencyColor = when {
                                activeProfile.lastLatencyMs != null && activeProfile.lastLatencyMs < 180 -> EmeraldAccent
                                activeProfile.lastLatencyMs != null -> AmberWarning
                                activeProfile.latencyType == LatencyMeasureType.TIMEOUT -> CoralError
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = latencyLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = latencyColor,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = activeProfile.latencyType.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { activeProfile?.let(onTestActiveLatency) },
                            enabled = activeProfile != null,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("test_active_latency_button")
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.test_latency_btn))
                        }
                        OutlinedButton(
                            onClick = onAutoSelectBest,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("auto_best_server_button")
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.auto_select_best))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Routing Mode Selector (Global / Rule-Based / Direct)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.nav_routing),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RoutingModeChip(
                            label = stringResource(R.string.routing_mode_global),
                            selected = settings.routingMode == RoutingMode.GLOBAL,
                            onClick = { onSwitchRoutingMode(RoutingMode.GLOBAL) },
                            testTag = "routing_mode_global"
                        )
                        RoutingModeChip(
                            label = stringResource(R.string.routing_mode_rule),
                            selected = settings.routingMode == RoutingMode.RULE_BASED,
                            onClick = { onSwitchRoutingMode(RoutingMode.RULE_BASED) },
                            testTag = "routing_mode_rule"
                        )
                        RoutingModeChip(
                            label = stringResource(R.string.routing_mode_direct),
                            selected = settings.routingMode == RoutingMode.DIRECT,
                            onClick = { onSwitchRoutingMode(RoutingMode.DIRECT) },
                            testTag = "routing_mode_direct"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Real Traffic Statistics Grid (Never fabricated)
            val isConnected = connectionState == VpnConnectionState.CONNECTED
            val upSpeedStr = if (!isConnected) {
                LocalizationHelper.formatSpeed(0L, usePersian)
            } else if (statistics.isTelemetryAuthentic) {
                LocalizationHelper.formatSpeed(statistics.uploadSpeedBps, usePersian)
            } else {
                stringResource(R.string.stat_unavailable)
            }

            val downSpeedStr = if (!isConnected) {
                LocalizationHelper.formatSpeed(0L, usePersian)
            } else if (statistics.isTelemetryAuthentic) {
                LocalizationHelper.formatSpeed(statistics.downloadSpeedBps, usePersian)
            } else {
                stringResource(R.string.stat_unavailable)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.upload_speed),
                    value = upSpeedStr,
                    subtitle = LocalizationHelper.formatBytes(statistics.sessionUploadBytes, usePersian),
                    icon = Icons.Default.Upload,
                    accent = CyanPrimary
                )
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.download_speed),
                    value = downSpeedStr,
                    subtitle = LocalizationHelper.formatBytes(statistics.sessionDownloadBytes, usePersian),
                    icon = Icons.Default.Download,
                    accent = EmeraldAccent
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.session_duration),
                    value = LocalizationHelper.formatDuration(statistics.connectedSinceMs, nowMs, usePersian),
                    subtitle = settings.activeEngine.displayName,
                    icon = Icons.Default.Schedule,
                    accent = MaterialTheme.colorScheme.primary
                )
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.session_traffic),
                    value = LocalizationHelper.formatBytes(
                        statistics.sessionUploadBytes + statistics.sessionDownloadBytes,
                        usePersian
                    ),
                    subtitle = "Total: ${
                        LocalizationHelper.formatBytes(
                            statistics.totalUploadBytes + statistics.totalDownloadBytes,
                            usePersian
                        )
                    }",
                    icon = Icons.Default.SwapVert,
                    accent = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 60-Second Real Traffic Chart
            TrafficSparklineCard(speedHistory = speedHistory)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OnboardingHeroCard(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Image(
                painter = painterResource(id = R.drawable.img_vpn_hero),
                contentDescription = stringResource(R.string.onboarding_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OnboardingBullet(stringResource(R.string.onboarding_feature_privacy))
                OnboardingBullet(stringResource(R.string.onboarding_feature_engines))
                OnboardingBullet(stringResource(R.string.onboarding_feature_bilingual))
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_get_started_button")
                ) {
                    Text(stringResource(R.string.onboarding_get_started))
                }
            }
        }
    }
}

@Composable
private fun OnboardingBullet(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = EmeraldAccent,
            modifier = Modifier
                .size(16.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ConnectionPowerOrb(
    state: VpnConnectionState,
    onClick: () -> Unit
) {
    val isConnected = state == VpnConnectionState.CONNECTED
    val isBusy = state in setOf(
        VpnConnectionState.PREPARING,
        VpnConnectionState.STARTING_ENGINE,
        VpnConnectionState.CONNECTING,
        VpnConnectionState.VERIFYING,
        VpnConnectionState.RECONNECTING,
        VpnConnectionState.STOPPING
    )

    val infiniteTransition = rememberInfiniteTransition(label = "orbPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isConnected || isBusy) 1.08f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val ringColor by animateColorAsState(
        targetValue = when (state) {
            VpnConnectionState.CONNECTED -> EmeraldAccent
            VpnConnectionState.PREPARING,
            VpnConnectionState.STARTING_ENGINE,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.VERIFYING,
            VpnConnectionState.RECONNECTING,
            VpnConnectionState.STOPPING -> AmberWarning
            VpnConnectionState.ERROR -> CoralError
            VpnConnectionState.DISCONNECTED -> CyanPrimary
        },
        label = "orbRingColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(176.dp)
    ) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(168.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            ringColor.copy(alpha = 0.25f),
                            ringColor.copy(alpha = 0.04f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Main interactive circular power button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(136.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(width = 3.dp, color = ringColor, shape = CircleShape)
                .clickable { onClick() }
                .testTag("connect_toggle_button")
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = if (isConnected) {
                        stringResource(R.string.tap_to_disconnect)
                    } else {
                        stringResource(R.string.tap_to_connect)
                    },
                    tint = ringColor,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isConnected) {
                        stringResource(R.string.tap_to_disconnect)
                    } else {
                        stringResource(R.string.tap_to_connect)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RoutingModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        modifier = Modifier.testTag(testTag)
    )
}

@Composable
private fun StatMetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TrafficSparklineCard(speedHistory: List<Pair<Long, Long>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "60s Real-Time Throughput",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "TX / RX",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                val width = size.width
                val height = size.height
                // Baseline grid
                drawLine(
                    color = Color.Gray.copy(alpha = 0.2f),
                    start = Offset(0f, height - 2f),
                    end = Offset(width, height - 2f),
                    strokeWidth = 1.dp.toPx()
                )
                if (speedHistory.size >= 2) {
                    val maxVal = speedHistory.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(1024L).toFloat()
                    val stepX = width / 59f
                    val upPath = Path()
                    val downPath = Path()
                    speedHistory.forEachIndexed { idx, (up, down) ->
                        val x = idx * stepX
                        val yUp = height - ((up.toFloat() / maxVal) * (height - 8f)).coerceIn(2f, height - 2f)
                        val yDown = height - ((down.toFloat() / maxVal) * (height - 8f)).coerceIn(2f, height - 2f)
                        if (idx == 0) {
                            upPath.moveTo(x, yUp)
                            downPath.moveTo(x, yDown)
                        } else {
                            upPath.lineTo(x, yUp)
                            downPath.lineTo(x, yDown)
                        }
                    }
                    drawPath(upPath, color = CyanPrimary, style = Stroke(width = 2.dp.toPx()))
                    drawPath(downPath, color = EmeraldAccent, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}
