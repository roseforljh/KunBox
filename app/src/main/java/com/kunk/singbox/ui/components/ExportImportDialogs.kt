package com.kunk.singbox.ui.components

import com.kunk.singbox.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kunk.singbox.model.ExportDataSummary
import com.kunk.singbox.viewmodel.ExportState
import com.kunk.singbox.viewmodel.ImportState
import java.text.SimpleDateFormat
import java.util.*

/**
 * 导出进度对话框
 */
@Composable
fun ExportProgressDialog(
    state: ExportState,
    onDismiss: () -> Unit
) {
    if (state == ExportState.Idle) return

    Dialog(
        onDismissRequest = {
            if (state !is ExportState.Exporting) {
                onDismiss()
            }
        },
        properties = DialogProperties(dismissOnBackPress = state !is ExportState.Exporting)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state) {
                    is ExportState.Exporting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.common_loading),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    is ExportState.Success -> {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.export_success),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.export_success_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onDismiss) {
                            Text(stringResource(R.string.common_ok))
                        }
                    }
                    is ExportState.Error -> {
                        Icon(
                            imageVector = Icons.Rounded.Error,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.export_failed),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onDismiss) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

/**
 * 导入预览对话框
 */
@Composable
fun ImportPreviewDialog(
    summary: ExportDataSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.import_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 数据摘要
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        SummaryRow(stringResource(R.string.import_export_time), dateFormat.format(Date(summary.exportTime)))
                        SummaryRow(stringResource(R.string.import_app_version), summary.appVersion)
                        SummaryRow(stringResource(R.string.import_data_version), "v${summary.version}")

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        SummaryRow(stringResource(R.string.import_profile_count), stringResource(R.string.import_count_items, summary.profileCount))
                        SummaryRow(stringResource(R.string.import_node_count), stringResource(R.string.import_count_items, summary.totalNodeCount))

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        SummaryRow(stringResource(R.string.import_has_settings), if (summary.hasSettings) stringResource(R.string.common_yes) else stringResource(R.string.common_no))
                        SummaryRow(stringResource(R.string.import_has_custom_rules), if (summary.hasCustomRules) stringResource(R.string.common_yes) else stringResource(R.string.common_no))
                        SummaryRow(stringResource(R.string.import_has_rulesets), if (summary.hasRuleSets) stringResource(R.string.common_yes) else stringResource(R.string.common_no))
                        SummaryRow(stringResource(R.string.import_has_app_rules), if (summary.hasAppRules) stringResource(R.string.common_yes) else stringResource(R.string.common_no))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 警告信息
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.import_overwrite_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onConfirm) {
                        Text(stringResource(R.string.import_confirm_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 导入进度/结果对话框
 */
@Composable
fun ImportProgressDialog(
    state: ImportState,
    onDismiss: () -> Unit
) {
    // 只处理正在导入、成功、部分成功、错误状态
    val shouldShow = when (state) {
        is ImportState.Importing,
        is ImportState.Success,
        is ImportState.PartialSuccess,
        is ImportState.Error -> true
        else -> false
    }

    if (!shouldShow) return

    Dialog(
        onDismissRequest = {
            if (state !is ImportState.Importing && state !is ImportState.Validating) {
                onDismiss()
            }
        },
        properties = DialogProperties(dismissOnBackPress = state !is ImportState.Importing)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state) {
                    is ImportState.Importing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.common_loading),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    is ImportState.Success -> {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.import_success),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (state.profilesImported > 0) {
                                Text(
                                    text = stringResource(R.string.import_success) + ": ${state.profilesImported} profiles, ${state.nodesImported} nodes", // TODO: better string
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.settingsImported) {
                                Text(
                                    text = stringResource(R.string.import_settings_restored),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.main_restart), // TODO: better string for "restart VPN"
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onDismiss) {
                            Text(stringResource(R.string.common_ok))
                        }
                    }
                    is ImportState.PartialSuccess -> {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.import_partial_success),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Success: ${state.profilesImported}\nFailed: ${state.profilesFailed}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (state.errors.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    state.errors.take(3).forEach { error ->
                                        Text(
                                            text = "• $error",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    if (state.errors.size > 3) {
                                        Text(
                                            text = "... 还有 ${state.errors.size - 3} 个错误",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onDismiss) {
                            Text(stringResource(R.string.common_ok))
                        }
                    }
                    is ImportState.Error -> {
                        Icon(
                            imageVector = Icons.Rounded.Error,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.import_failed),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onDismiss) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

/**
 * 验证中对话框
 */
@Composable
fun ValidatingDialog() {
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
