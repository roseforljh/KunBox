package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.kunk.singbox.model.UpdateStatus
import com.kunk.singbox.ui.scanner.QrScannerActivity
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.ProfileCard
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.utils.DeepLinkHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun ProfilesScreen(
    navController: NavController,
    viewModel: com.kunk.singbox.viewmodel.ProfilesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()

    var showSearchDialog by remember { mutableStateOf(false) }
    var showImportSelection by remember { mutableStateOf(false) }
    var showSubscriptionInput by remember { mutableStateOf(false) }
    var showClipboardInput by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<com.kunk.singbox.model.ProfileUi?>(null) }

    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.toastEvents.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // 监听深度链接导入事件 (URL Scheme)
    val pendingImport by DeepLinkHandler.pendingSubscriptionImport.collectAsState()
    androidx.compose.runtime.LaunchedEffect(pendingImport) {
        pendingImport?.let { data ->
            // 自动触发订阅导入
            viewModel.importSubscription(
                name = data.name,
                url = data.url,
                autoUpdateInterval = data.autoUpdateInterval
            )
            // 清除待处理数据
            DeepLinkHandler.clearPendingSubscriptionImport()
        }
    }

    // Handle update state feedback
    androidx.compose.runtime.LaunchedEffect(updateStatus) {
        updateStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    val importSuccessMsg = stringResource(R.string.profiles_import_success)
    val importFailedMsg = stringResource(R.string.profiles_import_failed)

    // Handle import state feedback
    androidx.compose.runtime.LaunchedEffect(importState) {
        when (val state = importState) {
            is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Success -> {
                Toast.makeText(context, context.getString(R.string.profiles_import_success, state.profile.name), Toast.LENGTH_SHORT).show()
                viewModel.resetImportState()
            }
            is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Error -> {
                Toast.makeText(context, context.getString(R.string.profiles_import_failed, state.message), Toast.LENGTH_LONG).show()
                viewModel.resetImportState()
            }
            // Loading state is now handled by ImportLoadingDialog
            else -> {}
        }
    }

    if (importState is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Loading) {
        ImportLoadingDialog(
            message = (importState as com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Loading).message,
            onCancel = { viewModel.cancelImport() }
        )
    }

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

    // 文件选择器 Launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                                reader.readText()
                            }
                        } ?: ""
                    }

                    if (content.isNotBlank()) {
                        // 从 URI 中提取文件名作为配置名称
                        val fileName = uri.lastPathSegment?.let { segment ->
                            // 清理文件名，移除路径和扩展名
                            segment.substringAfterLast("/")
                                .substringAfterLast(":")
                                .substringBeforeLast(".")
                                .takeIf { it.isNotBlank() }
                        } ?: context.getString(R.string.profiles_file_import)

                        viewModel.importFromContent(fileName, content)
                    } else {
                        Toast.makeText(context, context.getString(R.string.profiles_file_empty), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.profiles_read_file_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 二维码扫描 Launcher
    val qrCodeLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        if (result.contents != null) {
            val scannedContent = result.contents
            // 检查扫描结果是否为有效的节点链接或配置
            val isNodeLink = scannedContent.let {
                it.startsWith("vmess://") || it.startsWith("vless://") ||
                    it.startsWith("ss://") || it.startsWith("ssr://") ||
                    it.startsWith("trojan://") || it.startsWith("hysteria://") ||
                    it.startsWith("hysteria2://") || it.startsWith("hy2://") ||
                    it.startsWith("tuic://") || it.startsWith("wireguard://") ||
                    it.startsWith("ssh://") || it.startsWith("anytls://")
            }

            val isSubscriptionUrl = scannedContent.startsWith("http://") ||
                scannedContent.startsWith("https://")

            when {
                isNodeLink -> {
                    // 单节点链接，使用剪贴板导入方式
                    viewModel.importFromContent(context.getString(R.string.profiles_qrcode_import), scannedContent)
                }
                isSubscriptionUrl -> {
                    // 订阅链接，导入为订阅
                    viewModel.importSubscription(context.getString(R.string.profiles_qrcode_subscription), scannedContent, 0)
                }
                scannedContent.trim().startsWith("{") || scannedContent.trim().startsWith("proxies:") -> {
                    // 看起来像 JSON 或 YAML 配置
                    viewModel.importFromContent(context.getString(R.string.profiles_qrcode_import), scannedContent)
                }
                else -> {
                    // 尝试作为节点链接列表处理（可能是 base64 编码的）
                    viewModel.importFromContent(context.getString(R.string.profiles_qrcode_import), scannedContent)
                }
            }
        }
    }

    // 创建扫描选项的辅助函数
    fun createScanOptions(): ScanOptions {
        return ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("") // 我们在自定义布局中显示提示
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
            setCaptureActivity(QrScannerActivity::class.java) // 使用自定义正方形扫描框
        }
    }

    // 相机权限请求 Launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 权限已授予，启动扫描
            qrCodeLauncher.launch(createScanOptions())
        } else {
            Toast.makeText(context, context.getString(R.string.profiles_camera_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    if (showImportSelection) {
        ImportSelectionDialog(
            onDismiss = { showImportSelection = false },
            onTypeSelected = { type ->
                showImportSelection = false
                when (type) {
                    ProfileImportType.Subscription -> showSubscriptionInput = true
                    ProfileImportType.Clipboard -> showClipboardInput = true
                    ProfileImportType.File -> {
                        // 启动文件选择器
                        filePickerLauncher.launch(arrayOf(
                            "application/json",
                            "text/plain",
                            "application/x-yaml",
                            "text/yaml",
                            "*/*" // 允许选择任意文件类型
                        ))
                    }
                    ProfileImportType.QRCode -> {
                        // 检查相机权限
                        when {
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED -> {
                                // 已有权限，直接启动扫描
                                qrCodeLauncher.launch(createScanOptions())
                            }
                            else -> {
                                // 请求权限
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    }
                }
            }
        )
    }

    if (showSubscriptionInput) {
        SubscriptionInputDialog(
            onDismiss = { showSubscriptionInput = false },
            onConfirm = { name, url, autoUpdateInterval ->
                viewModel.importSubscription(name, url, autoUpdateInterval)
                showSubscriptionInput = false
            }
        )
    }

    if (showClipboardInput) {
        val clipboardEmptyMsg = stringResource(R.string.profiles_clipboard_empty)
        val nameInvalidMsg = stringResource(R.string.profiles_name_invalid)
        val defaultClipboardName = stringResource(R.string.profiles_clipboard_import)

        InputDialog(
            title = stringResource(R.string.profiles_import_clipboard),
            placeholder = stringResource(R.string.profiles_import_clipboard_hint),
            initialValue = "",
            confirmText = stringResource(R.string.common_import),
            onConfirm = { name ->
                if (name.contains("://")) {
                    Toast.makeText(context, nameInvalidMsg, Toast.LENGTH_SHORT).show()
                    return@InputDialog
                }

                val content = clipboardManager.getText()?.text ?: ""
                if (content.isNotBlank()) {
                    viewModel.importFromContent(if (name.isBlank()) defaultClipboardName else name, content)
                    showClipboardInput = false
                } else {
                    Toast.makeText(context, clipboardEmptyMsg, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showClipboardInput = false }
        )
    }

    if (showSearchDialog) {
        InputDialog(
            title = stringResource(R.string.profiles_search),
            placeholder = stringResource(R.string.profiles_search_hint),
            confirmText = stringResource(R.string.common_search),
            onConfirm = { showSearchDialog = false },
            onDismiss = { showSearchDialog = false }
        )
    }

    if (editingProfile != null) {
        val profile = editingProfile!!
        SubscriptionInputDialog(
            initialName = profile.name,
            initialUrl = profile.url ?: "",
            initialAutoUpdateInterval = profile.autoUpdateInterval,
            title = stringResource(R.string.profiles_edit_profile),
            onDismiss = { editingProfile = null },
            onConfirm = { name, url, autoUpdateInterval ->
                viewModel.updateProfileMetadata(profile.id, name, url, autoUpdateInterval)
                editingProfile = null
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            AnimatedVisibility(
                visible = isFabVisible,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                FloatingActionButton(
                    onClick = { showImportSelection = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add Profile")
                }
            }
        }
    ) { padding ->
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
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
                .padding(top = statusBarPadding.calculateTopPadding())
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.profiles_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(onClick = { showSearchDialog = true }) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onBackground)
                }
            }

            // List
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(profiles, key = { _, profile -> profile.id }) { index, profile ->
                    var visible by remember { mutableStateOf(false) }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (index < 15) {
                            delay(index * 30L)
                        }
                        visible = true
                    }

                    val alpha by animateFloatAsState(
                        targetValue = if (visible) 1f else 0f,
                        animationSpec = tween(durationMillis = 300),
                        label = "alpha"
                    )
                    val translateY by animateFloatAsState(
                        targetValue = if (visible) 0f else 40f,
                        animationSpec = tween(durationMillis = 300),
                        label = "translateY"
                    )

                    ProfileCard(
                        name = profile.name,
                        type = profile.type.name,
                        isSelected = profile.id == activeProfileId,
                        isEnabled = profile.enabled,
                        isUpdating = profile.updateStatus == UpdateStatus.Updating,
                        updateStatus = profile.updateStatus,
                        expireDate = profile.expireDate,
                        totalTraffic = profile.totalTraffic,
                        usedTraffic = profile.usedTraffic,
                        lastUpdated = profile.lastUpdated,
                        onClick = { viewModel.setActiveProfile(profile.id) },
                        onUpdate = {
                            viewModel.updateProfile(profile.id)
                        },
                        onToggle = {
                            viewModel.toggleProfileEnabled(profile.id)
                        },
                        onEdit = {
                            if (profile.type == com.kunk.singbox.model.ProfileType.Subscription ||
                                profile.type == com.kunk.singbox.model.ProfileType.Imported) {
                                editingProfile = profile
                            } else {
                                navController.navigate(Screen.ProfileEditor.route)
                            }
                        },
                        onDelete = {
                            viewModel.deleteProfile(profile.id)
                        },
                        modifier = Modifier.graphicsLayer(
                            alpha = alpha,
                            translationY = translateY
                        )
                    )
                }
            }
        }
    }
}

private enum class ProfileImportType { Subscription, File, Clipboard, QRCode }

@Composable
private fun ImportSelectionDialog(
    onDismiss: () -> Unit,
    onTypeSelected: (ProfileImportType) -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ImportOptionCard(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.profiles_subscription_link),
                subtitle = stringResource(R.string.common_import),
                onClick = { onTypeSelected(ProfileImportType.Subscription) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.Description,
                title = stringResource(R.string.profiles_local_file),
                subtitle = stringResource(R.string.profiles_local_file_subtitle),
                onClick = { onTypeSelected(ProfileImportType.File) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.ContentPaste,
                title = stringResource(R.string.profiles_clipboard),
                subtitle = stringResource(R.string.profiles_clipboard_subtitle),
                onClick = { onTypeSelected(ProfileImportType.Clipboard) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.QrCodeScanner,
                title = stringResource(R.string.profiles_scan_qrcode),
                subtitle = stringResource(R.string.profiles_scan_qrcode_subtitle),
                onClick = { onTypeSelected(ProfileImportType.QRCode) }
            )
        }
    }
}

@Composable
private fun ImportLoadingDialog(message: String, onCancel: () -> Unit = {}) {
    // 尝试解析进度信息 (例如 "正在提取节点 (50/1000)...")
    var displayMessage = message
    val progress = remember(message) {
        val regex = Regex(".*?\\((\\d+)/(\\d+)\\).*")
        val match = regex.find(message)
        if (match != null) {
            val (current, total) = match.destructured
            val totalFloat = total.toFloat()
            if (totalFloat > 0) {
                current.toFloat() / totalFloat
            } else {
                null
            }
        } else {
            null
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (progress != null) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            } else {
                androidx.compose.material3.CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ImportOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    StandardCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SubscriptionInputDialog(
    initialName: String = "",
    initialUrl: String = "",
    initialAutoUpdateInterval: Int = 0,
    title: String = stringResource(R.string.profiles_add_subscription),
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var autoUpdateEnabled by remember { mutableStateOf(initialAutoUpdateInterval > 0) }
    var autoUpdateMinutes by remember { mutableStateOf(if (initialAutoUpdateInterval > 0) initialAutoUpdateInterval.toString() else "60") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.profiles_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.profiles_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 自动更新开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.profiles_auto_update),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                androidx.compose.material3.Switch(
                    checked = autoUpdateEnabled,
                    onCheckedChange = { autoUpdateEnabled = it },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            // 自动更新间隔输入框（带动画显示/隐藏）
            AnimatedVisibility(
                visible = autoUpdateEnabled,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 300)
                ),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 300)
                )
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    androidx.compose.material3.OutlinedTextField(
                        value = autoUpdateMinutes,
                        onValueChange = { newValue ->
                            // 只允许输入数字
                            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                autoUpdateMinutes = newValue
                            }
                        },
                        label = { Text(stringResource(R.string.profiles_auto_update_interval)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        supportingText = {
                            Text(
                                text = stringResource(R.string.profiles_auto_update_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            val context = LocalContext.current

            Button(
                onClick = {
                    // 校验订阅链接是否为单节点链接
                    val isNodeLink = url.trim().let {
                        it.startsWith("vmess://") || it.startsWith("vless://") ||
                            it.startsWith("ss://") || it.startsWith("ssr://") ||
                            it.startsWith("trojan://") || it.startsWith("hysteria://") ||
                            it.startsWith("hysteria2://") || it.startsWith("hy2://") ||
                            it.startsWith("tuic://") || it.startsWith("bean://") ||
                            it.startsWith("wireguard://") || it.startsWith("ssh://")
                    }

                    if (isNodeLink) {
                        Toast.makeText(context,
                            context.getString(R.string.profiles_subscription_node_warning),
                            Toast.LENGTH_LONG).show()
                        return@Button
                    }

                    // 校验名称是否非法（看起来像链接）
                    if (name.contains("://")) {
                        Toast.makeText(context, context.getString(R.string.profiles_name_invalid), Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    // 计算最终的自动更新间隔
                    val finalInterval = if (autoUpdateEnabled) {
                        val minutes = autoUpdateMinutes.toIntOrNull() ?: 0
                        if (minutes < 15) {
                            Toast.makeText(context, context.getString(R.string.settings_update_interval_min), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        minutes
                    } else {
                        0
                    }

                    onConfirm(name, url, finalInterval)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text(stringResource(R.string.common_ok), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }

            Spacer(modifier = Modifier.height(8.dp))

            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}
