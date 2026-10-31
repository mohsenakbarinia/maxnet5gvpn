package com.example.core.xray

import com.example.domain.model.AppSettings
import com.example.domain.model.OutboundTag
import com.example.domain.model.RoutingMode
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.RoutingRuleType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType
import com.example.domain.model.ValidationResult
import com.example.domain.model.VpnProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * Programmatic Xray-core v26.10.29 Configuration Builder & Strict Validator.
 * Generates complete, standards-compliant Xray JSON configurations and validates
 * protocol, UUID, port, TLS, REALITY, WebSocket, gRPC, HTTPUpgrade, DNS, and routing rules.
 */
object XrayConfigBuilder {

    private val supportedProtocols = setOf(
        VpnProtocol.VLESS,
        VpnProtocol.VMESS,
        VpnProtocol.TROJAN,
        VpnProtocol.SHADOWSOCKS,
        VpnProtocol.WIREGUARD
    )

    private val uuidRegex = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$|^[0-9a-fA-F]{32}$"
    )

    private val shortIdHexRegex = Regex("^[0-9a-fA-F]{0,16}$")

    private val validVlessFlows = setOf(
        "",
        "xtls-rprx-vision",
        "xtls-rprx-vision-udp443"
    )

    private val validShadowsocksCiphers = setOf(
        "aes-128-gcm",
        "aes-256-gcm",
        "chacha20-poly1305",
        "chacha20-ietf-poly1305",
        "xchacha20-poly1305",
        "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
        "none",
        "plain"
    )

    fun isValidUuid(value: String): Boolean {
        return uuidRegex.matches(value.trim())
    }

    fun buildAndValidate(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem> = emptyList(),
        localSocksPort: Int = 10808,
        includeNativeTunInbound: Boolean = false,
        verifyWithHostBinary: Boolean = false
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (profile.protocol !in supportedProtocols) {
            errors.add(
                "پروتکل ${profile.protocol.displayName} توسط موتور Xray-core پشتیبانی نمی‌شود (Protocol ${profile.protocol.displayName} is not supported by Xray-core)."
            )
        }
        val cleanAddress = profile.address.trim().removeSurrounding("[", "]")
        if (cleanAddress.isBlank() || cleanAddress.contains(" ")) {
            errors.add("آدرس سرور نمی‌تواند خالی یا دارای فاصله باشد (Server address cannot be empty or contain spaces).")
        }
        if (profile.port !in 1..65535) {
            errors.add("پورت سرور باید بین 1 تا 65535 باشد (Server port must be between 1 and 65535, got ${profile.port}).")
        }
        if (profile.credential.isBlank()) {
            errors.add("شناسه کاربری / رمز عبور (${profile.protocol.displayName}) نمی‌تواند خالی باشد (Credential cannot be empty).")
        } else if (profile.protocol == VpnProtocol.VLESS || profile.protocol == VpnProtocol.VMESS) {
            if (!isValidUuid(profile.credential)) {
                errors.add(
                    "فرمت UUID در پروفایل ${profile.protocol.displayName} نامعتبر است (Invalid UUID format for ${profile.protocol.displayName})."
                )
            }
        }

        if (localSocksPort !in 1024..65535) {
            errors.add("Local SOCKS port must be an unprivileged port between 1024 and 65535.")
        }

        if (profile.protocol == VpnProtocol.SHADOWSOCKS) {
            val cipher = profile.encryption.trim().lowercase().ifBlank { "aes-256-gcm" }
            if (cipher !in validShadowsocksCiphers) {
                errors.add("روش رمزنگاری Shadowsocks پشتیبانی نمی‌شود (Unsupported Shadowsocks cipher: ${profile.encryption}).")
            }
        }

        // Validate REALITY security parameters (never fabricate missing security credentials)
        if (profile.security == SecurityType.REALITY) {
            if (profile.protocol != VpnProtocol.VLESS && profile.protocol != VpnProtocol.TROJAN) {
                errors.add("امنیت REALITY فقط با پروتکل‌های VLESS و Trojan سازگار است (REALITY is only supported with VLESS or Trojan).")
            }
            if (profile.realityPublicKey.isBlank()) {
                errors.add("امنیت REALITY نیازمند کلید عمومی معتبر (pbk / Public Key) است (REALITY requires a valid public key pbk).")
            }
            val effectiveSni = profile.sni.ifBlank { profile.host }
            if (effectiveSni.isBlank()) {
                errors.add("امنیت REALITY نیازمند مقدار معتبر SNI (serverName) است (REALITY requires a valid SNI / serverName).")
            }
            if (!shortIdHexRegex.matches(profile.realityShortId.trim())) {
                errors.add("مقدار ShortId (sid) در REALITY باید رشته هگزادسیمال حداکثر ۱۶ کاراکتری باشد (Invalid REALITY shortId hex format).")
            }
            if (profile.network !in setOf(TransportType.TCP, TransportType.GRPC, TransportType.H2, TransportType.XHTTP)) {
                errors.add("REALITY is incompatible with ${profile.network.displayName} transport.")
            }
        }

        // Validate VLESS flow
        if (profile.flow.isNotBlank()) {
            if (profile.protocol != VpnProtocol.VLESS) {
                errors.add("پارامتر Flow (${profile.flow}) فقط در پروتکل VLESS پشتیبانی می‌شود (Flow is only supported on VLESS).")
            } else if (profile.flow.trim() !in validVlessFlows) {
                errors.add("مقدار Flow (${profile.flow}) در Xray-core پشتیبانی نمی‌شود؛ از xtls-rprx-vision استفاده کنید (Unsupported VLESS flow: ${profile.flow}).")
            }
            if (profile.network != TransportType.TCP && profile.network != TransportType.MKCP) {
                errors.add("VLESS flow (${profile.flow}) requires TCP transport with TLS or REALITY.")
            }
            if (profile.security == SecurityType.NONE) {
                errors.add("VLESS flow (${profile.flow}) requires TLS or REALITY security.")
            }
        }

        if (profile.protocol == VpnProtocol.WIREGUARD && profile.wireGuardPublicKey.isBlank()) {
            errors.add("WireGuard outbound requires a server peer Public Key.")
        }

        if (profile.allowInsecure && profile.security == SecurityType.TLS) {
            warnings.add("AllowInsecure is enabled: TLS certificate verification is bypassed for this profile.")
        }

        if (errors.isNotEmpty()) {
            return ValidationResult(
                isValid = false,
                errors = errors,
                warnings = warnings,
                generatedConfigJson = ""
            )
        }

        val root = JSONObject()
        root.put("remarks", profile.remarks)

        // 1. Log configuration
        root.put(
            "log",
            JSONObject().apply {
                put("loglevel", "warning")
                put("dnsLog", false)
            }
        )

        // 2. Stats & Policy configuration (required for CoreController.queryAllOutboundTrafficStats)
        root.put("stats", JSONObject())
        root.put(
            "policy",
            JSONObject().apply {
                put(
                    "levels",
                    JSONObject().put(
                        "8",
                        JSONObject().apply {
                            put("handshake", 4)
                            put("connIdle", 300)
                            put("uplinkOnly", 1)
                            put("downlinkOnly", 1)
                        }
                    )
                )
                put(
                    "system",
                    JSONObject().apply {
                        put("statsInboundUplink", true)
                        put("statsInboundDownlink", true)
                        put("statsOutboundUplink", true)
                        put("statsOutboundDownlink", true)
                    }
                )
            }
        )

        // 3. DNS configuration
        if (settings.enableFakeDns) {
            root.put(
                "fakedns",
                JSONArray().put(
                    JSONObject().apply {
                        put("ipPool", "198.18.0.0/15")
                        put("poolSize", 65535)
                    }
                )
            )
        }
        root.put("dns", buildDnsObject(profile, settings))

        // 4. Inbounds (Strictly bound to 127.0.0.1 + optional Xray native tun inbound)
        root.put(
            "inbounds",
            buildInboundsArray(
                localSocksPort = localSocksPort,
                enableFakeDns = settings.enableFakeDns,
                mtu = settings.mtu,
                includeNativeTunInbound = includeNativeTunInbound
            )
        )

        // 5. Outbounds (proxy, direct, block, dns-out)
        root.put("outbounds", buildOutboundsArray(profile, settings))

        // 6. Routing configuration
        root.put("routing", buildRoutingObject(profile, settings, customRules))

        val configString = root.toString(2)

        // 7. If host Xray-core binary is available and explicitly requested, run engine-level check
        if (verifyWithHostBinary) {
            val engineCheck = XrayNativeBridge.validateConfigWithEngine(configString)
            if (engineCheck.isFailure) {
                return ValidationResult(
                    isValid = false,
                    errors = listOf(engineCheck.exceptionOrNull()?.message ?: "Xray engine rejected configuration"),
                    warnings = warnings,
                    generatedConfigJson = configString
                )
            }
        }

        return ValidationResult(
            isValid = true,
            errors = emptyList(),
            warnings = warnings,
            generatedConfigJson = configString
        )
    }

    /**
     * Builds a minimal outbound-only Xray configuration for `Libv2ray.measureOutboundDelay`.
     */
    fun buildSpeedtestConfig(profile: ServerProfile, settings: AppSettings): ValidationResult {
        val full = buildAndValidate(profile, settings, emptyList(), 10808, false)
        if (!full.isValid) return full
        val root = JSONObject()
        root.put("remarks", profile.remarks)
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put("outbounds", buildOutboundsArray(profile, settings))
        return ValidationResult(
            isValid = true,
            errors = emptyList(),
            warnings = full.warnings,
            generatedConfigJson = root.toString(2)
        )
    }

    private fun countryDomainRules(countryCode: String): JSONArray {
        return when (val cc = countryCode.trim().lowercase()) {
            "ir" -> JSONArray().put("domain:ir").put("geosite:category-ir")
            "ru" -> JSONArray().put("domain:ru").put("geosite:category-ru")
            "cn" -> JSONArray().put("geosite:cn")
            else -> JSONArray().put("domain:$cc")
        }
    }

    private fun buildDnsObject(profile: ServerProfile, settings: AppSettings): JSONObject {
        val servers = JSONArray()
        if (settings.enableFakeDns) {
            servers.put("fakedns")
        }

        val cleanAddress = profile.address.trim().removeSurrounding("[", "]")
        // Prevent DNS loop: resolve the proxy server's own domain using direct DNS
        if (!isIpLiteral(cleanAddress)) {
            servers.put(
                JSONObject().apply {
                    put("address", settings.directDns.ifBlank { "1.1.1.1" })
                    put("domains", JSONArray().put("full:$cleanAddress"))
                    put("skipFallback", true)
                }
            )
        }

        val country = settings.bypassCountryCode.trim().lowercase()
        if (settings.routingMode == RoutingMode.RULE_BASED && country.isNotEmpty()) {
            servers.put(
                JSONObject().apply {
                    put("address", settings.directDns.ifBlank { "1.1.1.1" })
                    put("domains", countryDomainRules(country))
                    put("expectIPs", JSONArray().put("geoip:$country"))
                    put("skipFallback", true)
                }
            )
        }

        // Primary remote DNS
        servers.put(settings.remoteDns.ifBlank { "https://1.1.1.1/dns-query" })
        // Fallback direct DNS
        servers.put(settings.directDns.ifBlank { "1.1.1.1" })

        return JSONObject().apply {
            put("hosts", JSONObject().apply {
                put("dns.google", "8.8.8.8")
                put("cloudflare-dns.com", "1.1.1.1")
            })
            put("servers", servers)
            put("queryStrategy", if (settings.enableIpv6) "UseIP" else settings.dnsQueryStrategy.ifBlank { "UseIPv4" })
            put("tag", "dns-in")
        }
    }

    private fun buildInboundsArray(
        localSocksPort: Int,
        enableFakeDns: Boolean,
        mtu: Int,
        includeNativeTunInbound: Boolean
    ): JSONArray {
        val destOverride = JSONArray().apply {
            put("http")
            put("tls")
            put("quic")
            if (enableFakeDns) put("fakedns")
        }

        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("port", localSocksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put(
                "settings",
                JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                    put("userLevel", 8)
                }
            )
            put(
                "sniffing",
                JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", destOverride)
                    put("routeOnly", !enableFakeDns)
                }
            )
        }

        val httpInbound = JSONObject().apply {
            put("tag", "http-in")
            put("port", if (localSocksPort < 65530) localSocksPort + 1 else localSocksPort - 1)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put(
                "sniffing",
                JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", destOverride)
                    put("routeOnly", !enableFakeDns)
                }
            )
        }

        val array = JSONArray().put(socksInbound).put(httpInbound)
        if (includeNativeTunInbound) {
            val tunInbound = JSONObject().apply {
                put("tag", "tun")
                put("protocol", "tun")
                put(
                    "settings",
                    JSONObject().apply {
                        put("name", "xray0")
                        put("MTU", mtu.coerceIn(1280, 1500))
                        put("userLevel", 8)
                    }
                )
                put(
                    "sniffing",
                    JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", destOverride)
                        put("routeOnly", !enableFakeDns)
                    }
                )
            }
            array.put(tunInbound)
        }
        return array
    }

    private fun buildOutboundsArray(profile: ServerProfile, settings: AppSettings): JSONArray {
        val outbounds = JSONArray()
        outbounds.put(buildProxyOutbound(profile))

        val domainStrategy = if (settings.enableIpv6) "UseIP" else "UseIPv4"
        outbounds.put(
            JSONObject().apply {
                put("tag", OutboundTag.DIRECT.wireTag)
                put("protocol", "freedom")
                put("settings", JSONObject())
                put(
                    "streamSettings",
                    JSONObject().put(
                        "sockopt",
                        JSONObject().put("domainStrategy", domainStrategy)
                    )
                )
            }
        )

        outbounds.put(
            JSONObject().apply {
                put("tag", OutboundTag.BLOCK.wireTag)
                put("protocol", "blackhole")
                put(
                    "settings",
                    JSONObject().apply {
                        put("response", JSONObject().put("type", "http"))
                    }
                )
            }
        )

        outbounds.put(
            JSONObject().apply {
                put("tag", "dns-out")
                put("protocol", "dns")
            }
        )

        return outbounds
    }

    private fun buildProxyOutbound(profile: ServerProfile): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", OutboundTag.PROXY.wireTag)
        outbound.put("protocol", profile.protocol.scheme)
        val cleanAddress = profile.address.trim().removeSurrounding("[", "]")

        when (profile.protocol) {
            VpnProtocol.VLESS -> {
                val user = JSONObject().apply {
                    put("id", profile.credential.trim())
                    put("encryption", profile.encryption.ifBlank { "none" })
                    put("level", 8)
                    if (profile.flow.isNotBlank()) put("flow", profile.flow.trim())
                }
                val vnext = JSONObject().apply {
                    put("address", cleanAddress)
                    put("port", profile.port)
                    put("users", JSONArray().put(user))
                }
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
                outbound.put("streamSettings", buildStreamSettings(profile))
            }
            VpnProtocol.VMESS -> {
                val user = JSONObject().apply {
                    put("id", profile.credential.trim())
                    put("alterId", profile.alterId)
                    put("security", profile.encryption.ifBlank { "auto" })
                    put("level", 8)
                }
                val vnext = JSONObject().apply {
                    put("address", cleanAddress)
                    put("port", profile.port)
                    put("users", JSONArray().put(user))
                }
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
                outbound.put("streamSettings", buildStreamSettings(profile))
            }
            VpnProtocol.TROJAN -> {
                val server = JSONObject().apply {
                    put("address", cleanAddress)
                    put("port", profile.port)
                    put("password", profile.credential)
                    put("level", 8)
                }
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(server)))
                outbound.put("streamSettings", buildStreamSettings(profile))
            }
            VpnProtocol.SHADOWSOCKS -> {
                val server = JSONObject().apply {
                    put("address", cleanAddress)
                    put("port", profile.port)
                    put("method", profile.encryption.ifBlank { "aes-256-gcm" })
                    put("password", profile.credential)
                    put("uot", true)
                    put("level", 8)
                }
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(server)))
                outbound.put("streamSettings", buildStreamSettings(profile))
            }
            VpnProtocol.WIREGUARD -> {
                outbound.put("protocol", "wireguard")
                val endpointHost = if (cleanAddress.contains(":")) "[$cleanAddress]" else cleanAddress
                val peer = JSONObject().apply {
                    put("endpoint", "$endpointHost:${profile.port}")
                    put("publicKey", profile.wireGuardPublicKey)
                }
                outbound.put(
                    "settings",
                    JSONObject().apply {
                        put("secretKey", profile.credential)
                        put("address", JSONArray().put(profile.wireGuardLocalAddress))
                        put("peers", JSONArray().put(peer))
                        put("mtu", 1420)
                    }
                )
            }
            else -> Unit
        }
        return outbound
    }

    private fun normalizePath(rawPath: String): String {
        val trimmed = rawPath.trim()
        return when {
            trimmed.isEmpty() -> "/"
            trimmed.startsWith("/") -> trimmed
            else -> "/$trimmed"
        }
    }

    private fun buildStreamSettings(profile: ServerProfile): JSONObject {
        val stream = JSONObject()
        stream.put("network", profile.network.wireName)
        stream.put("security", profile.security.wireName)

        val cleanAddress = profile.address.trim().removeSurrounding("[", "]")
        val resolvedSni = when {
            profile.sni.isNotBlank() -> profile.sni.trim()
            profile.host.isNotBlank() -> profile.host.trim().split(",").first().trim()
            !isIpLiteral(cleanAddress) -> cleanAddress
            else -> ""
        }

        when (profile.security) {
            SecurityType.TLS -> {
                stream.put(
                    "tlsSettings",
                    JSONObject().apply {
                        put("allowInsecure", profile.allowInsecure)
                        if (resolvedSni.isNotBlank()) put("serverName", resolvedSni)
                        if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint.trim())
                        if (profile.alpn.isNotBlank()) {
                            val alpnArray = JSONArray()
                            profile.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach { alpnArray.put(it) }
                            put("alpn", alpnArray)
                        }
                    }
                )
            }
            SecurityType.REALITY -> {
                stream.put(
                    "realitySettings",
                    JSONObject().apply {
                        put("show", false)
                        put("fingerprint", profile.fingerprint.ifBlank { "chrome" })
                        put("serverName", profile.sni.ifBlank { profile.host }.trim())
                        put("publicKey", profile.realityPublicKey.trim())
                        put("shortId", profile.realityShortId.trim())
                        put("spiderX", normalizePath(profile.realitySpiderX))
                    }
                )
            }
            SecurityType.NONE -> Unit
        }

        when (profile.network) {
            TransportType.WS -> {
                val wsPath = normalizePath(profile.path)
                val wsHost = profile.host.trim()
                stream.put(
                    "wsSettings",
                    JSONObject().apply {
                        put("path", wsPath)
                        if (wsHost.isNotBlank()) {
                            put("host", wsHost)
                            put("headers", JSONObject().put("Host", wsHost))
                        }
                    }
                )
            }
            TransportType.GRPC -> {
                stream.put(
                    "grpcSettings",
                    JSONObject().apply {
                        put("serviceName", profile.serviceName.ifBlank { profile.path.removePrefix("/") })
                        put("multiMode", false)
                        if (profile.host.isNotBlank()) {
                            put("authority", profile.host.trim())
                        }
                    }
                )
            }
            TransportType.H2 -> {
                stream.put(
                    "httpSettings",
                    JSONObject().apply {
                        put("path", normalizePath(profile.path))
                        if (profile.host.isNotBlank()) {
                            val hostArr = JSONArray()
                            profile.host.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach { hostArr.put(it) }
                            put("host", hostArr)
                        }
                    }
                )
            }
            TransportType.HTTPUPGRADE -> {
                stream.put(
                    "httpupgradeSettings",
                    JSONObject().apply {
                        put("path", normalizePath(profile.path))
                        if (profile.host.isNotBlank()) put("host", profile.host.trim())
                    }
                )
            }
            TransportType.XHTTP -> {
                stream.put(
                    "xhttpSettings",
                    JSONObject().apply {
                        put("path", normalizePath(profile.path))
                        if (profile.host.isNotBlank()) put("host", profile.host.trim())
                        put("mode", "auto")
                    }
                )
            }
            TransportType.MKCP -> {
                stream.put(
                    "kcpSettings",
                    JSONObject().apply {
                        put("mtu", 1350)
                        put("tti", 50)
                        put("header", JSONObject().put("type", profile.headerType.ifBlank { "none" }))
                        if (profile.path.isNotBlank()) put("seed", profile.path)
                    }
                )
            }
            TransportType.QUIC -> {
                stream.put(
                    "quicSettings",
                    JSONObject().apply {
                        put("security", profile.host.ifBlank { "none" })
                        put("key", profile.path)
                        put("header", JSONObject().put("type", profile.headerType.ifBlank { "none" }))
                    }
                )
            }
            TransportType.TCP -> {
                if (profile.headerType.equals("http", ignoreCase = true)) {
                    stream.put(
                        "tcpSettings",
                        JSONObject().put(
                            "header",
                            JSONObject().apply {
                                put("type", "http")
                                put(
                                    "request",
                                    JSONObject().apply {
                                        put("path", JSONArray().put(normalizePath(profile.path)))
                                        if (profile.host.isNotBlank()) {
                                            val hostArr = JSONArray()
                                            profile.host.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                                .forEach { hostArr.put(it) }
                                            put("headers", JSONObject().put("Host", hostArr))
                                        }
                                    }
                                )
                            }
                        )
                    )
                }
            }
        }

        return stream
    }

    private fun buildRoutingObject(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem>
    ): JSONObject {
        val rules = JSONArray()

        // Hijack port 53 to dns-out
        rules.put(
            JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put("socks-in").put("http-in"))
                put("port", 53)
                put("outboundTag", "dns-out")
            }
        )

        val cleanAddress = profile.address.trim().removeSurrounding("[", "]")
        // Ensure proxy server address itself is never routed back into proxy
        if (!isIpLiteral(cleanAddress)) {
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray().put("full:$cleanAddress"))
                    put("outboundTag", OutboundTag.DIRECT.wireTag)
                }
            )
        }

        if (settings.bypassLan) {
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().put("geoip:private"))
                    put("outboundTag", OutboundTag.DIRECT.wireTag)
                }
            )
        }

        if (settings.blockAds) {
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray().put("geosite:category-ads-all"))
                    put("outboundTag", OutboundTag.BLOCK.wireTag)
                }
            )
        }

        // User-defined custom rules
        for (rule in customRules.filter { it.isEnabled && it.value.isNotBlank() }) {
            val trimmedVal = rule.value.trim()
            val ruleObj = JSONObject().apply {
                put("type", "field")
                put("outboundTag", rule.outboundTag.wireTag)
                when (rule.type) {
                    RoutingRuleType.DOMAIN -> put("domain", JSONArray().put(trimmedVal))
                    RoutingRuleType.IP_CIDR -> put("ip", JSONArray().put(trimmedVal))
                    RoutingRuleType.GEOIP -> put("ip", JSONArray().put("geoip:${trimmedVal.lowercase()}"))
                    RoutingRuleType.GEOSITE -> {
                        val lower = trimmedVal.lowercase()
                        if (lower == "ir") {
                            put("domain", countryDomainRules("ir"))
                        } else {
                            put("domain", JSONArray().put("geosite:$lower"))
                        }
                    }
                }
            }
            rules.put(ruleObj)
        }

        when (settings.routingMode) {
            RoutingMode.RULE_BASED -> {
                val country = settings.bypassCountryCode.trim().lowercase()
                if (country.isNotEmpty()) {
                    rules.put(
                        JSONObject().apply {
                            put("type", "field")
                            put("domain", countryDomainRules(country))
                            put("outboundTag", OutboundTag.DIRECT.wireTag)
                        }
                    )
                    rules.put(
                        JSONObject().apply {
                            put("type", "field")
                            put("ip", JSONArray().put("geoip:$country"))
                            put("outboundTag", OutboundTag.DIRECT.wireTag)
                        }
                    )
                }
                rules.put(
                    JSONObject().apply {
                        put("type", "field")
                        put("network", "tcp,udp")
                        put("outboundTag", OutboundTag.PROXY.wireTag)
                    }
                )
            }
            RoutingMode.GLOBAL -> {
                rules.put(
                    JSONObject().apply {
                        put("type", "field")
                        put("network", "tcp,udp")
                        put("outboundTag", OutboundTag.PROXY.wireTag)
                    }
                )
            }
            RoutingMode.DIRECT -> {
                rules.put(
                    JSONObject().apply {
                        put("type", "field")
                        put("network", "tcp,udp")
                        put("outboundTag", OutboundTag.DIRECT.wireTag)
                    }
                )
            }
        }

        return JSONObject().apply {
            put("domainStrategy", if (settings.routingMode == RoutingMode.GLOBAL) "AsIs" else "IPIfNonMatch")
            put("rules", rules)
        }
    }

    fun isIpLiteral(address: String): Boolean {
        val clean = address.trim().removeSurrounding("[", "]")
        val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return ipv4Regex.matches(clean) || clean.contains(":")
    }
}
