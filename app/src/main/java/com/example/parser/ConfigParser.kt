package com.example.parser

import com.example.domain.model.ClipboardInputType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.SubscriptionFormat
import com.example.domain.model.TransportType
import com.example.domain.model.VpnProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
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
    val duplicatesSkipped: Int,
    val detectedFormat: SubscriptionFormat = SubscriptionFormat.PLAIN_TEXT,
    val totalReceivedCount: Int = profiles.size + duplicatesSkipped + failures.size
)

/**
 * Pure Kotlin VPN Configuration Parser, Clipboard Classifier & Subscription Format Detector.
 * Supports VLESS, VMess (v2rayN JSON & URI), Trojan, Shadowsocks (SIP002, Legacy Base64, 2022 Blake3),
 * Hysteria2 (hy2:// & hysteria2://), WireGuard (wireguard://), Xray/sing-box JSON outbounds,
 * and Standard / URL-safe Base64 subscription payloads.
 */
object ConfigParser {

    private val supportedSchemes = listOf(
        "vless://", "vmess://", "trojan://", "ss://",
        "hysteria2://", "hy2://", "wireguard://", "tuic://"
    )

    private val htmlIndicatorRegex = Regex(
        pattern = "(<!doctype\\s+html|<html\\b|<head\\b|<body\\b|<title\\b|<form\\b|<script\\b|cloudflare|403\\s+forbidden|404\\s+not\\s+found)",
        option = RegexOption.IGNORE_CASE
    )

