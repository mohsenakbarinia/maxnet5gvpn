package com.example.core.sync

import com.example.core.autovpn.AutoFreeVpnManager
import com.example.data.repository.VpnRepository
import com.example.domain.model.ServerProfile
import com.example.domain.model.SubscriptionImportReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

enum class SyncPhase {
    IDLE,
    SYNCING,
    UPDATED,
    PARTIAL_WARNING,
    OFFLINE_CACHED,
    ERROR
}

enum class SyncProgressState {
    IDLE,
    SYNCING,
    SUCCESS,
    ERROR
}

data class CategorySyncStatus(
    val category: String = "",
    val phase: SyncPhase = SyncPhase.IDLE,
    val isSyncing: Boolean = false,
    val serverCount: Int = 0,
    val subscriptionCount: Int = 0,
    val succeededSubscriptionCount: Int = 0,
    val failedSubscriptionCount: Int = 0,
    val newServersAdded: Int = 0,
    val lastSuccessfulSyncMs: Long = 0L,
    val lastAttemptMs: Long = 0L,
    val statusMessageEn: String = "",
    val statusMessageFa: String = "",
    val errorDetail: String? = null
) {
    val state: SyncProgressState
        get() = when (phase) {
            SyncPhase.IDLE -> SyncProgressState.IDLE
            SyncPhase.SYNCING -> SyncProgressState.SYNCING
            SyncPhase.UPDATED, SyncPhase.PARTIAL_WARNING -> SyncProgressState.SUCCESS
            SyncPhase.OFFLINE_CACHED, SyncPhase.ERROR -> SyncProgressState.ERROR
        }

    val messageEn: String get() = statusMessageEn
    val messageFa: String get() = statusMessageFa
}

data class DualSyncState(
    val personalSync: CategorySyncStatus = CategorySyncStatus(
        category = "PERSONAL",
        statusMessageEn = "Personal servers ready",
        statusMessageFa = "سرورهای شخصی آماده"
    ),
    val freeVpnSync: CategorySyncStatus = CategorySyncStatus(
        category = "FREE_VPN",
        statusMessageEn = "Free VPN servers ready",
        statusMessageFa = "سرورهای وی‌پی‌ان رایگان آماده"
    ),
    val isLaunchSyncCompleted: Boolean = false
) {
    val isAnySyncing: Boolean get() = personalSync.isSyncing || freeVpnSync.isSyncing
    val personalStatus: CategorySyncStatus get() = personalSync
    val freeVpnStatus: CategorySyncStatus get() = freeVpnSync
}

/**
 * Centralized Automatic Synchronization Manager for MAXNET5G.
 *
 * - Runs automatically on application launch in the background after local data initialization.
 * - Refreshes **Personal Servers (My VPN)** and **Free VPN Servers** independently and concurrently
 *   using Kotlin Coroutines and structured concurrency (`supervisorScope`).
 * - Prevents duplicate simultaneous refresh requests using independent mutex locks.
 * - Never blocks access to existing cached servers and never interrupts an active VPN connection.
 * - Strictly isolates Personal configurations (`VpnRepository` / Room) from Free VPN configurations
 *   (`AutoFreeVpnManager` / `AutoVpnCacheStore`).
 */
