@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.R
import com.kunk.singbox.ui.components.FloatingPageLayout
import com.kunk.singbox.ui.theme.LiquidGlassDialogEffect
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassDividerColor
import com.kunk.singbox.ui.theme.liquidGlassEmptyStatePanel
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassDialogContainerColor
import com.kunk.singbox.ui.theme.liquidGlassDialogPanel
import com.kunk.singbox.ui.theme.liquidGlassIconButtonColors
import com.kunk.singbox.ui.theme.liquidGlassMutedContentColor
import com.kunk.singbox.ui.theme.liquidGlassTextButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassTextButtonColors
import com.kunk.singbox.ui.theme.liquidGlassTextButtonPanel
import com.kunk.singbox.viewmodel.ConnectionInfoViewModel
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor

@Composable
internal fun Modifier.connectionEmptyIconPanel(): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = CircleShape, shadowElevation = 12.dp)
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), CircleShape)
    }
}

@Composable
internal fun Modifier.connectionSearchPanel(): Modifier {
    val shape = RoundedCornerShape(20.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 8.dp)
    } else {
        background(
            color = MaterialTheme.colorScheme.surface,
            shape = shape
        ).border(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.2f),
            shape = shape
        )
    }
}

@Composable
internal fun Modifier.connectionMetaBadgePanel(defaultColor: Color): Modifier {
    val shape = RoundedCornerShape(4.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 4.dp)
    } else {
        background(defaultColor, shape)
    }
}

@Composable
internal fun Modifier.connectionProtocolBadgePanel(defaultColor: Color): Modifier {
    val shape = RoundedCornerShape(6.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = true, shadowElevation = 4.dp)
    } else {
        background(defaultColor, shape)
    }
}

@Composable
internal fun Modifier.connectionCloseButtonPanel(): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = CircleShape, selected = true, shadowElevation = 4.dp)
    } else {
        this
    }
}

@Composable
internal fun Modifier.connectionEmptyStatePanel(): Modifier {
    return if (isLiquidGlassTheme()) {
        fillMaxWidth()
            .liquidGlassEmptyStatePanel()
            .padding(28.dp)
    } else {
        fillMaxWidth()
    }
}

@Composable
internal fun Modifier.connectionOverviewPanel(): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(
            shape = RoundedCornerShape(16.dp),
            selected = true,
            shadowElevation = 10.dp
        )
    } else {
        this
    }
}

@Composable
internal fun Modifier.connectionItemPanel(): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp)
    } else {
        this
    }
}

@Composable
internal fun connectionItemContainerColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) Color.Transparent else defaultColor
}

@Composable
internal fun connectionOverviewDividerBrush(): Brush {
    val dividerColor = liquidGlassDividerColor(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f))
    return Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            dividerColor,
            Color.Transparent
        )
    )
}

@Composable
internal fun connectionOverviewLabelColor(): Color {
    return if (isLiquidGlassTheme()) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    }
}

@Composable
internal fun connectionOverviewValueColor(): Color {
    return if (isLiquidGlassTheme()) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
}

@Composable
internal fun connectionProtocolBadgeTextColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        MaterialTheme.colorScheme.primary
    } else {
        defaultColor
    }
}

@Composable
internal fun connectionMetaBadgeTextColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        defaultColor
    }
}

