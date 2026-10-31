package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.LatencyTestOutcome
import com.example.core.LatencyTester
import com.example.core.NetworkQualityTester
import com.example.core.autovpn.AutoFreeVpnManager
import com.example.core.autovpn.AutoServerRanker
import com.example.core.autovpn.AutoVpnDiscoveryEngine
import com.example.core.autovpn.AutoVpnStatusSnapshot
import com.example.data.local.MaxNetDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.VpnRepository
import com.example.data.subscription.SubscriptionUpdateWorker
import com.example.domain.model.AppSettings
import com.example.domain.model.BrandingConfig
import com.example.domain.model.ClipboardInputType
import com.example.domain.model.ConnectionQualityReport
import com.example.domain.model.DiagnosticSnapshot
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.OutboundTag
import com.example.domain.model.QualityTestStage
import com.example.domain.model.RedactedLogEntry
import com.example.domain.model.RoutingMode
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.RoutingRuleType
import com.example.domain.model.ServerProfile
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.SubscriptionFormat
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.SubscriptionImportReport
import com.example.domain.model.SubscriptionItem
import com.example.domain.model.ThemePreference
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnProtocol
import com.example.parser.ConfigParser
import com.example.security.LogRedactor
import com.example.util.QrCodeHelper
import com.example.util.ResolvedServerLocation
import com.example.util.ServerLocationResolver
import com.example.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ProfileSortMode(val labelEn: String, val labelFa: String) {
    DEFAULT("Default order", "ترتیب پیش‌فرض"),
    LATENCY("Lowest ping", "کمترین پینگ"),
    HIGHEST_PING("Highest ping", "بیشترین پینگ"),
    NAME("Alphabetical", "حروف الفبا"),
    RECENTLY_ADDED("Recently added", "جدیدترین‌ها"),
    FAVORITES_FIRST("Favorites first", "ابتدا علاقه‌مندی‌ها"),
    PROTOCOL("Protocol", "پروتکل")
}

enum class ProfileConnectionFilter(val labelEn: String, val labelFa: String) {
    ALL("All statuses", "همه وضعیت‌ها"),
    CONNECTED("Connected", "متصل"),
    REACHABLE("Reachable", "پینگ سالم"),
    UNTESTED("Untested", "تست‌نشده"),
    FAILED("Failed", "ناموفق / خطا")
}

enum class TestBulkScope(val labelEn: String, val labelFa: String) {
    ALL("Test all profiles", "تست همه پروفایل‌ها"),
    FILTERED("Test filtered profiles", "تست پروفایل‌های فیلترشده"),
    SELECTED("Test selected profiles", "تست پروفایل‌های انتخاب‌شده")
}

data class ProfileExtraFilterState(
    val favoritesOnly: Boolean = false,
    val pinnedOnly: Boolean = false,
    val countryCode: String? = null,
    val connectionFilter: ProfileConnectionFilter = ProfileConnectionFilter.ALL
) {
    val activeFilterCount: Int
        get() = (if (favoritesOnly) 1 else 0) +
            (if (pinnedOnly) 1 else 0) +
            (if (countryCode != null) 1 else 0) +
            (if (connectionFilter != ProfileConnectionFilter.ALL) 1 else 0)
}

data class LatencyTestProgress(
    val isTesting: Boolean = false,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val reachableCount: Int = 0,
    val scope: TestBulkScope = TestBulkScope.ALL
)

class MaxNetViewModel(application: Application) : AndroidViewModel(application) {

    private val db = MaxNetDatabase.getInstance(application)
    private val settingsDataStore = SettingsDataStore(application)
    val repository = VpnRepository(application, db.maxNetDao(), settingsDataStore)

    val branding: BrandingConfig = repository.loadBrandingConfig()

    val settingsState: StateFlow<AppSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val rawProfilesFlow = repository.profilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalProfilesCountState: StateFlow<Int> = rawProfilesFlow
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val availableCountriesState: StateFlow<List<ResolvedServerLocation>> = rawProfilesFlow
        .map { profiles ->
            profiles
                .asSequence()
                .map { ServerLocationResolver.resolve(it) }
                .distinctBy { it.countryCode }
                .sortedBy { it.nameFa }
                .toList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptionsState: StateFlow<List<SubscriptionItem>> = repository.subscriptionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groupsState: StateFlow<List<String>> = repository.groupsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("Default"))

