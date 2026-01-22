package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.AppThemeMode
import com.kunk.singbox.model.AppLanguage
import com.kunk.singbox.model.ImportOptions
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.ui.components.AboutDialog
import com.kunk.singbox.ui.components.EditableTextItem
import com.kunk.singbox.ui.components.ExportProgressDialog
import com.kunk.singbox.ui.components.ImportPreviewDialog
import com.kunk.singbox.ui.components.ImportProgressDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.ValidatingDialog
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.viewmodel.ImportState
import com.kunk.singbox.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val settings by viewModel.settings.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val importState by viewModel.importState.collectAsState()

    var showAboutDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var isUpdatingRuleSets by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }

    // 文件选择器 - 导出
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportData(it) }
    }

    // 文件选择器 - 导入
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.validateImportFile(it) }
    }

    // 生成导出文件名
    fun generateExportFileName(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "singbox_backup_${dateFormat.format(Date())}.json"
    }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false }
        )
    }

    if (showThemeDialog) {
        SingleSelectDialog(
            title = stringResource(R.string.settings_app_theme),
            options = AppThemeMode.entries.map { stringResource(it.displayNameRes) },
            selectedIndex = AppThemeMode.entries.indexOf(settings.appTheme),
            onSelect = { index ->
                viewModel.setAppTheme(AppThemeMode.entries[index])
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showLanguageDialog) {
        SingleSelectDialog(
            title = stringResource(R.string.settings_app_language),
            options = AppLanguage.entries.map { stringResource(it.displayNameRes) },
            selectedIndex = AppLanguage.entries.indexOf(settings.appLanguage),
            onSelect = { index ->
                viewModel.setAppLanguage(AppLanguage.entries[index])
                showLanguageDialog = false
                // 提示用户需要重启应用
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_restart_needed),
                    Toast.LENGTH_LONG
                ).show()
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    // 导出状态对话框
    ExportProgressDialog(
        state = exportState,
        onDismiss = { viewModel.resetExportState() }
    )

    // 导入预览对话框
    if (importState is ImportState.Preview) {
        val previewState = importState as ImportState.Preview
        ImportPreviewDialog(
            summary = previewState.summary,
            onConfirm = {
                viewModel.confirmImport(previewState.uri, ImportOptions(overwriteExisting = true))
            },
            onDismiss = { viewModel.resetImportState() }
        )
    }

    // 导入进度/结果对话框
    ImportProgressDialog(
        state = importState,
        onDismiss = { viewModel.resetImportState() }
    )

    // 验证中对话框
    if (importState is ImportState.Validating) {
        ValidatingDialog()
    }

    // 导入错误处理（如果在 Preview 之前就出错）
    LaunchedEffect(importState) {
        if (importState is ImportState.Error) {
            // 错误会在 ImportProgressDialog 中显示
        }
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = statusBarPadding.calculateTopPadding())
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 1. Connection & Startup
        SettingsGroupTitle(stringResource(R.string.settings_general))
        StandardCard {
            SettingItem(
                title = stringResource(R.string.settings_app_theme),
                value = stringResource(settings.appTheme.displayNameRes),
                icon = Icons.Rounded.Brightness6,
                onClick = { showThemeDialog = true }
            )
            SettingItem(
                title = stringResource(R.string.settings_app_language),
                value = stringResource(settings.appLanguage.displayNameRes),
                icon = Icons.Rounded.Language,
                onClick = { showLanguageDialog = true }
            )
            SettingSwitchItem(
                title = "自动检查更新",
                subtitle = "启动应用时自动检查新版本",
                icon = Icons.Rounded.SystemUpdate,
                checked = settings.autoCheckUpdate,
                onCheckedChange = { scope.launch { viewModel.setAutoCheckUpdate(it) } }
            )
            SettingItem(
                title = stringResource(R.string.settings_connection_startup),
                subtitle = stringResource(R.string.settings_connection_startup_subtitle),
                icon = Icons.Rounded.PowerSettingsNew,
                onClick = { navController.navigate(Screen.ConnectionSettings.route) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Network
        SettingsGroupTitle(stringResource(R.string.settings_network))
        StandardCard {
            SettingItem(
                title = stringResource(R.string.settings_routing),
                subtitle = stringResource(R.string.settings_routing_subtitle),
                icon = Icons.Rounded.Route,
                onClick = { navController.navigate(Screen.RoutingSettings.route) }
            )
            SettingItem(
                title = stringResource(R.string.settings_dns),
                value = stringResource(R.string.settings_dns_auto),
                icon = Icons.Rounded.Dns,
                onClick = { navController.navigate(Screen.DnsSettings.route) }
            )
            SettingItem(
                title = stringResource(R.string.settings_tun_vpn),
                subtitle = stringResource(R.string.settings_tun_vpn_subtitle),
                icon = Icons.Rounded.VpnKey,
                onClick = { navController.navigate(Screen.TunSettings.route) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Tools
        // Pre-define string resources for use in click handlers
        val preparingUpdateMsg = stringResource(R.string.settings_preparing_update)
        val updateSuccessMsg = stringResource(R.string.settings_update_success)
        val updateFailedMsg = stringResource(R.string.settings_update_failed)
        val rulesetUpdateSuccessMsg = stringResource(R.string.settings_ruleset_update_success)
        val rulesetUpdateFailedMsg = stringResource(R.string.settings_ruleset_update_failed)

        SettingsGroupTitle(stringResource(R.string.settings_tools))
        StandardCard {
            SettingItem(
                title = if (isUpdatingRuleSets) updateMessage else stringResource(R.string.settings_update_rulesets),
                subtitle = if (isUpdatingRuleSets) stringResource(R.string.settings_updating) else stringResource(R.string.settings_update_rulesets_subtitle),
                icon = Icons.Rounded.Sync,
                trailing = {
                    if (isUpdatingRuleSets) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    }
                },
                onClick = {
                    if (!isUpdatingRuleSets) {
                        isUpdatingRuleSets = true
                        updateMessage = preparingUpdateMsg
                        scope.launch {
                            try {
                                val success = RuleSetRepository.getInstance(context).ensureRuleSetsReady(
                                    forceUpdate = true,
                                    allowNetwork = true
                                ) {
                                    updateMessage = it
                                }
                                updateMessage = if (success) updateSuccessMsg else updateFailedMsg
                                Toast.makeText(
                                    context,
                                    if (success) rulesetUpdateSuccessMsg else rulesetUpdateFailedMsg,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                updateMessage = "Error: ${e.message}"
                                Toast.makeText(
                                    context,
                                    "$rulesetUpdateFailedMsg: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } finally {
                                kotlinx.coroutines.delay(1000)
                                isUpdatingRuleSets = false
                            }
                        }
                    }
                }
            )
            SettingSwitchItem(
                title = stringResource(R.string.settings_ruleset_auto_update),
                subtitle = if (settings.ruleSetAutoUpdateEnabled)
                    stringResource(R.string.settings_ruleset_auto_update_enabled, settings.ruleSetAutoUpdateInterval)
                else
                    stringResource(R.string.settings_ruleset_auto_update_disabled),
                icon = Icons.Rounded.Schedule,
                checked = settings.ruleSetAutoUpdateEnabled,
                onCheckedChange = { viewModel.setRuleSetAutoUpdateEnabled(it) }
            )
            if (settings.ruleSetAutoUpdateEnabled) {
                val intervalMinMsg = stringResource(R.string.settings_update_interval_min)
                EditableTextItem(
                    title = stringResource(R.string.settings_update_interval),
                    value = stringResource(R.string.settings_update_interval_value, settings.ruleSetAutoUpdateInterval),
                    onValueChange = { newValue ->
                        val interval = newValue.filter { it.isDigit() }.toIntOrNull()
                        if (interval != null && interval >= 15) {
                            viewModel.setRuleSetAutoUpdateInterval(interval)
                        } else {
                            Toast.makeText(context, intervalMinMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            SettingSwitchItem(
                title = stringResource(R.string.settings_debug_mode),
                subtitle = stringResource(R.string.settings_debug_mode_subtitle),
                icon = Icons.Rounded.BugReport,
                checked = settings.debugLoggingEnabled,
                onCheckedChange = { viewModel.setDebugLoggingEnabled(it) }
            )
            SettingItem(
                title = stringResource(R.string.settings_logs),
                icon = Icons.Rounded.History,
                onClick = { navController.navigate(Screen.Logs.route) }
            )
            SettingItem(
                title = stringResource(R.string.settings_network_diagnostics),
                icon = Icons.Rounded.BugReport,
                onClick = { navController.navigate(Screen.Diagnostics.route) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. 数据管理
        SettingsGroupTitle(stringResource(R.string.settings_data_management))
        StandardCard {
            SettingItem(
                title = stringResource(R.string.settings_export_data),
                subtitle = stringResource(R.string.settings_export_data_subtitle),
                icon = Icons.Rounded.Upload,
                onClick = {
                    exportLauncher.launch(generateExportFileName())
                }
            )
            SettingItem(
                title = stringResource(R.string.settings_import_data),
                subtitle = stringResource(R.string.settings_import_data_subtitle),
                icon = Icons.Rounded.Download,
                onClick = {
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. About
        SettingsGroupTitle(stringResource(R.string.settings_about))
        StandardCard {
            SettingItem(
                title = stringResource(R.string.settings_about_app),
                icon = Icons.Rounded.Info,
                onClick = { showAboutDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}
