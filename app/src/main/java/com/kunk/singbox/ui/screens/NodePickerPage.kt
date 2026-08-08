package com.kunk.singbox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ViewCompact
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kunk.singbox.R
import com.kunk.singbox.model.FilterMode
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.ui.components.FullScreenDialogPage
import com.kunk.singbox.ui.components.NodeCard
import com.kunk.singbox.ui.components.NodeFilterDialog
import com.kunk.singbox.ui.components.NodeGridCard
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.theme.LiquidGlassDropdownMenu
import com.kunk.singbox.ui.theme.LiquidGlassFilterChip
import com.kunk.singbox.ui.theme.liquidGlassDropdownMenuItemColors
import com.kunk.singbox.viewmodel.NodesViewModel

internal fun resolveNodePickerProfileId(
    availableProfileIds: List<String>,
    currentProfileId: String?,
    selectedNodeProfileId: String?,
    activeProfileId: String?
): String? {
    return currentProfileId?.takeIf(availableProfileIds::contains)
        ?: selectedNodeProfileId?.takeIf(availableProfileIds::contains)
        ?: activeProfileId?.takeIf(availableProfileIds::contains)
        ?: availableProfileIds.firstOrNull()
}

@Composable
@Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
internal fun NodePickerPage(
    title: String,
    profiles: List<ProfileUi>,
    allNodes: List<NodeUi>,
    displayedNodes: List<NodeUi>,
    selectedNodeId: String?,
    testingNodeIds: Set<String> = emptySet(),
    isAutoSelectionEnabled: Boolean = false,
    autoSelectionSubtitle: String = "",
    onSelectAuto: (() -> Unit)? = null,
    onSelectNone: (() -> Unit)? = null,
    onSelectNode: (NodeUi) -> Unit,
    onDismiss: () -> Unit,
    viewModel: NodesViewModel = viewModel()
) {
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val nodeFilter by viewModel.nodeFilter.collectAsStateWithLifecycle()
    val sortType by viewModel.sortType.collectAsStateWithLifecycle()
    val nodeColumnCount by viewModel.nodeColumnCount.collectAsStateWithLifecycle()

    val profileIds = remember(profiles, allNodes) {
        val idsWithNodes = allNodes.mapTo(linkedSetOf()) { it.sourceProfileId }
        profiles.mapNotNullTo(mutableListOf()) { profile ->
            profile.id.takeIf(idsWithNodes::contains)
        }.apply {
            addAll(idsWithNodes.filterNot(::contains).sorted())
        }
    }
    val profileNames = remember(profiles) { profiles.associate { it.id to it.name } }
    val profileNodeCounts = remember(allNodes) { allNodes.groupingBy { it.sourceProfileId }.eachCount() }
    val selectedNodeProfileId = remember(allNodes, selectedNodeId) {
        allNodes.firstOrNull { it.id == selectedNodeId }?.sourceProfileId
    }

    var currentProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(profileIds, selectedNodeProfileId, activeProfileId) {
        currentProfileId = resolveNodePickerProfileId(
            availableProfileIds = profileIds,
            currentProfileId = currentProfileId,
            selectedNodeProfileId = selectedNodeProfileId,
            activeProfileId = activeProfileId
        )
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }

    val profileNodes = remember(displayedNodes, currentProfileId) {
        displayedNodes.filter { it.sourceProfileId == currentProfileId }
    }
    val visibleNodes = remember(profileNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            profileNodes
        } else {
            profileNodes.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
        }
    }

    if (showFilterDialog) {
        NodeFilterDialog(
            currentFilter = nodeFilter,
            onConfirm = { filter ->
                viewModel.setNodeFilter(filter)
                showFilterDialog = false
            },
            onDismiss = { showFilterDialog = false }
        )
    }

    if (showSortDialog) {
        val sortOptions = listOf(
            stringResource(R.string.nodes_sort_default) to NodeSortType.DEFAULT,
            stringResource(R.string.nodes_sort_latency) to NodeSortType.LATENCY,
            stringResource(R.string.nodes_sort_name) to NodeSortType.NAME
        )
        SingleSelectDialog(
            title = stringResource(R.string.nodes_sort),
            options = sortOptions.map { it.first },
            selectedIndex = sortOptions.indexOfFirst { it.second == sortType }.coerceAtLeast(0),
            onSelect = { index ->
                viewModel.setSortType(sortOptions[index].second)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false }
        )
    }

    val supportingContentHeight = if (profileIds.isEmpty()) 48.dp else 92.dp
    FullScreenDialogPage(
        title = title,
        onDismiss = onDismiss,
        actions = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize(Alignment.TopEnd)
            ) {
                IconButton(
                    onClick = { showMoreMenu = true },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.common_menu)
                    )
                }
                LiquidGlassDropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    modifier = Modifier.width(220.dp)
                ) {
                    val layoutIcon = when (nodeColumnCount) {
                        1 -> Icons.AutoMirrored.Rounded.ViewList
                        2 -> Icons.Rounded.GridView
                        else -> Icons.Rounded.ViewCompact
                    }
                    DropdownMenuItem(
                        leadingIcon = { Icon(layoutIcon, contentDescription = null) },
                        text = { Text(stringResource(R.string.common_layout)) },
                        onClick = {
                            showMoreMenu = false
                            viewModel.setNodeColumnCount(
                                when (nodeColumnCount) {
                                    1 -> 2
                                    2 -> 3
                                    else -> 1
                                }
                            )
                        },
                        colors = liquidGlassDropdownMenuItemColors()
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.FilterAlt,
                                contentDescription = null,
                                tint = if (nodeFilter.filterMode == FilterMode.NONE) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        },
                        text = { Text(stringResource(R.string.nodes_filter)) },
                        onClick = {
                            showMoreMenu = false
                            showFilterDialog = true
                        },
                        colors = liquidGlassDropdownMenuItemColors()
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null) },
                        text = { Text(stringResource(R.string.nodes_sort)) },
                        onClick = {
                            showMoreMenu = false
                            showSortDialog = true
                        },
                        colors = liquidGlassDropdownMenuItemColors()
                    )
                }
            }
        },
        supportingContentHeight = supportingContentHeight,
        supportingContent = {
            Column(modifier = Modifier.fillMaxSize()) {
                NodeSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    isExpanded = isSearchExpanded,
                    onToggle = { isSearchExpanded = !isSearchExpanded },
                    totalCount = profileNodes.size,
                    filteredCount = visibleNodes.size,
                    activeNodeName = allNodes.firstOrNull { it.id == selectedNodeId }?.displayName,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                if (profileIds.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(profileIds, key = { it }) { profileId ->
                            val profileName = profileNames[profileId]
                                ?: stringResource(R.string.rulesets_unknown_profile, profileId)
                            LiquidGlassFilterChip(
                                selected = currentProfileId == profileId,
                                onClick = {
                                    currentProfileId = profileId
                                    searchQuery = ""
                                },
                                label = {
                                    Text(
                                        text = "$profileName · ${profileNodeCounts[profileId] ?: 0}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (currentProfileId == profileId) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { contentTopPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(nodeColumnCount),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = contentTopPadding + 12.dp,
                end = 16.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onSelectNone != null) {
                item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                    NodePickerSpecialCard(
                        name = stringResource(R.string.common_none),
                        subtitle = stringResource(R.string.node_picker_no_detour),
                        isSelected = !isAutoSelectionEnabled && selectedNodeId == null,
                        onClick = {
                            onSelectNone()
                            onDismiss()
                        }
                    )
                }
            }
            if (onSelectAuto != null) {
                item(key = "automatic-selection", span = { GridItemSpan(maxLineSpan) }) {
                    NodePickerSpecialCard(
                        name = stringResource(R.string.nodes_auto_selection),
                        subtitle = autoSelectionSubtitle,
                        isSelected = isAutoSelectionEnabled,
                        onClick = {
                            onSelectAuto()
                            onDismiss()
                        }
                    )
                }
            }
            if (visibleNodes.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_no_nodes_available),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(visibleNodes, key = { it.id }) { node ->
                    val isSelected = !isAutoSelectionEnabled && node.id == selectedNodeId
                    val onClick = {
                        onSelectNode(node)
                        onDismiss()
                    }
                    if (nodeColumnCount == 1) {
                        NodeCard(
                            name = node.displayName,
                            type = node.protocolDisplay,
                            latency = node.latencyMs,
                            isSelected = isSelected,
                            isTesting = node.id in testingNodeIds,
                            onClick = onClick,
                            onEdit = {},
                            onExport = {},
                            onLatency = {},
                            onDelete = {},
                            showActions = false
                        )
                    } else {
                        NodeGridCard(
                            name = node.displayName,
                            type = node.protocolDisplay,
                            latency = node.latencyMs,
                            isSelected = isSelected,
                            isTesting = node.id in testingNodeIds,
                            onClick = onClick,
                            onEdit = {},
                            onExport = {},
                            onLatency = {},
                            onDelete = {},
                            showActions = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodePickerSpecialCard(
    name: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    NodeCard(
        name = name,
        type = subtitle,
        isSelected = isSelected,
        onClick = onClick,
        onEdit = {},
        onExport = {},
        onLatency = {},
        onDelete = {},
        showLatency = false,
        showActions = false
    )
}
