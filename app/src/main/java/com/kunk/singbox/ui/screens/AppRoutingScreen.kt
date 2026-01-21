package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.*
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoutingScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(stringResource(R.string.app_rules_tabs_groups), stringResource(R.string.app_rules_tabs_individual))

    var showAddGroupDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<AppGroup?>(null) }
    var showDeleteGroupConfirm by remember { mutableStateOf<AppGroup?>(null) }

    var showAddRuleDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AppRule?>(null) }
    var showDeleteRuleConfirm by remember { mutableStateOf<AppRule?>(null) }

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

    if (showAddGroupDialog) {
        AppGroupEditorDialog(
            installedApps = installedApps,
            nodes = allNodes,
            nodesForSelection = nodesForSelection,
            profiles = profiles,
            onDismiss = { showAddGroupDialog = false },
            onConfirm = { group ->
                settingsViewModel.addAppGroup(group)
                showAddGroupDialog = false
            }
        )
    }

    if (editingGroup != null) {
        AppGroupEditorDialog(
            initialGroup = editingGroup,
            installedApps = installedApps,
            nodes = allNodes,
            nodesForSelection = nodesForSelection,
            profiles = profiles,
            onDismiss = { editingGroup = null },
            onConfirm = { group ->
                settingsViewModel.updateAppGroup(group)
                editingGroup = null
            }
        )
    }

    if (showDeleteGroupConfirm != null) {
        ConfirmDialog(
            title = stringResource(R.string.app_groups_delete_title),
            message = stringResource(R.string.app_rules_delete_confirm, showDeleteGroupConfirm?.name ?: ""), // TODO: check string
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                settingsViewModel.deleteAppGroup(showDeleteGroupConfirm!!.id)
                showDeleteGroupConfirm = null
            },
            onDismiss = { showDeleteGroupConfirm = null }
        )
    }

    if (showAddRuleDialog) {
        AppRuleEditorDialog(
            installedApps = installedApps,
            existingPackages = settings.appRules.map { it.packageName }.toSet(),
            nodes = allNodes,
            nodesForSelection = nodesForSelection,
            profiles = profiles,
            onDismiss = { showAddRuleDialog = false },
            onConfirm = { rule ->
                settingsViewModel.addAppRule(rule)
                showAddRuleDialog = false
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

    if (showDeleteRuleConfirm != null) {
        ConfirmDialog(
            title = stringResource(R.string.app_rules_delete_title),
            message = stringResource(R.string.app_rules_delete_confirm, showDeleteRuleConfirm?.appName ?: ""),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                settingsViewModel.deleteAppRule(showDeleteRuleConfirm!!.id)
                showDeleteRuleConfirm = null
            },
            onDismiss = { showDeleteRuleConfirm = null }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_rules_title), color = MaterialTheme.colorScheme.onBackground) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (selectedTab == 0) showAddGroupDialog = true
                            else showAddRuleDialog = true
                        }) {
                            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.common_add), tint = MaterialTheme.colorScheme.onBackground)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    indicator = { tabPositions ->
                        TabRowDefaults.Indicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    divider = {}
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    title,
                                    color = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (selectedTab == 0) {
                if (settings.appGroups.isEmpty()) {
                    item {
                        EmptyState(Icons.Rounded.Folder, stringResource(R.string.app_rules_empty_groups), stringResource(R.string.app_rules_empty_groups_hint))
                    }
                } else {
                    items(settings.appGroups) { group ->
                        val mode = group.outboundMode ?: RuleSetOutboundMode.PROXY
                        val outboundText = resolveOutboundText(mode, group.outboundValue, allNodes, profiles)
                        AppGroupCard(
                            group = group,
                            outboundText = "${stringResource(mode.displayNameRes)} -> $outboundText",
                            onClick = { editingGroup = group },
                            onToggle = { settingsViewModel.toggleAppGroupEnabled(group.id) },
                            onDelete = { showDeleteGroupConfirm = group }
                        )
                    }
                }
            } else {
                if (settings.appRules.isEmpty()) {
                    item {
                        EmptyState(Icons.Rounded.Apps, stringResource(R.string.app_rules_empty_individual), stringResource(R.string.app_rules_empty_individual_hint))
                    }
                } else {
                    items(settings.appRules) { rule ->
                        val mode = rule.outboundMode ?: RuleSetOutboundMode.PROXY
                        val outboundText = resolveOutboundText(mode, rule.outboundValue, allNodes, profiles)
                        AppRuleItem(
                            rule = rule,
                            outboundText = "${stringResource(mode.displayNameRes)} -> $outboundText",
                            onClick = { editingRule = rule },
                            onToggle = { settingsViewModel.toggleAppRuleEnabled(rule.id) },
                            onDelete = { showDeleteRuleConfirm = rule }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Neutral500, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun resolveOutboundText(
    mode: RuleSetOutboundMode,
    value: String?,
    nodes: List<NodeUi>,
    profiles: List<ProfileUi>
): String {
    return when (mode) {
        RuleSetOutboundMode.DIRECT -> stringResource(R.string.outbound_tag_direct)
        RuleSetOutboundMode.BLOCK -> stringResource(R.string.outbound_tag_block)
        RuleSetOutboundMode.PROXY -> stringResource(R.string.outbound_tag_proxy)
        RuleSetOutboundMode.NODE -> {
            val parts = value?.split("::", limit = 2)
            val node = if (!value.isNullOrBlank() && parts != null && parts.size == 2) {
                val profileId = parts[0]
                val name = parts[1]
                nodes.find { it.sourceProfileId == profileId && it.name == name }
            } else {
                nodes.find { it.id == value } ?: nodes.find { it.name == value }
            }
            val profileName = profiles.find { p -> p.id == node?.sourceProfileId }?.name
            if (node != null && profileName != null) "${node.name} ($profileName)" else stringResource(R.string.app_rules_not_selected)
        }
        RuleSetOutboundMode.PROFILE -> profiles.find { it.id == value }?.name ?: stringResource(R.string.app_rules_unknown_profile)
    }
}
