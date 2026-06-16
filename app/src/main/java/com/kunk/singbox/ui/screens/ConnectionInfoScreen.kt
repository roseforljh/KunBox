package com.kunk.singbox.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
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
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.R
import com.kunk.singbox.model.ClashConnection
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.viewmodel.ConnectionInfoViewModel
import java.util.Locale

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionInfoScreen(
    navController: NavController,
    viewModel: ConnectionInfoViewModel = viewModel()
) {
    val allConnections by viewModel.connections.collectAsState()
    val response by viewModel.connectionsResponse.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val vpnActive by viewModel.vpnActive.collectAsState()

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
            onDismissRequest = { showConfirmDeleteAll = false },
            title = { Text(stringResource(R.string.connection_info_close_all)) },
            text = { Text(stringResource(R.string.connection_info_close_all_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.closeAllConnections()
                        showConfirmDeleteAll = false
                    }
                ) {
                    Text(
                        stringResource(R.string.traffic_stats_clear_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDeleteAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.connection_info_title),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    // 搜索按钮
                    IconButton(onClick = {
                        isSearchExpanded = !isSearchExpanded
                        if (!isSearchExpanded) searchQuery = ""
                    }) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = if (isSearchExpanded)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(onClick = { viewModel.setRefreshing(!isRefreshing) }) {
                        Icon(
                            if (isRefreshing) Icons.Rounded.Pause
                            else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(
                                if (isRefreshing) R.string.connection_info_pause
                                else R.string.connection_info_resume
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
                            contentDescription = stringResource(
                                R.string.connection_info_close_all
                            ),
                            tint = if (canCloseAll)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onBackground.copy(
                                    alpha = 0.4f
                                )
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 可展开搜索栏（与节点页面一致的样式）
            AnimatedVisibility(
                visible = isSearchExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ConnectionSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // 概览信息栏
            if (vpnActive) {
                OverviewCard(
                    totalConnections = response?.connections?.size ?: 0,
                    uploadTotal = response?.uploadTotal ?: 0,
                    downloadTotal = response?.downloadTotal ?: 0
                )
            }

            // 连接列表与空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                if (!vpnActive) {
                    ConnectionEmptyState(
                        icon = Icons.Rounded.LinkOff,
                        title = stringResource(R.string.connection_info_vpn_not_running),
                        subtitle = stringResource(R.string.traffic_stats_no_data_hint)
                    )
                } else if (connections.isEmpty()) {
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
                        )
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = connections,
                            key = { it.id }
                        ) { connection ->
                            ConnectionItemCard(
                                connection = connection,
                                onClose = { viewModel.closeConnection(connection.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// 搜索栏组件，样式与 NodesScreen 的 NodeSearchBar 一致
@Suppress("LongMethod")
@Composable
private fun ConnectionSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
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

@Suppress("LongMethod")
@Composable
private fun OverviewCard(
    totalConnections: Int,
    uploadTotal: Long,
    downloadTotal: Long
) {
    val dividerGradient = Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f),
            Color.Transparent
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
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
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                Text(
                    text = "$totalConnections",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
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
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                Text(
                    text = formatTraffic(uploadTotal),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
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
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                Text(
                    text = formatTraffic(downloadTotal),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Suppress("LongMethod", "CognitiveComplexMethod")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionItemCard(
    connection: ClashConnection,
    onClose: () -> Unit
) {
    val isUdp = connection.metadata.network.lowercase() == "udp"
    val badgeBg = if (isUdp)
        MaterialTheme.colorScheme.tertiaryContainer
    else
        MaterialTheme.colorScheme.primaryContainer
    val badgeTextColor = if (isUdp)
        MaterialTheme.colorScheme.onTertiaryContainer
    else
        MaterialTheme.colorScheme.onPrimaryContainer

    val hostStr = connection.metadata.host.ifBlank { connection.metadata.destinationIP }
    val portStr = connection.metadata.destinationPort

    val isDark = isSystemInDarkTheme()
    val borderColor = if (isDark) {
        Color.White.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                borderColor,
                RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 目标及删除按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 协议药丸
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = connection.metadata.network.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextColor
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 地址
                Text(
                    text = "$hostStr:$portStr",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 切断按钮
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(26.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.connection_info_close_connection),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // 额外的目的IP显示（如果host不为空）
            if (connection.metadata.host.isNotBlank() && connection.metadata.destinationIP.isNotBlank()) {
                Text(
                    text = "IP: ${connection.metadata.destinationIP}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 代理链与规则
            if (connection.chains.isNotEmpty() || connection.rule.isNotBlank()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 规则
                    if (connection.rule.isNotBlank()) {
                        val payload = if (connection.rulePayload.isNotBlank()) "(${connection.rulePayload})" else ""
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp, bottom = 4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Rule: ${connection.rule}$payload",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 节点链路
                    if (connection.chains.isNotEmpty()) {
                        val chainText = connection.chains.joinToString(" → ")
                        Box(
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = chainText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 流量统计及已连接时长
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "↑ ${formatTraffic(connection.upload)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "↓ ${formatTraffic(connection.download)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                // 持续时间
                val duration = formatDuration(connection.start)
                if (duration.isNotBlank()) {
                    Text(
                        text = duration,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp),
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

internal object ConnectionInfoUiPolicy {
    private val rfc3339Regex = Regex(
        """^(.+?)(?:\.(\d+))?([Zz]|[+-]\d{2}:\d{2}|[+-]\d{4}|[+-]\d{2})$"""
    )

    fun filterConnections(
        connections: List<ClashConnection>,
        searchQuery: String
    ): List<ClashConnection> {
        if (searchQuery.isBlank()) return connections

        val lower = searchQuery.lowercase()
        return connections.filter { conn ->
            conn.metadata.host.lowercase().contains(lower) ||
                conn.metadata.destinationIP.lowercase().contains(lower) ||
                conn.rule.lowercase().contains(lower) ||
                conn.rulePayload.lowercase().contains(lower) ||
                conn.chains.any { it.lowercase().contains(lower) }
        }
    }

    fun canCloseAll(
        vpnActive: Boolean,
        allConnections: List<ClashConnection>
    ): Boolean {
        return vpnActive && allConnections.isNotEmpty()
    }

    fun emptySubtitleRes(
        searchQuery: String,
        allConnections: List<ClashConnection>,
        filteredConnections: List<ClashConnection>
    ): Int {
        return if (isSearchNoMatch(searchQuery, allConnections, filteredConnections)) {
            R.string.connection_info_no_match
        } else {
            R.string.connection_info_no_active
        }
    }

    fun emptyTitleRes(
        searchQuery: String,
        allConnections: List<ClashConnection>,
        filteredConnections: List<ClashConnection>
    ): Int {
        return if (isSearchNoMatch(searchQuery, allConnections, filteredConnections)) {
            R.string.connection_info_no_match_title
        } else {
            R.string.connection_info_no_connections
        }
    }

    private fun isSearchNoMatch(
        searchQuery: String,
        allConnections: List<ClashConnection>,
        filteredConnections: List<ClashConnection>
    ): Boolean {
        return searchQuery.isNotBlank() &&
            allConnections.isNotEmpty() &&
            filteredConnections.isEmpty()
    }

    fun formatTraffic(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        if (digitGroups >= units.size) return "$bytes B"
        return String.format(
            Locale.US,
            "%.2f %s",
            bytes / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups]
        )
    }

    fun formatDuration(
        startTime: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val startMillis = parseRfc3339Millis(startTime) ?: return ""
        val diff = nowMillis - startMillis
        return if (diff <= 0) {
            "0s"
        } else {
            formatPositiveDuration(diff)
        }
    }

    private fun formatPositiveDuration(diff: Long): String {
        val sec = diff / 1000
        val min = sec / 60
        val hr = min / 60
        return when {
            sec < 60 -> "${sec}s"
            min < 60 -> "${min}m ${sec % 60}s"
            else -> "${hr}h ${min % 60}m"
        }
    }

    private fun parseRfc3339Millis(value: String): Long? {
        if (value.isBlank()) return null
        val match = rfc3339Regex.matchEntire(value) ?: return null
        val base = match.groupValues[1]
        val fraction = match.groups[2]?.value.orEmpty()
        val zone = normalizeZone(match.groupValues[3])
        val millis = fraction.take(3).padEnd(3, '0')
        val normalized = "$base.$millis$zone"

        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                .parse(normalized)
                ?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeZone(zone: String): String {
        return when {
            zone.equals("Z", ignoreCase = true) -> "Z"
            zone.matches(Regex("""[+-]\d{2}$""")) -> "$zone:00"
            zone.matches(Regex("""[+-]\d{4}$""")) -> {
                "${zone.substring(0, 3)}:${zone.substring(3)}"
            }
            else -> zone
        }
    }
}

private fun formatTraffic(bytes: Long): String {
    return ConnectionInfoUiPolicy.formatTraffic(bytes)
}

private fun formatDuration(startTime: String): String {
    return ConnectionInfoUiPolicy.formatDuration(startTime)
}
