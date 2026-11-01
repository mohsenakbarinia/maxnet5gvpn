package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.autovpn.AutoVpnDiscoveryEngine
import com.example.core.security.SecurityCenter
import com.example.core.security.SecurityCheckStatus
import com.example.core.smart.SmartEngine
import com.example.core.xray.RealProxyLatencyMeasurement
import com.example.data.local.ConnectionHistoryEntity
import com.example.data.local.MaxNetDatabase
import com.example.data.local.ProfileEntity
import com.example.domain.model.AppSettings
import com.example.domain.model.ConnectionHistoryItem
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.SmartConnectionMode
import com.example.domain.model.TransportType
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartVpnV3UpgradeTest {

    private lateinit var context: Context
    private lateinit var db: MaxNetDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MaxNetDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SmartEngine.resetCooldownsForTests()
    }

    @After
    fun tearDown() {
        db.close()
        SmartEngine.resetCooldownsForTests()
    }

    @Test
    fun testFreeVpnExclusiveEndpoint() {
        val endpoint = AutoVpnDiscoveryEngine.resolveFreeVpnSubscriptionEndpoint()
        assertEquals("https://io.maxnet6g.ir:2096/sub/acme0ym4y3dswjo5", endpoint)
        assertFalse(endpoint.contains("workers.dev"))
        assertFalse(endpoint.contains("github.com"))
    }

    @Test
    fun testComputeJitterCorrectness() {
        val samples = listOf(100L, 110L, 105L, 120L)
        // Diffs: |110 - 100| = 10, |105 - 110| = 5, |120 - 105| = 15.
        // Mean: (10 + 5 + 15) / 3 = 10 ms.
        val jitter = RealProxyLatencyMeasurement.computeJitter(samples)
        assertEquals(10L, jitter)

        val singleSample = listOf(100L)
        assertEquals(0L, RealProxyLatencyMeasurement.computeJitter(singleSample))

        val emptySamples = emptyList<Long>()
        assertEquals(0L, RealProxyLatencyMeasurement.computeJitter(emptySamples))
    }

    @Test
    fun testSmartEngineGamingModePrefersLowJitterAndLatency() {
        val serverA = createDummyProfile(
            id = 1L,
            remark = "Gaming Server A",
            latencyMs = 120L,
            jitterMs = 4L,
            packetLossRate = 0.0f
        )
        val serverB = createDummyProfile(
            id = 2L,
            remark = "High Jitter Server B",
            latencyMs = 120L,
            jitterMs = 45L,
            packetLossRate = 0.05f
        )

        val result = SmartEngine.selectBestServer(
            candidates = listOf(serverB, serverA),
            mode = SmartConnectionMode.GAMING
        )

        assertNotNull(result)
        assertEquals(1L, result!!.selectedProfile.id)
        assertTrue(result.score > 70)
        assertTrue(result.explanationEn.contains("Gaming"))
    }

    @Test
    fun testSmartEngineStreamingModePenalizesPacketLoss() {
        val serverLossy = createDummyProfile(
            id = 3L,
            remark = "Fast but Lossy",
            latencyMs = 60L,
            jitterMs = 5L,
            packetLossRate = 0.15f // 15% packet loss
        )
        val serverClean = createDummyProfile(
            id = 4L,
            remark = "Clean Streamer",
            latencyMs = 110L,
            jitterMs = 6L,
            packetLossRate = 0.0f // 0% loss
        )

        val result = SmartEngine.selectBestServer(
            candidates = listOf(serverLossy, serverClean),
            mode = SmartConnectionMode.STREAMING
        )

        assertNotNull(result)
        assertEquals(4L, result!!.selectedProfile.id)
        assertTrue(result.explanationEn.contains("streaming", ignoreCase = true))
    }

    @Test
    fun testSmartEngineHysteresisAvoidsPingPongSwitching() {
        val activeServer = createDummyProfile(
            id = 5L,
            remark = "Current Server",
            latencyMs = 140L,
            jitterMs = 8L,
            packetLossRate = 0.0f
        )
        val slightlyBetterServer = createDummyProfile(
            id = 6L,
            remark = "Candidate Slightly Better",
            latencyMs = 135L, // only 5ms faster
            jitterMs = 8L,
            packetLossRate = 0.0f
        )

        val result = SmartEngine.selectBestServer(
            candidates = listOf(slightlyBetterServer, activeServer),
            mode = SmartConnectionMode.BROWSING,
            currentActiveFingerprint = activeServer.uniqueFingerprintKey()
        )

        assertNotNull(result)
        // Hysteresis prevents thrashing
        assertEquals(5L, result!!.selectedProfile.id)
    }

    @Test
    fun testSmartEngineCooldownOnFailures() {
        val unstableServer = createDummyProfile(
            id = 7L,
            remark = "Unstable Server",
            latencyMs = 100L
        )
        val stableServer = createDummyProfile(
            id = 8L,
            remark = "Stable Server",
            latencyMs = 180L
        )

        // Mark 3 failures on unstableServer
        SmartEngine.recordServerFailure(unstableServer)
        SmartEngine.recordServerFailure(unstableServer)
        SmartEngine.recordServerFailure(unstableServer)

        val result = SmartEngine.selectBestServer(
            candidates = listOf(unstableServer, stableServer),
            mode = SmartConnectionMode.STABLE
        )

        assertNotNull(result)
        assertEquals(8L, result!!.selectedProfile.id)
    }

    @Test
    fun testSecurityCenterAuditCalculations() {
        val fullSecuritySettings = AppSettings(
            dnsLeakProtection = true,
            ipv6LeakProtection = true,
            killSwitchEnabled = true
        )
        val secureProfile = createDummyProfile(99L, "Secure TLS Server", 120L)
        val fullAudit = SecurityCenter.auditSecurityPosture(
            profile = secureProfile,
            settings = fullSecuritySettings,
            connectionState = VpnConnectionState.CONNECTED
        )
        assertEquals(100, fullAudit.overallSecurityScore)
        assertEquals(SecurityCheckStatus.SECURE, fullAudit.dnsLeakStatus)
        assertEquals(SecurityCheckStatus.SECURE, fullAudit.ipv6LeakStatus)
        assertEquals(SecurityCheckStatus.SECURE, fullAudit.killSwitchStatus)

        val relaxedSettings = AppSettings(
            dnsLeakProtection = false,
            ipv6LeakProtection = false,
            killSwitchEnabled = false
        )
        val relaxedAudit = SecurityCenter.auditSecurityPosture(
            profile = null,
            settings = relaxedSettings,
            connectionState = VpnConnectionState.DISCONNECTED
        )
        assertTrue(relaxedAudit.overallSecurityScore < 70)
        assertEquals(SecurityCheckStatus.DISABLED, relaxedAudit.dnsLeakStatus)
        assertEquals(SecurityCheckStatus.DISABLED, relaxedAudit.killSwitchStatus)
    }

    @Test
    fun testRoomDatabaseStoreAndRetrieveSmartMetrics() = runBlocking {
        val dao = db.maxNetDao()
        val entity = ProfileEntity(
            id = 101L,
            remarks = "Smart Metrics Test",
            groupName = "Default",
            subscriptionId = null,
            protocol = VpnProtocol.VLESS.name,
            address = "1.1.1.1",
            port = 443,
            encryptedCredential = "credential-123",
            lastLatencyMs = 125L,
            jitterMs = 7L,
            packetLossRate = 0.0f,
            successRate = 0.98f,
            healthScore = 95,
            consecutiveFailures = 0,
            lastFailureTimestampMs = null
        )
        dao.insertProfile(entity)

        val retrieved = dao.getProfileById(101L)
        assertNotNull(retrieved)
        assertEquals(7L, retrieved!!.jitterMs)
        assertEquals(0.0f, retrieved.packetLossRate ?: 1f, 0.001f)
        assertEquals(0.98f, retrieved.successRate ?: 0f, 0.001f)
        assertEquals(95, retrieved.healthScore)

        // Test updateProfileSmartMetrics
        dao.updateProfileSmartMetrics(
            id = 101L,
            latencyMs = 130L,
            tcpConnectMs = 35L,
            jitterMs = 12L,
            packetLossRate = 0.02f,
            successRate = 0.95f,
            healthScore = 88,
            consecutiveFailures = 1,
            lastFailureMs = System.currentTimeMillis(),
            measureType = LatencyMeasureType.PROXY_HTTP_DELAY.name
        )

        val updated = dao.getProfileById(101L)
        assertNotNull(updated)
        assertEquals(12L, updated!!.jitterMs)
        assertEquals(0.02f, updated.packetLossRate ?: 0f, 0.001f)
        assertEquals(1, updated.consecutiveFailures)
        assertEquals(88, updated.healthScore)
    }

    @Test
    fun testConnectionHistoryPersistenceAndClear() = runBlocking {
        val dao = db.maxNetDao()
        val historyItem = ConnectionHistoryEntity(
            id = 0L,
            profileName = "History Test Server",
            protocol = VpnProtocol.VLESS.name,
            engine = "XRAY_CORE",
            connectedAt = System.currentTimeMillis() - 60000L,
            disconnectedAt = System.currentTimeMillis(),
            uploadBytes = 524288L,
            downloadBytes = 1048576L,
            statusNote = "Normal Disconnect",
            failoverReason = "Seamless auto-failover from Server X",
            activeMode = SmartConnectionMode.GAMING.name,
            averageLatencyMs = 115L
        )
        dao.insertConnectionHistory(historyItem)

        val list = dao.getRecentConnectionHistory(10)
        assertEquals(1, list.size)
        assertEquals("History Test Server", list[0].profileName)
        assertEquals("Seamless auto-failover from Server X", list[0].failoverReason)
        assertEquals(SmartConnectionMode.GAMING.name, list[0].activeMode)
        assertEquals(115L, list[0].averageLatencyMs)

        dao.clearConnectionHistory()
        val emptyList = dao.getRecentConnectionHistory(10)
        assertTrue(emptyList.isEmpty())
    }

    private fun createDummyProfile(
        id: Long,
        remark: String,
        latencyMs: Long,
        jitterMs: Long = 5L,
        packetLossRate: Float = 0.0f
    ): ServerProfile {
        return ServerProfile(
            id = id,
            remarks = remark,
            protocol = VpnProtocol.VLESS,
            address = "srv$id.example.com",
            port = (443 + id).toInt(),
            credential = "uuid-$id",
            network = TransportType.TCP,
            security = SecurityType.TLS,
            lastLatencyMs = latencyMs,
            latencyType = LatencyMeasureType.PROXY_HTTP_DELAY,
            jitterMs = jitterMs,
            packetLossRate = packetLossRate,
            successRate = 1.0f,
            healthScore = 90,
            consecutiveFailures = 0,
            lastFailureTimestampMs = null
        )
    }
}
