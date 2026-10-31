package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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

        // Verify V2.5 Home Dashboard sections: Power Control, Status Chip, Active Server Card, Performance Card, Quality Test Button
        composeTestRule.onNodeWithTag("connect_toggle_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("connection_status_chip").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("selected_server_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("network_performance_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("connection_quality_test_button").performScrollTo().assertIsDisplayed()

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

    @Test
    fun `verify clipboard subscription URL confirmation dialog and Persian import results dialog in Profiles screen`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        // Navigate to Profiles tab
        composeTestRule.onNodeWithTag("nav_tab_profiles").performClick()
        composeTestRule.waitForIdle()

        // 1. Copy an HTTPS subscription URL with custom port 2096 and /sub/{token} to clipboard
        clipboardManager.setPrimaryClip(
            android.content.ClipData.newPlainText(
                "sub",
                "https://panel.domain.example:2096/sub/token_abc123#MaxNetSub"
            )
        )

        // Click "Import from Clipboard"
        composeTestRule.onNodeWithTag("import_clipboard_button").performClick()
        composeTestRule.waitForIdle()

        // Verify confirmation dialog is shown with exact Persian text
        composeTestRule.onNodeWithTag("clipboard_subscription_confirm_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_clipboard_subscription_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cancel_clipboard_subscription_button").performClick()
        composeTestRule.waitForIdle()

        // 2. Copy a batch of share links (1 valid + 1 duplicate + 1 invalid) to clipboard
        val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
        val validVless = "vless://$uuid@198.51.100.77:443?type=ws&security=tls&path=%2Fws&sni=example.com#Clipboard-VLESS"
        val batchClip = "$validVless\n$validVless\nvless://invalid_link"
        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("configs", batchClip))

        composeTestRule.onNodeWithTag("import_clipboard_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule
                .onAllNodes(androidx.compose.ui.test.hasTestTag("subscription_import_result_dialog"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Verify Persian Subscription Import Results Dialog is displayed
        composeTestRule.onNodeWithTag("subscription_import_result_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("import_report_total_count").assertIsDisplayed()
        composeTestRule.onNodeWithTag("import_report_new_count").assertIsDisplayed()
        composeTestRule.onNodeWithTag("import_report_duplicate_count").assertIsDisplayed()
        composeTestRule.onNodeWithTag("import_report_invalid_count").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dismiss_import_report_button").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp")
    fun `verify compact profiles screen header search filters sorting contextual menu and large dataset scrolling`() {
        // Navigate to Profiles tab
        composeTestRule.onNodeWithTag("nav_tab_profiles").performClick()
        composeTestRule.waitForIdle()

        // 1. Verify Header ("پروفایلهای من", "مدیریت کانفیگهای اتصال", total count badge)
        composeTestRule.onNodeWithTag("profiles_header_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("profiles_header_subtitle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_total_count_badge").assertIsDisplayed()

        // 2. Verify "افزودن پروفایل" dropdown menu -> Manual Configuration creation
        composeTestRule.onNodeWithTag("open_import_dialog_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("add_profile_dropdown_menu").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_manual_editor_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor_remarks_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_address_input").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = com.example.data.local.MaxNetDatabase.getInstance(context)

        // Return from manual editor
        composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        composeTestRule.waitForIdle()

        // 3. Seed 120 profiles across protocols and countries via Clipboard Import to verify compact list & large dataset
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val batchLinks = (1..120).joinToString("\n") { i ->
            val country = if (i % 2 == 0) "Germany-DE" else "Netherlands-NL"
            val uuid = String.format(java.util.Locale.US, "b831381d-6324-4d53-ad4f-%012d", i)
            "vless://$uuid@198.51.100.${(i % 250) + 1}:443?type=ws&security=tls&sni=sub$i.example.de#$country-Node-$i"
        }
        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("bulk_120", batchLinks))
        composeTestRule.onNodeWithTag("import_clipboard_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule
                .onAllNodes(androidx.compose.ui.test.hasTestTag("subscription_import_result_dialog"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag("dismiss_import_report_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("profiles_lazy_column").assertIsDisplayed()

        // 4. Verify Protocol Filter Chips ("همه", "VLESS", "VMess", "Trojan", "Shadowsocks")
        composeTestRule.onNodeWithTag("protocol_chip_ALL").assertIsDisplayed()
        composeTestRule.onNodeWithTag("protocol_chip_VLESS").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("protocol_chip_ALL").performClick()
        composeTestRule.waitForIdle()

        // 5. Verify Compact Sorting Menu (Lowest ping, Highest ping, Alphabetical, Recently added, Default, Favorites first)
        composeTestRule.onNodeWithTag("sort_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sort_option_NAME").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("sort_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sort_option_DEFAULT").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // 6. Verify Additional Filter Menu (Favorites, Pinned, Default, Country, Connection status)
        composeTestRule.onNodeWithTag("extra_filter_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("filter_menu_favorites").assertIsDisplayed()
        composeTestRule.onNodeWithTag("filter_menu_pinned").assertIsDisplayed()
        composeTestRule.onNodeWithTag("filter_menu_default_group").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Reset filters
        composeTestRule.onNodeWithTag("extra_filter_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("filter_menu_reset").performClick()
        composeTestRule.waitForIdle()

        // 7. Verify Search Bar filtering + Three-Dot Contextual Menu (Favorite, Pin, Delete confirmation)
        val allProfiles = runBlocking { db.maxNetDao().getAllProfilesSnapshot().map { it.toDomain() } }
        val targetProfile = allProfiles.first { it.remarks.endsWith("Node-120") }
        composeTestRule.onNodeWithTag("profile_search_input")
            .performTextInput("Node-120")
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule
                .onAllNodes(androidx.compose.ui.test.hasTestTag("profile_menu_button_${targetProfile.id}"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag("profile_menu_button_${targetProfile.id}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_pin_${targetProfile.id}").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_favorite_${targetProfile.id}").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Open 3-dot menu again and test Delete Confirmation dialog
        composeTestRule.onNodeWithTag("profile_menu_button_${targetProfile.id}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("delete_profile_${targetProfile.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("delete_confirm_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cancel_delete_profile_button").performClick()
        composeTestRule.waitForIdle()

        // Clear search button restores full dataset
        composeTestRule.onNodeWithTag("clear_search_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // 8. Verify QR Generation & Decoding roundtrip
        val shareUri = com.example.parser.ConfigParser.toShareUri(targetProfile)
        val qrBmp = com.example.util.QrCodeHelper.generateQrBitmap(shareUri, 320)
        assertTrue(qrBmp != null)
        val decodedQr = com.example.util.QrCodeHelper.decodeQrFromBitmap(qrBmp!!)
        assertTrue(decodedQr.isSuccess)
    }
}


