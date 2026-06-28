package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.service.manager.NetworkAutoSwitchManager
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.EditableMultilineTextItem
import com.kunk.singbox.ui.components.EditableTextItem
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.utils.LocalNetworkPermission
import com.kunk.singbox.viewmodel.SettingsViewModel
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarColors

@Suppress("CognitiveComplexMethod", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    var showPowerSavingDelayDialog by remember { mutableStateOf(false) }
    val permissionDeniedMessage = stringResource(R.string.connection_settings_network_auto_switch_permission_denied)
    val localNetworkPermissionDeniedMessage =
        stringResource(R.string.connection_settings_local_network_permission_denied)
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (NetworkAutoSwitchManager.hasWifiSsidPermission(context)) {
            settingsViewModel.setNetworkAutoSwitchEnabled(true)
        } else {
            AppNotificationManager.showMessage(context, permissionDeniedMessage)
        }
    }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (LocalNetworkPermission.hasPermission(context)) {
            settingsViewModel.updateAllowLan(true)
        } else {
            settingsViewModel.updateAllowLan(false)
            AppNotificationManager.showMessage(context, localNetworkPermissionDeniedMessage)
        }
    }

    if (showPowerSavingDelayDialog) {
        SingleSelectDialog(
            title = stringResource(R.string.connection_settings_power_saving),
            options = BackgroundPowerSavingDelay.entries.map { stringResource(it.displayNameRes) },
            selectedIndex = BackgroundPowerSavingDelay.entries.indexOf(settings.backgroundPowerSavingDelay),
            onSelect = { index ->
                settingsViewModel.setBackgroundPowerSavingDelay(BackgroundPowerSavingDelay.entries[index])
                showPowerSavingDelayDialog = false
            },
            onDismiss = { showPowerSavingDelayDialog = false }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connection_settings_title), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(
                        modifier = Modifier.liquidGlassIconButtonPanel(),
                        onClick = { navController.popBackStack() }
                    ) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = liquidGlassTopAppBarColors(defaultContainerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            StandardCard {
                SettingSwitchItem(
                    title = stringResource(R.string.connection_settings_auto_connect),
                    subtitle = stringResource(R.string.connection_settings_auto_connect_subtitle),
                    checked = settings.autoConnect,
                    onCheckedChange = { settingsViewModel.setAutoConnect(it) }
                )
                SettingSwitchItem(
                    title = stringResource(R.string.connection_settings_network_auto_switch),
                    subtitle = stringResource(R.string.connection_settings_network_auto_switch_subtitle),
                    checked = settings.networkAutoSwitchEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            settingsViewModel.setNetworkAutoSwitchEnabled(false)
                        } else if (NetworkAutoSwitchManager.hasWifiSsidPermission(context)) {
                            settingsViewModel.setNetworkAutoSwitchEnabled(true)
                        } else {
                            wifiPermissionLauncher.launch(NetworkAutoSwitchManager.requiredWifiSsidPermissions())
                        }
                    }
                )
                if (settings.networkAutoSwitchEnabled) {
                    EditableMultilineTextItem(
                        title = stringResource(R.string.connection_settings_trusted_wifi_ssids),
                        subtitle = stringResource(R.string.connection_settings_trusted_wifi_ssids_subtitle),
                        value = settings.trustedWifiSsids,
                        placeholder = stringResource(R.string.connection_settings_trusted_wifi_ssids_placeholder),
                        onValueChange = { settingsViewModel.setTrustedWifiSsids(it) }
                    )
                }
                SettingSwitchItem(
                    title = stringResource(R.string.connection_settings_hide_recent),
                    subtitle = stringResource(R.string.connection_settings_hide_recent_subtitle),
                    checked = settings.excludeFromRecent,
                    onCheckedChange = { settingsViewModel.setExcludeFromRecent(it) }
                )
                SettingSwitchItem(
                    title = stringResource(R.string.connection_settings_show_notification_speed),
                    subtitle = stringResource(R.string.connection_settings_show_notification_speed_subtitle),
                    checked = settings.showNotificationSpeed,
                    onCheckedChange = { settingsViewModel.setShowNotificationSpeed(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StandardCard {
                SettingSwitchItem(
                    title = stringResource(R.string.connection_settings_wake_reset),
                    subtitle = stringResource(R.string.connection_settings_wake_reset_subtitle),
                    checked = settings.wakeResetConnections,
                    onCheckedChange = { settingsViewModel.setWakeResetConnections(it) }
                )
                SettingItem(
                    title = stringResource(R.string.connection_settings_power_saving),
                    subtitle = stringResource(R.string.connection_settings_power_saving_subtitle),
                    value = stringResource(settings.backgroundPowerSavingDelay.displayNameRes),
                    onClick = { showPowerSavingDelayDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StandardCard {
                EditableTextItem(
                    title = stringResource(R.string.connection_settings_proxy_port),
                    subtitle = stringResource(R.string.connection_settings_proxy_port_subtitle),
                    value = settings.proxyPort.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { port -> settingsViewModel.updateProxyPort(port) }
                    }
                )
                SettingSwitchItem(
                    title = stringResource(R.string.connection_settings_allow_lan),
                    subtitle = stringResource(R.string.connection_settings_allow_lan_subtitle),
                    checked = settings.allowLan,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            settingsViewModel.updateAllowLan(false)
                        } else if (LocalNetworkPermission.canExposeLan(context, allowLan = true)) {
                            settingsViewModel.updateAllowLan(true)
                        } else {
                            localNetworkPermissionLauncher.launch(LocalNetworkPermission.requiredPermissions())
                        }
                    }
                )
                SettingSwitchItem(
                    title = stringResource(R.string.connection_settings_append_http_proxy),
                    subtitle = stringResource(R.string.connection_settings_append_http_proxy_subtitle),
                    checked = settings.appendHttpProxy,
                    onCheckedChange = { settingsViewModel.updateAppendHttpProxy(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StandardCard {
                EditableTextItem(
                    title = stringResource(R.string.connection_settings_latency_concurrency),
                    subtitle = stringResource(R.string.connection_settings_latency_concurrency_subtitle),
                    value = settings.latencyTestConcurrency.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { count -> settingsViewModel.updateLatencyTestConcurrency(count) }
                    }
                )
                EditableTextItem(
                    title = stringResource(R.string.connection_settings_latency_timeout),
                    subtitle = stringResource(R.string.connection_settings_latency_timeout_subtitle),
                    value = settings.latencyTestTimeout.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { ms -> settingsViewModel.updateLatencyTestTimeout(ms) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
