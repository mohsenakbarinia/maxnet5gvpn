package com.example.core.autovpn

import android.content.Context
import com.example.domain.model.ServerProfile
import com.example.domain.model.VpnProtocol
import com.example.parser.ConfigParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated persistent & in-memory local cache of verified Free VPN configurations,
 * subscription sync metadata, and per-server quality metrics.
 *
 * - Strictly isolated from the Personal VPN Room database (`MaxNetDao`).
 * - Performs atomic updates: a failed or empty refresh never erases the previous valid cache.
 */
class AutoVpnCacheStore(context: Context? = null) {

    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val metricsCache = ConcurrentHashMap<String, CandidateQualityMetrics>()
    private val validatedShareLinksCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var lastManifest: FreeConfigManifest? = null

    @Volatile
    private var memoizedProfiles: List<ServerProfile>? = null

    init {
        loadFromPrefs()
    }

    fun getLastManifest(): FreeConfigManifest? = lastManifest

    fun getLastRefreshTimestampMs(): Long = lastManifest?.fetchedAtMs ?: 0L

    fun getMetricsMap(): Map<String, CandidateQualityMetrics> = metricsCache.toMap()

    fun getMetrics(fingerprintKey: String): CandidateQualityMetrics? = metricsCache[fingerprintKey]

    /**
     * Atomically updates the last-known-good Free VPN configuration cache.
     * If [profiles] is empty, the previous valid cache is preserved untouched.
     */
    @Synchronized
    fun saveVerifiedRemoteCatalog(
        manifest: FreeConfigManifest,
        profiles: List<ServerProfile>
    ) {
        if (profiles.isEmpty()) {
            // Never erase the previous valid cache when a refresh yields 0 valid configurations
            return
        }
        val newLinks = LinkedHashMap<String, String>()
        for (profile in profiles.take(MAX_CACHED_CANDIDATES)) {
            val key = profile.uniqueFingerprintKey()
            val uri = ConfigParser.toShareUri(profile)
            if (uri.isNotBlank()) {
                newLinks[key] = uri
            }
        }
        if (newLinks.isEmpty()) {
            return
        }

        // Atomic swap of validated share links while retaining any existing valid metrics
        validatedShareLinksCache.clear()
        validatedShareLinksCache.putAll(newLinks)
        lastManifest = manifest.copy(
            configCount = newLinks.size,
            fetchedAtMs = if (manifest.fetchedAtMs > 0L) manifest.fetchedAtMs else System.currentTimeMillis()
        )
        memoizedProfiles = null
        persistToPrefs()
    }

    /**
     * Updates the cached manifest's `fetchedAtMs`, `etag`, and `lastModifiedHeader` when a conditional
     * HTTP request returns `304 Not Modified`, preserving all cached configurations.
     */
    @Synchronized
    fun markManifestFreshNotModified(
        nowMs: Long = System.currentTimeMillis(),
        etag: String = "",
        lastModifiedHeader: String = ""
    ) {
        val current = lastManifest ?: return
        lastManifest = current.copy(
            fetchedAtMs = nowMs,
            etag = etag.ifBlank { current.etag },
            lastModifiedHeader = lastModifiedHeader.ifBlank { current.lastModifiedHeader }
        )
        persistToPrefs()
    }

    @Synchronized
    fun updateMetrics(profile: ServerProfile, metrics: CandidateQualityMetrics) {
        val key = profile.uniqueFingerprintKey()
        metricsCache[key] = metrics
        if (metrics.latencyMs != null && metrics.latencyMs > 0 && !metrics.isOnCooldown()) {
            val uri = ConfigParser.toShareUri(profile)
            if (uri.isNotBlank()) {
                validatedShareLinksCache[key] = uri
            }
        }
        memoizedProfiles = null
        persistToPrefs()
    }