    /**
     * Inspects clipboard or pasted text and classifies the input:
     * - https:// or http:// -> SubscriptionUrl
     * - vless://, vmess://, trojan://, ss://, hysteria2:// -> SingleShareLink
     * - Multiple supported share links -> BulkShareLinks
     * - Base64 text -> Base64Subscription (safe single-pass format detection)
     * - JSON -> JsonSubscription
     * - Empty or Unrecognized -> Empty / Unrecognized
     */
    fun classifyClipboardInput(rawClipboard: String): ClipboardInputType {
        val trimmed = rawClipboard.trim()
        if (trimmed.isEmpty()) {
            return ClipboardInputType.Empty
        }

        val nonCommentLines = trimmed
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

        if (nonCommentLines.isEmpty()) {
            return ClipboardInputType.Empty
        }

        // 1. Check if input is a single HTTP or HTTPS subscription URL
        if (nonCommentLines.size == 1) {
            val single = nonCommentLines.first()
            if (single.startsWith("https://", ignoreCase = true) ||
                single.startsWith("http://", ignoreCase = true)
            ) {
                val parsedUrl = validateSubscriptionUrl(single)
                if (parsedUrl.isSuccess) {
                    val uri = parsedUrl.getOrThrow()
                    val fragmentTitle = single.substringAfter("#", "").let {
                        if (it.isNotBlank()) percentDecode(it).trim() else ""
                    }
                    val hostTitle = uri.host?.lowercase().orEmpty()
                    val suggestedTitle = fragmentTitle.ifBlank {
                        if (hostTitle.isNotBlank()) "Sub ($hostTitle)" else "Subscription"
                    }
                    val cleanUrl = single.substringBefore("#").trim()
                    return ClipboardInputType.SubscriptionUrl(
                        url = cleanUrl,
                        suggestedTitle = suggestedTitle,
                        isHttps = cleanUrl.startsWith("https://", ignoreCase = true)
                    )
                } else {
                    val err = parsedUrl.exceptionOrNull()?.message ?: "Invalid subscription URL"
                    return ClipboardInputType.Unrecognized(
                        rawSnippet = single.take(64),
                        reasonFa = "آدرس لینک اشتراک نامعتبر است: $err",
                        reasonEn = "Invalid subscription URL: $err"
                    )
                }
            }
        }

        // 2. Check if input consists of one or more supported share links
        val splitShareLinks = nonCommentLines
            .flatMap { splitMultipleUrisInLine(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val shareLinksOnly = splitShareLinks.filter { line ->
            val lower = line.lowercase()
            supportedSchemes.any { lower.startsWith(it) }
        }

        if (shareLinksOnly.isNotEmpty() && shareLinksOnly.size == splitShareLinks.size) {
            if (shareLinksOnly.size == 1) {
                val singleUri = shareLinksOnly.first()
                val scheme = singleUri.substringBefore("://").lowercase()
                val protocol = VpnProtocol.fromScheme(scheme) ?: VpnProtocol.VLESS
                return ClipboardInputType.SingleShareLink(protocol = protocol, uri = singleUri)
            }
            return ClipboardInputType.BulkShareLinks(links = shareLinksOnly)
        }

        // If some lines are share links and some are other lines, treat as bulk share links if at least one valid scheme exists
        if (shareLinksOnly.isNotEmpty()) {
            return ClipboardInputType.BulkShareLinks(links = splitShareLinks)
        }

        // 3. Check if input is JSON subscription / config
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return if (isCompatibleJsonPayload(trimmed)) {
                ClipboardInputType.JsonSubscription(rawJson = trimmed)
            } else {
                ClipboardInputType.Unrecognized(
                    rawSnippet = trimmed.take(64),
                    reasonFa = "ساختار JSON کلیپ‌بورد با فرمت کانفیگ‌های VPN سازگار نیست",
                    reasonEn = "Clipboard JSON is not a compatible VPN configuration"
                )
            }
        }

        // 4. Reject HTML pages early
        if (isHtmlPayload(trimmed)) {
            return ClipboardInputType.Unrecognized(
                rawSnippet = "HTML Document",
                reasonFa = "محتوای کلیپ‌بورد یک صفحه وب (HTML) است، نه کانفیگ VPN",
                reasonEn = "Clipboard contains an HTML webpage instead of VPN configuration"
            )
        }

        // 5. Attempt safe single-pass Base64 subscription detection
        val isUrlSafeCandidate = trimmed.contains('-') || trimmed.contains('_') ||
            (trimmed.replace("\r", "").replace("\n", "").replace(" ", "").length % 4 != 0)
        val decoded = decodeBase64Flexible(trimmed)
        if (decoded != null && !isHtmlPayload(decoded)) {
            val decodedTrimmed = decoded.trim()
            if (containsAnyScheme(decodedTrimmed) || isCompatibleJsonPayload(decodedTrimmed)) {
                return ClipboardInputType.Base64Subscription(
                    rawBase64 = trimmed,
                    decodedText = decodedTrimmed,
                    isUrlSafe = isUrlSafeCandidate
                )
            }
        }

        return ClipboardInputType.Unrecognized(
            rawSnippet = trimmed.take(64),
            reasonFa = "لینک اشتراک یا کانفیگ معتبری در کلیپ‌بورد شناسایی نشد",
            reasonEn = "No valid subscription URL or VPN configuration detected in clipboard"
        )
    }

    /**
     * Validates an HTTP/HTTPS subscription URL including custom ports (e.g. :2096) and /sub/{token} paths.
     */
    fun validateSubscriptionUrl(rawUrl: String): Result<URI> {
        val cleaned = rawUrl.trim().substringBefore("#").trim()
        if (cleaned.isEmpty() || cleaned.contains(" ") || cleaned.contains("\n")) {
            return Result.failure(IllegalArgumentException("URL contains invalid whitespace"))
        }
        return try {
            val uri = URI(cleaned)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && scheme != "http") {
                return Result.failure(IllegalArgumentException("Unsupported URL scheme (expected https:// or http://)"))
            }
            val host = uri.host
            if (host.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Subscription URL is missing a valid host"))
            }
            val port = uri.port
            if (port != -1 && port !in 1..65535) {
                return Result.failure(IllegalArgumentException("Invalid port number: $port"))
            }
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Malformed subscription URL: ${e.message}"))
        }
    }

    /**
     * Automatically identifies subscription response format in a single safe pass:
     * A. Plain-text subscription (multiple share links separated by newline)
     * B. Standard Base64 subscription
     * C. URL-safe Base64 subscription (URL-safe chars '-', '_' or missing '=' padding)
     * D. JSON subscription (Xray / sing-box outbounds or array of configs)
     * E. Invalid response (HTML login pages, error pages, empty or unsupported formats)
     */
    fun detectSubscriptionFormat(rawBody: String): Pair<SubscriptionFormat, String> {
        val trimmed = rawBody.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) {
            return SubscriptionFormat.INVALID to ""
        }

