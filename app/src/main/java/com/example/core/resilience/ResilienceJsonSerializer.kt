package com.example.core.resilience

import com.example.domain.model.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON serializer/deserializer for [AuthorizedRelay] configurations and
 * mapper between [AppSettings] and [ConnectivityConfig].
 * Supports both Android `org.json` runtime and pure JVM unit test environments.
 */
object ResilienceJsonSerializer {

    fun toConnectivityConfig(settings: AppSettings): ConnectivityConfig {
        return ConnectivityConfig(
            masterResilienceEnabled = settings.resilienceMasterEnabled,
            lowDataMode = settings.lowDataProbingMode,
            allowRuntimeVariants = settings.allowRuntimeTransportVariants,
            multiNetworkAssistEnabled = settings.multiNetworkAssistEnabled,
            relayFallbackEnabled = settings.relayFallbackEnabled,
            customDomesticEndpoints = settings.customDomesticProbeUrls.take(8)
        )
    }

    fun parseAuthorizedRelays(rawJson: String): List<AuthorizedRelay> {
        if (rawJson.isBlank()) return emptyList()
        return try {
            val array = JSONArray(rawJson)
            val result = mutableListOf<AuthorizedRelay>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = obj.optString("name").trim()
                val address = obj.optString("address", obj.optString("host")).trim()
                val port = obj.optInt("port", 443)
                val modeStr = obj.optString("mode", RelayMode.ENDPOINT_OVERRIDE.name)
                val mode = runCatching { RelayMode.valueOf(modeStr) }
                    .getOrDefault(RelayMode.ENDPOINT_OVERRIDE)
                val targetServerIdsJson = obj.optJSONArray("targetServerIds")
                val targetIds = mutableSetOf<Long>()
                if (targetServerIdsJson != null) {
                    for (j in 0 until targetServerIdsJson.length()) {
                        val sid = targetServerIdsJson.optString(j).trim().toLongOrNull()
                        if (sid != null) targetIds.add(sid)
                    }
                }
                val isEnabled = obj.optBoolean("isEnabled", obj.optBoolean("enabled", true))
                val ownerAttested = obj.optBoolean("ownerAttested", true)
                if (id.isNotEmpty() && address.isNotEmpty() && port in 1..65535 && ownerAttested) {
                    result.add(
                        AuthorizedRelay(
                            id = id,
                            name = name.ifEmpty { "$address:$port" },
                            host = address,
                            port = port,
                            mode = mode,
                            enabled = isEnabled,
                            associatedProfileIds = targetIds,
                            ownerAttested = true
                        )
                    )
                }
            }
            result
        } catch (_: Throwable) {
            parseAuthorizedRelaysPureKotlin(rawJson)
        }
    }

    fun serializeAuthorizedRelays(relays: List<AuthorizedRelay>): String {
        val eligible = relays.filter { it.ownerAttested }
        return buildString {
            append('[')
            eligible.forEachIndexed { idx, relay ->
                if (idx > 0) append(',')
                val idsJson = relay.associatedProfileIds.joinToString(",") { "\"$it\"" }
                append('{')
                append("\"id\":\"").append(escapeJson(relay.id)).append("\",")
                append("\"name\":\"").append(escapeJson(relay.name)).append("\",")
                append("\"address\":\"").append(escapeJson(relay.host)).append("\",")
                append("\"host\":\"").append(escapeJson(relay.host)).append("\",")
                append("\"port\":").append(relay.port).append(',')
                append("\"mode\":\"").append(relay.mode.name).append("\",")
                append("\"targetServerIds\":[").append(idsJson).append("],")
                append("\"isEnabled\":").append(relay.enabled).append(',')
                append("\"enabled\":").append(relay.enabled).append(',')
                append("\"ownerAttested\":").append(relay.ownerAttested)
                append('}')
            }
            append(']')
        }
    }

    private fun parseAuthorizedRelaysPureKotlin(rawJson: String): List<AuthorizedRelay> {
        val objectRegex = Regex("""\{([^{}]*)\}""")
        val result = mutableListOf<AuthorizedRelay>()
        for (match in objectRegex.findAll(rawJson)) {
            val body = match.groupValues[1]
            val id = extractJsonString(body, "id")
            val name = extractJsonString(body, "name")
            val address = extractJsonString(body, "address").ifEmpty { extractJsonString(body, "host") }
            val port = extractJsonInt(body, "port", 443)
            val modeStr = extractJsonString(body, "mode").ifEmpty { RelayMode.ENDPOINT_OVERRIDE.name }
            val mode = runCatching { RelayMode.valueOf(modeStr) }.getOrDefault(RelayMode.ENDPOINT_OVERRIDE)
            val isEnabled = extractJsonBoolean(body, "isEnabled", true)
            val ownerAttested = extractJsonBoolean(body, "ownerAttested", true)
            val idsRaw = Regex(""""targetServerIds"\s*:\s*\[([^\]]*)\]""").find(body)?.groupValues?.get(1).orEmpty()
            val targetIds = Regex(""""(\d+)"""").findAll(idsRaw)
                .mapNotNull { it.groupValues[1].toLongOrNull() }
                .toSet()
            if (id.isNotEmpty() && address.isNotEmpty() && port in 1..65535 && ownerAttested) {
                result.add(
                    AuthorizedRelay(
                        id = id,
                        name = name.ifEmpty { "$address:$port" },
                        host = address,
                        port = port,
                        mode = mode,
                        enabled = isEnabled,
                        associatedProfileIds = targetIds,
                        ownerAttested = true
                    )
                )
            }
        }
        return result
    }

    private fun extractJsonString(body: String, key: String): String {
        val m = Regex(""""$key"\s*:\s*"([^"]*)"""").find(body)
        return m?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun extractJsonInt(body: String, key: String, defaultVal: Int): Int {
        val m = Regex(""""$key"\s*:\s*(\d+)""").find(body)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: defaultVal
    }

    private fun extractJsonBoolean(body: String, key: String, defaultVal: Boolean): Boolean {
        val m = Regex(""""$key"\s*:\s*(true|false)""").find(body)
        return m?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: defaultVal
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
