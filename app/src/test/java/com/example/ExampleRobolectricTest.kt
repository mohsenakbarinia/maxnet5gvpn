package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.AppSettings
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnProtocol
import com.example.vpn.VpnController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `verify app name and home screen navigation and routing switch`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("MAXNET5G VPN", context.getString(R.string.app_name))

        composeTestRule.waitForIdle()
        // Dismiss initial onboarding card
        composeTestRule.onNodeWithTag("onboarding_get_started_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("connect_toggle_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("connection_status_chip").performScrollTo().assertIsDisplayed()

        // Switch routing mode on Home Screen
        composeTestRule.onNodeWithTag("routing_mode_global").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Navigate across tabs (Profiles, Subscriptions, Routing, Diagnostics, Settings)
        composeTestRule.onNodeWithTag("nav_tab_profiles").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("open_import_dialog_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_subscriptions").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("open_add_subscription_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_diagnostics").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("export_diagnostics_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("lang_fa_button").assertIsDisplayed()
    }

    @Test
    fun `verify VpnController real loopback SOCKS5 start and stop lifecycle`() = runBlocking {
        val testProfile = ServerProfile(
            id = 1L,
            remarks = "Test-Loopback-Node",
            protocol = VpnProtocol.VLESS,
            address = "192.0.2.100",
            port = 443,
            credential = "b831381d-6324-4d53-ad4f-8cda48b30811",
            network = TransportType.TCP,
            security = SecurityType.TLS,
            sni = "example.com"
        )
        val settings = AppSettings()

        val startRes = VpnController.startSession(
            profile = testProfile,
            settings = settings,
            rules = emptyList(),
            establishTunAndForwarder = { true },
            verifyConnectivity = false
        )
        assertTrue("Expected VpnController.startSession to succeed: ${startRes.exceptionOrNull()?.message}", startRes.isSuccess)
        val port = startRes.getOrThrow()
        assertTrue(port in 1024..65535)
        assertEquals(VpnConnectionState.CONNECTED, VpnController.connectionState.value)

        VpnController.stopSession()
        assertEquals(VpnConnectionState.DISCONNECTED, VpnController.connectionState.value)
    }
}
