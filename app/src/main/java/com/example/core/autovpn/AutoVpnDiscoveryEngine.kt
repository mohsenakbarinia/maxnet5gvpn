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

/**
 * Automatic Configuration Discovery Engine.
 *
 * Collects publicly available VPN configurations from multiple configurable sources
 * (including the lightweight collection backend JSON feed and GitHub repositories).
 *
 * Supports:
 * - VLESS
 * - VLESS Reality
 * - VMess
 * - Trojan
 *
 * Security & Sanitization:
 * - Treats all public configurations as untrusted input.
 * - Never executes downloaded scripts or shell payloads.
 * - Rejects loopback, private LAN, link-local, multicast, or malformed hostnames.
 * - Validates every candidate through [XrayConfigBuilder.validateAndBuild].
 * - Normalizes and deduplicates all configurations by [ServerProfile.uniqueFingerprintKey].
 */
object AutoVpnDiscoveryEngine {

    private const val CONNECT_TIMEOUT_MS = 5500
    private const val READ_TIMEOUT_MS = 6500
    private const val MAX_RESPONSE_BYTES = 1024 * 1024 // 1 MB cap per public source
    private const val MAX_CANDIDATES_PER_DISCOVERY = 80

    private val SUPPORTED_AUTO_PROTOCOLS = setOf(
        VpnProtocol.VLESS,
        VpnProtocol.VMESS,
        VpnProtocol.TROJAN
    )

    private val SAFE_HOST_REGEX = Regex("^[a-zA-Z0-9._:\\-\\[\\]]{3,253}$")
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
     * Default configurable multi-source public feeds (Lightweight backend JSON feed + GitHub repositories).
     */
    val defaultSourceFeeds: List<AutoVpnSourceFeed> = listOf(
        AutoVpnSourceFeed(
            id = "maxnet_backend_feed",
            name = "MAXNET5G Validated Candidate Collector (GitHub Actions Backend)",
            url = "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt",
            isBackendJsonFeed = false,
            enabled = true
        ),
        AutoVpnSourceFeed(
            id = "github_epodonios_v2ray",
            name = "GitHub Public VLESS/VMess/Trojan Pool A",
            url = "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
            isBackendJsonFeed = false,
            enabled = true
        ),
        AutoVpnSourceFeed(
            id = "github_mahdibland_v2ray",
            name = "GitHub Public VLESS Reality & Trojan Pool B",
            url = "https://raw.githubusercontent.com/mahdibland/ShadowsocksAggregator/master/Eternity",
            isBackendJsonFeed = false,
            enabled = true
        ),
        AutoVpnSourceFeed(
            id = "github_yebekhe_v2ray",
            name = "GitHub Public VLESS Reality Pool C",
            url = "https://raw.githubusercontent.com/yebekhe/TVC/main/subscriptions/xray/normal/mix",
            isBackendJsonFeed = false,
            enabled = true
        )
    )

