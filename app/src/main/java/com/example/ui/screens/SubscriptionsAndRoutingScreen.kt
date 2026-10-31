package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.AppSettings
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.OutboundTag
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.RoutingRuleType
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.SubscriptionItem
import com.example.ui.theme.CoralError
import com.example.util.LocalizationHelper

@Composable
fun SubscriptionsScreen(
    subscriptions: List<SubscriptionItem>,
    isRefreshing: Boolean,
    usePersianDigits: Boolean,
    onAddSubscription: (String, String, Boolean) -> Unit,
    onRefreshSubscription: (SubscriptionItem) -> Unit,
    onRefreshAll: () -> Unit,
    onDeleteSubscription: (SubscriptionItem) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.subscriptions_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefreshAll,
                    enabled = !isRefreshing && subscriptions.isNotEmpty(),
                    modifier = Modifier.testTag("refresh_all_subscriptions_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.refresh_all_subscriptions))
                }
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.testTag("open_add_subscription_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.add_subscription))
                }
            }
        }

        if (isRefreshing) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (subscriptions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.empty_subscriptions_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(subscriptions, key = { it.id }) { sub ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("subscription_card_${sub.id}"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sub.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = sub.lastStatus,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${stringResource(R.string.sub_last_updated)}: ${
                                            LocalizationHelper.formatTimestamp(sub.lastUpdatedAt, usePersianDigits)
                                        }",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    IconButton(
                                        onClick = { onRefreshSubscription(sub) },
                                        modifier = Modifier.testTag("refresh_sub_${sub.id}")
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                    }
                                    IconButton(
                                        onClick = { onDeleteSubscription(sub) },
                                        modifier = Modifier.testTag("delete_sub_${sub.id}")
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CoralError)
                                    }
                                }
                            }

                            // Subscription-Userinfo Quota Progress if available
                            val used = (sub.uploadBytes ?: 0L) + (sub.downloadBytes ?: 0L)
                            val total = sub.totalBytes
                            if (total != null && total > 0L) {
                                Spacer(modifier = Modifier.height(10.dp))
                                val progress = (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${LocalizationHelper.formatBytes(used, usePersianDigits)} / ${
                                        LocalizationHelper.formatBytes(total, usePersianDigits)
                                    }",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var title by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        var autoUpdate by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.add_subscription)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.sub_title_hint)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sub_title_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.sub_url_hint)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sub_url_input")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.sub_auto_update), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = autoUpdate, onCheckedChange = { autoUpdate = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddSubscription(title, url, autoUpdate)
                        showAddDialog = false
                    },
                    modifier = Modifier.testTag("confirm_add_sub_button")
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutingAndDnsScreen(
    settings: AppSettings,
    customRules: List<RoutingRuleItem>,
    installedApps: List<InstalledAppInfo>,
    onSaveRoutingAndDns: (Boolean, String, Boolean, String, String, Boolean, Boolean) -> Unit,
    onAddRoutingRule: (String, RoutingRuleType, String, OutboundTag) -> Unit,
    onToggleRoutingRule: (RoutingRuleItem) -> Unit,
    onDeleteRoutingRule: (Long) -> Unit,
    onLoadInstalledApps: () -> Unit,
    onSetSplitTunnelMode: (SplitTunnelMode) -> Unit,
    onToggleSplitTunnelApp: (String) -> Unit
) {
    var bypassLan by remember(settings) { mutableStateOf(settings.bypassLan) }
    var bypassCountry by remember(settings) { mutableStateOf(settings.bypassCountryCode) }
    var blockAds by remember(settings) { mutableStateOf(settings.blockAds) }
    var remoteDns by remember(settings) { mutableStateOf(settings.remoteDns) }
    var directDns by remember(settings) { mutableStateOf(settings.directDns) }
    var enableIpv6 by remember(settings) { mutableStateOf(settings.enableIpv6) }
    var enableFakeDns by remember(settings) { mutableStateOf(settings.enableFakeDns) }

    var showAddRuleDialog by remember { mutableStateOf(false) }
    var showSplitTunnelDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.routing_dns_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(14.dp))

        // Routing & DNS Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingToggleRow(
                    label = stringResource(R.string.bypass_lan),
                    checked = bypassLan,
                    onCheckedChange = { bypassLan = it }
                )
                SettingToggleRow(
                    label = stringResource(R.string.block_ads),
                    checked = blockAds,
                    onCheckedChange = { blockAds = it }
                )
                SettingToggleRow(
                    label = stringResource(R.string.enable_ipv6),
                    checked = enableIpv6,
                    onCheckedChange = { enableIpv6 = it }
                )
                SettingToggleRow(
                    label = stringResource(R.string.enable_fakedns),
                    checked = enableFakeDns,
                    onCheckedChange = { enableFakeDns = it }
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = bypassCountry,
                    onValueChange = { bypassCountry = it },
                    label = { Text(stringResource(R.string.bypass_country_code)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bypass_country_input")
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = remoteDns,
                    onValueChange = { remoteDns = it },
                    label = { Text(stringResource(R.string.remote_dns)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = directDns,
                    onValueChange = { directDns = it },
                    label = { Text(stringResource(R.string.direct_dns)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        onSaveRoutingAndDns(
                            bypassLan,
                            bypassCountry,
                            blockAds,
                            remoteDns,
                            directDns,
                            enableIpv6,
                            enableFakeDns
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_routing_dns_button")
                ) {
                    Text(stringResource(R.string.save_profile))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Per-App Split Tunneling Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.split_tunneling_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onLoadInstalledApps()
                            showSplitTunnelDialog = true
                        },
                        modifier = Modifier.testTag("open_split_tunnel_apps_button")
                    ) {
                        Text("Apps (${settings.splitTunnelPackages.size})")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.splitTunnelMode == SplitTunnelMode.ALL_APPS,
                        onClick = { onSetSplitTunnelMode(SplitTunnelMode.ALL_APPS) },
                        label = { Text(stringResource(R.string.split_mode_all)) }
                    )
                    FilterChip(
                        selected = settings.splitTunnelMode == SplitTunnelMode.ALLOW_SELECTED,
                        onClick = { onSetSplitTunnelMode(SplitTunnelMode.ALLOW_SELECTED) },
                        label = { Text(stringResource(R.string.split_mode_allow)) }
                    )
                    FilterChip(
                        selected = settings.splitTunnelMode == SplitTunnelMode.DISALLOW_SELECTED,
                        onClick = { onSetSplitTunnelMode(SplitTunnelMode.DISALLOW_SELECTED) },
                        label = { Text(stringResource(R.string.split_mode_disallow)) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Custom Routing Rules Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.custom_routing_rules),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = { showAddRuleDialog = true },
                        modifier = Modifier.testTag("add_routing_rule_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.add_routing_rule))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                if (customRules.isEmpty()) {
                    Text(
                        text = "No custom rules added yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    customRules.forEach { rule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rule.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "${rule.type.displayName}: ${rule.value} → ${rule.outboundTag.displayName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = rule.isEnabled,
                                onCheckedChange = { onToggleRoutingRule(rule) }
                            )
                            IconButton(onClick = { onDeleteRoutingRule(rule.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CoralError)
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(28.dp))
    }

    if (showAddRuleDialog) {
        var ruleName by remember { mutableStateOf("") }
        var ruleType by remember { mutableStateOf(RoutingRuleType.DOMAIN) }
        var ruleValue by remember { mutableStateOf("") }
        var outbound by remember { mutableStateOf(OutboundTag.DIRECT) }

        AlertDialog(
            onDismissRequest = { showAddRuleDialog = false },
            title = { Text(stringResource(R.string.add_routing_rule)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = ruleName,
                        onValueChange = { ruleName = it },
                        label = { Text("Rule Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RoutingRuleType.entries.forEach { t ->
                            FilterChip(
                                selected = ruleType == t,
                                onClick = { ruleType = t },
                                label = { Text(t.displayName) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ruleValue,
                        onValueChange = { ruleValue = it },
                        label = { Text("Domain / CIDR / Geo code (e.g. example.com or 10.0.0.0/8)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_rule_value_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutboundTag.entries.forEach { tag ->
                            FilterChip(
                                selected = outbound == tag,
                                onClick = { outbound = tag },
                                label = { Text(tag.displayName) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddRoutingRule(ruleName, ruleType, ruleValue, outbound)
                        showAddRuleDialog = false
                    },
                    modifier = Modifier.testTag("confirm_add_rule_button")
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRuleDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showSplitTunnelDialog) {
        var appQuery by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { onLoadInstalledApps() }

        val filteredApps = remember(installedApps, appQuery) {
            val q = appQuery.trim().lowercase()
            if (q.isEmpty()) installedApps
            else installedApps.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }

        AlertDialog(
            onDismissRequest = { showSplitTunnelDialog = false },
            title = { Text(stringResource(R.string.split_tunneling_title)) },
            text = {
                Column(modifier = Modifier.height(360.dp)) {
                    OutlinedTextField(
                        value = appQuery,
                        onValueChange = { appQuery = it },
                        placeholder = { Text("Search installed apps…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val checked = settings.splitTunnelPackages.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onToggleSplitTunnelApp(app.packageName) }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.appName, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSplitTunnelDialog = false }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
