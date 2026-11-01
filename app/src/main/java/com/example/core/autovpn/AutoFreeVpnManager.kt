package com.example.core.autovpn

import android.content.Context
import com.example.core.LatencyTestOutcome
import com.example.core.LatencyTester
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.ServerProfile
import com.example.domain.model.VpnConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates the MAXNET5G Intelligent Auto Free VPN System:
 * 1. Automatic & Manual Subscription Download from the dedicated Cloudflare Workers endpoint
 *    with multi-format detection (Plain-text, Base64, JSON) and non-destructive local caching.
 * 2. Configurable Automatic Refresh Interval (default 30 minutes).
 * 3. Controlled Parallel Candidate Testing (Semaphore-bounded, non-sequential).
 * 4. Intelligent Multi-Factor Server Ranking (AutoServerRanker).
 * 5. One-Tap Connection & Real Xray Engine Verification.
 * 6. Intelligent Failover with retry limits, per-server cooldowns, exponential backoff, and
 *    anti-flap protection against rapid server switching.
 */
class AutoFreeVpnManager(
    private val context: Context? = null,
    val cacheStore: AutoVpnCacheStore = AutoVpnCacheStore(context),
    private val maxParallelTests: Int = 8,
    private val candidateTestTimeoutMs: Int = 2200,
    private val maxCandidatesPerTestBatch: Int = 24,
    private val maxFailoverAttempts: Int = 5,
    private val baseFailoverBackoffMs: Long = 350L,
    @Volatile var autoRefreshIntervalMs: Long = DEFAULT_AUTO_REFRESH_INTERVAL_MS,
    private val minServerSwitchIntervalMs: Long = DEFAULT_MIN_SWITCH_INTERVAL_MS
) {

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var continuousOptimizationJob: Job? = null

    @Volatile
    private var lastServerSwitchTimestampMs: Long = 0L

    private val _statusState = MutableStateFlow(
        AutoVpnStatusSnapshot(
            cachedValidatedCount = cacheStore.getCachedValidatedCount(),
            manifestVersion = cacheStore.getLastManifest()?.version ?: 1,
            manifestUpdatedAt = cacheStore.getLastManifest()?.updatedAt ?: "",
            manifestSha256 = cacheStore.getLastManifest()?.sha256 ?: "",
            detectedSubscriptionFormat = cacheStore.getLastManifest()?.detectedFormat ?: "",
            lastRefreshTimestampMs = cacheStore.getLastRefreshTimestampMs(),
            autoRefreshIntervalMs = autoRefreshIntervalMs
        )
    )
    val statusState: StateFlow<AutoVpnStatusSnapshot> = _statusState.asStateFlow()

    private var lastRankedCandidates: List<RankedVpnCandidate> = emptyList()

    fun getLastRankedCandidates(): List<RankedVpnCandidate> = lastRankedCandidates

    fun setAutoRefreshInterval(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(MIN_AUTO_REFRESH_INTERVAL_MS, MAX_AUTO_REFRESH_INTERVAL_MS)
        autoRefreshIntervalMs = clamped
        _statusState.value = _statusState.value.copy(
            autoRefreshIntervalMs = clamped,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    fun isSubscriptionRefreshDue(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastRefresh = cacheStore.getLastRefreshTimestampMs()
        if (lastRefresh <= 0L || cacheStore.getCachedValidatedCount() == 0) return true
        return (nowMs - lastRefresh) >= autoRefreshIntervalMs
    }

    /**
     * Manually or automatically refreshes the Free VPN subscription from the dedicated
     * Cloudflare Workers endpoint.
     *
     * Never erases the previous valid cache if the refresh fails.
     */
    suspend fun refreshSubscriptionCatalog(
        customFetcher: (suspend (String) -> String?)? = null,
        onLog: (String, String, String) -> Unit = { _, _, _ -> }
    ): Result<List<ServerProfile>> {
        val previousCached = cacheStore.getCachedValidatedProfiles()
        val refreshStartMs = System.currentTimeMillis()
        _statusState.value = _statusState.value.copy(
            isRefreshingSubscription = true,
            detailMessageEn = "Refreshing Free VPN subscription configurations...",
            detailMessageFa = "در حال بروزرسانی کانفیگ‌های اشتراک وی‌پی‌ان رایگان...",
            lastUpdatedMs = refreshStartMs
        )

        return try {
            AutoVpnDiscoveryEngine.discoverValidatedConfigurations(
                context = context,
                existingProfiles = emptyList(),
                fetchRemoteSources = true,
                cacheStore = cacheStore,
                customFetcher = customFetcher
            )
            val updatedManifest = cacheStore.getLastManifest()
            val remoteSucceeded = updatedManifest != null && updatedManifest.fetchedAtMs >= refreshStartMs
            val currentCached = cacheStore.getCachedValidatedProfiles()

            if (remoteSucceeded && currentCached.isNotEmpty()) {
                _statusState.value = _statusState.value.copy(
                    isRefreshingSubscription = false,
                    discoveredCount = currentCached.size,
                    cachedValidatedCount = cacheStore.getCachedValidatedCount(),
                    manifestVersion = updatedManifest?.version ?: 1,
                    manifestUpdatedAt = updatedManifest?.updatedAt ?: "",
                    manifestSha256 = updatedManifest?.sha256 ?: "",
                    detectedSubscriptionFormat = updatedManifest?.detectedFormat ?: "",
                    lastRefreshTimestampMs = cacheStore.getLastRefreshTimestampMs(),
                    isUsingOfflineCache = false,
                    lastSyncStatusEn = "Synced Free VPN Subscription (${currentCached.size} servers)",
                    lastSyncStatusFa = "اشتراک وی‌پی‌ان رایگان بروزرسانی شد (${currentCached.size} سرور)",
                    detailMessageEn = "Loaded ${currentCached.size} validated Free VPN configurations.",
                    detailMessageFa = "${currentCached.size} کانفیگ معتبر رایگان دریافت و ذخیره شد.",
                    lastUpdatedMs = System.currentTimeMillis()
                )
                onLog(
                    "INFO",
                    "AutoFreeVPN",
                    "Refreshed Free VPN subscription: ${currentCached.size} valid configs (Format: ${updatedManifest?.detectedFormat ?: "AUTO"})"
                )
                Result.success(currentCached)
            } else {
                // Remote refresh failed — preserve previous valid cache!
                val preservedCount = previousCached.size
                _statusState.value = _statusState.value.copy(
                    isRefreshingSubscription = false,
                    cachedValidatedCount = preservedCount,
                    isUsingOfflineCache = preservedCount > 0,
                    lastSyncStatusEn = if (preservedCount > 0) {
                        "Offline Cache Active ($preservedCount saved servers)"
                    } else {
                        "Subscription Refresh Failed"
                    },
                    lastSyncStatusFa = if (preservedCount > 0) {
                        "کش آفلاین فعال ($preservedCount سرور ذخیره‌شده)"
                    } else {
                        "خطا در بروزرسانی اشتراک رایگان"
                    },
                    detailMessageEn = if (preservedCount > 0) {
                        "Refresh failed; preserved $preservedCount valid cached configurations."
                    } else {
                        "Could not reach Free VPN subscription endpoint."
                    },
                    detailMessageFa = if (preservedCount > 0) {
                        "بروزرسانی ناموفق بود؛ $preservedCount کانفیگ معتبر قبلی حفظ شد."
                    } else {
                        "امکان دریافت اشتراک وی‌پی‌ان رایگان وجود ندارد."
                    },
                    lastUpdatedMs = System.currentTimeMillis()
                )
                onLog(
                    "WARN",
                    "AutoFreeVPN",
                    "Free VPN subscription refresh failed; preserved $preservedCount cached configurations"
                )
                Result.failure(IllegalStateException("Subscription refresh failed; preserved $preservedCount cached configurations"))
            }
        } catch (ce: CancellationException) {
            _statusState.value = _statusState.value.copy(isRefreshingSubscription = false)
            throw ce
        } catch (t: Throwable) {
            val preservedCount = cacheStore.getCachedValidatedCount()
            _statusState.value = _statusState.value.copy(
                isRefreshingSubscription = false,
                cachedValidatedCount = preservedCount,
                isUsingOfflineCache = preservedCount > 0,
                lastUpdatedMs = System.currentTimeMillis()
            )
            Result.failure(t)
        }
    }

    /**
     * Executes the complete One-Tap Auto Free VPN pipeline:
     * Discover -> Parallel Test -> Rank -> Select Best -> Connect & Verify -> Automatic Failover.
     */
    suspend fun connectOneTapAuto(
        existingProfiles: List<ServerProfile> = emptyList(),
        fetchRemoteIfNeeded: Boolean = true,
        forceRemoteRefresh: Boolean = false,
        customDiscoveryFetcher: (suspend (String) -> String?)? = null,
        customLatencyProbe: (suspend (ServerProfile) -> LatencyTestOutcome)? = null,
        onPersistAndSelectCandidate: suspend (ServerProfile, Long?) -> ServerProfile = { p, lat ->
            if (lat != null) p.copy(lastLatencyMs = lat) else p
        },
        onAttemptConnectAndVerify: suspend (ServerProfile) -> Result<Long?>,
        onStopFailedSession: suspend () -> Unit = {},
        onLog: (String, String, String) -> Unit = { _, _, _ -> }
    ): Result<RankedVpnCandidate> {
        try {
            val startMs = System.currentTimeMillis()
            // Stage 1: Automatic Configuration Discovery from dedicated Cloudflare Workers endpoint
            _statusState.value = _statusState.value.copy(
                stage = AutoVpnStage.DISCOVERING_CONFIGS,
                detailMessageEn = "Syncing Free VPN subscription configurations...",
                detailMessageFa = "در حال دریافت کانفیگ‌های اشتراک وی‌پی‌ان رایگان...",
                failoverAttempt = 0,
                lastUpdatedMs = startMs
            )

            val cachedProfilesBefore = cacheStore.getCachedValidatedProfiles()
            val combinedExisting = (existingProfiles + cachedProfilesBefore).distinctBy { it.uniqueFingerprintKey() }

            val shouldFetchRemote = fetchRemoteIfNeeded && (
                forceRemoteRefresh ||
                    customDiscoveryFetcher != null ||
                    combinedExisting.size < 8 ||
                    isSubscriptionRefreshDue(startMs)
                )

            val discovered = AutoVpnDiscoveryEngine.discoverValidatedConfigurations(
                context = context,
                existingProfiles = combinedExisting,
                fetchRemoteSources = shouldFetchRemote,
                cacheStore = cacheStore,
                customFetcher = customDiscoveryFetcher
            )

            val latestCachedProfiles = cacheStore.getCachedValidatedProfiles()
            val latestManifest = cacheStore.getLastManifest()
            val remoteFetchedFresh = shouldFetchRemote &&
                latestManifest != null &&
                latestManifest.fetchedAtMs >= startMs

            val rawPool = if (remoteFetchedFresh && latestCachedProfiles.isNotEmpty()) {
                (latestCachedProfiles + existingProfiles).distinctBy { it.uniqueFingerprintKey() }
            } else {
                (combinedExisting + discovered).distinctBy { it.uniqueFingerprintKey() }
            }

            val candidatePool = rawPool.mapIndexed { idx, profile ->
                if (profile.id == 0L) profile.copy(id = -(idx + 1000L)) else profile
            }

            val usingOfflineCache = !remoteFetchedFresh && cachedProfilesBefore.isNotEmpty()

            if (candidatePool.isEmpty()) {
                _statusState.value = _statusState.value.copy(
                    stage = AutoVpnStage.EXHAUSTED_ERROR,
                    detailMessageEn = "No valid configurations discovered.",
                    detailMessageFa = "هیچ کانفیگ معتبری یافت نشد.",
                    lastUpdatedMs = System.currentTimeMillis()
                )
                return Result.failure(IllegalStateException("No valid VPN configurations available"))
            }

            onLog(
                "INFO",
                "AutoFreeVPN",
                "Discovered ${candidatePool.size} unique validated candidates (Cached: ${cacheStore.getCachedValidatedCount()}, OfflineCache: $usingOfflineCache)"
            )

            // Pre-rank candidates using cached metrics & network compatibility to test the most promising batch first
            val preRanked = AutoServerRanker.rankCandidates(
                profiles = candidatePool,
                metricsMap = cacheStore.getMetricsMap()
            )
            val batchToTest = preRanked.take(maxCandidatesPerTestBatch).map { it.profile }

            // Stage 2: Controlled Parallel Candidate Testing
            _statusState.value = _statusState.value.copy(
                stage = AutoVpnStage.TESTING_CANDIDATES,
                discoveredCount = candidatePool.size,
                testedCount = 0,
                totalToTestCount = batchToTest.size,
                cachedValidatedCount = cacheStore.getCachedValidatedCount(),
                manifestVersion = latestManifest?.version ?: 1,
                manifestUpdatedAt = latestManifest?.updatedAt ?: "",
                manifestSha256 = latestManifest?.sha256 ?: "",
                detectedSubscriptionFormat = latestManifest?.detectedFormat ?: "",
                lastRefreshTimestampMs = cacheStore.getLastRefreshTimestampMs(),
                isUsingOfflineCache = usingOfflineCache,
                lastSyncStatusEn = when {
                    usingOfflineCache -> "Offline Cache Active (${candidatePool.size} saved servers)"
                    latestManifest != null -> "Synced Subscription (${candidatePool.size} servers)"
                    else -> "Ready • ${candidatePool.size} Validated Free Configurations"
                },
                lastSyncStatusFa = when {
                    usingOfflineCache -> "کش آفلاین فعال (${candidatePool.size} سرور ذخیره‌شده)"
                    latestManifest != null -> "همگام با اشتراک رایگان (${candidatePool.size} سرور)"
                    else -> "آماده • ${candidatePool.size} کانفیگ رایگان تاییدشده"
                },
                detailMessageEn = "Testing ${batchToTest.size} candidates in parallel...",
                detailMessageFa = "در حال تست موازی ${batchToTest.size} سرور کاندید...",
                lastUpdatedMs = System.currentTimeMillis()
            )

            val completedCounter = AtomicInteger(0)
            val workingCounter = AtomicInteger(0)
            val semaphore = Semaphore(maxParallelTests)

            val testedProfiles = coroutineScope {
                batchToTest.map { candidate ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val outcome = if (customLatencyProbe != null) {
                                customLatencyProbe(candidate)
                            } else {
                                LatencyTester.testSingleTcpLatency(candidate, timeoutMs = candidateTestTimeoutMs)
                            }
                            val done = completedCounter.incrementAndGet()
                            val isReachable = outcome.latencyMs != null && outcome.latencyMs > 0
                            if (isReachable) {
                                workingCounter.incrementAndGet()
                            }

                            val existingMetrics = cacheStore.getMetrics(candidate.uniqueFingerprintKey())
                            val evaluatedMetrics = AutoServerRanker.evaluateCandidate(
                                profile = candidate.copy(
                                    lastLatencyMs = outcome.latencyMs ?: candidate.lastLatencyMs,
                                    latencyType = outcome.measureType
                                ),
                                existingMetrics = existingMetrics,
                                measuredLatencyMs = outcome.latencyMs ?: candidate.lastLatencyMs
                            )
                            cacheStore.updateMetrics(candidate, evaluatedMetrics)

                            _statusState.value = _statusState.value.copy(
                                testedCount = done,
                                workingCandidateCount = workingCounter.get(),
                                cachedValidatedCount = cacheStore.getCachedValidatedCount(),
                                detailMessageEn = "Tested $done/${batchToTest.size} candidates (${workingCounter.get()} reachable)...",
                                detailMessageFa = "تست $done از ${batchToTest.size} سرور انجام شد (${workingCounter.get()} سرور پاسخگو)...",
                                lastUpdatedMs = System.currentTimeMillis()
                            )

                            candidate.copy(
                                lastLatencyMs = outcome.latencyMs ?: candidate.lastLatencyMs,
                                latencyType = if (outcome.latencyMs != null) outcome.measureType else candidate.latencyType
                            )
                        }
                    }
                }.awaitAll()
            }

            // Stage 3: Intelligent Multi-Factor Server Ranking
            _statusState.value = _statusState.value.copy(
                stage = AutoVpnStage.RANKING_SERVERS,
                detailMessageEn = "Ranking candidates by latency, stability, and network compatibility...",
                detailMessageFa = "در حال رتبه‌بندی هوشمند بر اساس پینگ، پایداری و سازگاری شبکه...",
                lastUpdatedMs = System.currentTimeMillis()
            )

            val rankedCandidates = AutoServerRanker.rankCandidates(
                profiles = testedProfiles,
                metricsMap = cacheStore.getMetricsMap()
            )
            lastRankedCandidates = rankedCandidates

            // Stage 4 & 5: Connect to Best Candidate with Intelligent Automatic Failover
            val candidatesToTry = rankedCandidates.take(maxFailoverAttempts.coerceAtLeast(1))
            var lastError: Throwable? = null

            for ((index, candidate) in candidatesToTry.withIndex()) {
                val attemptNumber = index + 1
                val isFailover = index > 0

                val persistedProfile = onPersistAndSelectCandidate(
                    candidate.profile,
                    candidate.metrics.latencyMs
                )

                _statusState.value = _statusState.value.copy(
                    stage = if (isFailover) AutoVpnStage.FAILOVER_SWITCHING else AutoVpnStage.CONNECTING_BEST,
                    activeCandidate = candidate.copy(profile = persistedProfile),
                    failoverAttempt = index,
                    maxFailoverAttempts = candidatesToTry.size,
                    detailMessageEn = if (isFailover) {
                        "Failover ($attemptNumber/${candidatesToTry.size}): Switching to optimal alternative..."
                    } else {
                        "Connecting to top-ranked optimal server..."
                    },
                    detailMessageFa = if (isFailover) {
                        "جایگزینی خودکار ($attemptNumber از ${candidatesToTry.size}): اتصال به سرور جایگزین..."
                    } else {
                        "در حال اتصال به بهترین سرور منتخب..."
                    },
                    lastUpdatedMs = System.currentTimeMillis()
                )

                val connectResult = onAttemptConnectAndVerify(persistedProfile)
                if (connectResult.isSuccess) {
                    val verifiedLatency = connectResult.getOrNull() ?: candidate.metrics.latencyMs
                    val updatedMetrics = AutoServerRanker.recordConnectionSuccess(
                        profile = persistedProfile,
                        existing = cacheStore.getMetrics(persistedProfile.uniqueFingerprintKey()),
                        verifiedLatencyMs = verifiedLatency
                    )
                    cacheStore.updateMetrics(persistedProfile, updatedMetrics)

                    val connectedCandidate = RankedVpnCandidate(
                        profile = persistedProfile.copy(
                            lastLatencyMs = verifiedLatency ?: persistedProfile.lastLatencyMs,
                            latencyType = if (verifiedLatency != null) LatencyMeasureType.PROXY_HTTP_DELAY else persistedProfile.latencyType
                        ),
                        metrics = updatedMetrics
                    )

                    lastServerSwitchTimestampMs = System.currentTimeMillis()

                    _statusState.value = _statusState.value.copy(
                        stage = AutoVpnStage.CONNECTED,
                        activeCandidate = connectedCandidate,
                        failoverAttempt = index,
                        cachedValidatedCount = cacheStore.getCachedValidatedCount(),
                        isContinuousOptimizationActive = true,
                        detailMessageEn = "Connected automatically • ${connectedCandidate.metrics.qualityGrade.badgeEn} quality",
                        detailMessageFa = "متصل شد • کیفیت ${connectedCandidate.metrics.qualityGrade.badgeFa} (انتخاب هوشمند)",
                        lastUpdatedMs = System.currentTimeMillis()
                    )

                    onLog(
                        "INFO",
                        "AutoFreeVPN",
                        "Connected to optimal server '${persistedProfile.remarks}' (Score: ${"%.1f".format(updatedMetrics.compositeRankScore)}, Latency: ${verifiedLatency ?: "--"} ms)"
                    )
                    return Result.success(connectedCandidate)
                } else {
                    val err = connectResult.exceptionOrNull()
                    lastError = err
                    onLog(
                        "WARN",
                        "AutoFreeVPN",
                        "Candidate '${persistedProfile.remarks}' failed (${err?.message ?: "Unreachable"}). Stopping safely and failing over..."
                    )
                    // Safely stop failed session before trying next candidate
                    runCatching { onStopFailedSession() }

                    val failedMetrics = AutoServerRanker.recordConnectionFailure(
                        profile = persistedProfile,
                        existing = cacheStore.getMetrics(persistedProfile.uniqueFingerprintKey())
                    )
                    cacheStore.updateMetrics(persistedProfile, failedMetrics)

                    if (index < candidatesToTry.lastIndex && baseFailoverBackoffMs > 0L) {
                        val backoffMs = baseFailoverBackoffMs * (1L shl index.coerceAtMost(3))
                        delay(backoffMs)
                    }
                }
            }

            // All candidates in the retry budget failed
            _statusState.value = _statusState.value.copy(
                stage = AutoVpnStage.EXHAUSTED_ERROR,
                isContinuousOptimizationActive = false,
                detailMessageEn = "Could not establish a verified tunnel after trying ${candidatesToTry.size} servers.",
                detailMessageFa = "پس از بررسی ${candidatesToTry.size} سرور کاندید، اتصال پایدار برقرار نشد.",
                lastUpdatedMs = System.currentTimeMillis()
            )
            return Result.failure(
                lastError ?: IllegalStateException("All ${candidatesToTry.size} candidate servers failed verification")
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            _statusState.value = _statusState.value.copy(
                stage = AutoVpnStage.EXHAUSTED_ERROR,
                isContinuousOptimizationActive = false,
                detailMessageEn = t.message ?: "Auto-connect error",
                detailMessageFa = "خطا در سیستم اتصال خودکار: ${t.message ?: ""}",
                lastUpdatedMs = System.currentTimeMillis()
            )
            return Result.failure(t)
        }
    }

    /**
     * Starts continuous background health, quality optimization, and periodic subscription
     * refresh while VPN is connected.
     * Enforces anti-flap protection ([minServerSwitchIntervalMs]) against rapid server switching.
     */
    fun startContinuousOptimization(
        getConnectionState: () -> VpnConnectionState,
        getActiveTrafficBps: () -> Long,
        onHealthCheckActiveTunnel: suspend () -> Result<Long>,
        onTriggerFailoverReconnect: suspend () -> Unit,
        intervalMs: Long = 45_000L
    ) {
        continuousOptimizationJob?.cancel()
        continuousOptimizationJob = managerScope.launch {
            while (isActive) {
                delay(intervalMs)
                if (getConnectionState() != VpnConnectionState.CONNECTED) continue

                // Periodic background subscription refresh if interval has elapsed
                if (isSubscriptionRefreshDue()) {
                    runCatching { refreshSubscriptionCatalog() }
                }

                val health = runCatching { onHealthCheckActiveTunnel() }.getOrElse { Result.failure(it) }
                val activeCandidate = _statusState.value.activeCandidate
                val nowMs = System.currentTimeMillis()

                if (health.isFailure) {
                    // Active server degraded/dropped -> trigger intelligent failover
                    if (activeCandidate != null) {
                        val failedMetrics = AutoServerRanker.recordConnectionFailure(
                            profile = activeCandidate.profile,
                            existing = cacheStore.getMetrics(activeCandidate.profile.uniqueFingerprintKey())
                        )
                        cacheStore.updateMetrics(activeCandidate.profile, failedMetrics)
                    }
                    lastServerSwitchTimestampMs = nowMs
                    _statusState.value = _statusState.value.copy(
                        stage = AutoVpnStage.FAILOVER_SWITCHING,
                        detailMessageEn = "Connection degraded • Automatically switching to next best server...",
                        detailMessageFa = "افت کیفیت سرور فعلی • در حال تعویض خودکار به سرور بهتر...",
                        lastUpdatedMs = nowMs
                    )
                    onTriggerFailoverReconnect()
                } else {
                    val freshLatency = health.getOrNull()
                    if (activeCandidate != null && freshLatency != null) {
                        val updatedMetrics = AutoServerRanker.recordConnectionSuccess(
                            profile = activeCandidate.profile,
                            existing = cacheStore.getMetrics(activeCandidate.profile.uniqueFingerprintKey()),
                            verifiedLatencyMs = freshLatency
                        )
                        cacheStore.updateMetrics(activeCandidate.profile, updatedMetrics)
                        val refreshedCurrent = activeCandidate.copy(
                            profile = activeCandidate.profile.copy(
                                lastLatencyMs = freshLatency,
                                latencyType = LatencyMeasureType.PROXY_HTTP_DELAY
                            ),
                            metrics = updatedMetrics
                        )
                        _statusState.value = _statusState.value.copy(
                            stage = AutoVpnStage.CONNECTED,
                            activeCandidate = refreshedCurrent,
                            lastUpdatedMs = nowMs
                        )

                        // Protect against rapid server switching using both time cooldown and score hysteresis
                        val switchCooldownElapsed = (nowMs - lastServerSwitchTimestampMs) >= minServerSwitchIntervalMs
                        val bestChallenger = lastRankedCandidates.firstOrNull {
                            it.profile.uniqueFingerprintKey() != refreshedCurrent.profile.uniqueFingerprintKey()
                        }
                        if (switchCooldownElapsed &&
                            bestChallenger != null &&
                            AutoServerRanker.shouldSwitchToBetterCandidate(
                                current = refreshedCurrent,
                                challenger = bestChallenger,
                                currentTrafficSpeedBps = getActiveTrafficBps()
                            )
                        ) {
                            lastServerSwitchTimestampMs = nowMs
                            _statusState.value = _statusState.value.copy(
                                stage = AutoVpnStage.OPTIMIZING,
                                detailMessageEn = "Switching to a substantially faster server...",
                                detailMessageFa = "ارتقای خودکار اتصال به سرور سریع‌تر...",
                                lastUpdatedMs = nowMs
                            )
                            onTriggerFailoverReconnect()
                        }
                    }
                }
            }
        }
    }

    fun stopContinuousOptimizationAndResetToIdle() {
        continuousOptimizationJob?.cancel()
        continuousOptimizationJob = null
        _statusState.value = _statusState.value.copy(
            stage = AutoVpnStage.IDLE,
            isContinuousOptimizationActive = false,
            failoverAttempt = 0,
            cachedValidatedCount = cacheStore.getCachedValidatedCount(),
            detailMessageEn = "One tap automatically downloads, tests, ranks, and connects.",
            detailMessageFa = "با یک لمس، بهترین سرور رایگان به صورت خودکار دریافت، تست و متصل می‌شود.",
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    fun resetState() {
        stopContinuousOptimizationAndResetToIdle()
        cacheStore.clearAll()
        lastRankedCandidates = emptyList()
        lastServerSwitchTimestampMs = 0L
        _statusState.value = AutoVpnStatusSnapshot(autoRefreshIntervalMs = autoRefreshIntervalMs)
    }

    fun syncWithActiveProfile(
        profile: ServerProfile?,
        connectionState: VpnConnectionState,
        verifiedLatencyMs: Long? = null,
        downloadBps: Long? = null
    ) {
        if (profile == null) return
        val existing = cacheStore.getMetrics(profile.uniqueFingerprintKey())
        val metrics = AutoServerRanker.evaluateCandidate(
            profile = profile,
            existingMetrics = existing,
            measuredLatencyMs = verifiedLatencyMs ?: profile.lastLatencyMs ?: existing?.latencyMs,
            proxyVerified = connectionState == VpnConnectionState.CONNECTED,
            measuredDownloadBps = downloadBps ?: existing?.downloadBps
        )
        cacheStore.updateMetrics(profile, metrics)
        val ranked = RankedVpnCandidate(profile = profile, metrics = metrics)
        val stage = when (connectionState) {
            VpnConnectionState.CONNECTED -> AutoVpnStage.CONNECTED
            VpnConnectionState.RECONNECTING -> AutoVpnStage.FAILOVER_SWITCHING
            VpnConnectionState.VERIFYING -> AutoVpnStage.VERIFYING_TUNNEL
            VpnConnectionState.CONNECTING, VpnConnectionState.STARTING_ENGINE, VpnConnectionState.PREPARING ->
                if (_statusState.value.stage in setOf(
                        AutoVpnStage.DISCOVERING_CONFIGS,
                        AutoVpnStage.TESTING_CANDIDATES,
                        AutoVpnStage.RANKING_SERVERS,
                        AutoVpnStage.FAILOVER_SWITCHING
                    )
                ) {
                    _statusState.value.stage
                } else {
                    AutoVpnStage.CONNECTING_BEST
                }
            VpnConnectionState.ERROR -> AutoVpnStage.EXHAUSTED_ERROR
            else -> AutoVpnStage.IDLE
        }
        _statusState.value = _statusState.value.copy(
            stage = stage,
            activeCandidate = ranked,
            cachedValidatedCount = cacheStore.getCachedValidatedCount(),
            isContinuousOptimizationActive = connectionState == VpnConnectionState.CONNECTED,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    companion object {
        const val DEFAULT_AUTO_REFRESH_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
        const val MIN_AUTO_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        const val MAX_AUTO_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
        const val DEFAULT_MIN_SWITCH_INTERVAL_MS = 20_000L // 20 seconds anti-flap guard
    }
}
