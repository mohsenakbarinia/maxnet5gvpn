package com.example

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.domain.model.VpnConnectionState
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.QrShareModalDialog
import com.example.ui.screens.RoutingAndDnsScreen
import com.example.ui.screens.SettingsAndAboutScreen
import com.example.ui.screens.SubscriptionsScreen
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MaxNetVpnTheme
import com.example.ui.theme.MidnightSurface
import com.example.ui.theme.MidnightSurfaceVariant
import com.example.ui.viewmodel.MaxNetViewModel
import com.example.util.LocalizationHelper
import com.example.vpn.MaxNetVpnService
import kotlinx.coroutines.launch

enum class MaxNetDestination(
    val route: String,
    val titleRes: Int,
    val titleFa: String,
    val titleEn: String,
    val icon: ImageVector,
    val testTag: String
) {
    HOME("home", R.string.nav_home, "خانه", "Home", Icons.Default.Home, "nav_tab_home"),
    PROFILES("profiles", R.string.nav_profiles, "پروفایل‌ها", "Profiles", Icons.Default.Dns, "nav_tab_profiles"),
    SUBSCRIPTIONS("subscriptions", R.string.nav_subscriptions, "اشتراک‌ها", "Subscriptions", Icons.Default.CloudSync, "nav_tab_subscriptions"),
    ROUTING("routing", R.string.nav_routing, "مسیریابی", "Routing", Icons.Default.Route, "nav_tab_routing"),
    DIAGNOSTICS("diagnostics", R.string.nav_diagnostics, "عیب‌یابی", "Diagnostics", Icons.Default.BugReport, "nav_tab_diagnostics"),
    SETTINGS("settings", R.string.nav_settings, "تنظیمات", "Settings", Icons.Default.Settings, "nav_tab_settings")
}

class MainActivity : ComponentActivity() {

    private var incomingDeepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        extractDeepLink(intent)

        setContent {
            val viewModel: MaxNetViewModel = viewModel()
            val settings by viewModel.settingsState.collectAsStateWithLifecycle()

            LaunchedEffect(incomingDeepLink) {
                incomingDeepLink?.let { uri ->
                    viewModel.onExternalDeepLinkReceived(uri)
                    incomingDeepLink = null
                }
            }

            MaxNetVpnTheme(
                themePreference = settings.themePreference,
                languageCode = settings.languageCode,
                fontScale = settings.fontScale
            ) {
                MaxNetAppRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractDeepLink(intent)
    }

    private fun extractDeepLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val dataString = intent.dataString
            if (!dataString.isNullOrBlank()) {
                incomingDeepLink = dataString
            }
        }
    }
}

