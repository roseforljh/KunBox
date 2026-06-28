package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.zIndex
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.UpdateStatus
import com.kunk.singbox.ui.scanner.QrScannerActivity
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.ProfileCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.LiquidGlassFloatingActionButton
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassFloatingActionContainerColor
import com.kunk.singbox.ui.theme.liquidGlassFloatingActionContentColor
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassScreenContainerColor
import com.kunk.singbox.utils.DeepLinkHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

@Composable
private fun Modifier.profileSortItemPressFeedback(
    enabled: Boolean,
    onClick: () -> Unit
): Modifier {
    val useLiquidGlass = isLiquidGlassTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (useLiquidGlass && enabled && isPressed) 0.98f else 1f,
        animationSpec = spring(stiffness = 520f, dampingRatio = 0.72f),
        label = "liquid_glass_profile_sort_item_scale"
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

private suspend fun readImportContentSafely(
    context: android.content.Context,
    uri: Uri,
    maxBytes: Int
): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream()
        var totalBytes = 0

        while (true) {
            val read = inputStream.read(buffer)
            if (read <= 0) break
            totalBytes += read
            require(totalBytes <= maxBytes) {
                context.getString(R.string.profiles_import_content_too_large)
            }
            output.write(buffer, 0, read)
        }

        output.toString(Charsets.UTF_8.name())
    } ?: ""
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "SwallowedException")
fun ProfilesScreen(
    navController: NavController,
    viewModel: com.kunk.singbox.viewmodel.ProfilesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    bottomContentPadding: Dp = 0.dp
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val allNodes by viewModel.allNodes.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()

    var showSearchDialog by remember { mutableStateOf(false) }
    var showImportSelection by remember { mutableStateOf(false) }
    var showSubscriptionInput by remember { mutableStateOf(false) }
    var showClipboardInput by remember { mutableStateOf(false) }
    var showCustomConfigInput by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<com.kunk.singbox.model.ProfileUi?>(null) }

    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val commonLoadingMessage = stringResource(R.string.common_loading)
    val importContentTooLargeMessage = stringResource(R.string.profiles_import_content_too_large)
    val fileImportName = stringResource(R.string.profiles_file_import)
    val fileEmptyMessage = stringResource(R.string.profiles_file_empty)
    val readFileFailedFormat = stringResource(R.string.profiles_read_file_failed, "%s")
    val qrcodeImportName = stringResource(R.string.profiles_qrcode_import)
    val qrcodeSubscriptionName = stringResource(R.string.profiles_qrcode_subscription)
    val cameraPermissionRequiredMessage = stringResource(R.string.profiles_camera_permission_required)

    // Reordering state
    val profileList = remember { mutableStateListOf<com.kunk.singbox.model.ProfileUi>() }
    val isDragging = remember { mutableStateOf(false) }
    var suppressPlacementAnimation by remember { mutableStateOf(false) }
    val enablePlacementAnimation = false

    androidx.compose.runtime.LaunchedEffect(profiles) {
        if (!isDragging.value) {
            val currentIds = profileList.map { it.id }.toSet()
            val newIds = profiles.map { it.id }.toSet()
            if (currentIds != newIds || profileList.size != profiles.size || profileList.isEmpty()) {
                profileList.clear()
                profileList.addAll(profiles)
            } else {
                profiles.forEach { newProfile ->
                    val index = profileList.indexOfFirst { it.id == newProfile.id }
                    if (index != -1 && profileList[index] != newProfile) {
                        profileList[index] = newProfile
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

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.toastEvents.collectLatest { message ->
            AppNotificationManager.showMessage(context, message)
        }
    }

    val pendingImport by DeepLinkHandler.pendingSubscriptionImport.collectAsStateWithLifecycle()

    pendingImport?.let { data ->
        ConfirmDialog(
            title = stringResource(R.string.profiles_deep_link_import_title),
            message = stringResource(
                R.string.profiles_deep_link_import_message,
                data.name,
                data.url
            ),
            confirmText = stringResource(R.string.common_import),
            onConfirm = {
                val started = viewModel.importSubscription(
                    name = data.name,
                    url = data.url,
                    autoUpdateInterval = data.autoUpdateInterval
                )
                if (started) {
                    DeepLinkHandler.clearPendingSubscriptionImport()
                } else {
                    AppNotificationManager.showMessage(context, commonLoadingMessage)
                }
            },
            onDismiss = { DeepLinkHandler.clearPendingSubscriptionImport() }
        )
    }

    // Handle update state feedback
    androidx.compose.runtime.LaunchedEffect(updateStatus) {
        updateStatus?.let {
            AppNotificationManager.showMessage(context, it)
        }
    }

    val importSuccessMessage = (importState as? com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Success)
        ?.let { stringResource(R.string.profiles_import_success, it.profile.name) }
    val importFailedMessage = (importState as? com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Error)
        ?.let { stringResource(R.string.profiles_import_failed, it.message) }

    // Handle import state feedback
    androidx.compose.runtime.LaunchedEffect(importState) {
        when (val state = importState) {
            is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Success -> {
                AppNotificationManager.showMessage(
                    context,
                    importSuccessMessage.orEmpty()
                )
                viewModel.resetImportState()
            }
            is com.kunk.singbox.viewmodel.ProfilesViewModel.ImportState.Error -> {
                AppNotificationManager.showMessage(
                    context = context,
                    message = importFailedMessage.orEmpty(),
                    duration = androidx.compose.material3.SnackbarDuration.Long
                )
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

    var lastY by remember { mutableStateOf(0f) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val declaredLength = withContext(Dispatchers.IO) {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                            descriptor.length
                        } ?: -1L
                    }
                    if (declaredLength > com.kunk.singbox.viewmodel.ProfilesViewModel.MAX_IMPORT_CONTENT_BYTES) {
                        AppNotificationManager.showMessage(
                            context,
                            importContentTooLargeMessage
                        )
                        return@launch
                    }

                    val content = readImportContentSafely(
                        context = context,
                        uri = uri,
                        maxBytes = com.kunk.singbox.viewmodel.ProfilesViewModel.MAX_IMPORT_CONTENT_BYTES
                    )

                    if (content.isNotBlank()) {

                        val fileName = uri.lastPathSegment?.let { segment ->

                            segment.substringAfterLast("/")
                                .substringAfterLast(":")
                                .substringBeforeLast(".")
                                .takeIf { it.isNotBlank() }
                        } ?: fileImportName

                        viewModel.importFromContent(fileName, content)
                    } else {
                        AppNotificationManager.showMessage(context, fileEmptyMessage)
                    }
                } catch (e: Exception) {
                    AppNotificationManager.showMessage(
                        context = context,
                        message = String.format(Locale.getDefault(), readFileFailedFormat, e.message),
                        duration = androidx.compose.material3.SnackbarDuration.Long
                    )
                }
            }
        }
    }

    val qrCodeLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        if (result.contents != null) {
            val scannedContent = result.contents

            val isNodeLink = scannedContent.let {
                it.startsWith("vmess://") || it.startsWith("vless://") ||
                    it.startsWith("ss://") || it.startsWith("ssr://") ||
                    it.startsWith("trojan://") || it.startsWith("hysteria://") ||
                    it.startsWith("hysteria2://") || it.startsWith("hy2://") ||
                    it.startsWith("tuic://") || it.startsWith("wireguard://") ||
                    it.startsWith("ssh://") || it.startsWith("anytls://") ||
                    it.startsWith("naive://") || it.startsWith("naive+https://")
            }

            val isSubscriptionUrl = scannedContent.startsWith("http://") ||
                scannedContent.startsWith("https://")

            when {
                isNodeLink -> {
                    viewModel.importFromContent(qrcodeImportName, scannedContent)
                }
                isSubscriptionUrl -> {
                    val urlParts = scannedContent.split("#")
                    val parsedName = if (urlParts.size > 1 && urlParts[1].isNotBlank()) {
                        try {
                            java.net.URLDecoder.decode(urlParts[1], "UTF-8")
                        } catch (e: Exception) {
                            urlParts[1]
                        }
                    } else {
                        null
                    }
                    val name = parsedName ?: qrcodeSubscriptionName
                    viewModel.importSubscription(name, scannedContent, 0)
                }
                scannedContent.trim().startsWith("{") || scannedContent.trim().startsWith("proxies:") -> {

                    viewModel.importFromContent(qrcodeImportName, scannedContent)
                }
                else -> {

                    viewModel.importFromContent(qrcodeImportName, scannedContent)
                }
            }
        }
    }

    fun createScanOptions(): ScanOptions {
        return ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
            setCaptureActivity(QrScannerActivity::class.java) // custom scanner activity with transparent status bar
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            qrCodeLauncher.launch(createScanOptions())
        } else {
            AppNotificationManager.showMessage(context, cameraPermissionRequiredMessage)
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
                    ProfileImportType.Custom -> showCustomConfigInput = true
                    ProfileImportType.File -> {

                        filePickerLauncher.launch(arrayOf(
                            "application/json",
                            "text/plain",
                            "application/x-yaml",
                            "text/yaml",
                            "*/*"
                        ))
                    }
                    ProfileImportType.QRCode -> {

                        when {
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED -> {

                                qrCodeLauncher.launch(createScanOptions())
                            }
                            else -> {

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
            onConfirm = { name, url, autoUpdateInterval, dnsPreResolve, dnsServer, dnsOverride ->
                viewModel.importSubscription(name, url, autoUpdateInterval, dnsPreResolve, dnsServer, dnsOverride)
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
                    AppNotificationManager.showMessage(context, nameInvalidMsg)
                } else {
                    val content = clipboardManager.getText()?.text ?: ""
                    if (content.isNotBlank()) {
                        viewModel.importFromContent(if (name.isBlank()) defaultClipboardName else name, content)
                        showClipboardInput = false
                    } else {
                        AppNotificationManager.showMessage(context, clipboardEmptyMsg)
                    }
                }
            },
            onDismiss = { showClipboardInput = false }
        )
    }

    if (showCustomConfigInput) {
        DisposableEffect(Unit) {
            viewModel.setAllNodesUiActive(true)
            onDispose {
                viewModel.setAllNodesUiActive(false)
            }
        }

        val subscriptionProfileNames = remember(profiles) {
            profiles
                .filter { it.type == ProfileType.Subscription }
                .associate { it.id to it.name }
        }
        val selectableNodes = remember(allNodes, subscriptionProfileNames) {
            allNodes
                .filter { subscriptionProfileNames.containsKey(it.sourceProfileId) }
                .sortedWith(compareBy<NodeUi>({ subscriptionProfileNames[it.sourceProfileId] ?: "" }, { it.name }))
        }

        CustomConfigDialog(
            nodes = selectableNodes,
            profileNames = subscriptionProfileNames,
            onDismiss = { showCustomConfigInput = false },
            onConfirm = { name, selectedNodeIds ->
                viewModel.createCustomConfig(name, selectedNodeIds)
                showCustomConfigInput = false
            }
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
        val profile = checkNotNull(editingProfile)
        SubscriptionInputDialog(
            initialName = profile.name,
            initialUrl = profile.url ?: "",
            initialAutoUpdateInterval = profile.autoUpdateInterval,
            initialDnsPreResolve = profile.dnsPreResolve,
            initialDnsServer = profile.dnsServer,
            initialDnsOverride = profile.dnsOverride,
            title = stringResource(R.string.profiles_edit_profile),
            onDismiss = { editingProfile = null },
            onConfirm = { name, url, autoUpdateInterval, dnsPreResolve, dnsServer, dnsOverride ->
                viewModel.updateProfileMetadata(
                    profile.id,
                    name,
                    url,
                    autoUpdateInterval,
                    dnsPreResolve,
                    dnsServer,
                    dnsOverride
                )
                editingProfile = null
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = liquidGlassScreenContainerColor(MaterialTheme.colorScheme.background),
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
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
                    IconButton(
                        modifier = Modifier.liquidGlassIconButtonPanel(),
                        onClick = { showSearchDialog = true }
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
                // List
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp + bottomContentPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(profileList.size, key = { profileList[it].id }) { index ->
                        val profile = profileList[index]
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

                        val isDraggingItem = draggingItemIndex == index
                        val isSettlingItem = settlingItemId == profile.id
                        val isCurrentlyDragging = isDragging.value
                        val canDisplace = isCurrentlyDragging &&
                            draggingItemIndex != null &&
                            itemHeightPx > 0 &&
                            !isDraggingItem

                        var translationY = if (visible) 0f else 40f
                        if (canDisplace) {
                            val startIdx = draggingItemIndex ?: index
                            val dragProgress = draggingItemOffset / itemHeightPx
                            val rawEndProgress = when {
                                dragProgress > 0f -> kotlin.math.ceil(dragProgress)
                                dragProgress < 0f -> kotlin.math.floor(dragProgress)
                                else -> 0.0
                            }
                            val clampedStart = startIdx.coerceIn(0, profileList.lastIndex)
                            val clampedEnd = (startIdx + rawEndProgress.toInt()).coerceIn(0, profileList.lastIndex)
                            when {
                                clampedStart < clampedEnd && index > clampedStart && index <= clampedEnd -> {
                                    val itemSlotOffset = index - startIdx
                                    translationY = -(dragProgress - (itemSlotOffset - 1)) * itemHeightPx
                                    translationY = translationY.coerceIn(-itemHeightPx, 0f)
                                }
                                clampedStart > clampedEnd && index < clampedStart && index >= clampedEnd -> {
                                    val itemSlotOffset = startIdx - index
                                    translationY = (-dragProgress - (itemSlotOffset - 1)) * itemHeightPx
                                    translationY = translationY.coerceIn(0f, itemHeightPx)
                                }
                            }
                        }

                        val dragScale by animateFloatAsState(
                            targetValue = when {
                                isDraggingItem && isCurrentlyDragging -> 1.02f
                                isSettlingItem -> 1.01f
                                else -> 1f
                            },
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 260f),
                            label = "dragScale"
                        )
                        val dragShadow by animateFloatAsState(
                            targetValue = when {
                                isDraggingItem && isCurrentlyDragging -> 8f
                                isSettlingItem -> 4f
                                else -> 2f
                            },
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 260f),
                            label = "dragShadow"
                        )
                        val dragAlpha by animateFloatAsState(
                            targetValue = when {
                                isDraggingItem && isCurrentlyDragging -> 0.94f
                                isSettlingItem -> 0.98f
                                else -> 1f
                            },
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = 280f),
                            label = "dragAlpha"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDraggingItem && isCurrentlyDragging) 1f else 0f)
                                .onGloballyPositioned { coordinates ->
                                    if (itemHeightPx == 0f) {
                                        val spacingPx = with(density) { 12.dp.toPx() }
                                        itemHeightPx = coordinates.size.height.toFloat() + spacingPx
                                    }
                                }
                                .graphicsLayer {
                                    this.translationY = if (isDraggingItem) draggingItemOffset else translationY
                                    this.alpha = alpha * dragAlpha
                                    scaleX = dragScale
                                    scaleY = dragScale
                                    shadowElevation = dragShadow
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                }
                                .then(
                                    if (!enablePlacementAnimation || suppressPlacementAnimation) {
                                        Modifier
                                    } else {
                                        Modifier.animateItem()
                                    }
                                )
                                .profileSortItemPressFeedback(
                                    enabled = !isDraggingItem || !isCurrentlyDragging
                                ) {
                                    viewModel.setActiveProfile(profile.id)
                                }
                                .pointerInput(index) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingItemIndex = index
                                            draggingItemId = profile.id
                                            draggingItemOffset = 0f
                                            isDragging.value = true
                                            haptic.performHapticFeedback(
                                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                            )
                                        },
                                        onDragEnd = {
                                            draggingItemIndex?.let { startIdx ->
                                                val dist = if (itemHeightPx > 0f) {
                                                    kotlin.math.round(draggingItemOffset / itemHeightPx).toInt()
                                                } else {
                                                    0
                                                }
                                                val endIdx = (startIdx + dist).coerceIn(0, profileList.lastIndex)

                                                val settledProfileId = profile.id
                                                settlingItemId = settledProfileId
                                                suppressPlacementAnimation = true
                                                val absScrollBefore = if (itemHeightPx > 0f) {
                                                    listState.firstVisibleItemIndex * itemHeightPx +
                                                        listState.firstVisibleItemScrollOffset
                                                } else {
                                                    null
                                                }

                                                if (startIdx != endIdx) {
                                                    val item = profileList.removeAt(startIdx)
                                                    profileList.add(endIdx, item)
                                                    viewModel.reorderProfiles(profileList.toList())
                                                }

                                                val abs = absScrollBefore
                                                if (abs != null && itemHeightPx > 0f) {
                                                    val targetIndex = (abs / itemHeightPx).toInt()
                                                        .coerceIn(0, profileList.lastIndex)
                                                    val targetOffset = (abs - targetIndex * itemHeightPx)
                                                        .toInt()
                                                        .coerceAtLeast(0)
                                                    scope.launch {
                                                        listState.scrollToItem(targetIndex, targetOffset)
                                                    }
                                                }

                                                draggingItemIndex = null
                                                draggingItemOffset = 0f
                                                draggingItemId = null
                                                isDragging.value = false

                                                scope.launch {
                                                    androidx.compose.runtime.withFrameNanos { }
                                                    suppressPlacementAnimation = false
                                                }
                                                scope.launch {
                                                    delay(220)
                                                    if (settlingItemId == settledProfileId) {
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
                            ProfileCard(
                                name = profile.name,
                                type = profile.type.name,
                                isSelected = profile.id == activeProfileId,
                                isEnabled = profile.enabled,
                                isUpdating = profile.updateStatus == UpdateStatus.Updating &&
                                    profile.updateStage?.isBackground != true,
                                updateStatus = profile.updateStatus,
                                updateStage = profile.updateStage,
                                expireDate = profile.expireDate,
                                totalTraffic = profile.totalTraffic,
                                usedTraffic = profile.usedTraffic,
                                lastUpdated = profile.lastUpdated,
                                dnsPreResolve = profile.dnsPreResolve,
                                onClick = { viewModel.setActiveProfile(profile.id) },
                                onUpdate = { viewModel.updateProfile(profile.id) },
                                onToggle = { viewModel.toggleProfileEnabled(profile.id) },
                                onEdit = {
                                    if (profile.type == com.kunk.singbox.model.ProfileType.Subscription ||
                                        profile.type == com.kunk.singbox.model.ProfileType.Imported) {
                                        editingProfile = profile
                                    } else {
                                        navController.navigate(Screen.ProfileEditor.createRoute(profile.id))
                                    }
                                },
                                onDelete = { viewModel.deleteProfile(profile.id) }
                            )
                        }
                    }
                }
            }
        }

        val fabAlpha by animateFloatAsState(
            targetValue = if (isFabVisible) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "fabAlpha"
        )

        if (fabAlpha > 0f) {
            LiquidGlassFloatingActionButton(
                onClick = { showImportSelection = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp + bottomContentPadding)
                    .graphicsLayer {
                        alpha = fabAlpha
                        translationY = (1f - fabAlpha) * 15.dp.toPx()
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                containerColor = liquidGlassFloatingActionContainerColor(MaterialTheme.colorScheme.primary),
                contentColor = liquidGlassFloatingActionContentColor(MaterialTheme.colorScheme.onPrimary)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Add Profile")
            }
        }
    }
}