    fun getCachedValidatedProfiles(): List<ServerProfile> {
        memoizedProfiles?.let { return it }
        if (validatedShareLinksCache.isEmpty()) return emptyList()
        val rawBlock = validatedShareLinksCache.values.joinToString("\n")
        val parsed = ConfigParser.parseBatch(rawBlock, defaultGroup = AUTO_CACHE_GROUP).profiles
        val enriched = parsed.mapIndexed { idx, profile ->
            val key = profile.uniqueFingerprintKey()
            val m = metricsCache[key]
            val negativeEphemeralId = -(idx + 1000L)
            profile.copy(
                id = if (profile.id == 0L) negativeEphemeralId else profile.id,
                lastLatencyMs = m?.latencyMs ?: profile.lastLatencyMs,
                tcpConnectMs = m?.tcpConnectMs ?: profile.tcpConnectMs,
                latencyType = if (m?.proxyVerified == true && m.latencyMs != null) {
                    com.example.domain.model.LatencyMeasureType.PROXY_HTTP_DELAY
                } else {
                    profile.latencyType
                },
                lastTestedAtMs = m?.lastValidatedAtMs?.takeIf { it > 0L } ?: profile.lastTestedAtMs
            )
        }
        memoizedProfiles = enriched
        return enriched
    }

    fun getCachedValidatedCount(): Int = validatedShareLinksCache.size

    @Synchronized
    fun clearAll() {
        metricsCache.clear()
        validatedShareLinksCache.clear()
        lastManifest = null
        memoizedProfiles = null
        prefs?.edit()?.clear()?.apply()
    }

    @Synchronized
    fun clearCooldowns() {
        for ((key, value) in metricsCache.entries) {
            if (value.cooldownUntilMs > 0L || value.consecutiveFailures > 0) {
                metricsCache[key] = value.copy(cooldownUntilMs = 0L, consecutiveFailures = 0)
            }
        }
        memoizedProfiles = null
        persistToPrefs()
    }