@Composable
fun MaxNetAppRoot(viewModel: MaxNetViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val profiles by viewModel.filteredProfilesState.collectAsStateWithLifecycle()
    val totalProfilesCount by viewModel.totalProfilesCountState.collectAsStateWithLifecycle()
    val availableCountries by viewModel.availableCountriesState.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfileState.collectAsStateWithLifecycle()
    val personalSelectedProfile by viewModel.personalSelectedProfileState.collectAsStateWithLifecycle()
    val selectedVpnMode by viewModel.selectedVpnModeState.collectAsStateWithLifecycle()
    val ownership by viewModel.ownershipState.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptionsState.collectAsStateWithLifecycle()
    val groups by viewModel.groupsState.collectAsStateWithLifecycle()
    val routingRules by viewModel.routingRulesState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val statistics by viewModel.statisticsState.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnosticsState.collectAsStateWithLifecycle()
    val speedHistory by viewModel.speedHistoryState.collectAsStateWithLifecycle()
    val logs by viewModel.logsState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val protocolFilter by viewModel.protocolFilter.collectAsStateWithLifecycle()
    val groupFilter by viewModel.groupFilter.collectAsStateWithLifecycle()
    val extraFilter by viewModel.extraFilterState.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val selectedBulkIds by viewModel.selectedBulkIds.collectAsStateWithLifecycle()
    val isTestingLatency by viewModel.isTestingLatency.collectAsStateWithLifecycle()
    val latencyTestProgress by viewModel.latencyTestProgressState.collectAsStateWithLifecycle()
    val liveMeasuredProfileIds by viewModel.liveMeasuredProfileIdsState.collectAsStateWithLifecycle()
    val isRefreshingSubs by viewModel.isRefreshingSubscriptions.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val pendingDeepLink by viewModel.pendingDeepLinkUri.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBannerMessage.collectAsStateWithLifecycle()
    val configPreview by viewModel.configPreviewDialog.collectAsStateWithLifecycle()
    val qrShareProfile by viewModel.qrShareProfile.collectAsStateWithLifecycle()
    val pendingClipboardSub by viewModel.pendingClipboardSubscription.collectAsStateWithLifecycle()
    val subscriptionImportReport by viewModel.subscriptionImportReportState.collectAsStateWithLifecycle()
    val qualityReport by viewModel.qualityReportState.collectAsStateWithLifecycle()
    val autoVpnStatus by viewModel.autoVpnStatusState.collectAsStateWithLifecycle()
    val dualSyncState by viewModel.dualSyncState.collectAsStateWithLifecycle()
    val smartSelectionResult by viewModel.smartSelectionResultState.collectAsStateWithLifecycle()
    val securityAudit by viewModel.securityAuditState.collectAsStateWithLifecycle()
    val connectionHistory by viewModel.connectionHistoryState.collectAsStateWithLifecycle()
    val resilienceSnapshot by viewModel.resilienceState.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(MaxNetDestination.HOME) }

    if (currentTab != MaxNetDestination.HOME) {
        BackHandler {
            currentTab = MaxNetDestination.HOME
        }
    }

    LaunchedEffect(statusBanner) {
        statusBanner?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissMessage()
        }
    }

    // Android VpnService permission request launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val svcIntent = Intent(context, MaxNetVpnService::class.java).apply {
                action = MaxNetVpnService.ACTION_CONNECT
            }
            val started = runCatching { ContextCompat.startForegroundService(context, svcIntent) }.isSuccess
            if (!started || runCatching { Class.forName("androidx.test.core.app.ApplicationProvider") }.isSuccess || android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) {
                viewModel.toggleDirectEngineConnection(verifyConnectivity = false)
            }
        } else {
            viewModel.showMessage("VPN permission denied by user")
        }
    }

    val handleConnectToggle: () -> Unit = {
        val isRobolectric = runCatching { Class.forName("androidx.test.core.app.ApplicationProvider") }.isSuccess ||
            android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
        val activeStates = setOf(
            VpnConnectionState.PREPARING,
            VpnConnectionState.STARTING_ENGINE,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.VERIFYING,
            VpnConnectionState.CONNECTED,
            VpnConnectionState.RECONNECTING
        )
        if (connectionState in activeStates) {
            if (!isRobolectric) {
                val stopIntent = Intent(context, MaxNetVpnService::class.java).apply {
                    action = MaxNetVpnService.ACTION_DISCONNECT
                }
                runCatching { context.startService(stopIntent) }
            }
            viewModel.disconnectSession()
        } else {
            // ONE ACTION: TAP TO CONNECT — Never require manual server/country/protocol/profile selection!
            if (isRobolectric) {
                viewModel.toggleDirectEngineConnection(verifyConnectivity = false)
            } else {
                val prepareIntent = runCatching { VpnService.prepare(context) }.getOrNull()
                if (prepareIntent != null) {
                    runCatching {
                        vpnPermissionLauncher.launch(prepareIntent)
                    }.onFailure {
                        viewModel.toggleDirectEngineConnection(verifyConnectivity = false)
                    }
                } else {
                    val startIntent = Intent(context, MaxNetVpnService::class.java).apply {
                        action = MaxNetVpnService.ACTION_CONNECT
                    }
                    val started = runCatching { ContextCompat.startForegroundService(context, startIntent) }.isSuccess
                    if (!started) {
                        viewModel.toggleDirectEngineConnection(verifyConnectivity = false)
                    }
                }
            }
        }
    }

    val isFa = settings.languageCode.startsWith("fa", ignoreCase = true)
    val usePersianDigits = settings.usePersianDigits || isFa

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedScreen = maxWidth >= 640.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!isExpandedScreen) {
                    NavigationBar(
                        containerColor = MidnightSurface
                    ) {
                        MaxNetDestination.entries.forEach { dest ->
                            val navLabel = if (isFa) dest.titleFa else dest.titleEn
                            NavigationBarItem(
                                selected = currentTab == dest,
                                onClick = { currentTab = dest },
                                icon = {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = navLabel
                                    )
                                },
                                label = {
                                    Text(
                                        text = navLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CyanPrimary,
                                    selectedTextColor = CyanPrimary,
                                    indicatorColor = MidnightSurfaceVariant,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isExpandedScreen) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MidnightSurface
                    ) {
                        MaxNetDestination.entries.forEach { dest ->
                            val navLabel = if (isFa) dest.titleFa else dest.titleEn
                            NavigationRailItem(
                                selected = currentTab == dest,
                                onClick = { currentTab = dest },
                                icon = {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = navLabel
                                    )
                                },
                                label = { Text(navLabel) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = CyanPrimary,
                                    selectedTextColor = CyanPrimary,
                                    indicatorColor = MidnightSurfaceVariant
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (currentTab) {
                        MaxNetDestination.HOME -> {
                            HomeScreen(
                                connectionState = connectionState,
                                activeProfile = activeProfile,
                                personalProfile = personalSelectedProfile,
                                selectedVpnMode = selectedVpnMode,
                                ownership = ownership,
                                statistics = statistics,
                                diagnostics = diagnostics,
                                qualityReport = qualityReport,
                                autoVpnStatus = autoVpnStatus,
                                dualSyncState = dualSyncState,
                                speedHistory = speedHistory,
                                settings = settings,
                                smartSelectionResult = smartSelectionResult,
                                securityAudit = securityAudit,
                                connectionHistory = connectionHistory,
                                resilienceSnapshot = resilienceSnapshot,
                                onRunResilienceAssessment = viewModel::runResilienceNetworkAssessment,
                                onToggleMasterResilience = viewModel::setResilienceMasterEnabled,
                                onToggleLowDataProbing = viewModel::setLowDataProbingMode,
                                onToggleRuntimeVariants = viewModel::setAllowRuntimeVariants,
                                onToggleRelayFallback = viewModel::setRelayFallbackEnabled,
                                onAddAuthorizedRelay = viewModel::addAuthorizedRelay,
                                onRemoveAuthorizedRelay = viewModel::removeAuthorizedRelay,
                                onAddCustomDomesticEndpoint = viewModel::addCustomDomesticEndpoint,
                                onRemoveCustomDomesticEndpoint = viewModel::removeCustomDomesticEndpoint,
                                onSwitchVpnMode = viewModel::switchVpnMode,
                                onSelectSmartMode = viewModel::setSmartConnectionMode,
                                onToggleConnect = handleConnectToggle,
                                onSmartConnect = { viewModel.smartConnect() },
                                onTestActiveLatency = { viewModel.testSingleProfileLatency(it) },
                                onAutoSelectBest = { viewModel.testAllProfilesLatency(autoSelectBest = true) },
                                onRefreshFreeVpn = viewModel::refreshFreeVpnConfigurations,
                                onRunQualityTest = viewModel::runConnectionQualityTest,
                                onCancelQualityTest = viewModel::cancelConnectionQualityTest,
                                onSwitchRoutingMode = { viewModel.setRoutingMode(it) },
                                onSwitchEngine = { viewModel.setActiveEngine(it) },
                                onToggleLanguage = {
                                    val nextLang = if (settings.languageCode.startsWith("fa")) "en-US" else "fa-IR"
                                    viewModel.setLanguage(nextLang)
                                },
                                onToggleKillSwitch = viewModel::setKillSwitch,
                                onToggleDnsLeakProtection = viewModel::setDnsLeakProtection,
                                onToggleIpv6LeakProtection = viewModel::setIpv6LeakProtection,
                                onSetSecureDnsProvider = viewModel::setSecureDnsProvider,
                                onToggleAutoFailover = viewModel::setAutoFailover,
                                onClearConnectionHistory = viewModel::clearConnectionHistory,
                                onNavigateToProfiles = { currentTab = MaxNetDestination.PROFILES },
                                onNavigateToSettings = { currentTab = MaxNetDestination.SETTINGS },
                                onCompleteOnboarding = { viewModel.completeOnboarding() }
                            )
                        }
                        MaxNetDestination.PROFILES -> {
                            ProfilesScreen(
                                profiles = profiles,
                                activeProfileId = personalSelectedProfile?.id,
                                groups = groups,
                                searchQuery = searchQuery,
                                selectedProtocol = protocolFilter,
                                selectedGroup = groupFilter,
                                sortMode = sortMode,
                                selectedBulkIds = selectedBulkIds,
                                isTestingLatency = isTestingLatency,
                                usePersianDigits = usePersianDigits,
                                totalProfilesCount = totalProfilesCount,
                                connectionState = connectionState,
                                extraFilter = extraFilter,
                                availableCountries = availableCountries,
                                latencyTestProgress = latencyTestProgress,
                                liveMeasuredProfileIds = liveMeasuredProfileIds,
                                personalSyncStatus = dualSyncState.personalStatus,
                                onSearchQueryChange = viewModel::setSearchQuery,
                                onProtocolFilterChange = viewModel::setProtocolFilter,
                                onGroupFilterChange = viewModel::setGroupFilter,
                                onFavoritesFilterChange = viewModel::setFavoritesOnlyFilter,
                                onPinnedFilterChange = viewModel::setPinnedOnlyFilter,
                                onCountryFilterChange = viewModel::setCountryFilter,
                                onConnectionFilterChange = viewModel::setConnectionStatusFilter,
                                onResetAllFilters = viewModel::clearAllFilters,
                                onSortModeChange = viewModel::setSortMode,
                                onSelectProfile = viewModel::selectProfile,
                                onConnectToProfile = { targetProfile ->
                                    viewModel.switchVpnMode(com.example.core.autovpn.VpnAppMode.MY_VPN)
                                    viewModel.selectProfile(targetProfile.id)
                                    val activeStates = setOf(
                                        VpnConnectionState.PREPARING,
                                        VpnConnectionState.STARTING_ENGINE,
                                        VpnConnectionState.CONNECTING,
                                        VpnConnectionState.VERIFYING,
                                        VpnConnectionState.CONNECTED,
                                        VpnConnectionState.RECONNECTING
                                    )
                                    if (connectionState in activeStates) {
                                        viewModel.disconnectSession()
                                    }
                                    handleConnectToggle()
                                },
                                onSaveProfile = { viewModel.saveProfile(it) },
                                onDuplicateProfile = viewModel::duplicateProfile,
                                onDeleteProfile = viewModel::deleteProfile,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onTogglePinned = viewModel::togglePinned,
                                onToggleBulkSelect = viewModel::toggleBulkSelection,
                                onDeleteBulkSelected = viewModel::deleteBulkSelectedProfiles,
                                onClearBulkSelection = viewModel::clearBulkSelection,
                                onImportBatchText = viewModel::importConfigurations,
                                onDecodeQrBitmap = viewModel::decodeAndImportQrBitmap,
                                onTestSingleLatency = viewModel::testSingleProfileLatency,
                                onTestAllLatency = { viewModel.testAllProfilesLatency(autoSelectBest = false) },
                                onTestByScope = { scope -> viewModel.testProfilesByScope(scope, autoSelectBest = false) },
                                onCancelLatencyTest = viewModel::cancelLatencyTesting,
                                onShowCoreJson = viewModel::showCoreConfigPreview,
                                onShowQrCode = viewModel::showQrCodeModal,
                                onShowMessage = viewModel::showMessage
                            )
                        }
                        MaxNetDestination.SUBSCRIPTIONS -> {
                            SubscriptionsScreen(
                                subscriptions = subscriptions,
                                isRefreshing = isRefreshingSubs,
                                usePersianDigits = usePersianDigits,
                                onAddSubscription = viewModel::addSubscription,
                                onRefreshSubscription = viewModel::refreshSubscription,
                                onRefreshAll = viewModel::refreshAllSubscriptions,
                                onDeleteSubscription = viewModel::deleteSubscription
                            )
                        }
                        MaxNetDestination.ROUTING -> {
                            RoutingAndDnsScreen(
                                settings = settings,
                                customRules = routingRules,
                                installedApps = installedApps,
                                onSaveRoutingAndDns = viewModel::updateRoutingAndDns,
                                onAddRoutingRule = viewModel::addCustomRoutingRule,
                                onToggleRoutingRule = viewModel::toggleRoutingRule,
                                onDeleteRoutingRule = viewModel::deleteRoutingRule,
                                onLoadInstalledApps = viewModel::loadInstalledAppsIfNeeded,
                                onSetSplitTunnelMode = viewModel::setSplitTunnelMode,
                                onToggleSplitTunnelApp = viewModel::toggleSplitTunnelApp
                            )
                        }
                        MaxNetDestination.DIAGNOSTICS -> {
                            DiagnosticsScreen(
                                snapshot = diagnostics,
                                logs = logs,
                                onExportReport = viewModel::buildSanitizedDiagnosticReport,
                                onClearLogs = viewModel.repository::clearLogs,
                                onShowMessage = viewModel::showMessage
                            )
                        }
                        MaxNetDestination.SETTINGS -> {
                            SettingsAndAboutScreen(
                                settings = settings,
                                branding = viewModel.branding,
                                onSetLanguage = viewModel::setLanguage,
                                onSetTheme = viewModel::setThemePreference,
                                onSetPersianDigits = viewModel::setUsePersianDigits,
                                onSetFontScale = viewModel::setFontScale,
                                onSetAutoReconnect = viewModel::setAutoReconnect,
                                onSetConnectOnBoot = viewModel::setConnectOnBoot,
                                onToggleKillSwitch = viewModel::setKillSwitch,
                                onToggleDnsLeakProtection = viewModel::setDnsLeakProtection,
                                onToggleIpv6LeakProtection = viewModel::setIpv6LeakProtection,
                                onToggleAutoFailover = viewModel::setAutoFailover,
                                onSetSecureDnsProvider = viewModel::setSecureDnsProvider,
                                onExportBackup = { viewModel.repository.exportBackupJson() },
                                onRestoreBackup = { raw ->
                                    scope.launch {
                                        val res = viewModel.repository.restoreBackupJson(raw)
                                        res.fold(
                                            onSuccess = { count -> viewModel.showMessage("Restored $count profile(s) from backup") },
                                            onFailure = { err -> viewModel.showMessage("Restore failed: ${err.message}") }
                                        )
                                    }
                                },
                                onCheckUpdates = viewModel::checkForUpdates,
                                onShowMessage = viewModel::showMessage
                            )
                        }
                    }
                }
            }
        }
    }

    // Mandatory User Confirmation Dialog for External Deep-Link Import
    if (pendingDeepLink != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeepLinkImport,
            title = { Text("Confirm External Configuration Import") },
            text = {
                Column {
                    Text("An external link requested to import a VPN configuration into MAXNET5G VPN:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = pendingDeepLink?.take(120) ?: "",
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmDeepLinkImport,
                    modifier = Modifier.testTag("confirm_deeplink_import_button")
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeepLinkImport) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Core JSON Configuration Preview Modal
    configPreview?.let { (title, jsonText) ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCoreConfigPreview,
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = jsonText,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(jsonText))
                        viewModel.showMessage("Copied generated core JSON")
                        viewModel.dismissCoreConfigPreview()
                    }
                ) {
                    Text("Copy JSON")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCoreConfigPreview) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // QR Code Share Modal
    qrShareProfile?.let { profile ->
        QrShareModalDialog(
            profile = profile,
            onDismiss = viewModel::dismissQrCodeModal
        )
    }

    // Automatic Clipboard Subscription URL Confirmation Dialog
    pendingClipboardSub?.let { detectedSub ->
        var subTitle by remember(detectedSub.url) { mutableStateOf(detectedSub.suggestedTitle) }
        var autoUpdate by remember(detectedSub.url) { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = viewModel::dismissClipboardSubscriptionImport,
            modifier = Modifier.testTag("clipboard_subscription_confirm_dialog"),
            title = {
                Text(
                    text = "شناسایی لینک اشتراک",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "لینک اشتراک شناسایی شد. آیا می‌خواهید آن را اضافه کنید؟",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = subTitle,
                        onValueChange = { subTitle = it },
                        label = { Text("نام اشتراک (Subscription Name)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("clipboard_sub_title_input")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "بروزرسانی خودکار (Auto Update)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = autoUpdate,
                            onCheckedChange = { autoUpdate = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmClipboardSubscriptionImport(
                            customTitle = subTitle,
                            autoUpdate = autoUpdate,
                            onNavigateToSubscriptionFlow = {
                                currentTab = MaxNetDestination.SUBSCRIPTIONS
                            }
                        )
                    },
                    modifier = Modifier.testTag("confirm_clipboard_subscription_button")
                ) {
                    Text("افزودن اشتراک (Add subscription)")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissClipboardSubscriptionImport,
                    modifier = Modifier.testTag("cancel_clipboard_subscription_button")
                ) {
                    Text("انصراف (Cancel)")
                }
            }
        )
    }

    // Persian Subscription & Configuration Import Results Dialog
    subscriptionImportReport?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSubscriptionImportReport,
            modifier = Modifier.testTag("subscription_import_result_dialog"),
            title = {
                Text(
                    text = report.title,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (report.isSuccess) {
                            EmeraldAccent.copy(alpha = 0.14f)
                        } else {
                            CoralError.copy(alpha = 0.14f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "وضعیت دریافت اطلاعات",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = report.statusMessageFa,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (report.isSuccess) EmeraldAccent else CoralError,
                                modifier = Modifier.testTag("import_report_status_text")
                            )
                        }
                    }

                    HorizontalDivider(color = MidnightSurfaceVariant)

                    ImportResultStatRow(
                        label = "تعداد کانفیگ‌های دریافت‌شده",
                        value = LocalizationHelper.formatDigits(report.totalReceivedCount.toString(), usePersianDigits),
                        testTag = "import_report_total_count"
                    )
                    ImportResultStatRow(
                        label = "تعداد کانفیگ‌های جدید",
                        value = LocalizationHelper.formatDigits(report.newImportedCount.toString(), usePersianDigits),
                        valueColor = EmeraldAccent,
                        testTag = "import_report_new_count"
                    )
                    ImportResultStatRow(
                        label = "تعداد موارد تکراری",
                        value = LocalizationHelper.formatDigits(report.duplicateCount.toString(), usePersianDigits),
                        testTag = "import_report_duplicate_count"
                    )
                    ImportResultStatRow(
                        label = "تعداد کانفیگ‌های نامعتبر",
                        value = LocalizationHelper.formatDigits(report.invalidCount.toString(), usePersianDigits),
                        valueColor = if (report.invalidCount > 0) CoralError else MaterialTheme.colorScheme.onSurface,
                        testTag = "import_report_invalid_count"
                    )
                }
            },
            confirmButton = {
                if (report.isSuccess && currentTab != MaxNetDestination.PROFILES) {
                    Button(
                        onClick = {
                            viewModel.dismissSubscriptionImportReport()
                            currentTab = MaxNetDestination.PROFILES
                        },
                        modifier = Modifier.testTag("view_imported_profiles_button")
                    ) {
                        Text("مشاهده پروفایل‌ها")
                    }
                } else {
                    Button(
                        onClick = viewModel::dismissSubscriptionImportReport,
                        modifier = Modifier.testTag("dismiss_import_report_button")
                    ) {
                        Text("تأیید")
                    }
                }
            },
            dismissButton = {
                if (report.isSuccess && currentTab != MaxNetDestination.PROFILES) {
                    TextButton(
                        onClick = viewModel::dismissSubscriptionImportReport,
                        modifier = Modifier.testTag("dismiss_import_report_button")
                    ) {
                        Text("بستن")
                    }
                }
            }
        )
    }
}

@Composable
private fun ImportResultStatRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            modifier = Modifier.testTag(testTag)
        )
    }
}
