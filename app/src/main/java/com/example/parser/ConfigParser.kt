package com.example.parser

import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType
import com.example.domain.model.VpnProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

data class ParseFailure(
    val rawSnippet: String,
    val reason: String
)

data class BatchParseResult(
    val profiles: List<ServerProfile>,
    val failures: List<ParseFailure>,
    val duplicatesSkipped: Int
)

/**
 * Pure Kotlin VPN Configuration Parser & Share-Link Serializer.
 * Supports VLESS, VMess (v2rayN JSON & URI), Trojan, Shadowsocks (SIP002, Legacy Base64, 2022 Blake3),
 * Hysteria2 (hy2:// & hysteria2://), WireGuard (wireguard://), Xray JSON outbounds, and Base64 subscription payloads.
 */
object ConfigParser {

    private val supportedSchemes = listOf(
        "vless://", "vmess://", "trojan://", "ss://",
        "hysteria2://", "hy2://", "wireguard://", "tuic://"
    )

    /**
     * Decodes standard or URL-safe Base64 with or without padding, ignoring whitespace.
     */
    fun decodeBase64Flexible(input: String): String? {
        val cleaned = input.trim().replace("\r", "").replace("\n", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        return try {
            val normalized = cleaned
                .replace('-', '+')
                .replace('_', '/')
            val padNeeded = (4 - (normalized.length % 4)) % 4
            val padded = normalized + "=".repeat(padNeeded)
            val bytes = Base64.getDecoder().decode(padded)
            val decoded = String(bytes, Charsets.UTF_8)
            // Reject binary garbage if it contains NUL bytes
            if (decoded.any { it == '\u0000' }) null else decoded
        } catch (_: Exception) {
            null
        }
    }

    fun encodeBase64NoPad(input: String, urlSafe: Boolean = false): String {
        val encoder = if (urlSafe) Base64.getUrlEncoder().withoutPadding() else Base64.getEncoder().withoutPadding()
        return encoder.encodeToString(input.toByteArray(Charsets.UTF_8))
    }

    fun percentDecode(value: String): String {
        return try {
            // Preserve '+' inside remarks if needed or standard URL decode
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    fun percentEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            value
        }
    }

    /**
     * Parses a single text block or subscription body containing one or more configurations,
     * Base64-encoded subscription lists, or an Xray JSON configuration.
     */
    fun parseBatch(
        rawInput: String,
        defaultGroup: String = "Default",
        subscriptionId: Long? = null,
        existingFingerprintKeys: Set<String> = emptySet()
    ): BatchParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return BatchParseResult(emptyList(), listOf(ParseFailure("", "Empty input")), 0)
        }

        // Reject HTML bodies (e.g. captive portals or error pages from subscription links)
        if (trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        ) {
            return BatchParseResult(
                emptyList(),
                listOf(ParseFailure("HTML Document", "Received HTML webpage instead of VPN configuration")),
                0
            )
        }

