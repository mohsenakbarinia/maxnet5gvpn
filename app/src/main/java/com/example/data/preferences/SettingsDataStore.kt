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
        val PROFILE_SORT_MODE = stringPreferencesKey("profile_sort_mode")
        val SMART_CONNECTION_MODE = stringPreferencesKey("smart_connection_mode")
        val SMART_CONNECT_ENABLED = booleanPreferencesKey("smart_connect_enabled")
        val AUTO_FAILOVER_ENABLED = booleanPreferencesKey("auto_failover_enabled")
        val KILL_SWITCH_ENABLED = booleanPreferencesKey("kill_switch_enabled")
        val DNS_LEAK_PROTECTION = booleanPreferencesKey("dns_leak_protection")
        val IPV6_LEAK_PROTECTION = booleanPreferencesKey("ipv6_leak_protection")
        val SECURE_DNS_PROVIDER = stringPreferencesKey("secure_dns_provider")
        val RESILIENCE_MASTER_ENABLED = booleanPreferencesKey("resilience_master_enabled")
        val LOW_DATA_PROBING_MODE = booleanPreferencesKey("low_data_probing_mode")
        val ALLOW_RUNTIME_VARIANTS = booleanPreferencesKey("allow_runtime_transport_variants")
        val MULTI_NETWORK_ASSIST = booleanPreferencesKey("multi_network_assist_enabled")
        val RELAY_FALLBACK_ENABLED = booleanPreferencesKey("relay_fallback_enabled")
        val CUSTOM_DOMESTIC_PROBE_URLS = stringSetPreferencesKey("custom_domestic_probe_urls")
        val AUTHORIZED_RELAYS_JSON = stringPreferencesKey("authorized_relays_json")
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
            languageCode = prefs[Keys.LANGUAGE_CODE] ?: "fa-IR",
            themePreference = runCatching {
                ThemePreference.valueOf(prefs[Keys.THEME_PREF] ?: ThemePreference.DARK.name)
            }.getOrDefault(ThemePreference.DARK),
            usePersianDigits = prefs[Keys.PERSIAN_DIGITS] ?: true,
            fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            connectOnBoot = prefs[Keys.CONNECT_ON_BOOT] ?: false,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: true,
            profileSortMode = prefs[Keys.PROFILE_SORT_MODE] ?: "DEFAULT",
            smartConnectionMode = com.example.domain.model.SmartConnectionMode.fromId(prefs[Keys.SMART_CONNECTION_MODE]),
            smartConnectEnabled = prefs[Keys.SMART_CONNECT_ENABLED] ?: true,
            autoFailoverEnabled = prefs[Keys.AUTO_FAILOVER_ENABLED] ?: true,
            killSwitchEnabled = prefs[Keys.KILL_SWITCH_ENABLED] ?: false,
            dnsLeakProtection = prefs[Keys.DNS_LEAK_PROTECTION] ?: true,
            ipv6LeakProtection = prefs[Keys.IPV6_LEAK_PROTECTION] ?: true,
            secureDnsProvider = prefs[Keys.SECURE_DNS_PROVIDER] ?: "Cloudflare",
            resilienceMasterEnabled = prefs[Keys.RESILIENCE_MASTER_ENABLED] ?: true,
            lowDataProbingMode = prefs[Keys.LOW_DATA_PROBING_MODE] ?: false,
            allowRuntimeTransportVariants = prefs[Keys.ALLOW_RUNTIME_VARIANTS] ?: false,
            multiNetworkAssistEnabled = prefs[Keys.MULTI_NETWORK_ASSIST] ?: false,
            relayFallbackEnabled = prefs[Keys.RELAY_FALLBACK_ENABLED] ?: true,
            customDomesticProbeUrls = prefs[Keys.CUSTOM_DOMESTIC_PROBE_URLS] ?: emptySet(),
            authorizedRelaysJson = prefs[Keys.AUTHORIZED_RELAYS_JSON] ?: "[]"
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
                languageCode = prefs[Keys.LANGUAGE_CODE] ?: "fa-IR",
                themePreference = runCatching { ThemePreference.valueOf(prefs[Keys.THEME_PREF] ?: "DARK") }.getOrDefault(ThemePreference.DARK),
                usePersianDigits = prefs[Keys.PERSIAN_DIGITS] ?: true,
                fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
                autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
                connectOnBoot = prefs[Keys.CONNECT_ON_BOOT] ?: false,
                onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: true,
                profileSortMode = prefs[Keys.PROFILE_SORT_MODE] ?: "DEFAULT",
                smartConnectionMode = com.example.domain.model.SmartConnectionMode.fromId(prefs[Keys.SMART_CONNECTION_MODE]),
                smartConnectEnabled = prefs[Keys.SMART_CONNECT_ENABLED] ?: true,
                autoFailoverEnabled = prefs[Keys.AUTO_FAILOVER_ENABLED] ?: true,
                killSwitchEnabled = prefs[Keys.KILL_SWITCH_ENABLED] ?: false,
                dnsLeakProtection = prefs[Keys.DNS_LEAK_PROTECTION] ?: true,
                ipv6LeakProtection = prefs[Keys.IPV6_LEAK_PROTECTION] ?: true,
                secureDnsProvider = prefs[Keys.SECURE_DNS_PROVIDER] ?: "Cloudflare",
                resilienceMasterEnabled = prefs[Keys.RESILIENCE_MASTER_ENABLED] ?: true,
                lowDataProbingMode = prefs[Keys.LOW_DATA_PROBING_MODE] ?: false,
                allowRuntimeTransportVariants = prefs[Keys.ALLOW_RUNTIME_VARIANTS] ?: false,
                multiNetworkAssistEnabled = prefs[Keys.MULTI_NETWORK_ASSIST] ?: false,
                relayFallbackEnabled = prefs[Keys.RELAY_FALLBACK_ENABLED] ?: true,
                customDomesticProbeUrls = prefs[Keys.CUSTOM_DOMESTIC_PROBE_URLS] ?: emptySet(),
                authorizedRelaysJson = prefs[Keys.AUTHORIZED_RELAYS_JSON] ?: "[]"
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
            prefs[Keys.PROFILE_SORT_MODE] = updated.profileSortMode
            prefs[Keys.SMART_CONNECTION_MODE] = updated.smartConnectionMode.id
            prefs[Keys.SMART_CONNECT_ENABLED] = updated.smartConnectEnabled
            prefs[Keys.AUTO_FAILOVER_ENABLED] = updated.autoFailoverEnabled
            prefs[Keys.KILL_SWITCH_ENABLED] = updated.killSwitchEnabled
            prefs[Keys.DNS_LEAK_PROTECTION] = updated.dnsLeakProtection
            prefs[Keys.IPV6_LEAK_PROTECTION] = updated.ipv6LeakProtection
            prefs[Keys.SECURE_DNS_PROVIDER] = updated.secureDnsProvider
            prefs[Keys.RESILIENCE_MASTER_ENABLED] = updated.resilienceMasterEnabled
            prefs[Keys.LOW_DATA_PROBING_MODE] = updated.lowDataProbingMode
            prefs[Keys.ALLOW_RUNTIME_VARIANTS] = updated.allowRuntimeTransportVariants
            prefs[Keys.MULTI_NETWORK_ASSIST] = updated.multiNetworkAssistEnabled
            prefs[Keys.RELAY_FALLBACK_ENABLED] = updated.relayFallbackEnabled
            prefs[Keys.CUSTOM_DOMESTIC_PROBE_URLS] = updated.customDomesticProbeUrls
            prefs[Keys.AUTHORIZED_RELAYS_JSON] = updated.authorizedRelaysJson
        }
    }
}
