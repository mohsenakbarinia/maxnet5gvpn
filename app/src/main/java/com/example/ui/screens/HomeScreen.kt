package com.example.ui.screens

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.autovpn.AutoQualityGrade
import com.example.core.autovpn.AutoVpnStage
import com.example.core.autovpn.AutoVpnStatusSnapshot
import com.example.core.autovpn.ConnectionOwnership
import com.example.core.autovpn.VpnAppMode
import com.example.domain.model.AppSettings
import com.example.domain.model.ConnectionQualityReport
import com.example.domain.model.DiagnosticSnapshot
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.QualityTestStage
import com.example.domain.model.RoutingMode
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnProtocol
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MidnightBackground
import com.example.ui.theme.MidnightBorder
import com.example.ui.theme.MidnightSurface
import com.example.ui.theme.MidnightSurfaceVariant
import com.example.util.LocalizationHelper
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    connectionState: VpnConnectionState,
    activeProfile: ServerProfile?,
    personalProfile: ServerProfile? = activeProfile,
    selectedVpnMode: VpnAppMode = VpnAppMode.FREE_VPN,
    ownership: ConnectionOwnership = ConnectionOwnership.NONE,
    statistics: EngineStatistics,
    diagnostics: DiagnosticSnapshot = DiagnosticSnapshot(),
    qualityReport: ConnectionQualityReport = ConnectionQualityReport(),
    autoVpnStatus: AutoVpnStatusSnapshot = AutoVpnStatusSnapshot(),
    speedHistory: List<Pair<Long, Long>>,
    settings: AppSettings,
    onSwitchVpnMode: (VpnAppMode) -> Unit = {},
    onToggleConnect: () -> Unit,
    onTestActiveLatency: (ServerProfile) -> Unit,
    onAutoSelectBest: () -> Unit,
    onRunQualityTest: () -> Unit = {},
    onCancelQualityTest: () -> Unit = {},
    onSwitchRoutingMode: (RoutingMode) -> Unit,
    onSwitchEngine: (EngineType) -> Unit,
    onToggleLanguage: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onCompleteOnboarding: () -> Unit = {}
) {
    val isFa = settings.languageCode.startsWith("fa", ignoreCase = true)
    val usePersian = settings.usePersianDigits || isFa
    val context = LocalContext.current
    val isRobolectric = remember {
        runCatching { Class.forName("androidx.test.core.app.ApplicationProvider") }.isSuccess ||
            android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
    }
    val animatorScale = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
        }.getOrDefault(1.0f)
    }
    val reducedMotion = animatorScale == 0f || isRobolectric

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectionState, reducedMotion) {
        nowMs = System.currentTimeMillis()
        while (connectionState == VpnConnectionState.CONNECTED && !reducedMotion) {
            delay(1000L)
            nowMs = System.currentTimeMillis()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MidnightBackground,
                        Color(0xFF0B1426),
                        MidnightBackground
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // =========================================================================
                // SECTION A — Compact Header
                // =========================================================================
                CompactDashboardHeader(
                    settings = settings,
                    connectionState = connectionState,
                    activeProfile = activeProfile,
                    isFa = isFa,
                    onSwitchEngine = onSwitchEngine,
                    onToggleLanguage = onToggleLanguage,
                    onNavigateToSettings = onNavigateToSettings
                )

                Spacer(modifier = Modifier.height(10.dp))

                // =========================================================================
                // SECTION B — Dual VPN Mode Selector (FREE VPN vs MY VPN) + Ownership Badge
                // =========================================================================
                DualVpnModeSelectorCard(
                    selectedMode = selectedVpnMode,
                    ownership = ownership,
                    isFa = isFa,
                    onSelectMode = onSwitchVpnMode
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (selectedVpnMode == VpnAppMode.FREE_VPN) {
                            if (isFa) "وی‌پی‌ان رایگان هوشمند (مخزن گیت‌هاب)" else "Intelligent Auto Free VPN"
                        } else {
                            if (isFa) "وی‌پی‌ان شخصی من (پروفایل‌ها و اشتراک‌ها)" else "My Personal VPN"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (selectedVpnMode == VpnAppMode.FREE_VPN) {
                            if (isFa) "کشف، تست موازی و اتصال به بهترین سرور فقط با یک لمس" else "One-tap automatic discovery, parallel testing & optimal connection"
                        } else {
                            if (isFa) "مدیریت مستقل کانفیگ‌ها و اشتراک‌های شخصی بدون تداخل با سرورهای رایگان" else "Independent personal profiles & subscriptions isolated from Free VPN"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // =========================================================================
                // SECTION C — Main VPN Control (One Action: Tap to Connect)
                // =========================================================================
                PremiumVpnPowerControl(
                    state = connectionState,
                    autoVpnStatus = autoVpnStatus,
                    connectedSinceMs = statistics.connectedSinceMs,
                    nowMs = nowMs,
                    isFa = isFa,
                    usePersian = usePersian,
                    reducedMotion = reducedMotion,
                    lastError = diagnostics.lastError,
                    onToggleConnect = onToggleConnect
                )

                Spacer(modifier = Modifier.height(16.dp))

                // =========================================================================
                // SECTION D — Mode-Specific Active Status Card (Free VPN vs My VPN)
                // =========================================================================
                ActiveServerCard(
                    selectedVpnMode = selectedVpnMode,
                    activeProfile = activeProfile,
                    personalProfile = personalProfile,
                    connectionState = connectionState,
                    autoVpnStatus = autoVpnStatus,
                    isFa = isFa,
                    usePersian = usePersian,
                    onTestActiveLatency = onTestActiveLatency,
                    onAutoSelectBest = onAutoSelectBest,
                    onNavigateToProfiles = onNavigateToProfiles
                )

                Spacer(modifier = Modifier.height(12.dp))

                // =========================================================================
                // SECTION E — REAL NETWORK PERFORMANCE (Ping | Download | Upload)
                // =========================================================================
                RealNetworkPerformanceCard(
                    connectionState = connectionState,
                    activeProfile = activeProfile,
                    statistics = statistics,
                    qualityReport = qualityReport,
                    isFa = isFa,
                    usePersian = usePersian,
                    onTestPing = { activeProfile?.let(onTestActiveLatency) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // =========================================================================
                // SECTION F — CONNECTION QUALITY TEST
                // =========================================================================
                ConnectionQualityTestSection(
                    qualityReport = qualityReport,
                    isFa = isFa,
                    usePersian = usePersian,
                    onRunTest = onRunQualityTest,
                    onCancelTest = onCancelQualityTest
                )

                Spacer(modifier = Modifier.height(12.dp))

                // =========================================================================
                // SECTION G — CONNECTION INFORMATION (Visible when Connected)
                // =========================================================================
                AnimatedVisibility(
                    visible = connectionState == VpnConnectionState.CONNECTED,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        ActiveConnectionInfoCard(
                            activeProfile = activeProfile,
                            statistics = statistics,
                            settings = settings,
                            nowMs = nowMs,
                            isFa = isFa,
                            usePersian = usePersian
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Compact Routing Mode Bar (Global / Rule-Based / Direct)
                CompactRoutingBar(
                    selectedMode = settings.routingMode,
                    isFa = isFa,
                    onSwitchRoutingMode = onSwitchRoutingMode
                )

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun CompactDashboardHeader(
    settings: AppSettings,
    connectionState: VpnConnectionState,
    activeProfile: ServerProfile?,
    isFa: Boolean,
    onSwitchEngine: (EngineType) -> Unit,
    onToggleLanguage: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val isConnected = connectionState == VpnConnectionState.CONNECTED
    val shieldTint = if (isConnected) EmeraldAccent else CyanPrimary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MidnightSurface.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MidnightBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(shieldTint.copy(alpha = 0.14f))
                        .border(1.dp, shieldTint.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = stringResource(R.string.app_name),
                        tint = shieldTint,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val secBadge = when {
                        isConnected && activeProfile != null -> {
                            if (isFa) "تونل رمزنگاری‌شده (${activeProfile.security.displayName})"
                            else "Encrypted (${activeProfile.security.displayName})"
                        }
                        activeProfile != null -> {
                            if (isFa) "آماده اتصال • ${activeProfile.protocol.displayName}"
                            else "Ready • ${activeProfile.protocol.displayName}"
                        }
                        else -> if (isFa) "کلاینت امن دو موتوره" else "Dual-Engine Client"
                    }
                    Text(
                        text = secBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isConnected) EmeraldAccent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Engine toggle badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MidnightSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MidnightBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val next = if (settings.activeEngine == EngineType.XRAY_CORE) {
                                EngineType.SING_BOX
                            } else {
                                EngineType.XRAY_CORE
                            }
                            onSwitchEngine(next)
                        }
                        .testTag("engine_switch_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = stringResource(R.string.active_engine_label),
                            tint = CyanPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (settings.activeEngine == EngineType.XRAY_CORE) "Xray" else "sing-box",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Language toggle badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MidnightSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MidnightBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onToggleLanguage() }
                        .testTag("language_toggle_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = stringResource(R.string.language_label),
                            tint = CyanPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isFa) "EN" else "فا",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Settings shortcut icon button
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MidnightSurfaceVariant)
                        .testTag("header_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.nav_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumVpnPowerControl(
    state: VpnConnectionState,
    autoVpnStatus: AutoVpnStatusSnapshot,
    connectedSinceMs: Long?,
    nowMs: Long,
    isFa: Boolean,
    usePersian: Boolean,
    reducedMotion: Boolean,
    lastError: String?,
    onToggleConnect: () -> Unit
) {
    val isAutoDiscoveringOrTesting = autoVpnStatus.stage in setOf(
        AutoVpnStage.DISCOVERING_CONFIGS,
        AutoVpnStage.TESTING_CANDIDATES,
        AutoVpnStage.RANKING_SERVERS,
        AutoVpnStage.FAILOVER_SWITCHING
    )
    val isConnected = state == VpnConnectionState.CONNECTED
    val isReconnectingOrFailover = state == VpnConnectionState.RECONNECTING ||
        autoVpnStatus.stage == AutoVpnStage.FAILOVER_SWITCHING
    val isConnecting = isAutoDiscoveringOrTesting || state in setOf(
        VpnConnectionState.PREPARING,
        VpnConnectionState.STARTING_ENGINE,
        VpnConnectionState.CONNECTING,
        VpnConnectionState.VERIFYING,
        VpnConnectionState.RECONNECTING
    )
    val isDisconnecting = state == VpnConnectionState.STOPPING
    val isError = state == VpnConnectionState.ERROR || autoVpnStatus.stage == AutoVpnStage.EXHAUSTED_ERROR

    val primaryButtonLabel = when {
        isConnected -> if (isFa) "متصل هستید" else "Connected"
        isDisconnecting -> if (isFa) "در حال قطع اتصال..." else "Disconnecting..."
        isReconnectingOrFailover -> if (isFa) "اتصال مجدد هوشمند..." else "Reconnecting..."
        isConnecting -> if (isFa) "در حال اتصال..." else "Connecting..."
        isError -> if (isFa) "خطا در اتصال" else "Connection Error"
        else -> if (isFa) "اتصال هوشمند" else "Tap to Connect"
    }

    val detailedStageLabel = when {
        isConnected -> if (isFa) "متصل • تونل هوشمند بهینه‌شده" else "Connected • Auto-Optimized Tunnel"
        isReconnectingOrFailover -> if (isFa) autoVpnStatus.stage.titleFa else autoVpnStatus.stage.titleEn
        isAutoDiscoveringOrTesting -> if (isFa) autoVpnStatus.stage.titleFa else autoVpnStatus.stage.titleEn
        state == VpnConnectionState.PREPARING -> if (isFa) "در حال آماده‌سازی بهترین کانفیگ..." else "Preparing Optimal Config..."
        state == VpnConnectionState.STARTING_ENGINE -> if (isFa) "راه‌اندازی هسته Xray-core..." else "Starting Xray-core..."
        state == VpnConnectionState.CONNECTING -> if (isFa) "برقراری تونل امن..." else "Establishing Tunnel..."
        state == VpnConnectionState.VERIFYING -> if (isFa) "تایید عملیاتی بودن تونل (HTTP 204)..." else "Verifying Tunnel (HTTP 204)..."
        state == VpnConnectionState.STOPPING -> if (isFa) "در حال قطع اتصال..." else "Disconnecting..."
        isError -> if (isFa) "خطا در برقراری اتصال" else "Connection Failed"
        else -> if (isFa) "آماده اتصال خودکار با یک لمس" else "Ready • One-Tap Auto Connect"
    }

    val orbAccentColor by animateColorAsState(
        targetValue = when {
            isConnected -> EmeraldAccent
            isReconnectingOrFailover -> AmberWarning
            isConnecting -> CyanPrimary
            isDisconnecting -> AmberWarning
            isError -> CoralError
            else -> CyanPrimary.copy(alpha = 0.82f)
        },
        animationSpec = tween(durationMillis = 320),
        label = "orbAccentColor"
    )

    val glowPulse: Float
    val sweepAngleOffset: Float
    if (!reducedMotion && (isConnected || isConnecting || isDisconnecting)) {
        val infiniteTransition = rememberInfiniteTransition(label = "powerOrbTransition")
        val animPulse by infiniteTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.09f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowPulse"
        )
        val animSweep by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1350, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sweepAngleOffset"
        )
        glowPulse = animPulse
        sweepAngleOffset = animSweep
    } else {
        glowPulse = 0.96f
        sweepAngleOffset = -90f
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(196.dp)
        ) {
            // Subtle outer ambient radial glow
            Box(
                modifier = Modifier
                    .size(188.dp)
                    .scale(glowPulse)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                orbAccentColor.copy(alpha = if (isConnected || isConnecting) 0.28f else 0.14f),
                                orbAccentColor.copy(alpha = 0.05f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Animated progress arc when CONNECTING or DISCONNECTING
            if (isConnecting || isDisconnecting) {
                Canvas(modifier = Modifier.size(162.dp)) {
                    drawArc(
                        color = orbAccentColor.copy(alpha = 0.22f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = orbAccentColor,
                        startAngle = if (reducedMotion) -90f else sweepAngleOffset,
                        sweepAngle = 110f,
                        useCenter = false,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Main Circular Power Button (One Action: Tap to Connect)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(146.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MidnightSurfaceVariant,
                                MidnightSurface
                            )
                        )
                    )
                    .border(
                        width = if (isConnected) 3.dp else 2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                orbAccentColor,
                                orbAccentColor.copy(alpha = 0.45f)
                            )
                        ),
                        shape = CircleShape
                    )
                    .clickable(enabled = !isDisconnecting) { onToggleConnect() }
                    .testTag("connect_toggle_button")
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(orbAccentColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = primaryButtonLabel,
                            tint = orbAccentColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = primaryButtonLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isConnected) EmeraldAccent else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }

        // Status Pill
        Surface(
            shape = RoundedCornerShape(50),
            color = orbAccentColor.copy(alpha = 0.13f),
            border = androidx.compose.foundation.BorderStroke(1.dp, orbAccentColor.copy(alpha = 0.35f)),
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("connection_status_chip")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(orbAccentColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = detailedStageLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = orbAccentColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Connection Duration Badge right beneath status pill when connected
        AnimatedVisibility(visible = isConnected && connectedSinceMs != null) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = "⏱ ${LocalizationHelper.formatDuration(connectedSinceMs, nowMs, usePersian)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = EmeraldAccent,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .testTag("hero_session_timer")
                )
            }
        }

        // Error Banner + Retry Button when in ERROR state
        AnimatedVisibility(
            visible = isError,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag("connection_error_banner"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CoralError.copy(alpha = 0.12f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, CoralError.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = CoralError,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = lastError
                                ?: if (isFa) autoVpnStatus.detailMessageFa else autoVpnStatus.detailMessageEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = onToggleConnect,
                        modifier = Modifier.testTag("retry_connection_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = CoralError,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isFa) "تلاش مجدد" else "Retry",
                            color = CoralError,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DualVpnModeSelectorCard(
    selectedMode: VpnAppMode,
    ownership: ConnectionOwnership,
    isFa: Boolean,
    onSelectMode: (VpnAppMode) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dual_vpn_mode_selector"),
        shape = RoundedCornerShape(16.dp),
        color = MidnightSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MidnightBorder)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isFreeSelected = selectedMode == VpnAppMode.FREE_VPN
                val isMyVpnSelected = selectedMode == VpnAppMode.MY_VPN

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isFreeSelected) CyanPrimary.copy(alpha = 0.16f) else MidnightSurfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isFreeSelected) CyanPrimary else MidnightBorder
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectMode(VpnAppMode.FREE_VPN) }
                        .testTag("mode_selector_free_vpn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (isFreeSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isFa) VpnAppMode.FREE_VPN.titleFa else VpnAppMode.FREE_VPN.titleEn,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isFreeSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isFreeSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isMyVpnSelected) EmeraldAccent.copy(alpha = 0.16f) else MidnightSurfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isMyVpnSelected) EmeraldAccent else MidnightBorder
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectMode(VpnAppMode.MY_VPN) }
                        .testTag("mode_selector_my_vpn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = if (isMyVpnSelected) EmeraldAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isFa) VpnAppMode.MY_VPN.titleFa else VpnAppMode.MY_VPN.titleEn,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isMyVpnSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isMyVpnSelected) EmeraldAccent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (ownership != ConnectionOwnership.NONE) {
                Spacer(modifier = Modifier.height(6.dp))
                val ownerColor = if (ownership == ConnectionOwnership.FREE) CyanPrimary else EmeraldAccent
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        .testTag("ownership_status_badge"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = ownerColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isFa) ownership.labelFa else ownership.labelEn,
                        style = MaterialTheme.typography.labelSmall,
                        color = ownerColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Mode-Specific Server Status Card.
 * - In [VpnAppMode.FREE_VPN]: Displays intelligent discovery, parallel testing, quality ranking,
 *   GitHub manifest sync status, and automatic selection indicator without exposing raw credentials or server lists.
 * - In [VpnAppMode.MY_VPN]: Displays the user's selected Personal profile from Room with quick navigation
 *   to Personal Profiles & Subscriptions.
 */
@Composable
private fun ActiveServerCard(
    selectedVpnMode: VpnAppMode = VpnAppMode.FREE_VPN,
    activeProfile: ServerProfile?,
    personalProfile: ServerProfile? = activeProfile,
    connectionState: VpnConnectionState,
    autoVpnStatus: AutoVpnStatusSnapshot,
    isFa: Boolean,
    usePersian: Boolean,
    onTestActiveLatency: (ServerProfile) -> Unit,
    onAutoSelectBest: () -> Unit,
    onNavigateToProfiles: () -> Unit = {}
) {
    val isConnected = connectionState == VpnConnectionState.CONNECTED

    if (selectedVpnMode == VpnAppMode.MY_VPN) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .testTag("selected_server_card"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MidnightSurface),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isConnected) EmeraldAccent.copy(alpha = 0.4f) else MidnightBorder
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(EmeraldAccent.copy(alpha = 0.14f))
                                .border(1.dp, EmeraldAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = EmeraldAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFa) "پروفایل شخصی منتخب (MY VPN)" else "SELECTED PERSONAL PROFILE",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = personalProfile?.remarks
                                    ?: if (isFa) "پروفایل شخصی انتخاب نشده است" else "No Personal Profile Selected",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = if (personalProfile != null) {
                                    "${personalProfile.protocol.displayName} • ${personalProfile.network.displayName} • ${personalProfile.security.displayName}"
                                } else {
                                    if (isFa) "برای افزودن کانفیگ یا لینک اشتراک شخصی ضربه بزنید" else "Tap below to import personal configs or subscriptions"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = EmeraldAccent.copy(alpha = 0.14f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.45f)),
                        modifier = Modifier.testTag("auto_connection_quality_badge")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (isFa) "شخصی" else "Personal",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldAccent,
                                fontWeight = FontWeight.Bold
                            )
                            personalProfile?.lastLatencyMs?.let { ping ->
                                Text(
                                    text = "${LocalizationHelper.formatDigits(ping.toString(), usePersian)} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onNavigateToProfiles,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MidnightBorder),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("open_personal_profiles_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = EmeraldAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isFa) "پروفایل‌های من" else "My Profiles",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (personalProfile != null) {
                                onTestActiveLatency(personalProfile)
                            } else {
                                onAutoSelectBest()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MidnightBorder),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_active_latency_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.test_latency_btn),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
        return
    }

    val candidateMetrics = autoVpnStatus.activeCandidate?.metrics
    val qualityGrade = candidateMetrics?.qualityGrade ?: when {
        isConnected -> AutoQualityGrade.GOOD
        else -> AutoQualityGrade.UNVERIFIED
    }
    val gradeColor = when (qualityGrade) {
        AutoQualityGrade.EXCELLENT -> EmeraldAccent
        AutoQualityGrade.GOOD -> CyanPrimary
        AutoQualityGrade.FAIR -> AmberWarning
        AutoQualityGrade.DEGRADED -> CoralError
        AutoQualityGrade.UNVERIFIED -> CyanPrimary
    }

    val statusHeadline = when {
        isConnected -> if (isFa) "انتخاب خودکار: سرور بهینه متصل است" else "Auto-Selected Optimal Server Active"
        autoVpnStatus.stage != AutoVpnStage.IDLE ->
            if (isFa) autoVpnStatus.stage.titleFa else autoVpnStatus.stage.titleEn
        else -> if (isFa) "انتخاب هوشمند خودکار سرور" else "Automatic Intelligent Server Selection"
    }

    val statusSubtitle = if (isFa) autoVpnStatus.detailMessageFa else autoVpnStatus.detailMessageEn
    val syncStatusLine = if (isFa) autoVpnStatus.lastSyncStatusFa else autoVpnStatus.lastSyncStatusEn

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .testTag("selected_server_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightSurface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isConnected) EmeraldAccent.copy(alpha = 0.4f) else MidnightBorder
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Auto-Optimization Bolt / Shield Badge
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(gradeColor.copy(alpha = 0.14f))
                            .border(1.dp, gradeColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.Security else Icons.Default.Bolt,
                            contentDescription = null,
                            tint = gradeColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isFa) "وضعیت انتخاب هوشمند سرور" else "AUTO SERVER SELECTION",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanPrimary,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = statusHeadline,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = statusSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = syncStatusLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanPrimary.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("free_vpn_sync_status")
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Current Connection Quality Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = gradeColor.copy(alpha = 0.14f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, gradeColor.copy(alpha = 0.45f)),
                    modifier = Modifier.testTag("auto_connection_quality_badge")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isFa) qualityGrade.badgeFa else qualityGrade.badgeEn,
                            style = MaterialTheme.typography.labelSmall,
                            color = gradeColor,
                            fontWeight = FontWeight.Bold
                        )
                        if (candidateMetrics != null && candidateMetrics.compositeRankScore > 0.0) {
                            val scoreInt = candidateMetrics.compositeRankScore.toInt()
                            val scoreText = LocalizationHelper.formatDigits("$scoreInt/100", usePersian)
                            Text(
                                text = scoreText,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Auto-Optimization Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (activeProfile != null) {
                            onTestActiveLatency(activeProfile)
                        } else {
                            onAutoSelectBest()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MidnightBorder),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("test_active_latency_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.test_latency_btn),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                OutlinedButton(
                    onClick = onAutoSelectBest,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MidnightBorder),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("auto_best_server_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.auto_select_best),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun RealNetworkPerformanceCard(
    connectionState: VpnConnectionState,
    activeProfile: ServerProfile?,
    statistics: EngineStatistics,
    qualityReport: ConnectionQualityReport,
    isFa: Boolean,
    usePersian: Boolean,
    onTestPing: () -> Unit
) {
    val isConnected = connectionState == VpnConnectionState.CONNECTED

    // 1. Real Ping value & clear source label (Tunnel HTTP 204 vs Server TCP Handshake)
    val effectiveLatencyMs = qualityReport.latencyMs ?: activeProfile?.lastLatencyMs
    val effectiveLatencyType = if (qualityReport.latencyMs != null) {
        qualityReport.latencyType
    } else {
        activeProfile?.latencyType ?: LatencyMeasureType.UNTESTED
    }

    val pingValueStr = if (effectiveLatencyMs != null) {
        "${LocalizationHelper.formatDigits(effectiveLatencyMs.toString(), usePersian)} ms"
    } else {
        "--"
    }

    val pingSubLabel = when {
        effectiveLatencyMs != null && effectiveLatencyType == LatencyMeasureType.PROXY_HTTP_DELAY ->
            if (isFa) "تونل VPN (HTTP 204)" else "VPN Tunnel (204)"
        effectiveLatencyMs != null && effectiveLatencyType == LatencyMeasureType.TCP_HANDSHAKE ->
            if (isFa) "پینگ سرور (TCP)" else "Endpoint (TCP)"
        effectiveLatencyType == LatencyMeasureType.TIMEOUT ->
            if (isFa) "تایم‌اوت / عدم پاسخ" else "Timeout"
        else ->
            if (isFa) "تست نشده" else "Untested"
    }

    val pingAccent = when {
        effectiveLatencyMs != null && effectiveLatencyMs < 180 -> EmeraldAccent
        effectiveLatencyMs != null -> AmberWarning
        effectiveLatencyType == LatencyMeasureType.TIMEOUT -> CoralError
        else -> CyanPrimary
    }

    // 2. Real Download Speed (Live VPN telemetry when active, or measured quality test throughput, or "--")
    val downValueStr = when {
        isConnected && statistics.isTelemetryAuthentic && statistics.downloadSpeedBps > 0L ->
            LocalizationHelper.formatSpeed(statistics.downloadSpeedBps, usePersian)
        qualityReport.downloadBps != null && qualityReport.downloadBps > 0L ->
            LocalizationHelper.formatSpeed(qualityReport.downloadBps, usePersian)
        isConnected && statistics.isTelemetryAuthentic ->
            LocalizationHelper.formatSpeed(0L, usePersian)
        else -> "--"
    }
    val downSubLabel = when {
        isConnected && statistics.isTelemetryAuthentic ->
            "${if (isFa) "کل:" else "Session:"} ${LocalizationHelper.formatBytes(statistics.sessionDownloadBytes, usePersian)}"
        qualityReport.downloadBps != null ->
            if (isFa) "تست کیفیت شبکه" else "Measured Test"
        else -> if (isFa) "نیازمند اتصال یا تست" else "Connect or Test"
    }

    // 3. Real Upload Speed (Live VPN telemetry when active, or measured quality test throughput, or "--")
    val upValueStr = when {
        isConnected && statistics.isTelemetryAuthentic && statistics.uploadSpeedBps > 0L ->
            LocalizationHelper.formatSpeed(statistics.uploadSpeedBps, usePersian)
        qualityReport.uploadBps != null && qualityReport.uploadBps > 0L ->
            LocalizationHelper.formatSpeed(qualityReport.uploadBps, usePersian)
        isConnected && statistics.isTelemetryAuthentic ->
            LocalizationHelper.formatSpeed(0L, usePersian)
        else -> "--"
    }
    val upSubLabel = when {
        isConnected && statistics.isTelemetryAuthentic ->
            "${if (isFa) "کل:" else "Session:"} ${LocalizationHelper.formatBytes(statistics.sessionUploadBytes, usePersian)}"
        qualityReport.uploadBps != null ->
            if (isFa) "تست کیفیت شبکه" else "Measured Test"
        else -> if (isFa) "نیازمند اتصال یا تست" else "Connect or Test"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("network_performance_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MidnightBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Metric 1: Ping (ms)
            CompactMetricColumn(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = activeProfile != null) { onTestPing() },
                title = if (isFa) "پینگ" else "Ping",
                value = pingValueStr,
                subtitle = pingSubLabel,
                icon = Icons.Default.Speed,
                accent = pingAccent
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(44.dp)
                    .background(MidnightBorder)
            )

            // Metric 2: Download Speed
            CompactMetricColumn(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.download_speed),
                value = downValueStr,
                subtitle = downSubLabel,
                icon = Icons.Default.Download,
                accent = EmeraldAccent
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(44.dp)
                    .background(MidnightBorder)
            )

            // Metric 3: Upload Speed
            CompactMetricColumn(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.upload_speed),
                value = upValueStr,
                subtitle = upSubLabel,
                icon = Icons.Default.Upload,
                accent = CyanPrimary
            )
        }
    }
}

@Composable
private fun CompactMetricColumn(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accent,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ConnectionQualityTestSection(
    qualityReport: ConnectionQualityReport,
    isFa: Boolean,
    usePersian: Boolean,
    onRunTest: () -> Unit,
    onCancelTest: () -> Unit
) {
    val isTesting = qualityReport.stage in setOf(
        QualityTestStage.TESTING_TUNNEL,
        QualityTestStage.TESTING_LATENCY,
        QualityTestStage.TESTING_INTERNET,
        QualityTestStage.TESTING_DOWNLOAD,
        QualityTestStage.TESTING_UPLOAD
    )

    val stageDescription = when (qualityReport.stage) {
        QualityTestStage.TESTING_TUNNEL ->
            if (isFa) "در حال بررسی تونل VPN..." else "Checking VPN tunnel..."
        QualityTestStage.TESTING_LATENCY ->
            if (isFa) "در حال اندازه‌گیری پینگ واقعی..." else "Measuring real latency..."
        QualityTestStage.TESTING_INTERNET ->
            if (isFa) "در حال بررسی دسترسی به اینترنت آزاد..." else "Testing internet reachability..."
        QualityTestStage.TESTING_DOWNLOAD ->
            if (isFa) "در حال سنجش سرعت دانلود..." else "Measuring download throughput..."
        QualityTestStage.TESTING_UPLOAD ->
            if (isFa) "در حال سنجش سرعت آپلود..." else "Measuring upload throughput..."
        else -> if (isFa) "تست کیفیت اتصال" else "Test Connection Quality"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { if (isTesting) onCancelTest() else onRunTest() },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isTesting) MidnightSurfaceVariant else CyanPrimary.copy(alpha = 0.16f),
                contentColor = CyanPrimary
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.55f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("connection_quality_test_button")
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = CyanPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stageDescription,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel_testing),
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isFa) "تست کیفیت اتصال" else "Connection Quality Test",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        AnimatedVisibility(
            visible = qualityReport.stage == QualityTestStage.COMPLETED ||
                qualityReport.stage == QualityTestStage.FAILED,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val isSuccess = qualityReport.stage == QualityTestStage.COMPLETED && qualityReport.internetReachable
            val borderColor = if (isSuccess) EmeraldAccent.copy(alpha = 0.45f) else AmberWarning.copy(alpha = 0.45f)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag("quality_test_result_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MidnightSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isSuccess) EmeraldAccent else AmberWarning,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isFa) qualityReport.summaryMessageFa else qualityReport.summaryMessageEn,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        TextButton(
                            onClick = onRunTest,
                            modifier = Modifier.testTag("retry_quality_test_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isFa) "تست مجدد" else "Retry",
                                style = MaterialTheme.typography.labelMedium,
                                color = CyanPrimary
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MidnightBorder
                    )

                    // Detailed 4-item separated diagnostic breakdown
                    QualityDetailRow(
                        label = if (isFa) "وضعیت تونل VPN" else "VPN Tunnel Status",
                        value = when {
                            qualityReport.testedThroughVpn && qualityReport.tunnelConnected ->
                                if (isFa) "فعال (SOCKS5 تایید شد)" else "Active (SOCKS5 Verified)"
                            qualityReport.testedThroughVpn ->
                                if (isFa) "عدم پاسخ تونل" else "Tunnel Unresponsive"
                            else ->
                                if (isFa) "غیرفعال (تست بدون VPN)" else "Inactive (Tested Direct)"
                        },
                        valueColor = if (qualityReport.tunnelConnected) EmeraldAccent else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    QualityDetailRow(
                        label = if (isFa) "دسترسی به اینترنت آزاد" else "Internet Reachability",
                        value = if (qualityReport.internetReachable) {
                            if (isFa) "در دسترس (HTTP 204 OK)" else "Reachable (HTTP 204 OK)"
                        } else {
                            if (isFa) "عدم دسترسی / تایم‌اوت" else "Unreachable / Timeout"
                        },
                        valueColor = if (qualityReport.internetReachable) EmeraldAccent else CoralError
                    )

                    QualityDetailRow(
                        label = if (isFa) "پینگ و نوع سنجش" else "Measured Latency",
                        value = if (qualityReport.latencyMs != null) {
                            val typeText = if (qualityReport.latencyType == LatencyMeasureType.PROXY_HTTP_DELAY) {
                                if (isFa) "تونل VPN" else "VPN Tunnel"
                            } else {
                                if (isFa) "TCP سرور" else "TCP Endpoint"
                            }
                            "${LocalizationHelper.formatDigits(qualityReport.latencyMs.toString(), usePersian)} ms ($typeText)"
                        } else {
                            "--"
                        },
                        valueColor = CyanPrimary
                    )

                    QualityDetailRow(
                        label = if (isFa) "نرخ دانلود / آپلود آزمایشی" else "Test Throughput (Down / Up)",
                        value = buildString {
                            append(qualityReport.downloadBps?.let { LocalizationHelper.formatSpeed(it, usePersian) } ?: "--")
                            append("  |  ")
                            append(qualityReport.uploadBps?.let { LocalizationHelper.formatSpeed(it, usePersian) } ?: "--")
                        },
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityDetailRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

@Composable
private fun ActiveConnectionInfoCard(
    activeProfile: ServerProfile?,
    statistics: EngineStatistics,
    settings: AppSettings,
    nowMs: Long,
    isFa: Boolean,
    usePersian: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_connection_info_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MidnightSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isFa) "اطلاعات نشست فعال" else "Active Session Details",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldAccent
                    )
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = LocalizationHelper.formatDuration(statistics.connectedSinceMs, nowMs, usePersian),
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            QualityDetailRow(
                label = if (isFa) "سرور فعال" else "Active Server",
                value = activeProfile?.remarks ?: "--",
                valueColor = MaterialTheme.colorScheme.onSurface
            )

            if (activeProfile != null) {
                QualityDetailRow(
                    label = if (isFa) "پروتکل و موتور" else "Protocol & Engine",
                    value = "${activeProfile.protocol.displayName} • ${activeProfile.security.displayName} (${
                        if (settings.activeEngine == EngineType.XRAY_CORE) "Xray-core" else "sing-box"
                    })",
                    valueColor = CyanPrimary
                )
            }

            if (statistics.isTelemetryAuthentic) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isFa) "ترافیک دریافتی / ارسالی" else "Received / Sent Traffic",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = "↓ ${LocalizationHelper.formatBytes(statistics.sessionDownloadBytes, usePersian)}   ↑ ${
                                LocalizationHelper.formatBytes(statistics.sessionUploadBytes, usePersian)
                            }",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactRoutingBar(
    selectedMode: RoutingMode,
    isFa: Boolean,
    onSwitchRoutingMode: (RoutingMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MidnightSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MidnightBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isFa) "حالت مسیریابی" else "Routing",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoutingModeChip(
                    label = stringResource(R.string.routing_mode_global),
                    selected = selectedMode == RoutingMode.GLOBAL,
                    onClick = { onSwitchRoutingMode(RoutingMode.GLOBAL) },
                    testTag = "routing_mode_global"
                )
                RoutingModeChip(
                    label = stringResource(R.string.routing_mode_rule),
                    selected = selectedMode == RoutingMode.RULE_BASED,
                    onClick = { onSwitchRoutingMode(RoutingMode.RULE_BASED) },
                    testTag = "routing_mode_rule"
                )
                RoutingModeChip(
                    label = stringResource(R.string.routing_mode_direct),
                    selected = selectedMode == RoutingMode.DIRECT,
                    onClick = { onSwitchRoutingMode(RoutingMode.DIRECT) },
                    testTag = "routing_mode_direct"
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
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = CyanPrimary.copy(alpha = 0.18f),
            selectedLabelColor = CyanPrimary
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MidnightBorder,
            selectedBorderColor = CyanPrimary.copy(alpha = 0.55f)
        ),
        modifier = Modifier.testTag(testTag)
    )
}

/**
 * Extracts country flag & localized country name strictly from authentic profile fields
 * (remarks, groupName, or host). Never invents fake country data if the profile does not contain one.
 */
private fun resolveAuthenticServerLocation(
    profile: ServerProfile?,
    isFa: Boolean
): Pair<String, String> {
    if (profile == null) {
        return "🌐" to if (isFa) "بدون سرور" else "No Server"
    }
    val combined = "${profile.remarks} ${profile.groupName} ${profile.address}".lowercase()

    // If the user's remarks already starts with a regional flag emoji, preserve it directly
    val existingFlags = listOf("🇩🇪", "🇳🇱", "🇫🇮", "🇺🇸", "🇬🇧", "🇫🇷", "🇹🇷", "🇦🇪", "🇸🇬", "🇯🇵", "🇸🇪", "🇨🇭", "🇨🇦", "🇦🇹", "🇮🇹", "🇪🇸")
    for (flag in existingFlags) {
        if (profile.remarks.contains(flag)) {
            return flag to if (isFa) "سرور انتخاب‌شده" else "Selected Server"
        }
    }

    return when {
        combined.contains("germany") || combined.contains("frankfurt") || combined.contains("آلمان") || combined.contains("المان") ->
            "🇩🇪" to if (isFa) "آلمان (Germany)" else "Germany"
        combined.contains("netherlands") || combined.contains("amsterdam") || combined.contains("هلند") ->
            "🇳🇱" to if (isFa) "هلند (Netherlands)" else "Netherlands"
        combined.contains("finland") || combined.contains("helsinki") || combined.contains("فنلاند") ->
            "🇫🇮" to if (isFa) "فنلاند (Finland)" else "Finland"
        combined.contains("united states") || combined.contains("usa") || combined.contains("آمریکا") ->
            "🇺🇸" to if (isFa) "آمریکا (USA)" else "United States"
        combined.contains("united kingdom") || combined.contains("london") || combined.contains("انگلستان") || combined.contains("انگلیس") ->
            "🇬🇧" to if (isFa) "انگلستان (UK)" else "United Kingdom"
        combined.contains("france") || combined.contains("paris") || combined.contains("فرانسه") ->
            "🇫🇷" to if (isFa) "فرانسه (France)" else "France"
        combined.contains("turkey") || combined.contains("istanbul") || combined.contains("ترکیه") ->
            "🇹🇷" to if (isFa) "ترکیه (Turkey)" else "Turkey"
        combined.contains("uae") || combined.contains("dubai") || combined.contains("امارات") || combined.contains("دبی") ->
            "🇦🇪" to if (isFa) "امارات (UAE)" else "UAE"
        combined.contains("singapore") || combined.contains("سنگاپور") ->
            "🇸🇬" to if (isFa) "سنگاپور (Singapore)" else "Singapore"
        combined.contains("japan") || combined.contains("tokyo") || combined.contains("ژاپن") ->
            "🇯🇵" to if (isFa) "ژاپن (Japan)" else "Japan"
        combined.contains("sweden") || combined.contains("stockholm") || combined.contains("سوئد") ->
            "🇸🇪" to if (isFa) "سوئد (Sweden)" else "Sweden"
        combined.contains("switzerland") || combined.contains("zurich") || combined.contains("سوئیس") ->
            "🇨🇭" to if (isFa) "سوئیس (Switzerland)" else "Switzerland"
        combined.contains("canada") || combined.contains("toronto") || combined.contains("کانادا") ->
            "🇨🇦" to if (isFa) "کانادا (Canada)" else "Canada"
        else ->
            "🌐" to if (isFa) "سرور اختصاصی" else "Custom Server"
    }
}
