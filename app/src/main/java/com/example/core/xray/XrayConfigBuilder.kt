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
 * Programmatic Xray-core Configuration Builder & Validator.
 * Generates complete, standards-compliant Xray JSON configurations with:
 * - Local-only SOCKS5 & HTTP inbounds bound strictly to 127.0.0.1
 * - Sniffing (http, tls, quic, fakedns)
 * - Statistics policy (uplink/downlink traffic counters)
 * - Outbounds (proxy, direct, block, dns-out)
 * - DNS loop prevention, DoH/DoT, Direct DNS for bypassed country/domains, FakeDNS
 * - Routing modes (Global, Rule-Based, Direct) + GeoIP/GeoSite + custom rules
 */
object XrayConfigBuilder {

    private val supportedProtocols = setOf(
        VpnProtocol.VLESS,
        VpnProtocol.VMESS,
        VpnProtocol.TROJAN,
        VpnProtocol.SHADOWSOCKS,
        VpnProtocol.WIREGUARD
    )

    fun buildAndValidate(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem> = emptyList(),
        localSocksPort: Int = 10808
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (profile.protocol !in supportedProtocols) {
            errors.add("Protocol ${profile.protocol.displayName} is not supported by Xray-core. Switch engine to sing-box for ${profile.protocol.displayName}.")
        }
        if (profile.address.isBlank() || profile.address.contains(" ")) {
            errors.add("Server address cannot be empty or contain spaces.")
        }
        if (profile.port !in 1..65535) {
            errors.add("Server port must be between 1 and 65535 (got ${profile.port}).")
        }
        if (profile.credential.isBlank()) {
            errors.add("${profile.protocol.displayName} credential (UUID / Password / Key) cannot be empty.")
        }
        if (localSocksPort !in 1024..65535) {
            errors.add("Local SOCKS port must be an unprivileged port between 1024 and 65535.")
        }

        // Validate protocol + transport + security combinations
        if (profile.security == SecurityType.REALITY) {
            if (profile.protocol != VpnProtocol.VLESS && profile.protocol != VpnProtocol.TROJAN) {
                errors.add("REALITY security is only supported with VLESS or Trojan protocols.")
            }
            if (profile.realityPublicKey.isBlank()) {
                errors.add("REALITY security requires a valid Public Key (pbk).")
            }
            if (profile.network !in setOf(TransportType.TCP, TransportType.GRPC, TransportType.H2, TransportType.XHTTP)) {
                errors.add("REALITY is incompatible with ${profile.network.displayName} transport.")
            }
        }

        if (profile.flow.isNotBlank()) {
            if (profile.protocol != VpnProtocol.VLESS) {
                errors.add("Flow (${profile.flow}) is only supported on VLESS protocol.")
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

        // 2. Stats & Policy configuration
        root.put("stats", JSONObject())
        root.put(
            "policy",
            JSONObject().apply {
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
        root.put("dns", buildDnsObject(profile, settings))

        // 4. Inbounds (Strictly bound to 127.0.0.1)
        root.put("inbounds", buildInboundsArray(localSocksPort, settings.enableFakeDns))

        // 5. Outbounds (proxy, direct, block, dns-out)
        root.put("outbounds", buildOutboundsArray(profile, settings))

        // 6. Routing configuration
        root.put("routing", buildRoutingObject(profile, settings, customRules))

        return ValidationResult(
            isValid = true,
            errors = emptyList(),
            warnings = warnings,
            generatedConfigJson = root.toString(2)
        )
    }

    private fun buildDnsObject(profile: ServerProfile, settings: AppSettings): JSONObject {
        val servers = JSONArray()
        if (settings.enableFakeDns) {
            servers.put("fakedns")
        }

        // Prevent DNS loop: resolve the proxy server's own domain using direct DNS
        if (!isIpLiteral(profile.address)) {
            servers.put(
                JSONObject().apply {
                    put("address", settings.directDns.ifBlank { "1.1.1.1" })
                    put("domains", JSONArray().put("full:${profile.address}"))
                    put("skipFallback", true)
                }
            )
        }

        val country = settings.bypassCountryCode.trim().lowercase()
        if (settings.routingMode == RoutingMode.RULE_BASED && country.isNotEmpty()) {
            servers.put(
                JSONObject().apply {
                    put("address", settings.directDns.ifBlank { "1.1.1.1" })
                    put("domains", JSONArray().put("geosite:$country"))
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

    private fun buildInboundsArray(localSocksPort: Int, enableFakeDns: Boolean): JSONArray {
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
                }
            )
            put(
                "sniffing",
                JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", destOverride)
                    put("routeOnly", false)
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
                }
            )
        }

        return JSONArray().put(socksInbound).put(httpInbound)
    }

    private fun buildOutboundsArray(profile: ServerProfile, settings: AppSettings): JSONArray {
        val outbounds = JSONArray()
        outbounds.put(buildProxyOutbound(profile))

        outbounds.put(
            JSONObject().apply {
                put("tag", OutboundTag.DIRECT.wireTag)
                put("protocol", "freedom")
                put(
                    "settings",
                    JSONObject().apply {
                        put("domainStrategy", if (settings.enableIpv6) "UseIP" else "UseIPv4")
                    }
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

        when (profile.protocol) {
            VpnProtocol.VLESS -> {
                val user = JSONObject().apply {
                    put("id", profile.credential)
                    put("encryption", profile.encryption.ifBlank { "none" })
                    if (profile.flow.isNotBlank()) put("flow", profile.flow)
                }
                val vnext = JSONObject().apply {
                    put("address", profile.address)
                    put("port", profile.port)
                    put("users", JSONArray().put(user))
                }
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
                outbound.put("streamSettings", buildStreamSettings(profile))
            }
            VpnProtocol.VMESS -> {
                val user = JSONObject().apply {
                    put("id", profile.credential)
                    put("alterId", profile.alterId)
                    put("security", profile.encryption.ifBlank { "auto" })
                }
                val vnext = JSONObject().apply {
                    put("address", profile.address)
                    put("port", profile.port)
                    put("users", JSONArray().put(user))
                }
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
                outbound.put("streamSettings", buildStreamSettings(profile))
            }
            VpnProtocol.TROJAN -> {
                val server = JSONObject().apply {
                    put("address", profile.address)
                    put("port", profile.port)
                    put("password", profile.credential)
                }
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(server)))
                outbound.put("streamSettings", buildStreamSettings(profile))
            }
            VpnProtocol.SHADOWSOCKS -> {
                val server = JSONObject().apply {
                    put("address", profile.address)
                    put("port", profile.port)
                    put("method", profile.encryption.ifBlank { "aes-256-gcm" })
                    put("password", profile.credential)
                    put("uot", true)
                }
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(server)))
                outbound.put("streamSettings", buildStreamSettings(profile))
            }
            VpnProtocol.WIREGUARD -> {
                outbound.put("protocol", "wireguard")
                val peer = JSONObject().apply {
                    put("endpoint", "${profile.address}:${profile.port}")
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

    private fun buildStreamSettings(profile: ServerProfile): JSONObject {
        val stream = JSONObject()
        stream.put("network", profile.network.wireName)
        stream.put("security", profile.security.wireName)

        when (profile.security) {
            SecurityType.TLS -> {
                stream.put(
                    "tlsSettings",
                    JSONObject().apply {
                        put("allowInsecure", profile.allowInsecure)
                        if (profile.sni.isNotBlank()) put("serverName", profile.sni)
                        else if (profile.host.isNotBlank()) put("serverName", profile.host)
                        if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
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
                        put("serverName", profile.sni.ifBlank { profile.host })
                        put("publicKey", profile.realityPublicKey)
                        put("shortId", profile.realityShortId)
                        put("spiderX", profile.realitySpiderX.ifBlank { "/" })
                    }
                )
            }
            SecurityType.NONE -> Unit
        }

        when (profile.network) {
            TransportType.WS -> {
                stream.put(
                    "wsSettings",
                    JSONObject().apply {
                        put("path", profile.path.ifBlank { "/" })
                        if (profile.host.isNotBlank()) {
                            put("headers", JSONObject().put("Host", profile.host))
                        }
                    }
                )
            }
            TransportType.GRPC -> {
                stream.put(
                    "grpcSettings",
                    JSONObject().apply {
                        put("serviceName", profile.serviceName.ifBlank { profile.path })
                        put("multiMode", false)
                    }
                )
            }
            TransportType.H2 -> {
                stream.put(
                    "httpSettings",
                    JSONObject().apply {
                        put("path", profile.path.ifBlank { "/" })
                        if (profile.host.isNotBlank()) {
                            put("host", JSONArray().put(profile.host))
                        }
                    }
                )
            }
            TransportType.HTTPUPGRADE -> {
                stream.put(
                    "httpupgradeSettings",
                    JSONObject().apply {
                        put("path", profile.path.ifBlank { "/" })
                        if (profile.host.isNotBlank()) put("host", profile.host)
                    }
                )
            }
            TransportType.XHTTP -> {
                stream.put(
                    "xhttpSettings",
                    JSONObject().apply {
                        put("path", profile.path.ifBlank { "/" })
                        if (profile.host.isNotBlank()) put("host", profile.host)
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
                                        put("path", JSONArray().put(profile.path.ifBlank { "/" }))
                                        if (profile.host.isNotBlank()) {
                                            put("headers", JSONObject().put("Host", JSONArray().put(profile.host)))
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

        // Ensure proxy server address itself is never routed back into proxy
        if (!isIpLiteral(profile.address)) {
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray().put("full:${profile.address}"))
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
            val ruleObj = JSONObject().apply {
                put("type", "field")
                put("outboundTag", rule.outboundTag.wireTag)
                when (rule.type) {
                    RoutingRuleType.DOMAIN -> put("domain", JSONArray().put(rule.value.trim()))
                    RoutingRuleType.IP_CIDR -> put("ip", JSONArray().put(rule.value.trim()))
                    RoutingRuleType.GEOIP -> put("ip", JSONArray().put("geoip:${rule.value.trim().lowercase()}"))
                    RoutingRuleType.GEOSITE -> put("domain", JSONArray().put("geosite:${rule.value.trim().lowercase()}"))
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
                            put("domain", JSONArray().put("geosite:$country"))
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

    private fun isIpLiteral(address: String): Boolean {
        val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return ipv4Regex.matches(address) || address.contains(":")
    }
}