class AutoServerSyncManager(
    private val vpnRepository: VpnRepository,
    private val autoFreeVpnManager: AutoFreeVpnManager,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onLog: (level: String, tag: String, message: String) -> Unit = { _, _, _ -> }
) {
    private val personalMutex = Mutex()
    private val freeVpnMutex = Mutex()
    private val launchSyncTriggered = AtomicBoolean(false)

    private val _syncState = MutableStateFlow(
        DualSyncState(
            freeVpnSync = CategorySyncStatus(
                phase = SyncPhase.IDLE,
                serverCount = autoFreeVpnManager.cacheStore.getCachedValidatedCount(),
                lastSuccessfulSyncMs = autoFreeVpnManager.cacheStore.getLastRefreshTimestampMs(),
                statusMessageEn = "Free VPN servers ready (${autoFreeVpnManager.cacheStore.getCachedValidatedCount()})",
                statusMessageFa = "سرورهای وی‌پی‌ان رایگان آماده (${autoFreeVpnManager.cacheStore.getCachedValidatedCount()})"
            )
        )
    )
    val syncState: StateFlow<DualSyncState> = _syncState.asStateFlow()

    /**
     * Triggers the automatic application-launch synchronization once per process launch
     * (or whenever explicitly requested via [force] = true).
     * Runs Personal Subscriptions refresh and Free VPN refresh independently and concurrently.
     */
    fun triggerLaunchSync(
        force: Boolean = false,
        customFreeVpnFetcher: (suspend (String) -> String?)? = null,
        onComplete: ((DualSyncState) -> Unit)? = null
    ) {
        if (!force && !launchSyncTriggered.compareAndSet(false, true)) {
            return
        }
        externalScope.launch {
            syncBothConcurrently(
                forceAllPersonalSubscriptions = false,
                customFreeVpnFetcher = customFreeVpnFetcher
            )
            onComplete?.invoke(_syncState.value)
        }
    }

    /**
     * Refreshes Personal Subscriptions (`My VPN`) and Free VPN Servers (`Free VPN`)
     * independently and concurrently using structured concurrency.
     */
    suspend fun syncBothConcurrently(
        forceAllPersonalSubscriptions: Boolean = false,
        customFreeVpnFetcher: (suspend (String) -> String?)? = null
    ): DualSyncState = supervisorScope {
        val personalDeferred = async(Dispatchers.IO) {
            syncPersonalSubscriptions(forceAll = forceAllPersonalSubscriptions)
        }
        val freeDeferred = async(Dispatchers.IO) {
            syncFreeVpnServers(customFetcher = customFreeVpnFetcher)
        }
        personalDeferred.await()
        freeDeferred.await()
        _syncState.update { it.copy(isLaunchSyncCompleted = true) }
        _syncState.value
    }

    suspend fun syncAllConcurrently(
        force: Boolean = false,
        customFreeVpnFetcher: (suspend () -> String?)? = null
    ): DualSyncState = syncBothConcurrently(
        forceAllPersonalSubscriptions = force,
        customFreeVpnFetcher = if (customFreeVpnFetcher != null) { _ -> customFreeVpnFetcher() } else null
    )

    /**
     * Synchronizes all Personal Subscriptions (`My VPN`) in-place:
     * - Preserves existing profile IDs, custom remarks, favorites, pins, and selected server state.
     * - Never modifies or deletes manually imported profiles (`subscriptionId == null`).
     * - If one personal subscription fails, keeps its existing saved configurations and continues
     *   updating the remaining subscriptions.
     * - Prevents duplicate concurrent personal sync executions.
     */
    suspend fun syncPersonalSubscriptions(
        forceAll: Boolean = false
    ): CategorySyncStatus {
        if (!personalMutex.tryLock()) {
            // Another personal sync is already in flight; return current status without duplicating requests
            return _syncState.value.personalSync
        }
        val startMs = System.currentTimeMillis()
        try {
            val allSubs = vpnRepository.getAllSubscriptionsSnapshot()
            val subsToRefresh = if (forceAll) {
                allSubs
            } else {
                allSubs.filter { it.enabledAutoUpdate }
            }
            val initialProfiles = vpnRepository.getAllProfilesSnapshot()

            if (subsToRefresh.isEmpty()) {
                val maxUpdated = allSubs.mapNotNull { it.lastUpdatedAt }.maxOrNull() ?: startMs
                val idleStatus = CategorySyncStatus(
                    category = "PERSONAL",
                    phase = SyncPhase.UPDATED,
                    isSyncing = false,
                    serverCount = initialProfiles.size,
                    subscriptionCount = allSubs.size,
                    succeededSubscriptionCount = 0,
                    failedSubscriptionCount = 0,
                    lastSuccessfulSyncMs = maxUpdated,
                    lastAttemptMs = startMs,
                    statusMessageEn = "Personal servers (${initialProfiles.size})",
                    statusMessageFa = "سرورهای شخصی (${initialProfiles.size} سرور)"
                )
                _syncState.update { it.copy(personalSync = idleStatus) }
                return idleStatus
            }

            _syncState.update { current ->
                current.copy(
                    personalSync = current.personalSync.copy(
                        phase = SyncPhase.SYNCING,
                        isSyncing = true,
                        serverCount = initialProfiles.size,
                        subscriptionCount = subsToRefresh.size,
                        lastAttemptMs = startMs,
                        statusMessageEn = "Updating personal subscriptions...",
                        statusMessageFa = "در حال بروزرسانی اشتراک‌های شخصی...",
                        errorDetail = null
                    )
                )
            }

            var succeeded = 0
            var failed = 0
            var totalNewAdded = 0
            var lastErrorMsg: String? = null
            val reports = mutableListOf<SubscriptionImportReport>()

            for (sub in subsToRefresh) {
                val (res, report) = runCatching {
                    vpnRepository.refreshSubscriptionWithReport(sub)
                }.getOrElse { err ->
                    if (err is CancellationException) throw err
                    Result.failure<com.example.domain.model.SubscriptionItem>(err) to SubscriptionImportReport(
                        title = sub.title,
                        sourceType = "SUBSCRIPTION",
                        detectedFormat = com.example.domain.model.SubscriptionFormat.INVALID,
                        totalReceivedCount = 0,
                        newImportedCount = 0,
                        duplicateCount = 0,
                        invalidCount = 0,
                        statusMessageFa = "خطا در بروزرسانی اشتراک ${sub.title}",
                        statusMessageEn = err.message ?: "Failed to refresh subscription",
                        isSuccess = false
                    )
                }
                reports.add(report)
                if (res.isSuccess) {
                    succeeded++
                    totalNewAdded += report.newImportedCount
                } else {
                    failed++
                    lastErrorMsg = report.statusMessageEn
                }
            }

            val updatedProfiles = vpnRepository.getAllProfilesSnapshot()
            val nowMs = System.currentTimeMillis()
            val finalStatus = when {
                succeeded > 0 && failed == 0 -> CategorySyncStatus(
                    phase = SyncPhase.UPDATED,
                    isSyncing = false,
                    serverCount = updatedProfiles.size,
                    subscriptionCount = subsToRefresh.size,
                    succeededSubscriptionCount = succeeded,
                    failedSubscriptionCount = 0,
                    newServersAdded = totalNewAdded,
                    lastSuccessfulSyncMs = nowMs,
                    lastAttemptMs = nowMs,
                    statusMessageEn = "Personal servers updated (${updatedProfiles.size})",
                    statusMessageFa = "سرورهای شخصی بروزرسانی شد (${updatedProfiles.size} سرور)"
                )
                succeeded > 0 && failed > 0 -> CategorySyncStatus(
                    phase = SyncPhase.PARTIAL_WARNING,
                    isSyncing = false,
                    serverCount = updatedProfiles.size,
                    subscriptionCount = subsToRefresh.size,
                    succeededSubscriptionCount = succeeded,
                    failedSubscriptionCount = failed,
                    newServersAdded = totalNewAdded,
                    lastSuccessfulSyncMs = nowMs,
                    lastAttemptMs = nowMs,
                    statusMessageEn = "Personal servers updated (${updatedProfiles.size}) • $failed sub(s) kept cached",
                    statusMessageFa = "سرورهای شخصی بروز شد (${updatedProfiles.size}) • $failed اشتراک از کش حفظ شد",
                    errorDetail = lastErrorMsg
                )
                else -> CategorySyncStatus(
                    phase = if (updatedProfiles.isNotEmpty()) SyncPhase.OFFLINE_CACHED else SyncPhase.ERROR,
                    isSyncing = false,
                    serverCount = updatedProfiles.size,
                    subscriptionCount = subsToRefresh.size,
                    succeededSubscriptionCount = 0,
                    failedSubscriptionCount = failed,
                    newServersAdded = 0,
                    lastSuccessfulSyncMs = _syncState.value.personalSync.lastSuccessfulSyncMs,
                    lastAttemptMs = nowMs,
                    statusMessageEn = if (updatedProfiles.isNotEmpty()) {
                        "Personal update failed • Saved servers preserved (${updatedProfiles.size})"
                    } else {
                        "Personal subscription update failed"
                    },
                    statusMessageFa = if (updatedProfiles.isNotEmpty()) {
                        "بروزرسانی ناموفق بود • سرورهای ذخیره‌شده حفظ شدند (${updatedProfiles.size})"
                    } else {
                        "خطا در بروزرسانی اشتراک‌های شخصی"
                    },
                    errorDetail = lastErrorMsg
                )
            }

            _syncState.update { it.copy(personalSync = finalStatus) }
            onLog(
                if (failed == 0) "INFO" else "WARN",
                "AutoSync",
                "Personal subscriptions sync completed: $succeeded/${subsToRefresh.size} succeeded, total personal servers=${updatedProfiles.size}"
            )
            return finalStatus
        } catch (ce: CancellationException) {
            _syncState.update { current ->
                current.copy(personalSync = current.personalSync.copy(isSyncing = false))
            }
            throw ce
        } finally {
            personalMutex.unlock()
        }
    }

    /**
     * Synchronizes Free VPN Servers from the exclusive subscription endpoint:
     * `"https://io.maxnet6g.ir:2096/sub/acme0ym4y3dswjo5"`
     * - Inspects Content-Type & content format (Plain-text, Base64, Xray JSON).
     * - Supports conditional HTTP requests (`ETag`, `Last-Modified`, `HTTP 304`).
     * - Preserves the last valid cached Free VPN server list if the endpoint is unavailable or returns invalid data.
     * - Prevents duplicate concurrent Free VPN sync executions.
     */
    suspend fun syncFreeVpnServers(
        force: Boolean = false,
        customFetcher: (suspend (String) -> String?)? = null
    ): CategorySyncStatus {
        if (!freeVpnMutex.tryLock()) {
            return _syncState.value.freeVpnSync
        }
        val startMs = System.currentTimeMillis()
        try {
            val cachedBefore = autoFreeVpnManager.cacheStore.getCachedValidatedCount()
            _syncState.update { current ->
                current.copy(
                    freeVpnSync = current.freeVpnSync.copy(
                        phase = SyncPhase.SYNCING,
                        isSyncing = true,
                        serverCount = cachedBefore,
                        subscriptionCount = 1,
                        lastAttemptMs = startMs,
                        statusMessageEn = "Updating Free VPN servers...",
                        statusMessageFa = "در حال بروزرسانی سرورهای وی‌پی‌ان رایگان...",
                        errorDetail = null
                    )
                )
            }

            val result: Result<List<ServerProfile>> = autoFreeVpnManager.refreshSubscriptionCatalog(
                customFetcher = customFetcher,
                onLog = onLog
            )

            val nowMs = System.currentTimeMillis()
            val cachedAfter = autoFreeVpnManager.cacheStore.getCachedValidatedCount()
            val finalStatus = result.fold(
                onSuccess = { list ->
                    val count = list.size.coerceAtLeast(cachedAfter)
                    CategorySyncStatus(
                        phase = SyncPhase.UPDATED,
                        isSyncing = false,
                        serverCount = count,
                        subscriptionCount = 1,
                        succeededSubscriptionCount = 1,
                        failedSubscriptionCount = 0,
                        newServersAdded = (count - cachedBefore).coerceAtLeast(0),
                        lastSuccessfulSyncMs = autoFreeVpnManager.cacheStore.getLastRefreshTimestampMs().takeIf { it > 0L } ?: nowMs,
                        lastAttemptMs = nowMs,
                        statusMessageEn = "Free VPN servers updated ($count)",
                        statusMessageFa = "سرورهای وی‌پی‌ان رایگان بروزرسانی شد ($count سرور)"
                    )
                },
                onFailure = { err ->
                    CategorySyncStatus(
                        phase = if (cachedAfter > 0) SyncPhase.OFFLINE_CACHED else SyncPhase.ERROR,
                        isSyncing = false,
                        serverCount = cachedAfter,
                        subscriptionCount = 1,
                        succeededSubscriptionCount = 0,
                        failedSubscriptionCount = 1,
                        newServersAdded = 0,
                        lastSuccessfulSyncMs = autoFreeVpnManager.cacheStore.getLastRefreshTimestampMs(),
                        lastAttemptMs = nowMs,
                        statusMessageEn = if (cachedAfter > 0) {
                            "Free VPN cached servers preserved ($cachedAfter)"
                        } else {
                            "Free VPN update failed"
                        },
                        statusMessageFa = if (cachedAfter > 0) {
                            "سرورهای ذخیره‌شده وی‌پی‌ان رایگان حفظ شدند ($cachedAfter سرور)"
                        } else {
                            "خطا در بروزرسانی سرورهای وی‌پی‌ان رایگان"
                        },
                        errorDetail = err.message
                    )
                }
            )

            _syncState.update { it.copy(freeVpnSync = finalStatus) }
            return finalStatus
        } catch (ce: CancellationException) {
            _syncState.update { current ->
                current.copy(freeVpnSync = current.freeVpnSync.copy(isSyncing = false))
            }
            throw ce
        } finally {
            freeVpnMutex.unlock()
        }
    }
}
