package com.kunk.singbox.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.R
import com.kunk.singbox.model.*
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRulesScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AppRule?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AppRule?>(null) }

    val allNodes by nodesViewModel.allNodes.collectAsState()
    val nodesForSelection by nodesViewModel.filteredAllNodes.collectAsState()
    val profiles by profilesViewModel.profiles.collectAsState()

    DisposableEffect(Unit) {
        nodesViewModel.setAllNodesUiActive(true)
        onDispose {
            nodesViewModel.setAllNodesUiActive(false)
        }
    }

    // 仅显示在 allowlist 中的应用，直接根据包名获取应用信息
    val installedApps = remember(settings.vpnAllowlist) {
        val pm = context.packageManager
        settings.vpnAllowlist
            .split("\n", "\r", ",", ";", " ", "\t")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { packageName ->
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val appName = appInfo.loadLabel(pm).toString()
                    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    InstalledApp(packageName, appName, isSystemApp)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }
            .sortedBy { it.appName.lowercase() }
    }

    if (showAddDialog) {
        AppRuleEditorDialog(
            installedApps = installedApps,
            existingPackages = settings.appRules.map { it.packageName }.toSet(),
            nodes = allNodes,
            nodesForSelection = nodesForSelection,
            profiles = profiles,
            onDismiss = { showAddDialog = false },
            onConfirm = { rule ->
                settingsViewModel.addAppRule(rule)
                showAddDialog = false
            }
        )
    }

    if (editingRule != null) {
        AppRuleEditorDialog(
            initialRule = editingRule,
            installedApps = installedApps,
            existingPackages = settings.appRules.filter { it.id != editingRule?.id }.map { it.packageName }.toSet(),
            nodes = allNodes,
            nodesForSelection = nodesForSelection,
            profiles = profiles,
            onDismiss = { editingRule = null },
            onConfirm = { rule ->
                settingsViewModel.updateAppRule(rule)
                editingRule = null
            }
        )
    }

    if (showDeleteConfirm != null) {
        ConfirmDialog(
            title = stringResource(R.string.app_rules_delete_title),
            message = stringResource(R.string.app_rules_delete_confirm, showDeleteConfirm?.appName ?: ""),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                settingsViewModel.deleteAppRule(showDeleteConfirm!!.id)
                showDeleteConfirm = null
            },
            onDismiss = { showDeleteConfirm = null }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_rules_title), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.common_add), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StandardCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.app_rules_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutboundChip(RuleSetOutboundMode.PROXY, stringResource(R.string.outbound_tag_proxy))
                            OutboundChip(RuleSetOutboundMode.BLOCK, stringResource(R.string.outbound_tag_block))
                        }
                    }
                }
            }

            if (settings.appRules.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Apps, contentDescription = null, tint = Neutral500, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.app_rules_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.app_rules_empty_individual_hint), color = Neutral500, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                items(settings.appRules) { rule ->
                    val mode = rule.outboundMode ?: RuleSetOutboundMode.PROXY
                    val outboundText = when (mode) {
                        RuleSetOutboundMode.DIRECT -> stringResource(R.string.outbound_tag_direct)
                        RuleSetOutboundMode.BLOCK -> stringResource(R.string.outbound_tag_block)
                        RuleSetOutboundMode.PROXY -> stringResource(R.string.outbound_tag_proxy)
                        RuleSetOutboundMode.NODE -> {
                            val value = rule.outboundValue
                            val parts = value?.split("::", limit = 2)
                            val node = if (!value.isNullOrBlank() && parts != null && parts.size == 2) {
                                val profileId = parts[0]
                                val name = parts[1]
                                allNodes.find { it.sourceProfileId == profileId && it.name == name }
                            } else {
                                allNodes.find { it.id == value } ?: allNodes.find { it.name == value }
                            }
                            val profileName = profiles.find { p -> p.id == node?.sourceProfileId }?.name
                            if (node != null && profileName != null) "${node.name} ($profileName)" else stringResource(R.string.app_rules_not_selected)
                        }
                        RuleSetOutboundMode.PROFILE -> profiles.find { it.id == rule.outboundValue }?.name ?: stringResource(R.string.app_rules_unknown_profile)
                    }
                    AppRuleItem(
                        rule = rule,
                        outboundText = "${stringResource(mode.displayNameRes)} -> $outboundText",
                        onClick = { editingRule = rule },
                        onToggle = { settingsViewModel.toggleAppRuleEnabled(rule.id) },
                        onDelete = { showDeleteConfirm = rule }
                    )
                }
            }
        }
    }
}

@Composable
fun OutboundChip(mode: RuleSetOutboundMode, label: String) {
    val (icon, color) = when (mode) {
        RuleSetOutboundMode.PROXY, RuleSetOutboundMode.NODE, RuleSetOutboundMode.PROFILE -> Icons.Rounded.Shield to Color(0xFF4CAF50)
        RuleSetOutboundMode.DIRECT -> Icons.Rounded.Public to Color(0xFF2196F3)
        RuleSetOutboundMode.BLOCK -> Icons.Rounded.Block to Color(0xFFFF5252)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text = label, fontSize = 12.sp, color = color)
    }
}
