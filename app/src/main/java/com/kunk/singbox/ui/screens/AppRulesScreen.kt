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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.R
import com.kunk.singbox.model.*
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.rememberLocalNetworkPermissionRequest
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassEmptyStatePanel
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassMutedContentColor
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarColors
import com.kunk.singbox.utils.LocalNetworkPermission
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
private fun Modifier.outboundChipPanel(): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = CircleShape, selected = true, shadowElevation = 4.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    } else {
        this
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRulesScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AppRule?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AppRule?>(null) }
    val requestLocalNetworkPermission = rememberLocalNetworkPermissionRequest()

    fun saveRuleWithPermissionCheck(rule: AppRule, save: () -> Unit) {
        if (LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(bypassLan = false, appRules = listOf(rule))
            )
        ) {
            requestLocalNetworkPermission(save)
        } else {
            save()
        }
    }

    fun toggleRuleWithPermissionCheck(rule: AppRule) {
        val toggle = { settingsViewModel.toggleAppRuleEnabled(rule.id) }
        if (
            !rule.enabled &&
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(bypassLan = false, appRules = listOf(rule.copy(enabled = true)))
            )
        ) {
            requestLocalNetworkPermission(toggle)
        } else {
            toggle()
        }
    }

    val allNodes by nodesViewModel.allNodes.collectAsStateWithLifecycle()
    val nodesForSelection by nodesViewModel.filteredAllNodes.collectAsStateWithLifecycle()
    val profiles by profilesViewModel.profiles.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        nodesViewModel.setAllNodesUiActive(true)
        onDispose {
            nodesViewModel.setAllNodesUiActive(false)
        }
    }

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
                saveRuleWithPermissionCheck(rule) {
                    settingsViewModel.addAppRule(rule)
                    showAddDialog = false
                }
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
                saveRuleWithPermissionCheck(rule) {
                    settingsViewModel.updateAppRule(rule)
                    editingRule = null
                }
            }
        )
    }

    val ruleToDelete = showDeleteConfirm
    if (ruleToDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.app_rules_delete_title),
            message = stringResource(R.string.app_rules_delete_confirm, ruleToDelete.appName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                settingsViewModel.deleteAppRule(ruleToDelete.id)
                showDeleteConfirm = null
            },
            onDismiss = { showDeleteConfirm = null }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_rules_title), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(
                        modifier = Modifier.liquidGlassIconButtonPanel(),
                        onClick = { navController.popBackStack() }
                    ) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(
                        modifier = Modifier.liquidGlassIconButtonPanel(),
                        onClick = { showAddDialog = true }
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.common_add), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = liquidGlassTopAppBarColors(defaultContainerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            ),
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlassEmptyStatePanel()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.Apps,
                                contentDescription = null,
                                tint = liquidGlassMutedContentColor(Neutral500),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.app_rules_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.app_rules_empty_individual_hint),
                                color = liquidGlassMutedContentColor(Neutral500),
                                fontSize = 12.sp
                            )
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
                        onToggle = { toggleRuleWithPermissionCheck(rule) },
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
    Row(
        modifier = Modifier.outboundChipPanel(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text = label, fontSize = 12.sp, color = color)
    }
}
