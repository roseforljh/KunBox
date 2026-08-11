package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewCompact
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.FilterMode
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.ExpandableSearchBar
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.FloatingMainPageLayout
import com.kunk.singbox.ui.components.NodeFilterDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.NodeCard
import com.kunk.singbox.ui.components.NodeGridCard
import com.kunk.singbox.ui.components.TopOnlySupportingContent
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ui.theme.LiquidGlassDropdownMenu
import com.kunk.singbox.ui.theme.LiquidGlassFloatingActionButton
import com.kunk.singbox.ui.theme.LiquidGlassSmallFloatingActionButton
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassFloatingActionContainerColor
import com.kunk.singbox.ui.theme.liquidGlassFloatingActionContentColor
import com.kunk.singbox.ui.theme.liquidGlassDropdownMenuItemColors
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassProgressColor
import com.kunk.singbox.ui.theme.liquidGlassProgressTrackColor
import com.kunk.singbox.ui.theme.liquidGlassMutedContentColor
import com.kunk.singbox.ui.theme.liquidGlassScreenContainerColor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
private fun Modifier.nodesMenuPanel(shape: RoundedCornerShape = RoundedCornerShape(12.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        this // 交给 LiquidGlassDropdownMenu 去绘制玻璃面板
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = shape
            )
    }
}

@Composable
internal fun Modifier.nodeActiveIndicatorPanel(): Modifier {
    return if (isLiquidGlassTheme()) {
        size(10.dp)
            .liquidGlassPanel(shape = RoundedCornerShape(5.dp), selected = true, shadowElevation = 3.dp)
    } else {
        size(6.dp)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(3.dp)
            )
    }
}

@Composable
private fun Modifier.nodeTestingProgressPanel(): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp)
            .padding(12.dp)
    } else {
        this
    }
}

