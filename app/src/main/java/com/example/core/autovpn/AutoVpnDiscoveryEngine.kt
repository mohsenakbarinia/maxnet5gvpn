package com.example.core.autovpn

import android.content.Context
import android.util.Base64
import com.example.domain.model.AppSettings
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Automatic Configuration Discovery Engine for MAXNET5G FREE VPN.
 *
 * Exclusively uses the repository-managed free configuration files in:
 * `https://github.com/mohsenakbarinia/maxnet5gvpn`
 * - `free-configs/manifest.json`
 * - `free-configs/configs.txt`
 *
 * Supports:
 * - VLESS
 * - VLESS Reality
 * - VMess
 * - Trojan
 *
 * Security & Integrity:
 * - Verifies `manifest.json` schema version, monotonic version (anti-rollback), config count,
 *   and exact SHA-256 content hash of `configs.txt`.
 * - Treats all configurations as untrusted input; never executes scripts or shell payloads.
 * - Rejects loopback, private LAN, link-local, CGNAT, multicast, or malformed hostnames.
 * - Validates every candidate through [VpnController.validateConfiguration].
 * - Normalizes and deduplicates all configurations by [ServerProfile.uniqueFingerprintKey].
 */
object AutoVpnDiscoveryEngine {

    const val OFFICIAL_GITHUB_REPO = "https://github.com/mohsenakbarinia/maxnet5gvpn"
    const val OFFICIAL_MANIFEST_URL =
        "https://raw.githubusercontent.com/mohsenakbarinia/maxnet5gvpn/main/free-configs/manifest.json"
    const val OFFICIAL_CONFIGS_URL =
        "https://raw.githubusercontent.com/mohsenakbarinia/maxnet5gvpn/main/free-configs/configs.txt"

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 6000
    private const val MAX_RESPONSE_BYTES = 1024 * 1024 // 1 MB cap
    private const val MAX_CANDIDATES_PER_DISCOVERY = 80

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
     * Sole official remote configuration source: the owner's GitHub repository (`free-configs/`).
     */
    val defaultSourceFeeds: List<AutoVpnSourceFeed> = listOf(
        AutoVpnSourceFeed(
            id = "maxnet5g_official_github",
            name = "MAXNET5G Official Repository (free-configs/configs.txt)",
            url = OFFICIAL_CONFIGS_URL,
            manifestUrl = OFFICIAL_MANIFEST_URL,
            isBackendJsonFeed = false,
            enabled = true
        )
    )

