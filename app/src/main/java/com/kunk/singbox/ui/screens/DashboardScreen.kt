package com.kunk.singbox.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.viewmodel.DashboardViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kunk.singbox.ui.components.BigToggle
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.InfoCard
import com.kunk.singbox.ui.components.ModeChip
import com.kunk.singbox.ui.components.NodeSelectorDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StatusChip
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.R
import com.kunk.singbox.viewmodel.NodesViewModel
import android.widget.Toast
import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel()
) {
    val context = LocalContext.current

    val connectionState by viewModel.connectionState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val activeNodeId by viewModel.activeNodeId.collectAsState()
    val activeNodeLatency by viewModel.activeNodeLatency.collectAsState()
    val currentNodePing by viewModel.currentNodePing.collectAsState()
    val isPingTesting by viewModel.isPingTesting.collectAsState()
    val nodes by viewModel.nodes.collectAsState()
    val settings by settingsViewModel.settings.collectAsState()

    val nodesForSelector by nodesViewModel.nodes.collectAsState()
    val testingNodeIds by nodesViewModel.testingNodeIds.collectAsState()

    // 优化: 使用 derivedStateOf 避免不必要的重组
    // 原因: profiles 或 activeProfileId 变化时,只有实际名称改变才触发重组
    val activeProfileName by remember {
        derivedStateOf {
            profiles.find { it.id == activeProfileId }?.name
        }
    }

    // 优化: 缓存活跃节点名称计算
    // 重要：必须依赖 activeNodeId 的变化，否则节点选择后不会更新显示
    val activeNodeName by remember(activeNodeId) {
        derivedStateOf {
            viewModel.getActiveNodeName()
        }
    }

    var showModeDialog by remember { mutableStateOf(false) }
    val currentMode = stringResource(settings.routingMode.displayNameRes)
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showTestDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showNodeDialog by remember { mutableStateOf(false) }
    var lastConnectionState by remember { mutableStateOf<ConnectionState?>(null) }

    val updateStatus by viewModel.updateStatus.collectAsState()
    val testStatus by viewModel.testStatus.collectAsState()
    val actionStatus by viewModel.actionStatus.collectAsState()
    val vpnPermissionNeeded by viewModel.vpnPermissionNeeded.collectAsState()

    // VPN 权限请求处理
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    // 当需要 VPN 权限时启动请求
    LaunchedEffect(vpnPermissionNeeded) {
        if (vpnPermissionNeeded) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                // 已有权限
                viewModel.onVpnPermissionResult(true)
            }
        }
    }

    // 已移除连接状态的 Toast 提示，避免干扰用户
    // 用户可以通过 UI 上的连接状态指示器（表情、文字）来了解当前状态
    LaunchedEffect(connectionState) {
        lastConnectionState = connectionState
    }

    // Monitor update status
    LaunchedEffect(updateStatus) {
        updateStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    // Monitor test status
    LaunchedEffect(testStatus) {
        testStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(actionStatus) {
        actionStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    if (showModeDialog) {
        val options = RoutingMode.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.dashboard_routing_mode),
            options = options,
            selectedIndex = RoutingMode.entries.indexOf(settings.routingMode).coerceAtLeast(0),
            onSelect = { index ->
                settingsViewModel.setRoutingMode(
                    RoutingMode.entries[index],
                    notifyRestartRequired = false
                )
                if (connectionState == ConnectionState.Connected || connectionState == ConnectionState.Connecting) {
                    viewModel.restartVpn()
                }
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false }
        )
    }

    if (showUpdateDialog) {
        ConfirmDialog(
            title = stringResource(R.string.dashboard_update_subscription),
            message = stringResource(R.string.dashboard_update_subscription_confirm),
            confirmText = stringResource(R.string.common_update),
            onConfirm = {
                viewModel.updateAllSubscriptions()
                showUpdateDialog = false
            },
            onDismiss = { showUpdateDialog = false }
        )
    }

    if (showTestDialog) {
        ConfirmDialog(
            title = stringResource(R.string.dashboard_latency_test),
            message = stringResource(R.string.dashboard_latency_test_confirm),
            confirmText = stringResource(R.string.dashboard_start_test),
            onConfirm = {
                viewModel.testAllNodesLatency()
                showTestDialog = false
            },
            onDismiss = { showTestDialog = false }
        )
    }

    if (showProfileDialog) {
        val options = profiles.map { it.name }
        SingleSelectDialog(
            title = stringResource(R.string.dashboard_select_profile),
            options = options,
            selectedIndex = profiles.indexOfFirst { it.id == activeProfileId }.let { idx ->
                when {
                    options.isEmpty() -> -1
                    idx >= 0 -> idx
                    else -> 0
                }
            },
            onSelect = { index ->
                profiles.getOrNull(index)?.id?.let { viewModel.setActiveProfile(it) }
                showProfileDialog = false
            },
            onDismiss = { showProfileDialog = false }
        )
    }

    if (showNodeDialog) {
        NodeSelectorDialog(
            title = stringResource(R.string.dashboard_select_node),
            nodes = nodesForSelector,
            selectedNodeId = activeNodeId,
            testingNodeIds = testingNodeIds,
            onSelect = { nodeId ->
                viewModel.setActiveNode(nodeId)
            },
            onDismiss = { showNodeDialog = false }
        )
    }

    // Helper to format bytes
    fun formatBytes(bytes: Long): String = Formatter.formatFileSize(context, bytes)

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    // Background Animation
    val isRunning = connectionState == ConnectionState.Connected || connectionState == ConnectionState.Connecting
    val infiniteTransition = rememberInfiniteTransition(label = "BackgroundAnimation")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isRunning) 0.6f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    val defaultBaseColor = MaterialTheme.colorScheme.onSurface

    Box(modifier = Modifier.fillMaxSize()) {
        // Background Decoration - 3D Box Cage (Back Face & Connections)
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val center = center
            val baseSize = size.minDimension * 0.55f * pulseScale

            val baseAlpha = if (isRunning) pulseAlpha * 0.5f else 0.15f
            val color = defaultBaseColor.copy(alpha = baseAlpha)
            val strokeWidth = if (isRunning) 2.dp.toPx() else 1.dp.toPx()

            val rotationX = Math.toRadians(15.0).toFloat()
            val rotationY = Math.toRadians(35.0).toFloat()

            fun project(x: Float, y: Float, z: Float): Offset {
                val y1 = y * cos(rotationX) - z * sin(rotationX)
                val z1 = y * sin(rotationX) + z * cos(rotationX)
                val x2 = x * cos(rotationY) + z1 * sin(rotationY)
                return Offset(center.x + x2, center.y + y1)
            }

            val s = baseSize / 1.5f

            val p1 = project(-s, -s, -s)
            val p2 = project(s, -s, -s)
            val p3 = project(s, s, -s)
            val p4 = project(-s, s, -s)
            val p5 = project(-s, -s, s)
            val p6 = project(s, -s, s)
            val p7 = project(s, s, s)
            val p8 = project(-s, s, s)

            val path = Path()
            // Back Face (p5-p8)
            path.moveTo(p5.x, p5.y)
            path.lineTo(p6.x, p6.y)
            path.lineTo(p7.x, p7.y)
            path.lineTo(p8.x, p8.y)
            path.close()

            // Connections
            path.moveTo(p1.x, p1.y); path.lineTo(p5.x, p5.y)
            path.moveTo(p2.x, p2.y); path.lineTo(p6.x, p6.y)
            path.moveTo(p3.x, p3.y); path.lineTo(p7.x, p7.y)
            path.moveTo(p4.x, p4.y); path.lineTo(p8.x, p8.y)

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = strokeWidth)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()) // 为状态栏添加顶部内边距
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Status Bar (Chips)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // App Logo & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    // Use AndroidView to render adaptive icon correctly
                    AndroidView(
                        factory = { ctx ->
                            ImageView(ctx).apply {
                                setImageResource(R.mipmap.ic_launcher_round)
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .padding(end = 12.dp)
                    )
                    Text(
                        text = "KunBox",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusChip(
                        label = stringResource(connectionState.displayNameRes),
                        isActive = connectionState == ConnectionState.Connected
                    )

                    val indicatorColor = when (connectionState) {
                        ConnectionState.Connected -> Color(0xFF4CAF50) // Green
                        ConnectionState.Error -> Color(0xFFF44336) // Red
                        else -> Neutral500 // Grey
                    }

                    ModeChip(
                        mode = currentMode,
                        indicatorColor = indicatorColor
                    ) { showModeDialog = true }
                }

                val noProfileMsg = stringResource(R.string.dashboard_no_profiles_available)
                val noNodeMsg = stringResource(R.string.dashboard_no_nodes_available)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    StatusChip(
                        label = activeProfileName ?: stringResource(R.string.dashboard_no_profile_selected),
                        onClick = {
                            if (profiles.isNotEmpty()) {
                                showProfileDialog = true
                            } else {
                                Toast.makeText(context, noProfileMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusChip(
                        label = activeNodeName ?: stringResource(R.string.dashboard_no_node_selected),
                        onClick = {
                            if (nodes.isNotEmpty()) {
                                showNodeDialog = true
                            } else {
                                Toast.makeText(context, noNodeMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            // 2. Main Toggle - 居中显示
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f)
            ) {
                BigToggle(
                    isRunning = connectionState == ConnectionState.Connected || connectionState == ConnectionState.Connecting,
                    onClick = {
                        viewModel.toggleConnection()
                    }
                )
            }

            // 3. Stats & Quick Actions
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Always show InfoCard but with placeholder data when not connected
                val isConnected = connectionState == ConnectionState.Connected
                // 优先使用 VPN 启动后测得的实时延迟，如果没有则使用缓存的延迟
                // currentNodePing: null = 未测试, -1 = 超时/失败, >0 = 实际延迟
                val displayPing = when {
                    currentNodePing != null && currentNodePing!! > 0 -> currentNodePing
                    currentNodePing == null && activeNodeLatency != null -> activeNodeLatency
                    else -> currentNodePing // 可能是 -1（超时）或 null
                }
                // 使用明确的 isPingTesting 状态来控制加载动画
                val isPingLoading = isConnected && isPingTesting
                // 格式化延迟显示：超时显示"超时"，未测试显示"-"
                val timeoutMsg = stringResource(R.string.common_timeout)
                val pingText = when {
                    !isConnected -> "-"
                    displayPing != null && displayPing > 0 -> "$displayPing ms"
                    displayPing == -1L -> timeoutMsg
                    else -> "-"
                }
                InfoCard(
                    uploadSpeed = if (isConnected) "${formatBytes(stats.uploadSpeed)}/s" else "-/s",
                    downloadSpeed = if (isConnected) "${formatBytes(stats.downloadSpeed)}/s" else "-/s",
                    ping = pingText,
                    isPingLoading = isPingLoading,
                    onPingClick = if (isConnected) {
                        { viewModel.retestCurrentNodePing() }
                    } else {
                        null
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Quick Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QuickActionButton(Icons.Rounded.Refresh, stringResource(R.string.dashboard_update_subscription)) { showUpdateDialog = true }
                    QuickActionButton(Icons.Rounded.Bolt, stringResource(R.string.dashboard_latency_test)) { showTestDialog = true }
                    QuickActionButton(Icons.Rounded.Terminal, stringResource(R.string.dashboard_logs)) { navController.navigate(Screen.Logs.route) }
                    QuickActionButton(Icons.Rounded.BugReport, stringResource(R.string.dashboard_diagnostics)) { navController.navigate(Screen.Diagnostics.route) }
                }
            }
        }

        // Foreground Decoration - 3D Box Cage (Front Face)
        // Drawn on top of Column to create "Trapped" effect
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val center = center
            val baseSize = size.minDimension * 0.55f * pulseScale

            val baseAlpha = if (isRunning) pulseAlpha * 0.5f else 0.15f
            val color = defaultBaseColor.copy(alpha = baseAlpha)
            val strokeWidth = if (isRunning) 2.dp.toPx() else 1.dp.toPx()

            val rotationX = Math.toRadians(15.0).toFloat()
            val rotationY = Math.toRadians(35.0).toFloat()

            fun project(x: Float, y: Float, z: Float): Offset {
                val y1 = y * cos(rotationX) - z * sin(rotationX)
                val z1 = y * sin(rotationX) + z * cos(rotationX)
                val x2 = x * cos(rotationY) + z1 * sin(rotationY)
                return Offset(center.x + x2, center.y + y1)
            }

            val s = baseSize / 1.5f

            // Only need p1-p4 for front face
            val p1 = project(-s, -s, -s)
            val p2 = project(s, -s, -s)
            val p3 = project(s, s, -s)
            val p4 = project(-s, s, -s)

            val path = Path()
            // Front Face
            path.moveTo(p1.x, p1.y)
            path.lineTo(p2.x, p2.y)
            path.lineTo(p3.x, p3.y)
            path.lineTo(p4.x, p4.y)
            path.close()

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = strokeWidth)
            )
        }
    }
}

@Composable
fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
