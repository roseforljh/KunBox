package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.ui.components.ClickableDropdownField
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.ProfileNodeSelectDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import com.kunk.singbox.ui.navigation.Screen
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kunk.singbox.viewmodel.DefaultRuleSetDownloadState
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.NodeUi
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos

import androidx.compose.ui.draw.scale

private val defaultRuleSetTags = setOf(
    "geosite-cn",
    "geoip-cn",
    "geosite-geolocation-!cn",
    "geosite-category-ads-all",
    "geosite-private"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RuleSetsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    val downloadingRuleSets by settingsViewModel.downloadingRuleSets.collectAsState()
    val defaultRuleSetDownloadState by settingsViewModel.defaultRuleSetDownloadState.collectAsState()
    val allNodes by nodesViewModel.allNodes.collectAsState()
    val nodesForSelection by nodesViewModel.filteredAllNodes.collectAsState()
    val profiles by profilesViewModel.profiles.collectAsState()

    DisposableEffect(Unit) {
        nodesViewModel.setAllNodesUiActive(true)
        onDispose {
            nodesViewModel.setAllNodesUiActive(false)
        }
    }

    LaunchedEffect(settings.ruleSets.isEmpty()) {
        if (settings.ruleSets.isEmpty()) {
            settingsViewModel.ensureDefaultRuleSetsReady()
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingRuleSet by remember { mutableStateOf<RuleSet?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateMapOf<String, Boolean>() }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Outbound/Inbound dialog states
    var outboundEditingRuleSet by remember { mutableStateOf<RuleSet?>(null) }
    var showOutboundModeDialog by remember { mutableStateOf(false) }
    var showTargetSelectionDialog by remember { mutableStateOf(false) }
    var showNodeSelectionDialog by remember { mutableStateOf(false) }
    var showInboundDialog by remember { mutableStateOf(false) }
    var targetSelectionTitle by remember { mutableStateOf("") }
    var targetOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val availableInbounds = listOf("tun", "mixed")

    val selectionNodes = nodesForSelection

    // Helper functions for node resolution
    fun resolveNodeByStoredValue(value: String?): NodeUi? {
        if (value.isNullOrBlank()) return null
        val parts = value.split("::", limit = 2)
        if (parts.size == 2) {
            val profileId = parts[0]
            val name = parts[1]
            return allNodes.find { it.sourceProfileId == profileId && it.name == name }
        }
        return allNodes.find { it.id == value } ?: allNodes.find { it.name == value }
    }

    fun toNodeRef(node: NodeUi): String = "${node.sourceProfileId}::${node.name}"

    // Reordering State
    val ruleSets = remember { mutableStateListOf<RuleSet>() }
    // Only sync if dragging is NOT active to avoid conflicts
    val isDragging = remember { mutableStateOf(false) }
    var suppressPlacementAnimation by remember { mutableStateOf(false) }
    val enablePlacementAnimation = false

    LaunchedEffect(settings.ruleSets) {
        if (!isDragging.value) {
            // Only update if the set of IDs has changed or size changed
            // This prevents overwriting local reordering with stale remote data immediately after drop
            val currentIds = ruleSets.map { it.id }.toSet()
            val newIds = settings.ruleSets.map { it.id }.toSet()

            if (currentIds != newIds || ruleSets.size != settings.ruleSets.size || ruleSets.isEmpty()) {
                ruleSets.clear()
                ruleSets.addAll(settings.ruleSets)
            } else {
                // If IDs match but order differs, we assume local state is correct (unless we want to force sync)
                // To be safe, if the lists are drastically different (e.g. initial load), we sync.
                // But for reordering, we trust the local operation.
                // Double check if we need to sync for property updates (e.g. name change)
                if (ruleSets.map { it.toString() } != settings.ruleSets.map { it.toString() }) {
                    // Content might have changed, but try to preserve order if possible?
                    // For now, simpler approach: if local state matches the ID set, we trust local order.
                    // But if properties changed, we should update items in place?
                    // Let's just do a smart update:
                    settings.ruleSets.forEach { newRule ->
                        val index = ruleSets.indexOfFirst { it.id == newRule.id }
                        if (index != -1 && ruleSets[index] != newRule) {
                            ruleSets[index] = newRule
                        }
                    }
                }
            }
        }
    }

    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggingItemOffset by remember { mutableStateOf(0f) }
    var itemHeightPx by remember { mutableStateOf(0f) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems.clear()
    }

    fun toggleSelection(id: String) {
        selectedItems[id] = !(selectedItems[id] ?: false)
        if (selectedItems.none { it.value }) {
            exitSelectionMode()
        }
    }

    if (showAddDialog) {
        RuleSetEditorDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { ruleSet ->
                settingsViewModel.addRuleSet(ruleSet)
                showAddDialog = false
            }
        )
    }

    if (editingRuleSet != null) {
        RuleSetEditorDialog(
            initialRuleSet = editingRuleSet,
            onDismiss = { editingRuleSet = null },
            onConfirm = { ruleSet ->
                settingsViewModel.updateRuleSet(ruleSet)
                editingRuleSet = null
            },
            onDelete = {
                settingsViewModel.deleteRuleSet(editingRuleSet!!.id)
                editingRuleSet = null
            }
        )
    }

    if (defaultRuleSetDownloadState.isActive) {
        DefaultRuleSetProgressDialog(
            state = defaultRuleSetDownloadState,
            onCancel = { settingsViewModel.cancelDefaultRuleSetDownload() }
        )
    }

    // Pre-load string resources for use in callbacks
    val profilesDeletedMsg = stringResource(R.string.profiles_deleted)
    val selectProfileMsg = stringResource(R.string.rulesets_select_profile)

    if (showDeleteConfirmDialog) {
        val selectedCount = selectedItems.count { it.value }
        ConfirmDialog(
            title = stringResource(R.string.rulesets_delete_title),
            message = stringResource(R.string.rulesets_delete_batch_confirm, selectedCount),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                val idsToDelete = selectedItems.filter { it.value }.keys.toList()
                settingsViewModel.deleteRuleSets(idsToDelete)
                scope.launch {
                    snackbarHostState.showSnackbar(profilesDeletedMsg)
                }
                showDeleteConfirmDialog = false
                exitSelectionMode()
            },
            onDismiss = { showDeleteConfirmDialog = false }
        )
    }

    // Outbound Mode Dialog
    if (showOutboundModeDialog && outboundEditingRuleSet != null) {
        val options = RuleSetOutboundMode.entries.map { stringResource(it.displayNameRes) }
        val currentMode = outboundEditingRuleSet!!.outboundMode ?: RuleSetOutboundMode.DIRECT
        SingleSelectDialog(
            title = stringResource(R.string.rulesets_select_outbound),
            options = options,
            selectedIndex = RuleSetOutboundMode.entries.indexOf(currentMode),
            onSelect = { index ->
                val selectedMode = RuleSetOutboundMode.entries[index]
                val updatedRuleSet = outboundEditingRuleSet!!.copy(outboundMode = selectedMode, outboundValue = null)

                if (selectedMode == RuleSetOutboundMode.NODE ||
                    selectedMode == RuleSetOutboundMode.PROFILE
                ) {
                    outboundEditingRuleSet = updatedRuleSet
                    showOutboundModeDialog = false

                    when (selectedMode) {
                        RuleSetOutboundMode.NODE -> {
                            showNodeSelectionDialog = true
                        }
                        RuleSetOutboundMode.PROFILE -> {
                            targetSelectionTitle = selectProfileMsg
                            targetOptions = profiles.map { it.name to it.id }
                        }
                        else -> {}
                    }
                    if (selectedMode != RuleSetOutboundMode.NODE) {
                        showTargetSelectionDialog = true
                    }
                } else {
                    settingsViewModel.updateRuleSet(updatedRuleSet)
                    outboundEditingRuleSet = null
                    showOutboundModeDialog = false
                }
            },
            onDismiss = {
                showOutboundModeDialog = false
                outboundEditingRuleSet = null
            }
        )
    }

    // Target Selection Dialog
    if (showTargetSelectionDialog && outboundEditingRuleSet != null) {
        val currentValue = outboundEditingRuleSet!!.outboundValue
        val currentRef = resolveNodeByStoredValue(currentValue)?.let { toNodeRef(it) } ?: currentValue
        SingleSelectDialog(
            title = targetSelectionTitle,
            options = targetOptions.map { it.first },
            selectedIndex = targetOptions.indexOfFirst { it.second == currentRef },
            onSelect = { index ->
                val selectedValue = targetOptions[index].second
                val updatedRuleSet = outboundEditingRuleSet!!.copy(outboundValue = selectedValue)
                settingsViewModel.updateRuleSet(updatedRuleSet)
                showTargetSelectionDialog = false
                outboundEditingRuleSet = null
            },
            onDismiss = {
                showTargetSelectionDialog = false
                outboundEditingRuleSet = null
            }
        )
    }

    if (showNodeSelectionDialog && outboundEditingRuleSet != null) {
        val currentValue = outboundEditingRuleSet!!.outboundValue
        val currentRef = resolveNodeByStoredValue(currentValue)?.let { toNodeRef(it) } ?: currentValue
        ProfileNodeSelectDialog(
            title = stringResource(R.string.rulesets_select_node),
            profiles = profiles,
            nodesForSelection = selectionNodes,
            selectedNodeRef = currentRef,
            onSelect = { ref ->
                val updatedRuleSet = outboundEditingRuleSet!!.copy(outboundValue = ref)
                settingsViewModel.updateRuleSet(updatedRuleSet)
            },
            onDismiss = {
                showNodeSelectionDialog = false
                outboundEditingRuleSet = null
            }
        )
    }

    // Inbound Dialog
    if (showInboundDialog && outboundEditingRuleSet != null) {
        AlertDialog(
            onDismissRequest = {
                showInboundDialog = false
                outboundEditingRuleSet = null
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.rulesets_select_inbound), color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    availableInbounds.forEach { inbound ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val currentInbounds = (outboundEditingRuleSet!!.inbounds ?: emptyList()).toMutableList()
                                    if (currentInbounds.contains(inbound)) {
                                        currentInbounds.remove(inbound)
                                    } else {
                                        currentInbounds.add(inbound)
                                    }
                                    outboundEditingRuleSet = outboundEditingRuleSet!!.copy(inbounds = currentInbounds)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = (outboundEditingRuleSet!!.inbounds ?: emptyList()).contains(inbound),
                                onCheckedChange = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = inbound, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.updateRuleSet(outboundEditingRuleSet!!)
                        showInboundDialog = false
                        outboundEditingRuleSet = null
                    }
                ) {
                    Text(stringResource(R.string.common_ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showInboundDialog = false
                        outboundEditingRuleSet = null
                    }
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(stringResource(R.string.rulesets_selection_mode, selectedItems.count { it.value }), color = MaterialTheme.colorScheme.onBackground)
                    } else {
                        Text(stringResource(R.string.rulesets_title), color = MaterialTheme.colorScheme.onBackground)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            exitSelectionMode()
                        } else {
                            navController.popBackStack()
                        }
                    }) {
                        Icon(
                            if (isSelectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = if (isSelectionMode) "取消" else "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        val selectedCount = selectedItems.count { it.value }
                        IconButton(
                            onClick = { showDeleteConfirmDialog = true },
                            enabled = selectedCount > 0
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "删除",
                                tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        IconButton(onClick = { navController.navigate(Screen.RuleSetHub.route) }) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = "导入", tint = MaterialTheme.colorScheme.onBackground)
                        }
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = "添加", tint = MaterialTheme.colorScheme.onBackground)
                        }
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
            state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (ruleSets.isEmpty() && !defaultRuleSetDownloadState.isActive) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.rulesets_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                items(ruleSets.size, key = { ruleSets[it].id }) { index ->
                    val ruleSet = ruleSets[index]

                    // Calculate visual offset based on drag state
                    // We DO NOT swap the list during drag to avoid recomposition issues.
                    // Instead, we visually shift items.

                    val currentDraggingIndex = draggingItemIndex
                    val currentDragOffset = draggingItemOffset

                    // Calculate the "projected" index of the dragged item
                    var translationY = 0f
                    var zIndex = 0f

                    if (currentDraggingIndex != null && itemHeightPx > 0) {
                        if (index == currentDraggingIndex) {
                            translationY = currentDragOffset
                            zIndex = 1f
                        } else {
                            // Determine if this item should shift
                            val dist = (currentDragOffset / itemHeightPx).toInt()
                            val targetIndex = (currentDraggingIndex + dist).coerceIn(0, ruleSets.lastIndex)

                            if (currentDraggingIndex < targetIndex) {
                                // Dragging down: items between current and target shift UP
                                if (index in (currentDraggingIndex + 1)..targetIndex) {
                                    translationY = -itemHeightPx
                                }
                            } else if (currentDraggingIndex > targetIndex) {
                                // Dragging up: items between target and current shift DOWN
                                if (index in targetIndex until currentDraggingIndex) {
                                    translationY = itemHeightPx
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .zIndex(zIndex)
                            .graphicsLayer {
                                this.translationY = translationY
                                if (index == currentDraggingIndex) {
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                    shadowElevation = 8.dp.toPx()
                                }
                            }
                            .onGloballyPositioned { coordinates ->
                                if (itemHeightPx == 0f) {
                                    val spacingPx = with(density) { 16.dp.toPx() }
                                    itemHeightPx = coordinates.size.height.toFloat() + spacingPx
                                }
                            }
                            .then(
                                if (!enablePlacementAnimation || suppressPlacementAnimation) {
                                    Modifier
                                } else {
                                    Modifier.animateItem()
                                }
                            )
                    ) {
                        RuleSetItem(
                            ruleSet = ruleSet,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedItems[ruleSet.id] ?: false,
                            isDownloading = downloadingRuleSets.contains(ruleSet.tag),
                            onClick = {
                                if (isSelectionMode) {
                                    toggleSelection(ruleSet.id)
                                }
                            },
                            onToggle = { enabled ->
                                settingsViewModel.updateRuleSet(ruleSet.copy(enabled = enabled))
                            },
                            onEditClick = { editingRuleSet = ruleSet },
                            onDeleteClick = { settingsViewModel.deleteRuleSet(ruleSet.id) },
                            onOutboundClick = {
                                outboundEditingRuleSet = ruleSet
                                showOutboundModeDialog = true
                            },
                            onInboundClick = {
                                outboundEditingRuleSet = ruleSet
                                showInboundDialog = true
                            },
                            modifier = Modifier
                                .pointerInput(index) { // Key on index to ensure closure captures latest position
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            if (!isSelectionMode) {
                                                draggingItemIndex = index
                                                draggingItemOffset = 0f
                                                isDragging.value = true
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            }
                                        },
                                        onDragEnd = {
                                            if (draggingItemIndex != null) {
                                                val startIdx = draggingItemIndex!!
                                                val dist = kotlin.math.round(draggingItemOffset / itemHeightPx).toInt()
                                                val endIdx = (startIdx + dist).coerceIn(0, ruleSets.lastIndex)

                                                // Disable placement animation for the drop frame so the card settles immediately.
                                                suppressPlacementAnimation = true

                                                // Preserve absolute scroll position (px) to prevent list "jump" after reordering.
                                                // Anchoring by item-id causes a one-item "drop" when swapping with the first row.
                                                val absScrollBefore = if (itemHeightPx > 0f) {
                                                    listState.firstVisibleItemIndex * itemHeightPx + listState.firstVisibleItemScrollOffset
                                                } else {
                                                    null
                                                }

                                                if (startIdx != endIdx) {
                                                    // Immediately update UI list state to reflect final position
                                                    val item = ruleSets.removeAt(startIdx)
                                                    ruleSets.add(endIdx, item)

                                                    // Asynchronously sync with ViewModel
                                                    settingsViewModel.reorderRuleSets(ruleSets.toList())
                                                }

                                                // Restore absolute scroll position (no animation).
                                                // This keeps the page from visually shifting when items above the viewport change.
                                                val abs = absScrollBefore
                                                if (abs != null && itemHeightPx > 0f) {
                                                    val targetIndex = (abs / itemHeightPx).toInt().coerceIn(0, ruleSets.lastIndex)
                                                    val targetOffset = (abs - targetIndex * itemHeightPx).toInt().coerceAtLeast(0)
                                                    scope.launch {
                                                        listState.scrollToItem(targetIndex, targetOffset)
                                                    }
                                                }

                                                // Reset drag state
                                                draggingItemIndex = null
                                                draggingItemOffset = 0f

                                                // IMPORTANT: Reset isDragging AFTER clearing indices to ensure
                                                // UI recomposes correctly without ghost overlays
                                                isDragging.value = false

                                                // Re-enable placement animation on next frame.
                                                scope.launch {
                                                    withFrameNanos { }
                                                    suppressPlacementAnimation = false
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            draggingItemIndex = null
                                            draggingItemOffset = 0f
                                            isDragging.value = false
                                            suppressPlacementAnimation = false
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            draggingItemOffset += dragAmount.y
                                        }
                                    )
                                }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RuleSetItem(
    ruleSet: RuleSet,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isDownloading: Boolean = false,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit = {},
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onOutboundClick: () -> Unit = {},
    onInboundClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.rulesets_delete_title),
            message = stringResource(R.string.rulesets_delete_confirm, ruleSet.tag),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                onDeleteClick()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    StandardCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { if (isSelectionMode) onClick() })
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = ruleSet.tag,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.settings_updating),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // 状态标签 - 绿色
                        Surface(
                            color = Color(0xFF2E7D32).copy(alpha = 0.8f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.common_ready),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // 出站标签 - 根据模式显示不同颜色
                    val outboundMode = ruleSet.outboundMode ?: RuleSetOutboundMode.DIRECT
                    val outboundText = stringResource(outboundMode.displayNameRes)
                    val outboundColor = when (outboundMode) {
                        RuleSetOutboundMode.DIRECT -> Color(0xFF1565C0) // 蓝色
                        RuleSetOutboundMode.BLOCK -> Color(0xFFC62828) // 红色
                        RuleSetOutboundMode.PROXY -> Color(0xFF7B1FA2) // 紫色
                        RuleSetOutboundMode.NODE -> Color(0xFFE65100) // 橙色
                        RuleSetOutboundMode.PROFILE -> Color(0xFF00838F) // 青色
                    }
                    Surface(
                        color = outboundColor.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = outboundText,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // 入站标签 - 黄色/橙色
                    val inbounds = ruleSet.inbounds ?: emptyList()
                    val inboundText = if (inbounds.isEmpty()) stringResource(R.string.common_all) else inbounds.joinToString(",")
                    Surface(
                        color = Color(0xFFFF8F00).copy(alpha = 0.8f), // 琥珀色
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = inboundText,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${stringResource(ruleSet.type.displayNameRes)} • ${ruleSet.format}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ruleSet.type == RuleSetType.REMOTE) {
                    Text(
                        text = ruleSet.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = ruleSet.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (!isSelectionMode) {
                if (defaultRuleSetTags.contains(ruleSet.tag)) {
                    Switch(
                        checked = ruleSet.enabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier
                            .scale(0.8f)
                            .padding(end = 8.dp)
                    )
                } else {
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "更多选项",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        MaterialTheme(
                            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(12.dp))
                        ) {
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .width(100.dp)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(stringResource(R.string.common_edit), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        onEditClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        showDeleteConfirm = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(stringResource(R.string.common_outbound), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        onOutboundClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(stringResource(R.string.common_inbound), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        onInboundClick()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RuleSetEditorDialog(
    initialRuleSet: RuleSet? = null,
    onDismiss: () -> Unit,
    onConfirm: (RuleSet) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var tag by remember { mutableStateOf(initialRuleSet?.tag ?: "") }
    var type by remember { mutableStateOf(initialRuleSet?.type ?: RuleSetType.REMOTE) }
    var format by remember { mutableStateOf(initialRuleSet?.format ?: "binary") }
    var url by remember { mutableStateOf(initialRuleSet?.url ?: "") }
    var path by remember { mutableStateOf(initialRuleSet?.path ?: "") }

    var showTypeDialog by remember { mutableStateOf(false) }
    var showFormatDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showTypeDialog) {
        val options = RuleSetType.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.rulesets_type),
            options = options,
            selectedIndex = RuleSetType.entries.indexOf(type),
            onSelect = { index ->
                type = RuleSetType.entries[index]
                showTypeDialog = false
            },
            onDismiss = { showTypeDialog = false }
        )
    }

    if (showFormatDialog) {
        val options = listOf("binary", "source")
        SingleSelectDialog(
            title = stringResource(R.string.rulesets_format),
            options = options,
            selectedIndex = options.indexOf(format).coerceAtLeast(0),
            onSelect = { index ->
                format = options[index]
                showFormatDialog = false
            },
            onDismiss = { showFormatDialog = false }
        )
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.rulesets_delete_title),
            message = stringResource(R.string.rulesets_delete_confirm, tag),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                onDelete?.invoke()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (initialRuleSet == null) stringResource(R.string.rulesets_add) else stringResource(R.string.rulesets_edit),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                StyledTextField(
                    label = "标识 (Tag)",
                    value = tag,
                    onValueChange = { tag = it },
                    placeholder = "geoip-cn"
                )

                ClickableDropdownField(
                    label = stringResource(R.string.rulesets_type),
                    value = stringResource(type.displayNameRes),
                    onClick = { showTypeDialog = true }
                )

                ClickableDropdownField(
                    label = stringResource(R.string.rulesets_format),
                    value = format,
                    onClick = { showFormatDialog = true }
                )

                if (type == RuleSetType.REMOTE) {
                    StyledTextField(
                        label = stringResource(R.string.rulesets_url),
                        value = url,
                        onValueChange = { url = it },
                        placeholder = "https://example.com/rules.srs"
                    )
                } else {
                    StyledTextField(
                        label = stringResource(R.string.rulesets_local_path),
                        value = path,
                        onValueChange = { path = it },
                        placeholder = "/path/to/rules.srs"
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newRuleSet = initialRuleSet?.copy(
                        tag = tag,
                        type = type,
                        format = format,
                        url = url,
                        path = path
                    ) ?: RuleSet(
                        tag = tag,
                        type = type,
                        format = format,
                        url = url,
                        path = path
                    )
                    onConfirm(newRuleSet)
                },
                enabled = tag.isNotBlank() && (if (type == RuleSetType.REMOTE) url.isNotBlank() else path.isNotBlank())
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            Row {
                if (initialRuleSet != null && onDelete != null) {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    )
}

@Composable
fun DefaultRuleSetProgressDialog(
    state: DefaultRuleSetDownloadState,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.rulesets_add_default),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = state.currentTag ?: stringResource(R.string.common_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${state.completed}/${state.total}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.completed.toFloat() / state.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
