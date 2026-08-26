package com.kunk.singbox.ui.screens

import android.graphics.Bitmap
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.*
import com.kunk.singbox.repository.InstalledAppsRepository
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.FloatingPageLayout
import com.kunk.singbox.ui.components.rememberLocalNetworkPermissionRequest
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.viewmodel.InstalledAppsViewModel
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import com.kunk.singbox.ui.theme.liquidGlassEmptyStatePanel
import com.kunk.singbox.ui.theme.liquidGlassMutedContentColor
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor
import com.kunk.singbox.utils.LocalNetworkPermission
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoutingScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel(),
    installedAppsViewModel: InstalledAppsViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val appRoutingSupported = settings.resolvedTrafficCaptureMode() != TrafficCaptureMode.PROXY_ONLY
    val context = LocalContext.current
    val requestLocalNetworkPermission = rememberLocalNetworkPermissionRequest()

    var showAddGroupDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<AppGroup?>(null) }
    var showDeleteGroupConfirm by remember { mutableStateOf<AppGroup?>(null) }

    val allNodes by nodesViewModel.allNodes.collectAsStateWithLifecycle()
    val nodesForSelection by nodesViewModel.filteredAllNodes.collectAsStateWithLifecycle()
    val profiles by profilesViewModel.profiles.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        nodesViewModel.setAllNodesUiActive(true)
        onDispose {
            nodesViewModel.setAllNodesUiActive(false)
        }
    }

    LaunchedEffect(Unit) {
        installedAppsViewModel.loadAppsIfNeeded()
    }

    val installedApps by installedAppsViewModel.appItems.collectAsStateWithLifecycle()
    val inventoryState by installedAppsViewModel.loadingState.collectAsStateWithLifecycle()
    val inventoryAvailable = installedApps.isNotEmpty() &&
        inventoryState is InstalledAppsRepository.LoadingState.Loaded
    val capturedPackages = remember(settings, installedApps, context.packageName) {
        PerAppVpnScopeResolver.resolve(
            policy = PerAppVpnPolicy.from(settings),
            installedApps = installedApps,
            selfPackage = context.packageName
        ).capturedPackages
    }
    val selectableApps = remember(installedApps, capturedPackages) {
        installedApps.filter { it.packageName in capturedPackages }
    }
    val loadAppIcon = remember(installedAppsViewModel) {
        installedAppsViewModel::loadIcon
    }
    val loadAppIcons = remember(installedAppsViewModel) {
        installedAppsViewModel::loadIcons
    }
    val previewPackages = remember(settings.appGroups, inventoryAvailable, capturedPackages) {
        settings.appGroups.asSequence()
            .flatMap { group ->
                group.apps.asSequence()
                    .filter { !inventoryAvailable || it.packageName in capturedPackages }
                    .take(8)
            }
            .map(AppInfo::packageName)
            .distinct()
            .toList()
    }
    var previewIcons by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    LaunchedEffect(previewPackages, loadAppIcons) {
        previewIcons = loadAppIcons(previewPackages)
    }

    fun saveGroupWithPermissionCheck(group: AppGroup, save: () -> Unit) {
        if (LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(bypassLan = false, appGroups = listOf(group))
            )
        ) {
            requestLocalNetworkPermission(save)
        } else {
            save()
        }
    }

    fun toggleGroupWithPermissionCheck(group: AppGroup) {
        val toggle = { settingsViewModel.toggleAppGroupEnabled(group.id) }
        if (
            !group.enabled &&
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(bypassLan = false, appGroups = listOf(group.copy(enabled = true)))
            )
        ) {
            requestLocalNetworkPermission(toggle)
        } else {
            toggle()
        }
    }

    if (showAddGroupDialog) {
        AppGroupEditorDialog(
            installedApps = selectableApps,
            nodes = allNodes,
            nodesForSelection = nodesForSelection,
            profiles = profiles,
            loadIcon = loadAppIcon,
            loadIcons = loadAppIcons,
            onDismiss = { showAddGroupDialog = false },
            onConfirm = { group ->
                saveGroupWithPermissionCheck(group) {
                    settingsViewModel.addAppGroup(group)
                    showAddGroupDialog = false
                }
            }
        )
    }

    if (editingGroup != null) {
        val originalGroup = requireNotNull(editingGroup)
        val dormantApps = if (inventoryAvailable) {
            originalGroup.apps.filterNot { it.packageName in capturedPackages }
        } else {
            emptyList()
        }
        AppGroupEditorDialog(
            initialGroup = originalGroup.copy(
                apps = if (inventoryAvailable) {
                    originalGroup.apps.filter { it.packageName in capturedPackages }
                } else {
                    originalGroup.apps
                }
            ),
            installedApps = selectableApps,
            nodes = allNodes,
            nodesForSelection = nodesForSelection,
            profiles = profiles,
            loadIcon = loadAppIcon,
            loadIcons = loadAppIcons,
            onDismiss = { editingGroup = null },
            onConfirm = { group ->
                val mergedGroup = group.copy(
                    apps = (dormantApps + group.apps).distinctBy(AppInfo::packageName)
                )
                saveGroupWithPermissionCheck(mergedGroup) {
                    settingsViewModel.updateAppGroup(mergedGroup)
                    editingGroup = null
                }
            }
        )
    }

    val groupToDelete = showDeleteGroupConfirm
    if (groupToDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.app_groups_delete_title),
            message = stringResource(
                R.string.app_groups_delete_confirm,
                groupToDelete.name,
                groupToDelete.apps.size
            ),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                settingsViewModel.deleteAppGroup(groupToDelete.id)
                showDeleteGroupConfirm = null
            },
            onDismiss = { showDeleteGroupConfirm = null }
        )
    }

    FloatingPageLayout(
        title = stringResource(R.string.app_rules_title),
        onBack = { navController.popBackStack() },
        actions = {
            if (appRoutingSupported) {
                IconButton(onClick = { showAddGroupDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.common_add), tint = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    ) { contentTopPadding ->
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background)
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = contentTopPadding + 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!appRoutingSupported) {
                    item {
                        EmptyState(
                            Icons.Rounded.Info,
                            stringResource(R.string.app_routing_proxy_only_unsupported),
                            stringResource(R.string.app_routing_proxy_only_unsupported_hint)
                        )
                    }
                } else if (settings.appGroups.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Rounded.Folder,
                            stringResource(R.string.app_rules_empty_groups),
                            stringResource(R.string.app_rules_empty_groups_hint)
                        )
                    }
                } else {
                    items(settings.appGroups) { group ->
                        val effectiveGroup = group.copy(
                            apps = if (inventoryAvailable) {
                                group.apps.filter { it.packageName in capturedPackages }
                            } else {
                                group.apps
                            }
                        )
                        val mode = group.outboundMode ?: RuleSetOutboundMode.PROXY
                        val outboundText = resolveOutboundText(mode, group.outboundValue, allNodes, profiles)
                        AppGroupCard(
                            group = effectiveGroup,
                            outboundText = "${stringResource(mode.displayNameRes)} -> $outboundText",
                            icons = previewIcons,
                            onClick = { editingGroup = group },
                            onToggle = { toggleGroupWithPermissionCheck(group) },
                            onDelete = { showDeleteGroupConfirm = group }
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
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassEmptyStatePanel()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = liquidGlassMutedContentColor(Neutral500),
                modifier = Modifier.size(48.dp)
            )
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