        // Check if input is an Xray JSON object or array
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val jsonResult = parseXrayJson(trimmed, defaultGroup, subscriptionId)
            if (jsonResult.profiles.isNotEmpty() || jsonResult.failures.isNotEmpty()) {
                return deduplicate(jsonResult.profiles, jsonResult.failures, existingFingerprintKeys)
            }
        }

        // Check if the entire body is a Base64-encoded subscription payload
        val candidateText = if (!containsAnyScheme(trimmed)) {
            val decoded = decodeBase64Flexible(trimmed)
            if (decoded != null && (containsAnyScheme(decoded) || decoded.trim().startsWith("{"))) {
                decoded
            } else {
                trimmed
            }
        } else {
            trimmed
        }

        val lines = candidateText
            .lines()
            .flatMap { line -> splitMultipleUrisInLine(line.trim()) }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

        val parsedProfiles = mutableListOf<ServerProfile>()
        val failures = mutableListOf<ParseFailure>()

        for (line in lines) {
            val singleResult = parseSingleUri(line, defaultGroup, subscriptionId)
            singleResult.fold(
                onSuccess = { parsedProfiles.add(it) },
                onFailure = { err ->
                    failures.add(ParseFailure(line.take(64), err.message ?: "Invalid configuration"))
                }
            )
        }

        return deduplicate(parsedProfiles, failures, existingFingerprintKeys)
    }

    private fun deduplicate(
        profiles: List<ServerProfile>,
        failures: List<ParseFailure>,
        existingKeys: Set<String>
    ): BatchParseResult {
        val seen = existingKeys.toMutableSet()
        val unique = mutableListOf<ServerProfile>()
        var skipped = 0
        for (profile in profiles) {
            val key = profile.uniqueFingerprintKey()
            if (seen.add(key)) {
                unique.add(profile)
            } else {
                skipped++
            }
        }
        return BatchParseResult(unique, failures, skipped)
    }

    private fun containsAnyScheme(text: String): Boolean {
        val lower = text.lowercase()
        return supportedSchemes.any { lower.contains(it) }
    }

    private fun splitMultipleUrisInLine(line: String): List<String> {
        if (line.isEmpty()) return emptyList()
        val regex = Regex("(?=\\b(?:vless|vmess|trojan|ss|hysteria2|hy2|wireguard|tuic)://)", RegexOption.IGNORE_CASE)
        val parts = line.split(regex).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(line) else parts
    }

    fun parseSingleUri(
        rawUri: String,
        defaultGroup: String = "Default",
        subscriptionId: Long? = null
    ): Result<ServerProfile> {
        val cleaned = rawUri.trim()
        val schemeIndex = cleaned.indexOf("://")
        if (schemeIndex <= 0) {
            return Result.failure(IllegalArgumentException("Missing URI scheme (expected vless://, vmess://, trojan://, ss://)"))
        }
        val scheme = cleaned.substring(0, schemeIndex).lowercase()
        return when (scheme) {
            "vless" -> parseVlessUri(cleaned, defaultGroup, subscriptionId)
            "vmess" -> parseVmessUri(cleaned, defaultGroup, subscriptionId)
            "trojan" -> parseTrojanUri(cleaned, defaultGroup, subscriptionId)
            "ss" -> parseShadowsocksUri(cleaned, defaultGroup, subscriptionId)
            "hysteria2", "hy2" -> parseHysteria2Uri(cleaned, defaultGroup, subscriptionId)
            "wireguard", "wg" -> parseWireGuardUri(cleaned, defaultGroup, subscriptionId)
            else -> Result.failure(IllegalArgumentException("Unsupported protocol scheme: $scheme://"))
        }
    }

    private fun parseVlessUri(
        uri: String,
        group: String,
        subscriptionId: Long?
    ): Result<ServerProfile> {
        return parseStandardUserHostUri(uri, VpnProtocol.VLESS, group, subscriptionId)
    }

    private fun parseTrojanUri(
        uri: String,
        group: String,
        subscriptionId: Long?
    ): Result<ServerProfile> {
        return parseStandardUserHostUri(uri, VpnProtocol.TROJAN, group, subscriptionId)
    }

    private fun parseHysteria2Uri(
        uri: String,
        group: String,
        subscriptionId: Long?
    ): Result<ServerProfile> {
        return parseStandardUserHostUri(uri, VpnProtocol.HYSTERIA2, group, subscriptionId)
    }

    private fun parseWireGuardUri(
        uri: String,
        group: String,
        subscriptionId: Long?
    ): Result<ServerProfile> {
        return parseStandardUserHostUri(uri, VpnProtocol.WIREGUARD, group, subscriptionId)
    }

    private fun parseStandardUserHostUri(
        uri: String,
        protocol: VpnProtocol,
        group: String,
        subscriptionId: Long?
    ): Result<ServerProfile> {
        val bodyAfterScheme = uri.substringAfter("://")
        val fragmentPart = if (bodyAfterScheme.contains("#")) {
            percentDecode(bodyAfterScheme.substringAfterLast("#")).trim()
        } else {
            ""
        }
        val withoutFragment = bodyAfterScheme.substringBeforeLast("#")
        val queryString = if (withoutFragment.contains("?")) {
            withoutFragment.substringAfter("?")
        } else {
            ""
        }
        val authority = withoutFragment.substringBefore("?").removeSuffix("/")

        val atIndex = authority.lastIndexOf('@')
        if (atIndex <= 0) {
            return Result.failure(IllegalArgumentException("${protocol.displayName} URI is missing credential before '@'"))
        }
        val credential = percentDecode(authority.substring(0, atIndex)).trim()
        if (credential.isEmpty()) {
            return Result.failure(IllegalArgumentException("${protocol.displayName} credential cannot be empty"))
        }
        val hostPortStr = authority.substring(atIndex + 1).trim()
        val (host, port) = parseHostAndPort(hostPortStr)
            ?: return Result.failure(IllegalArgumentException("Invalid server address or port in '$hostPortStr'"))

        val params = parseQueryParams(queryString)
        val network = TransportType.fromWire(params["type"] ?: params["network"])
        val defaultSec = if (protocol == VpnProtocol.TROJAN || protocol == VpnProtocol.HYSTERIA2) {
            "tls"
        } else {
            "none"
        }
        val security = SecurityType.fromWire(params["security"] ?: defaultSec)
        val encryption = params["encryption"] ?: if (protocol == VpnProtocol.VLESS) "none" else "auto"
        val flow = params["flow"] ?: ""
        val headerType = params["headerType"] ?: "none"
        val hostHeader = params["host"] ?: params["obfsParam"] ?: ""
        val path = params["path"] ?: ""
        val serviceName = params["serviceName"] ?: ""
        val sni = params["sni"] ?: params["peer"] ?: ""
        val alpn = params["alpn"] ?: ""
        val fp = params["fp"] ?: params["fingerprint"] ?: "chrome"
        val pbk = params["pbk"] ?: ""
        val sid = params["sid"] ?: ""
        val spx = params["spx"] ?: ""
        val wgPublicKey = params["publicKey"] ?: params["publickey"] ?: ""
        val wgReserved = params["reserved"] ?: ""
        val wgAddress = params["address"] ?: "172.16.0.2/32"
        val allowInsecure = params["allowInsecure"] == "1" ||
            params["allowInsecure"].equals("true", ignoreCase = true) ||
            params["insecure"] == "1"

        if (security == SecurityType.REALITY && pbk.isBlank()) {
            return Result.failure(IllegalArgumentException("REALITY security requires a valid public key (pbk)"))
        }

        val remarks = fragmentPart.ifBlank { "${protocol.displayName}-$host:$port" }

        return Result.success(
            ServerProfile(
                remarks = remarks,
                groupName = group,
                subscriptionId = subscriptionId,
                protocol = protocol,
                address = host,
                port = port,
                credential = credential,
                encryption = encryption,
                flow = flow,
                network = network,
                headerType = headerType,
                host = hostHeader,
                path = path,
                serviceName = serviceName,
                security = security,
                sni = sni,
                alpn = alpn,
                fingerprint = fp,
                realityPublicKey = pbk,
                realityShortId = sid,
                realitySpiderX = spx,
                wireGuardPublicKey = wgPublicKey,
                wireGuardReserved = wgReserved,
                wireGuardLocalAddress = wgAddress,
                allowInsecure = allowInsecure
            )
        )
    }

    private fun parseVmessUri(
        uri: String,
        group: String,
        subscriptionId: Long?
    ): Result<ServerProfile> {
        val payload = uri.substringAfter("://").trim()
        // Support standard user@host style vmess if someone uses it
        if (payload.contains("@") && !payload.startsWith("ey")) {
            return parseStandardUserHostUri(uri, VpnProtocol.VMESS, group, subscriptionId)
        }
        val decodedJson = decodeBase64Flexible(payload.substringBefore("#"))
            ?: return Result.failure(IllegalArgumentException("Invalid Base64 in vmess:// link"))

        return try {
            val obj = JSONObject(decodedJson)
            val address = obj.optString("add", "").trim().removeSurrounding("[", "]")
            val port = obj.optString("port", "0").trim().toIntOrNull() ?: obj.optInt("port", 0)
            val id = obj.optString("id", "").trim()
            if (address.isEmpty() || port !in 1..65535 || id.isEmpty()) {
                return Result.failure(IllegalArgumentException("VMess JSON missing valid add, port, or id"))
            }
            val ps = obj.optString("ps", "").trim().ifBlank { "VMess-$address:$port" }
            val aid = obj.optString("aid", "0").toIntOrNull() ?: obj.optInt("aid", 0)
            val scy = obj.optString("scy", obj.optString("security", "auto")).ifBlank { "auto" }
            val net = TransportType.fromWire(obj.optString("net", "tcp"))
            val type = obj.optString("type", "none").ifBlank { "none" }
            val host = obj.optString("host", "")
            val path = obj.optString("path", "")
            val tlsRaw = obj.optString("tls", "none")
            val security = SecurityType.fromWire(tlsRaw)
            val sni = obj.optString("sni", "")
            val alpn = obj.optString("alpn", "")
            val fp = obj.optString("fp", "chrome").ifBlank { "chrome" }
            val allowInsecure = obj.optBoolean("allowInsecure", false) || obj.optInt("allowInsecure", 0) == 1

            Result.success(
                ServerProfile(
                    remarks = ps,
                    groupName = group,
                    subscriptionId = subscriptionId,
                    protocol = VpnProtocol.VMESS,
                    address = address,
                    port = port,
                    credential = id,
                    encryption = scy,
                    alterId = aid,
                    network = net,
                    headerType = type,
                    host = host,
                    path = path,
                    serviceName = if (net == TransportType.GRPC) path else "",
                    security = security,
                    sni = sni,
                    alpn = alpn,
                    fingerprint = fp,
                    allowInsecure = allowInsecure
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Malformed VMess JSON: ${e.message}"))
        }
    }

    private fun parseShadowsocksUri(
        uri: String,
        group: String,
        subscriptionId: Long?
    ): Result<ServerProfile> {
        val afterScheme = uri.substringAfter("://").trim()
        val remarks = if (afterScheme.contains("#")) {
            percentDecode(afterScheme.substringAfterLast("#")).trim()
        } else {
            ""
        }
        val withoutFragment = afterScheme.substringBeforeLast("#")
        val queryPart = if (withoutFragment.contains("?")) withoutFragment.substringAfter("?") else ""
        val mainPart = withoutFragment.substringBefore("?").removeSuffix("/")

        val userInfo: String
        val hostPortPart: String

        if (mainPart.contains("@")) {
            // SIP002 or plaintext userinfo@host:port
            val rawUser = mainPart.substringBeforeLast("@")
            hostPortPart = mainPart.substringAfterLast("@")
            userInfo = if (rawUser.contains(":")) {
                percentDecode(rawUser)
            } else {
                decodeBase64Flexible(rawUser) ?: percentDecode(rawUser)
            }
        } else {
            // Legacy whole-URI Base64: ss://BASE64(method:password@host:port)#remarks
            val decodedAll = decodeBase64Flexible(mainPart)
                ?: return Result.failure(IllegalArgumentException("Invalid Base64 in legacy Shadowsocks URI"))
            if (!decodedAll.contains("@")) {
                return Result.failure(IllegalArgumentException("Shadowsocks URI missing '@host:port'"))
            }
            userInfo = decodedAll.substringBeforeLast("@")
            hostPortPart = decodedAll.substringAfterLast("@")
        }

        val colonIdx = userInfo.indexOf(':')
        if (colonIdx <= 0) {
            return Result.failure(IllegalArgumentException("Shadowsocks userinfo missing 'method:password'"))
        }
        val method = userInfo.substring(0, colonIdx).trim()
        val password = userInfo.substring(colonIdx + 1).trim()
        if (method.isEmpty() || password.isEmpty()) {
            return Result.failure(IllegalArgumentException("Shadowsocks cipher or password is empty"))
        }

        val (host, port) = parseHostAndPort(hostPortPart)
            ?: return Result.failure(IllegalArgumentException("Invalid Shadowsocks host or port: $hostPortPart"))

        val params = parseQueryParams(queryPart)
        return Result.success(
            ServerProfile(
                remarks = remarks.ifBlank { "SS-$host:$port" },
                groupName = group,
                subscriptionId = subscriptionId,
                protocol = VpnProtocol.SHADOWSOCKS,
                address = host,
                port = port,
                credential = password,
                encryption = method,
                network = TransportType.fromWire(params["type"] ?: "tcp"),
                host = params["host"] ?: "",
                path = params["path"] ?: "",
                security = SecurityType.fromWire(params["security"] ?: "none"),
                sni = params["sni"] ?: ""
            )
        )
    }

    /**
     * Parses IPv4, domain, or bracketed IPv6 `[2001:db8::1]:443`.
     */
    fun parseHostAndPort(hostPort: String): Pair<String, Int>? {
        val trimmed = hostPort.trim()
        if (trimmed.isEmpty()) return null

        val host: String
        val portStr: String

        if (trimmed.startsWith("[")) {
            val closeBracket = trimmed.indexOf(']')
            if (closeBracket <= 1 || closeBracket + 2 > trimmed.length || trimmed[closeBracket + 1] != ':') {
                return null
            }
            host = trimmed.substring(1, closeBracket).trim()
            portStr = trimmed.substring(closeBracket + 2).trim()
        } else {
            val lastColon = trimmed.lastIndexOf(':')
            if (lastColon <= 0 || lastColon == trimmed.length - 1) return null
            // Reject unbracketed IPv6 with multiple colons
            if (trimmed.indexOf(':') != lastColon) return null
            host = trimmed.substring(0, lastColon).trim()
            portStr = trimmed.substring(lastColon + 1).trim()
        }

        val port = portStr.toIntOrNull() ?: return null
        if (host.isEmpty() || host.contains(" ") || port !in 1..65535) return null
        return host to port
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split("&")) {
            if (pair.isBlank()) continue
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) {
                val key = percentDecode(pair.substring(0, eqIdx).trim())
                val value = percentDecode(pair.substring(eqIdx + 1).trim())
                // Keep first or last non-blank parameter cleanly
                if (key.isNotEmpty() && (!result.containsKey(key) || value.isNotEmpty())) {
                    result[key] = value
                }
            }
        }
        return result
    }

    /**
     * Parses raw Xray JSON configurations and extracts proxy outbounds into ServerProfiles.
     */
    private fun parseXrayJson(
        rawJson: String,
        defaultGroup: String,
        subscriptionId: Long?
    ): BatchParseResult {
        val profiles = mutableListOf<ServerProfile>()
        val failures = mutableListOf<ParseFailure>()
        try {
            val rootObjects = if (rawJson.trim().startsWith("[")) {
                val arr = JSONArray(rawJson)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            } else {
                listOf(JSONObject(rawJson))
            }

            for (root in rootObjects) {
                val remarksBase = root.optString("remarks", "").ifBlank { "Imported JSON" }
                val outbounds = root.optJSONArray("outbounds")
                if (outbounds == null || outbounds.length() == 0) {
                    failures.add(ParseFailure("JSON", "No 'outbounds' array found in Xray JSON"))
                    continue
                }
                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val protocolStr = ob.optString("protocol", "").lowercase()
                    if (protocolStr in listOf("freedom", "blackhole", "dns", "direct", "block")) continue
                    val protocol = VpnProtocol.fromScheme(protocolStr) ?: continue
                    val settings = ob.optJSONObject("settings") ?: continue
                    val stream = ob.optJSONObject("streamSettings")

                    val network = TransportType.fromWire(stream?.optString("network", "tcp"))
                    val security = SecurityType.fromWire(stream?.optString("security", "none"))
                    val tlsSettings = stream?.optJSONObject("tlsSettings")
                    val realitySettings = stream?.optJSONObject("realitySettings")
                    val wsSettings = stream?.optJSONObject("wsSettings")
                    val grpcSettings = stream?.optJSONObject("grpcSettings")

                    when (protocol) {
                        VpnProtocol.VLESS, VpnProtocol.VMESS -> {
                            val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: continue
                            val address = vnext.optString("address", "")
                            val port = vnext.optInt("port", 443)
                            val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: continue
                            val id = user.optString("id", "")
                            val enc = user.optString("encryption", user.optString("security", "none"))
                            val flow = user.optString("flow", "")
                            if (address.isNotEmpty() && id.isNotEmpty()) {
                                profiles.add(
                                    ServerProfile(
                                        remarks = ob.optString("tag", "").ifBlank { remarksBase },
                                        groupName = defaultGroup,
                                        subscriptionId = subscriptionId,
                                        protocol = protocol,
                                        address = address,
                                        port = port,
                                        credential = id,
                                        encryption = enc,
                                        flow = flow,
                                        network = network,
                                        host = wsSettings?.optJSONObject("headers")?.optString("Host", "") ?: "",
                                        path = wsSettings?.optString("path", "") ?: "",
                                        serviceName = grpcSettings?.optString("serviceName", "") ?: "",
                                        security = security,
                                        sni = tlsSettings?.optString("serverName", "")
                                            ?: realitySettings?.optString("serverName", "") ?: "",
                                        fingerprint = tlsSettings?.optString("fingerprint", "")
                                            ?: realitySettings?.optString("fingerprint", "chrome") ?: "chrome",
                                        realityPublicKey = realitySettings?.optString("publicKey", "") ?: "",
                                        realityShortId = realitySettings?.optString("shortId", "") ?: "",
                                        realitySpiderX = realitySettings?.optString("spiderX", "") ?: ""
                                    )
                                )
                            }
                        }
                        VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS -> {
                            val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: continue
                            val address = server.optString("address", "")
                            val port = server.optInt("port", 443)
                            val password = server.optString("password", "")
                            val method = server.optString("method", "aes-256-gcm")
                            if (address.isNotEmpty() && password.isNotEmpty()) {
                                profiles.add(
                                    ServerProfile(
                                        remarks = ob.optString("tag", "").ifBlank { remarksBase },
                                        groupName = defaultGroup,
                                        subscriptionId = subscriptionId,
                                        protocol = protocol,
                                        address = address,
                                        port = port,
                                        credential = password,
                                        encryption = method,
                                        network = network,
                                        security = security,
                                        sni = tlsSettings?.optString("serverName", "") ?: ""
                                    )
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }
        } catch (e: Exception) {
            failures.add(ParseFailure("JSON", "Invalid JSON syntax: ${e.message}"))
        }
        return BatchParseResult(profiles, failures, 0)
    }

    /**
     * Converts a ServerProfile back into a standard shareable URI (round-trip conversion).
     */
    fun toShareUri(profile: ServerProfile): String {
        val formattedHost = if (profile.address.contains(":") && !profile.address.startsWith("[")) {
            "[${profile.address}]"
        } else {
            profile.address
        }

        return when (profile.protocol) {
            VpnProtocol.VMESS -> {
                val json = JSONObject().apply {
                    put("v", "2")
                    put("ps", profile.remarks)
                    put("add", profile.address)
                    put("port", profile.port.toString())
                    put("id", profile.credential)
                    put("aid", profile.alterId.toString())
                    put("scy", profile.encryption.ifBlank { "auto" })
                    put("net", profile.network.wireName)
                    put("type", profile.headerType.ifBlank { "none" })
                    put("host", profile.host)
                    put("path", profile.path.ifBlank { profile.serviceName })
                    put("tls", if (profile.security == SecurityType.NONE) "" else profile.security.wireName)
                    put("sni", profile.sni)
                    put("alpn", profile.alpn)
                    put("fp", profile.fingerprint)
                }
                "vmess://${encodeBase64NoPad(json.toString(), urlSafe = false)}"
            }
            VpnProtocol.SHADOWSOCKS -> {
                val userInfo = "${profile.encryption}:${profile.credential}"
                val encodedUser = encodeBase64NoPad(userInfo, urlSafe = true)
                val encodedRemarks = percentEncode(profile.remarks)
                "ss://$encodedUser@$formattedHost:${profile.port}#$encodedRemarks"
            }
            else -> {
                val params = mutableListOf<String>()
                params.add("type=${percentEncode(profile.network.wireName)}")
                params.add("security=${percentEncode(profile.security.wireName)}")
                if (profile.protocol == VpnProtocol.VLESS) {
                    params.add("encryption=${percentEncode(profile.encryption.ifBlank { "none" })}")
                    if (profile.flow.isNotBlank()) params.add("flow=${percentEncode(profile.flow)}")
                }
                if (profile.host.isNotBlank()) params.add("host=${percentEncode(profile.host)}")
                if (profile.path.isNotBlank()) params.add("path=${percentEncode(profile.path)}")
                if (profile.serviceName.isNotBlank()) params.add("serviceName=${percentEncode(profile.serviceName)}")
                if (profile.sni.isNotBlank()) params.add("sni=${percentEncode(profile.sni)}")
                if (profile.alpn.isNotBlank()) params.add("alpn=${percentEncode(profile.alpn)}")
                if (profile.fingerprint.isNotBlank()) params.add("fp=${percentEncode(profile.fingerprint)}")
                if (profile.security == SecurityType.REALITY) {
                    if (profile.realityPublicKey.isNotBlank()) params.add("pbk=${percentEncode(profile.realityPublicKey)}")
                    if (profile.realityShortId.isNotBlank()) params.add("sid=${percentEncode(profile.realityShortId)}")
                    if (profile.realitySpiderX.isNotBlank()) params.add("spx=${percentEncode(profile.realitySpiderX)}")
                }
                if (profile.protocol == VpnProtocol.WIREGUARD && profile.wireGuardPublicKey.isNotBlank()) {
                    params.add("publicKey=${percentEncode(profile.wireGuardPublicKey)}")
                    params.add("address=${percentEncode(profile.wireGuardLocalAddress)}")
                }
                if (profile.allowInsecure) params.add("allowInsecure=1")

                val query = params.joinToString("&")
                val encodedCred = percentEncode(profile.credential)
                val encodedRemarks = percentEncode(profile.remarks)
                "${profile.protocol.scheme}://$encodedCred@$formattedHost:${profile.port}?$query#$encodedRemarks"
            }
        }
    }
}
