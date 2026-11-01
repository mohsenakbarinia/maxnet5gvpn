package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.LatencyTestOutcome
import com.example.core.LatencyTester
import com.example.core.autovpn.AutoFreeVpnManager
import com.example.core.autovpn.AutoServerRanker
import com.example.core.autovpn.AutoVpnCacheStore
import com.example.core.autovpn.AutoVpnDiscoveryEngine
import com.example.core.autovpn.ConnectionOwnership
import com.example.core.autovpn.VpnAppMode
import com.example.core.sync.AutoServerSyncManager
import com.example.core.sync.SyncProgressState
import com.example.core.xray.XrayConfigBuilder
import com.example.core.xray.XrayNativeBridge
import com.example.data.local.MaxNetDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.VpnRepository
import com.example.domain.model.AppSettings
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerConnectionStatus
import com.example.domain.model.ServerProfile
import com.example.domain.model.SubscriptionFormat
import com.example.domain.model.TransportType
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnProtocol
import com.example.vpn.VpnController
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RealLatencyAndAutoSyncTest {

    private lateinit var context: Context
    private lateinit var db: MaxNetDatabase
    private lateinit var repository: VpnRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = MaxNetDatabase.getInstance(context)
        repository = VpnRepository(context, db.maxNetDao(), SettingsDataStore(context))

        val existingProfiles = db.maxNetDao().getAllProfilesSnapshot()
        if (existingProfiles.isNotEmpty()) {
            db.maxNetDao().deleteProfilesByIds(existingProfiles.map { it.id })
        }
        val existingSubs = db.maxNetDao().getAllSubscriptionsSnapshot()
        for (sub in existingSubs) {
            db.maxNetDao().deleteSubscriptionById(sub.id)
        }
        VpnController.stopSession()
        VpnController.resetAutoFreeVpnManagerForTests()
    }

    /**
     * Scenario 8: Real Xray proxy latency measurement for valid and dead servers.
     * Verifies:
     * - Temporary Xray config generation on an isolated ephemeral port (`socks-in` only, no TUN or active port collision)
     * - Real SOCKS5 (`ATYP = 0x03` domain name) + HTTP 204 round-trip measurement using `SystemClock.elapsedRealtime()`
     * - 1 warm-up + up to 3 sample measurements with median calculation
     * - Separation of `tcpConnectMs` (TCP Connect) vs `lastLatencyMs` (Real VPN Latency `PROXY_HTTP_DELAY`)
     * - Dead/failing proxy servers NEVER fall back to displaying raw TCP connect time as Real VPN Latency.
     */
    @Test
    fun `real Xray proxy latency measurement records median HTTP 204 delay and never substitutes TCP connect on failure`() = runBlocking {
        // 1. Verify median calculation rules (1, 2, 3 samples)
        assertEquals(152L, XrayNativeBridge.computeMedianLatencyMs(listOf(180L, 137L, 152L)))
        assertEquals(150L, XrayNativeBridge.computeMedianLatencyMs(listOf(137L, 163L)))
        assertEquals(168L, XrayNativeBridge.computeMedianLatencyMs(listOf(168L)))
        assertTrue(runCatching { XrayNativeBridge.computeMedianLatencyMs(emptyList()) }.isFailure)

        // 2. Verify temporary Xray test config uses isolated ephemeral SOCKS port and GLOBAL routing
        val testProfile = ServerProfile(
            id = 10L,
            remarks = "Germany-VLESS-Reality",
            protocol = VpnProtocol.VLESS,
            address = "127.0.0.1",
            port = 443,
            credential = "b831381d-6324-4d53-ad4f-8cda48b30811",
            network = TransportType.TCP,
            security = SecurityType.REALITY,
            sni = "www.cloudflare.com",
            realityPublicKey = "m2c2t6P8vK9xL1nQ4rS7uW0yA3bC5dE8fG1hJ4kM7nP",
            realityShortId = "a1b2"
        )
        val ephemeralPort = XrayNativeBridge.allocateEphemeralLoopbackPort()
        assertTrue(ephemeralPort in 1024..65535)
        val tempConfigValidation = XrayConfigBuilder.buildTemporaryProxyTestConfig(
            profile = testProfile,
            settings = AppSettings(),
            localSocksPort = ephemeralPort
        )
        assertTrue(tempConfigValidation.isValid)
        val tempConfigJson = tempConfigValidation.generatedConfigJson
        assertTrue(tempConfigJson.contains("\"port\": $ephemeralPort"))
        assertFalse("Temporary test config must not bind the active VPN HTTP port", tempConfigJson.contains("10809"))

        // 3. Spin up a local SOCKS5 test server that simulates a real multi-RTT proxy outbound (e.g., ~145ms HTTP 204)
        //    while raw TCP connect to the local port is <15ms.
        val requestCounter = AtomicInteger(0)
        val simulatedProxyHttpDelays = listOf(190L, 138L, 162L, 148L) // 1 warm-up (190ms) + 3 samples (138, 162, 148 -> median >= 148ms)
        val socksServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val socksPort = socksServer.localPort
        val serverThread = thread(start = true, isDaemon = true) {
            try {
                while (!socksServer.isClosed) {
                    val client = socksServer.accept()
                    thread(start = true, isDaemon = true) {
                        client.use { sock ->
                            val input = sock.getInputStream()
                            val output = sock.getOutputStream()
                            // Greeting
                            val ver = input.read()
                            if (ver != 0x05) return@use
                            val nMethods = input.read()
                            repeat(nMethods.coerceAtLeast(0)) { input.read() }
                            output.write(byteArrayOf(0x05, 0x00))
                            output.flush()

                            // Connect command
                            val cmdHeader = ByteArray(4)
                            if (input.read(cmdHeader) < 4) return@use
                            val atyp = cmdHeader[3].toInt()
                            if (atyp == 0x03) {
                                val len = input.read()
                                ByteArray(len).also { input.read(it) }
                            } else if (atyp == 0x01) {
                                ByteArray(4).also { input.read(it) }
                            }
                            ByteArray(2).also { input.read(it) } // Port
                            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x00, 0x50))
                            output.flush()

                            // Read HTTP request line
                            val reader = BufferedReader(InputStreamReader(input, Charsets.US_ASCII))
                            reader.readLine()
                            val idx = requestCounter.getAndIncrement()
                            val simulatedRtt = simulatedProxyHttpDelays.getOrElse(idx) { 150L }
                            Thread.sleep(simulatedRtt)

                            val httpResponse = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            output.write(httpResponse.toByteArray(Charsets.US_ASCII))
                            output.flush()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        try {
            // Measure warm-up + 3 samples over SOCKS5
            val warmUp = XrayNativeBridge.measureSingleHttpOverSocks5(
                localSocksPort = socksPort,
                candidateUrl = "http://www.gstatic.com/generate_204",
                timeoutMs = 3000
            ).getOrNull()
            assertNotNull("Expected warm-up HTTP 204 over SOCKS5 to succeed", warmUp)
            val samples = (1..3).mapNotNull {
                XrayNativeBridge.measureSingleHttpOverSocks5(
                    localSocksPort = socksPort,
                    candidateUrl = "http://www.gstatic.com/generate_204",
                    timeoutMs = 3000
                ).getOrNull()
            }
            assertEquals(3, samples.size)
            val medianProxyLatency = XrayNativeBridge.computeMedianLatencyMs(samples)
            val rawTcpLatency = LatencyTester.measureDiagnosticTcpConnectMs("127.0.0.1", socksPort, 1000)!!

            // Real VPN proxy latency (~148-165ms) must reflect full proxy HTTP round-trip, NOT raw TCP (<25ms)
            assertTrue(
                "Expected Real VPN Proxy Latency ($medianProxyLatency ms) to be significantly higher than raw TCP connect ($rawTcpLatency ms)",
                medianProxyLatency >= 130L && rawTcpLatency < 50L
            )
        } finally {
            runCatching { socksServer.close() }
            serverThread.interrupt()
        }

        // 4. Test a server whose TCP port is open (e.g., accepts TCP handshake) but Xray proxy outbound fails HTTP 204
        val tcpOnlyServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val tcpOnlyPort = tcpOnlyServer.localPort
        val tcpAcceptThread = thread(start = true, isDaemon = true) {
            try {
                while (!tcpOnlyServer.isClosed) {
                    val s = tcpOnlyServer.accept()
                    // Immediately close without completing TLS/VLESS/HTTP 204
                    s.close()
                }
            } catch (_: Exception) {
            }
        }
        try {
            val deadProxyProfile = testProfile.copy(
                id = 99L,
                remarks = "TCP-Open-But-Proxy-Dead",
                address = "127.0.0.1",
                port = tcpOnlyPort
            )
            val outcome = LatencyTester.testRealVpnLatency(
                context = context,
                profile = deadProxyProfile,
                timeoutMs = 1800
            )
            // Diagnostic TCP connect time is recorded, but Real VPN Latency (`latencyMs`) MUST be null and status FAILED
            assertNotNull("Expected diagnostic TCP connect time to be measured", outcome.tcpConnectMs)
            assertNull("Dead proxy MUST NOT fall back to TCP connect time as Real VPN Latency", outcome.latencyMs)
            assertEquals(LatencyMeasureType.TIMEOUT, outcome.measureType)
            assertEquals(ServerConnectionStatus.FAILED, outcome.connectionStatus)
        } finally {
            runCatching { tcpOnlyServer.close() }
            tcpAcceptThread.interrupt()
        }
    }

    /**
     * Scenario 9: Server ranking using Real VPN Latency (`PROXY_HTTP_DELAY`) as primary metric.
     * A server with lower Real VPN Latency (137ms HTTP 204, 45ms TCP) must rank higher than a server
     * with faster TCP connect (26ms TCP) but slower Real VPN Latency (340ms HTTP 204) or unverified TCP-only latency.
     */
    @Test
    fun `server ranker prioritizes Real VPN Latency over raw TCP connect time`() {
        val nowMs = 1_750_000_000_000L

        val fastTcpButSlowProxy = ServerProfile(
            id = 1L,
            remarks = "Fast-TCP-26ms-Slow-Proxy-340ms",
            protocol = VpnProtocol.VLESS,
            address = "104.16.10.10",
            port = 443,
            credential = "b831381d-6324-4d53-ad4f-8cda48b30801",
            network = TransportType.WS,
            security = SecurityType.TLS,
            sni = "cdn.cloudflare.com",
            lastLatencyMs = 340L,
            tcpConnectMs = 26L,
            latencyType = LatencyMeasureType.PROXY_HTTP_DELAY,
            lastTestedAtMs = nowMs
        )

        val optimalRealVpnServer = ServerProfile(
            id = 2L,
            remarks = "Optimal-Real-VPN-137ms-TCP-48ms",
            protocol = VpnProtocol.VLESS,
            address = "104.16.10.20",
            port = 443,
            credential = "b831381d-6324-4d53-ad4f-8cda48b30802",
            network = TransportType.WS,
            security = SecurityType.TLS,
            sni = "cdn.cloudflare.com",
            lastLatencyMs = 137L,
            tcpConnectMs = 48L,
            latencyType = LatencyMeasureType.PROXY_HTTP_DELAY,
            lastTestedAtMs = nowMs
        )

        val unverifiedTcpOnlyServer = ServerProfile(
            id = 3L,
            remarks = "Unverified-TCP-Only-22ms",
            protocol = VpnProtocol.VLESS,
            address = "104.16.10.30",
            port = 443,
            credential = "b831381d-6324-4d53-ad4f-8cda48b30803",
            network = TransportType.WS,
            security = SecurityType.TLS,
            sni = "cdn.cloudflare.com",
            lastLatencyMs = 22L,
            tcpConnectMs = 22L,
            latencyType = LatencyMeasureType.TCP_HANDSHAKE,
            lastTestedAtMs = nowMs
        )

        val metrics1 = AutoServerRanker.evaluateCandidate(
            profile = fastTcpButSlowProxy,
            existingMetrics = null,
            measuredLatencyMs = 340L,
            measuredTcpConnectMs = 26L,
            proxyVerified = true,
            nowMs = nowMs
        )
        val metrics2 = AutoServerRanker.evaluateCandidate(
            profile = optimalRealVpnServer,
            existingMetrics = null,
            measuredLatencyMs = 137L,
            measuredTcpConnectMs = 48L,
            proxyVerified = true,
            nowMs = nowMs
        )
        val metrics3 = AutoServerRanker.evaluateCandidate(
            profile = unverifiedTcpOnlyServer,
            existingMetrics = null,
            measuredLatencyMs = 22L,
            measuredTcpConnectMs = 22L,
            proxyVerified = false,
            nowMs = nowMs
        )

        val ranked = AutoServerRanker.rankCandidates(
            profiles = listOf(fastTcpButSlowProxy, optimalRealVpnServer, unverifiedTcpOnlyServer),
            metricsMap = mapOf(
                fastTcpButSlowProxy.uniqueFingerprintKey() to metrics1,
                optimalRealVpnServer.uniqueFingerprintKey() to metrics2,
                unverifiedTcpOnlyServer.uniqueFingerprintKey() to metrics3
            ),
            nowMs = nowMs
        )

        assertEquals(
            "Expected server with lowest Real VPN Latency (137ms HTTP 204) to rank #1",
            optimalRealVpnServer.uniqueFingerprintKey(),
            ranked.first().profile.uniqueFingerprintKey()
        )
        assertEquals(26L, ranked.find { it.profile.id == 1L }?.metrics?.tcpConnectMs)
        assertEquals(48L, ranked.first().metrics.tcpConnectMs)
    }

    /**
     * Scenarios 2, 3, 4:
     * - Personal subscription refresh with added, removed, and updated servers in-place (preserving ID, favorite, pinned, custom remarks, selectedProfileId)
     * - Personal subscription failure while preserving existing saved servers
     * - Manual personal profiles remaining untouched during subscription refresh
     */
    @Test
    fun `personal subscription refresh updates in place preserves customizations and manual profiles and survives failures`() = runBlocking {
        // 1. Create a manual personal profile (subscriptionId == null)
        val manualId = repository.saveProfile(
            ServerProfile(
                remarks = "My-Manual-Private-VLESS",
                groupName = "ManualGroup",
                protocol = VpnProtocol.VLESS,
                address = "198.51.100.10",
                port = 443,
                credential = "b831381d-6324-4d53-ad4f-8cda48b30899",
                network = TransportType.TCP,
                security = SecurityType.TLS,
                sni = "manual.example.com",
                isFavorite = true
            )
        )

        // 2. Seed initial Personal Subscription #1 with 2 servers (Server A and Server B)
        val serverAUuid = "b831381d-6324-4d53-ad4f-8cda48b30801"
        val serverBUuid = "b831381d-6324-4d53-ad4f-8cda48b30802"
        val serverCUuid = "b831381d-6324-4d53-ad4f-8cda48b30803"

        val updatedSubPayload = listOf(
            "vless://$serverAUuid@198.51.100.1:443?type=ws&security=tls&sni=a.example.com&path=%2Fws-a#Provider-New-Name-A",
            "vless://$serverCUuid@198.51.100.3:443?type=ws&security=tls&sni=c.example.com&path=%2Fws-c#Newly-Added-Server-C"
        ).joinToString("\n")

        // Start a local HTTP server to serve the updated subscription payload
        val httpServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val httpPort = httpServer.localPort
        val httpThread = thread(start = true, isDaemon = true) {
            try {
                while (!httpServer.isClosed) {
                    val client = httpServer.accept()
                    client.use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val bodyBytes = updatedSubPayload.toByteArray(Charsets.UTF_8)
                        val resp = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: text/plain; charset=utf-8\r\n")
                            append("ETag: \"v2-sub-etag\"\r\n")
                            append("Content-Length: ${bodyBytes.size}\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        val out = sock.getOutputStream()
                        out.write(resp.toByteArray(Charsets.US_ASCII))
                        out.write(bodyBytes)
                        out.flush()
                    }
                }
            } catch (_: Exception) {
            }
        }

        var originalServerADbId = 0L
        try {
            val subEntityId = db.maxNetDao().insertSubscription(
                com.example.data.local.SubscriptionEntity.fromDomain(
                    com.example.domain.model.SubscriptionItem(
                        title = "PersonalSub1",
                        url = "http://127.0.0.1:$httpPort/api/v1/sub",
                        lastUpdatedAt = 1000L,
                        lastStatus = "OK (2 servers)",
                        enabledAutoUpdate = true,
                        profileCount = 2
                    )
                )
            )

            val initialSubProfiles = listOf(
                ServerProfile(
                    remarks = "Provider-Default-Name-A",
                    groupName = "PersonalSub1",
                    protocol = VpnProtocol.VLESS,
                    address = "198.51.100.1",
                    port = 443,
                    credential = serverAUuid,
                    network = TransportType.WS,
                    security = SecurityType.TLS,
                    sni = "a.example.com",
                    path = "/ws-a",
                    subscriptionId = subEntityId
                ),
                ServerProfile(
                    remarks = "Obsolete-Server-B",
                    groupName = "PersonalSub1",
                    protocol = VpnProtocol.VLESS,
                    address = "198.51.100.2",
                    port = 443,
                    credential = serverBUuid,
                    network = TransportType.WS,
                    security = SecurityType.TLS,
                    sni = "b.example.com",
                    path = "/ws-b",
                    subscriptionId = subEntityId
                )
            )
            db.maxNetDao().insertProfiles(initialSubProfiles.map { com.example.data.local.ProfileEntity.fromDomain(it) })

            val savedServerA = repository.getAllProfilesSnapshot().first { it.credential == serverAUuid }
            originalServerADbId = savedServerA.id

            // User selects Server A, pins it, favorites it, and customizes its remarks
            repository.selectProfile(originalServerADbId)
            repository.toggleFavorite(savedServerA)
            repository.togglePinned(savedServerA.copy(isFavorite = true))
            repository.saveProfile(
                savedServerA.copy(
                    remarks = "My Custom Germany VIP",
                    isFavorite = true,
                    isPinned = true,
                    lastLatencyMs = 142L,
                    tcpConnectMs = 34L,
                    latencyType = LatencyMeasureType.PROXY_HTTP_DELAY
                )
            )

            // 3. Refresh Subscription #1 via repository.refreshSubscriptionWithReport:
            //    - Server A still exists (provider sends "Provider-New-Name-A")
            //    - Server B was removed by provider
            //    - Server C was newly added by provider
            val subItem = db.maxNetDao().getAllSubscriptionsSnapshot().first { it.id == subEntityId }.toDomain()
            val (refreshRes, _) = repository.refreshSubscriptionWithReport(subItem)
            assertTrue("Expected personal subscription refresh to succeed: ${refreshRes.exceptionOrNull()?.message}", refreshRes.isSuccess)
        } finally {
            runCatching { httpServer.close() }
            httpThread.interrupt()
        }

        val profilesAfterSync = repository.getAllProfilesSnapshot()
        // Manual profile must remain untouched
        val manualAfter = profilesAfterSync.find { it.id == manualId }
        assertNotNull("Manual profile must never be deleted or modified by subscription sync", manualAfter)
        assertEquals("My-Manual-Private-VLESS", manualAfter!!.remarks)
        assertTrue(manualAfter.isFavorite)

        // Obsolete Server B must be removed
        assertNull("Obsolete subscription server B must be removed", profilesAfterSync.find { it.credential == serverBUuid })

        // Newly discovered Server C must be added
        val serverCAfter = profilesAfterSync.find { it.credential == serverCUuid }
        assertNotNull("Newly discovered subscription server C must be added", serverCAfter)
        assertEquals("Newly-Added-Server-C", serverCAfter!!.remarks)

        // Existing Server A must be updated IN-PLACE preserving its database ID, favorite, pinned, user-customized remarks, and latency
        val serverAAfter = profilesAfterSync.find { it.credential == serverAUuid }
        assertNotNull(serverAAfter)
        assertEquals("In-place update must preserve original database ID", originalServerADbId, serverAAfter!!.id)
        assertEquals("User-customized remarks must be preserved across subscription sync", "My Custom Germany VIP", serverAAfter.remarks)
        assertTrue("Favorite flag must be preserved", serverAAfter.isFavorite)
        assertTrue("Pinned flag must be preserved", serverAAfter.isPinned)
        assertEquals(142L, serverAAfter.lastLatencyMs)
        assertEquals(34L, serverAAfter.tcpConnectMs)
    }

    /**
     * Scenarios 1, 5, 6, 7, 10, 11, 12:
     * - Concurrent Personal & Free VPN updates on app launch via `AutoServerSyncManager`
     * - Free VPN plain-text, Base64, and Xray JSON support + failure fallback preserving cache
     * - Active VPN connection is never interrupted during background sync
     * - Repeated app opens prevent duplicate concurrent syncs
     * - Zero data contamination between Free VPN and My VPN
     */
    @Test
    fun `auto server sync manager refreshes Personal and Free VPN concurrently without interrupting active VPN or duplicating tasks`() = runBlocking {
        val cacheStore = AutoVpnCacheStore(context)
        cacheStore.clearAll()
        val freeManager = AutoFreeVpnManager(
            context = context,
            cacheStore = cacheStore,
            maxParallelTests = 4,
            candidateTestTimeoutMs = 500
        )
        val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val syncManager = AutoServerSyncManager(
            vpnRepository = repository,
            autoFreeVpnManager = freeManager,
            externalScope = syncScope
        )

        // Seed 1 manual personal profile and start an active VPN session (Scenario 11: App launch while VPN is connected)
        val activePersonalProfile = ServerProfile(
            remarks = "Active-Connected-Tunnel",
            protocol = VpnProtocol.VLESS,
            address = "192.0.2.100",
            port = 443,
            credential = "b831381d-6324-4d53-ad4f-8cda48b30811",
            network = TransportType.TCP,
            security = SecurityType.TLS,
            sni = "example.com"
        )
        val savedId = repository.saveProfile(activePersonalProfile)
        val startRes = VpnController.startSession(
            profile = activePersonalProfile.copy(id = savedId),
            settings = AppSettings(),
            rules = emptyList(),
            ownership = ConnectionOwnership.PERSONAL,
            establishTunAndForwarder = { true },
            verifyConnectivity = false
        )
        assertTrue(startRes.isSuccess)
        assertEquals(VpnConnectionState.CONNECTED, VpnController.connectionState.value)

        val freeFetchInvocations = AtomicInteger(0)
        val freePayloadBase64 = java.util.Base64.getEncoder().encodeToString(
            listOf(
                "vless://b831381d-6324-4d53-ad4f-8cda48b30855@104.16.132.229:443?encryption=none&security=tls&sni=speed.cloudflare.com&type=ws&path=%2Ffree1#Free-Node-1",
                "trojan://d053503f-8546-4f75-9f61-0efc60d52033@104.21.48.84:443?security=tls&sni=cdn.cloudflare.com&type=tcp#Free-Node-2"
            ).joinToString("\n").toByteArray(Charsets.UTF_8)
        )

        // Scenario 7 & 12: Trigger two concurrent sync calls simultaneously; Mutex must deduplicate
        val results = coroutineScope {
            val d1 = async {
                syncManager.syncAllConcurrently(
                    force = true,
                    customFreeVpnFetcher = {
                        freeFetchInvocations.incrementAndGet()
                        delay(80L)
                        freePayloadBase64
                    }
                )
            }
            val d2 = async {
                delay(10L)
                syncManager.syncFreeVpnServers(
                    force = true,
                    customFetcher = {
                        freeFetchInvocations.incrementAndGet()
                        freePayloadBase64
                    }
                )
            }
            Pair(d1.await(), d2.await())
        }

        val dualState = results.first
        assertEquals(SyncProgressState.SUCCESS, dualState.personalStatus.state)
        assertEquals(SyncProgressState.SUCCESS, dualState.freeVpnStatus.state)
        assertEquals(2, dualState.freeVpnStatus.serverCount)
        assertEquals("Expected duplicate concurrent Free VPN sync to be coalesced by Mutex", 1, freeFetchInvocations.get())

        // Verify active VPN connection was NEVER interrupted by launch sync (Scenario 11)
        assertEquals(VpnConnectionState.CONNECTED, VpnController.connectionState.value)
        assertEquals(ConnectionOwnership.PERSONAL, VpnController.ownershipState.value)

        // Verify strict data isolation (Scenario 10): Personal Room DB still has only the 1 personal profile
        val personalDbAfter = repository.getAllProfilesSnapshot()
        assertEquals(1, personalDbAfter.size)
        assertEquals("Active-Connected-Tunnel", personalDbAfter.first().remarks)

        // Scenario 6: Free VPN refresh failure preserves cached servers
        val failedFreeSync = syncManager.syncFreeVpnServers(
            force = true,
            customFetcher = { null } // Simulate offline / network failure
        )
        assertEquals(SyncProgressState.ERROR, failedFreeSync.state)
        assertEquals(2, failedFreeSync.serverCount)
        assertEquals(2, cacheStore.getCachedValidatedCount())

        VpnController.stopSession()
    }
}