    private fun loadFromPrefs() {
        val sp = prefs ?: return
        runCatching {
            sp.getString(KEY_MANIFEST_JSON, null)?.let { manifestRaw ->
                val mObj = JSONObject(manifestRaw)
                lastManifest = FreeConfigManifest(
                    schemaVersion = mObj.optInt("schema_version", 1),
                    version = mObj.optInt("version", 1),
                    updatedAt = mObj.optString("updated_at", ""),
                    configCount = mObj.optInt("config_count", 0),
                    sha256 = mObj.optString("sha256", ""),
                    repository = mObj.optString("repository", "Cloudflare Workers Subscription"),
                    configsPath = mObj.optString("configs_path", "sub/raw?app=xray"),
                    signature = mObj.optString("signature", ""),
                    detectedFormat = mObj.optString("detected_format", "BASE64_STANDARD"),
                    fetchedAtMs = mObj.optLong("fetched_at_ms", 0L),
                    etag = mObj.optString("etag", ""),
                    lastModifiedHeader = mObj.optString("last_modified_header", "")
                )
            }
        }
        runCatching {
            sp.getString(KEY_CATALOG_URIS_JSON, null)?.let { catalogRaw ->
                val arr = JSONArray(catalogRaw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val key = obj.optString("key")
                    val uri = obj.optString("uri")
                    if (key.isNotBlank() && uri.isNotBlank()) {
                        validatedShareLinksCache[key] = uri
                    }
                }
            }
        }
        runCatching {
            val rawJson = sp.getString(KEY_METRICS_JSON, null) ?: return
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val key = obj.optString("key")
                if (key.isBlank()) continue
                val protoName = obj.optString("protocol", VpnProtocol.VLESS.name)
                val proto = runCatching { VpnProtocol.valueOf(protoName) }.getOrDefault(VpnProtocol.VLESS)
                val gradeName = obj.optString("grade", AutoQualityGrade.UNVERIFIED.name)
                val grade = runCatching { AutoQualityGrade.valueOf(gradeName) }.getOrDefault(AutoQualityGrade.UNVERIFIED)
                val latency = if (obj.has("latencyMs") && !obj.isNull("latencyMs")) obj.optLong("latencyMs") else null
                val tcpConnect = if (obj.has("tcpConnectMs") && !obj.isNull("tcpConnectMs")) obj.optLong("tcpConnectMs") else null
                val downBps = if (obj.has("downloadBps") && !obj.isNull("downloadBps")) obj.optLong("downloadBps") else null
                val shareUri = obj.optString("shareUri", "")
                if (shareUri.isNotBlank()) {
                    validatedShareLinksCache[key] = shareUri
                }
                metricsCache[key] = CandidateQualityMetrics(
                    fingerprintKey = key,
                    protocol = proto,
                    isReality = obj.optBoolean("isReality", false),
                    latencyMs = latency,
                    tcpConnectMs = tcpConnect,
                    proxyVerified = obj.optBoolean("proxyVerified", false),
                    downloadBps = downBps,
                    successCount = obj.optInt("successCount", 0),
                    failureCount = obj.optInt("failureCount", 0),
                    consecutiveFailures = obj.optInt("consecutiveFailures", 0),
                    cooldownUntilMs = obj.optLong("cooldownUntilMs", 0L),
                    lastValidatedAtMs = obj.optLong("lastValidatedAtMs", 0L),
                    stabilityRatio = obj.optDouble("stabilityRatio", 1.0),
                    networkCompatibilityScore = obj.optDouble("networkCompatibilityScore", 0.8),
                    compositeRankScore = obj.optDouble("compositeRankScore", 0.0),
                    qualityGrade = grade
                )
            }
        }
    }

    private fun persistToPrefs() {
        val sp = prefs ?: return
        runCatching {
            val editor = sp.edit()
            lastManifest?.let { m ->
                val mObj = JSONObject().apply {
                    put("schema_version", m.schemaVersion)
                    put("version", m.version)
                    put("updated_at", m.updatedAt)
                    put("config_count", m.configCount)
                    put("sha256", m.sha256)
                    put("repository", m.repository)
                    put("configs_path", m.configsPath)
                    put("signature", m.signature)
                    put("detected_format", m.detectedFormat)
                    put("fetched_at_ms", m.fetchedAtMs)
                    put("etag", m.etag)
                    put("last_modified_header", m.lastModifiedHeader)
                }
                editor.putString(KEY_MANIFEST_JSON, mObj.toString())
            }
            val catalogArr = JSONArray()
            for ((key, uri) in validatedShareLinksCache.entries.take(MAX_CACHED_CANDIDATES)) {
                catalogArr.put(
                    JSONObject().apply {
                        put("key", key)
                        put("uri", uri)
                    }
                )
            }
            editor.putString(KEY_CATALOG_URIS_JSON, catalogArr.toString())

            val entries = metricsCache.values
                .sortedByDescending { it.lastValidatedAtMs }
                .take(MAX_CACHED_CANDIDATES)
            val arr = JSONArray()
            for (m in entries) {
                val obj = JSONObject().apply {
                    put("key", m.fingerprintKey)
                    put("protocol", m.protocol.name)
                    put("isReality", m.isReality)
                    if (m.latencyMs != null) put("latencyMs", m.latencyMs)
                    if (m.tcpConnectMs != null) put("tcpConnectMs", m.tcpConnectMs)
                    put("proxyVerified", m.proxyVerified)
                    if (m.downloadBps != null) put("downloadBps", m.downloadBps)
                    put("successCount", m.successCount)
                    put("failureCount", m.failureCount)
                    put("consecutiveFailures", m.consecutiveFailures)
                    put("cooldownUntilMs", m.cooldownUntilMs)
                    put("lastValidatedAtMs", m.lastValidatedAtMs)
                    put("stabilityRatio", m.stabilityRatio)
                    put("networkCompatibilityScore", m.networkCompatibilityScore)
                    put("compositeRankScore", m.compositeRankScore)
                    put("grade", m.qualityGrade.name)
                    validatedShareLinksCache[m.fingerprintKey]?.let { put("shareUri", it) }
                }
                arr.put(obj)
            }
            editor.putString(KEY_METRICS_JSON, arr.toString()).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "maxnet_auto_free_vpn_cache"
        private const val KEY_METRICS_JSON = "cached_candidate_metrics_v1"
        private const val KEY_CATALOG_URIS_JSON = "cached_catalog_uris_v1"
        private const val KEY_MANIFEST_JSON = "cached_manifest_v1"
        private const val MAX_CACHED_CANDIDATES = 250
        const val AUTO_CACHE_GROUP = "AutoFreeVPN"
    }
}
