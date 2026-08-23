package com.kunk.singbox.ui.screens

import android.text.format.Formatter
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.PingDisplayText
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.viewmodel.DashboardViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kunk.singbox.ui.components.BigToggle
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.InfoCard
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.ModeChip
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StatusChip
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.rememberLocalNetworkPermissionRequest
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.R
import com.kunk.singbox.utils.LocalNetworkPermission
import com.kunk.singbox.viewmodel.NodesViewModel
import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassMutedContentColor
import com.kunk.singbox.ui.theme.liquidGlassStrongContentColor
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "FunctionNaming")
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    bottomContentPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val dataPlaneReadiness by viewModel.dataPlaneReadiness.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val activeNodeId by viewModel.activeNodeId.collectAsStateWithLifecycle()
    val isAutoSelectionEnabled by viewModel.isAutoSelectionEnabled.collectAsStateWithLifecycle()
    val activeNodeLatency by viewModel.activeNodeLatency.collectAsStateWithLifecycle()
    val isPingTesting by viewModel.isPingTesting.collectAsStateWithLifecycle()
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val runtimeNodeLabel by SingBoxRemote.activeLabel.collectAsStateWithLifecycle()

    val nodesForSelector by nodesViewModel.nodes.collectAsStateWithLifecycle()
    val rawNodesForSelector by nodesViewModel.rawNodes.collectAsStateWithLifecycle()
    val testingNodeIds by nodesViewModel.testingNodeIds.collectAsStateWithLifecycle()

    val activeProfileName by remember {
        derivedStateOf {
            profiles.find { it.id == activeProfileId }?.name
        }
    }

    // 连接态优先显示运行态标签，避免自动切换后主页仍显示手选旧节点
    val activeNodeName by remember(activeNodeId, nodes, connectionState, runtimeNodeLabel) {
        derivedStateOf {
            viewModel.getActiveNodeName()
        }
    }
    val autoSelectionSubtitle = when {
        !isAutoSelectionEnabled -> stringResource(R.string.nodes_auto_selection_disabled)
        runtimeNodeLabel.isNotBlank() -> stringResource(R.string.nodes_auto_selection_current, runtimeNodeLabel)
        connectionState == ConnectionState.Connecting -> stringResource(R.string.nodes_auto_selection_selecting)
        else -> stringResource(R.string.nodes_auto_selection_unavailable)
    }
    val displayedNodeName = if (isAutoSelectionEnabled) {
        stringResource(
            R.string.nodes_auto_selection_label,
            activeNodeName ?: stringResource(R.string.nodes_auto_selection_selecting)
        )
    } else {
        activeNodeName
    }

    var showModeDialog by remember { mutableStateOf(false) }
    val currentMode = stringResource(settings.routingMode.displayNameRes)
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showTestDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showNodeDialog by remember { mutableStateOf(false) }
    var lastConnectionState by remember { mutableStateOf<ConnectionState?>(null) }

    val vpnPermissionNeeded by viewModel.vpnPermissionNeeded.collectAsStateWithLifecycle()
    val requestLocalNetworkPermission = rememberLocalNetworkPermissionRequest()

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(vpnPermissionNeeded) {
        if (vpnPermissionNeeded) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                viewModel.onVpnPermissionResult(true)
            }
        }
    }

    LaunchedEffect(connectionState) {
        lastConnectionState = connectionState
    }

    LaunchedEffect(viewModel) {
        viewModel.toastEvents.collectLatest { message ->
            AppNotificationManager.showMessage(context, message)
        }
    }

    if (showModeDialog) {
        val options = RoutingMode.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.dashboard_routing_mode),
            options = options,
            selectedIndex = RoutingMode.entries.indexOf(settings.routingMode).coerceAtLeast(0),
            onSelect = { index ->
                val selectedMode = RoutingMode.entries[index]
                scope.launch {
                    settingsViewModel.setRoutingModeAndWait(
                        selectedMode,
                        notifyRestartRequired = false
                    )
                    if (connectionState == ConnectionState.Connected || connectionState == ConnectionState.Connecting) {
                        viewModel.restartVpn()
                    }
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
        NodePickerPage(
            title = stringResource(R.string.dashboard_select_node),
            profiles = profiles,
            allNodes = rawNodesForSelector,
            displayedNodes = nodesForSelector,
            selectedNodeId = activeNodeId,
            isAutoSelectionEnabled = isAutoSelectionEnabled,
            autoSelectionSubtitle = autoSelectionSubtitle,
            testingNodeIds = testingNodeIds,
            onSelectAuto = viewModel::enableAutoSelection,
            onSelectNode = { node ->
                viewModel.setActiveNode(node.id)
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
    val useLiquidGlass = isLiquidGlassTheme()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(modifier = Modifier.fillMaxSize()) {
        // Background Decoration - 3D Box Cage (Back Face & Connections)
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val center = center
            val baseSize = size.minDimension * 0.55f * pulseScale

            val baseAlpha = if (isRunning) pulseAlpha * 0.5f else 0.15f
            val color = if (useLiquidGlass) {
                primaryColor.copy(alpha = if (isDark) baseAlpha * 1.5f else baseAlpha * 1.6f)
            } else {
                defaultBaseColor.copy(alpha = baseAlpha)
            }
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
                .padding(top = statusBarPadding.calculateTopPadding())
                .padding(24.dp)
                .padding(bottom = bottomContentPadding),
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
                    val safetyStatusLabel = when (dataPlaneReadiness.status) {
                        com.kunk.singbox.ipc.DataPlaneStatus.BLOCKING -> stringResource(R.string.vpn_status_blocking)
                        com.kunk.singbox.ipc.DataPlaneStatus.RECOVERING ->
                            stringResource(R.string.vpn_status_recovering)
                        com.kunk.singbox.ipc.DataPlaneStatus.FAILED_BLOCKED ->
                            stringResource(R.string.vpn_status_failed_blocked)
                        com.kunk.singbox.ipc.DataPlaneStatus.FAILED_UNPROTECTED ->
                            stringResource(R.string.vpn_status_failed_unprotected)
                        else -> stringResource(connectionState.displayNameRes)
                    }
                    StatusChip(
                        label = safetyStatusLabel,
                        isActive = connectionState == ConnectionState.Connected
                    )

                    val indicatorColor = when (connectionState) {
                        ConnectionState.Connected -> Color(0xFF4CAF50) // Green
                        ConnectionState.Error -> Color(0xFFF44336) // Red
                        else -> liquidGlassMutedContentColor(Neutral500)
                    }

                    ModeChip(
                        mode = currentMode,
                        indicatorColor = indicatorColor
                    ) { showModeDialog = true }
                }

                val browserExcluded = dataPlaneReadiness.routingScope.contains("browser=excluded")
                val failedUnprotected =
                    dataPlaneReadiness.status == com.kunk.singbox.ipc.DataPlaneStatus.FAILED_UNPROTECTED
                val protectionWarning = when {
                    browserExcluded ->
                        stringResource(R.string.vpn_browser_not_covered)
                    failedUnprotected -> stringResource(R.string.vpn_lockdown_incomplete)
                    settings.tunEnabled &&
                        dataPlaneReadiness.status == com.kunk.singbox.ipc.DataPlaneStatus.READY &&
                        dataPlaneReadiness.lockdownStatus != com.kunk.singbox.ipc.VpnLockdownStatus.ENABLED ->
                        stringResource(R.string.vpn_lockdown_incomplete)
                    else -> null
                }
                if (protectionWarning != null) {
                    Text(
                        text = protectionWarning,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (browserExcluded || failedUnprotected) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                            }
                    )
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
                                AppNotificationManager.showMessage(context, noProfileMsg)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusChip(
                        label = displayedNodeName ?: stringResource(R.string.dashboard_no_node_selected),
                        onClick = {
                            if (nodes.isNotEmpty()) {
                                showNodeDialog = true
                            } else {
                                AppNotificationManager.showMessage(context, noNodeMsg)
                            }
                        }
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f)
            ) {
                val isRunning = connectionState == ConnectionState.Connected ||
                    connectionState == ConnectionState.Connecting
                val toggleConnection = { viewModel.toggleConnection() }
                BigToggle(
                    isRunning = isRunning,
                    onClick = {
                        if (!isRunning && LocalNetworkPermission.requiresLocalNetworkAccess(settings)) {
                            requestLocalNetworkPermission(toggleConnection)
                        } else {
                            toggleConnection()
                        }
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

                val displayPing = activeNodeLatency

                val isPingLoading = isConnected && isPingTesting

                val timeoutMsg = stringResource(R.string.common_timeout)
                val unavailableMsg = stringResource(R.string.common_unavailable)
                val ipv6OnlyMsg = stringResource(R.string.common_ipv6_only)
                val pingText = PingDisplayText.resolve(
                    isConnected = isConnected,
                    displayPing = displayPing,
                    timeoutText = timeoutMsg,
                    unavailableText = unavailableMsg,
                    ipv6OnlyText = ipv6OnlyMsg
                )
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
                Spacer(modifier = Modifier.height(16.dp))

                // Quick Actions Card
                StandardCard(modifier = Modifier.fillMaxWidth(), border = null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QuickActionButton(
                            Icons.Rounded.Refresh,
                            stringResource(R.string.dashboard_update_subscription)
                        ) {
                            showUpdateDialog = true
                        }
                        QuickActionButton(
                            Icons.Rounded.Bolt,
                            stringResource(R.string.dashboard_latency_test)
                        ) {
                            showTestDialog = true
                        }
                        QuickActionButton(
                            Icons.Rounded.Terminal,
                            stringResource(R.string.dashboard_logs)
                        ) {
                            navController.navigate(Screen.Logs.route)
                        }
                        QuickActionButton(
                            Icons.Rounded.BugReport,
                            stringResource(R.string.dashboard_diagnostics)
                        ) {
                            navController.navigate(Screen.Diagnostics.route)
                        }
                    }
                }
            }
        }

        // Foreground Decoration - 3D Box Cage (Front Face)
        // Drawn on top of Column to create "Trapped" effect
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val center = center
            val baseSize = size.minDimension * 0.55f * pulseScale

            val baseAlpha = if (isRunning) pulseAlpha * 0.5f else 0.15f
            val color = if (useLiquidGlass) {
                primaryColor.copy(alpha = if (isDark) baseAlpha * 1.5f else baseAlpha * 1.6f)
            } else {
                defaultBaseColor.copy(alpha = baseAlpha)
            }
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
        IconButton(
            modifier = Modifier.liquidGlassIconButtonPanel(),
            onClick = onClick
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = liquidGlassStrongContentColor(MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
}
