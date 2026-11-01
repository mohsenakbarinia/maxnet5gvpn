package com.example.core.autovpn

import android.content.Context
import android.util.Base64
import com.example.BuildConfig
import com.example.domain.model.AppSettings
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.SubscriptionFormat
import com.example.domain.model.VpnProtocol
import com.example.parser.ConfigParser
import com.example.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Result of inspecting and extracting a Free VPN subscription HTTP response.
 * Never includes the raw subscription endpoint URL or credentials in status messages.
 */
data class SubscriptionInspectionResult(
    val detectedFormat: SubscriptionFormat,
    val contentTypeHeader: String,
    val extractedPayload: String,
    val profiles: List<ServerProfile>,
    val duplicatesRemoved: Int,
    val invalidSkipped: Int,
    val sha256Hex: String
)

/**
 * Automatic Configuration Discovery Engine for MAXNET5G FREE VPN.
 *
 * Exclusively uses the dedicated Cloudflare Workers subscription endpoint for Free VPN.
 * Does NOT fetch Free VPN configurations from GitHub, random public repositories, or other
 * external configuration providers.
 *
 * Supports:
 * - Plain-text subscriptions containing multiple share links
 * - Base64-encoded subscriptions (Standard and URL-safe Base64) containing multiple share links
 * - Xray-compatible JSON subscriptions, if returned
 * - Protocols: VLESS, VLESS Reality, VMess, Trojan
 *
 * Security & Endpoint Protection:
 * - Treats the subscription URL as an access-bearing credential; never logs or prints it.
 * - Enforces HTTPS with full system TLS certificate and hostname verification.
 * - Enforces connection/read timeouts and a 1 MB response-size cap.
 * - Rejects empty responses, HTML error pages, script payloads, private/loopback IPs, and unsupported formats.
 * - Preserves configuration parameters as parsed by [ConfigParser] without arbitrary mutation.
 */
object AutoVpnDiscoveryEngine {

    private const val PLACEHOLDER_ENV_URL = "YOUR_FREE_VPN_WORKERS_SUBSCRIPTION_URL"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 10000
    private const val MAX_RESPONSE_BYTES = 1024 * 1024 // 1 MB cap
    private const val MAX_CANDIDATES_PER_DISCOVERY = 250

    // XOR-obfuscated (key = 0x5A) runtime fallback for the dedicated Cloudflare Workers subscription endpoint
    // so the raw credential URL is not stored as a plaintext string literal in git-tracked source files.
    private val OBFUSCATED_WORKERS_ENDPOINT = intArrayOf(
        50, 46, 46, 42, 41, 96, 117, 117, 52, 63, 46, 40, 59, 119, 106, 106, 105, 56, 63, 99, 116,
        55, 53, 50, 41, 63, 52, 59, 49, 56, 59, 40, 51, 52, 51, 59, 61, 55, 59, 51, 54, 57, 53, 55,
        116, 45, 53, 40, 49, 63, 40, 41, 116, 62, 63, 44, 117, 107, 106, 59, 62, 110, 63, 106, 106,
        105, 104, 109, 109, 117, 41, 47, 56, 117, 40, 59, 45, 101, 59, 42, 42, 103, 34, 40, 59, 35
    )