        // E. Detect HTML login pages, captive portals, and error pages
        if (isHtmlPayload(trimmed)) {
            return SubscriptionFormat.INVALID to trimmed
        }

        // A. Detect plain-text share links directly (note: vmess:// links start with "vmess://",
        // so their inner Base64 JSON is NOT decoded at the subscription outer layer!)
        if (containsAnySchemeAtLineStart(trimmed)) {
            return SubscriptionFormat.PLAIN_TEXT to trimmed
        }

        // D. Detect compatible JSON subscription formats
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return if (isCompatibleJsonPayload(trimmed)) {
                SubscriptionFormat.JSON to trimmed
            } else {
                SubscriptionFormat.INVALID to trimmed
            }
        }

        // B & C. Single-pass Base64 decoding (Standard or URL-safe Base64)
        val compact = trimmed.replace("\r", "").replace("\n", "").replace(" ", "")
        val hasUrlSafeChars = compact.contains('-') || compact.contains('_')
        val hasMissingPadding = (compact.length % 4 != 0) && !compact.endsWith("=")
        val decoded = decodeBase64Flexible(compact)
        if (decoded != null) {
            val decodedTrimmed = decoded.trim().removePrefix("\uFEFF").trim()
            if (isHtmlPayload(decodedTrimmed)) {
                return SubscriptionFormat.INVALID to decodedTrimmed
            }
            if (containsAnyScheme(decodedTrimmed) || isCompatibleJsonPayload(decodedTrimmed)) {
                val format = if (hasUrlSafeChars || hasMissingPadding) {
                    SubscriptionFormat.BASE64_URL_SAFE
                } else {
                    SubscriptionFormat.BASE64_STANDARD
                }
                return format to decodedTrimmed
            }
        }

        return SubscriptionFormat.INVALID to trimmed
    }

    fun isHtmlPayload(text: String): Boolean {
        val sample = text.trim().take(1024)
        if (sample.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            sample.startsWith("<html", ignoreCase = true) ||
            sample.startsWith("<?xml", ignoreCase = true)
        ) {
            return true
        }
        return sample.startsWith("<") && htmlIndicatorRegex.containsMatchIn(sample)
    }

    private fun isCompatibleJsonPayload(text: String): Boolean {
        val trimmed = text.trim()
        return try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                if (arr.length() == 0) return false
                val first = arr.get(0)
                when (first) {
                    is String -> containsAnyScheme(first)
                    is JSONObject -> first.has("outbounds") || first.has("protocol") || first.has("type") || first.has("server") || first.has("address")
                    else -> false
                }
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                obj.has("outbounds") || obj.has("servers") || obj.has("profiles") || obj.has("links") ||
                    (obj.has("protocol") && obj.has("settings")) ||
                    (obj.has("type") && obj.has("server"))
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Decodes standard or URL-safe Base64 with or without padding, ignoring whitespace.
     */
    fun decodeBase64Flexible(input: String): String? {
        val cleaned = input.trim().replace("\r", "").replace("\n", "").replace("\t", "").replace(" ", "")
        if (cleaned.length < 4) return null
        // Must only contain valid Base64 / URL-safe Base64 characters
        if (!cleaned.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '-' || it == '_' || it == '=' }) {
            return null
        }
        return try {
            val normalized = cleaned
                .replace('-', '+')
                .replace('_', '/')
                .trimEnd('=')
            val padNeeded = (4 - (normalized.length % 4)) % 4
            if (normalized.length % 4 == 1) return null
            val padded = normalized + "=".repeat(padNeeded)
            val bytes = Base64.getDecoder().decode(padded)
            val decoded = String(bytes, Charsets.UTF_8)
            // Reject binary garbage if it contains NUL or excessive control characters
            if (decoded.any { it == '\u0000' || (it.code in 1..8) || (it.code in 14..31) }) null else decoded
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
     * Base64-encoded subscription lists, or a compatible JSON configuration.
     */
    fun parseBatch(
        rawInput: String,
        defaultGroup: String = "Default",
        subscriptionId: Long? = null,
        existingFingerprintKeys: Set<String> = emptySet()
    ): BatchParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return BatchParseResult(
                profiles = emptyList(),
                failures = listOf(ParseFailure("", "Empty input (پاسخ دریافتی خالی است)")),
                duplicatesSkipped = 0,
                detectedFormat = SubscriptionFormat.INVALID,
                totalReceivedCount = 0
            )
        }

        // Reject HTML bodies (e.g. captive portals, login pages, or error pages from subscription links)
        if (isHtmlPayload(trimmed)) {
            return BatchParseResult(
                profiles = emptyList(),
                failures = listOf(ParseFailure("HTML Document", "Received HTML webpage instead of VPN configuration (دریافت صفحه وب HTML به جای کانفیگ)")),
                duplicatesSkipped = 0,
                detectedFormat = SubscriptionFormat.INVALID,
                totalReceivedCount = 0
            )
        }

        val (detectedFormat, candidateText) = detectSubscriptionFormat(trimmed)

        if (detectedFormat == SubscriptionFormat.INVALID && !containsAnyScheme(trimmed)) {
            // Still parse line-by-line if user pasted malformed share link(s) so each line failure is reported accurately
            val rawLines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            val lineFailures = rawLines.map { line ->
                val err = parseSingleUri(line, defaultGroup, subscriptionId).exceptionOrNull()?.message
                    ?: "Unsupported or invalid configuration format"
                ParseFailure(line.take(64), err)
            }
            return BatchParseResult(
                profiles = emptyList(),
                failures = lineFailures.ifEmpty { listOf(ParseFailure(trimmed.take(64), "Unsupported subscription format")) },
                duplicatesSkipped = 0,
                detectedFormat = SubscriptionFormat.INVALID,
                totalReceivedCount = lineFailures.size
            )
        }

        // Check if resolved payload is JSON object or array
        val trimmedCandidate = candidateText.trim()
        if (trimmedCandidate.startsWith("{") || trimmedCandidate.startsWith("[")) {
            val jsonResult = parseXrayJson(trimmedCandidate, defaultGroup, subscriptionId)
            if (jsonResult.profiles.isNotEmpty() || jsonResult.failures.isNotEmpty()) {
                return deduplicate(
                    profiles = jsonResult.profiles,
                    failures = jsonResult.failures,
                    existingKeys = existingFingerprintKeys,
                    detectedFormat = if (detectedFormat == SubscriptionFormat.PLAIN_TEXT) SubscriptionFormat.JSON else detectedFormat
                )
            }
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

        return deduplicate(parsedProfiles, failures, existingFingerprintKeys, detectedFormat)
    }

    private fun deduplicate(
        profiles: List<ServerProfile>,
        failures: List<ParseFailure>,
        existingKeys: Set<String>,
        detectedFormat: SubscriptionFormat = SubscriptionFormat.PLAIN_TEXT
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
        val totalReceived = unique.size + skipped + failures.size
        return BatchParseResult(
            profiles = unique,
            failures = failures,
            duplicatesSkipped = skipped,
            detectedFormat = detectedFormat,
            totalReceivedCount = totalReceived
        )
    }

    private fun containsAnyScheme(text: String): Boolean {
        val lower = text.lowercase()
        return supportedSchemes.any { lower.contains(it) }
    }

    private fun containsAnySchemeAtLineStart(text: String): Boolean {
        return text.lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .any { line -> supportedSchemes.any { scheme -> line.startsWith(scheme) } }
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
            return Result.failure(IllegalArgumentException("Missing URI scheme (expected vless://, vmess://, trojan://, ss://, hysteria2://)"))
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
        // Support both hysteria2://password@host:port?... and hysteria2://host:port?auth=password&...
        val bodyAfterScheme = uri.substringAfter("://")
        val withoutFragment = bodyAfterScheme.substringBeforeLast("#")
        val authority = withoutFragment.substringBefore("?").removeSuffix("/")
        if (!authority.contains("@") && withoutFragment.contains("?")) {
            val query = withoutFragment.substringAfter("?")
            val params = parseQueryParams(query)
            val authPassword = params["auth"] ?: params["password"]
            if (!authPassword.isNullOrBlank()) {
                val scheme = uri.substringBefore("://")
                val normalizedUri = "$scheme://${percentEncode(authPassword)}@$bodyAfterScheme"
                return parseStandardUserHostUri(normalizedUri, VpnProtocol.HYSTERIA2, group, subscriptionId)
            }
        }
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

        if (protocol == VpnProtocol.VLESS || protocol == VpnProtocol.VMESS || protocol == VpnProtocol.TUIC) {
            if (!com.example.core.xray.XrayConfigBuilder.isValidUuid(credential)) {
                return Result.failure(
                    IllegalArgumentException("${protocol.displayName} credential must be a valid UUID (8-4-4-4-12 hex)")
                )
            }
        }

        if (security == SecurityType.REALITY) {
            if (pbk.isBlank()) {
                return Result.failure(IllegalArgumentException("REALITY security requires a valid public key (pbk)"))
            }
            if (sni.isBlank()) {
                return Result.failure(IllegalArgumentException("REALITY security requires a target SNI (sni)"))
            }
            if (sid.isNotBlank() && !sid.matches(Regex("^[0-9a-fA-F]{1,16}$"))) {
                return Result.failure(IllegalArgumentException("REALITY shortId (sid) must be 1..16 hexadecimal characters"))
            }
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
            if (!com.example.core.xray.XrayConfigBuilder.isValidUuid(id)) {
                return Result.failure(IllegalArgumentException("VMess JSON 'id' is not a valid UUID"))
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
     * Parses compatible subscription JSON configurations (Xray JSON outbounds, sing-box outbounds,
     * or JSON arrays of share links) and extracts proxy outbounds into ServerProfiles.
     */
    private fun parseXrayJson(
        rawJson: String,
        defaultGroup: String,
        subscriptionId: Long?
    ): BatchParseResult {
        val profiles = mutableListOf<ServerProfile>()
        val failures = mutableListOf<ParseFailure>()
        try {
            val trimmed = rawJson.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                // Support JSON array of share link strings ["vless://...", "vmess://..."]
                if (arr.length() > 0 && arr.opt(0) is String) {
                    for (i in 0 until arr.length()) {
                        val link = arr.optString(i, "").trim()
                        if (link.isNotEmpty()) {
                            parseSingleUri(link, defaultGroup, subscriptionId).fold(
                                onSuccess = { profiles.add(it) },
                                onFailure = { err -> failures.add(ParseFailure(link.take(64), err.message ?: "Invalid URI in JSON array")) }
                            )
                        }
                    }
                    return BatchParseResult(profiles, failures, 0, SubscriptionFormat.JSON)
                }
            }

            val rootObjects = if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            } else {
                listOf(JSONObject(trimmed))
            }

            for (root in rootObjects) {
                // Support JSON object containing a "links" or "profiles" string array
                val linksArr = root.optJSONArray("links") ?: root.optJSONArray("profiles")
                if (linksArr != null && linksArr.length() > 0 && linksArr.opt(0) is String) {
                    for (i in 0 until linksArr.length()) {
                        val link = linksArr.optString(i, "").trim()
                        if (link.isNotEmpty()) {
                            parseSingleUri(link, defaultGroup, subscriptionId).fold(
                                onSuccess = { profiles.add(it) },
                                onFailure = { err -> failures.add(ParseFailure(link.take(64), err.message ?: "Invalid URI in JSON")) }
                            )
                        }
                    }
                    continue
                }

                val remarksBase = root.optString("remarks", "").ifBlank { "Imported JSON" }
                val outbounds = root.optJSONArray("outbounds")
                if (outbounds == null || outbounds.length() == 0) {
                    failures.add(ParseFailure("JSON", "No 'outbounds' array found in JSON configuration"))
                    continue
                }
                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val protocolStr = ob.optString("protocol", ob.optString("type", "")).lowercase()
                    if (protocolStr in listOf("freedom", "blackhole", "dns", "direct", "block", "selector", "urltest")) continue
                    val protocol = VpnProtocol.fromScheme(protocolStr) ?: continue

                    // Handle sing-box style outbound ("server", "server_port", "uuid"/"password")
                    if (!ob.has("settings") && ob.has("server")) {
                        val serverHost = ob.optString("server", "").trim()
                        val serverPort = ob.optInt("server_port", 443)
                        val cred = ob.optString("uuid", ob.optString("password", "")).trim()
                        val tlsObj = ob.optJSONObject("tls")
                        val transportObj = ob.optJSONObject("transport")
                        val realityObj = tlsObj?.optJSONObject("reality")
                        val sec = when {
                            realityObj?.optBoolean("enabled", false) == true -> SecurityType.REALITY
                            tlsObj?.optBoolean("enabled", false) == true || protocol == VpnProtocol.HYSTERIA2 -> SecurityType.TLS
                            else -> SecurityType.NONE
                        }
                        val net = TransportType.fromWire(transportObj?.optString("type", "tcp"))
                        if (serverHost.isNotEmpty() && cred.isNotEmpty() && serverPort in 1..65535) {
                            profiles.add(
                                ServerProfile(
                                    remarks = ob.optString("tag", "").ifBlank { remarksBase },
                                    groupName = defaultGroup,
                                    subscriptionId = subscriptionId,
                                    protocol = protocol,
                                    address = serverHost,
                                    port = serverPort,
                                    credential = cred,
                                    encryption = ob.optString("method", ob.optString("security", "none")),
                                    flow = ob.optString("flow", ""),
                                    network = net,
                                    path = transportObj?.optString("path", "") ?: "",
                                    serviceName = transportObj?.optString("service_name", "") ?: "",
                                    security = sec,
                                    sni = tlsObj?.optString("server_name", "") ?: "",
                                    realityPublicKey = realityObj?.optString("public_key", "") ?: "",
                                    realityShortId = realityObj?.optString("short_id", "") ?: ""
                                )
                            )
                        }
                        continue
                    }

                    val settings = ob.optJSONObject("settings") ?: continue
                    val stream = ob.optJSONObject("streamSettings")

                    val network = TransportType.fromWire(stream?.optString("network", "tcp"))
                    val security = SecurityType.fromWire(stream?.optString("security", "none"))
                    val tlsSettings = stream?.optJSONObject("tlsSettings")
                    val realitySettings = stream?.optJSONObject("realitySettings")
                    val wsSettings = stream?.optJSONObject("wsSettings")
                    val grpcSettings = stream?.optJSONObject("grpcSettings")
                    val httpUpgradeSettings = stream?.optJSONObject("httpupgradeSettings")
                    val xhttpSettings = stream?.optJSONObject("xhttpSettings") ?: stream?.optJSONObject("splithttpSettings")

                    val extractedHost = wsSettings?.optString("host", "")?.takeIf { it.isNotBlank() }
                        ?: wsSettings?.optJSONObject("headers")?.optString("Host", "")?.takeIf { it.isNotBlank() }
                        ?: httpUpgradeSettings?.optString("host", "")?.takeIf { it.isNotBlank() }
                        ?: xhttpSettings?.optString("host", "")
                        ?: ""
                    val extractedPath = wsSettings?.optString("path", "")?.takeIf { it.isNotBlank() }
                        ?: httpUpgradeSettings?.optString("path", "")?.takeIf { it.isNotBlank() }
                        ?: xhttpSettings?.optString("path", "")
                        ?: ""

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
                                        host = extractedHost,
                                        path = extractedPath,
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
                        VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS, VpnProtocol.HYSTERIA2 -> {
                            val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: continue
                            val address = server.optString("address", "")
                            val port = server.optInt("port", 443)
                            val password = server.optString("password", server.optString("auth", ""))
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
                                        security = if (protocol == VpnProtocol.HYSTERIA2 && security == SecurityType.NONE) SecurityType.TLS else security,
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
        return BatchParseResult(profiles, failures, 0, SubscriptionFormat.JSON)
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
