package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.EngineType
import com.example.domain.model.RoutingMode
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "maxnet5g_settings")

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val SELECTED_PROFILE_ID = longPreferencesKey("selected_profile_id")
        val ACTIVE_ENGINE = stringPreferencesKey("active_engine")
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val BYPASS_COUNTRY = stringPreferencesKey("bypass_country")
        val BLOCK_ADS = booleanPreferencesKey("block_ads")
        val REMOTE_DNS = stringPreferencesKey("remote_dns")
        val DIRECT_DNS = stringPreferencesKey("direct_dns")
        val DNS_STRATEGY = stringPreferencesKey("dns_strategy")
        val ENABLE_IPV6 = booleanPreferencesKey("enable_ipv6")
        val ENABLE_FAKEDNS = booleanPreferencesKey("enable_fakedns")
        val MTU = intPreferencesKey("mtu")
        val SPLIT_MODE = stringPreferencesKey("split_tunnel_mode")
        val SPLIT_PACKAGES = stringSetPreferencesKey("split_tunnel_packages")
        val LANGUAGE_CODE = stringPreferencesKey("language_code")
        val THEME_PREF = stringPreferencesKey("theme_pref")
        val PERSIAN_DIGITS = booleanPreferencesKey("use_persian_digits")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val CONNECT_ON_BOOT = booleanPreferencesKey("connect_on_boot")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            selectedProfileId = prefs[Keys.SELECTED_PROFILE_ID],
            activeEngine = runCatching {
                EngineType.valueOf(prefs[Keys.ACTIVE_ENGINE] ?: EngineType.XRAY_CORE.name)
            }.getOrDefault(EngineType.XRAY_CORE),
            routingMode = runCatching {
                RoutingMode.valueOf(prefs[Keys.ROUTING_MODE] ?: RoutingMode.RULE_BASED.name)
            }.getOrDefault(RoutingMode.RULE_BASED),
            bypassLan = prefs[Keys.BYPASS_LAN] ?: true,
            bypassCountryCode = prefs[Keys.BYPASS_COUNTRY] ?: "IR",
            blockAds = prefs[Keys.BLOCK_ADS] ?: false,
            remoteDns = prefs[Keys.REMOTE_DNS] ?: "https://1.1.1.1/dns-query",
            directDns = prefs[Keys.DIRECT_DNS] ?: "1.1.1.1",
            dnsQueryStrategy = prefs[Keys.DNS_STRATEGY] ?: "UseIPv4",
            enableIpv6 = prefs[Keys.ENABLE_IPV6] ?: false,
            enableFakeDns = prefs[Keys.ENABLE_FAKEDNS] ?: false,
            mtu = prefs[Keys.MTU] ?: 1500,
            splitTunnelMode = runCatching {
                SplitTunnelMode.valueOf(prefs[Keys.SPLIT_MODE] ?: SplitTunnelMode.ALL_APPS.name)
            }.getOrDefault(SplitTunnelMode.ALL_APPS),
            splitTunnelPackages = prefs[Keys.SPLIT_PACKAGES] ?: emptySet(),
            languageCode = prefs[Keys.LANGUAGE_CODE] ?: "en-US",
            themePreference = runCatching {
                ThemePreference.valueOf(prefs[Keys.THEME_PREF] ?: ThemePreference.DARK.name)
            }.getOrDefault(ThemePreference.DARK),
            usePersianDigits = prefs[Keys.PERSIAN_DIGITS] ?: false,
            fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            connectOnBoot = prefs[Keys.CONNECT_ON_BOOT] ?: false,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false
        )
    }

    suspend fun updateSelectedProfile(profileId: Long?) {
        context.dataStore.edit { prefs ->
            if (profileId == null) prefs.remove(Keys.SELECTED_PROFILE_ID)
            else prefs[Keys.SELECTED_PROFILE_ID] = profileId
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = AppSettings(
                selectedProfileId = prefs[Keys.SELECTED_PROFILE_ID],
                activeEngine = runCatching { EngineType.valueOf(prefs[Keys.ACTIVE_ENGINE] ?: "XRAY_CORE") }.getOrDefault(EngineType.XRAY_CORE),
                routingMode = runCatching { RoutingMode.valueOf(prefs[Keys.ROUTING_MODE] ?: "RULE_BASED") }.getOrDefault(RoutingMode.RULE_BASED),
                bypassLan = prefs[Keys.BYPASS_LAN] ?: true,
                bypassCountryCode = prefs[Keys.BYPASS_COUNTRY] ?: "IR",
                blockAds = prefs[Keys.BLOCK_ADS] ?: false,
                remoteDns = prefs[Keys.REMOTE_DNS] ?: "https://1.1.1.1/dns-query",
                directDns = prefs[Keys.DIRECT_DNS] ?: "1.1.1.1",
                dnsQueryStrategy = prefs[Keys.DNS_STRATEGY] ?: "UseIPv4",
                enableIpv6 = prefs[Keys.ENABLE_IPV6] ?: false,
                enableFakeDns = prefs[Keys.ENABLE_FAKEDNS] ?: false,
                mtu = prefs[Keys.MTU] ?: 1500,
                splitTunnelMode = runCatching { SplitTunnelMode.valueOf(prefs[Keys.SPLIT_MODE] ?: "ALL_APPS") }.getOrDefault(SplitTunnelMode.ALL_APPS),
                splitTunnelPackages = prefs[Keys.SPLIT_PACKAGES] ?: emptySet(),
                languageCode = prefs[Keys.LANGUAGE_CODE] ?: "en-US",
                themePreference = runCatching { ThemePreference.valueOf(prefs[Keys.THEME_PREF] ?: "DARK") }.getOrDefault(ThemePreference.DARK),
                usePersianDigits = prefs[Keys.PERSIAN_DIGITS] ?: false,
                fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
                autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
                connectOnBoot = prefs[Keys.CONNECT_ON_BOOT] ?: false,
                onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false
            )
            val updated = transform(current)
            if (updated.selectedProfileId != null) prefs[Keys.SELECTED_PROFILE_ID] = updated.selectedProfileId
            else prefs.remove(Keys.SELECTED_PROFILE_ID)
            prefs[Keys.ACTIVE_ENGINE] = updated.activeEngine.name
            prefs[Keys.ROUTING_MODE] = updated.routingMode.name
            prefs[Keys.BYPASS_LAN] = updated.bypassLan
            prefs[Keys.BYPASS_COUNTRY] = updated.bypassCountryCode
            prefs[Keys.BLOCK_ADS] = updated.blockAds
            prefs[Keys.REMOTE_DNS] = updated.remoteDns
            prefs[Keys.DIRECT_DNS] = updated.directDns
            prefs[Keys.DNS_STRATEGY] = updated.dnsQueryStrategy
            prefs[Keys.ENABLE_IPV6] = updated.enableIpv6
            prefs[Keys.ENABLE_FAKEDNS] = updated.enableFakeDns
            prefs[Keys.MTU] = updated.mtu
            prefs[Keys.SPLIT_MODE] = updated.splitTunnelMode.name
            prefs[Keys.SPLIT_PACKAGES] = updated.splitTunnelPackages
            prefs[Keys.LANGUAGE_CODE] = updated.languageCode
            prefs[Keys.THEME_PREF] = updated.themePreference.name
            prefs[Keys.PERSIAN_DIGITS] = updated.usePersianDigits
            prefs[Keys.FONT_SCALE] = updated.fontScale
            prefs[Keys.AUTO_RECONNECT] = updated.autoReconnect
            prefs[Keys.CONNECT_ON_BOOT] = updated.connectOnBoot
            prefs[Keys.ONBOARDING_COMPLETED] = updated.onboardingCompleted
        }
    }
}