@Composable
internal fun ConnectionCloseControl(
    onClose: () -> Unit
) {
    IconButton(
        onClick = onClose,
        modifier = Modifier
            .size(26.dp)
            .connectionCloseButtonPanel(),
        colors = liquidGlassIconButtonColors(
            defaultContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
            defaultContentColor = MaterialTheme.colorScheme.error,
            liquidContentColor = MaterialTheme.colorScheme.error
        )
    ) {
        Icon(
            Icons.Rounded.Close,
            contentDescription = stringResource(R.string.connection_info_close_connection),
            modifier = Modifier.size(14.dp)
        )
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionInfoScreen(
    navController: NavController,
    viewModel: ConnectionInfoViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val response = uiState.response
    val isRefreshing = uiState.isRefreshing
    val vpnActive = uiState.vpnActive
    val allConnections = response?.connections.orEmpty()

    var showConfirmDeleteAll by remember { mutableStateOf(false) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 本地搜索过滤
    val connections = remember(allConnections, searchQuery) {
        ConnectionInfoUiPolicy.filterConnections(allConnections, searchQuery)
    }
    val canCloseAll = ConnectionInfoUiPolicy.canCloseAll(
        vpnActive = vpnActive,
        allConnections = allConnections
    )

    if (showConfirmDeleteAll) {
        AlertDialog(
            modifier = Modifier.liquidGlassDialogPanel(),
            containerColor = liquidGlassDialogContainerColor(),
            onDismissRequest = { showConfirmDeleteAll = false },
            title = { Text(stringResource(R.string.connection_info_close_all)) },
            text = {
                LiquidGlassDialogEffect()
                Text(stringResource(R.string.connection_info_close_all_confirm))
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.liquidGlassTextButtonPanel(),
                    colors = liquidGlassTextButtonColors(
                        contentColor = liquidGlassTextButtonContentColor(
                            defaultColor = MaterialTheme.colorScheme.error,
                            liquidColor = MaterialTheme.colorScheme.error
                        )
                    ),
                    onClick = {
                        viewModel.closeAllConnections()
                        showConfirmDeleteAll = false
                    }
                ) {
                    Text(
                        stringResource(R.string.traffic_stats_clear_button),
                        color = liquidGlassTextButtonContentColor(
                            defaultColor = MaterialTheme.colorScheme.error,
                            liquidColor = MaterialTheme.colorScheme.error
                        )
                    )
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
                    onClick = { showConfirmDeleteAll = false }
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    FloatingPageLayout(
        title = stringResource(R.string.connection_info_title),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(
                onClick = {
                    isSearchExpanded = !isSearchExpanded
                    if (!isSearchExpanded) searchQuery = ""
                }
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = if (isSearchExpanded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    }
                )
            }
            IconButton(
                onClick = { viewModel.setRefreshing(!isRefreshing) }
            ) {
                Icon(
                    if (isRefreshing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(
                        if (isRefreshing) {
                            R.string.connection_info_pause
                        } else {
                            R.string.connection_info_resume
                        }
                    ),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(
                onClick = { showConfirmDeleteAll = true },
                enabled = canCloseAll
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.connection_info_close_all),
                    tint = if (canCloseAll) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    }
                )
            }
        },
        circularAction = false
    ) { contentTopPadding ->
        val searchAlpha by animateFloatAsState(
            targetValue = if (isSearchExpanded) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "searchAlpha"
        )
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background)
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    top = contentTopPadding + 6.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (searchAlpha > 0f) {
                    item(key = "connection_search") {
                        ConnectionSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            modifier = Modifier
                                .graphicsLayer {
                                    alpha = searchAlpha
                                    scaleY = 0.96f + 0.04f * searchAlpha
                                    translationY = (1f - searchAlpha) * (-10.dp.toPx())
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }

                if (vpnActive) {
                    item(key = "connection_overview") {
                        OverviewCard(
                            totalConnections = allConnections.size,
                            uploadTotal = response?.uploadTotal ?: 0,
                            downloadTotal = response?.downloadTotal ?: 0
                        )
                    }
                }

                if (!vpnActive) {
                    item(key = "connection_vpn_inactive") {
                        ConnectionEmptyState(
                            icon = Icons.Rounded.LinkOff,
                            title = stringResource(R.string.connection_info_vpn_not_running),
                            subtitle = stringResource(R.string.traffic_stats_no_data_hint),
                            modifier = Modifier.fillParentMaxHeight(0.72f)
                        )
                    }
                } else if (connections.isEmpty()) {
                    item(key = "connection_empty") {
                        ConnectionEmptyState(
                            icon = Icons.Rounded.Info,
                            title = stringResource(
                                ConnectionInfoUiPolicy.emptyTitleRes(
                                    searchQuery = searchQuery,
                                    allConnections = allConnections,
                                    filteredConnections = connections
                                )
                            ),
                            subtitle = stringResource(
                                ConnectionInfoUiPolicy.emptySubtitleRes(
                                    searchQuery = searchQuery,
                                    allConnections = allConnections,
                                    filteredConnections = connections
                                )
                            ),
                            modifier = Modifier.fillParentMaxHeight(0.62f)
                        )
                    }
                } else {
                    items(
                        items = connections,
                        key = { it.id }
                    ) { connection ->
                        ConnectionItemCard(
                            connection = connection,
                            onClose = { viewModel.closeConnection(connection.id) },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

// 搜索栏组件，样式与 NodesScreen 的 NodeSearchBar 一致
@Suppress("LongMethod")
@Composable
internal fun ConnectionSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .connectionSearchPanel(),
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
                            color = liquidGlassMutedContentColor(Neutral500),
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
                    .padding(end = 6.dp)
                    .size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.common_clear),
                    tint = liquidGlassMutedContentColor(Neutral500),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
internal fun OverviewCard(
    totalConnections: Int,
    uploadTotal: Long,
    downloadTotal: Long
) {
    val dividerGradient = connectionOverviewDividerBrush()
    val shape = RoundedCornerShape(16.dp)

    if (isLiquidGlassTheme()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .connectionOverviewPanel()
        ) {
            OverviewCardContent(
                totalConnections = totalConnections,
                uploadTotal = uploadTotal,
                downloadTotal = downloadTotal,
                dividerGradient = dividerGradient
            )
        }
        return
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        OverviewCardContent(
            totalConnections = totalConnections,
            uploadTotal = uploadTotal,
            downloadTotal = downloadTotal,
            dividerGradient = dividerGradient
        )
    }
}

@Composable
@Suppress("LongMethod")
internal fun OverviewCardContent(
    totalConnections: Int,
    uploadTotal: Long,
    downloadTotal: Long,
    dividerGradient: Brush
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.connection_info_active_count),
                style = MaterialTheme.typography.labelMedium,
                color = connectionOverviewLabelColor()
            )
            Text(
                text = "$totalConnections",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = connectionOverviewValueColor()
            )
        }
        Spacer(
            modifier = Modifier
                .height(36.dp)
                .width(1.dp)
                .background(dividerGradient)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.connection_info_total_upload),
                style = MaterialTheme.typography.labelMedium,
                color = connectionOverviewLabelColor()
            )
            Text(
                text = formatTraffic(uploadTotal),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = connectionOverviewValueColor()
            )
        }
        Spacer(
            modifier = Modifier
                .height(36.dp)
                .width(1.dp)
                .background(dividerGradient)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.connection_info_total_download),
                style = MaterialTheme.typography.labelMedium,
                color = connectionOverviewLabelColor()
            )
            Text(
                text = formatTraffic(downloadTotal),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = connectionOverviewValueColor()
            )
        }
    }
}
