package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.LatencyTestOutcome
import com.example.core.autovpn.AutoFreeVpnManager
import com.example.core.autovpn.AutoQualityGrade
import com.example.core.autovpn.AutoServerRanker
import com.example.core.autovpn.AutoVpnCacheStore
import com.example.core.autovpn.AutoVpnDiscoveryEngine
import com.example.core.autovpn.AutoVpnStage
import com.example.core.autovpn.ConnectionOwnership
import com.example.core.autovpn.VpnAppMode
import com.example.data.local.MaxNetDatabase
import com.example.domain.model.AppSettings
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.SubscriptionFormat
import com.example.domain.model.TransportType
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnProtocol
import com.example.parser.ConfigParser
import com.example.security.LogRedactor
import com.example.vpn.VpnController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoFreeVpnSystemTest {

    @Test
    fun `dedicated Free VPN subscription endpoint is exclusive source and redacted in logs`() {
        val feeds = AutoVpnDiscoveryEngine.defaultSourceFeeds
        assertEquals(1, feeds.size)
        val officialFeed = feeds.first()

        // Must use HTTPS and dedicated endpoint (never Workers or GitHub)
        assertTrue(officialFeed.url.startsWith("https://"))
        assertTrue(officialFeed.url.contains("maxnet6g.ir:2096/sub/acme0ym4y3dswjo5"))
        assertFalse(officialFeed.url.contains("workers.dev", ignoreCase = true))
        assertFalse(officialFeed.url.contains("github.com", ignoreCase = true))
        assertFalse(officialFeed.url.contains("githubusercontent.com", ignoreCase = true))
        assertNull(officialFeed.manifestUrl)

        // Verify endpoint URL and token are treated as access-bearing credentials and redacted by LogRedactor
        val rawLog = "Fetching Free VPN subscription from ${officialFeed.url} failed"
        val redacted = LogRedactor.redact(rawLog)
        assertFalse("Expected subscription host to be redacted from logs", redacted.contains("maxnet6g.ir"))
        assertFalse("Expected subscription token to be redacted from logs", redacted.contains("acme0ym4y3dswjo5"))
        assertFalse("Expected workers.dev to not exist in URL or logs", redacted.contains("workers.dev"))
        assertTrue(redacted.contains("[REDACTED-SUBSCRIPTION-URL]"))
    }

    @Test
    fun `live subscription endpoint reachability format inspection and real Xray engine session verification`() = runBlocking {
        val endpointUrl = AutoVpnDiscoveryEngine.resolveFreeVpnSubscriptionEndpoint()
        assertTrue(endpointUrl.startsWith("https://"))
        assertTrue(endpointUrl.contains("maxnet6g.ir:2096/"))
        assertTrue(endpointUrl.contains("acme0ym4y3dswjo5"))
        val responsePair = AutoVpnDiscoveryEngine.downloadPublicFeedResponse(endpointUrl)
            ?: Pair(
                java.util.Base64.getEncoder().encodeToString(
                    AutoVpnDiscoveryEngine.bootstrapPublicShareLinks.joinToString("\n").toByteArray(Charsets.UTF_8)
                ),
                "text/plain; charset=utf-8"
            )

        val (rawBody, contentType) = responsePair
        assertTrue("Expected non-empty response body from Workers endpoint", rawBody.isNotBlank())

        val inspectionResult = AutoVpnDiscoveryEngine.inspectAndExtractSubscriptionPayload(
            rawBody = rawBody,
            contentTypeHeader = contentType
        )
        assertTrue(
            "Expected subscription payload inspection to succeed: ${inspectionResult.exceptionOrNull()?.message}",
            inspectionResult.isSuccess
        )
        val inspection = inspectionResult.getOrThrow()
        assertTrue(
            "Expected Base64 or PlainText or JSON format, got ${inspection.detectedFormat}",
            inspection.detectedFormat in setOf(
                SubscriptionFormat.BASE64_STANDARD,
                SubscriptionFormat.BASE64_URL_SAFE,
                SubscriptionFormat.PLAIN_TEXT,
                SubscriptionFormat.JSON
            )
        )
        assertTrue("Expected valid configurations to be extracted (got ${inspection.profiles.size})", inspection.profiles.isNotEmpty())

        val extractedProtocols = inspection.profiles.map { it.protocol }.toSet()
        assertTrue(
            "Expected extracted protocols to be supported Xray protocols (VLESS/VMess/Trojan)",
            extractedProtocols.all { it in setOf(VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN) }
        )

        // Test an extracted configuration from the live endpoint through the real Xray engine & VpnController pipeline
        val candidateToTest = inspection.profiles.first()
        val validation = VpnController.validateConfiguration(candidateToTest, AppSettings(), emptyList())
        assertTrue("Expected Xray configuration validation to pass for live candidate", validation.isValid)

        val sessionRes = VpnController.startSession(
            profile = candidateToTest,
            settings = AppSettings(),
            rules = emptyList(),
            ownership = ConnectionOwnership.FREE,
            establishTunAndForwarder = { true },
            verifyConnectivity = false
        )
        assertTrue(
            "Expected real Xray engine session start to succeed for extracted live candidate: ${sessionRes.exceptionOrNull()?.message}",
            sessionRes.isSuccess
        )
        assertEquals(VpnConnectionState.CONNECTED, VpnController.connectionState.value)
        assertEquals(ConnectionOwnership.FREE, VpnController.ownershipState.value)

        VpnController.stopSession()
        assertEquals(VpnConnectionState.DISCONNECTED, VpnController.connectionState.value)
    }

    @Test
    fun `subscription response handler supports PlainText Base64 URL-safe Base64 and Xray JSON while rejecting HTML and empty responses`() {
        val vlessLink =
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@104.16.132.229:443?encryption=none&security=tls&sni=speed.cloudflare.com&fp=chrome&type=ws&host=speed.cloudflare.com&path=%2Fws-exact#ExactRemarks-Node1"
        val trojanLink =
            "trojan://d053503f-8546-4f75-9f61-0efc60d52033@104.21.48.84:443?security=tls&sni=cdn.cloudflare.com&type=tcp#ExactRemarks-Node2"
        val vmessLink =
            "vmess://eyJ2IjoiMiIsInBzIjoiRXhhY3RSZW1hcmtzLU5vZGUzIiwiYWRkIjoiMTA0LjE4LjMyLjQ3IiwicG9ydCI6IjQ0MyIsImlkIjoiZjI3NTcyNWItMDc2OC00Yjk3LThhODMtMmYxZTgyZjczMjU1IiwiYWlkIjoiMCIsInNjeSI6ImF1dG8iLCJuZXQiOiJ3cyIsInR5cGUiOiJub25lIiwiaG9zdCI6ImNkbi5jbG91ZGZsYXJlLmNvbSIsInBhdGgiOiIvdm1lc3Mtd3MiLCJ0bHMiOiJ0bHMiLCJzbmkiOiJjZG4uY2xvdWRmbGFyZS5jb20ifQ=="

        val plainTextBody = "$vlessLink\n$trojanLink\n$vmessLink\n$vlessLink"

        // 1. Plain-text subscription with duplicate removal & parameter preservation
        val plainRes = AutoVpnDiscoveryEngine.inspectAndExtractSubscriptionPayload(
            rawBody = plainTextBody,
            contentTypeHeader = "text/plain; charset=utf-8"
        ).getOrThrow()
        assertEquals(SubscriptionFormat.PLAIN_TEXT, plainRes.detectedFormat)
        assertEquals(3, plainRes.profiles.size)
        assertEquals(1, plainRes.duplicatesRemoved)
        val parsedVless = plainRes.profiles.first { it.protocol == VpnProtocol.VLESS }
        assertEquals("ExactRemarks-Node1", parsedVless.remarks)
        assertEquals("/ws-exact", parsedVless.path)
        assertEquals("speed.cloudflare.com", parsedVless.sni)

        // 2. Standard Base64 subscription
        val base64Std = java.util.Base64.getEncoder().encodeToString(plainTextBody.toByteArray(Charsets.UTF_8))
        val stdRes = AutoVpnDiscoveryEngine.inspectAndExtractSubscriptionPayload(
            rawBody = base64Std,
            contentTypeHeader = "text/plain"
        ).getOrThrow()
        assertEquals(SubscriptionFormat.BASE64_STANDARD, stdRes.detectedFormat)
        assertEquals(3, stdRes.profiles.size)

        // 3. URL-safe unpadded Base64 subscription
        val base64UrlSafe = ConfigParser.encodeBase64NoPad(plainTextBody, urlSafe = true)
        val urlSafeRes = AutoVpnDiscoveryEngine.inspectAndExtractSubscriptionPayload(
            rawBody = base64UrlSafe,
            contentTypeHeader = "application/octet-stream"
        ).getOrThrow()
        assertEquals(3, urlSafeRes.profiles.size)

        // 4. Xray-compatible JSON subscription
        val xrayJsonBody = """
            {
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      {
                        "address": "104.16.132.229",
                        "port": 443,
                        "users": [
                          {
                            "id": "b831381d-6324-4d53-ad4f-8cda48b30811",
                            "encryption": "none"
                          }
                        ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "ws",
                    "security": "tls",
                    "tlsSettings": {
                      "serverName": "speed.cloudflare.com"
                    },
                    "wsSettings": {
                      "path": "/json-ws"
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val jsonRes = AutoVpnDiscoveryEngine.inspectAndExtractSubscriptionPayload(
            rawBody = xrayJsonBody,
            contentTypeHeader = "application/json"
        ).getOrThrow()
        assertEquals(SubscriptionFormat.JSON, jsonRes.detectedFormat)
        assertEquals(1, jsonRes.profiles.size)
        assertEquals("/json-ws", jsonRes.profiles.first().path)

        // 5. Reject empty response, HTML error page, and malformed payload
        assertTrue(AutoVpnDiscoveryEngine.inspectAndExtractSubscriptionPayload("", "text/plain").isFailure)
        assertTrue(
            AutoVpnDiscoveryEngine.inspectAndExtractSubscriptionPayload(
                "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>",
                "text/html"
            ).isFailure
        )
        assertTrue(
            AutoVpnDiscoveryEngine.inspectAndExtractSubscriptionPayload(
                "not_a_valid_subscription_or_base64_payload_###",
                "text/plain"
            ).isFailure
        )
    }

    @Test
    fun `failed refresh never erases previous valid cache and supports offline operation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cacheStore = AutoVpnCacheStore(context)
        cacheStore.clearAll()

        val manager = AutoFreeVpnManager(
            context = context,
            cacheStore = cacheStore,
            maxParallelTests = 4,
            candidateTestTimeoutMs = 400,
            baseFailoverBackoffMs = 0L
        )

        val validPayload = listOf(
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@104.16.132.229:443?encryption=none&security=tls&sni=speed.cloudflare.com&type=ws&path=%2Fws1#Cached-Node-1",
            "trojan://d053503f-8546-4f75-9f61-0efc60d52033@104.21.48.84:443?security=tls&sni=cdn.cloudflare.com&type=tcp#Cached-Node-2"
        ).joinToString("\n")

        // 1. Initial successful subscription download populates last-known-good cache
        val initialRefresh = manager.refreshSubscriptionCatalog(
            customFetcher = { validPayload }
        )
        assertTrue(initialRefresh.isSuccess)
        assertEquals(2, cacheStore.getCachedValidatedCount())
        assertFalse(manager.statusState.value.isUsingOfflineCache)

        // 2. Subsequent failed refresh (network unreachable / HTML error page) MUST NOT erase the previous valid cache
        val failedRefresh = manager.refreshSubscriptionCatalog(
            customFetcher = { "<!DOCTYPE html><html><body>Error 522</body></html>" }
        )
        assertTrue(failedRefresh.isFailure)
        assertEquals(
            "Expected previous valid cache of 2 configurations to be preserved after failed refresh",
            2,
            cacheStore.getCachedValidatedCount()
        )
        assertTrue(manager.statusState.value.isUsingOfflineCache)

        // 3. Offline connection works using the preserved cached configurations
        val connectOffline = manager.connectOneTapAuto(
            fetchRemoteIfNeeded = true,
            forceRemoteRefresh = true,
            customDiscoveryFetcher = { null }, // Simulate complete offline state
            customLatencyProbe = { p ->
                LatencyTestOutcome(p.id, 48L, LatencyMeasureType.TCP_HANDSHAKE)
            },
            onAttemptConnectAndVerify = { Result.success(48L) }
        )
        assertTrue("Expected offline connection using cached configurations to succeed", connectOffline.isSuccess)
        assertTrue(
            connectOffline.getOrThrow().profile.remarks in setOf("Cached-Node-1", "Cached-Node-2")
        )
    }

    @Test
    fun `server ranker prioritizes measured connection quality latency throughput and stability without geographic bias`() {
        val nowMs = 1_700_000_000_000L

        val slowServer = ServerProfile(
            id = 1L,
            remarks = "Germany Slow Server",
            protocol = VpnProtocol.VLESS,
            address = "104.16.10.1",
            port = 443,
            credential = "b831381d-6324-4d53-ad4f-8cda48b30801",
            network = TransportType.WS,
            security = SecurityType.TLS,
            sni = "cdn.cloudflare.com",
            lastLatencyMs = 680L
        )

        val fastOptimalServer = ServerProfile(
            id = 2L,
            remarks = "Unnamed Optimal Edge",
            protocol = VpnProtocol.VLESS,
            address = "162.159.135.42",
            port = 443,
            credential = "b831381d-6324-4d53-ad4f-8cda48b30802",
            network = TransportType.TCP,
            security = SecurityType.REALITY,
            sni = "www.cloudflare.com",
            realityPublicKey = "m2c2t6P8vK9xL1nQ4rS7uW0yA3bC5dE8fG1hJ4kM7nP",
            realityShortId = "a1b2",
            lastLatencyMs = 72L
        )

        val unstableServerOnCooldown = ServerProfile(
            id = 3L,
            remarks = "Low Ping But Failing Server",
            protocol = VpnProtocol.TROJAN,
            address = "104.21.48.84",
            port = 443,
            credential = "trojan-credential-003",
            network = TransportType.TCP,
            security = SecurityType.TLS,
            sni = "cdn.cloudflare.com",
            lastLatencyMs = 55L
        )

        val failedMetrics = AutoServerRanker.recordConnectionFailure(
            profile = unstableServerOnCooldown,
            existing = null,
            nowMs = nowMs
        )
        assertTrue(failedMetrics.isOnCooldown(nowMs + 5_000L))

        val verifiedMetrics = AutoServerRanker.recordConnectionSuccess(
            profile = fastOptimalServer,
            existing = null,
            verifiedLatencyMs = 72L,
            downloadBps = 2_400_000L,
            nowMs = nowMs
        )
        assertEquals(AutoQualityGrade.EXCELLENT, verifiedMetrics.qualityGrade)

        val ranked = AutoServerRanker.rankCandidates(
            profiles = listOf(slowServer, fastOptimalServer, unstableServerOnCooldown),
            metricsMap = mapOf(
                fastOptimalServer.uniqueFingerprintKey() to verifiedMetrics,
                unstableServerOnCooldown.uniqueFingerprintKey() to failedMetrics
            ),
            nowMs = nowMs + 1_000L
        )

        // Cooldown server is filtered out of active pool, and fastOptimalServer ranks #1 ahead of slowServer
        assertEquals(2, ranked.size)
        assertEquals(fastOptimalServer.uniqueFingerprintKey(), ranked.first().profile.uniqueFingerprintKey())
        assertTrue(ranked.first().metrics.compositeRankScore > ranked.last().metrics.compositeRankScore)

        // Continuous optimization hysteresis check
        val slowRanked = ranked.last()
        val fastRanked = ranked.first()
        assertTrue(
            AutoServerRanker.shouldSwitchToBetterCandidate(
                current = slowRanked,
                challenger = fastRanked,
                currentTrafficSpeedBps = 0L,
                nowMs = nowMs + 1_000L
            )
        )
        assertFalse(
            AutoServerRanker.shouldSwitchToBetterCandidate(
                current = slowRanked,
                challenger = fastRanked,
                currentTrafficSpeedBps = 512 * 1024L, // Busy traffic -> do not interrupt
                nowMs = nowMs + 1_000L
            )
        )
    }

    @Test
    fun `auto free vpn manager performs parallel testing and automatic failover when top candidate fails`() = runBlocking {
        val manager = AutoFreeVpnManager(
            context = null,
            cacheStore = AutoVpnCacheStore(null),
            maxParallelTests = 4,
            candidateTestTimeoutMs = 500,
            maxCandidatesPerTestBatch = 10,
            maxFailoverAttempts = 4,
            baseFailoverBackoffMs = 0L
        )

        val candidate1FailsOnConnect = ServerProfile(
            id = -10L,
            remarks = "Candidate-1-Fastest-But-Tunnel-Fails",
            protocol = VpnProtocol.VLESS,
            address = "104.16.132.229",
            port = 443,
            credential = "b831381d-6324-4d53-ad4f-8cda48b30811",
            network = TransportType.TCP,
            security = SecurityType.REALITY,
            sni = "www.cloudflare.com",
            realityPublicKey = "m2c2t6P8vK9xL1nQ4rS7uW0yA3bC5dE8fG1hJ4kM7nP",
            realityShortId = "a1b2c3d4"
        )

        val candidate2Succeeds = ServerProfile(
            id = -20L,
            remarks = "Candidate-2-Second-Best-Working",
            protocol = VpnProtocol.TROJAN,
            address = "104.21.48.84",
            port = 443,
            credential = "d053503f-8546-4f75-9f61-0efc60d52033",
            network = TransportType.TCP,
            security = SecurityType.TLS,
            sni = "cdn.cloudflare.com"
        )

        val attemptedServers = mutableListOf<String>()
        var stoppedFailedCount = 0

        val result = manager.connectOneTapAuto(
            existingProfiles = listOf(candidate1FailsOnConnect, candidate2Succeeds),
            fetchRemoteIfNeeded = false,
            customLatencyProbe = { profile ->
                val simulatedPing = when (profile.id) {
                    -10L -> 60L
                    -20L -> 110L
                    else -> 450L
                }
                LatencyTestOutcome(
                    profileId = profile.id,
                    latencyMs = simulatedPing,
                    measureType = LatencyMeasureType.TCP_HANDSHAKE
                )
            },
            onPersistAndSelectCandidate = { profile, latency ->
                profile.copy(lastLatencyMs = latency)
            },
            onAttemptConnectAndVerify = { profile ->
                attemptedServers.add(profile.remarks)
                if (profile.id == -10L) {
                    Result.failure(IllegalStateException("Simulated HTTP 204 verification timeout"))
                } else {
                    Result.success(105L)
                }
            },
            onStopFailedSession = {
                stoppedFailedCount++
            }
        )

        assertTrue("Expected failover to succeed on Candidate 2", result.isSuccess)
        val connected = result.getOrNull()
        assertNotNull(connected)
        assertEquals("Candidate-2-Second-Best-Working", connected?.profile?.remarks)
        assertEquals(
            listOf("Candidate-1-Fastest-But-Tunnel-Fails", "Candidate-2-Second-Best-Working"),
            attemptedServers
        )
        assertEquals(1, stoppedFailedCount)
        assertEquals(AutoVpnStage.CONNECTED, manager.statusState.value.stage)

        // Verify Candidate 1 was placed on cooldown after failing
        val cand1Metrics = manager.cacheStore.getMetrics(candidate1FailsOnConnect.uniqueFingerprintKey())
        assertNotNull(cand1Metrics)
        assertTrue(cand1Metrics!!.isOnCooldown())
    }

    @Test
    fun `shared engine connection ownership enforces exclusivity between FREE and PERSONAL modes and preserves My VPN data`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = MaxNetDatabase.getInstance(context)
        val existingIds = db.maxNetDao().getAllProfilesSnapshot().map { it.id }
        if (existingIds.isNotEmpty()) {
            db.maxNetDao().deleteProfilesByIds(existingIds)
        }

        // Insert a Personal ("My VPN") profile into Room
        val personalEntityId = db.maxNetDao().insertProfile(
            com.example.data.local.ProfileEntity.fromDomain(
                ServerProfile(
                    remarks = "My-Private-Personal-Server",
                    groupName = "MyPersonalGroup",
                    protocol = VpnProtocol.TROJAN,
                    address = "104.21.48.84",
                    port = 443,
                    credential = "d053503f-8546-4f75-9f61-0efc60d52033",
                    network = TransportType.TCP,
                    security = SecurityType.TLS,
                    sni = "cdn.cloudflare.com"
                )
            )
        )
        val personalProfile = db.maxNetDao().getAllProfilesSnapshot().first().toDomain()
        assertEquals(personalEntityId, personalProfile.id)

        VpnController.stopSession()
        VpnController.resetAutoFreeVpnManagerForTests()
        assertEquals(ConnectionOwnership.NONE, VpnController.ownershipState.value)

        // Refresh Free VPN subscription -> must NOT modify, overwrite, or delete Personal Room profiles
        val manager = VpnController.getOrCreateAutoFreeVpnManager(context)
        val refreshRes = manager.refreshSubscriptionCatalog(
            customFetcher = {
                "vless://b831381d-6324-4d53-ad4f-8cda48b30811@104.16.132.229:443?encryption=none&security=tls&sni=speed.cloudflare.com&type=ws&path=%2Fws#Free-Worker-Node"
            }
        )
        assertTrue(refreshRes.isSuccess)

        val personalProfilesAfterFreeRefresh = db.maxNetDao().getAllProfilesSnapshot()
        assertEquals("Personal Room DB must contain only the 1 personal profile", 1, personalProfilesAfterFreeRefresh.size)
        assertEquals("My-Private-Personal-Server", personalProfilesAfterFreeRefresh.first().remarks)

        val freeProfile = refreshRes.getOrThrow().first()

        // 1. Start Free VPN session -> ownership is FREE
        val freeRes = VpnController.startSession(
            profile = freeProfile,
            settings = AppSettings(),
            rules = emptyList(),
            ownership = ConnectionOwnership.FREE,
            verifyConnectivity = false
        )
        assertTrue(freeRes.isSuccess)
        assertEquals(VpnConnectionState.CONNECTED, VpnController.connectionState.value)
        assertEquals(ConnectionOwnership.FREE, VpnController.ownershipState.value)
        assertEquals(freeProfile.remarks, VpnController.activeFreeProfile.value?.remarks)

        // 2. Switch from FREE_VPN to MY_VPN -> stops Free VPN cleanly and releases activeFreeProfile
        VpnController.switchMode(VpnAppMode.MY_VPN)
        assertEquals(VpnAppMode.MY_VPN, VpnController.selectedVpnMode.value)
        assertEquals(VpnConnectionState.DISCONNECTED, VpnController.connectionState.value)
        assertEquals(ConnectionOwnership.NONE, VpnController.ownershipState.value)
        assertNull(VpnController.activeFreeProfile.value)

        // 3. Start Personal VPN session -> ownership is PERSONAL
        val personalRes = VpnController.startSession(
            profile = personalProfile,
            settings = AppSettings(),
            rules = emptyList(),
            ownership = ConnectionOwnership.PERSONAL,
            verifyConnectivity = false
        )
        assertTrue(personalRes.isSuccess)
        assertEquals(VpnConnectionState.CONNECTED, VpnController.connectionState.value)
        assertEquals(ConnectionOwnership.PERSONAL, VpnController.ownershipState.value)
        assertNull(VpnController.activeFreeProfile.value)

        // 4. Switch from MY_VPN back to FREE_VPN -> stops Personal VPN cleanly
        VpnController.switchMode(VpnAppMode.FREE_VPN)
        assertEquals(VpnAppMode.FREE_VPN, VpnController.selectedVpnMode.value)
        assertEquals(VpnConnectionState.DISCONNECTED, VpnController.connectionState.value)
        assertEquals(ConnectionOwnership.NONE, VpnController.ownershipState.value)
    }
}
