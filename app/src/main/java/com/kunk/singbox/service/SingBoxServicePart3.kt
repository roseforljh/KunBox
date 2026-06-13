package com.kunk.singbox.service

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.utils.L
import com.kunk.singbox.utils.NetworkClient
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

@Suppress("TooManyFunctions")
abstract class SingBoxServicePart3 : SingBoxServicePart2() {
    protected override suspend fun restartVpnService(reason: String) = withContext(Dispatchers.Main) {
        L.vpn("Restart", "Restarting: $reason")

        // 保存当前配置路径
        val configPath = SingBoxService.lastConfigPath ?: run {
            L.warn("Restart", "Cannot restart: no config path")
            return@withContext
        }

        try {
            // 停止当前服务 (不停止 Service 本身)
            stopVpn(stopService = false)

            // 等待完全停止
            var waitCount = 0
            while (isStopping && waitCount < 50) {
                delay(100)
                waitCount++
            }

            // 短暂延迟确保资源完全释放
            delay(500)

            // 重新启动
            startVpn(configPath)

            L.result("Restart", true, "VPN restarted")
        } catch (e: Exception) {
            L.error("Restart", "Failed to restart VPN", e)
            SingBoxService.setLastError("Failed to restart VPN: ${e.message}")
        }
    }

    // 屏幕/前台状态从 ScreenStateManager 读取

    protected override fun findBestPhysicalNetwork(): Network? {
        // 优先使用 ConnectManager (新架构)
        connectManager.getCurrentNetwork()?.let { return it }
        // 回退到 NetworkManager
        networkManager?.findBestPhysicalNetwork()?.let { return it }
        // 当 networkManager 为 null 时（服务重启期间），使用 NetworkHelper 的回退逻辑
        return networkHelper.findBestPhysicalNetworkFallback()
    }

