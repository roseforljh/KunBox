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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.DnsStrategy
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val settings by settingsViewModel.settings.collectAsState()

    // State for Dialogs
    var showLocalDnsDialog by remember { mutableStateOf(false) }
    var showRemoteDnsDialog by remember { mutableStateOf(false) }
    var showFakeIpDialog by remember { mutableStateOf(false) }
    var showStrategyDialog by remember { mutableStateOf(false) }
    var showRemoteStrategyDialog by remember { mutableStateOf(false) }
    var showDirectStrategyDialog by remember { mutableStateOf(false) }
    var showServerStrategyDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }

    if (showLocalDnsDialog) {
        InputDialog(
            title = stringResource(R.string.settings_local_dns),
            initialValue = settings.localDns,
            onConfirm = {
                settingsViewModel.setLocalDns(it)
                showLocalDnsDialog = false
            },
            onDismiss = { showLocalDnsDialog = false }
        )
    }

    if (showRemoteDnsDialog) {
        InputDialog(
            title = stringResource(R.string.settings_remote_dns),
            initialValue = settings.remoteDns,
            onConfirm = {
                settingsViewModel.setRemoteDns(it)
                showRemoteDnsDialog = false
            },
            onDismiss = { showRemoteDnsDialog = false }
        )
    }

    if (showFakeIpDialog) {
        InputDialog(
            title = stringResource(R.string.dns_settings_fake_ip_range),
            initialValue = settings.fakeIpRange,
            onConfirm = {
                settingsViewModel.setFakeIpRange(it)
                showFakeIpDialog = false
            },
            onDismiss = { showFakeIpDialog = false }
        )
    }

    if (showStrategyDialog) {
        val options = DnsStrategy.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.dns_settings_strategy),
            options = options,
            selectedIndex = DnsStrategy.entries.indexOf(settings.dnsStrategy).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setDnsStrategy(DnsStrategy.entries[index])
                showStrategyDialog = false
            },
            onDismiss = { showStrategyDialog = false }
        )
    }

    if (showRemoteStrategyDialog) {
        val options = DnsStrategy.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.dns_settings_remote_strategy),
            options = options,
            selectedIndex = DnsStrategy.entries.indexOf(settings.remoteDnsStrategy).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setRemoteDnsStrategy(DnsStrategy.entries[index])
                showRemoteStrategyDialog = false
            },
            onDismiss = { showRemoteStrategyDialog = false }
        )
    }

    if (showDirectStrategyDialog) {
        val options = DnsStrategy.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.dns_settings_direct_strategy),
            options = options,
            selectedIndex = DnsStrategy.entries.indexOf(settings.directDnsStrategy).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setDirectDnsStrategy(DnsStrategy.entries[index])
                showDirectStrategyDialog = false
            },
            onDismiss = { showDirectStrategyDialog = false }
        )
    }

    if (showServerStrategyDialog) {
        val options = DnsStrategy.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.dns_settings_server_strategy),
            options = options,
            selectedIndex = DnsStrategy.entries.indexOf(settings.serverAddressStrategy).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setServerAddressStrategy(DnsStrategy.entries[index])
                showServerStrategyDialog = false
            },
            onDismiss = { showServerStrategyDialog = false }
        )
    }

    if (showCacheDialog) {
        val options = listOf(stringResource(R.string.common_enabled), stringResource(R.string.common_disabled))
        val currentIndex = if (settings.dnsCacheEnabled) 0 else 1
        SingleSelectDialog(
            title = stringResource(R.string.dns_settings_cache),
            options = options,
            selectedIndex = currentIndex,
            onSelect = { index ->
                settingsViewModel.setDnsCacheEnabled(index == 0)
                showCacheDialog = false
            },
            onDismiss = { showCacheDialog = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_settings_title), color = MaterialTheme.colorScheme.onBackground) },
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
                SettingItem(title = stringResource(R.string.settings_local_dns), value = settings.localDns, onClick = { showLocalDnsDialog = true })
                SettingItem(title = stringResource(R.string.settings_remote_dns), value = settings.remoteDns, onClick = { showRemoteDnsDialog = true })
            }

            Spacer(modifier = Modifier.height(16.dp))

            StandardCard {
                SettingSwitchItem(
                    title = "Fake DNS",
                    subtitle = stringResource(R.string.dns_settings_fake_dns_subtitle),
                    checked = settings.fakeDnsEnabled,
                    onCheckedChange = { settingsViewModel.setFakeDnsEnabled(it) }
                )
                SettingItem(title = stringResource(R.string.dns_settings_fake_ip_range), value = settings.fakeIpRange, onClick = { showFakeIpDialog = true })
            }

            Spacer(modifier = Modifier.height(16.dp))

            StandardCard {
                SettingItem(title = stringResource(R.string.dns_settings_strategy), value = stringResource(settings.dnsStrategy.displayNameRes), onClick = { showStrategyDialog = true })
                SettingItem(title = stringResource(R.string.dns_settings_remote_strategy), value = stringResource(settings.remoteDnsStrategy.displayNameRes), onClick = { showRemoteStrategyDialog = true })
                SettingItem(title = stringResource(R.string.dns_settings_direct_strategy), value = stringResource(settings.directDnsStrategy.displayNameRes), onClick = { showDirectStrategyDialog = true })
                SettingItem(title = stringResource(R.string.dns_settings_server_strategy), value = stringResource(settings.serverAddressStrategy.displayNameRes), onClick = { showServerStrategyDialog = true })
                SettingItem(title = stringResource(R.string.dns_settings_cache), value = if (settings.dnsCacheEnabled) stringResource(R.string.common_enabled) else stringResource(R.string.common_disabled), onClick = { showCacheDialog = true })
            }
        }
    }
}