@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodesScreen(
    navController: NavController,
    viewModel: NodesViewModel = viewModel(),
    bottomContentPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val showTopControls by remember {
        derivedStateOf { !gridState.canScrollBackward }
    }

    var isFabVisible by remember { mutableStateOf(true) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -10f) {
                    isFabVisible = false
                } else if (available.y > 10f) {
                    isFabVisible = true
                }
                return Offset.Zero
            }
        }
    }

    var lastY by remember { mutableFloatStateOf(0f) }

    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val activeNodeId by viewModel.activeNodeId.collectAsStateWithLifecycle()
    val isAutoSelectionEnabled by viewModel.isAutoSelectionEnabled.collectAsStateWithLifecycle()
    val switchingNodeId by viewModel.switchingNodeId.collectAsStateWithLifecycle()
    val runtimeNodeLabel by SingBoxRemote.activeLabel.collectAsStateWithLifecycle()
    val testingNodeIds by viewModel.testingNodeIds.collectAsStateWithLifecycle()
    val nodeFilter by viewModel.nodeFilter.collectAsStateWithLifecycle()
    val sortType by viewModel.sortType.collectAsStateWithLifecycle()
    val testProgress by viewModel.testProgress.collectAsStateWithLifecycle()
    val nodeColumnCount by viewModel.nodeColumnCount.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()

    LaunchedEffect(showTopControls) {
        if (!showTopControls) {
            isSearchExpanded = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collectLatest { message ->
            AppNotificationManager.showMessage(context, message)
        }
    }

    val filteredNodes by remember {
        androidx.compose.runtime.derivedStateOf {
            if (searchQuery.isBlank()) {
                nodes
            } else {
                nodes.filter { node ->
                    node.displayName.contains(searchQuery, ignoreCase = true)
                }
            }
        }
    }

    var showSortDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var exportLink by remember { mutableStateOf<String?>(null) }
    var isFabExpanded by remember { mutableStateOf(false) }
    var nodeToDelete by remember { mutableStateOf<NodeUi?>(null) }
    var showClearLatencyConfirm by remember { mutableStateOf(false) }

    val pendingNodeDelete = nodeToDelete
    if (pendingNodeDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.common_delete),
            message = stringResource(R.string.common_delete_confirm, pendingNodeDelete.displayName),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteNode(pendingNodeDelete.id)
                nodeToDelete = null
            },
            onDismiss = { nodeToDelete = null }
        )
    }

    if (showClearLatencyConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.nodes_clear_latency),
            message = stringResource(R.string.nodes_clear_latency_confirm),
            confirmText = stringResource(R.string.common_clear),
            isDestructive = true,
            onConfirm = {
                viewModel.clearLatency()
                showClearLatencyConfirm = false
            },
            onDismiss = { showClearLatencyConfirm = false }
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
            selectedIndex = sortOptions.indexOfFirst { it.second == sortType },
            onSelect = { index ->
                viewModel.setSortType(sortOptions[index].second)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false }
        )
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

    if (exportLink != null) {
        val exportValue = checkNotNull(exportLink)
        val copiedMsg = stringResource(R.string.nodes_copied_to_clipboard)
        InputDialog(
            title = stringResource(R.string.nodes_export_link),
            initialValue = exportValue,
            confirmText = stringResource(R.string.common_copy),
            onConfirm = {
                clipboardManager.setText(AnnotatedString(it))
                AppNotificationManager.showMessage(context, copiedMsg)
                exportLink = null
            },
            onDismiss = { exportLink = null }
        )
    }

    @Composable
    fun NodeActionButtons(modifier: Modifier = Modifier) {
        val fabContainerColor = liquidGlassFloatingActionContainerColor(MaterialTheme.colorScheme.primary)
        val fabContentColor = liquidGlassFloatingActionContentColor(MaterialTheme.colorScheme.onPrimary)

        val fabAlpha by animateFloatAsState(
            targetValue = if (isFabVisible) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "fabAlpha"
        )

        if (fabAlpha > 0f) {
            Column(
                modifier = modifier
                    .graphicsLayer {
                        alpha = fabAlpha
                        translationY = (1f - fabAlpha) * 15.dp.toPx()
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                horizontalAlignment = Alignment.End
            ) {
                AnimatedVisibility(
                    visible = isFabExpanded,
                    enter = fadeIn(animationSpec = tween(durationMillis = 120)) +
                        slideInVertically(initialOffsetY = { it / 5 }) +
                        scaleIn(initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 1f)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 90)) +
                        slideOutVertically(targetOffsetY = { it / 5 }) +
                        scaleOut(targetScale = 0.88f, transformOrigin = TransformOrigin(1f, 1f))
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                    ) {
                        // Clear Latency
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.nodes_clear_latency),
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            LiquidGlassSmallFloatingActionButton(
                                onClick = {
                                    showClearLatencyConfirm = true
                                    isFabExpanded = false
                                },
                                containerColor = fabContainerColor,
                                contentColor = fabContentColor
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.nodes_clear_latency))
                            }
                        }

                        // Add Node
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.nodes_add),
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            LiquidGlassSmallFloatingActionButton(
                                onClick = {
                                    navController.navigate(Screen.NodeAdd.route)
                                    isFabExpanded = false
                                },
                                containerColor = fabContainerColor,
                                contentColor = fabContentColor
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.nodes_add))
                            }
                        }

                        // Manual Create Node
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.nodes_manual_create),
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            LiquidGlassSmallFloatingActionButton(
                                onClick = {
                                    navController.navigate(Screen.NodeProtocolSelect.route)
                                    isFabExpanded = false
                                },
                                containerColor = fabContainerColor,
                                contentColor = fabContentColor
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.nodes_manual_create))
                            }
                        }

                        // Test Latency
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isTesting) stringResource(R.string.nodes_stop_test) else stringResource(R.string.nodes_test_latency),
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            LiquidGlassSmallFloatingActionButton(
                                onClick = {
                                    viewModel.testAllLatency()
                                    isFabExpanded = false
                                },
                                containerColor = fabContainerColor,
                                contentColor = fabContentColor
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = liquidGlassProgressColor(fabContentColor),
                                        strokeWidth = 2.dp,
                                        trackColor = liquidGlassProgressTrackColor(Color.Transparent)
                                    )
                                } else {
                                    Icon(Icons.Rounded.Bolt, contentDescription = stringResource(R.string.nodes_test_latency))
                                }
                            }
                        }
                    }
                }

                LiquidGlassFloatingActionButton(
                    onClick = { isFabExpanded = !isFabExpanded },
                    containerColor = fabContainerColor,
                    contentColor = fabContentColor
                ) {
                    Icon(
                        imageVector = if (isFabExpanded) Icons.Rounded.Close else Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.common_menu)
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = liquidGlassScreenContainerColor(MaterialTheme.colorScheme.background),
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { padding ->
            FloatingMainPageLayout(
                title = stringResource(R.string.nodes_title),
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(pass = PointerEventPass.Initial)
                            lastY = down.position.y
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val currentY = event.changes.firstOrNull()?.position?.y ?: lastY
                                val deltaY = currentY - lastY
                                if (deltaY < -30f) {
                                    isFabVisible = false
                                } else if (deltaY > 30f) {
                                    isFabVisible = true
                                }
                                lastY = currentY
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .nestedScroll(nestedScrollConnection)
                    .padding(bottom = padding.calculateBottomPadding()),
                actions = {
                    val activeIndex = remember(filteredNodes, activeNodeId) {
                        filteredNodes.indexOfFirst { it.id == activeNodeId }
                    }

                    val layoutIcon = when (nodeColumnCount) {
                        1 -> Icons.Rounded.GridView
                        2 -> Icons.Rounded.ViewCompact
                        else -> Icons.Rounded.ViewList
                    }
                    IconButton(
                        modifier = Modifier.liquidGlassIconButtonPanel(),
                        onClick = {
                            val nextCount = when (nodeColumnCount) {
                                1 -> 2
                                2 -> 3
                                else -> 1
                            }
                            viewModel.setNodeColumnCount(nextCount)
                        }
                    ) {
                        Icon(
                            imageVector = layoutIcon,
                            contentDescription = "Switch layout",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                        IconButton(
                            modifier = Modifier.liquidGlassIconButtonPanel(),
                            onClick = { showMoreMenu = true }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        MaterialTheme(
                            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(12.dp))
                        ) {
                            LiquidGlassDropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                                modifier = Modifier.nodesMenuPanel()
                            ) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.MyLocation,
                                            contentDescription = null,
                                            tint = if (activeIndex >= 0) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            }
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = "定位当前节点",
                                            color = if (activeIndex >= 0) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            }
                                        )
                                    },
                                    enabled = activeIndex >= 0,
                                    onClick = {
                                        showMoreMenu = false
                                        if (activeIndex >= 0) {
                                            scope.launch {
                                                gridState.animateScrollToItem(activeIndex + 2)
                                            }
                                        }
                                    },
                                    colors = liquidGlassDropdownMenuItemColors()
                                )

                                DropdownMenuItem(
                                    leadingIcon = {
                                        val hasFilter = nodeFilter.filterMode != FilterMode.NONE
                                        Icon(
                                            imageVector = Icons.Rounded.FilterAlt,
                                            contentDescription = null,
                                            tint = if (hasFilter) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = stringResource(R.string.nodes_filter),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        showFilterDialog = true
                                    },
                                    colors = liquidGlassDropdownMenuItemColors()
                                )

                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Sort,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = stringResource(R.string.nodes_sort),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        showSortDialog = true
                                    },
                                    colors = liquidGlassDropdownMenuItemColors()
                                )
                            }
                        }
                    }
                },
                supportingContentHeight = 48.dp,
                supportingContent = {
                    TopOnlySupportingContent(visible = showTopControls) {
                        NodeSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            isExpanded = isSearchExpanded,
                            onToggle = { isSearchExpanded = !isSearchExpanded },
                            totalCount = nodes.size,
                            filteredCount = filteredNodes.size,
                            activeNodeName = nodes.find { it.id == activeNodeId }?.displayName,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            ) { contentTopPadding ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(nodeColumnCount),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = 16.dp + bottomContentPadding,
                        top = contentTopPadding + 12.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(
                        key = "testing-progress",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        AnimatedVisibility(
                            visible = testProgress != null,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            testProgress?.let { (completed, total) ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .nodeTestingProgressPanel()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.nodes_testing_progress),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "$completed / $total",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { if (total > 0) completed.toFloat() / total else 0f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp),
                                        color = liquidGlassProgressColor(MaterialTheme.colorScheme.primary),
                                        trackColor = liquidGlassProgressTrackColor(
                                            MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    }
                    item(key = "automatic-selection", contentType = "node") {
                        val subtitle = when {
                            !isAutoSelectionEnabled -> stringResource(R.string.nodes_auto_selection_disabled)
                            runtimeNodeLabel.isNotBlank() -> stringResource(
                                R.string.nodes_auto_selection_current,
                                runtimeNodeLabel
                            )
                            else -> stringResource(R.string.nodes_auto_selection_selecting)
                        }
                        Box(
                            modifier = Modifier.animateItem(
                                placementSpec = spring(
                                    stiffness = 500f,
                                    dampingRatio = 0.85f
                                )
                            )
                        ) {
                            if (nodeColumnCount == 1) {
                                NodeCard(
                                    name = stringResource(R.string.nodes_auto_selection),
                                    type = subtitle,
                                    isSelected = isAutoSelectionEnabled,
                                    onClick = viewModel::enableAutoSelection,
                                    onEdit = {},
                                    onExport = {},
                                    onLatency = {},
                                    onDelete = {},
                                    showLatency = false,
                                    showActions = false
                                )
                            } else {
                                NodeGridCard(
                                    name = stringResource(R.string.nodes_auto_selection),
                                    type = subtitle,
                                    isSelected = isAutoSelectionEnabled,
                                    onClick = viewModel::enableAutoSelection,
                                    onEdit = {},
                                    onExport = {},
                                    onLatency = {},
                                    onDelete = {},
                                    showLatency = false,
                                    showActions = false
                                )
                            }
                        }
                    }
                    itemsIndexed(
                        items = filteredNodes,
                        key = { _, node -> node.id },
                        contentType = { _, _ -> "node" }
                    ) { index, node ->
                        val isSelected = !isAutoSelectionEnabled && activeNodeId == node.id
                        val isTestingNode = testingNodeIds.contains(node.id)
                        val isSwitchingNode = switchingNodeId == node.id

                        val onNodeClick = remember(node.id) { { viewModel.setActiveNode(node.id) } }
                        val onEdit = remember(node.id) {
                            { navController.navigate(Screen.NodeDetail.createRoute(node.id)) }
                        }
                        val onExport = remember(node.id) {
                            {
                                scope.launch {
                                    val link = viewModel.exportNode(node.id)
                                    if (link != null) {
                                        exportLink = link
                                    }
                                }
                                Unit
                            }
                        }
                        val onLatency = remember(node.id) { { viewModel.testLatency(node.id) } }
                        val onDelete = remember(node) { { nodeToDelete = node } }

                        // Scroll-triggered animation for all items
                        var visible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            visible = true
                        }

                        val alpha by animateFloatAsState(
                            targetValue = if (visible) 1f else 0f,
                            animationSpec = tween(durationMillis = 300),
                            label = "alpha"
                        )
                        val translateY by animateFloatAsState(
                            targetValue = if (visible) 0f else 50f,
                            animationSpec = tween(durationMillis = 300),
                            label = "translateY"
                        )

                        // animateItem 包住整张卡，避免选中边框与卡片位移动画脱节
                        Box(
                            modifier = Modifier.animateItem(
                                placementSpec = spring(
                                    stiffness = 500f,
                                    dampingRatio = 0.85f
                                )
                            )
                        ) {
                            val cardModifier = Modifier.graphicsLayer {
                                this.alpha = alpha
                                this.translationY = translateY
                                this.compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                            if (nodeColumnCount == 1) {
                                NodeCard(
                                    name = node.displayName,
                                    type = node.protocolDisplay,
                                    latency = node.latencyMs,
                                    isSelected = isSelected,
                                    hasDetour = node.hasDetour,
                                    isSwitching = isSwitchingNode,
                                    isTesting = isTestingNode,
                                    onClick = onNodeClick,
                                    onEdit = onEdit,
                                    onExport = onExport,
                                    onLatency = onLatency,
                                    onDelete = onDelete,
                                    modifier = cardModifier
                                )
                            } else {
                                NodeGridCard(
                                    name = node.displayName,
                                    type = node.protocolDisplay,
                                    latency = node.latencyMs,
                                    isSelected = isSelected,
                                    hasDetour = node.hasDetour,
                                    isSwitching = isSwitchingNode,
                                    isTesting = isTestingNode,
                                    onClick = onNodeClick,
                                    onEdit = onEdit,
                                    onExport = onExport,
                                    onLatency = onLatency,
                                    onDelete = onDelete,
                                    modifier = cardModifier
                                )
                            }
                        }
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = isFabExpanded,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val scrimColor = if (isDark) {
                Color.Black.copy(alpha = 0.65f)
            } else {
                Color.White.copy(alpha = 0.78f)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { isFabExpanded = false })
                    }
            )
        }

        NodeActionButtons(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp + bottomContentPadding)
        )
    }
}

@Suppress("FunctionNaming", "LongMethod", "CognitiveComplexMethod")
@Composable
internal fun NodeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    totalCount: Int,
    filteredCount: Int,
    activeNodeName: String?,
    modifier: Modifier = Modifier
) {
    ExpandableSearchBar(
        query = query,
        onQueryChange = onQueryChange,
        isExpanded = isExpanded,
        onToggle = onToggle,
        placeholder = stringResource(R.string.common_search),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = if (filteredCount != totalCount) {
                    "$filteredCount / $totalCount ${stringResource(R.string.nodes_count_suffix)}"
                } else {
                    "$totalCount ${stringResource(R.string.nodes_count_suffix)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = liquidGlassMutedContentColor(Neutral500)
            )

            if (activeNodeName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.nodeActiveIndicatorPanel()
                    ) {
                        if (isLiquidGlassTheme()) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = activeNodeName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
    }
}