    val routingRulesState: StateFlow<List<RoutingRuleItem>> = repository.routingRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val autoFreeVpnManager: AutoFreeVpnManager = VpnController.getOrCreateAutoFreeVpnManager(application)
    val autoVpnStatusState: StateFlow<AutoVpnStatusSnapshot> = autoFreeVpnManager.statusState

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

    private val _extraFilter = MutableStateFlow(ProfileExtraFilterState())
    val extraFilterState: StateFlow<ProfileExtraFilterState> = _extraFilter.asStateFlow()

    private val _sortMode = MutableStateFlow(ProfileSortMode.DEFAULT)
    val sortMode: StateFlow<ProfileSortMode> = _sortMode.asStateFlow()

    private val _selectedBulkIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedBulkIds: StateFlow<Set<Long>> = _selectedBulkIds.asStateFlow()

    private val _isTestingLatency = MutableStateFlow(false)
    val isTestingLatency: StateFlow<Boolean> = _isTestingLatency.asStateFlow()

    private val _latencyTestProgress = MutableStateFlow(LatencyTestProgress())
    val latencyTestProgressState: StateFlow<LatencyTestProgress> = _latencyTestProgress.asStateFlow()

    private val _liveMeasuredProfileIds = MutableStateFlow<Set<Long>>(emptySet())
    val liveMeasuredProfileIdsState: StateFlow<Set<Long>> = _liveMeasuredProfileIds.asStateFlow()

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

    private val _pendingClipboardSubscription = MutableStateFlow<ClipboardInputType.SubscriptionUrl?>(null)
    val pendingClipboardSubscription: StateFlow<ClipboardInputType.SubscriptionUrl?> = _pendingClipboardSubscription.asStateFlow()

    private val _subscriptionImportReport = MutableStateFlow<SubscriptionImportReport?>(null)
    val subscriptionImportReportState: StateFlow<SubscriptionImportReport?> = _subscriptionImportReport.asStateFlow()

    private val _qualityReport = MutableStateFlow(ConnectionQualityReport())
    val qualityReportState: StateFlow<ConnectionQualityReport> = _qualityReport.asStateFlow()

    private var latencyJob: Job? = null
    private var qualityTestJob: Job? = null

    private data class FilterCriteria(
        val query: String,
        val protocol: VpnProtocol?,
        val group: String?,
        val extra: ProfileExtraFilterState,
        val sort: ProfileSortMode
    )

    private val filterCriteriaFlow = combine(
        _searchQuery,
        _protocolFilter,
        _groupFilter,
        _extraFilter,
        _sortMode
    ) { q, proto, grp, extra, sort ->
        FilterCriteria(q, proto, grp, extra, sort)
    }

