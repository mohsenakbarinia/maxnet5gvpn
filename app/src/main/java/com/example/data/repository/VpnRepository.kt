package com.example.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.data.local.ConnectionHistoryEntity
import com.example.data.local.MaxNetDao
import com.example.data.local.ProfileEntity
import com.example.data.local.ProfileGroupEntity
import com.example.data.local.RoutingRuleEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.preferences.SettingsDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.BrandingConfig
import com.example.domain.model.ConnectionHistoryItem
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.RedactedLogEntry
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.ServerProfile
import com.example.domain.model.SubscriptionItem
import com.example.domain.model.UpdateManifest
import com.example.parser.BatchParseResult
import com.example.parser.ConfigParser
import com.example.security.LogRedactor
import com.example.data.subscription.SubscriptionRepository
import com.example.domain.model.SubscriptionFormat
import com.example.domain.model.SubscriptionImportReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class VpnRepository(
    private val context: Context,
    private val dao: MaxNetDao,
    private val settingsDataStore: SettingsDataStore,
    customHttpClient: OkHttpClient? = null
) {
    private val httpClient: OkHttpClient = customHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val subscriptionRepository = SubscriptionRepository(
        dao = dao,
        baseHttpClient = customHttpClient,
        onLog = { level, tag, rawMessage, secrets ->
            appendLog(level, tag, rawMessage, secrets)
        }
    )

    private val _logs = MutableStateFlow<List<RedactedLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<RedactedLogEntry>> = _logs.asStateFlow()

    val profilesFlow: Flow<List<ServerProfile>> = dao.observeAllProfiles().map { list ->
        list.map { it.toDomain() }
    }

    val subscriptionsFlow: Flow<List<SubscriptionItem>> = dao.observeAllSubscriptions().map { list ->
        list.map { it.toDomain() }
    }

    val groupsFlow: Flow<List<String>> = dao.observeAllGroups().map { list ->
        (listOf("Default") + list.map { it.name }).distinct()
    }

    val routingRulesFlow: Flow<List<RoutingRuleItem>> = dao.observeRoutingRules().map { list ->
        list.map { it.toDomain() }
    }

    val historyFlow: Flow<List<ConnectionHistoryItem>> = dao.observeConnectionHistory().map { list ->
        list.map { it.toDomain() }
    }

    val settingsFlow: Flow<AppSettings> = settingsDataStore.settingsFlow

    fun appendLog(level: String, tag: String, rawMessage: String, extraSecrets: Collection<String> = emptyList()) {
        val redacted = LogRedactor.redact(rawMessage, extraSecrets)
        val entry = RedactedLogEntry(level = level, tag = tag, message = redacted)
        _logs.value = (_logs.value + entry).takeLast(300)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // Profile Management
    suspend fun saveProfile(profile: ServerProfile): Long = withContext(Dispatchers.IO) {
        if (profile.groupName.isNotBlank() && profile.groupName != "Default") {
            dao.insertGroup(ProfileGroupEntity(name = profile.groupName.trim()))
        }
        val effectiveProfile = if (profile.id > 0L) {
            val existing = dao.getProfileById(profile.id)?.toDomain()
            val customizedRemarks = existing != null &&
                (existing.userCustomizedRemarks || (existing.subscriptionId != null && existing.remarks != profile.remarks))
            profile.copy(userCustomizedRemarks = customizedRemarks)
        } else {
            profile
        }
        val id = dao.insertProfile(ProfileEntity.fromDomain(effectiveProfile))
        appendLog("INFO", "ProfileManager", "Saved profile '${effectiveProfile.remarks}' (${effectiveProfile.protocol.displayName})", listOf(effectiveProfile.credential))
        id
    }

    suspend fun duplicateProfile(profile: ServerProfile): Long = withContext(Dispatchers.IO) {
        val copy = profile.copy(
            id = 0L,
            remarks = "${profile.remarks} (Copy)",
            createdAt = System.currentTimeMillis()
        )
        saveProfile(copy)
    }

    suspend fun deleteProfile(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteProfileById(id)
        appendLog("INFO", "ProfileManager", "Deleted profile ID=$id")
    }

    suspend fun deleteProfilesBulk(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        dao.deleteProfilesByIds(ids)
        appendLog("INFO", "ProfileManager", "Bulk deleted ${ids.size} profiles")
    }

    suspend fun toggleFavorite(profile: ServerProfile) = withContext(Dispatchers.IO) {
        dao.updateProfile(ProfileEntity.fromDomain(profile.copy(isFavorite = !profile.isFavorite)))
    }

    suspend fun togglePinned(profile: ServerProfile) = withContext(Dispatchers.IO) {
        dao.updateProfile(ProfileEntity.fromDomain(profile.copy(isPinned = !profile.isPinned)))
    }

    suspend fun updateProfileLatency(id: Long, latencyMs: Long?, measureType: LatencyMeasureType) = withContext(Dispatchers.IO) {
        dao.updateProfileLatency(id, latencyMs, measureType.name)
    }

    suspend fun updateProfileLatencyAndDiagnostics(
        id: Long,
        latencyMs: Long?,
        tcpConnectMs: Long?,
        measureType: LatencyMeasureType,
        testedAtMs: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        dao.updateProfileLatencyAndDiagnostics(
            id = id,
            latencyMs = latencyMs,
            tcpConnectMs = tcpConnectMs,
            measureType = measureType.name,
            testedAtMs = testedAtMs
        )
    }

    suspend fun getAllProfilesSnapshot(): List<ServerProfile> = withContext(Dispatchers.IO) {
        dao.getAllProfilesSnapshot().map { it.toDomain() }
    }

    suspend fun getAllSubscriptionsSnapshot(): List<SubscriptionItem> = withContext(Dispatchers.IO) {
        dao.getAllSubscriptionsSnapshot().map { it.toDomain() }
    }

    suspend fun importBatchText(
        rawText: String,
        groupName: String = "Default",
        subscriptionId: Long? = null
    ): BatchParseResult = withContext(Dispatchers.IO) {
        val existingKeys = dao.getAllProfilesSnapshot()
            .map { it.toDomain().uniqueFingerprintKey() }
            .toSet()

        val result = ConfigParser.parseBatch(
            rawInput = rawText,
            defaultGroup = groupName,
            subscriptionId = subscriptionId,
            existingFingerprintKeys = existingKeys
        )

        if (result.profiles.isNotEmpty()) {
            if (groupName.isNotBlank() && groupName != "Default") {
                dao.insertGroup(ProfileGroupEntity(name = groupName))
            }
            val entities = result.profiles.mapIndexed { idx, p ->
                ProfileEntity.fromDomain(p.copy(sortOrder = idx))
            }
            dao.insertProfiles(entities)
        }
        appendLog(
            "INFO",
            "ConfigImporter",
            "Imported ${result.profiles.size} profiles (${result.duplicatesSkipped} duplicates skipped, ${result.failures.size} invalid)"
        )
        result
    }

    suspend fun importBatchTextWithReport(
        rawText: String,
        groupName: String = "Default",
        subscriptionId: Long? = null
    ): Pair<BatchParseResult, SubscriptionImportReport> = withContext(Dispatchers.IO) {
        val res = importBatchText(rawText, groupName, subscriptionId)
        val isOk = res.profiles.isNotEmpty() || res.duplicatesSkipped > 0
        val statusFa = when {
            res.profiles.isNotEmpty() -> "ایمپورت کانفیگ‌ها با موفقیت انجام شد (${res.detectedFormat.labelFa})"
            res.duplicatesSkipped > 0 && res.failures.isEmpty() -> "تمامی کانفیگ‌های دریافت‌شده قبلاً در برنامه ثبت شده‌اند"
            else -> res.failures.firstOrNull()?.reason ?: "هیچ کانفیگ معتبری یافت نشد"
        }
        val statusEn = when {
            res.profiles.isNotEmpty() -> "Imported ${res.profiles.size} configuration(s)"
            res.duplicatesSkipped > 0 && res.failures.isEmpty() -> "All configurations already exist (duplicates skipped)"
            else -> res.failures.firstOrNull()?.reason ?: "No valid configurations found"
        }
        val report = SubscriptionImportReport(
            title = if (groupName.isNotBlank() && groupName != "Default") groupName else "کلیپ‌بورد / ایمپورت مستقیم",
            sourceType = "CLIPBOARD",
            detectedFormat = res.detectedFormat,
            totalReceivedCount = res.totalReceivedCount,
            newImportedCount = res.profiles.size,
            duplicateCount = res.duplicatesSkipped,
            invalidCount = res.failures.size,
            statusMessageFa = statusFa,
            statusMessageEn = statusEn,
            isSuccess = isOk
        )
        res to report
    }

    // Subscription Manager
    suspend fun addSubscription(title: String, url: String, autoUpdate: Boolean): Result<SubscriptionItem> {
        return subscriptionRepository.addOrUpdateSubscription(title, url, autoUpdate).first
    }

    suspend fun addSubscriptionWithReport(
        title: String,
        url: String,
        autoUpdate: Boolean
    ): Pair<Result<SubscriptionItem>, SubscriptionImportReport> {
        return subscriptionRepository.addOrUpdateSubscription(title, url, autoUpdate)
    }

    suspend fun deleteSubscription(subscriptionId: Long) = withContext(Dispatchers.IO) {
        dao.deleteProfilesBySubscriptionId(subscriptionId)
        dao.deleteSubscriptionById(subscriptionId)
        appendLog("INFO", "Subscription", "Deleted subscription ID=$subscriptionId and its associated profiles")
    }

    suspend fun refreshSubscription(sub: SubscriptionItem): Result<SubscriptionItem> {
        return subscriptionRepository.refreshSubscriptionWithReport(sub, isNewCreation = false).first
    }

    suspend fun refreshSubscriptionWithReport(sub: SubscriptionItem): Pair<Result<SubscriptionItem>, SubscriptionImportReport> {
        return subscriptionRepository.refreshSubscriptionWithReport(sub, isNewCreation = false)
    }

    // Routing Rules
    suspend fun addRoutingRule(rule: RoutingRuleItem) = withContext(Dispatchers.IO) {
        dao.insertRoutingRule(RoutingRuleEntity.fromDomain(rule))
    }

    suspend fun toggleRoutingRule(rule: RoutingRuleItem) = withContext(Dispatchers.IO) {
        dao.updateRoutingRule(RoutingRuleEntity.fromDomain(rule.copy(isEnabled = !rule.isEnabled)))
    }

    suspend fun deleteRoutingRule(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteRoutingRuleById(id)
    }

    suspend fun getRoutingRulesSnapshot(): List<RoutingRuleItem> = withContext(Dispatchers.IO) {
        dao.getRoutingRulesSnapshot().map { it.toDomain() }
    }

    // Settings
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsDataStore.updateSettings(transform)
    }

    suspend fun selectProfile(profileId: Long?) {
        settingsDataStore.updateSelectedProfile(profileId)
    }

    // Connection History
    suspend fun recordConnectionSession(
        profileName: String,
        protocol: String,
        engine: String,
        connectedAt: Long,
        disconnectedAt: Long?,
        uploadBytes: Long,
        downloadBytes: Long,
        statusNote: String,
        failoverReason: String? = null,
        activeMode: String = "STABLE",
        averageLatencyMs: Long? = null
    ) = withContext(Dispatchers.IO) {
        dao.insertConnectionHistory(
            ConnectionHistoryEntity(
                profileName = profileName,
                protocol = protocol,
                engine = engine,
                connectedAt = connectedAt,
                disconnectedAt = disconnectedAt,
                uploadBytes = uploadBytes,
                downloadBytes = downloadBytes,
                statusNote = statusNote,
                failoverReason = failoverReason,
                activeMode = activeMode,
                averageLatencyMs = averageLatencyMs
            )
        )
    }

    suspend fun clearConnectionHistory() = withContext(Dispatchers.IO) {
        dao.clearConnectionHistory()
        appendLog("INFO", "Analytics", "Connection history cleared by user")
    }

    suspend fun getRecentConnectionHistory(limit: Int = 50): List<ConnectionHistoryItem> = withContext(Dispatchers.IO) {
        dao.getRecentConnectionHistory(limit).map { it.toDomain() }
    }

    suspend fun updateProfileSmartMetrics(
        id: Long,
        latencyMs: Long?,
        tcpConnectMs: Long?,
        jitterMs: Long?,
        packetLossRate: Float?,
        successRate: Float?,
        healthScore: Int?,
        consecutiveFailures: Int,
        lastFailureMs: Long?,
        measureType: String,
        testedAtMs: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        dao.updateProfileSmartMetrics(
            id = id,
            latencyMs = latencyMs,
            tcpConnectMs = tcpConnectMs,
            jitterMs = jitterMs,
            packetLossRate = packetLossRate,
            successRate = successRate,
            healthScore = healthScore,
            consecutiveFailures = consecutiveFailures,
            lastFailureMs = lastFailureMs,
            measureType = measureType,
            testedAtMs = testedAtMs
        )
    }

    // Installed Apps for Split Tunneling
    suspend fun getInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            packages
                .filter { it.packageName != context.packageName }
                .map { appInfo ->
                    val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                        .getOrDefault(appInfo.packageName)
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    InstalledAppInfo(
                        packageName = appInfo.packageName,
                        appName = label,
                        isSystemApp = isSystem
                    )
                }
                .sortedWith(compareBy<InstalledAppInfo> { it.isSystemApp }.thenBy { it.appName.lowercase() })
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Backup & Restore JSON
    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val profiles = getAllProfilesSnapshot()
        val rules = getRoutingRulesSnapshot()
        val root = JSONObject()
        root.put("app", "MAXNET5G VPN")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val urisArray = JSONArray()
        profiles.forEach { urisArray.put(ConfigParser.toShareUri(it)) }
        root.put("profileUris", urisArray)

        val rulesArray = JSONArray()
        rules.forEach { r ->
            rulesArray.put(JSONObject().apply {
                put("name", r.name)
                put("type", r.type.name)
                put("value", r.value)
                put("outboundTag", r.outboundTag.name)
                put("isEnabled", r.isEnabled)
            })
        }
        root.put("routingRules", rulesArray)
        root.toString(2)
    }

    suspend fun restoreBackupJson(rawJson: String): Result<Int> = withContext(Dispatchers.IO) {
        return@withContext try {
            val root = JSONObject(rawJson)
            val uris = root.optJSONArray("profileUris")
            val sb = StringBuilder()
            if (uris != null) {
                for (i in 0 until uris.length()) {
                    sb.appendLine(uris.optString(i))
                }
            }
            val imported = importBatchText(sb.toString())
            Result.success(imported.profiles.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Branding Configuration Loader
    fun loadBrandingConfig(): BrandingConfig {
        return try {
            val jsonText = context.assets.open("branding.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(jsonText)
            BrandingConfig(
                appName = obj.optString("appName", "MAXNET5G VPN"),
                appId = obj.optString("appId", context.packageName),
                urlScheme = obj.optString("urlScheme", "maxnet5g"),
                primaryColorHex = obj.optString("primaryColorHex", "#00E5FF"),
                accentColorHex = obj.optString("accentColorHex", "#00E676"),
                defaultLanguage = obj.optString("defaultLanguage", "en-US"),
                defaultTheme = obj.optString("defaultTheme", "DARK"),
                supportUrl = obj.optString("supportUrl", "https://maxnet5g.example.com/support"),
                privacyPolicyUrl = obj.optString("privacyPolicyUrl", "https://maxnet5g.example.com/privacy"),
                termsUrl = obj.optString("termsUrl", "https://maxnet5g.example.com/terms"),
                updateManifestUrl = obj.optString("updateManifestUrl", "https://maxnet5g.example.com/updates/manifest.json"),
                lockedMode = obj.optBoolean("lockedMode", false)
            )
        } catch (_: Exception) {
            BrandingConfig()
        }
    }

    // Application Update Checker & SHA-256 Verifier
    suspend fun checkUpdateManifest(manifestUrl: String): Result<UpdateManifest> = withContext(Dispatchers.IO) {
        if (!manifestUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Update manifest URL must use HTTPS."))
        }
        return@withContext try {
            val req = Request.Builder().url(manifestUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Update server returned HTTP ${resp.code}"))
                }
                val body = resp.body?.string() ?: "{}"
                val json = JSONObject(body)
                val manifest = UpdateManifest(
                    latestVersion = json.getString("latestVersion"),
                    minVersion = json.optString("minVersion", "1.0.0"),
                    apkUrl = json.getString("apkUrl"),
                    changelog = json.optString("changelog", ""),
                    sha256 = json.getString("sha256")
                )
                Result.success(manifest)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun verifySha256Checksum(bytes: ByteArray, expectedHex: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val actualHex = digest.joinToString("") { "%02x".format(it) }
        return actualHex.equals(expectedHex.trim(), ignoreCase = true)
    }
}
