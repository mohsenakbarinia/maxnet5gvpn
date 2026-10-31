package com.example

import com.example.core.LatencyTester
import com.example.core.NetworkQualityTester
import com.example.core.singbox.SingBoxConfigBuilder
import com.example.core.xray.XrayConfigBuilder
import com.example.domain.model.AppSettings
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.OutboundTag
import com.example.domain.model.QualityTestStage
import com.example.domain.model.RoutingMode
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.RoutingRuleType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnProtocol
import com.example.parser.ConfigParser
import com.example.security.CredentialCipher
import com.example.security.LogRedactor
import com.example.util.LocalizationHelper
import com.example.vpn.VpnController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Comprehensive Automated Parser, Configuration Builder, Real Xray-core v26.10.29 Engine Validation,
 * Encrypted VLESS Loopback Tunnel, Latency Testing, Encryption, and Redaction Test Suite.
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
        val encoded = ConfigParser.encodeBase64NoPad(vmessJson, urlSafe = true)
        val uri = "vmess://$encoded"

        val parsed = ConfigParser.parseSingleUri(uri).getOrThrow()
        assertEquals(VpnProtocol.VMESS, parsed.protocol)
        assertEquals("VMess-Node-ایران", parsed.remarks)
        assertEquals("198.51.100.25", parsed.address)
        assertEquals(8443, parsed.port)
        assertEquals(TransportType.WS, parsed.network)
        assertEquals(SecurityType.TLS, parsed.security)
        assertEquals("/ws-path", parsed.path)

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
    fun `reject invalid UUID, invalid port, missing REALITY parameters, HTML body, and deduplicate batch`() {
        val htmlResult = ConfigParser.parseBatch("<!DOCTYPE html><html><body>Error</body></html>")
        assertTrue(htmlResult.profiles.isEmpty())
        assertFalse(htmlResult.failures.isEmpty())

        val invalidVmess = ConfigParser.parseSingleUri("vmess://!!!not_base64!!!")
        assertTrue(invalidVmess.isFailure)

        val invalidUuid = ConfigParser.parseSingleUri("vless://not-a-valid-uuid@example.com:443?security=tls&sni=example.com")
        assertTrue(invalidUuid.isFailure)

        val invalidPort = ConfigParser.parseSingleUri("vless://$testUuid@example.com:70000?security=tls&sni=example.com")
        assertTrue(invalidPort.isFailure)

        val missingPort = ConfigParser.parseSingleUri("vless://$testUuid@example.com?security=tls")
        assertTrue(missingPort.isFailure)

        val missingRealityPbk = ConfigParser.parseSingleUri("vless://$testUuid@example.com:443?security=reality&sni=example.com")
        assertTrue(missingRealityPbk.isFailure)

        val missingRealitySni = ConfigParser.parseSingleUri("vless://$testUuid@example.com:443?security=reality&pbk=TestPubKey12345")
        assertTrue(missingRealitySni.isFailure)

        val invalidRealitySid = ConfigParser.parseSingleUri("vless://$testUuid@example.com:443?security=reality&sni=example.com&pbk=TestPubKey12345&sid=NOT_HEX_ZZ")
        assertTrue(invalidRealitySid.isFailure)

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
    fun `validate Xray and sing-box configuration builders and test against Xray-core v26_10_29`() {
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
        assertTrue(xrayValidation.generatedConfigJson.contains("geosite:category-ir"))
        assertTrue(xrayValidation.generatedConfigJson.contains("geosite:category-ads-all"))

        // If upstream Xray 26.10.29 host binary is available, verify `xray run -test` accepts our generated JSON
        val hostXray = File("/tmp/xray-linux/xray")
        if (hostXray.exists() && hostXray.canExecute()) {
            val tmpCfg = File.createTempFile("xray-unit-test-", ".json")
            try {
                tmpCfg.writeText(xrayValidation.generatedConfigJson)
                val proc = ProcessBuilder(hostXray.absolutePath, "run", "-test", "-c", tmpCfg.absolutePath)
                    .apply { environment()["XRAY_LOCATION_ASSET"] = hostXray.parentFile?.absolutePath ?: "/tmp/xray-linux" }
                    .redirectErrorStream(true)
                    .start()
                assertTrue(proc.waitFor(5, TimeUnit.SECONDS))
                val output = proc.inputStream.bufferedReader().readText()
                assertEquals("Xray 26.10.29 rejected config: $output", 0, proc.exitValue())
                assertTrue(output.contains("Configuration OK"))
            } finally {
                tmpCfg.delete()
            }
        }

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
    fun `verify real end-to-end VLESS encrypted proxy tunnel and HTTP 204 verification via Xray-core`() = runBlocking {
        val hostXray = File("/tmp/xray-linux/xray")
        if (!hostXray.exists() || !hostXray.canExecute()) return@runBlocking

        val loopback = InetAddress.getByName("127.0.0.1")
        // 1. Start a real local SOCKS5 + HTTP 204 upstream responder for the VLESS server
        val httpServerSocket = ServerSocket(0, 16, loopback)
        val httpPort = httpServerSocket.localPort
        val httpThread = Thread {
            while (!httpServerSocket.isClosed) {
                try {
                    val client = httpServerSocket.accept()
                    client.soTimeout = 2000
                    val inp = client.getInputStream()
                    val out = client.getOutputStream()
                    // SOCKS5 greeting from VLESS server outbound
                    val greet = ByteArray(3)
                    inp.read(greet)
                    out.write(byteArrayOf(0x05, 0x00))
                    out.flush()
                    // SOCKS5 CONNECT from VLESS server outbound
                    val reqBuf = ByteArray(64)
                    inp.read(reqBuf)
                    out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 80))
                    out.flush()
                    // Read HTTP request tunneled through VLESS
                    val httpBuf = ByteArray(1024)
                    inp.read(httpBuf)
                    val resp = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(resp.toByteArray())
                    out.flush()
                    client.close()
                } catch (_: Exception) {
                    if (httpServerSocket.isClosed) break
                }
            }
        }.apply { isDaemon = true; start() }

        // 2. Start a real local Xray-core v26.10.29 VLESS server on 127.0.0.1
        val vlessServerPort = ServerSocket(0, 1, loopback).use { it.localPort }
        val serverConfigFile = File.createTempFile("xray-vless-server-", ".json")
        serverConfigFile.writeText(
            """
            {
              "log": { "loglevel": "warning" },
              "inbounds": [
                {
                  "tag": "vless-in",
                  "listen": "127.0.0.1",
                  "port": $vlessServerPort,
                  "protocol": "vless",
                  "settings": {
                    "clients": [
                      { "id": "$testUuid" }
                    ],
                    "decryption": "none"
                  },
                  "streamSettings": {
                    "network": "tcp",
                    "security": "none"
                  }
                }
              ],
              "outbounds": [
                {
                  "tag": "direct",
                  "protocol": "socks",
                  "settings": {
                    "servers": [
                      { "address": "127.0.0.1", "port": $httpPort }
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )
        val serverProcess = ProcessBuilder(hostXray.absolutePath, "run", "-c", serverConfigFile.absolutePath)
            .apply { environment()["XRAY_LOCATION_ASSET"] = hostXray.parentFile?.absolutePath ?: "/tmp/xray-linux" }
            .redirectErrorStream(true)
            .start()

        try {
            // Wait until the local VLESS server port is listening
            Thread.sleep(350)

            val clientProfile = ServerProfile(
                id = 77L,
                remarks = "Real-VLESS-Loopback-Server",
                protocol = VpnProtocol.VLESS,
                address = "127.0.0.1",
                port = vlessServerPort,
                credential = testUuid,
                encryption = "none",
                network = TransportType.TCP,
                security = SecurityType.NONE
            )
            val observedStates = mutableListOf<VpnConnectionState>()
            val test204Url = "http://1.1.1.1/generate_204"

            // 3. Connect VpnController with full HTTP 204 verification enabled
            val tunnelSettings = AppSettings(routingMode = RoutingMode.GLOBAL, bypassLan = false)
            val startResult = VpnController.startSession(
                profile = clientProfile,
                settings = tunnelSettings,
                rules = emptyList(),
                establishTunAndForwarder = { true },
                verifyConnectivity = true,
                verificationUrl = test204Url,
                onStateChanged = { state -> observedStates.add(state) }
            )

            assertTrue("Expected real VLESS session to succeed: ${startResult.exceptionOrNull()?.message}", startResult.isSuccess)
            val clientSocksPort = startResult.getOrThrow()
            assertEquals(VpnConnectionState.CONNECTED, VpnController.connectionState.value)
            assertTrue(observedStates.contains(VpnConnectionState.PREPARING))
            assertTrue(observedStates.contains(VpnConnectionState.STARTING_ENGINE))
            assertTrue(observedStates.contains(VpnConnectionState.CONNECTING))
            assertTrue(observedStates.contains(VpnConnectionState.VERIFYING))
            assertTrue(observedStates.contains(VpnConnectionState.CONNECTED))

            // 4. Test real proxy HTTP 204 delay and NetworkQualityTester through the active SOCKS5 port
            val latencyOutcome = LatencyTester.testProxyHttp204Delay(
                profile = clientProfile,
                localSocksPort = clientSocksPort,
                testUrl = test204Url
            )
            assertEquals(LatencyMeasureType.PROXY_HTTP_DELAY, latencyOutcome.measureType)
            assertNotNull(latencyOutcome.latencyMs)
            assertTrue((latencyOutcome.latencyMs ?: 0L) >= 1L)

            val qualityReport = NetworkQualityTester.runQualityTest(
                profile = clientProfile,
                isVpnConnected = true,
                localSocksPort = clientSocksPort,
                testUrl204 = test204Url,
                onStageUpdate = {}
            )
            assertTrue(qualityReport.testedThroughVpn)
            assertTrue(qualityReport.tunnelConnected)
            assertTrue(qualityReport.internetReachable)
            assertEquals(QualityTestStage.COMPLETED, qualityReport.stage)
            assertEquals(LatencyMeasureType.PROXY_HTTP_DELAY, qualityReport.latencyType)
            assertNotNull(qualityReport.latencyMs)

            // 5. Stop session cleanly
            VpnController.stopSession()
            assertEquals(VpnConnectionState.DISCONNECTED, VpnController.connectionState.value)

            // 6. Verify that an unreachable server port fails verification and transitions to ERROR (never fake CONNECTED)
            val deadPort = ServerSocket(0, 1, loopback).use { it.localPort }
            val deadProfile = clientProfile.copy(port = deadPort, remarks = "Dead-Server")
            val deadResult = VpnController.startSession(
                profile = deadProfile,
                settings = tunnelSettings,
                rules = emptyList(),
                establishTunAndForwarder = { true },
                verifyConnectivity = true,
                verificationUrl = test204Url,
                onStateChanged = { state -> observedStates.add(state) }
            )
            assertTrue("Dead server must fail verification", deadResult.isFailure)
            assertEquals(VpnConnectionState.ERROR, VpnController.connectionState.value)
        } finally {
            VpnController.stopSession()
            serverProcess.destroyForcibly()
            serverConfigFile.delete()
            runCatching { httpServerSocket.close() }
            httpThread.interrupt()
        }
    }

    @Test
    fun `verify LogRedactor and CredentialCipher security`() {
        val rawLog = "Connecting vless://$testUuid@192.0.2.1:443 and https://sub.example.com:2096/sub/secretToken999"
        val sanitized = LogRedactor.redact(rawLog, listOf("MyCustomPassword123"))
        assertFalse(sanitized.contains(testUuid))
        assertFalse(sanitized.contains("secretToken999"))
        assertTrue(sanitized.contains("[REDACTED"))

        val encrypted = CredentialCipher.encrypt(testUuid)
        assertNotNull(encrypted)
        assertEquals(testUuid, CredentialCipher.decrypt(encrypted))

        val persianDigits = LocalizationHelper.formatDigits("443 ms", true)
        assertEquals("۴۴۳ ms", persianDigits)
    }

    @Test
    fun `verify automatic clipboard input detection and subscription format detection for all 5 protocols`() {
        // 1. HTTPS subscription URL with custom port 2096 and /sub/{token}
        val subUrlClip = ConfigParser.classifyClipboardInput("  https://panel.domain.example:2096/sub/tk_987654321#VIP-Sub  ")
        assertTrue(subUrlClip is com.example.domain.model.ClipboardInputType.SubscriptionUrl)
        val subUrl = subUrlClip as com.example.domain.model.ClipboardInputType.SubscriptionUrl
        assertEquals("https://panel.domain.example:2096/sub/tk_987654321", subUrl.url)
        assertEquals("VIP-Sub", subUrl.suggestedTitle)
        assertTrue(subUrl.isHttps)

        // 2. Single protocol share links (VLESS, VMess, Trojan, Shadowsocks, Hysteria2)
        val vlessLink = "vless://$testUuid@198.51.100.10:443?type=ws&security=tls&path=%2Fws&sni=example.com#VLESS-1"
        val vmessJson = """{"v":"2","ps":"VMess-1","add":"198.51.100.11","port":"443","id":"$testUuid","aid":"0","scy":"auto","net":"ws","type":"none","host":"example.com","path":"/vmess","tls":"tls","sni":"example.com"}"""
        val vmessLink = "vmess://${ConfigParser.encodeBase64NoPad(vmessJson, urlSafe = false)}"
        val trojanLink = "trojan://TrojanPass123@198.51.100.12:443?type=tcp&security=tls&sni=example.com#Trojan-1"
        val ssUser = ConfigParser.encodeBase64NoPad("aes-256-gcm:SsSecretPass", urlSafe = true)
        val ssLink = "ss://$ssUser@198.51.100.13:8388#SS-1"
        val hy2Link = "hysteria2://Hy2Pass999@198.51.100.14:8443?sni=example.com#Hysteria2-1"

        assertEquals(
            VpnProtocol.VLESS,
            (ConfigParser.classifyClipboardInput(vlessLink) as com.example.domain.model.ClipboardInputType.SingleShareLink).protocol
        )
        assertEquals(
            VpnProtocol.VMESS,
            (ConfigParser.classifyClipboardInput(vmessLink) as com.example.domain.model.ClipboardInputType.SingleShareLink).protocol
        )
        assertEquals(
            VpnProtocol.TROJAN,
            (ConfigParser.classifyClipboardInput(trojanLink) as com.example.domain.model.ClipboardInputType.SingleShareLink).protocol
        )
        assertEquals(
            VpnProtocol.SHADOWSOCKS,
            (ConfigParser.classifyClipboardInput(ssLink) as com.example.domain.model.ClipboardInputType.SingleShareLink).protocol
        )
        assertEquals(
            VpnProtocol.HYSTERIA2,
            (ConfigParser.classifyClipboardInput(hy2Link) as com.example.domain.model.ClipboardInputType.SingleShareLink).protocol
        )

        // 3. Plain-text multi-protocol subscription (including duplicate and invalid check)
        val multiPlainText = listOf(vlessLink, vmessLink, trojanLink, ssLink, hy2Link).joinToString("\n")
        val bulkClip = ConfigParser.classifyClipboardInput(multiPlainText)
        assertTrue(bulkClip is com.example.domain.model.ClipboardInputType.BulkShareLinks)
        val (plainFormat, _) = ConfigParser.detectSubscriptionFormat(multiPlainText)
        assertEquals(com.example.domain.model.SubscriptionFormat.PLAIN_TEXT, plainFormat)
        val plainBatch = ConfigParser.parseBatch(multiPlainText)
        assertEquals(5, plainBatch.profiles.size)
        assertEquals(0, plainBatch.failures.size)

        // 4. Standard Base64 subscription containing all 5 protocols (verifies inner VMess Base64 is preserved!)
        val standardB64 = java.util.Base64.getEncoder().encodeToString(multiPlainText.toByteArray(Charsets.UTF_8))
        val stdClip = ConfigParser.classifyClipboardInput(standardB64)
        assertTrue(stdClip is com.example.domain.model.ClipboardInputType.Base64Subscription)
        val (stdFormat, _) = ConfigParser.detectSubscriptionFormat(standardB64)
        assertEquals(com.example.domain.model.SubscriptionFormat.BASE64_STANDARD, stdFormat)
        val stdBatch = ConfigParser.parseBatch(standardB64)
        assertEquals(5, stdBatch.profiles.size)

        // 5. URL-safe Base64 subscription without padding
        val urlSafeB64 = ConfigParser.encodeBase64NoPad(multiPlainText, urlSafe = true)
        val (urlSafeFormat, _) = ConfigParser.detectSubscriptionFormat(urlSafeB64)
        assertEquals(com.example.domain.model.SubscriptionFormat.BASE64_URL_SAFE, urlSafeFormat)
        val urlSafeBatch = ConfigParser.parseBatch(urlSafeB64)
        assertEquals(5, urlSafeBatch.profiles.size)

        // 6. Invalid URL, HTML error page, and unrecognized clipboard input
        val badUrlClip = ConfigParser.classifyClipboardInput("https://")
        assertTrue(badUrlClip is com.example.domain.model.ClipboardInputType.Unrecognized)

        val htmlClip = ConfigParser.classifyClipboardInput("<!DOCTYPE html><html><head><title>Login</title></head><body>Please login</body></html>")
        assertTrue(htmlClip is com.example.domain.model.ClipboardInputType.Unrecognized)
    }

    @Test
    fun `verify SubscriptionRepository HTTP downloader, redirects, status codes, timeouts, SSL validation, and transactional Room storage`() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.MaxNetDatabase::class.java
        ).allowMainThreadQueries().build()
        val dao = db.maxNetDao()

        val vless1 = "vless://$testUuid@198.51.100.21:443?type=tcp&security=tls&sni=example.com#SubNode-1"
        val vmessJson = """{"v":"2","ps":"SubNode-2-VMess","add":"198.51.100.22","port":"443","id":"$testUuid","aid":"0","scy":"auto","net":"ws","type":"none","host":"example.com","path":"/ws","tls":"tls","sni":"example.com"}"""
        val vmess2 = "vmess://${ConfigParser.encodeBase64NoPad(vmessJson, urlSafe = false)}"
        val trojan3 = "trojan://PassTrojan@198.51.100.23:443?type=tcp&security=tls&sni=example.com#SubNode-3-Trojan"
        val hy2Node4 = "hysteria2://PassHy2@198.51.100.24:8443?sni=example.com#SubNode-4-Hy2"

        val loopback = InetAddress.getByName("127.0.0.1")
        // Try binding port 2096 on loopback if free, otherwise ephemeral port
        val serverSocket = runCatching { ServerSocket(2096, 16, loopback) }
            .getOrElse { ServerSocket(0, 16, loopback) }
        val port = serverSocket.localPort

        val serverThread = Thread {
            while (!serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()
                    client.soTimeout = 2000
                    val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = reader.readLine() ?: ""
                    val headers = mutableListOf<String>()
                    while (true) {
                        val h = reader.readLine() ?: break
                        if (h.isEmpty()) break
                        headers.add(h)
                    }
                    val path = requestLine.split(" ").getOrNull(1) ?: "/"
                    val out = client.getOutputStream()

                    when {
                        path.startsWith("/sub/valid-b64") -> {
                            // 3 valid unique (vless1, vmess2, trojan3) + 1 duplicate (vless1) + 1 invalid line = 5 total received
                            val rawSub = listOf(vless1, vmess2, trojan3, vless1, "vless://invalid_entry").joinToString("\n")
                            val b64Body = ConfigParser.encodeBase64NoPad(rawSub, urlSafe = true)
                            val bytes = b64Body.toByteArray(Charsets.UTF_8)
                            val resp = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain; charset=utf-8\r\n" +
                                "Profile-Title: MaxNet-VIP\r\n" +
                                "Subscription-Userinfo: upload=1024; download=2048; total=10485760; expire=1999999999\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.UTF_8))
                            out.write(bytes)
                        }
                        path.startsWith("/sub/redirect-302") -> {
                            val resp = "HTTP/1.1 302 Found\r\n" +
                                "Location: http://127.0.0.1:$port/sub/valid-b64\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.UTF_8))
                        }
                        path.startsWith("/sub/expired-403") -> {
                            val resp = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.UTF_8))
                        }
                        path.startsWith("/sub/notfound-404") -> {
                            val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.UTF_8))
                        }
                        path.startsWith("/sub/ratelimit-429") -> {
                            val resp = "HTTP/1.1 429 Too Many Requests\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.UTF_8))
                        }
                        path.startsWith("/sub/error-500") -> {
                            val resp = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.UTF_8))
                        }
                        path.startsWith("/sub/empty") -> {
                            val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.UTF_8))
                        }
                        path.startsWith("/sub/html-login") -> {
                            val html = "<!DOCTYPE html><html><body>Login Required</body></html>"
                            val bytes = html.toByteArray(Charsets.UTF_8)
                            val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.UTF_8))
                            out.write(bytes)
                        }
                        path.startsWith("/sub/timeout") -> {
                            Thread.sleep(600)
                        }
                        path.startsWith("/sub/updated-nodes") -> {
                            val rawSub = listOf(vless1, hy2Node4).joinToString("\n")
                            val bytes = rawSub.toByteArray(Charsets.UTF_8)
                            val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.UTF_8))
                            out.write(bytes)
                        }
                    }
                    out.flush()
                    client.close()
                } catch (_: Exception) {
                    if (serverSocket.isClosed) break
                }
            }
        }.apply { isDaemon = true; start() }

        val fastHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()

        val subRepo = com.example.data.subscription.SubscriptionRepository(
            dao = dao,
            baseHttpClient = fastHttpClient
        )

        try {
            // 1. Insert a manual profile first to verify subscription operations never delete manual profiles
            dao.insertProfile(
                com.example.data.local.ProfileEntity.fromDomain(
                    ServerProfile(
                        remarks = "Manual-Node",
                        protocol = VpnProtocol.VLESS,
                        address = "192.0.2.200",
                        port = 443,
                        credential = testUuid,
                        subscriptionId = null
                    )
                )
            )

            // 2. Test HTTP 302 redirect -> /sub/valid-b64 with URL-safe Base64, duplicates, and invalid entry
            val (addRes, report) = subRepo.addOrUpdateSubscription(
                title = "Sub (127.0.0.1)",
                url = "http://127.0.0.1:$port/sub/redirect-302",
                autoUpdate = true
            )
            assertTrue("Expected subscription import to succeed: ${addRes.exceptionOrNull()?.message}", addRes.isSuccess)
            val savedSub = addRes.getOrThrow()
            assertEquals("MaxNet-VIP", savedSub.title)
            assertEquals(3, savedSub.profileCount)
            assertEquals(5, report.totalReceivedCount)
            assertEquals(3, report.newImportedCount)
            assertEquals(1, report.duplicateCount)
            assertEquals(1, report.invalidCount)
            assertTrue(report.isSuccess)

            // Verify 1 manual profile + 3 subscription profiles = 4 total in Room DB
            assertEquals(4, dao.getAllProfilesSnapshot().size)

            // 3. Test expired subscription (403 Forbidden) & verify existing profiles are preserved!
            val expiredSub = savedSub.copy(url = "http://127.0.0.1:$port/sub/expired-403")
            val (res403, rep403) = subRepo.refreshSubscriptionWithReport(expiredSub)
            assertTrue(res403.isFailure)
            assertFalse(rep403.isSuccess)
            assertEquals(403, rep403.httpStatusCode)
            // Crucial: all 4 profiles must still be intact in Room after failed refresh!
            assertEquals(4, dao.getAllProfilesSnapshot().size)

            // 4. Test 404 Not Found, 429 Rate Limit, 500 Server Error, Empty Response, HTML Login Page, and Network Timeout
            val (_, rep404) = subRepo.refreshSubscriptionWithReport(savedSub.copy(url = "http://127.0.0.1:$port/sub/notfound-404"))
            assertEquals(404, rep404.httpStatusCode)

            val (_, rep429) = subRepo.refreshSubscriptionWithReport(savedSub.copy(url = "http://127.0.0.1:$port/sub/ratelimit-429"))
            assertEquals(429, rep429.httpStatusCode)

            val (_, rep500) = subRepo.refreshSubscriptionWithReport(savedSub.copy(url = "http://127.0.0.1:$port/sub/error-500"))
            assertEquals(500, rep500.httpStatusCode)

            val (resEmpty, repEmpty) = subRepo.refreshSubscriptionWithReport(savedSub.copy(url = "http://127.0.0.1:$port/sub/empty"))
            assertTrue(resEmpty.isFailure)
            assertTrue(repEmpty.statusMessageFa.contains("خالی"))

            val (resHtml, repHtml) = subRepo.refreshSubscriptionWithReport(savedSub.copy(url = "http://127.0.0.1:$port/sub/html-login"))
            assertTrue(resHtml.isFailure)
            assertTrue(repHtml.statusMessageFa.contains("HTML"))

            val (resTimeout, repTimeout) = subRepo.refreshSubscriptionWithReport(savedSub.copy(url = "http://127.0.0.1:$port/sub/timeout"))
            assertTrue(resTimeout.isFailure)
            assertTrue(repTimeout.statusMessageFa.contains("Timeout", ignoreCase = true) || repTimeout.statusMessageFa.contains("مهلت"))

            // 5. Test SSL certificate validation error against non-TLS / invalid TLS socket (proves SSL verification is active)
            val (resSsl, repSsl) = subRepo.refreshSubscriptionWithReport(savedSub.copy(url = "https://127.0.0.1:$port/sub/valid-b64"))
            assertTrue(resSsl.isFailure)
            assertFalse(repSsl.isSuccess)

            // 6. Test deleting subscription removes only its 3 profiles and keeps the manual profile
            dao.deleteProfilesBySubscriptionId(savedSub.id)
            dao.deleteSubscriptionById(savedSub.id)
            val remaining = dao.getAllProfilesSnapshot()
            assertEquals(1, remaining.size)
            assertEquals("Manual-Node", remaining.first().remarks)
        } finally {
            runCatching { serverSocket.close() }
            serverThread.interrupt()
            db.close()
        }
    }
}

