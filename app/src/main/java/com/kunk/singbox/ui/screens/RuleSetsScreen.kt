package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.ProfileNodeSelectDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.rememberLocalNetworkPermissionRequest
import com.kunk.singbox.ui.navigation.Screen
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassCheckboxColors
import com.kunk.singbox.ui.theme.liquidGlassDialogContainerColor
import com.kunk.singbox.ui.theme.liquidGlassDialogPanel
import com.kunk.singbox.ui.theme.liquidGlassEmptyStatePanel
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassPressFeedback
import com.kunk.singbox.ui.theme.liquidGlassTextButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassTextButtonColors
import com.kunk.singbox.ui.theme.liquidGlassTextButtonPanel
import kotlinx.coroutines.launch
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarColors

internal val defaultRuleSetTags = setOf(
    "geosite-cn",
    "geoip-cn",
    "geosite-geolocation-!cn",
    "geosite-category-ads-all",
    "geosite-private"
)

@Composable
private fun Modifier.ruleSetInboundOptionPanel(isSelected: Boolean): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(
            shape = RoundedCornerShape(10.dp),
            selected = isSelected,
            shadowElevation = 4.dp
        )
    } else {
        this
    }
}

@Composable
private fun Modifier.ruleSetSortItemPressFeedback(
    enabled: Boolean,
    onClick: () -> Unit
): Modifier {
    val useLiquidGlass = isLiquidGlassTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (useLiquidGlass && enabled && isPressed) 0.98f else 1f,
        animationSpec = spring(stiffness = 520f, dampingRatio = 0.72f),
        label = "liquid_glass_rule_set_sort_item_scale"
    )
    val clickModifier = if (useLiquidGlass) {
        Modifier.clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier.clickable(
            enabled = enabled,
            onClick = onClick
        )
    }

    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.then(clickModifier)
}

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
    val scope = rememberCoroutineScope()
    val requestLocalNetworkPermission = rememberLocalNetworkPermissionRequest()

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
    var draggingItemId by remember { mutableStateOf<String?>(null) }
    var settlingItemId by remember { mutableStateOf<String?>(null) }
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
        val currentRuleSet = checkNotNull(editingRuleSet)
        RuleSetEditorDialog(
            initialRuleSet = currentRuleSet,
            onDismiss = { editingRuleSet = null },
            onConfirm = { ruleSet ->
                settingsViewModel.updateRuleSet(ruleSet)
                editingRuleSet = null
            },
            onDelete = {
                settingsViewModel.deleteRuleSet(currentRuleSet.id)
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
                AppNotificationManager.showMessage(navController.context, profilesDeletedMsg)
                showDeleteConfirmDialog = false
                exitSelectionMode()
            },
            onDismiss = { showDeleteConfirmDialog = false }
        )
    }

    // Outbound Mode Dialog
    if (showOutboundModeDialog && outboundEditingRuleSet != null) {
        val currentRuleSet = checkNotNull(outboundEditingRuleSet)
        val options = RuleSetOutboundMode.entries.map { stringResource(it.displayNameRes) }
        val currentMode = currentRuleSet.outboundMode ?: RuleSetOutboundMode.DIRECT
        SingleSelectDialog(
            title = stringResource(R.string.rulesets_select_outbound),
            options = options,
            selectedIndex = RuleSetOutboundMode.entries.indexOf(currentMode),
            onSelect = { index ->
                val selectedMode = RuleSetOutboundMode.entries[index]
                val updatedRuleSet = currentRuleSet.copy(
                    outboundMode = selectedMode,
                    outboundValue = null
                )

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
                } else if (selectedMode == RuleSetOutboundMode.DIRECT) {
                    requestLocalNetworkPermission {
                        settingsViewModel.updateRuleSet(updatedRuleSet)
                    }
                    outboundEditingRuleSet = null
                    showOutboundModeDialog = false
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
        val currentRuleSet = checkNotNull(outboundEditingRuleSet)
        val currentValue = currentRuleSet.outboundValue
        val currentRef = resolveNodeByStoredValue(currentValue)?.let { toNodeRef(it) } ?: currentValue
        val selectedIndex = targetOptions.indexOfFirst { it.second == currentRef }
        SingleSelectDialog(
            title = targetSelectionTitle,
            options = targetOptions.map { it.first },
            selectedIndex = selectedIndex.coerceAtLeast(0),
            onSelect = { index ->
                val selectedValue = targetOptions.getOrNull(index)?.second ?: return@SingleSelectDialog
                val updatedRuleSet = currentRuleSet.copy(outboundValue = selectedValue)
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
        val currentRuleSet = checkNotNull(outboundEditingRuleSet)
        val currentValue = currentRuleSet.outboundValue
        val currentRef = resolveNodeByStoredValue(currentValue)?.let { toNodeRef(it) } ?: currentValue
        ProfileNodeSelectDialog(
            title = stringResource(R.string.rulesets_select_node),
            profiles = profiles,
            nodesForSelection = selectionNodes,
            selectedNodeRef = currentRef,
            onSelect = { ref ->
                val updatedRuleSet = currentRuleSet.copy(outboundValue = ref)
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
        val currentRuleSet = checkNotNull(outboundEditingRuleSet)
        AlertDialog(
            modifier = Modifier.liquidGlassDialogPanel(RoundedCornerShape(24.dp)),
            onDismissRequest = {
                showInboundDialog = false
                outboundEditingRuleSet = null
            },
            containerColor = liquidGlassDialogContainerColor(),
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.rulesets_select_inbound), color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    availableInbounds.forEach { inbound ->
                        val ruleSet = outboundEditingRuleSet ?: currentRuleSet
                        val inboundList = ruleSet.inbounds ?: emptyList()
                        val isSelected = inboundList.contains(inbound)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .ruleSetInboundOptionPanel(isSelected)
                                .liquidGlassPressFeedback(
                                    label = "liquid_glass_rule_set_inbound_option_scale"
                                ) {
                                    val currentInbounds = inboundList.toMutableList()
                                    if (currentInbounds.contains(inbound)) {
                                        currentInbounds.remove(inbound)
                                    } else {
                                        currentInbounds.add(inbound)
                                    }
                                    outboundEditingRuleSet = ruleSet.copy(inbounds = currentInbounds)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null,
                                colors = liquidGlassCheckboxColors()
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = inbound, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.liquidGlassTextButtonPanel(),
                    colors = liquidGlassTextButtonColors(
                        contentColor = liquidGlassTextButtonContentColor(MaterialTheme.colorScheme.primary)
                    ),
                    onClick = {
                        val ruleSet = outboundEditingRuleSet ?: currentRuleSet
                        settingsViewModel.updateRuleSet(ruleSet)
                        showInboundDialog = false
                        outboundEditingRuleSet = null
                    }
                ) {
                    Text(stringResource(R.string.common_ok))
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.liquidGlassTextButtonPanel(),
                    colors = liquidGlassTextButtonColors(
                        contentColor = liquidGlassTextButtonContentColor(
                            defaultColor = MaterialTheme.colorScheme.primary,
                            liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ),
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background),
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(
                            stringResource(R.string.rulesets_selection_mode, selectedItems.count { it.value }),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    } else {
                        Text(stringResource(R.string.rulesets_title), color = MaterialTheme.colorScheme.onBackground)
                    }
                },
                navigationIcon = {
                    IconButton(
                        modifier = Modifier.liquidGlassIconButtonPanel(selected = isSelectionMode),
                        onClick = {
                            if (isSelectionMode) {
                                exitSelectionMode()
                            } else {
                                navController.popBackStack()
                            }
                        }
                    ) {
                        Icon(
                            if (isSelectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = if (isSelectionMode) "Close" else "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (isSelectionMode) {
                            val selectedCount = selectedItems.count { it.value }
                            IconButton(
                                modifier = Modifier.liquidGlassIconButtonPanel(
                                    selected = selectedCount > 0,
                                    enabled = selectedCount > 0
                                ),
                                onClick = { showDeleteConfirmDialog = true },
                                enabled = selectedCount > 0
                            ) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = "Delete",
                                    tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            IconButton(
                                modifier = Modifier.liquidGlassIconButtonPanel(),
                                onClick = { navController.navigate(Screen.RuleSetHub.route) }
                            ) {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = "Download", tint = MaterialTheme.colorScheme.onBackground)
                            }
                            IconButton(
                                modifier = Modifier.liquidGlassIconButtonPanel(),
                                onClick = { showAddDialog = true }
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.onBackground)
                            }
                        }
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
            state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (ruleSets.isEmpty() && !defaultRuleSetDownloadState.isActive) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlassEmptyStatePanel()
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
            }
            items(ruleSets.size, key = { ruleSets[it].id }) { index ->
                val ruleSet = ruleSets[index]
                val isDraggingItem = draggingItemIndex == index
                val isSettlingItem = settlingItemId == ruleSet.id
                val isCurrentlyDragging = isDragging.value
                val currentDraggingIndex = draggingItemIndex
                val currentDragOffset = draggingItemOffset

                var targetTranslationY = 0f
                var zIndex = 0f
                val canDisplace = !isSelectionMode &&
                    currentDraggingIndex != null &&
                    itemHeightPx > 0f &&
                    !isDraggingItem
                if (currentDraggingIndex != null && itemHeightPx > 0f) {
                    if (isDraggingItem) {
                        targetTranslationY = currentDragOffset
                        zIndex = 1f
                    } else if (canDisplace) {
                        val dragProgress = currentDragOffset / itemHeightPx
                        val rawEndProgress = when {
                            dragProgress > 0f -> kotlin.math.ceil(dragProgress)
                            dragProgress < 0f -> kotlin.math.floor(dragProgress)
                            else -> 0.0
                        }
                        val clampedStart = currentDraggingIndex.coerceIn(0, ruleSets.lastIndex)
                        val clampedEnd = (currentDraggingIndex + rawEndProgress.toInt()).coerceIn(0, ruleSets.lastIndex)

                        when {
                            clampedStart < clampedEnd && index > clampedStart && index <= clampedEnd -> {
                                val itemSlotOffset = index - currentDraggingIndex
                                targetTranslationY = -(dragProgress - (itemSlotOffset - 1)) * itemHeightPx
                                targetTranslationY = targetTranslationY.coerceIn(-itemHeightPx, 0f)
                            }
                            clampedStart > clampedEnd && index < clampedStart && index >= clampedEnd -> {
                                val itemSlotOffset = currentDraggingIndex - index
                                targetTranslationY = (-dragProgress - (itemSlotOffset - 1)) * itemHeightPx
                                targetTranslationY = targetTranslationY.coerceIn(0f, itemHeightPx)
                            }
                        }
                    }
                }

                val dragScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = when {
                        isDraggingItem && isCurrentlyDragging -> 1.02f
                        isSettlingItem -> 1.01f
                        else -> 1f
                    },
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 260f),
                    label = "dragScale"
                )
                val dragShadow by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = when {
                        isDraggingItem && isCurrentlyDragging -> 8f
                        isSettlingItem -> 4f
                        else -> 0f
                    },
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 260f),
                    label = "dragShadow"
                )
                val dragAlpha by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = when {
                        isDraggingItem && isCurrentlyDragging -> 0.94f
                        isSettlingItem -> 0.98f
                        else -> 1f
                    },
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 280f),
                    label = "dragAlpha"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(zIndex)
                        .onGloballyPositioned { coordinates ->
                            if (itemHeightPx == 0f) {
                                val spacingPx = with(density) { 16.dp.toPx() }
                                itemHeightPx = coordinates.size.height.toFloat() + spacingPx
                            }
                        }
                        .graphicsLayer {
                            this.translationY = targetTranslationY
                            scaleX = dragScale
                            scaleY = dragScale
                            shadowElevation = dragShadow
                            alpha = dragAlpha
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        .then(
                            if (!enablePlacementAnimation || suppressPlacementAnimation) {
                                Modifier
                            } else {
                                Modifier.animateItem()
                            }
                        )
                        .ruleSetSortItemPressFeedback(
                            enabled = !isDraggingItem || !isCurrentlyDragging
                        ) {
                            if (isSelectionMode) {
                                toggleSelection(ruleSet.id)
                            }
                        }
                        .pointerInput(index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    if (!isSelectionMode) {
                                        draggingItemIndex = index
                                        draggingItemId = ruleSet.id
                                        draggingItemOffset = 0f
                                        isDragging.value = true
                                        haptic.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                        )
                                    }
                                },
                                onDragEnd = {
                                    draggingItemIndex?.let { startIdx ->
                                        val dist = if (itemHeightPx > 0f) {
                                            val progress = draggingItemOffset / itemHeightPx
                                            when {
                                                progress > 0f -> kotlin.math.ceil(progress).toInt()
                                                progress < 0f -> kotlin.math.floor(progress).toInt()
                                                else -> 0
                                            }
                                        } else {
                                            0
                                        }
                                        val endIdx = (startIdx + dist).coerceIn(0, ruleSets.lastIndex)

                                        val settledRuleSetId = ruleSet.id
                                        settlingItemId = settledRuleSetId
                                        suppressPlacementAnimation = true

                                        if (startIdx != endIdx) {
                                            val item = ruleSets.removeAt(startIdx)
                                            ruleSets.add(endIdx, item)
                                            settingsViewModel.reorderRuleSets(ruleSets.toList())
                                        }

                                        draggingItemIndex = null
                                        draggingItemId = null
                                        draggingItemOffset = 0f
                                        isDragging.value = false

                                        scope.launch {
                                            androidx.compose.runtime.withFrameNanos { }
                                            suppressPlacementAnimation = false
                                        }
                                        scope.launch {
                                            kotlinx.coroutines.delay(220)
                                            if (settlingItemId == settledRuleSetId) {
                                                settlingItemId = null
                                            }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    draggingItemIndex = null
                                    draggingItemId = null
                                    draggingItemOffset = 0f
                                    settlingItemId = null
                                    isDragging.value = false
                                    suppressPlacementAnimation = false
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    draggingItemOffset += dragAmount.y
                                }
                            )
                        }
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
                            if (enabled && ruleSet.outboundMode == RuleSetOutboundMode.DIRECT) {
                                requestLocalNetworkPermission {
                                    settingsViewModel.updateRuleSet(ruleSet.copy(enabled = true))
                                }
                            } else {
                                settingsViewModel.updateRuleSet(ruleSet.copy(enabled = enabled))
                            }
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
                        }
                    )
                }
            }
        }
    }
}