    protected override fun updateDefaultInterface(network: Network) {
        networkHelper.updateDefaultInterface(
            network = network,
            vpnStartedAtMs = vpnStartedAtMs.get(),
            startupWindowMs = vpnStartupWindowMs,
            defaultInterfaceName = defaultInterfaceName,
            lastKnownNetwork = lastKnownNetwork,
            lastSetUnderlyingAtMs = lastSetUnderlyingNetworksAtMs.get(),
            debounceMs = setUnderlyingNetworksDebounceMs,
            isRunning = SingBoxService.isRunning,
            setUnderlyingNetworks = { networks -> setUnderlyingNetworks(networks) },
            updateInterfaceListener = { name, index, expensive, constrained ->
                currentInterfaceListener?.updateDefaultInterface(name, index, expensive, constrained)
            },
            updateState = { net, iface, now ->
                lastKnownNetwork = net
                defaultInterfaceName = iface
                lastSetUnderlyingNetworksAtMs.set(now)
                noPhysicalNetworkWarningLogged = false
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.e(SingBoxService.TAG, "SingBoxService onCreate: pid=${android.os.Process.myPid()} SingBoxService.instance=${System.identityHashCode(this)}")
        SingBoxService.instance = this as SingBoxService

        // Restore manually stopped state from persistent storage
        SingBoxService.isManuallyStopped = VpnStateStore.isManuallyStopped()
        Log.i(SingBoxService.TAG, "Restored SingBoxService.isManuallyStopped state: $SingBoxService.isManuallyStopped")

        notificationManager.createNotificationChannel()
        // 初始化 ConnectivityManager
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // ===== 初始化新架构 Managers =====
        initManagers()

        serviceScope.launch {
            SingBoxService.lastErrorFlow.collect {
                requestRemoteStateUpdate(force = false)
            }
        }

        // 监听活动节点变化，更新通知
        serviceScope.launch {
            ConfigRepository.getInstance(this@SingBoxServicePart3).activeNodeId.collect { _ ->
                if (SingBoxService.isRunning) {
                    requestNotificationUpdate(force = false)
                    requestRemoteStateUpdate(force = false)
                }
            }
        }

        // 监听通知栏速度显示设置变化
        serviceScope.launch {
            SettingsRepository.getInstance(this@SingBoxServicePart3)
                .settings
                .map { it.showNotificationSpeed }
                .distinctUntilChanged()
                .collect { enabled ->
                    showNotificationSpeed = enabled
                    if (SingBoxService.isRunning) {
                        requestNotificationUpdate(force = true)
                    }
                }
        }

        // ⭐ P0修复3: 注册Activity生命周期回调，检测应用返回前台
        screenStateManager.registerActivityLifecycleCallbacks(application)
    }

    /**
     *
     */

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                screenStateManager.onAppBackground()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(SingBoxService.TAG, "onStartCommand action=${intent?.action}")
        runCatching {
            LogRepository.getInstance().addLog("INFO SingBoxService: onStartCommand action=${intent?.action}")
        }
        if (intent?.action == null) {
            handleStickyRestartIntent()
            return START_STICKY
        }
        when (intent?.action) {
            SingBoxService.ACTION_START -> {
                SingBoxService.isManuallyStopped = false
                VpnStateStore.setManuallyStopped(false)
                VpnTileService.persistVpnPending(applicationContext, "starting")

                // 性能优化: 预创建 TUN Builder (非阻塞)
                coreManager.preallocateTunBuilder()

                val configPath = intent.getStringExtra(SingBoxService.EXTRA_CONFIG_PATH)
                val pendingNode = intent.getStringExtra(SingBoxService.EXTRA_PENDING_NODE_NAME)
                if (!pendingNode.isNullOrBlank()) {
                    pendingNodeName = pendingNode
                    realTimeNodeName = null
                    VpnStateStore.setActiveLabel(pendingNode)
                    requestNotificationUpdate(force = true)
                    requestRemoteStateUpdate(force = true)
                }
                val cleanCache = intent.getBooleanExtra(SingBoxService.EXTRA_CLEAN_CACHE, false)

                // P0 Optimization: If config path is missing (Shortcut/Headless), generate it inside Service
                if (configPath == null) {
                    Log.i(SingBoxService.TAG, "SingBoxService.ACTION_START received without config path, generating config...")
                    serviceScope.launch {
                        try {
                            val repo = ConfigRepository.getInstance(applicationContext)
                            val result = repo.generateConfigFile()
                            if (result != null) {
                                Log.i(SingBoxService.TAG, "Config generated successfully: ${result.path}")
                                // Recursively call start command with the generated path
                                val newIntent = Intent(applicationContext, SingBoxService::class.java).apply {
                                    action = SingBoxService.ACTION_START
                                    putExtra(SingBoxService.EXTRA_CONFIG_PATH, result.path)
                                    putExtra(SingBoxService.EXTRA_CLEAN_CACHE, cleanCache)
                                }
                                startService(newIntent)
                            } else {
                                Log.e(SingBoxService.TAG, "Failed to generate config file")
                                SingBoxService.setLastError("Failed to generate config file")
                                withContext(Dispatchers.Main) {
                                    clearStartCommandFailureState()
                                    stopSelf()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(SingBoxService.TAG, "Error generating config in Service", e)
                            SingBoxService.setLastError("Error generating config: ${e.message}")
                            withContext(Dispatchers.Main) {
                                clearStartCommandFailureState()
                                stopSelf()
                            }
                        }
                    }
                    return START_STICKY
                }

                updateServiceState(ServiceState.STARTING)
                synchronized(this) {
                    // FIX: Ensure pendingCleanCache is set from intent even for cold start
                    if (cleanCache) pendingCleanCache = true

                    if (SingBoxService.isStarting) {
                        pendingStartConfigPath = configPath
                        stopSelfRequested = false
                        SingBoxService.lastConfigPath = configPath
                        // Return STICKY to allow system to restart VPN if killed due to memory pressure
                        return START_STICKY
                    }
                    if (isStopping) {
                        pendingStartConfigPath = configPath
                        stopSelfRequested = false
                        SingBoxService.lastConfigPath = configPath
                        // Return STICKY to allow system to restart VPN if killed due to memory pressure
                        return START_STICKY
                    }
                    // If already running, do a clean restart to avoid half-broken tunnel state
                    if (SingBoxService.isRunning) {
                        pendingStartConfigPath = configPath
                        stopSelfRequested = false
                        SingBoxService.lastConfigPath = configPath
                    }
                }
                if (SingBoxService.isRunning) {
                    val activeLabel = pendingNodeName ?: runCatching {
                        val repo = ConfigRepository.getInstance(applicationContext)
                        val nodeId = repo.activeNodeId.value
                        repo.nodes.value.find { it.id == nodeId }?.name
                    }.getOrNull()
                    realTimeNodeName = null
                    if (!activeLabel.isNullOrBlank()) {
                        VpnStateStore.setActiveLabel(activeLabel)
                    }
                    requestNotificationUpdate(force = true)
                    requestRemoteStateUpdate(force = true)
                    // 只有当需要更改核心配置（如路由规则、DNS 等）时才重启
                    stopVpn(stopService = false)
                } else {
                    startVpn(configPath)
                }
            }
            SingBoxService.ACTION_STOP -> {
                Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_STOP (manual) -> stopping VPN")
                SingBoxService.isManuallyStopped = true
                VpnStateStore.setManuallyStopped(true)
                VpnTileService.persistVpnPending(applicationContext, "stopping")
                updateServiceState(ServiceState.STOPPING)
                notificationManager.setSuppressUpdates(true)
                notificationManager.cancelNotification()
                synchronized(this) {
                    pendingStartConfigPath = null
                }
                stopVpn(stopService = true)
            }
            SingBoxService.ACTION_SWITCH_NODE -> {
                Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_SWITCH_NODE -> switching node")
                val targetNodeId = intent.getStringExtra("node_id")
                val outboundTag = intent.getStringExtra("outbound_tag")
                val targetNodeName = intent.getStringExtra(SingBoxService.EXTRA_PENDING_NODE_NAME)
                runCatching {
                    LogRepository.getInstance().addLog(
                        "INFO SingBoxService: SingBoxService.ACTION_SWITCH_NODE nodeId=${targetNodeId.orEmpty()} " +
                            "outboundTag=${outboundTag.orEmpty()} targetNodeName=${targetNodeName.orEmpty()}"
                    )
                }
                // Remember latest config path for fallback restart if hot switch doesn't apply.
                val fallbackConfigPath = intent.getStringExtra(SingBoxService.EXTRA_CONFIG_PATH)
                if (!fallbackConfigPath.isNullOrBlank()) {
                    synchronized(this) {
                        pendingHotSwitchFallbackConfigPath = fallbackConfigPath
                    }
                    runCatching {
                        LogRepository.getInstance().addLog("INFO SingBoxService: SWITCH_NODE fallback configPath=$fallbackConfigPath")
                    }
                }
                if (targetNodeId != null) {
                    nodeSwitchManager.performHotSwitch(
                        nodeId = targetNodeId,
                        outboundTag = outboundTag,
                        targetNodeName = targetNodeName,
                        serviceClass = SingBoxService::class.java,
                        actionStart = SingBoxService.ACTION_START,
                        extraConfigPath = SingBoxService.EXTRA_CONFIG_PATH
                    )
                } else {
                    nodeSwitchManager.switchNextNode(
                        serviceClass = SingBoxService::class.java,
                        actionStart = SingBoxService.ACTION_START,
                        extraConfigPath = SingBoxService.EXTRA_CONFIG_PATH
                    )
                }
            }
            SingBoxService.ACTION_UPDATE_SETTING -> {
                val key = intent.getStringExtra(SingBoxService.EXTRA_SETTING_KEY)
                if (key == "show_notification_speed") {
                    val value = intent.getBooleanExtra(SingBoxService.EXTRA_SETTING_VALUE_BOOL, true)
                    Log.i(SingBoxService.TAG, "Received setting update: $key = $value")
                    showNotificationSpeed = value
                    if (SingBoxService.isRunning) {
                        requestNotificationUpdate(force = true)
                    }
                }
            }
            SingBoxService.ACTION_PREPARE_RESTART -> {
                val reason = intent.getStringExtra(SingBoxService.EXTRA_PREPARE_RESTART_REASON).orEmpty()
                Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_PREPARE_RESTART (reason='$reason') -> preparing for VPN restart")
                performPrepareRestart()
            }
            SingBoxService.ACTION_HOT_RELOAD -> {
                // ⭐ 2025-fix: 内核级热重载
                // 在 VPN 运行时重载配置，不销毁 VPN 服务
                Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_HOT_RELOAD -> performing hot reload")
                val configContent = intent.getStringExtra(SingBoxService.EXTRA_CONFIG_CONTENT)
                if (configContent.isNullOrEmpty()) {
                    Log.e(SingBoxService.TAG, "SingBoxService.ACTION_HOT_RELOAD: config content is empty")
                } else {
                    performHotReload(configContent)
                }
            }
            SingBoxService.ACTION_FULL_RESTART -> {
                Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_FULL_RESTART -> performing full restart (TUN rebuild)")
                val configPath = intent.getStringExtra(SingBoxService.EXTRA_CONFIG_PATH)
                if (configPath.isNullOrEmpty()) {
                    Log.e(SingBoxService.TAG, "SingBoxService.ACTION_FULL_RESTART: config path is empty")
                } else {
                    performFullRestart(configPath)
                }
            }
            SingBoxService.ACTION_RESET_CONNECTIONS -> {
                Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_RESET_CONNECTIONS -> user requested connection reset")
                if (SingBoxService.isRunning) {
                    serviceScope.launch {
                        BoxWrapperManager.resetAllConnections(true)
                        runCatching {
                            LogRepository.getInstance().addLog("INFO: User triggered connection reset via notification")
                        }
                    }
                }
            }
            SingBoxService.ACTION_NETWORK_BUMP -> {
                Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_NETWORK_BUMP -> triggering network bump")
                if (SingBoxService.isRunning) {
                    serviceScope.launch {
                        BoxWrapperManager.closeIdleConnections(30)
                    }
                }
            }
        }
        // Use START_STICKY to allow system auto-restart if killed due to memory pressure
        // This prevents "VPN mysteriously stops" issue on Android 14+
        // System will restart service with null intent, we handle it gracefully above
        return START_STICKY
    }

    protected override fun handleStickyRestartIntent() {
        if (SingBoxService.isRunning || SingBoxService.isStarting) {
            Log.i(SingBoxService.TAG, "Sticky restart ignored: service is already running or starting")
            return
        }

        val runningConfigFile = File(filesDir, "running_config.json")
        val runningConfigUsable = SingBoxService.isRunningConfigUsable(runningConfigFile)
        val mode = VpnStateStore.getMode()
        val manuallyStopped = VpnStateStore.isManuallyStopped()
        if (!SingBoxService.shouldRecoverFromStickyRestart(manuallyStopped, mode, runningConfigUsable)) {
            Log.i(
                SingBoxService.TAG,
                "Sticky restart skipped: manuallyStopped=$manuallyStopped, mode=$mode, " +
                    "runningConfigUsable=$runningConfigUsable"
            )
            return
        }

        Log.w(SingBoxService.TAG, "Sticky restart recovering VPN from ${runningConfigFile.absolutePath}")
        SingBoxService.isManuallyStopped = false
        VpnStateStore.setManuallyStopped(false)
        VpnTileService.persistVpnPending(applicationContext, "starting")
        updateServiceState(ServiceState.STARTING)
        startVpn(runningConfigFile.absolutePath)
    }

    protected override fun clearStartCommandFailureState() {
        synchronized(this) {
            SingBoxService.isRunning = false
            SingBoxService.isStarting = false
            isStopping = false
            pendingStartConfigPath = null
            pendingCleanCache = false
            stopSelfRequested = false
        }
        NetworkClient.onVpnStateChanged(false)
        VpnTileService.persistVpnState(applicationContext, false)
        VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
        VpnTileService.persistVpnPending(applicationContext, "")
        updateServiceState(ServiceState.STOPPED)
        updateTileState()
    }

    /**
     * 执行预清理操作
     */

    protected override fun performPrepareRestart() {
        if (!SingBoxService.isRunning) {
            Log.w(SingBoxService.TAG, "performPrepareRestart: VPN not running, skip")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val last = lastPrepareRestartAtMs.get()
        val elapsed = now - last
        if (elapsed < prepareRestartDebounceMs) {
            Log.d(SingBoxService.TAG, "performPrepareRestart: skipped (debounce, elapsed=${elapsed}ms)")
            return
        }
        lastPrepareRestartAtMs.set(now)

        serviceScope.launch {
            try {
                Log.i(SingBoxService.TAG, "[PrepareRestart] Step 1/3: Wake up core")
                coreManager.wakeService()

                Log.i(SingBoxService.TAG, "[PrepareRestart] Step 2/3: Disconnect underlying network")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    setUnderlyingNetworks(null)
                }

                // Step 3: 等待应用收到广播
                // 不需要太长时间，因为VPN重启本身也需要时间
                Log.i(SingBoxService.TAG, "[PrepareRestart] Step 3/3: Waiting for apps to process network change...")
                delay(100)

                // 注意：不需要调用 closeAllConnectionsImmediate()
                // 因为 VPN 重启时服务关闭会强制关闭所有连接

                Log.i(SingBoxService.TAG, "[PrepareRestart] Complete - apps should now detect network interruption")
            } catch (e: Exception) {
                Log.e(SingBoxService.TAG, "performPrepareRestart error", e)
            }
        }
    }

    /**
     * 执行内核级热重载
     * 在 VPN 运行时重载配置，不销毁 VPN 服务
     * 失败时 Toast 报错并关闭 VPN，让用户手动重新打开
     */

    protected override fun performHotReload(configContent: String) {
        if (!SingBoxService.isRunning) {
            Log.w(SingBoxService.TAG, "performHotReload: VPN not running, skip")
            return
        }

        serviceScope.launch {
            try {
                Log.i(SingBoxService.TAG, "[HotReload] Starting kernel-level hot reload...")

                // 更新 CoreManager 的设置，确保后续操作使用最新设置
                val settings = SettingsRepository.getInstance(applicationContext).settings.first()
                coreManager.setCurrentSettings(settings)

                val result = coreManager.hotReloadConfig(configContent, preserveSelector = true)

                result.onSuccess { success ->
                    if (success) {
                        Log.i(SingBoxService.TAG, "[HotReload] Kernel hot reload succeeded")
                        LogRepository.getInstance().addLog("INFO [HotReload] Config reloaded successfully")

                        // Re-init BoxWrapperManager with current CommandServer
                        commandManager.getCommandServer()?.let { server ->
                            BoxWrapperManager.init(server)
                        }

                        // Update notification
                        requestNotificationUpdate(force = true)
                    } else {
                        handleHotReloadFailure("Kernel hot reload not available")
                    }
                }.onFailure { e ->
                    handleHotReloadFailure("Hot reload failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(SingBoxService.TAG, "performHotReload error", e)
                handleHotReloadFailure("Hot reload error: ${e.message}")
            }
        }
    }

    protected override fun handleHotReloadFailure(errorMsg: String) {
        Log.e(SingBoxService.TAG, "[HotReload] $errorMsg, stopping VPN")
        LogRepository.getInstance().addLog("ERROR [HotReload] $errorMsg")

        serviceScope.launch(Dispatchers.Main) {
            AppNotificationManager.showMessage(
                context = applicationContext,
                message = errorMsg,
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
        }

        SingBoxService.isManuallyStopped = false
        stopVpn(stopService = true)
    }

    protected override fun performFullRestart(configPath: String) {
        if (!SingBoxService.isRunning) {
            Log.w(SingBoxService.TAG, "performFullRestart: VPN not running, starting directly")
            startVpn(configPath)
            return
        }

        serviceScope.launch {
            try {
                Log.i(SingBoxService.TAG, "[FullRestart] Step 1/3: Stopping VPN completely...")

                coreManager.closeTunInterface()

                stopVpn(stopService = false)

                var waitCount = 0
                while (isStopping && waitCount < 50) {
                    delay(100)
                    waitCount++
                }

                Log.i(SingBoxService.TAG, "[FullRestart] Step 2/3: VPN stopped, waiting for cleanup...")
                delay(200)

                Log.i(SingBoxService.TAG, "[FullRestart] Step 3/3: Restarting VPN with new config...")
                SingBoxService.lastConfigPath = configPath
                startVpn(configPath)

                Log.i(SingBoxService.TAG, "[FullRestart] Complete")
            } catch (e: Exception) {
                Log.e(SingBoxService.TAG, "performFullRestart error", e)
                SingBoxService.setLastError("Full restart failed: ${e.message}")
            }
        }
    }

    /**
     *
     * 直接调用 Go 层 StartOrReloadService，阻塞等待结果
     *
     * 这里使用 runBlocking 是因为 AIDL 接口不支持挂起函数，
     * 调用来自 VPN 进程的 Binder 线程池，使用 Dispatchers.IO 避免阻塞调用线程
     *
     * @return true=成功, false=失败
     */

    override fun performHotReloadSync(configContent: String): Boolean {
        if (!SingBoxService.isRunning) {
            Log.w(SingBoxService.TAG, "performHotReloadSync: VPN not running")
            return false
        }

        return try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                Log.i(SingBoxService.TAG, "[HotReload-Sync] Starting kernel-level hot reload...")

                val settings = SettingsRepository.getInstance(applicationContext).settings.first()
                coreManager.setCurrentSettings(settings)

                val result = coreManager.hotReloadConfig(configContent, preserveSelector = true)

                result.getOrNull() == true && result.isSuccess.also { success ->
                    if (success && result.getOrNull() == true) {
                        Log.i(SingBoxService.TAG, "[HotReload-Sync] Kernel hot reload succeeded")
                        LogRepository.getInstance().addLog("INFO [HotReload] Config reloaded successfully via IPC")

                        commandManager.getCommandServer()?.let { server ->
                            BoxWrapperManager.init(server)
                        }

                        requestNotificationUpdate(force = true)
                        requestRemoteStateUpdate(force = true)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(SingBoxService.TAG, "performHotReloadSync error", e)
            false
        }
    }

    /**
     *
     * 原方法 ~430 行，现在简化为 ~90 行
     */

    protected override fun startVpn(configPath: String) {
        // 状态检查（保留在 Service 中，因为涉及多线程同步）
        synchronized(this) {
            if (SingBoxService.isRunning) {
                Log.w(SingBoxService.TAG, "VPN already running, ignore start request")
                return
            }
            if (SingBoxService.isStarting) {
                Log.w(SingBoxService.TAG, "VPN is already in starting process, ignore start request")
                return
            }
            if (isStopping) {
                Log.w(SingBoxService.TAG, "VPN is stopping, queue start request")
                pendingStartConfigPath = configPath
                stopSelfRequested = false
                SingBoxService.lastConfigPath = configPath
                return
            }
            SingBoxService.isStarting = true
        }

        SingBoxService.lastConfigPath = configPath

        // fix: 启动前同步当前选中节点名到 VpnStateStore，避免通知显示上次运行的旧节点
        runCatching {
            val repo = ConfigRepository.getInstance(this)
            val nodeId = repo.activeNodeId.value
            val name = repo.nodes.value.find { it.id == nodeId }?.name
            if (!name.isNullOrBlank()) {
                VpnStateStore.setActiveLabel(name)
            }
        }

        // 启动前台通知（必须在协程前调用）
        var foregroundStarted = false
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    VpnNotificationManager.NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(VpnNotificationManager.NOTIFICATION_ID, notification)
            }
            notificationManager.markForegroundStarted()
            foregroundStarted = true
        } catch (e: Exception) {
            Log.e(SingBoxService.TAG, "Failed to call startForeground", e)
        }
        if (!SingBoxService.shouldContinueCoreStartAfterForegroundResult(foregroundStarted)) {
            SingBoxService.setLastError("Failed to start foreground service")
            clearStartCommandFailureState()
            stopSelf()
        } else {
            continueStartVpnAfterForeground(configPath)
        }
    }

    protected override fun continueStartVpnAfterForeground(configPath: String) {
        // 获取清理缓存标志
        val cleanCache = synchronized(this) {
            val c = pendingCleanCache
            pendingCleanCache = false
            c
        }

        // 委托给 StartupManager
        startVpnJob?.cancel()
        startVpnJob = serviceScope.launch {
            val result = startupManager.startVpn(
                configPath = configPath,
                cleanCache = cleanCache,
                coreManager = coreManager,
                connectManager = connectManager,
                callbacks = startupCallbacks
            )

            when (result) {
                is com.kunk.singbox.service.manager.StartupManager.StartResult.Success -> {
                    pendingNodeName = null
                    updateServiceState(ServiceState.RUNNING)

                    // 注册 libbox 服务
                    tryRegisterRunningServiceForLibbox()
                }
                is com.kunk.singbox.service.manager.StartupManager.StartResult.Failed -> {
                    stopVpn(stopService = true)
                }
                is com.kunk.singbox.service.manager.StartupManager.StartResult.NeedPermission -> {
                    updateServiceState(ServiceState.STOPPED)
                    stopSelf()
                }
                is com.kunk.singbox.service.manager.StartupManager.StartResult.Cancelled -> {
                    // 已在 callbacks.onCancelled() 中处理
                }
            }

            // 清理
            startVpnJob = null
            if (!SingBoxService.isRunning && !isStopping && serviceState == ServiceState.STARTING) {
                updateServiceState(ServiceState.STOPPED)
            }
            updateTileState()
        }
    }
}
