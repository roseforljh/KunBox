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
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.viewmodel.DiagnosticsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    navController: NavController,
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val showResultDialog by viewModel.showResultDialog.collectAsState()
    val resultTitle by viewModel.resultTitle.collectAsState()
    val resultMessage by viewModel.resultMessage.collectAsState()

    val isConnectivityLoading by viewModel.isConnectivityLoading.collectAsState()
    val isPingLoading by viewModel.isPingLoading.collectAsState()
    val isDnsLoading by viewModel.isDnsLoading.collectAsState()
    val isRoutingLoading by viewModel.isRoutingLoading.collectAsState()
    val isRunConfigLoading by viewModel.isRunConfigLoading.collectAsState()
    val isAppRoutingDiagLoading by viewModel.isAppRoutingDiagLoading.collectAsState()
    val isConnOwnerStatsLoading by viewModel.isConnOwnerStatsLoading.collectAsState()

    if (showResultDialog) {
        ConfirmDialog(
            title = resultTitle,
            message = resultMessage,
            confirmText = stringResource(R.string.common_ok),
            onConfirm = { viewModel.dismissDialog() },
            onDismiss = { viewModel.dismissDialog() }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title), color = MaterialTheme.colorScheme.onBackground) },
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
                SettingItem(
                    title = stringResource(R.string.diagnostics_view_config),
                    subtitle = if (isRunConfigLoading) stringResource(R.string.diagnostics_view_config_loading) else stringResource(R.string.diagnostics_view_config_subtitle),
                    icon = Icons.Rounded.InsertDriveFile,
                    onClick = { viewModel.showRunningConfigSummary() },
                    enabled = !isRunConfigLoading
                )
                SettingItem(
                    title = stringResource(R.string.diagnostics_export_config),
                    subtitle = if (isRunConfigLoading) stringResource(R.string.diagnostics_export_config_loading) else stringResource(R.string.diagnostics_export_config_subtitle),
                    icon = Icons.Rounded.FileDownload,
                    onClick = { viewModel.exportRunningConfigToExternalFiles() },
                    enabled = !isRunConfigLoading
                )
                SettingItem(
                    title = stringResource(R.string.diagnostics_app_routing),
                    subtitle = if (isAppRoutingDiagLoading) stringResource(R.string.diagnostics_app_routing_loading) else stringResource(R.string.diagnostics_app_routing_subtitle),
                    icon = Icons.Rounded.Storage,
                    onClick = { viewModel.runAppRoutingDiagnostics() },
                    enabled = !isAppRoutingDiagLoading
                )
                SettingItem(
                    title = stringResource(R.string.diagnostics_conn_owner),
                    subtitle = if (isConnOwnerStatsLoading) stringResource(R.string.diagnostics_conn_owner_loading) else stringResource(R.string.diagnostics_conn_owner_subtitle),
                    icon = Icons.Rounded.Analytics,
                    onClick = { viewModel.showConnectionOwnerStats() },
                    enabled = !isConnOwnerStatsLoading
                )
                SettingItem(
                    title = stringResource(R.string.diagnostics_reset_conn_owner),
                    subtitle = stringResource(R.string.diagnostics_reset_conn_owner_subtitle),
                    icon = Icons.Rounded.Refresh,
                    onClick = { viewModel.resetConnectionOwnerStats() },
                    enabled = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingItem(
                    title = stringResource(R.string.diagnostics_connectivity),
                    subtitle = if (isConnectivityLoading) stringResource(R.string.diagnostics_connectivity_loading) else stringResource(R.string.diagnostics_connectivity_subtitle),
                    icon = Icons.Rounded.NetworkCheck,
                    onClick = { viewModel.runConnectivityCheck() },
                    enabled = !isConnectivityLoading
                )
                SettingItem(
                    title = stringResource(R.string.diagnostics_ping_test),
                    subtitle = if (isPingLoading) stringResource(R.string.diagnostics_routing_test_loading) else stringResource(R.string.diagnostics_ping_test_subtitle),
                    icon = Icons.Rounded.Speed,
                    onClick = { viewModel.runPingTest() },
                    enabled = !isPingLoading
                )
                SettingItem(
                    title = stringResource(R.string.diagnostics_dns_query),
                    subtitle = if (isDnsLoading) stringResource(R.string.common_loading) else stringResource(R.string.diagnostics_dns_query_subtitle),
                    icon = Icons.Rounded.Dns,
                    onClick = { viewModel.runDnsQuery() },
                    enabled = !isDnsLoading
                )
                SettingItem(
                    title = stringResource(R.string.diagnostics_routing_test),
                    subtitle = if (isRoutingLoading) stringResource(R.string.diagnostics_routing_test_loading) else stringResource(R.string.diagnostics_routing_test_subtitle),
                    icon = Icons.Rounded.Route,
                    onClick = { viewModel.runRoutingTest() },
                    enabled = !isRoutingLoading
                )
            }
        }
    }
}
