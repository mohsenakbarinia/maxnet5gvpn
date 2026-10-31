package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.LatencyTester
import com.example.data.local.MaxNetDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.VpnRepository
import com.example.data.subscription.SubscriptionUpdateWorker
import com.example.domain.model.AppSettings
import com.example.domain.model.BrandingConfig
import com.example.domain.model.DiagnosticSnapshot
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.OutboundTag
import com.example.domain.model.RedactedLogEntry
import com.example.domain.model.RoutingMode
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.RoutingRuleType
import com.example.domain.model.ServerProfile
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.SubscriptionItem
import com.example.domain.model.ThemePreference
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnProtocol
import com.example.parser.ConfigParser
import com.example.security.LogRedactor
import com.example.util.QrCodeHelper
import com.example.vpn.VpnController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ProfileSortMode(val labelEn: String, val labelFa: String) {
    DEFAULT("Pinned & Order", "سنجاق و ترتیب"),
    LATENCY("Lowest Ping", "کمترین پینگ"),
    NAME("Name (A–Z)", "نام (الفبا)"),
    PROTOCOL("Protocol", "پروتکل")
}

class MaxNetViewModel(application: Application) : AndroidViewModel(application) {

    private val db = MaxNetDatabase.getInstance(application)
    private val settingsDataStore = SettingsDataStore(application)
    val repository = VpnRepository(application, db.maxNetDao(), settingsDataStore)

    val branding: BrandingConfig = repository.loadBrandingConfig()

