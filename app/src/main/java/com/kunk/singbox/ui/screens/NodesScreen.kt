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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.FilterMode
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.ui.components.AddNodeDialog
import com.kunk.singbox.ui.components.AddNodeTarget
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.NodeFilterDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.NodeCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.Neutral500
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodesScreen(
    navController: NavController,
    viewModel: NodesViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // FAB显隐逻辑：上滑隐藏，下滑显示（即使列表不可滚动也生效）
    var isFabVisible by remember { mutableStateOf(true) }

    // nestedScroll 处理列表可滚动时的情况
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

    // pointerInput 处理列表不可滚动时的手势检测
    var lastY by remember { mutableStateOf(0f) }

    val nodes by viewModel.nodes.collectAsState()
    val activeNodeId by viewModel.activeNodeId.collectAsState()
    val testingNodeIds by viewModel.testingNodeIds.collectAsState()
    val nodeFilter by viewModel.nodeFilter.collectAsState()
    val sortType by viewModel.sortType.collectAsState()
    val testProgress by viewModel.testProgress.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    val isTesting by viewModel.isTesting.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
    var showFilterDialog by remember { mutableStateOf(false) }
    var showProtocolSelectDialog by remember { mutableStateOf(false) }
    var exportLink by remember { mutableStateOf<String?>(null) }
    var isFabExpanded by remember { mutableStateOf(false) }
    var showAddNodeDialog by remember { mutableStateOf(false) }

    if (showSortDialog) {
        val sortOptions = listOf(
            stringResource(R.string.nodes_sort_default) to NodeSortType.DEFAULT,
            stringResource(R.string.nodes_sort_latency) to NodeSortType.LATENCY,
            stringResource(R.string.nodes_sort_name) to NodeSortType.NAME,
            stringResource(R.string.nodes_sort_region) to NodeSortType.REGION
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

    if (showAddNodeDialog) {
        AddNodeDialog(
            profiles = profiles,
            onConfirm = { nodeLink, target ->
                when (target) {
                    is AddNodeTarget.ExistingProfile -> {
                        viewModel.addNode(
                            content = nodeLink,
                            targetProfileId = target.profileId
                        )
                    }
                    is AddNodeTarget.NewProfile -> {
                        viewModel.addNode(
                            content = nodeLink,
                            newProfileName = target.profileName
                        )
                    }
                }
                showAddNodeDialog = false
            },
            onDismiss = { showAddNodeDialog = false }
        )
    }

    // 节点筛选对话框
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

    // 协议选择对话框（手动创建节点）
    if (showProtocolSelectDialog) {
        val protocolOptions = listOf(
            "VMess", "VLESS", "Trojan", "Shadowsocks", "Hysteria2", "Hysteria",
            "TUIC", "WireGuard", "SSH", "AnyTLS", "SOCKS", "HTTP"
        )
        val protocolValues = listOf(
            "vmess", "vless", "trojan", "shadowsocks", "hysteria2", "hysteria",
            "tuic", "wireguard", "ssh", "anytls", "socks", "http"
        )
        SingleSelectDialog(
            title = stringResource(R.string.nodes_select_protocol),
            options = protocolOptions,
            selectedIndex = -1,
            onSelect = { index ->
                navController.navigate(Screen.NodeCreate.createRoute(protocolValues[index]))
                showProtocolSelectDialog = false
            },
            onDismiss = { showProtocolSelectDialog = false }
        )
    }

    if (exportLink != null) {
        val copiedMsg = stringResource(R.string.nodes_copied_to_clipboard)
        InputDialog(
            title = stringResource(R.string.nodes_export_link),
            initialValue = exportLink!!,
            confirmText = stringResource(R.string.common_copy),
            onConfirm = {
                clipboardManager.setText(AnnotatedString(it))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                exportLink = null
            },
            onDismiss = { exportLink = null }
        )
    }

    Scaffold(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            AnimatedVisibility(
                visible = isFabVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedVisibility(
                        visible = isFabExpanded,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            // Clear Latency
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.nodes_clear_latency),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(end = 8.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                SmallFloatingActionButton(
                                    onClick = {
                                        viewModel.clearLatency()
                                        isFabExpanded = false
                                    },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
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
                                SmallFloatingActionButton(
                                    onClick = {
                                        showAddNodeDialog = true
                                        isFabExpanded = false
                                    },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
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
                                SmallFloatingActionButton(
                                    onClick = {
                                        showProtocolSelectDialog = true
                                        isFabExpanded = false
                                    },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
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
                                SmallFloatingActionButton(
                                    onClick = {
                                        viewModel.testAllLatency()
                                        isFabExpanded = false
                                    },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ) {
                                    if (isTesting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Rounded.Bolt, contentDescription = stringResource(R.string.nodes_test_latency))
                                    }
                                }
                            }
                        }
                    }

                    FloatingActionButton(
                        onClick = { isFabExpanded = !isFabExpanded },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(
                            imageVector = if (isFabExpanded) Icons.Rounded.Close else Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.common_menu)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
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
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            // 1. Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.nodes_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 筛选按钮
                    IconButton(onClick = { showFilterDialog = true }) {
                        val hasFilter = nodeFilter.filterMode != FilterMode.NONE
                        Icon(
                            imageVector = Icons.Rounded.FilterAlt,
                            contentDescription = stringResource(R.string.nodes_filter),
                            tint = if (hasFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(onClick = { showSortDialog = true }) {
                        Icon(Icons.Rounded.Sort, contentDescription = stringResource(R.string.nodes_sort), tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }

            // 2. Search Bar (在标题下方)
            NodeSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                isExpanded = isSearchExpanded,
                onToggle = { isSearchExpanded = !isSearchExpanded },
                totalCount = nodes.size,
                filteredCount = filteredNodes.size,
                activeNodeName = nodes.find { it.id == activeNodeId }?.displayName,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            AnimatedVisibility(
                visible = testProgress != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                testProgress?.let { (completed, total) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
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
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 88.dp, top = 12.dp, start = 16.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = filteredNodes,
                        key = { _, node -> node.id },
                        contentType = { _, _ -> "node" }
                    ) { index, node ->
                        val isSelected = activeNodeId == node.id
                        val isTestingNode = testingNodeIds.contains(node.id)

                        val onNodeClick = remember(node.id) { { viewModel.setActiveNode(node.id) } }
                        val onEdit = remember(node.id) {
                            { navController.navigate(Screen.NodeDetail.createRoute(node.id)) }
                        }
                        val onExport = remember(node.id) {
                            {
                                val link = viewModel.exportNode(node.id)
                                if (link != null) {
                                    exportLink = link
                                }
                            }
                        }
                        val onLatency = remember(node.id) { { viewModel.testLatency(node.id) } }
                        val onDelete = remember(node.id) { { viewModel.deleteNode(node.id) } }

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

                        NodeCard(
                            name = node.displayName,
                            type = node.protocolDisplay,
                            latency = node.latencyMs,
                            isSelected = isSelected,
                            isTesting = isTestingNode,
                            regionFlag = node.regionFlag,
                            trafficUsed = node.trafficUsed,
                            onClick = onNodeClick,
                            onEdit = onEdit,
                            onExport = onExport,
                            onLatency = onLatency,
                            onDelete = onDelete,
                            modifier = Modifier
                                .animateItemPlacement()
                                .graphicsLayer(
                                    alpha = alpha,
                                    translationY = translateY
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    totalCount: Int,
    filteredCount: Int,
    activeNodeName: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // 左侧搜索图标按钮
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = null,
                tint = if (isExpanded) MaterialTheme.colorScheme.primary else Neutral500,
                modifier = Modifier.size(24.dp)
            )
        }

        // 未展开时显示统计信息
        AnimatedVisibility(
            visible = !isExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(start = 48.dp, end = 8.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 节点数量统计
                Text(
                    text = if (filteredCount != totalCount) {
                        "$filteredCount / $totalCount ${stringResource(R.string.nodes_count_suffix)}"
                    } else {
                        "$totalCount ${stringResource(R.string.nodes_count_suffix)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Neutral500
                )

                // 活跃节点
                if (activeNodeName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(3.dp)
                                )
                        )
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

        // 右侧胶囊搜索框(仅展开时显示,从左侧按钮右边开始)
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
            modifier = Modifier
                .padding(start = 52.dp)
                .height(40.dp)
        ) {
            var isFocused by remember { mutableStateOf(false) }
            val focusRequester = remember { FocusRequester() }

            LaunchedEffect(isExpanded) {
                if (isExpanded) {
                    focusRequester.requestFocus()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (query.isEmpty() && !isFocused) {
                                Text(
                                    text = stringResource(R.string.common_search),
                                    color = Neutral500,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.common_clear),
                            tint = Neutral500,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
