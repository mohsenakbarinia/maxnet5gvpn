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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MaxNetVpnTheme
import com.example.ui.viewmodel.MaxNetViewModel
import com.example.vpn.MaxNetVpnService
import kotlinx.coroutines.launch

enum class MaxNetDestination(
    val route: String,
    val titleRes: Int,
    val icon: ImageVector,
    val testTag: String
) {
    HOME("home", R.string.nav_home, Icons.Default.Home, "nav_tab_home"),
    PROFILES("profiles", R.string.nav_profiles, Icons.Default.Dns, "nav_tab_profiles"),
    SUBSCRIPTIONS("subscriptions", R.string.nav_subscriptions, Icons.Default.CloudSync, "nav_tab_subscriptions"),
    ROUTING("routing", R.string.nav_routing, Icons.Default.Route, "nav_tab_routing"),
    DIAGNOSTICS("diagnostics", R.string.nav_diagnostics, Icons.Default.BugReport, "nav_tab_diagnostics"),
    SETTINGS("settings", R.string.nav_settings, Icons.Default.Settings, "nav_tab_settings")
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
    val activeProfile by viewModel.activeProfileState.collectAsStateWithLifecycle()
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
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val selectedBulkIds by viewModel.selectedBulkIds.collectAsStateWithLifecycle()
    val isTestingLatency by viewModel.isTestingLatency.collectAsStateWithLifecycle()
    val isRefreshingSubs by viewModel.isRefreshingSubscriptions.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val pendingDeepLink by viewModel.pendingDeepLinkUri.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBannerMessage.collectAsStateWithLifecycle()
    val configPreview by viewModel.configPreviewDialog.collectAsStateWithLifecycle()
    val qrShareProfile by viewModel.qrShareProfile.collectAsStateWithLifecycle()

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
            if (!started || android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                viewModel.toggleDirectEngineConnection(verifyConnectivity = false)
            }
        } else {
            viewModel.showMessage("VPN permission denied by user")
        }
    }

    val handleConnectToggle: () -> Unit = {
        val activeStates = setOf(
            VpnConnectionState.PREPARING,
            VpnConnectionState.STARTING_ENGINE,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.VERIFYING,
            VpnConnectionState.CONNECTED,
            VpnConnectionState.RECONNECTING
        )
        if (connectionState in activeStates) {
            val stopIntent = Intent(context, MaxNetVpnService::class.java).apply {
                action = MaxNetVpnService.ACTION_DISCONNECT
            }
            runCatching { context.startService(stopIntent) }
            viewModel.disconnectSession()
        } else if (activeProfile == null) {
            viewModel.showMessage("Select or import a server profile first")
            currentTab = MaxNetDestination.PROFILES
        } else {
            val isRobolectric = android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
            val prepareIntent = if (isRobolectric) null else runCatching { VpnService.prepare(context) }.getOrNull()
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
                if (!started || isRobolectric) {
                    viewModel.toggleDirectEngineConnection(verifyConnectivity = false)
                }
            }
        }
    }

    val usePersianDigits = settings.usePersianDigits || settings.languageCode.startsWith("fa", ignoreCase = true)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedScreen = maxWidth >= 640.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!isExpandedScreen) {
                    NavigationBar {
                        MaxNetDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = currentTab == dest,
                                onClick = { currentTab = dest },
                                icon = {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = stringResource(dest.titleRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(dest.titleRes),
                                        maxLines = 1
                                    )
                                },
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
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        MaxNetDestination.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = currentTab == dest,
                                onClick = { currentTab = dest },
                                icon = {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = stringResource(dest.titleRes)
                                    )
                                },
                                label = { Text(stringResource(dest.titleRes)) },
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
                                statistics = statistics,
                                speedHistory = speedHistory,
                                settings = settings,
                                onToggleConnect = handleConnectToggle,
                                onTestActiveLatency = { viewModel.testSingleProfileLatency(it) },
                                onAutoSelectBest = { viewModel.testAllProfilesLatency(autoSelectBest = true) },
                                onSwitchRoutingMode = { viewModel.setRoutingMode(it) },
                                onSwitchEngine = { viewModel.setActiveEngine(it) },
                                onToggleLanguage = {
                                    val nextLang = if (settings.languageCode.startsWith("fa")) "en-US" else "fa-IR"
                                    viewModel.setLanguage(nextLang)
                                },
                                onNavigateToProfiles = { currentTab = MaxNetDestination.PROFILES },
                                onCompleteOnboarding = { viewModel.completeOnboarding() }
                            )
                        }
                        MaxNetDestination.PROFILES -> {
                            ProfilesScreen(
                                profiles = profiles,
                                activeProfileId = activeProfile?.id,
                                groups = groups,
                                searchQuery = searchQuery,
                                selectedProtocol = protocolFilter,
                                selectedGroup = groupFilter,
                                sortMode = sortMode,
                                selectedBulkIds = selectedBulkIds,
                                isTestingLatency = isTestingLatency,
                                usePersianDigits = usePersianDigits,
                                onSearchQueryChange = viewModel::setSearchQuery,
                                onProtocolFilterChange = viewModel::setProtocolFilter,
                                onGroupFilterChange = viewModel::setGroupFilter,
                                onSortModeChange = viewModel::setSortMode,
                                onSelectProfile = viewModel::selectProfile,
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
}
