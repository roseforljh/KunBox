package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.GhProxyMirror
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val settings by settingsViewModel.settings.collectAsState()

    // Dialog States
    var showModeDialog by remember { mutableStateOf(false) }
    var showDefaultRuleDialog by remember { mutableStateOf(false) }
    var showMirrorDialog by remember { mutableStateOf(false) }
    var showLatencyMethodDialog by remember { mutableStateOf(false) }
    var showLatencyUrlDialog by remember { mutableStateOf(false) }
    var showSubscriptionTimeoutDialog by remember { mutableStateOf(false) }

    if (showLatencyMethodDialog) {
        val options = LatencyTestMethod.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.routing_settings_latency_test_method),
            options = options,
            selectedIndex = LatencyTestMethod.entries.indexOf(settings.latencyTestMethod).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setLatencyTestMethod(LatencyTestMethod.entries[index])
                showLatencyMethodDialog = false
            },
            onDismiss = { showLatencyMethodDialog = false }
        )
    }

    if (showLatencyUrlDialog) {
        InputDialog(
            title = stringResource(R.string.routing_settings_latency_test_url),
            initialValue = settings.latencyTestUrl,
            placeholder = "e.g. https://www.gstatic.com/generate_204",
            onConfirm = { url ->
                settingsViewModel.setLatencyTestUrl(url.trim())
                showLatencyUrlDialog = false
            },
            onDismiss = { showLatencyUrlDialog = false }
        )
    }

    if (showSubscriptionTimeoutDialog) {
        InputDialog(
            title = stringResource(R.string.routing_settings_subscription_timeout),
            initialValue = settings.subscriptionUpdateTimeout.toString(),
            placeholder = "e.g. 30",
            onConfirm = { input ->
                val seconds = input.trim().toIntOrNull()
                if (seconds != null && seconds >= 10) {
                    settingsViewModel.setSubscriptionUpdateTimeout(seconds)
                }
                showSubscriptionTimeoutDialog = false
            },
            onDismiss = { showSubscriptionTimeoutDialog = false }
        )
    }

    if (showModeDialog) {
        val options = RoutingMode.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.routing_settings_mode),
            options = options,
            selectedIndex = RoutingMode.entries.indexOf(settings.routingMode).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setRoutingMode(RoutingMode.entries[index])
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false }
        )
    }

    if (showDefaultRuleDialog) {
        val options = DefaultRule.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.routing_settings_default_rule),
            options = options,
            selectedIndex = DefaultRule.entries.indexOf(settings.defaultRule).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setDefaultRule(DefaultRule.entries[index])
                showDefaultRuleDialog = false
            },
            onDismiss = { showDefaultRuleDialog = false }
        )
    }

    if (showMirrorDialog) {
        val options = GhProxyMirror.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.routing_settings_github_mirror),
            options = options,
            selectedIndex = GhProxyMirror.entries.indexOf(settings.ghProxyMirror).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setGhProxyMirror(GhProxyMirror.entries[index])
                showMirrorDialog = false
            },
            onDismiss = { showMirrorDialog = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routing_settings_title), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            StandardCard {
                SettingItem(title = stringResource(R.string.routing_settings_mode), value = stringResource(settings.routingMode.displayNameRes), onClick = { showModeDialog = true })
                SettingItem(title = stringResource(R.string.routing_settings_default_rule), value = stringResource(settings.defaultRule.displayNameRes), onClick = { showDefaultRuleDialog = true })
                SettingItem(title = stringResource(R.string.routing_settings_latency_test_method), value = stringResource(settings.latencyTestMethod.displayNameRes), onClick = { showLatencyMethodDialog = true })
                SettingItem(
                    title = stringResource(R.string.routing_settings_latency_test_url),
                    onClick = { showLatencyUrlDialog = true },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = settings.latencyTestUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = Neutral500
                            )
                        }
                    }
                )
                SettingItem(title = stringResource(R.string.routing_settings_github_mirror), value = stringResource(settings.ghProxyMirror.displayNameRes), onClick = { showMirrorDialog = true })
                SettingItem(
                    title = stringResource(R.string.routing_settings_subscription_timeout),
                    value = stringResource(R.string.routing_settings_latency_test_timeout_s, settings.subscriptionUpdateTimeout),
                    onClick = { showSubscriptionTimeoutDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StandardCard {
                SettingSwitchItem(
                    title = stringResource(R.string.routing_settings_block_ads),
                    subtitle = stringResource(R.string.routing_settings_block_ads_subtitle),
                    checked = settings.blockAds,
                    onCheckedChange = { settingsViewModel.setBlockAds(it) }
                )
                SettingSwitchItem(
                    title = stringResource(R.string.routing_settings_block_quic),
                    subtitle = stringResource(R.string.routing_settings_block_quic_subtitle),
                    checked = settings.blockQuic,
                    onCheckedChange = { settingsViewModel.setBlockQuic(it) }
                )
                SettingSwitchItem(
                    title = stringResource(R.string.routing_settings_bypass_lan),
                    subtitle = stringResource(R.string.routing_settings_bypass_lan_subtitle),
                    checked = settings.bypassLan,
                    onCheckedChange = { settingsViewModel.setBypassLan(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StandardCard {
                SettingItem(
                    title = stringResource(R.string.routing_settings_app_routing),
                    value = stringResource(R.string.routing_settings_app_routing_rules, settings.appRules.size + settings.appGroups.size),
                    onClick = { navController.navigate(Screen.AppRules.route) }
                )
                val domainRuleCount = settings.customRules.count {
                    it.enabled && it.type in listOf(
                        com.kunk.singbox.model.RuleType.DOMAIN,
                        com.kunk.singbox.model.RuleType.DOMAIN_SUFFIX,
                        com.kunk.singbox.model.RuleType.DOMAIN_KEYWORD
                    )
                }
                SettingItem(
                    title = stringResource(R.string.routing_settings_domain_routing),
                    value = stringResource(R.string.routing_settings_app_routing_rules, domainRuleCount),
                    onClick = { navController.navigate(Screen.DomainRules.route) }
                )
                SettingItem(title = stringResource(R.string.routing_settings_manage_rulesets), onClick = { navController.navigate(Screen.RuleSets.route) })
            }
        }
    }
}