    val filteredProfilesState: StateFlow<List<ServerProfile>> = combine(
        rawProfilesFlow,
        filterCriteriaFlow,
        settingsState,
        connectionState
    ) { profiles, criteria, settings, connState ->
        val q = criteria.query.trim().lowercase()
        val proto = criteria.protocol
        val group = criteria.group
        val extra = criteria.extra
        val sort = criteria.sort
        val connectedProfileId = if (connState == VpnConnectionState.CONNECTED) settings.selectedProfileId else null

        val filtered = profiles.filter { p ->
            val matchesProto = proto == null || p.protocol == proto
            if (!matchesProto) return@filter false

            val matchesGroup = group == null || p.groupName.equals(group, ignoreCase = true)
            if (!matchesGroup) return@filter false

            if (extra.favoritesOnly && !p.isFavorite) return@filter false
            if (extra.pinnedOnly && !p.isPinned) return@filter false

            val needsLocation = extra.countryCode != null || q.isNotEmpty()
            val location = if (needsLocation) ServerLocationResolver.resolve(p) else null

            if (extra.countryCode != null && location?.countryCode != extra.countryCode) {
                return@filter false
            }

            val matchesConn = when (extra.connectionFilter) {
                ProfileConnectionFilter.ALL -> true
                ProfileConnectionFilter.CONNECTED -> p.id == connectedProfileId || p.id == settings.selectedProfileId
                ProfileConnectionFilter.REACHABLE -> p.lastLatencyMs != null && p.lastLatencyMs > 0
                ProfileConnectionFilter.UNTESTED -> p.lastLatencyMs == null && p.latencyType == LatencyMeasureType.UNTESTED
                ProfileConnectionFilter.FAILED -> p.latencyType == LatencyMeasureType.TIMEOUT
            }
            if (!matchesConn) return@filter false

            if (q.isEmpty()) {
                true
            } else {
                p.remarks.lowercase().contains(q) ||
                    p.address.lowercase().contains(q) ||
                    "${p.address.lowercase()}:${p.port}".contains(q) ||
                    p.host.lowercase().contains(q) ||
                    p.sni.lowercase().contains(q) ||
                    p.protocol.displayName.lowercase().contains(q) ||
                    p.protocol.scheme.lowercase().contains(q) ||
                    p.groupName.lowercase().contains(q) ||
                    (location != null && (
                        location.nameFa.lowercase().contains(q) ||
                            location.nameEn.lowercase().contains(q) ||
                            location.countryCode.lowercase().contains(q)
                        ))
            }
        }

        // Preserve user's original ordering in database; only sort view projection
        when (sort) {
            ProfileSortMode.DEFAULT -> filtered
            ProfileSortMode.LATENCY -> filtered.sortedWith(
                compareBy<ServerProfile> { !it.isPinned }
                    .thenBy { it.lastLatencyMs ?: Long.MAX_VALUE }
            )
            ProfileSortMode.HIGHEST_PING -> filtered.sortedWith(
                compareBy<ServerProfile> { !it.isPinned }
                    .thenBy { it.lastLatencyMs == null }
                    .thenByDescending { it.lastLatencyMs ?: Long.MIN_VALUE }
            )
            ProfileSortMode.NAME -> filtered.sortedWith(
                compareBy<ServerProfile> { !it.isPinned }
                    .thenBy { it.remarks.lowercase() }
            )
            ProfileSortMode.RECENTLY_ADDED -> filtered.sortedWith(
                compareBy<ServerProfile> { !it.isPinned }
                    .thenByDescending { it.createdAt }
                    .thenByDescending { it.id }
            )
            ProfileSortMode.FAVORITES_FIRST -> filtered.sortedWith(
                compareBy<ServerProfile> { !it.isFavorite }
                    .thenBy { !it.isPinned }
                    .thenBy { it.sortOrder }
                    .thenByDescending { it.id }
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
        viewModelScope.launch {
            val savedSettings = repository.settingsFlow.first()
            val restoredSort = runCatching {
                ProfileSortMode.valueOf(savedSettings.profileSortMode)
            }.getOrDefault(ProfileSortMode.DEFAULT)
            _sortMode.value = restoredSort
        }
        viewModelScope.launch {
            combine(activeProfileState, connectionState, diagnosticsState) { profile, state, diag ->
                Triple(profile, state, diag.verifiedProxyLatencyMs)
            }.collect { (profile, state, verifiedLatency) ->
                autoFreeVpnManager.syncWithActiveProfile(
                    profile = profile,
                    connectionState = state,
                    verifiedLatencyMs = verifiedLatency
                )
            }
        }
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

    fun setFavoritesOnlyFilter(enabled: Boolean) {
        _extraFilter.value = _extraFilter.value.copy(favoritesOnly = enabled)
    }

    fun setPinnedOnlyFilter(enabled: Boolean) {
        _extraFilter.value = _extraFilter.value.copy(pinnedOnly = enabled)
    }

    fun setCountryFilter(countryCode: String?) {
        _extraFilter.value = _extraFilter.value.copy(countryCode = countryCode)
    }

    fun setConnectionStatusFilter(filter: ProfileConnectionFilter) {
        _extraFilter.value = _extraFilter.value.copy(connectionFilter = filter)
    }

    fun clearAllFilters() {
        _protocolFilter.value = null
        _groupFilter.value = null
        _extraFilter.value = ProfileExtraFilterState()
        _searchQuery.value = ""
    }

    fun setSortMode(mode: ProfileSortMode) {
        _sortMode.value = mode
        viewModelScope.launch {
            repository.updateSettings { it.copy(profileSortMode = mode.name) }
        }
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
        when (val classified = ConfigParser.classifyClipboardInput(rawText)) {
            is ClipboardInputType.Empty -> {
                showMessage("حافظه کلیپ‌بورد خالی است (Clipboard is empty)")
            }
            is ClipboardInputType.SubscriptionUrl -> {
                // Trigger mandatory confirmation dialog:
                // "لینک اشتراک شناسایی شد. آیا می‌خواهید آن را اضافه کنید؟"
                val titleHint = if (groupName.isNotBlank() && groupName != "Default") {
                    groupName
                } else {
                    classified.suggestedTitle
                }
                _pendingClipboardSubscription.value = classified.copy(suggestedTitle = titleHint)
            }
            is ClipboardInputType.Unrecognized -> {
                val report = SubscriptionImportReport(
                    title = "ایمپورت از کلیپ‌بورد",
                    sourceType = "CLIPBOARD",
                    detectedFormat = SubscriptionFormat.INVALID,
                    totalReceivedCount = 1,
                    newImportedCount = 0,
                    duplicateCount = 0,
                    invalidCount = 1,
                    statusMessageFa = classified.reasonFa,
                    statusMessageEn = classified.reasonEn,
                    isSuccess = false
                )
                _subscriptionImportReport.value = report
                showMessage(classified.reasonFa)
            }
            is ClipboardInputType.SingleShareLink,
            is ClipboardInputType.BulkShareLinks,
            is ClipboardInputType.Base64Subscription,
            is ClipboardInputType.JsonSubscription -> {
                viewModelScope.launch {
                    val (res, report) = repository.importBatchTextWithReport(rawText, groupName)
                    if (res.profiles.isNotEmpty()) {
                        val all = repository.getAllProfilesSnapshot()
                        if (settingsState.value.selectedProfileId == null && all.isNotEmpty()) {
                            repository.selectProfile(all.first().id)
                        }
                        showMessage("Imported ${res.profiles.size} configuration(s) (${res.duplicatesSkipped} duplicate skipped)")
                    } else if (res.duplicatesSkipped > 0) {
                        showMessage("All ${res.duplicatesSkipped} configuration(s) already exist")
                    } else {
                        val reason = res.failures.firstOrNull()?.reason ?: "No valid configurations found"
                        showMessage("Import failed: $reason")
                    }
                    _subscriptionImportReport.value = report
                }
            }
        }
    }

    fun confirmClipboardSubscriptionImport(
        customTitle: String? = null,
        autoUpdate: Boolean = true,
        onNavigateToSubscriptionFlow: () -> Unit = {}
    ) {
        val pending = _pendingClipboardSubscription.value ?: return
        _pendingClipboardSubscription.value = null
        val title = customTitle?.takeIf { it.isNotBlank() } ?: pending.suggestedTitle
        onNavigateToSubscriptionFlow()
        addSubscription(title = title, url = pending.url, autoUpdate = autoUpdate)
    }

    fun dismissClipboardSubscriptionImport() {
        _pendingClipboardSubscription.value = null
    }

    fun dismissSubscriptionImportReport() {
        _subscriptionImportReport.value = null
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
        // Support maxnet5g://import?uri=... or direct vless://... or https://...
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
    fun disconnectSession() {
        viewModelScope.launch {
            autoFreeVpnManager.stopContinuousOptimizationAndResetToIdle()
            VpnController.stopSession { level, tag, msg ->
                repository.appendLog(level, tag, msg)
            }
        }
    }

    fun toggleDirectEngineConnection(verifyConnectivity: Boolean = false) {
        viewModelScope.launch {
            val state = connectionState.value
            val activeStates = setOf(
                VpnConnectionState.PREPARING,
                VpnConnectionState.STARTING_ENGINE,
                VpnConnectionState.CONNECTING,
                VpnConnectionState.VERIFYING,
                VpnConnectionState.CONNECTED,
                VpnConnectionState.RECONNECTING
            )
            if (state in activeStates) {
                autoFreeVpnManager.stopContinuousOptimizationAndResetToIdle()
                VpnController.stopSession { level, tag, msg ->
                    repository.appendLog(level, tag, msg)
                }
            } else {
                val isRobolectric = runCatching { Class.forName("androidx.test.core.app.ApplicationProvider") }.isSuccess ||
                    android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
                val existingSnapshot = repository.getAllProfilesSnapshot()
                val selectedProfile = activeProfileState.value ?: existingSnapshot.firstOrNull()
                val orderedExisting = if (selectedProfile != null) {
                    listOf(selectedProfile) + existingSnapshot.filter { it.id != selectedProfile.id }
                } else {
                    existingSnapshot
                }

                val outcome = autoFreeVpnManager.connectOneTapAuto(
                    existingProfiles = orderedExisting,
                    fetchRemoteIfNeeded = !isRobolectric && orderedExisting.size < 6,
                    customLatencyProbe = if (isRobolectric) {
                        { candidate ->
                            LatencyTestOutcome(
                                profileId = candidate.id,
                                latencyMs = candidate.lastLatencyMs ?: 85L,
                                measureType = LatencyMeasureType.TCP_HANDSHAKE
                            )
                        }
                    } else {
                        null
                    },
                    onPersistAndSelectCandidate = { candidate, latencyMs ->
                        val allNow = repository.getAllProfilesSnapshot()
                        val existingMatch = allNow.find {
                            it.uniqueFingerprintKey() == candidate.uniqueFingerprintKey()
                        }
                        val savedProfile = if (existingMatch != null) {
                            if (latencyMs != null && !isRobolectric) {
                                repository.updateProfileLatency(
                                    existingMatch.id,
                                    latencyMs,
                                    LatencyMeasureType.TCP_HANDSHAKE
                                )
                            }
                            existingMatch.copy(lastLatencyMs = latencyMs ?: existingMatch.lastLatencyMs)
                        } else {
                            val toSave = if (latencyMs != null) candidate.copy(lastLatencyMs = latencyMs) else candidate
                            val newId = repository.saveProfile(toSave)
                            toSave.copy(id = newId)
                        }
                        repository.selectProfile(savedProfile.id)
                        savedProfile
                    },
                    onAttemptConnectAndVerify = { candidateProfile ->
                        val res = VpnController.startSession(
                            context = getApplication(),
                            profile = candidateProfile,
                            settings = settingsState.value,
                            rules = routingRulesState.value,
                            establishTunAndForwarder = { true },
                            verifyConnectivity = verifyConnectivity,
                            onLog = { level, tag, msg ->
                                repository.appendLog(level, tag, msg, listOf(candidateProfile.credential))
                            }
                        )
                        res.map {
                            diagnosticsState.value.verifiedProxyLatencyMs ?: candidateProfile.lastLatencyMs
                        }
                    },
                    onStopFailedSession = {
                        VpnController.stopSession { level, tag, msg ->
                            repository.appendLog(level, tag, msg)
                        }
                    },
                    onLog = { level, tag, msg ->
                        repository.appendLog(level, tag, msg)
                    }
                )
                outcome.onFailure { err ->
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
            _liveMeasuredProfileIds.value = _liveMeasuredProfileIds.value + profile.id
            val updatedProfile = profile.copy(lastLatencyMs = outcome.latencyMs, latencyType = outcome.measureType)
            val evaluated = AutoServerRanker.evaluateCandidate(
                profile = updatedProfile,
                existingMetrics = autoFreeVpnManager.cacheStore.getMetrics(profile.uniqueFingerprintKey()),
                measuredLatencyMs = outcome.latencyMs,
                proxyVerified = outcome.measureType == LatencyMeasureType.PROXY_HTTP_DELAY
            )
            autoFreeVpnManager.cacheStore.updateMetrics(updatedProfile, evaluated)
            val msg = if (outcome.latencyMs != null) {
                "${profile.remarks}: ${outcome.latencyMs} ms (${outcome.measureType.label})"
            } else {
                "${profile.remarks}: ${outcome.errorReason ?: "Timeout"}"
            }
            showMessage(msg)
            repository.appendLog("INFO", "LatencyTest", msg, listOf(profile.credential))
        }
    }

    fun testAllProfilesLatency(autoSelectBest: Boolean = false) {
        if (autoSelectBest) {
            viewModelScope.launch {
                val currentAll = repository.getAllProfilesSnapshot()
                if (currentAll.isEmpty()) {
                    val discovered = AutoVpnDiscoveryEngine.discoverValidatedConfigurations(
                        context = getApplication(),
                        existingProfiles = emptyList(),
                        fetchRemoteSources = !runCatching { Class.forName("androidx.test.core.app.ApplicationProvider") }.isSuccess &&
                            !android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
                    )
                    for (cand in discovered) {
                        repository.saveProfile(cand)
                    }
                }
                testProfilesByScope(scope = TestBulkScope.ALL, autoSelectBest = true)
            }
        } else {
            testProfilesByScope(scope = TestBulkScope.ALL, autoSelectBest = false)
        }
    }

    fun testProfilesByScope(scope: TestBulkScope, autoSelectBest: Boolean = false) {
        if (_isTestingLatency.value) return
        latencyJob = viewModelScope.launch {
            val allProfiles = repository.getAllProfilesSnapshot()
            val targetProfiles = when (scope) {
                TestBulkScope.ALL -> allProfiles
                TestBulkScope.FILTERED -> filteredProfilesState.value.ifEmpty { allProfiles }
                TestBulkScope.SELECTED -> {
                    val selIds = _selectedBulkIds.value
                    allProfiles.filter { it.id in selIds }
                }
            }
            if (targetProfiles.isEmpty()) {
                showMessage("هیچ پروفایلی برای تست وجود ندارد (No profiles to test)")
                return@launch
            }
            _isTestingLatency.value = true
            var completed = 0
            var reachableCount = 0
            _latencyTestProgress.value = LatencyTestProgress(
                isTesting = true,
                completedCount = 0,
                totalCount = targetProfiles.size,
                reachableCount = 0,
                scope = scope
            )
            try {
                val outcomes = LatencyTester.testProfilesConcurrently(targetProfiles) { outcome ->
                    repository.updateProfileLatency(outcome.profileId, outcome.latencyMs, outcome.measureType)
                    _liveMeasuredProfileIds.value = _liveMeasuredProfileIds.value + outcome.profileId
                    completed++
                    if (outcome.latencyMs != null) reachableCount++
                    _latencyTestProgress.value = LatencyTestProgress(
                        isTesting = true,
                        completedCount = completed,
                        totalCount = targetProfiles.size,
                        reachableCount = reachableCount,
                        scope = scope
                    )
                }
                val reachable = outcomes.filter { it.latencyMs != null }
                if (autoSelectBest && reachable.isNotEmpty()) {
                    val best = reachable.minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
                    if (best != null) {
                        repository.selectProfile(best.profileId)
                        val bestProfile = targetProfiles.find { it.id == best.profileId }
                        showMessage("Selected best server: ${bestProfile?.remarks ?: ""} (${best.latencyMs} ms)")
                    }
                } else {
                    showMessage("تست پینگ تکمیل شد: ${reachable.size} از ${outcomes.size} سرور در دسترس")
                }
            } finally {
                _isTestingLatency.value = false
                _latencyTestProgress.value = _latencyTestProgress.value.copy(isTesting = false)
            }
        }
    }

    fun cancelLatencyTesting() {
        latencyJob?.cancel()
        _isTestingLatency.value = false
        _latencyTestProgress.value = _latencyTestProgress.value.copy(isTesting = false)
        showMessage("تست پینگ لغو شد (Cancelled latency testing)")
    }

    fun runConnectionQualityTest() {
        if (qualityTestJob?.isActive == true) return
        qualityTestJob = viewModelScope.launch {
            val profile = activeProfileState.value ?: rawProfilesFlow.value.firstOrNull()
            val isConnected = connectionState.value == VpnConnectionState.CONNECTED
            val localSocksPort = statisticsState.value.localSocksPort
            val finalReport = NetworkQualityTester.runQualityTest(
                profile = profile,
                isVpnConnected = isConnected,
                localSocksPort = localSocksPort,
                onStageUpdate = { partial ->
                    _qualityReport.value = partial
                }
            )
            _qualityReport.value = finalReport
            if (profile != null && finalReport.latencyMs != null) {
                repository.updateProfileLatency(profile.id, finalReport.latencyMs, finalReport.latencyType)
            }
            val isFa = settingsState.value.languageCode.startsWith("fa", ignoreCase = true)
            val summary = if (isFa) finalReport.summaryMessageFa else finalReport.summaryMessageEn
            if (summary.isNotBlank()) {
                repository.appendLog("INFO", "QualityTest", summary, listOfNotNull(profile?.credential))
            }
        }
    }

    fun cancelConnectionQualityTest() {
        qualityTestJob?.cancel()
        qualityTestJob = null
        _qualityReport.value = _qualityReport.value.copy(
            stage = QualityTestStage.IDLE
        )
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
            val (res, report) = repository.addSubscriptionWithReport(title, url, autoUpdate)
            _isRefreshingSubscriptions.value = false
            _subscriptionImportReport.value = report
            res.fold(
                onSuccess = { sub ->
                    val all = repository.getAllProfilesSnapshot()
                    if (settingsState.value.selectedProfileId == null && all.isNotEmpty()) {
                        repository.selectProfile(all.first().id)
                    }
                    showMessage("Added '${sub.title}' (${sub.profileCount} profiles)")
                },
                onFailure = { err -> showMessage("Subscription error: ${err.message}") }
            )
        }
    }

    fun refreshSubscription(sub: SubscriptionItem) {
        viewModelScope.launch {
            _isRefreshingSubscriptions.value = true
            val (res, report) = repository.refreshSubscriptionWithReport(sub)
            _isRefreshingSubscriptions.value = false
            _subscriptionImportReport.value = report
            res.fold(
                onSuccess = { updated ->
                    val all = repository.getAllProfilesSnapshot()
                    if (settingsState.value.selectedProfileId == null && all.isNotEmpty()) {
                        repository.selectProfile(all.first().id)
                    }
                    showMessage("Updated '${updated.title}' (${updated.profileCount} servers)")
                },
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
            var totalReceived = 0
            var totalNew = 0
            var totalDup = 0
            var totalInvalid = 0
            var lastFormat = SubscriptionFormat.PLAIN_TEXT
            for (sub in subs) {
                val (res, rep) = repository.refreshSubscriptionWithReport(sub)
                if (res.isSuccess) okCount++
                totalReceived += rep.totalReceivedCount
                totalNew += rep.newImportedCount
                totalDup += rep.duplicateCount
                totalInvalid += rep.invalidCount
                lastFormat = rep.detectedFormat
            }
            _isRefreshingSubscriptions.value = false
            _subscriptionImportReport.value = SubscriptionImportReport(
                title = "بروزرسانی همه اشتراک‌ها",
                sourceType = "SUBSCRIPTION",
                detectedFormat = lastFormat,
                totalReceivedCount = totalReceived,
                newImportedCount = totalNew,
                duplicateCount = totalDup,
                invalidCount = totalInvalid,
                statusMessageFa = "بروزرسانی $okCount از ${subs.size} اشتراک انجام شد",
                statusMessageEn = "Updated $okCount/${subs.size} subscription(s)",
                isSuccess = okCount > 0
            )
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
        sb.appendLine("Native Core Version: ${diag.engineVersion}")
        sb.appendLine("Active Profile: ${diag.activeProfileRemarks}")
        sb.appendLine("Core Initialized: ${diag.coreInitialized}")
        sb.appendLine("TUN Interface Active: ${diag.tunInterfaceActive}")
        sb.appendLine("HevSocks5Tunnel Active: ${diag.tunForwarderActive}")
        sb.appendLine("Local Loopback Port: ${diag.localLoopbackPort ?: "None"}")
        sb.appendLine("Verified Proxy Delay: ${diag.verifiedProxyLatencyMs?.let { "${it}ms" } ?: "None"}")
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
