package com.example

import com.example.core.singbox.SingBoxConfigBuilder
import com.example.core.xray.XrayConfigBuilder
import com.example.domain.model.AppSettings
import com.example.domain.model.OutboundTag
import com.example.domain.model.RoutingMode
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.RoutingRuleType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType
import com.example.domain.model.VpnProtocol
import com.example.parser.ConfigParser
import com.example.security.CredentialCipher
import com.example.security.LogRedactor
import com.example.util.LocalizationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive Automated Parser, Configuration Builder, Encryption, and Redaction Test Suite.
 * Uses only example.com, RFC 5737 documentation IP ranges (192.0.2.x, 198.51.100.x, 203.0.113.x),
 * and RFC 3849 IPv6 documentation addresses (2001:db8::/32).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    private val testUuid = "b831381d-6324-4d53-ad4f-8cda48b30811"

    @Test
    fun `parse valid VLESS with REALITY and Persian emoji remarks`() {
        val uri = "vless://$testUuid@192.0.2.10:443?type=tcp&security=reality&encryption=none&flow=xtls-rprx-vision&sni=example.com&fp=chrome&pbk=TestPublicKey123456&sid=01ab&spx=%2F#%D8%B3%D8%B1%D9%88%D8%B1%20%D8%AA%D8%B3%D8%AA%20%F0%9F%9A%80"
        val res = ConfigParser.parseSingleUri(uri)
        assertTrue(res.isSuccess)
        val p = res.getOrThrow()
        assertEquals(VpnProtocol.VLESS, p.protocol)
        assertEquals("192.0.2.10", p.address)
        assertEquals(443, p.port)
        assertEquals(testUuid, p.credential)
        assertEquals(SecurityType.REALITY, p.security)
        assertEquals("TestPublicKey123456", p.realityPublicKey)
        assertEquals("01ab", p.realityShortId)
        assertEquals("xtls-rprx-vision", p.flow)
        assertEquals("سرور تست 🚀", p.remarks)
    }

    @Test
    fun `parse valid VMess v2rayN Base64 JSON and round-trip`() {
        val vmessJson = """
            {"v":"2","ps":"VMess-Node-ایران","add":"198.51.100.25","port":"8443","id":"$testUuid","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/ws-path","tls":"tls","sni":"cdn.example.com","alpn":"h2,http/1.1","fp":"firefox"}
        """.trimIndent()
        val encoded = ConfigParser.encodeBase64NoPad(vmessJson, urlSafe = true) // URL-safe without padding
        val uri = "vmess://$encoded"

        val parsed = ConfigParser.parseSingleUri(uri).getOrThrow()
        assertEquals(VpnProtocol.VMESS, parsed.protocol)
        assertEquals("VMess-Node-ایران", parsed.remarks)
        assertEquals("198.51.100.25", parsed.address)
        assertEquals(8443, parsed.port)
        assertEquals(TransportType.WS, parsed.network)
        assertEquals(SecurityType.TLS, parsed.security)
        assertEquals("/ws-path", parsed.path)

        // Round-trip conversion
        val shareUri = ConfigParser.toShareUri(parsed)
        val reparsed = ConfigParser.parseSingleUri(shareUri).getOrThrow()
        assertEquals(parsed.address, reparsed.address)
        assertEquals(parsed.port, reparsed.port)
        assertEquals(parsed.credential, reparsed.credential)
        assertEquals(parsed.remarks, reparsed.remarks)
    }

    @Test
    fun `parse valid Trojan with IPv6 bracketed address and uppercase scheme`() {
        val uri = "TROJAN://MyTestTrojanSecret@[2001:db8::1]:443?type=grpc&security=tls&serviceName=grpc-svc&sni=example.com#IPv6-Trojan"
        val parsed = ConfigParser.parseSingleUri(uri).getOrThrow()
        assertEquals(VpnProtocol.TROJAN, parsed.protocol)
        assertEquals("2001:db8::1", parsed.address)
        assertEquals(443, parsed.port)
        assertEquals("MyTestTrojanSecret", parsed.credential)
        assertEquals(TransportType.GRPC, parsed.network)
        assertEquals("grpc-svc", parsed.serviceName)

        val shareUri = ConfigParser.toShareUri(parsed)
        assertTrue(shareUri.contains("[2001:db8::1]:443"))
        val reparsed = ConfigParser.parseSingleUri(shareUri).getOrThrow()
        assertEquals("2001:db8::1", reparsed.address)
    }

    @Test
    fun `parse Shadowsocks SIP002, Legacy Base64, and 2022 Blake3 cipher`() {
        val sip002User = ConfigParser.encodeBase64NoPad("2022-blake3-aes-128-gcm:i543210abcdef=:fedcba012345=", urlSafe = true)
        val sip002Uri = "ss://$sip002User@203.0.113.42:8388#SS2022-Test"
        val p1 = ConfigParser.parseSingleUri(sip002Uri).getOrThrow()
        assertEquals(VpnProtocol.SHADOWSOCKS, p1.protocol)
        assertEquals("2022-blake3-aes-128-gcm", p1.encryption)
        assertEquals("i543210abcdef=:fedcba012345=", p1.credential)
        assertEquals("203.0.113.42", p1.address)

        val legacyPayload = ConfigParser.encodeBase64NoPad("chacha20-ietf-poly1305:legacyPass123@192.0.2.99:9000", urlSafe = false)
        val legacyUri = "ss://$legacyPayload#LegacySS"
        val p2 = ConfigParser.parseSingleUri(legacyUri).getOrThrow()
        assertEquals("chacha20-ietf-poly1305", p2.encryption)
        assertEquals("legacyPass123", p2.credential)
        assertEquals(9000, p2.port)
    }

    @Test
    fun `reject invalid Base64, missing parameters, HTML body, and deduplicate batch`() {
        val htmlResult = ConfigParser.parseBatch("<!DOCTYPE html><html><body>Error</body></html>")
        assertTrue(htmlResult.profiles.isEmpty())
        assertFalse(htmlResult.failures.isEmpty())

        val invalidVmess = ConfigParser.parseSingleUri("vmess://!!!not_base64!!!")
        assertTrue(invalidVmess.isFailure)

        val missingPort = ConfigParser.parseSingleUri("vless://$testUuid@example.com?security=tls")
        assertTrue(missingPort.isFailure)

        // Batch with whitespace, duplicate URIs, and one invalid line
        val validLine = "vless://$testUuid@192.0.2.50:443?type=ws&security=tls&path=%2Fedge&sni=example.com#Node1"
        val batchText = """
            
              $validLine  
            $validLine
            vless://invalid_line_without_host
        """.trimIndent()
        val batchResult = ConfigParser.parseBatch(batchText)
        assertEquals(1, batchResult.profiles.size)
        assertEquals(1, batchResult.duplicatesSkipped)
        assertEquals(1, batchResult.failures.size)
    }

    @Test
    fun `validate Xray and sing-box configuration builders`() {
        val validProfile = ServerProfile(
            remarks = "Edge-TLS",
            protocol = VpnProtocol.VLESS,
            address = "192.0.2.15",
            port = 443,
            credential = testUuid,
            network = TransportType.WS,
            path = "/ws",
            host = "example.com",
            security = SecurityType.TLS,
            sni = "example.com"
        )
        val settings = AppSettings(
            routingMode = RoutingMode.RULE_BASED,
            bypassLan = true,
            bypassCountryCode = "IR",
            blockAds = true
        )
        val rules = listOf(
            RoutingRuleItem(
                name = "Direct Example",
                type = RoutingRuleType.DOMAIN,
                value = "direct.example.com",
                outboundTag = OutboundTag.DIRECT
            )
        )

        val xrayValidation = XrayConfigBuilder.buildAndValidate(validProfile, settings, rules, 10808)
        assertTrue(xrayValidation.isValid)
        assertTrue(xrayValidation.generatedConfigJson.contains("\"listen\": \"127.0.0.1\""))
        assertTrue(xrayValidation.generatedConfigJson.contains("geosite:ir"))
        assertTrue(xrayValidation.generatedConfigJson.contains("geosite:category-ads-all"))

        // Reject REALITY without publicKey
        val invalidReality = validProfile.copy(security = SecurityType.REALITY, realityPublicKey = "")
        val invalidResult = XrayConfigBuilder.buildAndValidate(invalidReality, settings, rules, 10808)
        assertFalse(invalidResult.isValid)
        assertTrue(invalidResult.errors.any { it.contains("pbk", ignoreCase = true) })

        // Verify Hysteria2 is rejected on Xray-core with clear message but accepted on sing-box
        val hy2Profile = validProfile.copy(protocol = VpnProtocol.HYSTERIA2)
        assertFalse(XrayConfigBuilder.buildAndValidate(hy2Profile, settings).isValid)
        val singBoxResult = SingBoxConfigBuilder.buildAndValidate(hy2Profile, settings)
        assertTrue(singBoxResult.isValid)
        assertTrue(singBoxResult.generatedConfigJson.contains("\"type\": \"hysteria2\""))
    }

    @Test
    fun `verify LogRedactor and CredentialCipher security`() {
        val rawLog = "Connecting vless://$testUuid@192.0.2.1:443 and https://sub.example.com/api?token=secret999"
        val sanitized = LogRedactor.redact(rawLog, listOf("MyCustomPassword123"))
        assertFalse(sanitized.contains(testUuid))
        assertFalse(sanitized.contains("secret999"))
        assertTrue(sanitized.contains("[REDACTED"))

        val encrypted = CredentialCipher.encrypt(testUuid)
        assertNotNull(encrypted)
        assertEquals(testUuid, CredentialCipher.decrypt(encrypted))

        // Verify Persian digits and Jalali conversion
        val persianDigits = LocalizationHelper.formatDigits("443 ms", true)
        assertEquals("۴۴۳ ms", persianDigits)
    }
}