    val settingsState: StateFlow<AppSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val rawProfilesFlow = repository.profilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptionsState: StateFlow<List<SubscriptionItem>> = repository.subscriptionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groupsState: StateFlow<List<String>> = repository.groupsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("Default"))

    val routingRulesState: StateFlow<List<RoutingRuleItem>> = repository.routingRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionState: StateFlow<VpnConnectionState> = VpnController.connectionState
    val statisticsState: StateFlow<EngineStatistics> = VpnController.statistics
    val diagnosticsState: StateFlow<DiagnosticSnapshot> = VpnController.diagnostics
    val speedHistoryState: StateFlow<List<Pair<Long, Long>>> = VpnController.speedHistory
    val logsState: StateFlow<List<RedactedLogEntry>> = repository.logsFlow

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _protocolFilter = MutableStateFlow<VpnProtocol?>(null)
    val protocolFilter: StateFlow<VpnProtocol?> = _protocolFilter.asStateFlow()

    private val _groupFilter = MutableStateFlow<String?>(null)
    val groupFilter: StateFlow<String?> = _groupFilter.asStateFlow()

    private val _sortMode = MutableStateFlow(ProfileSortMode.DEFAULT)
    val sortMode: StateFlow<ProfileSortMode> = _sortMode.asStateFlow()

    private val _selectedBulkIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedBulkIds: StateFlow<Set<Long>> = _selectedBulkIds.asStateFlow()

    private val _isTestingLatency = MutableStateFlow(false)
    val isTestingLatency: StateFlow<Boolean> = _isTestingLatency.asStateFlow()

    private val _isRefreshingSubscriptions = MutableStateFlow(false)
    val isRefreshingSubscriptions: StateFlow<Boolean> = _isRefreshingSubscriptions.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _pendingDeepLinkUri = MutableStateFlow<String?>(null)
    val pendingDeepLinkUri: StateFlow<String?> = _pendingDeepLinkUri.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    private val _configPreviewDialog = MutableStateFlow<Pair<String, String>?>(null)
    val configPreviewDialog: StateFlow<Pair<String, String>?> = _configPreviewDialog.asStateFlow()

    private val _qrShareProfile = MutableStateFlow<ServerProfile?>(null)
    val qrShareProfile: StateFlow<ServerProfile?> = _qrShareProfile.asStateFlow()

    private var latencyJob: Job? = null

    val filteredProfilesState: StateFlow<List<ServerProfile>> = combine(
        rawProfilesFlow,
        _searchQuery,
        _protocolFilter,
        _groupFilter,
        _sortMode
    ) { profiles, query, proto, group, sort ->
        val q = query.trim().lowercase()
        val filtered = profiles.filter { p ->
            val matchesQuery = q.isEmpty() ||
                p.remarks.lowercase().contains(q) ||
                p.address.lowercase().contains(q) ||
                p.protocol.displayName.lowercase().contains(q) ||
                p.groupName.lowercase().contains(q)
            val matchesProto = proto == null || p.protocol == proto
            val matchesGroup = group == null || p.groupName.equals(group, ignoreCase = true)
            matchesQuery && matchesProto && matchesGroup
        }
        when (sort) {
            ProfileSortMode.DEFAULT -> filtered
            ProfileSortMode.LATENCY -> filtered.sortedWith(
                compareBy<ServerProfile> { !it.isPinned }
                    .thenBy { it.lastLatencyMs ?: Long.MAX_VALUE }
            )
            ProfileSortMode.NAME -> filtered.sortedWith(
                compareBy<ServerProfile> { !it.isPinned }
                    .thenBy { it.remarks.lowercase() }
            )
            ProfileSortMode.PROTOCOL -> filtered.sortedWith(
                compareBy<ServerProfile> { !it.isPinned }
                    .thenBy { it.protocol.displayName }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProfileState: StateFlow<ServerProfile?> = combine(
        rawProfilesFlow,
        settingsState
    ) { profiles, settings ->
        profiles.find { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        SubscriptionUpdateWorker.schedulePeriodicUpdates(application)
    }

    fun showMessage(msg: String) {
        _statusBannerMessage.value = msg
    }

    fun dismissMessage() {
        _statusBannerMessage.value = null
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setProtocolFilter(protocol: VpnProtocol?) {
        _protocolFilter.value = protocol
    }

    fun setGroupFilter(group: String?) {
        _groupFilter.value = group
    }

    fun setSortMode(mode: ProfileSortMode) {
        _sortMode.value = mode
    }

    fun toggleBulkSelection(profileId: Long) {
        val current = _selectedBulkIds.value
        _selectedBulkIds.value = if (current.contains(profileId)) current - profileId else current + profileId
    }

    fun clearBulkSelection() {
        _selectedBulkIds.value = emptySet()
    }

    fun deleteBulkSelectedProfiles() {
        val ids = _selectedBulkIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteProfilesBulk(ids)
            _selectedBulkIds.value = emptySet()
            showMessage("Deleted ${ids.size} selected profiles")
        }
    }

    fun selectProfile(profileId: Long) {
        viewModelScope.launch {
            repository.selectProfile(profileId)
        }
    }

    fun saveProfile(profile: ServerProfile, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            val validation = VpnController.validateConfiguration(
                profile = profile,
                settings = settingsState.value,
                rules = routingRulesState.value
            )
            val savedId = repository.saveProfile(profile)
            if (settingsState.value.selectedProfileId == null) {
                repository.selectProfile(savedId)
            }
            if (!validation.isValid) {
                showMessage("Saved with engine note: ${validation.errors.firstOrNull()}")
            } else {
                showMessage("Saved profile '${profile.remarks}'")
            }
            onSaved()
        }
    }

    fun duplicateProfile(profile: ServerProfile) {
        viewModelScope.launch {
            repository.duplicateProfile(profile)
            showMessage("Duplicated '${profile.remarks}'")
        }
    }

    fun deleteProfile(profile: ServerProfile) {
        viewModelScope.launch {
            repository.deleteProfile(profile.id)
            showMessage("Deleted '${profile.remarks}'")
        }
    }

    fun toggleFavorite(profile: ServerProfile) {
        viewModelScope.launch {
            repository.toggleFavorite(profile)
        }
    }

    fun togglePinned(profile: ServerProfile) {
        viewModelScope.launch {
            repository.togglePinned(profile)
        }
    }

    fun importConfigurations(rawText: String, groupName: String = "Default") {
        viewModelScope.launch {
            val res = repository.importBatchText(rawText, groupName)
            if (res.profiles.isNotEmpty()) {
                val all = repository.getAllProfilesSnapshot()
                if (settingsState.value.selectedProfileId == null && all.isNotEmpty()) {
                    repository.selectProfile(all.first().id)
                }
                showMessage("Imported ${res.profiles.size} configuration(s) (${res.duplicatesSkipped} duplicate skipped)")
            } else {
                val reason = res.failures.firstOrNull()?.reason ?: "No valid configurations found"
                showMessage("Import failed: $reason")
            }
        }
    }

    fun decodeAndImportQrBitmap(bitmap: Bitmap) {
        val decoded = QrCodeHelper.decodeQrFromBitmap(bitmap)
        decoded.fold(
            onSuccess = { text -> importConfigurations(text) },
            onFailure = { err -> showMessage(err.message ?: "Failed to decode QR code") }
        )
    }

    fun onExternalDeepLinkReceived(uriString: String) {
        if (uriString.isNotBlank()) {
            _pendingDeepLinkUri.value = uriString
        }
    }

    fun confirmDeepLinkImport() {
        val uri = _pendingDeepLinkUri.value ?: return
        _pendingDeepLinkUri.value = null
        // Support maxnet5g://import?uri=... or direct vless://...
        val payload = if (uri.startsWith("maxnet5g://", ignoreCase = true) && uri.contains("url=")) {
            ConfigParser.percentDecode(uri.substringAfter("url=").substringBefore("&"))
        } else {
            uri
        }
        importConfigurations(payload)
    }

    fun dismissDeepLinkImport() {
        _pendingDeepLinkUri.value = null
    }

    // Core Connection Lifecycle (Direct controller trigger + Service trigger)
    fun toggleDirectEngineConnection() {
        viewModelScope.launch {
            val state = connectionState.value
            if (state == VpnConnectionState.CONNECTED || state == VpnConnectionState.CONNECTING) {
                VpnController.stopSession { level, tag, msg ->
                    repository.appendLog(level, tag, msg)
                }
            } else {
                val profile = activeProfileState.value ?: rawProfilesFlow.value.firstOrNull()
                if (profile == null) {
                    showMessage("Please import or create a server profile first.")
                    return@launch
                }
                val res = VpnController.startSession(
                    profile = profile,
                    settings = settingsState.value,
                    rules = routingRulesState.value,
                    tunActive = false,
                    onLog = { level, tag, msg ->
                        repository.appendLog(level, tag, msg, listOf(profile.credential))
                    }
                )
                res.onFailure { err ->
                    showMessage("Connection error: ${err.message}")
                }
            }
        }
    }

    // Latency Testing & Best Server Selection
    fun testSingleProfileLatency(profile: ServerProfile) {
        viewModelScope.launch {
            val localPort = statisticsState.value.localSocksPort
            val outcome = if (connectionState.value == VpnConnectionState.CONNECTED &&
                localPort != null &&
                activeProfileState.value?.id == profile.id
            ) {
                LatencyTester.testProxyHttp204Delay(profile, localPort)
            } else {
                LatencyTester.testSingleTcpLatency(profile)
            }
            repository.updateProfileLatency(profile.id, outcome.latencyMs, outcome.measureType)
            val msg = if (outcome.latencyMs != null) {
                "${profile.remarks}: ${outcome.latencyMs} ms (${outcome.measureType.label})"
            } else {
                "${profile.remarks}: ${outcome.errorReason ?: "Timeout"}"
            }
            repository.appendLog("INFO", "LatencyTest", msg, listOf(profile.credential))
        }
    }

    fun testAllProfilesLatency( autoSelectBest: Boolean = false) {
        if (_isTestingLatency.value) return
        latencyJob = viewModelScope.launch {
            val profiles = filteredProfilesState.value.ifEmpty { repository.getAllProfilesSnapshot() }
            if (profiles.isEmpty()) {
                showMessage("No profiles available to test")
                return@launch
            }
            _isTestingLatency.value = true
            try {
                val outcomes = LatencyTester.testProfilesConcurrently(profiles) { outcome ->
                    repository.updateProfileLatency(outcome.profileId, outcome.latencyMs, outcome.measureType)
                }
                val reachable = outcomes.filter { it.latencyMs != null }
                if (autoSelectBest && reachable.isNotEmpty()) {
                    val best = reachable.minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
                    if (best != null) {
                        repository.selectProfile(best.profileId)
                        val bestProfile = profiles.find { it.id == best.profileId }
                        showMessage("Selected best server: ${bestProfile?.remarks ?: ""} (${best.latencyMs} ms)")
                    }
                } else {
                    showMessage("Latency test finished: ${reachable.size}/${outcomes.size} reachable")
                }
            } finally {
                _isTestingLatency.value = false
            }
        }
    }

    fun cancelLatencyTesting() {
        latencyJob?.cancel()
        _isTestingLatency.value = false
        showMessage("Cancelled latency testing")
    }

    // Core JSON Preview & Share Dialogs
    fun showCoreConfigPreview(profile: ServerProfile) {
        val settings = settingsState.value
        val rules = routingRulesState.value
        val result = VpnController.validateConfiguration(profile, settings, rules)
        val redactedJson = LogRedactor.redact(
            result.generatedConfigJson.ifBlank {
                "Validation Failed:\n" + result.errors.joinToString("\n• ", prefix = "• ")
            },
            listOf(profile.credential, profile.realityPublicKey)
        )
        val title = "${settings.activeEngine.displayName} — ${profile.remarks}"
        _configPreviewDialog.value = title to redactedJson
    }

    fun dismissCoreConfigPreview() {
        _configPreviewDialog.value = null
    }

    fun showQrCodeModal(profile: ServerProfile) {
        _qrShareProfile.value = profile
    }

    fun dismissQrCodeModal() {
        _qrShareProfile.value = null
    }

    // Subscriptions
    fun addSubscription(title: String, url: String, autoUpdate: Boolean) {
        viewModelScope.launch {
            _isRefreshingSubscriptions.value = true
            val res = repository.addSubscription(title, url, autoUpdate)
            _isRefreshingSubscriptions.value = false
            res.fold(
                onSuccess = { sub -> showMessage("Added '${sub.title}' (${sub.profileCount} profiles)") },
                onFailure = { err -> showMessage("Subscription error: ${err.message}") }
            )
        }
    }

    fun refreshSubscription(sub: SubscriptionItem) {
        viewModelScope.launch {
            _isRefreshingSubscriptions.value = true
            val res = repository.refreshSubscription(sub)
            _isRefreshingSubscriptions.value = false
            res.fold(
                onSuccess = { updated -> showMessage("Updated '${updated.title}' (${updated.profileCount} servers)") },
                onFailure = { err -> showMessage("Update failed: ${err.message}") }
            )
        }
    }

    fun refreshAllSubscriptions() {
        viewModelScope.launch {
            val subs = subscriptionsState.value
            if (subs.isEmpty()) return@launch
            _isRefreshingSubscriptions.value = true
            var okCount = 0
            for (sub in subs) {
                if (repository.refreshSubscription(sub).isSuccess) okCount++
            }
            _isRefreshingSubscriptions.value = false
            showMessage("Updated $okCount/${subs.size} subscription(s)")
        }
    }

    fun deleteSubscription(sub: SubscriptionItem) {
        viewModelScope.launch {
            repository.deleteSubscription(sub.id)
            showMessage("Deleted subscription '${sub.title}'")
        }
    }

    // Settings, Routing, DNS & Split Tunneling
    fun setActiveEngine(engine: EngineType) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(activeEngine = engine) }
            repository.appendLog("INFO", "EngineSelector", "Switched active core engine to ${engine.displayName}")
        }
    }

    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(routingMode = mode) }
        }
    }

    fun updateRoutingAndDns(
        bypassLan: Boolean,
        bypassCountryCode: String,
        blockAds: Boolean,
        remoteDns: String,
        directDns: String,
        enableIpv6: Boolean,
        enableFakeDns: Boolean
    ) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    bypassLan = bypassLan,
                    bypassCountryCode = bypassCountryCode.trim().uppercase(),
                    blockAds = blockAds,
                    remoteDns = remoteDns.trim(),
                    directDns = directDns.trim(),
                    enableIpv6 = enableIpv6,
                    enableFakeDns = enableFakeDns
                )
            }
            showMessage("Saved Routing & DNS settings")
        }
    }

    fun addCustomRoutingRule(name: String, type: RoutingRuleType, value: String, tag: OutboundTag) {
        if (value.isBlank()) return
        viewModelScope.launch {
            repository.addRoutingRule(
                RoutingRuleItem(
                    name = name.ifBlank { "${type.displayName}: $value" },
                    type = type,
                    value = value.trim(),
                    outboundTag = tag
                )
            )
        }
    }

    fun toggleRoutingRule(rule: RoutingRuleItem) {
        viewModelScope.launch {
            repository.toggleRoutingRule(rule)
        }
    }

    fun deleteRoutingRule(id: Long) {
        viewModelScope.launch {
            repository.deleteRoutingRule(id)
        }
    }

    fun loadInstalledAppsIfNeeded() {
        if (_installedApps.value.isNotEmpty()) return
        viewModelScope.launch {
            _installedApps.value = repository.getInstalledApps()
        }
    }

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(splitTunnelMode = mode) }
        }
    }

    fun toggleSplitTunnelApp(packageName: String) {
        viewModelScope.launch {
            repository.updateSettings { cur ->
                val updated = if (cur.splitTunnelPackages.contains(packageName)) {
                    cur.splitTunnelPackages - packageName
                } else {
                    cur.splitTunnelPackages + packageName
                }
                cur.copy(splitTunnelPackages = updated)
            }
        }
    }

    // Localization, Theme, Onboarding & Diagnostics
    fun completeOnboarding() {
        viewModelScope.launch {
            repository.updateSettings { it.copy(onboardingCompleted = true) }
        }
    }

    fun setLanguage(langCode: String) {
        viewModelScope.launch {
            val usePersianNum = langCode.startsWith("fa", ignoreCase = true)
            repository.updateSettings {
                it.copy(languageCode = langCode, usePersianDigits = usePersianNum)
            }
        }
    }

    fun setThemePreference(theme: ThemePreference) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(themePreference = theme) }
        }
    }

    fun setUsePersianDigits(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(usePersianDigits = enabled) }
        }
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(fontScale = scale.coerceIn(0.85f, 1.25f)) }
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(autoReconnect = enabled) }
        }
    }

    fun setConnectOnBoot(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(connectOnBoot = enabled) }
        }
    }

    fun buildSanitizedDiagnosticReport(): String {
        val diag = diagnosticsState.value
        val settings = settingsState.value
        val logs = logsState.value
        val sb = StringBuilder()
        sb.appendLine("=== MAXNET5G VPN SANITIZED DIAGNOSTIC REPORT ===")
        sb.appendLine("Connection State: ${diag.connectionState.name}")
        sb.appendLine("Selected Engine: ${diag.activeEngine.displayName}")
        sb.appendLine("Active Profile: ${diag.activeProfileRemarks}")
        sb.appendLine("Core Initialized: ${diag.coreInitialized}")
        sb.appendLine("TUN Interface Active: ${diag.tunInterfaceActive}")
        sb.appendLine("Bridge Architecture: ${diag.bridgeArchitectureStatus}")
        sb.appendLine("Routing Mode: ${settings.routingMode.name} (Bypass Country: ${settings.bypassCountryCode})")
        sb.appendLine("Split Tunnel Mode: ${settings.splitTunnelMode.name} (${settings.splitTunnelPackages.size} apps)")
        sb.appendLine("Last Error: ${diag.lastError ?: "None"}")
        sb.appendLine("--- Redacted Event Logs (${logs.size}) ---")
        for (entry in logs) {
            sb.appendLine("[${entry.level}] [${entry.tag}] ${entry.message}")
        }
        return LogRedactor.redact(sb.toString())
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            val res = repository.checkUpdateManifest(branding.updateManifestUrl)
            res.fold(
                onSuccess = { manifest ->
                    showMessage("Latest version: ${manifest.latestVersion} (SHA-256 verified manifest)")
                },
                onFailure = {
                    showMessage("Running latest version 1.0.0 (Update endpoint offline or unconfigured)")
                }
            )
        }
    }
}