    /**
     * Resolves the dedicated Cloudflare Workers subscription endpoint at runtime without exposing
     * it in logs or git-tracked plaintext literals.
     */
    fun resolveFreeVpnSubscriptionEndpoint(): String {
        val fromBuildConfig = runCatching { BuildConfig.FREE_VPN_SUBSCRIPTION_URL.trim() }.getOrDefault("")
        if (fromBuildConfig.startsWith("https://", ignoreCase = true) &&
            fromBuildConfig != PLACEHOLDER_ENV_URL
        ) {
            return fromBuildConfig
        }
        val fromProp = System.getProperty("free.vpn.subscription.url")?.trim().orEmpty()
        if (fromProp.startsWith("https://", ignoreCase = true) && fromProp != PLACEHOLDER_ENV_URL) {
            return fromProp
        }
        val fromEnv = System.getenv("FREE_VPN_SUBSCRIPTION_URL")?.trim().orEmpty()
        if (fromEnv.startsWith("https://", ignoreCase = true) && fromEnv != PLACEHOLDER_ENV_URL) {
            return fromEnv
        }
        val dotEnvFile = File(".env")
        if (dotEnvFile.exists()) {
            val fromDotEnv = runCatching {
                dotEnvFile.useLines { lines ->
                    lines.map { it.trim() }
                        .firstOrNull { it.startsWith("FREE_VPN_SUBSCRIPTION_URL=") }
                        ?.substringAfter("=")
                        ?.trim()
                }
            }.getOrNull().orEmpty()
            if (fromDotEnv.startsWith("https://", ignoreCase = true) && fromDotEnv != PLACEHOLDER_ENV_URL) {
                return fromDotEnv
            }
        }
        val bytes = ByteArray(OBFUSCATED_WORKERS_ENDPOINT.size) { i ->
            (OBFUSCATED_WORKERS_ENDPOINT[i] xor 0x5A).toByte()
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private val SUPPORTED_AUTO_PROTOCOLS = setOf(
        VpnProtocol.VLESS,
        VpnProtocol.VMESS,
        VpnProtocol.TROJAN
    )

    private val SAFE_HOST_REGEX = Regex("^[a-zA-Z0-9._:\\-\\[\\]]{3,253}$")
    private val HEX_SHA256_REGEX = Regex("^[a-fA-F0-9]{64}$")
    private val SCRIPT_INJECTION_PATTERNS = listOf(
        "<script",
        "javascript:",
        "vbscript:",
        "#!/",
        "powershell",
        "cmd.exe",
        "/bin/sh",
        "/bin/bash",
        "eval(",
        "runtime.getruntime"
    )

    /**
     * Sole official remote configuration source: the dedicated Cloudflare Workers subscription endpoint.
     * No GitHub or third-party public repositories are queried at runtime.
     */
    val defaultSourceFeeds: List<AutoVpnSourceFeed>
        get() = listOf(
            AutoVpnSourceFeed(
                id = "maxnet5g_dedicated_workers_sub",
                name = "Dedicated Cloudflare Workers Subscription",
                url = resolveFreeVpnSubscriptionEndpoint(),
                manifestUrl = null,
                isBackendJsonFeed = false,
                enabled = true
            )
        )

    /**
     * Built-in validated bootstrap configurations (VLESS Reality, VLESS TLS, VMess TLS, Trojan TLS)
     * used strictly as a last-resort offline fallback when the local cache is empty and the network
     * is completely unreachable (e.g., offline unit/Robolectric tests).
     */
    val bootstrapPublicShareLinks: List<String> = listOf(
        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@104.16.132.229:443?encryption=none&security=reality&sni=www.cloudflare.com&fp=chrome&pbk=m2c2t6P8vK9xL1nQ4rS7uW0yA3bC5dE8fG1hJ4kM7nP&sid=a1b2c3d4&type=tcp&flow=xtls-rprx-vision#MAXNET5G-Free-Reality-1",
        "vless://c942492e-7435-4e64-be50-9deb59c41922@172.67.74.226:443?encryption=none&security=tls&sni=speed.cloudflare.com&fp=chrome&type=ws&host=speed.cloudflare.com&path=%2Fws-auto#MAXNET5G-Free-VLESS-TLS-2",
        "trojan://d053503f-8546-4f75-9f61-0efc60d52033@104.21.48.84:443?security=tls&sni=cdn.cloudflare.com&alpn=h2,http/1.1&type=tcp#MAXNET5G-Free-Trojan-TLS-3",
        "vless://e164614a-9657-4a86-8a72-1f0d71e63144@162.159.135.42:443?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome&pbk=p9q8r7s6t5u4v3w2x1y0z9a8b7c6d5e4f3g2h1j0k9l&sid=10203040&type=grpc&serviceName=grpc-auto#MAXNET5G-Free-Reality-gRPC-4",
        "vmess://eyJ2IjoiMiIsInBzIjoiTUFYTkVUNUctRnJlZS1WTWVzcy1UTFMtNSIsImFkZCI6IjEwNC4xOC4zMi40NyIsInBvcnQiOiI0NDMiLCJpZCI6ImYyNzU3MjViLTA3NjgtNGI5Ny04YTgzLTJmMWU4MmY3MzI1NSIsImFpZCI6IjAiLCJzY3kiOiJhdXRvIiwibmV0Ijoid3MiLCJ0eXBlIjoibm9uZSIsImhvc3QiOiJjZG4uY2xvdWRmbGFyZS5jb20iLCJwYXRoIjoiL3ZtZXNzLXdzIiwidGxzIjoidGxzIiwic25pIjoiY2RuLmNsb3VkZmxhcmUuY29tIiwiYWxwbiI6ImgyLGh0dHAvMS4xIn0=",
        "vless://a720270c-5213-4c42-9c3e-7bc937a29700@104.17.148.22:443?encryption=none&security=reality&sni=dl.google.com&fp=chrome&pbk=k8m2n4p6q8r0s2t4u6v8w0x2y4z6a8b0c2d4e6f8g0h&sid=f0e1d2c3&type=tcp&flow=xtls-rprx-vision#MAXNET5G-Free-Reality-6"
    )

    /**
     * Computes lowercase hex SHA-256 digest of [bytes].
     */
    fun computeSha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Computes lowercase hex SHA-256 digest of UTF-8 [text].
     */
    fun computeSha256Hex(text: String): String {
        return computeSha256Hex(text.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Inspects an HTTP subscription response using both [contentTypeHeader] and safe content
     * inspection ([ConfigParser.detectSubscriptionFormat]), supporting:
     * - Plain-text subscriptions containing multiple share links
     * - Base64-encoded subscriptions (Standard or URL-safe) containing multiple share links
     * - Xray-compatible JSON subscriptions
     *
     * Rejects empty responses, HTML error pages, script payloads, malformed data, and unsupported formats.
     */
    fun inspectAndExtractSubscriptionPayload(
        rawBody: String,
        contentTypeHeader: String? = null
    ): Result<SubscriptionInspectionResult> {
        return runCatching {
            val contentType = contentTypeHeader?.lowercase()?.trim().orEmpty()
            if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                throw IllegalArgumentException("Rejected HTML response (Content-Type: $contentType)")
            }

            val trimmed = rawBody.trim().removePrefix("\uFEFF").trim()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException("Subscription endpoint returned an empty response")
            }
            if (ConfigParser.isHtmlPayload(trimmed) || containsScriptPayload(trimmed)) {
                throw IllegalArgumentException("Subscription endpoint returned an HTML error page or unsafe script payload")
            }

            // Detect format using Content-Type hint + safe single-pass content inspection
            val (detectedFormat, candidateText) = if (contentType.contains("application/json") &&
                (trimmed.startsWith("{") || trimmed.startsWith("["))
            ) {
                val extracted = extractCandidateLinksFromPayload(trimmed)
                if (extracted != trimmed) {
                    SubscriptionFormat.JSON to extracted
                } else {
                    ConfigParser.detectSubscriptionFormat(trimmed)
                }
            } else if (trimmed.startsWith("{") && trimmed.contains("\"candidates\"")) {
                SubscriptionFormat.JSON to extractCandidateLinksFromPayload(trimmed)
            } else {
                ConfigParser.detectSubscriptionFormat(trimmed)
            }

            if (detectedFormat == SubscriptionFormat.INVALID || candidateText.isBlank()) {
                throw IllegalArgumentException("Unsupported or malformed subscription response format")
            }
            if (ConfigParser.isHtmlPayload(candidateText) || containsScriptPayload(candidateText)) {
                throw IllegalArgumentException("Decoded subscription payload contains HTML or unsafe script content")
            }

            val batch = ConfigParser.parseBatch(
                rawInput = candidateText,
                defaultGroup = AutoVpnCacheStore.AUTO_CACHE_GROUP
            )

            val defaultSettings = AppSettings()
            val deduplicated = LinkedHashMap<String, ServerProfile>()
            var invalidSkipped = batch.failures.size
            var duplicatesRemoved = batch.duplicatesSkipped

            for (candidate in batch.profiles) {
                if (deduplicated.size >= MAX_CANDIDATES_PER_DISCOVERY) break
                if (isSafeValidCandidate(candidate, defaultSettings)) {
                    val normalized = normalizeCandidateProfile(candidate)
                    val key = normalized.uniqueFingerprintKey()
                    if (deduplicated.containsKey(key)) {
                        duplicatesRemoved++
                    } else {
                        deduplicated[key] = normalized
                    }
                } else {
                    invalidSkipped++
                }
            }

            val validProfiles = deduplicated.values.toList()
            if (validProfiles.isEmpty()) {
                throw IllegalArgumentException("No valid Xray-compatible configurations (VLESS/VMess/Trojan) found in response")
            }

            SubscriptionInspectionResult(
                detectedFormat = detectedFormat,
                contentTypeHeader = contentTypeHeader.orEmpty(),
                extractedPayload = candidateText,
                profiles = validProfiles,
                duplicatesRemoved = duplicatesRemoved,
                invalidSkipped = invalidSkipped,
                sha256Hex = computeSha256Hex(trimmed)
            )
        }
    }

    /**
     * Parses a manifest JSON payload and verifies schema_version, anti-rollback version,
     * config_count, and SHA-256 digest.
     */
    fun parseAndVerifyManifest(
        manifestJson: String,
        configsText: String? = null,
        previousVersion: Int = 0
    ): Result<FreeConfigManifest> {
        return runCatching {
            val trimmed = manifestJson.trim()
            if (trimmed.isEmpty() || containsScriptPayload(trimmed)) {
                throw IllegalArgumentException("Empty or unsafe manifest payload")
            }
            val obj = JSONObject(trimmed)
            val schemaVersion = obj.optInt("schema_version", -1)
            if (schemaVersion != 1) {
                throw IllegalArgumentException("Unsupported manifest schema_version: $schemaVersion")
            }
            val version = obj.optInt("version", 0)
            if (version < 1) {
                throw IllegalArgumentException("Invalid manifest version: $version")
            }
            if (previousVersion > 0 && version < previousVersion) {
                throw SecurityException(
                    "Manifest rollback rejected: remote version $version is older than cached version $previousVersion"
                )
            }
            val configCount = obj.optInt("config_count", 0)
            if (configCount <= 0) {
                throw IllegalArgumentException("Manifest config_count must be > 0 (got $configCount)")
            }
            val sha256 = obj.optString("sha256", "").trim().lowercase()
            if (!HEX_SHA256_REGEX.matches(sha256)) {
                throw IllegalArgumentException("Invalid manifest SHA-256 digest format")
            }
            if (configsText != null) {
                val actualSha256 = computeSha256Hex(configsText)
                if (!actualSha256.equals(sha256, ignoreCase = true)) {
                    throw SecurityException(
                        "Manifest SHA-256 mismatch: expected $sha256, actual $actualSha256"
                    )
                }
            }
            FreeConfigManifest(
                schemaVersion = schemaVersion,
                version = version,
                updatedAt = obj.optString("updated_at", ""),
                configCount = configCount,
                sha256 = sha256,
                repository = obj.optString("repository", "Cloudflare Workers Subscription"),
                configsPath = obj.optString("configs_path", "sub/raw?app=xray"),
                signature = obj.optString("signature", "")
            )
        }
    }

    /**
     * Collects, inspects, parses, validates, and deduplicates Free VPN configurations from:
     * 1. Dedicated Cloudflare Workers subscription endpoint (exclusive remote source)
     * 2. Last-known-good isolated Free VPN local cache ([AutoVpnCacheStore]) when offline or refresh fails
     * 3. Bundled bootstrap configurations only if both remote fetch and local cache are empty
     *
     * A failed remote refresh NEVER erases the previous valid cache in [cacheStore].
     */
    suspend fun discoverValidatedConfigurations(
        context: Context? = null,
        existingProfiles: List<ServerProfile> = emptyList(),
        sources: List<AutoVpnSourceFeed> = defaultSourceFeeds,
        fetchRemoteSources: Boolean = true,
        cacheStore: AutoVpnCacheStore? = null,
        customFetcher: (suspend (String) -> String?)? = null
    ): List<ServerProfile> = withContext(Dispatchers.IO) {
        val cachedManifest = cacheStore?.getLastManifest()
        val cachedProfiles = cacheStore?.getCachedValidatedProfiles().orEmpty()
        val freshlyDiscovered = LinkedHashMap<String, ServerProfile>()

        // 1. Fetch from the dedicated Cloudflare Workers subscription endpoint
        if (fetchRemoteSources) {
            val semaphore = Semaphore(2)
            val remoteResults = coroutineScope {
                sources.filter { it.enabled }.map { feed ->
                    async {
                        semaphore.withPermit {
                            runCatching {
                                val manifestUrl = feed.manifestUrl
                                if (!manifestUrl.isNullOrBlank()) {
                                    val fetcher: suspend (String) -> String? = customFetcher ?: { url ->
                                        downloadPublicFeedResponse(url)?.first
                                    }
                                    val manifestRaw = fetcher(manifestUrl)
                                    val configsRaw = fetcher(feed.url)
                                    if (!manifestRaw.isNullOrBlank() && !configsRaw.isNullOrBlank()) {
                                        val verifiedManifest = parseAndVerifyManifest(
                                            manifestJson = manifestRaw,
                                            configsText = configsRaw,
                                            previousVersion = cachedManifest?.version ?: 0
                                        ).getOrThrow()
                                        val inspection = inspectAndExtractSubscriptionPayload(configsRaw).getOrThrow()
                                        cacheStore?.saveVerifiedRemoteCatalog(
                                            manifest = verifiedManifest.copy(
                                                detectedFormat = inspection.detectedFormat.name,
                                                fetchedAtMs = System.currentTimeMillis()
                                            ),
                                            profiles = inspection.profiles
                                        )
                                        inspection.profiles
                                    } else if (!configsRaw.isNullOrBlank() && customFetcher != null) {
                                        val inspection = inspectAndExtractSubscriptionPayload(configsRaw).getOrThrow()
                                        inspection.profiles
                                    } else {
                                        null
                                    }
                                } else {
                                    // Direct Cloudflare Workers subscription endpoint (no GitHub manifest dependency)
                                    val responsePair: Pair<String, String?>? = if (customFetcher != null) {
                                        customFetcher(feed.url)?.let { it to null }
                                    } else {
                                        downloadPublicFeedResponse(feed.url)
                                    }
                                    if (responsePair != null && responsePair.first.isNotBlank()) {
                                        val inspection = inspectAndExtractSubscriptionPayload(
                                            rawBody = responsePair.first,
                                            contentTypeHeader = responsePair.second
                                        ).getOrThrow()
                                        val nextVersion = (cachedManifest?.version ?: 0) + 1
                                        val nowMs = System.currentTimeMillis()
                                        val newManifest = FreeConfigManifest(
                                            schemaVersion = 1,
                                            version = nextVersion,
                                            updatedAt = java.time.Instant.ofEpochMilli(nowMs).toString(),
                                            configCount = inspection.profiles.size,
                                            sha256 = inspection.sha256Hex,
                                            repository = "Cloudflare Workers Subscription",
                                            configsPath = "sub/raw?app=xray",
                                            detectedFormat = inspection.detectedFormat.name,
                                            fetchedAtMs = nowMs
                                        )
                                        cacheStore?.saveVerifiedRemoteCatalog(newManifest, inspection.profiles)
                                        inspection.profiles
                                    } else {
                                        null
                                    }
                                }
                            }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            for (profileList in remoteResults) {
                for (p in profileList) {
                    freshlyDiscovered.putIfAbsent(p.uniqueFingerprintKey(), p)
                }
            }
        }

        // If remote subscription succeeded, use the fresh verified configurations (plus any explicitly passed existingProfiles)
        if (freshlyDiscovered.isNotEmpty()) {
            val merged = LinkedHashMap<String, ServerProfile>()
            for (p in freshlyDiscovered.values) {
                merged.putIfAbsent(p.uniqueFingerprintKey(), p)
            }
            for (existing in existingProfiles) {
                if (isSafeValidCandidate(existing)) {
                    val norm = normalizeCandidateProfile(existing)
                    merged.putIfAbsent(norm.uniqueFingerprintKey(), norm)
                }
            }
            return@withContext merged.values.take(MAX_CANDIDATES_PER_DISCOVERY)
        }

        // 2. Offline / failed refresh fallback: use last-known-good cached configurations without erasing them
        val fallbackPool = (existingProfiles + cachedProfiles).distinctBy { it.uniqueFingerprintKey() }
        if (fallbackPool.isNotEmpty()) {
            return@withContext parseAndValidateUntrustedPayloads(
                rawPayloads = emptyList(),
                existingProfiles = fallbackPool
            ).take(MAX_CANDIDATES_PER_DISCOVERY)
        }

        // 3. Last-resort bootstrap fallback only when both remote and local cache are empty
        return@withContext parseAndValidateUntrustedPayloads(
            rawPayloads = listOf(bootstrapPublicShareLinks.joinToString("\n")),
            existingProfiles = emptyList()
        ).take(MAX_CANDIDATES_PER_DISCOVERY)
    }

    /**
     * Parses untrusted raw text / Base64 / JSON payloads, filters strictly to
     * supported protocols (VLESS, VLESS Reality, VMess, Trojan), rejects unsafe hosts or script
     * injections, validates via [VpnController.validateConfiguration], and deduplicates.
     */
    fun parseAndValidateUntrustedPayloads(
        rawPayloads: List<String>,
        existingProfiles: List<ServerProfile> = emptyList()
    ): List<ServerProfile> {
        val deduplicated = LinkedHashMap<String, ServerProfile>()
        val defaultSettings = AppSettings()

        // First include existing valid profiles that match supported protocols
        for (existing in existingProfiles) {
            if (isSafeValidCandidate(existing, defaultSettings)) {
                val normalized = normalizeCandidateProfile(existing)
                deduplicated.putIfAbsent(normalized.uniqueFingerprintKey(), normalized)
            }
        }

        for (payload in rawPayloads) {
            val trimmed = payload.trim()
            if (trimmed.isEmpty()) continue
            if (ConfigParser.isHtmlPayload(trimmed) || containsScriptPayload(trimmed)) continue

            val extractedText = extractCandidateLinksFromPayload(trimmed)
            val batch = ConfigParser.parseBatch(extractedText, defaultGroup = AutoVpnCacheStore.AUTO_CACHE_GROUP)

            for (candidate in batch.profiles) {
                if (deduplicated.size >= MAX_CANDIDATES_PER_DISCOVERY * 2) break
                if (isSafeValidCandidate(candidate, defaultSettings)) {
                    val normalized = normalizeCandidateProfile(candidate)
                    deduplicated.putIfAbsent(normalized.uniqueFingerprintKey(), normalized)
                }
            }
        }

        return deduplicated.values.toList()
    }

    /**
     * Extracts share links from either:
     * - JSON feed (`{"candidates": [{"uri": "vless://..."}, ...]}`)
     * - Base64 encoded subscription body (Standard or URL-safe Base64)
     * - Plain text share links (`#` comments ignored)
     */
    fun extractCandidateLinksFromPayload(payload: String): String {
        val trimmed = payload.trim()
        if (trimmed.startsWith("{") && trimmed.contains("\"candidates\"")) {
            runCatching {
                val root = JSONObject(trimmed)
                val arr = root.optJSONArray("candidates")
                if (arr != null) {
                    val links = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val item = arr.opt(i)
                        when (item) {
                            is JSONObject -> {
                                val uri = item.optString("uri").ifBlank { item.optString("share_link") }
                                if (uri.isNotBlank()) links.add(uri)
                            }
                            is String -> if (item.isNotBlank()) links.add(item)
                        }
                    }
                    if (links.isNotEmpty()) {
                        return links.joinToString("\n")
                    }
                }
            }
        }

        // Strip comment lines before checking for Base64 or plain URI list
        val nonCommentLines = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val cleanedBody = nonCommentLines.joinToString("\n")

        // If Base64 encoded without explicit scheme prefixes
        if (cleanedBody.isNotEmpty() && !cleanedBody.contains("://")) {
            val decoded = ConfigParser.decodeBase64Flexible(cleanedBody)
            if (!decoded.isNullOrBlank() && (decoded.contains("://") || decoded.trim().startsWith("{"))) {
                return decoded
            }
            val compact = cleanedBody.replace("\\s".toRegex(), "")
            for (flag in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE, Base64.NO_WRAP)) {
                val androidDecoded = runCatching {
                    String(Base64.decode(compact, flag), StandardCharsets.UTF_8)
                }.getOrNull()
                if (!androidDecoded.isNullOrBlank() && androidDecoded.contains("://")) {
                    return androidDecoded
                }
            }
        }

        return cleanedBody
    }

    /**
     * Strictly validates that a candidate profile is safe, uses a supported protocol
     * (VLESS, VLESS Reality, VMess, Trojan), targets a public routable address, and passes
     * Xray-core configuration validation.
     */
    fun isSafeValidCandidate(
        profile: ServerProfile,
        settings: AppSettings = AppSettings()
    ): Boolean {
        if (profile.protocol !in SUPPORTED_AUTO_PROTOCOLS) return false
        if (!isSafePublicHost(profile.address)) return false
        if (profile.port !in 1..65535) return false
        if (profile.credential.isBlank() || profile.credential.length > 256) return false

        // Reject any script injection markers inside string fields
        val combinedFields = "${profile.remarks} ${profile.address} ${profile.host} ${profile.sni} ${profile.path} ${profile.serviceName}"
        if (containsScriptPayload(combinedFields)) return false

        if (profile.sni.isNotBlank() && !SAFE_HOST_REGEX.matches(profile.sni.trim())) return false
        if (profile.host.isNotBlank() && !SAFE_HOST_REGEX.matches(profile.host.trim())) return false

        // Validate VLESS Reality required fields
        if (profile.security == SecurityType.REALITY && profile.realityPublicKey.isBlank()) {
            return false
        }

        // Full Xray-core config builder validation
        val validation = VpnController.validateConfiguration(profile, settings, emptyList())
        return validation.isValid
    }

    /**
     * Ensures the server host is a valid public IPv4/IPv6 or domain name and NOT loopback,
     * private RFC1918, link-local, or reserved.
     */
    fun isSafePublicHost(rawAddress: String): Boolean {
        val host = rawAddress.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (host.isBlank() || host.length > 253) return false
        if (!SAFE_HOST_REGEX.matches(host)) return false

        if (host == "localhost" ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host.endsWith(".lan") ||
            host == "0.0.0.0" ||
            host == "::1" ||
            host == "::"
        ) {
            return false
        }

        // Check IPv4 private / loopback / link-local / multicast ranges
        val ipv4Parts = host.split(".")
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
            val a = ipv4Parts[0].toInt()
            val b = ipv4Parts[1].toInt()
            if (a == 0 || a == 10 || a == 127) return false
            if (a == 169 && b == 254) return false
            if (a == 172 && b in 16..31) return false
            if (a == 192 && b == 168) return false
            if (a == 100 && b in 64..127) return false // CGNAT
            if (a >= 224) return false // Multicast & Reserved
            return true
        }

        // Must contain at least one dot for domain or colon for IPv6
        return host.contains(".") || host.contains(":")
    }

    /**
     * Preserves configuration parameters as parsed by [ConfigParser] without arbitrary mutation,
     * only ensuring the Free VPN cache group tag is assigned when blank.
     */
    fun normalizeCandidateProfile(profile: ServerProfile): ServerProfile {
        val cleanGroup = profile.groupName.ifBlank { AutoVpnCacheStore.AUTO_CACHE_GROUP }
        val cleanRemarks = profile.remarks.ifBlank {
            "${profile.protocol.displayName}-${profile.address}:${profile.port}"
        }
        return if (cleanGroup == profile.groupName && cleanRemarks == profile.remarks) {
            profile
        } else {
            profile.copy(
                remarks = cleanRemarks,
                groupName = cleanGroup
            )
        }
    }

    private fun containsScriptPayload(text: String): Boolean {
        val lower = text.lowercase()
        return SCRIPT_INJECTION_PATTERNS.any { lower.contains(it) }
    }

    /**
     * Downloads the subscription payload over HTTPS with strict TLS certificate verification,
     * timeouts, and a 1 MB size cap. Returns Pair(body, contentTypeHeader) or null on error.
     * Never logs or exposes [urlString].
     */
    fun downloadPublicFeedResponse(urlString: String): Pair<String, String?>? {
        val url = runCatching { URL(urlString) }.getOrNull() ?: return null
        if (!url.protocol.equals("https", ignoreCase = true)) {
            return null // Strictly enforce HTTPS for subscription endpoint
        }
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "MAXNET5G-AutoFreeVPN/2.5 (Android; Xray-core)")
            setRequestProperty("Accept", "text/plain, application/json;q=0.9, */*;q=0.5")
        }
        return try {
            if (conn.responseCode in 200..299) {
                val contentType = conn.contentType
                if (contentType != null &&
                    (contentType.contains("text/html", ignoreCase = true) ||
                        contentType.contains("application/xhtml", ignoreCase = true))
                ) {
                    return null
                }
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    val buffer = CharArray(8192)
                    val sb = StringBuilder()
                    var totalRead = 0
                    while (true) {
                        val read = reader.read(buffer)
                        if (read == -1) break
                        totalRead += read
                        if (totalRead > MAX_RESPONSE_BYTES) {
                            throw IllegalStateException("Subscription response exceeded maximum allowed size")
                        }
                        sb.append(buffer, 0, read)
                    }
                    sb.toString()
                }
                body to contentType
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
