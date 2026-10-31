package com.example

import com.example.core.LatencyTestOutcome
import com.example.core.autovpn.AutoFreeVpnManager
import com.example.core.autovpn.AutoQualityGrade
import com.example.core.autovpn.AutoServerRanker
import com.example.core.autovpn.AutoVpnCacheStore
import com.example.core.autovpn.AutoVpnDiscoveryEngine
import com.example.core.autovpn.AutoVpnStage
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType
import com.example.domain.model.VpnProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoFreeVpnSystemTest {

    @Test
    fun `discovery engine parses validates normalizes and deduplicates VLESS Reality VMess and Trojan while rejecting untrusted inputs`() {
        val vlessReality =
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@104.16.132.229:443?encryption=none&security=reality&sni=www.cloudflare.com&fp=chrome&pbk=m2c2t6P8vK9xL1nQ4rS7uW0yA3bC5dE8fG1hJ4kM7nP&sid=a1b2c3d4&type=tcp&flow=xtls-rprx-vision#Node-A"
        val vlessRealityDuplicate =
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@104.16.132.229:443?encryption=none&security=reality&sni=www.cloudflare.com&fp=chrome&pbk=m2c2t6P8vK9xL1nQ4rS7uW0yA3bC5dE8fG1hJ4kM7nP&sid=a1b2c3d4&type=tcp&flow=xtls-rprx-vision#Duplicate-Label"
        val vlessTls =
            "vless://c942492e-7435-4e64-be50-9deb59c41922@172.67.74.226:443?encryption=none&security=tls&sni=speed.cloudflare.com&fp=chrome&type=ws&host=speed.cloudflare.com&path=%2Fws#Node-B"
        val trojanTls =
            "trojan://d053503f-8546-4f75-9f61-0efc60d52033@104.21.48.84:443?security=tls&sni=cdn.cloudflare.com&type=tcp#Node-C"
        val vmessTls =
            "vmess://eyJ2IjoiMiIsInBzIjoiTm9kZS1EIiwiYWRkIjoiMTA0LjE4LjMyLjQ3IiwicG9ydCI6IjQ0MyIsImlkIjoiZjI3NTcyNWItMDc2OC00Yjk3LThhODMtMmYxZTgyZjczMjU1IiwiYWlkIjoiMCIsInNjeSI6ImF1dG8iLCJuZXQiOiJ3cyIsInR5cGUiOiJub25lIiwiaG9zdCI6ImNkbi5jbG91ZGZsYXJlLmNvbSIsInBhdGgiOiIvdm1lc3Mtd3MiLCJ0bHMiOiJ0bHMiLCJzbmkiOiJjZG4uY2xvdWRmbGFyZS5jb20ifQ=="

        // Unsafe / untrusted configs that MUST be rejected
        val loopbackVless =
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@127.0.0.1:443?security=tls&sni=example.com#Loopback"
        val privateLanTrojan =
            "trojan://secretpass@192.168.1.10:443?security=tls&sni=example.com#PrivateLan"
        val scriptPayload =
            "<script>eval('malicious')</script>\nvless://b831381d-6324-4d53-ad4f-8cda48b30811@104.16.132.229:443#Scripted"

        val backendJsonFeed = """
            {
              "schema_version": "1.0",
              "candidates": [
                {"protocol": "VLESS_REALITY", "uri": "$vlessReality"},
                {"protocol": "VLESS_REALITY", "uri": "$vlessRealityDuplicate"},
                {"protocol": "VLESS", "uri": "$vlessTls"},
                {"protocol": "TROJAN", "uri": "$trojanTls"},
                {"protocol": "VMESS", "uri": "$vmessTls"},
                {"protocol": "VLESS", "uri": "$loopbackVless"},
                {"protocol": "TROJAN", "uri": "$privateLanTrojan"}
              ]
            }
        """.trimIndent()

        val validated = AutoVpnDiscoveryEngine.parseAndValidateUntrustedPayloads(
            rawPayloads = listOf(backendJsonFeed, scriptPayload)
        )

        // Exactly 4 unique valid public configs (VLESS Reality duplicate merged; loopback, LAN, and script payload rejected)
        assertEquals(4, validated.size)
        val protocols = validated.map { it.protocol }.toSet()
        assertTrue(protocols.contains(VpnProtocol.VLESS))
        assertTrue(protocols.contains(VpnProtocol.TROJAN))
        assertTrue(protocols.contains(VpnProtocol.VMESS))
        assertTrue(validated.any { it.security == SecurityType.REALITY && it.realityPublicKey.isNotBlank() })
        assertFalse(validated.any { it.address == "127.0.0.1" || it.address.startsWith("192.168.") })
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

        // Continuous optimization hysteresis check:
        // Should switch from slowServer to fastOptimalServer when traffic is idle,
        // but NOT switch when user is actively downloading at high speed
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
            id = 10L,
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
            id = 20L,
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
                    10L -> 60L
                    20L -> 110L
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
                if (profile.id == 10L) {
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
}