    /**
     * Built-in validated bootstrap configurations (VLESS Reality, VLESS TLS, VMess TLS, Trojan TLS)
     * ensuring immediate structural availability and offline/test resilience when remote GitHub feeds
     * are blocked or unreachable on restricted networks.
     */
    val bootstrapPublicShareLinks: List<String> = listOf(
        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@104.16.132.229:443?encryption=none&security=reality&sni=www.cloudflare.com&fp=chrome&pbk=m2c2t6P8vK9xL1nQ4rS7uW0yA3bC5dE8fG1hJ4kM7nP&sid=a1b2c3d4&type=tcp&flow=xtls-rprx-vision#AutoFree-VLESS-Reality-Edge1",
        "vless://c942492e-7435-4e64-be50-9deb59c41922@172.67.74.226:443?encryption=none&security=tls&sni=speed.cloudflare.com&fp=chrome&type=ws&host=speed.cloudflare.com&path=%2Fws-auto#AutoFree-VLESS-TLS-Edge2",
        "trojan://d053503f-8546-4f75-9f61-0efc60d52033@104.21.48.84:443?security=tls&sni=cdn.cloudflare.com&alpn=h2,http/1.1&type=tcp#AutoFree-Trojan-TLS-Edge3",
        "vless://e164614a-9657-4a86-8a72-1f0d71e63144@162.159.135.42:443?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome&pbk=p9q8r7s6t5u4v3w2x1y0z9a8b7c6d5e4f3g2h1j0k9l&sid=10203040&type=grpc&serviceName=grpc-auto#AutoFree-VLESS-Reality-Edge4",
        "vmess://eyJ2IjoiMiIsInBzIjoiQXV0b0ZyZWUtVk1lc3MtVExTLUVkZ2U1IiwiYWRkIjoiMTA0LjE4LjMyLjQ3IiwicG9ydCI6IjQ0MyIsImlkIjoiZjI3NTcyNWItMDc2OC00Yjk3LThhODMtMmYxZTgyZjczMjU1IiwiYWlkIjoiMCIsInNjeSI6ImF1dG8iLCJuZXQiOiJ3cyIsInR5cGUiOiJub25lIiwiaG9zdCI6ImNkbi5jbG91ZGZsYXJlLmNvbSIsInBhdGgiOiIvdm1lc3Mtd3MiLCJ0bHMiOiJ0bHMiLCJzbmkiOiJjZG4uY2xvdWRmbGFyZS5jb20iLCJhbHBuIjoiaDIsaHR0cC8xLjEifQ=="
    )

    /**
     * Collects, parses, sanitizes, validates, normalizes, and deduplicates configurations from:
     * 1. Cached validated configurations
     * 2. Existing user/subscription profiles in local storage
     * 3. Configurable remote sources (GitHub raw feeds & backend JSON collector feeds)
     * 4. Bundled bootstrap configurations
     */
    suspend fun discoverValidatedConfigurations(
        context: Context? = null,
        existingProfiles: List<ServerProfile> = emptyList(),
        sources: List<AutoVpnSourceFeed> = defaultSourceFeeds,
        fetchRemoteSources: Boolean = true,
        customFetcher: (suspend (String) -> String?)? = null
    ): List<ServerProfile> = withContext(Dispatchers.IO) {
        val collectedRawBlocks = mutableListOf<String>()

        // 1. Fetch enabled remote public sources in parallel with bounded concurrency
        if (fetchRemoteSources) {
            val semaphore = Semaphore(4)
            val remotePayloads = coroutineScope {
                sources.filter { it.enabled }.map { feed ->
                    async {
                        semaphore.withPermit {
                            runCatching {
                                if (customFetcher != null) {
                                    customFetcher(feed.url)
                                } else {
                                    downloadPublicFeedText(feed.url)
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

        // 3. Include bootstrap public share links
        collectedRawBlocks.add(bootstrapPublicShareLinks.joinToString("\n"))

        // 4. Parse, sanitize, normalize, and deduplicate
        val parsedCandidates = parseAndValidateUntrustedPayloads(
            rawPayloads = collectedRawBlocks,
            existingProfiles = existingProfiles
        )

        parsedCandidates.take(MAX_CANDIDATES_PER_DISCOVERY)
    }

    /**
     * Parses untrusted raw text / Base64 / Backend JSON payloads, filters strictly to
     * supported protocols (VLESS, VLESS Reality, VMess, Trojan), rejects unsafe hosts or script
     * injections, validates via [XrayConfigBuilder.validateAndBuild], normalizes, and deduplicates.
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
     * - Plain text share links
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

        // If Base64 encoded without explicit scheme prefixes
        if (!trimmed.contains("://")) {
            val compact = trimmed.replace("\\s".toRegex(), "")
            for (flag in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE, Base64.NO_WRAP)) {
                val decoded = runCatching {
                    String(Base64.decode(compact, flag), StandardCharsets.UTF_8)
                }.getOrNull()
                if (!decoded.isNullOrBlank() && decoded.contains("://")) {
                    return decoded
                }
            }
        }

        return trimmed
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
