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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val MAX_SUBSCRIPTION_BYTES = 5L * 1024L * 1024L // 5 MB limit
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
        val id = dao.insertProfile(ProfileEntity.fromDomain(profile))
        appendLog("INFO", "ProfileManager", "Saved profile '${profile.remarks}' (${profile.protocol.displayName})", listOf(profile.credential))
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

    suspend fun getAllProfilesSnapshot(): List<ServerProfile> = withContext(Dispatchers.IO) {
        dao.getAllProfilesSnapshot().map { it.toDomain() }
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

    // Subscription Manager
    suspend fun addSubscription(title: String, url: String, autoUpdate: Boolean): Result<SubscriptionItem> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(
                IllegalArgumentException("Subscription URL must use HTTPS for security.")
            )
        }
        val item = SubscriptionItem(
            title = title.trim().ifBlank { "Subscription" },
            url = cleanUrl,
            enabledAutoUpdate = autoUpdate
        )
        val id = dao.insertSubscription(SubscriptionEntity.fromDomain(item))
        val saved = item.copy(id = id)
        refreshSubscription(saved)
    }

    suspend fun deleteSubscription(subscriptionId: Long) = withContext(Dispatchers.IO) {
        dao.deleteProfilesBySubscriptionId(subscriptionId)
        dao.deleteSubscriptionById(subscriptionId)
        appendLog("INFO", "Subscription", "Deleted subscription ID=$subscriptionId and its associated profiles")
    }

    /**
     * Fetches and updates a subscription over HTTPS with:
     * - Retry policy (up to 2 attempts)
     * - Strict 5 MB response size limit
     * - HTML response rejection
     * - Header metadata parsing (Subscription-Userinfo, Profile-Update-Interval, Profile-Title, Support-Url, Announce)
     * - Preservation of manually added profiles & active profile selection
     */
    suspend fun refreshSubscription(sub: SubscriptionItem): Result<SubscriptionItem> = withContext(Dispatchers.IO) {
        if (!sub.url.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Only HTTPS subscription URLs are permitted."))
        }

        var lastException: Exception? = null
        for (attempt in 1..2) {
            try {
                val request = Request.Builder()
                    .url(sub.url)
                    .header("User-Agent", "MAXNET5G-VPN/1.0")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}: ${response.message}")
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                    if (contentType.contains("text/html", ignoreCase = true)) {
                        throw IllegalStateException("Rejected HTML response from subscription endpoint")
                    }

                    val bodySource = response.body?.source()
                        ?: throw IllegalStateException("Empty subscription response body")
                    bodySource.request(MAX_SUBSCRIPTION_BYTES + 1L)
                    if (bodySource.buffer.size > MAX_SUBSCRIPTION_BYTES) {
                        throw IllegalStateException("Subscription response exceeds maximum 5 MB limit")
                    }
                    val bodyText = bodySource.readUtf8()

                    // Parse headers
                    val userInfoHeader = response.header("Subscription-Userinfo")
                        ?: response.header("subscription-userinfo")
                    val parsedUserInfo = parseSubscriptionUserInfo(userInfoHeader)
                    val intervalHeader = (response.header("Profile-Update-Interval")
                        ?: response.header("profile-update-interval"))?.toIntOrNull()
                    val titleHeader = response.header("Profile-Title")
                    val supportUrlHeader = response.header("Support-Url").orEmpty()
                    val webPageUrlHeader = response.header("Profile-Web-Page-Url").orEmpty()
                    val announceHeader = response.header("Announce").orEmpty()

                    // Keep track of previously selected profile fingerprint so selection survives refresh
                    val allBefore = dao.getAllProfilesSnapshot().map { it.toDomain() }
                    val manualFingerprints = allBefore
                        .filter { it.subscriptionId != sub.id }
                        .map { it.uniqueFingerprintKey() }
                        .toSet()

                    val batch = ConfigParser.parseBatch(
                        rawInput = bodyText,
                        defaultGroup = sub.title,
                        subscriptionId = sub.id,
                        existingFingerprintKeys = manualFingerprints
                    )

                    if (batch.profiles.isEmpty()) {
                        throw IllegalStateException(
                            batch.failures.firstOrNull()?.reason ?: "No valid server configurations found in subscription"
                        )
                    }

                    // Replace only this subscription's profiles; never touch manual profiles
                    dao.deleteProfilesBySubscriptionId(sub.id)
                    val newEntities = batch.profiles.mapIndexed { idx, p ->
                        ProfileEntity.fromDomain(
                            p.copy(
                                subscriptionId = sub.id,
                                groupName = sub.title,
                                sortOrder = idx
                            )
                        )
                    }
                    dao.insertProfiles(newEntities)

                    val updatedSub = sub.copy(
                        title = if (!titleHeader.isNullOrBlank() && sub.title == "Subscription") titleHeader else sub.title,
                        updateIntervalHours = intervalHeader ?: sub.updateIntervalHours,
                        lastUpdatedAt = System.currentTimeMillis(),
                        lastStatus = "OK (${batch.profiles.size} servers)",
                        uploadBytes = parsedUserInfo["upload"],
                        downloadBytes = parsedUserInfo["download"],
                        totalBytes = parsedUserInfo["total"],
                        expireTimestampSec = parsedUserInfo["expire"],
                        supportUrl = supportUrlHeader.ifBlank { sub.supportUrl },
                        webPageUrl = webPageUrlHeader.ifBlank { sub.webPageUrl },
                        announcement = announceHeader.ifBlank { sub.announcement },
                        profileCount = batch.profiles.size
                    )
                    dao.updateSubscription(SubscriptionEntity.fromDomain(updatedSub))
                    appendLog("INFO", "Subscription", "Updated '${updatedSub.title}' with ${batch.profiles.size} servers", listOf(sub.url))
                    return@withContext Result.success(updatedSub)
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt == 1) delay(600L)
            }
        }

        val errMsg = lastException?.message ?: "Subscription update failed"
        val failedSub = sub.copy(
            lastUpdatedAt = System.currentTimeMillis(),
            lastStatus = "Error: $errMsg"
        )
        dao.updateSubscription(SubscriptionEntity.fromDomain(failedSub))
        appendLog("ERROR", "Subscription", "Subscription '${sub.title}' failed: $errMsg", listOf(sub.url))
        Result.failure(lastException ?: IllegalStateException(errMsg))
    }

    private fun parseSubscriptionUserInfo(header: String?): Map<String, Long> {
        if (header.isNullOrBlank()) return emptyMap()
        val map = mutableMapOf<String, Long>()
        header.split(";").forEach { part ->
            val kv = part.trim().split("=")
            if (kv.size == 2) {
                val k = kv[0].trim().lowercase()
                val v = kv[1].trim().toLongOrNull()
                if (v != null) map[k] = v
            }
        }
        return map
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
        statusNote: String
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
                statusNote = statusNote
            )
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
