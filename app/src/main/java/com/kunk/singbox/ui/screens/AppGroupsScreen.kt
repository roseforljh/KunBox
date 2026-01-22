package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.*
import com.kunk.singbox.repository.InstalledAppsRepository
import com.kunk.singbox.ui.components.AppListLoadingDialog
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.viewmodel.InstalledAppsViewModel
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppGroupsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel(),
    installedAppsViewModel: InstalledAppsViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<AppGroup?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AppGroup?>(null) }

    val allNodes by nodesViewModel.allNodes.collectAsState()
    val nodesForSelection by nodesViewModel.filteredAllNodes.collectAsState()
    val profiles by profilesViewModel.profiles.collectAsState()

    DisposableEffect(Unit) {
        nodesViewModel.setAllNodesUiActive(true)
        onDispose {
            nodesViewModel.setAllNodesUiActive(false)
        }
    }

    // 使用 InstalledAppsViewModel 获取应用列表
    val installedApps by installedAppsViewModel.installedApps.collectAsState()
    val loadingState by installedAppsViewModel.loadingState.collectAsState()
    val isLoading = loadingState !is InstalledAppsRepository.LoadingState.Loaded

    // 触发加载
    LaunchedEffect(Unit) {
        installedAppsViewModel.loadAppsIfNeeded()
    }

    // 显示加载对话框
    AppListLoadingDialog(loadingState = loadingState)

    if (showAddDialog) {
        AppGroupEditorDialog(
            installedApps = installedApps,
            nodes = allNodes,
            nodesForSelection = nodesForSelection,
            profiles = profiles,
            onDismiss = { showAddDialog = false },
            onConfirm = { group ->
                settingsViewModel.addAppGroup(group)
                showAddDialog = false
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

    if (showDeleteConfirm != null) {
        ConfirmDialog(
            title = stringResource(R.string.app_groups_delete_title),
            message = stringResource(R.string.app_groups_delete_confirm, showDeleteConfirm?.name ?: "", showDeleteConfirm?.apps?.size ?: 0),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                settingsViewModel.deleteAppGroup(showDeleteConfirm!!.id)
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
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.app_groups_add), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    StandardCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.app_groups_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (settings.appGroups.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = Neutral500,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(stringResource(R.string.app_groups_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(stringResource(R.string.app_rules_empty_groups_hint), color = Neutral500, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    this.items(items = settings.appGroups) { group ->
                        val mode = group.outboundMode ?: RuleSetOutboundMode.DIRECT
                        val outboundText = when (mode) {
                            RuleSetOutboundMode.DIRECT -> "直连"
                            RuleSetOutboundMode.BLOCK -> "拦截"
                            RuleSetOutboundMode.PROXY -> "代理"
                            RuleSetOutboundMode.NODE -> {
                                val value = group.outboundValue
                                val parts = value?.split("::", limit = 2)
                                val node = if (!value.isNullOrBlank() && parts != null && parts.size == 2) {
                                    val profileId = parts[0]
                                    val name = parts[1]
                                    allNodes.find { it.sourceProfileId == profileId && it.name == name }
                                } else {
                                    allNodes.find { it.id == value } ?: allNodes.find { it.name == value }
                                }
                                val profileName = profiles.find { p -> p.id == node?.sourceProfileId }?.name
                                if (node != null && profileName != null) {
                                    "${node.name} ($profileName)"
                                } else {
                                    stringResource(R.string.app_rules_not_selected)
                                }
                            }
                            RuleSetOutboundMode.PROFILE -> profiles.find { it.id == group.outboundValue }?.name ?: stringResource(R.string.app_rules_unknown_profile)
                        }
                        AppGroupCard(
                            group = group,
                            outboundText = "${stringResource(mode.displayNameRes)} → $outboundText",
                            onClick = { editingGroup = group },
                            onToggle = { settingsViewModel.toggleAppGroupEnabled(group.id) },
                            onDelete = { showDeleteConfirm = group }
                        )
                    }
                }
            }
        }
    }
}
