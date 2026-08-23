package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.ui.components.AppMultiSelectDialog
import com.kunk.singbox.ui.components.FloatingPageLayout
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.repository.InstalledAppsRepository
import com.kunk.singbox.viewmodel.SettingsViewModel
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
@Composable
fun TunSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val installedAppsRepo = remember { InstalledAppsRepository.getInstance(context) }
    val installedApps by installedAppsRepo.appItems.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (installedAppsRepo.needsLoading()) {
            installedAppsRepo.loadApps()
        }
    }

    val installedPackageNames = remember(installedApps) {
        installedApps.map { it.packageName }.toSet()
    }

    // Dialog States
    var showStackDialog by remember { mutableStateOf(false) }
    var showIpVersionDialog by remember { mutableStateOf(false) }
    var showMtuDialog by remember { mutableStateOf(false) }
    var showRouteModeDialog by remember { mutableStateOf(false) }
    var showRouteCidrsDialog by remember { mutableStateOf(false) }
    var showAppModeDialog by remember { mutableStateOf(false) }
    var showAllowlistDialog by remember { mutableStateOf(false) }
    var showBlocklistDialog by remember { mutableStateOf(false) }

    if (showStackDialog) {
        val options = TunStack.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.tun_settings_stack),
            options = options,
            selectedIndex = TunStack.entries.indexOf(settings.tunStack).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setTunStack(TunStack.entries[index])
                showStackDialog = false
            },
            onDismiss = { showStackDialog = false }
        )
    }

    if (showIpVersionDialog) {
        val options = IpVersionMode.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.tun_settings_ip_version_mode),
            options = options,
            selectedIndex = IpVersionMode.entries.indexOf(settings.ipVersionMode).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setIpVersionMode(IpVersionMode.entries[index])
                showIpVersionDialog = false
            },
            onDismiss = { showIpVersionDialog = false }
        )
    }

    if (showMtuDialog) {
        InputDialog(
            title = stringResource(R.string.tun_settings_mtu),
            initialValue = settings.tunMtu.toString(),
            onConfirm = {
                it.toIntOrNull()?.let { mtu -> settingsViewModel.setTunMtu(mtu) }
                showMtuDialog = false
            },
            onDismiss = { showMtuDialog = false }
        )
    }

    if (showRouteModeDialog) {
        val options = VpnRouteMode.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.tun_settings_route_mode),
            options = options,
            selectedIndex = VpnRouteMode.entries.indexOf(settings.vpnRouteMode).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setVpnRouteMode(VpnRouteMode.entries[index])
                showRouteModeDialog = false
            },
            onDismiss = { showRouteModeDialog = false }
        )
    }

    if (showRouteCidrsDialog) {
        InputDialog(
            title = stringResource(R.string.tun_settings_route_cidrs),
            initialValue = settings.vpnRouteIncludeCidrs,
            placeholder = "e.g.\n0.0.0.0/0\n10.0.0.0/8",
            singleLine = false,
            minLines = 4,
            maxLines = 8,
            onConfirm = {
                settingsViewModel.setVpnRouteIncludeCidrs(it)
                showRouteCidrsDialog = false
            },
            onDismiss = { showRouteCidrsDialog = false }
        )
    }

    if (showAppModeDialog) {
        val options = VpnAppMode.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.tun_settings_app_mode),
            options = options,
            selectedIndex = VpnAppMode.entries.indexOf(settings.vpnAppMode).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setVpnAppMode(VpnAppMode.entries[index])
                showAppModeDialog = false
            },
            onDismiss = { showAppModeDialog = false }
        )
    }

    if (showAllowlistDialog) {
        val selected = settings.vpnAllowlist
            .split("\n", "\r", ",", ";", " ", "\t")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        AppMultiSelectDialog(
            title = stringResource(R.string.tun_settings_select_allowlist_apps),
            selectedPackages = selected,
            enableQuickSelectCommonApps = true,
            onConfirm = { packages ->
                settingsViewModel.setVpnAllowlist(packages.joinToString("\n"))
                showAllowlistDialog = false
            },
            onDismiss = { showAllowlistDialog = false }
        )
    }

    if (showBlocklistDialog) {
        val selected = settings.vpnBlocklist
            .split("\n", "\r", ",", ";", " ", "\t")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        AppMultiSelectDialog(
            title = stringResource(R.string.tun_settings_select_blocklist_apps),
            selectedPackages = selected,
            enableQuickSelectCommonApps = true,
            quickSelectExcludeCommonApps = true,
            onConfirm = { packages ->
                settingsViewModel.setVpnBlocklist(packages.joinToString("\n"))
                showBlocklistDialog = false
            },
            onDismiss = { showBlocklistDialog = false }
        )
    }

    FloatingPageLayout(
        title = stringResource(R.string.tun_settings_title),
        onBack = { navController.popBackStack() }
    ) { contentTopPadding ->
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background)
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(
                        start = 16.dp,
                        top = contentTopPadding + 16.dp,
                        end = 16.dp,
                        bottom = 16.dp
                    )
                    .navigationBarsPadding()
            ) {
                StandardCard {
                    SettingSwitchItem(
                        title = stringResource(R.string.tun_settings_enable),
                        subtitle = stringResource(R.string.tun_settings_enable_subtitle),
                        checked = settings.tunEnabled,
                        onCheckedChange = { settingsViewModel.setTunEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                StandardCard {
                    SettingItem(
                        title = stringResource(R.string.tun_settings_stack),
                        value = stringResource(settings.tunStack.displayNameRes),
                        onClick = { showStackDialog = true }
                    )
                    SettingItem(
                        title = stringResource(R.string.tun_settings_ip_version_mode),
                        value = stringResource(settings.ipVersionMode.displayNameRes),
                        onClick = { showIpVersionDialog = true }
                    )
                    SettingSwitchItem(
                        title = stringResource(R.string.tun_settings_mtu_auto),
                        subtitle = stringResource(R.string.tun_settings_mtu_auto_subtitle),
                        checked = settings.tunMtuAuto,
                        onCheckedChange = { settingsViewModel.setTunMtuAuto(it) }
                    )
                    SettingItem(
                        title = stringResource(R.string.tun_settings_mtu),
                        value = if (settings.tunMtuAuto) {
                            stringResource(R.string.common_auto)
                        } else {
                            settings.tunMtu.toString()
                        },
                        enabled = !settings.tunMtuAuto,
                        onClick = { showMtuDialog = true }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                StandardCard {
                    SettingSwitchItem(
                        title = stringResource(R.string.tun_settings_auto_route),
                        subtitle = stringResource(R.string.tun_settings_auto_route_subtitle),
                        checked = settings.autoRoute,
                        onCheckedChange = { settingsViewModel.setAutoRoute(it) }
                    )
                    SettingSwitchItem(
                        title = stringResource(R.string.tun_settings_strict_route),
                        subtitle = stringResource(R.string.tun_settings_strict_route_subtitle),
                        checked = settings.strictRoute,
                        onCheckedChange = { settingsViewModel.setStrictRoute(it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                val cidrCount = settings.vpnRouteIncludeCidrs
                    .split("\n", "\r", ",", ";", " ", "\t")
                    .map { it.trim() }
                    .count { it.isNotEmpty() }
                val allowCount = settings.vpnAllowlist
                    .split("\n", "\r", ",", ";", " ", "\t")
                    .map { it.trim() }
                    .count { it.isNotEmpty() && (installedPackageNames.isEmpty() || it in installedPackageNames) }
                val blockCount = settings.vpnBlocklist
                    .split("\n", "\r", ",", ";", " ", "\t")
                    .map { it.trim() }
                    .count { it.isNotEmpty() && (installedPackageNames.isEmpty() || it in installedPackageNames) }

                StandardCard {
                    SettingItem(
                        title = stringResource(R.string.tun_settings_route_mode),
                        value = stringResource(settings.vpnRouteMode.displayNameRes),
                        onClick = { showRouteModeDialog = true }
                    )
                    SettingItem(
                        title = stringResource(R.string.tun_settings_route_cidrs),
                        value = if (settings.vpnRouteMode == VpnRouteMode.CUSTOM) {
                            stringResource(R.string.tun_settings_route_cidrs_configured, cidrCount)
                        } else {
                            "-"
                        },
                        onClick = { if (settings.vpnRouteMode == VpnRouteMode.CUSTOM) showRouteCidrsDialog = true }
                    )
                    SettingItem(
                        title = stringResource(R.string.tun_settings_app_mode),
                        value = stringResource(settings.vpnAppMode.displayNameRes),
                        onClick = { showAppModeDialog = true }
                    )
                    SettingSwitchItem(
                        title = stringResource(R.string.tun_settings_follow_new_apps),
                        subtitle = stringResource(R.string.tun_settings_follow_new_apps_subtitle),
                        checked = settings.autoIncludeNewAppsInPerAppRules,
                        onCheckedChange = settingsViewModel::setAutoIncludeNewAppsInPerAppRules
                    )
                    SettingItem(
                        title = stringResource(R.string.tun_settings_allowlist),
                        value = if (settings.vpnAppMode == VpnAppMode.ALLOWLIST) {
                            stringResource(R.string.tun_settings_allowlist_configured, allowCount)
                        } else {
                            "-"
                        },
                        onClick = { if (settings.vpnAppMode == VpnAppMode.ALLOWLIST) showAllowlistDialog = true }
                    )
                    SettingItem(
                        title = stringResource(R.string.tun_settings_blocklist),
                        value = if (settings.vpnAppMode == VpnAppMode.BLOCKLIST) {
                            stringResource(R.string.tun_settings_blocklist_configured, blockCount)
                        } else {
                            "-"
                        },
                        onClick = { if (settings.vpnAppMode == VpnAppMode.BLOCKLIST) showBlocklistDialog = true }
                    )
                }
            }
        }
    }
}
