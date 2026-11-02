package com.example.core.resilience

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Loads, validates, and manages probe endpoints from the bundled asset
 * (`connectivity/probe_endpoints.json`) and optional operator/user overrides in DataStore.
 *
 * Validation rules (Section 4.3):
 * - HTTPS-only for HTTP/XFER probes.
 * - Valid hostname/IP and port 1..65535.
 * - No embedded userinfo/credentials (`user:pass@host`) or query tokens.
 * - Max 8 endpoints per role.
 */
class ProbeEndpointRegistry(
    private val context: Context? = null
) {
    private val lock = Any()
    private var assetEndpoints: List<ProbeEndpoint> = defaultBuiltInEndpoints()
    private var customDomesticEndpoints: List<ProbeEndpoint> = emptyList()
    private var customInternationalEndpoints: List<ProbeEndpoint> = emptyList()

    init {
        loadFromAssetsIfAvailable()
    }

    fun loadFromAssetsIfAvailable(ctxOverride: Context? = context) {
        val ctx = ctxOverride ?: return
        runCatching {
            ctx.assets.open("connectivity/probe_endpoints.json").bufferedReader().use { reader ->
                val text = reader.readText()
                parseAssetJson(text)
            }
        }
    }

    fun isValidHttpsProbeUrl(rawUrl: String): Boolean {
        return createValidatedEndpointFromUrl(rawUrl, ProbeEndpointRole.DOMESTIC) != null
    }

    fun parseAssetJson(jsonText: String) {
        val root = JSONObject(jsonText)
        val parsed = mutableListOf<ProbeEndpoint>()
        val endpointsArr = root.optJSONArray("endpoints") ?: JSONArray()
        for (i in 0 until endpointsArr.length()) {
            val obj = endpointsArr.optJSONObject(i) ?: continue
            parseEndpointObject(obj, defaultRole = ProbeEndpointRole.INTERNATIONAL)?.let {
                if (validateEndpoint(it)) parsed.add(it)
            }
        }
        val domesticArr = root.optJSONArray("domesticEndpoints") ?: JSONArray()
        for (i in 0 until domesticArr.length()) {
            val obj = domesticArr.optJSONObject(i) ?: continue
            parseEndpointObject(obj, defaultRole = ProbeEndpointRole.DOMESTIC)?.let {
                if (validateEndpoint(it)) parsed.add(it)
            }
        }
        if (parsed.isNotEmpty()) {
            synchronized(lock) {
                assetEndpoints = parsed
            }
        }
    }

    fun setCustomDomesticUrls(urls: List<String>) {
        val validated = urls.mapNotNull { rawUrl ->
            createValidatedEndpointFromUrl(
                rawUrl = rawUrl,
                role = ProbeEndpointRole.DOMESTIC,
                providerGroup = "domestic_custom"
            )
        }.take(MAX_ENDPOINTS_PER_ROLE)
        synchronized(lock) {
            customDomesticEndpoints = validated
        }
    }

    fun setCustomInternationalUrls(urls: List<String>) {
        val validated = urls.mapNotNull { rawUrl ->
            createValidatedEndpointFromUrl(
                rawUrl = rawUrl,
                role = ProbeEndpointRole.INTERNATIONAL,
                providerGroup = "intl_custom"
            )
        }.take(MAX_ENDPOINTS_PER_ROLE)
        synchronized(lock) {
            customInternationalEndpoints = validated
        }
    }

    fun getInternationalEndpoints(): List<ProbeEndpoint> = synchronized(lock) {
        val combined = customInternationalEndpoints + assetEndpoints.filter {
            it.role == ProbeEndpointRole.INTERNATIONAL
        }
        combined
            .distinctBy { it.url.lowercase() }
            .sortedByDescending { it.priority }
            .take(MAX_ENDPOINTS_PER_ROLE)
    }

    fun getTunnelCheckEndpoints(): List<ProbeEndpoint> = synchronized(lock) {
        val fromAssets = assetEndpoints.filter { it.role == ProbeEndpointRole.TUNNEL_CHECK }
        val base = fromAssets.ifEmpty {
            assetEndpoints.filter { it.role == ProbeEndpointRole.INTERNATIONAL }
        }
        base
            .distinctBy { it.url.lowercase() }
            .sortedByDescending { it.priority }
            .take(MAX_ENDPOINTS_PER_ROLE)
    }

    fun getDomesticEndpoints(): List<ProbeEndpoint> = synchronized(lock) {
        val combined = customDomesticEndpoints + assetEndpoints.filter {
            it.role == ProbeEndpointRole.DOMESTIC
        }
        combined
            .distinctBy { it.url.lowercase() }
            .sortedByDescending { it.priority }
            .take(MAX_ENDPOINTS_PER_ROLE)
    }

    companion object {
        const val MAX_ENDPOINTS_PER_ROLE = 8

        fun validateEndpoint(endpoint: ProbeEndpoint): Boolean {
            if (endpoint.url.isBlank() || endpoint.host.isBlank()) return false
            if (endpoint.port !in 1..65535) return false
            val uri = runCatching { URI(endpoint.url.trim()) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "https") return false
            if (!uri.userInfo.isNullOrEmpty()) return false
            val host = uri.host?.trim() ?: return false
            if (host.isEmpty() || host.contains(' ')) return false
            val query = uri.rawQuery.orEmpty().lowercase()
            if (query.contains("token=") || query.contains("key=") || query.contains("password=")) {
                return false
            }
            return true
        }

        fun createValidatedEndpointFromUrl(
            rawUrl: String,
            role: ProbeEndpointRole,
            providerGroup: String = "custom"
        ): ProbeEndpoint? {
            val trimmed = rawUrl.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val host = uri.host?.trim()?.lowercase() ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val id = EndpointIdHasher.hashEndpoint(trimmed, port, role.name)
            val group = if (providerGroup == "custom" || providerGroup.endsWith("_custom")) {
                extractProviderGroupFromHost(host)
            } else {
                providerGroup
            }
            val candidate = ProbeEndpoint(
                id = id,
                role = role,
                providerGroup = group,
                url = trimmed,
                host = host,
                port = port,
                expectedHttpStatuses = setOf(200, 204),
                priority = 110
            )
            return if (validateEndpoint(candidate)) candidate else null
        }

        private fun extractProviderGroupFromHost(host: String): String {
            val parts = host.split('.').filter { it.isNotBlank() }
            return if (parts.size >= 2) {
                parts.takeLast(2).joinToString(".")
            } else {
                host
            }
        }

        private fun parseEndpointObject(
            obj: JSONObject,
            defaultRole: ProbeEndpointRole
        ): ProbeEndpoint? {
            val url = obj.optString("url", "").trim()
            val host = obj.optString("host", "").trim()
            if (url.isEmpty() || host.isEmpty()) return null
            val roleStr = obj.optString("role", defaultRole.name)
            val role = runCatching { ProbeEndpointRole.valueOf(roleStr) }.getOrDefault(defaultRole)
            val providerGroup = obj.optString("providerGroup", extractProviderGroupFromHost(host))
            val port = obj.optInt("port", 443)
            val statusesArr = obj.optJSONArray("expectedHttpStatuses")
            val statuses = mutableSetOf<Int>()
            if (statusesArr != null) {
                for (i in 0 until statusesArr.length()) {
                    statuses.add(statusesArr.optInt(i, 204))
                }
            }
            if (statuses.isEmpty()) statuses.add(204)
            val expectedBodyPrefix = obj.optString("expectedBodyPrefix", "").takeIf { it.isNotEmpty() }
            val spkiPin = obj.optString("spkiPinSha256", "").takeIf { it.isNotEmpty() }
            val priority = obj.optInt("priority", 100)
            val id = obj.optString("id", EndpointIdHasher.hashEndpoint(url, port, role.name))
            return ProbeEndpoint(
                id = id,
                role = role,
                providerGroup = providerGroup,
                url = url,
                host = host,
                port = port,
                expectedHttpStatuses = statuses,
                expectedBodyPrefix = expectedBodyPrefix,
                spkiPinSha256 = spkiPin,
                priority = priority
            )
        }

        fun defaultBuiltInEndpoints(): List<ProbeEndpoint> = listOf(
            ProbeEndpoint(
                id = "intl_google_204",
                role = ProbeEndpointRole.INTERNATIONAL,
                providerGroup = "google",
                url = "https://www.gstatic.com/generate_204",
                host = "www.gstatic.com",
                port = 443,
                expectedHttpStatuses = setOf(204),
                priority = 100
            ),
            ProbeEndpoint(
                id = "intl_cloudflare_204",
                role = ProbeEndpointRole.INTERNATIONAL,
                providerGroup = "cloudflare",
                url = "https://cp.cloudflare.com/generate_204",
                host = "cp.cloudflare.com",
                port = 443,
                expectedHttpStatuses = setOf(204),
                priority = 95
            ),
            ProbeEndpoint(
                id = "intl_msft_connect",
                role = ProbeEndpointRole.INTERNATIONAL,
                providerGroup = "microsoft",
                url = "https://www.msftconnecttest.com/connecttest.txt",
                host = "www.msftconnecttest.com",
                port = 443,
                expectedHttpStatuses = setOf(200),
                expectedBodyPrefix = "Microsoft Connect Test",
                priority = 90
            ),
            ProbeEndpoint(
                id = "intl_google_connectivity",
                role = ProbeEndpointRole.INTERNATIONAL,
                providerGroup = "google",
                url = "https://connectivitycheck.gstatic.com/generate_204",
                host = "connectivitycheck.gstatic.com",
                port = 443,
                expectedHttpStatuses = setOf(204),
                priority = 85
            ),
            ProbeEndpoint(
                id = "tunnel_cloudflare_204",
                role = ProbeEndpointRole.TUNNEL_CHECK,
                providerGroup = "cloudflare",
                url = "https://cp.cloudflare.com/generate_204",
                host = "cp.cloudflare.com",
                port = 443,
                expectedHttpStatuses = setOf(204),
                priority = 100
            ),
            ProbeEndpoint(
                id = "tunnel_google_204",
                role = ProbeEndpointRole.TUNNEL_CHECK,
                providerGroup = "google",
                url = "https://www.gstatic.com/generate_204",
                host = "www.gstatic.com",
                port = 443,
                expectedHttpStatuses = setOf(204),
                priority = 95
            ),
            ProbeEndpoint(
                id = "tunnel_msft_connect",
                role = ProbeEndpointRole.TUNNEL_CHECK,
                providerGroup = "microsoft",
                url = "https://www.msftconnecttest.com/connecttest.txt",
                host = "www.msftconnecttest.com",
                port = 443,
                expectedHttpStatuses = setOf(200),
                expectedBodyPrefix = "Microsoft Connect Test",
                priority = 90
            )
        )
    }
}
