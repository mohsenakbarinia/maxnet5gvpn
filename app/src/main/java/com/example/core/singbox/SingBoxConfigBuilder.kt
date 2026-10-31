package com.example.core.singbox

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
 * Programmatic sing-box Configuration Builder & Validator.
 * Supports VLESS, VMess, Trojan, Shadowsocks, Hysteria2, WireGuard, and TUIC.
 */
object SingBoxConfigBuilder {

    fun buildAndValidate(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem> = emptyList(),
        localSocksPort: Int = 10808
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (profile.address.isBlank() || profile.address.contains(" ")) {
            errors.add("Server address cannot be empty or contain spaces.")
        }
        if (profile.port !in 1..65535) {
            errors.add("Server port must be between 1 and 65535.")
        }
        if (profile.credential.isBlank()) {
            errors.add("${profile.protocol.displayName} credential cannot be empty.")
        }
        if (profile.network == TransportType.MKCP || profile.network == TransportType.XHTTP) {
            errors.add("Transport ${profile.network.displayName} is an Xray-specific transport and is not supported by sing-box.")
        }
        if (profile.security == SecurityType.REALITY && profile.realityPublicKey.isBlank()) {
            errors.add("REALITY security requires a valid Public Key (pbk).")
        }
        if (profile.allowInsecure) {
            warnings.add("AllowInsecure is enabled: TLS certificate verification is disabled.")
        }

        if (errors.isNotEmpty()) {
            return ValidationResult(false, errors, warnings, "")
        }

        val root = JSONObject()
        root.put("log", JSONObject().apply {
            put("level", "warn")
            put("timestamp", true)
        })

        // DNS
        val dnsServers = JSONArray()
        dnsServers.put(JSONObject().apply {
            put("tag", "dns-remote")
            put("address", settings.remoteDns.ifBlank { "https://1.1.1.1/dns-query" })
            put("detour", OutboundTag.PROXY.wireTag)
        })
        dnsServers.put(JSONObject().apply {
            put("tag", "dns-direct")
            put("address", settings.directDns.ifBlank { "1.1.1.1" })
            put("detour", OutboundTag.DIRECT.wireTag)
        })
        root.put("dns", JSONObject().apply {
            put("servers", dnsServers)
            put("strategy", if (settings.enableIpv6) "prefer_ipv4" else "ipv4_only")
        })

        // Inbounds (strictly local loopback mixed inbound)
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("type", "mixed")
            put("tag", "mixed-in")
            put("listen", "127.0.0.1")
            put("listen_port", localSocksPort)
            put("sniff", true)
        })
        root.put("inbounds", inbounds)

        // Outbounds
        val outbounds = JSONArray()
        outbounds.put(buildSingBoxProxyOutbound(profile))
        outbounds.put(JSONObject().apply {
            put("type", "direct")
            put("tag", OutboundTag.DIRECT.wireTag)
        })
        outbounds.put(JSONObject().apply {
            put("type", "block")
            put("tag", OutboundTag.BLOCK.wireTag)
        })
        outbounds.put(JSONObject().apply {
            put("type", "dns")
            put("tag", "dns-out")
        })
        root.put("outbounds", outbounds)

        // Route
        val routeRules = JSONArray()
        routeRules.put(JSONObject().apply {
            put("protocol", "dns")
            put("outbound", "dns-out")
        })
        if (settings.bypassLan) {
            routeRules.put(JSONObject().apply {
                put("ip_is_private", true)
                put("outbound", OutboundTag.DIRECT.wireTag)
            })
        }
        if (settings.blockAds) {
            routeRules.put(JSONObject().apply {
                put("geosite", JSONArray().put("category-ads-all"))
                put("outbound", OutboundTag.BLOCK.wireTag)
            })
        }
        for (rule in customRules.filter { it.isEnabled && it.value.isNotBlank() }) {
            val r = JSONObject().put("outbound", rule.outboundTag.wireTag)
            when (rule.type) {
                RoutingRuleType.DOMAIN -> r.put("domain_suffix", JSONArray().put(rule.value.trim()))
                RoutingRuleType.IP_CIDR -> r.put("ip_cidr", JSONArray().put(rule.value.trim()))
                RoutingRuleType.GEOIP -> r.put("geoip", JSONArray().put(rule.value.trim().lowercase()))
                RoutingRuleType.GEOSITE -> r.put("geosite", JSONArray().put(rule.value.trim().lowercase()))
            }
            routeRules.put(r)
        }
        if (settings.routingMode == RoutingMode.RULE_BASED && settings.bypassCountryCode.isNotBlank()) {
            val cc = settings.bypassCountryCode.trim().lowercase()
            routeRules.put(JSONObject().apply {
                put("geoip", JSONArray().put(cc))
                put("geosite", JSONArray().put(cc))
                put("outbound", OutboundTag.DIRECT.wireTag)
            })
        }
        root.put("route", JSONObject().apply {
            put("rules", routeRules)
            put(
                "final",
                if (settings.routingMode == RoutingMode.DIRECT) OutboundTag.DIRECT.wireTag else OutboundTag.PROXY.wireTag
            )
            put("auto_detect_interface", true)
        })

        return ValidationResult(true, emptyList(), warnings, root.toString(2))
    }

    private fun buildSingBoxProxyOutbound(profile: ServerProfile): JSONObject {
        val ob = JSONObject()
        ob.put("tag", OutboundTag.PROXY.wireTag)
        ob.put("server", profile.address)
        ob.put("server_port", profile.port)

        when (profile.protocol) {
            VpnProtocol.VLESS -> {
                ob.put("type", "vless")
                ob.put("uuid", profile.credential)
                if (profile.flow.isNotBlank()) ob.put("flow", profile.flow)
            }
            VpnProtocol.VMESS -> {
                ob.put("type", "vmess")
                ob.put("uuid", profile.credential)
                ob.put("security", profile.encryption.ifBlank { "auto" })
                ob.put("alter_id", profile.alterId)
            }
            VpnProtocol.TROJAN -> {
                ob.put("type", "trojan")
                ob.put("password", profile.credential)
            }
            VpnProtocol.SHADOWSOCKS -> {
                ob.put("type", "shadowsocks")
                ob.put("method", profile.encryption.ifBlank { "aes-256-gcm" })
                ob.put("password", profile.credential)
            }
            VpnProtocol.HYSTERIA2 -> {
                ob.put("type", "hysteria2")
                ob.put("password", profile.credential)
            }
            VpnProtocol.WIREGUARD -> {
                ob.put("type", "wireguard")
                ob.put("private_key", profile.credential)
                ob.put("peer_public_key", profile.wireGuardPublicKey)
                ob.put("local_address", JSONArray().put(profile.wireGuardLocalAddress))
            }
            VpnProtocol.TUIC -> {
                ob.put("type", "tuic")
                ob.put("uuid", profile.credential)
            }
        }

        if (profile.security != SecurityType.NONE || profile.protocol == VpnProtocol.HYSTERIA2) {
            val tls = JSONObject().apply {
                put("enabled", true)
                put("server_name", profile.sni.ifBlank { profile.host.ifBlank { profile.address } })
                put("insecure", profile.allowInsecure)
                if (profile.fingerprint.isNotBlank()) {
                    put("utls", JSONObject().put("enabled", true).put("fingerprint", profile.fingerprint))
                }
                if (profile.security == SecurityType.REALITY) {
                    put("reality", JSONObject().apply {
                        put("enabled", true)
                        put("public_key", profile.realityPublicKey)
                        put("short_id", profile.realityShortId)
                    })
                }
            }
            ob.put("tls", tls)
        }

        when (profile.network) {
            TransportType.WS -> {
                ob.put("transport", JSONObject().apply {
                    put("type", "ws")
                    put("path", profile.path.ifBlank { "/" })
                    if (profile.host.isNotBlank()) {
                        put("headers", JSONObject().put("Host", profile.host))
                    }
                })
            }
            TransportType.GRPC -> {
                ob.put("transport", JSONObject().apply {
                    put("type", "grpc")
                    put("service_name", profile.serviceName.ifBlank { profile.path })
                })
            }
            TransportType.H2 -> {
                ob.put("transport", JSONObject().apply {
                    put("type", "http")
                    put("path", profile.path.ifBlank { "/" })
                })
            }
            TransportType.HTTPUPGRADE -> {
                ob.put("transport", JSONObject().apply {
                    put("type", "httpupgrade")
                    put("path", profile.path.ifBlank { "/" })
                })
            }
            else -> Unit
        }
        return ob
    }
}
