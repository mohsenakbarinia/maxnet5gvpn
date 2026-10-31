package com.example.core.autovpn

import android.content.Context
import com.example.domain.model.ServerProfile
import com.example.domain.model.VpnProtocol
import com.example.parser.ConfigParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent & in-memory local cache of recently validated VPN configurations and their quality metrics.
 */
class AutoVpnCacheStore(context: Context? = null) {

    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val metricsCache = ConcurrentHashMap<String, CandidateQualityMetrics>()
    private val validatedShareLinksCache = ConcurrentHashMap<String, String>()

    init {
        loadFromPrefs()
    }

    fun getMetricsMap(): Map<String, CandidateQualityMetrics> = metricsCache.toMap()

    fun getMetrics(fingerprintKey: String): CandidateQualityMetrics? = metricsCache[fingerprintKey]

    fun updateMetrics(profile: ServerProfile, metrics: CandidateQualityMetrics) {
        val key = profile.uniqueFingerprintKey()
        metricsCache[key] = metrics
        if (metrics.latencyMs != null && metrics.latencyMs > 0 && !metrics.isOnCooldown()) {
            val uri = ConfigParser.toShareUri(profile)
            if (uri.isNotBlank()) {
                validatedShareLinksCache[key] = uri
            }
        }
        persistToPrefs()
    }

    fun getCachedValidatedProfiles(): List<ServerProfile> {
        if (validatedShareLinksCache.isEmpty()) return emptyList()
        val rawBlock = validatedShareLinksCache.values.joinToString("\n")
        val parsed = ConfigParser.parseBatch(rawBlock, defaultGroup = AUTO_CACHE_GROUP).profiles
        return parsed.map { profile ->
            val key = profile.uniqueFingerprintKey()
            val m = metricsCache[key]
            if (m?.latencyMs != null) {
                profile.copy(lastLatencyMs = m.latencyMs)
            } else {
                profile
            }
        }
    }

    fun getCachedValidatedCount(): Int = validatedShareLinksCache.size

    fun clearAll() {
        metricsCache.clear()
        validatedShareLinksCache.clear()
        prefs?.edit()?.clear()?.apply()
    }

    fun clearCooldowns() {
        for ((key, value) in metricsCache.entries) {
            if (value.cooldownUntilMs > 0L || value.consecutiveFailures > 0) {
                metricsCache[key] = value.copy(cooldownUntilMs = 0L, consecutiveFailures = 0)
            }
        }
        persistToPrefs()
    }

    private fun loadFromPrefs() {
        val sp = prefs ?: return
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
            sp.edit().putString(KEY_METRICS_JSON, arr.toString()).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "maxnet_auto_free_vpn_cache"
        private const val KEY_METRICS_JSON = "cached_candidate_metrics_v1"
        private const val MAX_CACHED_CANDIDATES = 120
        const val AUTO_CACHE_GROUP = "AutoFreeVPN"
    }
}