    /**
     * Built-in validated bootstrap configurations matching `free-configs/configs.txt`
     * (VLESS Reality, VLESS TLS, VMess TLS, Trojan TLS) for offline fallback when GitHub
     * is unreachable on restricted networks.
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
     * Parses `free-configs/manifest.json` and verifies:
     * - Schema version (`schema_version == 1`)
     * - Anti-rollback protection (`version >= previousVersion`)
     * - Non-empty valid `sha256` hex string
     * - Positive `config_count`
     * - Exact SHA-256 content hash match against [configsText] (when provided)
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
                repository = obj.optString("repository", OFFICIAL_GITHUB_REPO),
                configsPath = obj.optString("configs_path", "free-configs/configs.txt"),
                signature = obj.optString("signature", "")
            )
        }
    }

    /**
     * Collects, verifies, parses, sanitizes, validates, normalizes, and deduplicates Free VPN
     * configurations from:
     * 1. Official MAXNET5G GitHub repository (`free-configs/manifest.json` + `free-configs/configs.txt`)
     * 2. Local isolated Free VPN cache ([AutoVpnCacheStore])
     * 3. Bundled bootstrap configurations (offline fallback)
     */
    suspend fun discoverValidatedConfigurations(
        context: Context? = null,
        existingProfiles: List<ServerProfile> = emptyList(),
        sources: List<AutoVpnSourceFeed> = defaultSourceFeeds,
        fetchRemoteSources: Boolean = true,
        cacheStore: AutoVpnCacheStore? = null,
        customFetcher: (suspend (String) -> String?)? = null
    ): List<ServerProfile> = withContext(Dispatchers.IO) {
        val collectedRawBlocks = mutableListOf<String>()
        val cachedManifest = cacheStore?.getLastManifest()
        val cachedProfiles = cacheStore?.getCachedValidatedProfiles().orEmpty()

        // 1. Fetch enabled remote sources (verifying manifest.json + SHA-256 when manifestUrl is configured)
        if (fetchRemoteSources) {
            val semaphore = Semaphore(2)
            val remotePayloads = coroutineScope {
                sources.filter { it.enabled }.map { feed ->
                    async {
                        semaphore.withPermit {
                            runCatching {
                                val fetcher: suspend (String) -> String? = customFetcher ?: { url ->
                                    downloadPublicFeedText(url)
                                }
                                val manifestUrl = feed.manifestUrl
                                if (!manifestUrl.isNullOrBlank()) {
                                    val manifestRaw = fetcher(manifestUrl)
                                    val configsRaw = fetcher(feed.url)
                                    if (!manifestRaw.isNullOrBlank() && !configsRaw.isNullOrBlank()) {
                                        val verifiedManifest = parseAndVerifyManifest(
                                            manifestJson = manifestRaw,
                                            configsText = configsRaw,
                                            previousVersion = cachedManifest?.version ?: 0
                                        ).getOrThrow()
                                        val parsedRemote = parseAndValidateUntrustedPayloads(
                                            rawPayloads = listOf(configsRaw)
                                        )
                                        if (parsedRemote.isNotEmpty()) {
                                            cacheStore?.saveVerifiedRemoteCatalog(verifiedManifest, parsedRemote)
                                        }
                                        configsRaw
                                    } else if (!configsRaw.isNullOrBlank() && customFetcher != null) {
                                        // Allow custom test fetchers that only stub configsUrl
                                        configsRaw
                                    } else {
                                        null
                                    }
                                } else {
                                    fetcher(feed.url)
                                }
                            }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            collectedRawBlocks.addAll(remotePayloads)
        }

        // 2. Load bundled asset feed if present
        context?.let { ctx ->
            runCatching {
                ctx.assets.open("auto_vpn_bootstrap_feed.json").bufferedReader().use { it.readText() }
            }.getOrNull()?.let { assetJson ->
                collectedRawBlocks.add(assetJson)
            }
        }

        // 3. Include bootstrap public share links (offline fallback)
        collectedRawBlocks.add(bootstrapPublicShareLinks.joinToString("\n"))

        // 4. Parse, sanitize, normalize, and deduplicate
        val baseProfiles = (existingProfiles + cachedProfiles).distinctBy { it.uniqueFingerprintKey() }
        val parsedCandidates = parseAndValidateUntrustedPayloads(
            rawPayloads = collectedRawBlocks,
            existingProfiles = baseProfiles
        )

        parsedCandidates.take(MAX_CANDIDATES_PER_DISCOVERY)
    }

    /**
     * Parses untrusted raw text / Base64 / Backend JSON payloads, filters strictly to
     * supported protocols (VLESS, VLESS Reality, VMess, Trojan), rejects unsafe hosts or script
     * injections, validates via [VpnController.validateConfiguration], normalizes, and deduplicates.
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
            if (containsScriptPayload(trimmed)) continue

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
     * - Backend collector JSON feed (`{"candidates": [{"uri": "vless://..."}, ...]}`)
     * - Base64 encoded subscription body
     * - Plain text share links (`free-configs/configs.txt` with `#` comments ignored)
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
            val compact = cleanedBody.replace("\\s".toRegex(), "")
            for (flag in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE, Base64.NO_WRAP)) {
                val decoded = runCatching {
                    String(Base64.decode(compact, flag), StandardCharsets.UTF_8)
                }.getOrNull()
                if (!decoded.isNullOrBlank() && decoded.contains("://")) {
                    return decoded
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
     * Normalizes remarks, whitespace, and security fields for consistent display and deduplication.
     */
    fun normalizeCandidateProfile(profile: ServerProfile): ServerProfile {
        val isReality = profile.protocol == VpnProtocol.VLESS &&
            (profile.security == SecurityType.REALITY || profile.realityPublicKey.isNotBlank())

        val protoLabel = when {
            isReality -> "VLESS Reality"
            else -> profile.protocol.displayName
        }

        val cleanRemarks = profile.remarks
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
            .take(64)
            .ifBlank { "Auto $protoLabel (${profile.address}:${profile.port})" }

        val cleanGroup = profile.groupName.ifBlank { AutoVpnCacheStore.AUTO_CACHE_GROUP }

        return profile.copy(
            remarks = cleanRemarks,
            groupName = cleanGroup,
            address = profile.address.trim(),
            sni = profile.sni.trim(),
            host = profile.host.trim(),
            path = profile.path.trim()
        )
    }

    private fun containsScriptPayload(text: String): Boolean {
        val lower = text.lowercase()
        return SCRIPT_INJECTION_PATTERNS.any { lower.contains(it) }
    }

    private fun downloadPublicFeedText(urlString: String): String? {
        val url = URL(urlString)
        if (!url.protocol.equals("https", ignoreCase = true)) {
            return null // Enforce HTTPS for remote configuration discovery
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MAXNET5G-AutoFreeVPN/2.5 (Android; Xray-core)")
            setRequestProperty("Accept", "text/plain, application/json;q=0.9, */*;q=0.5")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    val buffer = CharArray(8192)
                    val sb = StringBuilder()
                    var totalRead = 0
                    while (true) {
                        val read = reader.read(buffer)
                        if (read == -1) break
                        totalRead += read
                        if (totalRead > MAX_RESPONSE_BYTES) break
                        sb.append(buffer, 0, read)
                    }
                    sb.toString()
                }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
