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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.FloatingPageLayout
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.viewmodel.DiagnosticsViewModel
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    navController: NavController,
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val showResultDialog by viewModel.showResultDialog.collectAsStateWithLifecycle()
    val resultTitle by viewModel.resultTitle.collectAsStateWithLifecycle()
    val resultMessage by viewModel.resultMessage.collectAsStateWithLifecycle()

    val isConnectivityLoading by viewModel.isConnectivityLoading.collectAsStateWithLifecycle()
    val isPingLoading by viewModel.isPingLoading.collectAsStateWithLifecycle()
    val isDnsLoading by viewModel.isDnsLoading.collectAsStateWithLifecycle()
    val isDnsLeakCheckLoading by viewModel.isDnsLeakCheckLoading.collectAsStateWithLifecycle()
    val isRoutingLoading by viewModel.isRoutingLoading.collectAsStateWithLifecycle()
    val isRunConfigLoading by viewModel.isRunConfigLoading.collectAsStateWithLifecycle()
    val isAppRoutingDiagLoading by viewModel.isAppRoutingDiagLoading.collectAsStateWithLifecycle()
    val isConnOwnerStatsLoading by viewModel.isConnOwnerStatsLoading.collectAsStateWithLifecycle()
    val isNodeLineQueryLoading by viewModel.isNodeLineQueryLoading.collectAsStateWithLifecycle()
    val isDiagnosticExporting by viewModel.isDiagnosticExporting.collectAsStateWithLifecycle()
    val isResourceSamplingEnabled by viewModel.isResourceSamplingEnabled.collectAsStateWithLifecycle()
    val resourceSampleCount by viewModel.resourceSampleCount.collectAsStateWithLifecycle()

    if (showResultDialog) {
        ConfirmDialog(
            title = resultTitle,
            message = resultMessage,
            confirmText = stringResource(R.string.common_ok),
            onConfirm = { viewModel.dismissDialog() },
            onDismiss = { viewModel.dismissDialog() }
        )
    }

    FloatingPageLayout(
        title = stringResource(R.string.diagnostics_title),
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
                    SettingSwitchItem(
                        title = stringResource(R.string.diagnostics_resource_sampling),
                        subtitle = when {
                            isResourceSamplingEnabled -> stringResource(
                                R.string.diagnostics_resource_sampling_active,
                                resourceSampleCount
                            )
                            resourceSampleCount > 0 -> stringResource(
                                R.string.diagnostics_resource_sampling_stopped,
                                resourceSampleCount
                            )
                            else -> stringResource(R.string.diagnostics_resource_sampling_subtitle)
                        },
                        icon = Icons.Rounded.Analytics,
                        checked = isResourceSamplingEnabled,
                        onCheckedChange = viewModel::setResourceSamplingEnabled
                    )
                    SettingItem(
                        title = stringResource(R.string.diagnostics_export_package),
                        subtitle = if (isDiagnosticExporting) {
                            stringResource(R.string.diagnostics_export_package_loading)
                        } else {
                            stringResource(R.string.diagnostics_export_package_subtitle)
                        },
                        icon = Icons.Rounded.FileDownload,
                        onClick = viewModel::exportDiagnosticArchive,
                        enabled = !isDiagnosticExporting
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
                    SettingItem(
                        title = stringResource(R.string.diagnostics_node_line_query),
                        subtitle = if (isNodeLineQueryLoading) {
                            stringResource(R.string.diagnostics_node_line_query_loading)
                        } else {
                            stringResource(R.string.diagnostics_node_line_query_subtitle)
                        },
                        icon = Icons.Rounded.Route,
                        onClick = { viewModel.runNodeLineQuery() },
                        enabled = !isNodeLineQueryLoading
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
                        title = stringResource(R.string.diagnostics_dns_leak_check),
                        subtitle = if (isDnsLeakCheckLoading) {
                            stringResource(R.string.diagnostics_dns_leak_check_loading)
                        } else {
                            stringResource(R.string.diagnostics_dns_leak_check_subtitle)
                        },
                        icon = Icons.Rounded.Dns,
                        onClick = { viewModel.runDnsLeakCheck() },
                        enabled = !isDnsLeakCheckLoading
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
}
