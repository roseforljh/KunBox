package com.kunk.singbox.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.service.quicksettings.TileService
import android.content.ComponentName
import com.google.gson.Gson
import com.kunk.singbox.R
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.service.network.NetworkManager
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.service.manager.ConnectManager
import com.kunk.singbox.service.manager.HealthMonitor
import com.kunk.singbox.service.manager.SelectorManager as ServiceSelectorManager
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.manager.CoreManager
import com.kunk.singbox.service.manager.NetworkHelper
import com.kunk.singbox.service.manager.PlatformInterfaceImpl
import com.kunk.singbox.service.manager.ShutdownManager
import com.kunk.singbox.service.manager.ScreenStateManager
import com.kunk.singbox.service.manager.RouteGroupSelector
import com.kunk.singbox.service.manager.ForeignVpnMonitor
import com.kunk.singbox.service.manager.CoreNetworkResetManager
import com.kunk.singbox.service.manager.NodeSwitchManager
import com.kunk.singbox.service.manager.BackgroundPowerManager
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.ConnectionOwnerStatsSnapshot
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import com.kunk.singbox.utils.L
import com.kunk.singbox.utils.NetworkClient
import io.nekohasekai.libbox.*
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SingBoxService : VpnService() {

    private val gson = Gson()

    // ===== 新架构 Managers =====
    // 核心管理器 (VPN 启动/停止)
    private val coreManager: CoreManager by lazy {
        CoreManager(this, this, serviceScope)
    }

    // 连接管理器
    private val connectManager: ConnectManager by lazy {
        ConnectManager(this, serviceScope)
    }

    // 节点选择管理器
    private val serviceSelectorManager: ServiceSelectorManager by lazy {
        ServiceSelectorManager()
    }

    // 路由组自动选择管理器
    private val routeGroupSelector: RouteGroupSelector by lazy {
        RouteGroupSelector(this, serviceScope)
    }

    // 健康监控管理器包装器
    private val healthMonitorWrapper: HealthMonitor by lazy {
        HealthMonitor(serviceScope)
    }

    // Command 管理器 (Server/Client 交互)
    private val commandManager: CommandManager by lazy {
        CommandManager(this, serviceScope)
    }

    // Platform Interface 实现 (提取自原内联实现)
    private val platformInterfaceImpl: PlatformInterfaceImpl by lazy {
        PlatformInterfaceImpl(
            context = this,
            serviceScope = serviceScope,
            mainHandler = mainHandler,
            callbacks = platformCallbacks
        )
    }

    // 网络辅助工具
    private val networkHelper: NetworkHelper by lazy {
        NetworkHelper(this, serviceScope)
    }

    // 启动管理器
    private val startupManager: com.kunk.singbox.service.manager.StartupManager by lazy {
        com.kunk.singbox.service.manager.StartupManager(this, this, serviceScope)
    }

    // 关闭管理器
    private val shutdownManager: com.kunk.singbox.service.manager.ShutdownManager by lazy {
        com.kunk.singbox.service.manager.ShutdownManager(this, cleanupScope)
    }

    // 屏幕状态管理器
    private val screenStateManager: ScreenStateManager by lazy {
        ScreenStateManager(this, serviceScope)
    }

    // 外部 VPN 监控器
    private val foreignVpnMonitor: ForeignVpnMonitor by lazy {
        ForeignVpnMonitor(this)
    }

    // 核心网络重置管理器
    private val coreNetworkResetManager: CoreNetworkResetManager by lazy {
        CoreNetworkResetManager(serviceScope)
    }

    // 节点切换管理器
    private val nodeSwitchManager: NodeSwitchManager by lazy {
        NodeSwitchManager(this, serviceScope)
    }

    private val backgroundPowerManager: BackgroundPowerManager by lazy {
        BackgroundPowerManager(serviceScope)
    }

    // PlatformInterfaceImpl 回调实现
    private val platformCallbacks = object : PlatformInterfaceImpl.Callbacks {
        override fun protect(fd: Int): Boolean = this@SingBoxService.protect(fd)

        override fun openTun(options: TunOptions): Result<Int> {
            isConnectingTun.set(true)
            return try {
                val network = connectManager.getCurrentNetwork()
                val result = coreManager.openTun(options, network, reuseExisting = true)
                result.onSuccess { fd ->
                    vpnInterface = coreManager.vpnInterface
                    if (network != null) {
                        lastKnownNetwork = network
                        vpnStartedAtMs.set(SystemClock.elapsedRealtime())
                        connectManager.markVpnStarted()
                    }
                }
                result
            } finally {
                isConnectingTun.set(false)
            }
        }

        override fun getConnectivityManager(): ConnectivityManager? = connectivityManager
        override fun getCurrentNetwork(): Network? = connectManager.getCurrentNetwork()
        override fun getLastKnownNetwork(): Network? = lastKnownNetwork
        override fun setLastKnownNetwork(network: Network?) { lastKnownNetwork = network }
        override fun markVpnStarted() { connectManager.markVpnStarted() }

        override fun requestCoreNetworkReset(reason: String, force: Boolean) {
            this@SingBoxService.requestCoreNetworkReset(reason, force)
        }
        override fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean) {
            serviceScope.launch { this@SingBoxService.resetConnectionsOptimal(reason, skipDebounce) }
        }
        override fun setUnderlyingNetworks(networks: Array<Network>?) {
            this@SingBoxService.setUnderlyingNetworks(networks)
        }

        override fun isRunning(): Boolean = ServiceStateHolder.isRunning
        override fun isStarting(): Boolean = ServiceStateHolder.isStarting
        override fun isManuallyStopped(): Boolean = ServiceStateHolder.isManuallyStopped
        override fun getLastConfigPath(): String? = ServiceStateHolder.lastConfigPath
        override fun getCurrentSettings(): AppSettings? = currentSettings

        override fun incrementConnectionOwnerCalls() { ServiceStateHolder.incrementConnectionOwnerCalls() }
        override fun incrementConnectionOwnerInvalidArgs() { ServiceStateHolder.incrementConnectionOwnerInvalidArgs() }
        override fun incrementConnectionOwnerUidResolved() { ServiceStateHolder.incrementConnectionOwnerUidResolved() }
        override fun incrementConnectionOwnerSecurityDenied() { ServiceStateHolder.incrementConnectionOwnerSecurityDenied() }
        override fun incrementConnectionOwnerOtherException() { ServiceStateHolder.incrementConnectionOwnerOtherException() }
        override fun setConnectionOwnerLastEvent(event: String) { ServiceStateHolder.setConnectionOwnerLastEvent(event) }
        override fun setConnectionOwnerLastUid(uid: Int) { ServiceStateHolder.setConnectionOwnerLastUid(uid) }
        override fun isConnectionOwnerPermissionDeniedLogged(): Boolean = ServiceStateHolder.connectionOwnerPermissionDeniedLogged
        override fun setConnectionOwnerPermissionDeniedLogged(logged: Boolean) { ServiceStateHolder.connectionOwnerPermissionDeniedLogged = logged }

        override fun cacheUidToPackage(uid: Int, packageName: String) {
            this@SingBoxService.cacheUidToPackage(uid, packageName)
        }
        override fun getUidFromCache(uid: Int): String? = uidToPackageCache[uid]

        override fun findBestPhysicalNetwork(): Network? = this@SingBoxService.findBestPhysicalNetwork()
    }

    // 通知管理器 (原有)
    private val notificationManager: VpnNotificationManager by lazy {
        VpnNotificationManager(this, serviceScope)
    }

    private val remoteStateUpdateDebounceMs: Long = 250L
    private val lastRemoteStateUpdateAtMs = AtomicLong(0L)
    @Volatile private var remoteStateUpdateJob: Job? = null

    companion object {
        private const val TAG = "SingBoxService"

        const val ACTION_START = ServiceStateHolder.ACTION_START
        const val ACTION_STOP = ServiceStateHolder.ACTION_STOP
        const val ACTION_SWITCH_NODE = ServiceStateHolder.ACTION_SWITCH_NODE
        const val ACTION_SERVICE = ServiceStateHolder.ACTION_SERVICE
        const val ACTION_UPDATE_SETTING = ServiceStateHolder.ACTION_UPDATE_SETTING
        const val ACTION_PREPARE_RESTART = ServiceStateHolder.ACTION_PREPARE_RESTART
        const val ACTION_HOT_RELOAD = ServiceStateHolder.ACTION_HOT_RELOAD
        const val EXTRA_CONFIG_PATH = ServiceStateHolder.EXTRA_CONFIG_PATH
        const val EXTRA_CONFIG_CONTENT = ServiceStateHolder.EXTRA_CONFIG_CONTENT
        const val EXTRA_CLEAN_CACHE = ServiceStateHolder.EXTRA_CLEAN_CACHE
        const val EXTRA_SETTING_KEY = ServiceStateHolder.EXTRA_SETTING_KEY
        const val EXTRA_SETTING_VALUE_BOOL = ServiceStateHolder.EXTRA_SETTING_VALUE_BOOL

        var instance: SingBoxService?
            get() = ServiceStateHolder.instance
            private set(value) { ServiceStateHolder.instance = value }

        var isRunning: Boolean
            get() = ServiceStateHolder.isRunning
            private set(value) { ServiceStateHolder.isRunning = value }

        val isRunningFlow get() = ServiceStateHolder.isRunningFlow

        var isStarting: Boolean
            get() = ServiceStateHolder.isStarting
            private set(value) { ServiceStateHolder.isStarting = value }

        val isStartingFlow get() = ServiceStateHolder.isStartingFlow

        val lastErrorFlow get() = ServiceStateHolder.lastErrorFlow

        var isManuallyStopped: Boolean
            get() = ServiceStateHolder.isManuallyStopped
            private set(value) { ServiceStateHolder.isManuallyStopped = value }

        private var lastConfigPath: String?
            get() = ServiceStateHolder.lastConfigPath
            set(value) { ServiceStateHolder.lastConfigPath = value }

        private fun setLastError(message: String?) = ServiceStateHolder.setLastError(message)

        fun getConnectionOwnerStatsSnapshot() = ServiceStateHolder.getConnectionOwnerStatsSnapshot()
        fun resetConnectionOwnerStats() = ServiceStateHolder.resetConnectionOwnerStats()
    }

    private fun tryRegisterRunningServiceForLibbox() {
        val svc = boxService ?: return
        try {
            val m = Libbox::class.java.methods.firstOrNull { it.name == "setRunningService" && it.parameterTypes.size == 1 }
            m?.invoke(null, svc)
        } catch (_: Exception) {
        }
    }

    private fun tryClearRunningServiceForLibbox() {
        val svc = boxService ?: return
        try {
            val m = Libbox::class.java.methods.firstOrNull { it.name == "clearRunningService" && it.parameterTypes.size == 1 }
            m?.invoke(null, svc)
        } catch (_: Exception) {
        }
    }

    /**
     * 初始化新架构 Managers (7个核心模块)
     */
    private fun initManagers() {
        // 1. 初始化核心管理器
        coreManager.init(platformInterfaceImpl)
        Log.i(TAG, "CoreManager initialized")

        // 2. 初始化连接管理器
        connectManager.init(
            onNetworkChanged = { network ->
                if (network != null) {
                    connectManager.setUnderlyingNetworks(
                        networks = arrayOf(network),
                        setUnderlyingFn = { nets -> setUnderlyingNetworks(nets) }
                    )
                }
            },
            onNetworkLost = {
                Log.i(TAG, "Network lost via ConnectManager")
            }
        )
        Log.i(TAG, "ConnectManager initialized")

        // 3. 初始化节点选择管理器
        serviceSelectorManager.init(commandManager.getCommandClient())
        Log.i(TAG, "ServiceSelectorManager initialized")

        // 4. 初始化 Command 管理器
        commandManager.init(object : CommandManager.Callbacks {
            override fun requestNotificationUpdate(force: Boolean) {
                this@SingBoxService.requestNotificationUpdate(force)
            }
            override fun resolveEgressNodeName(tagOrSelector: String?): String? {
                return this@SingBoxService.resolveEgressNodeName(
                    ConfigRepository.getInstance(this@SingBoxService),
                    tagOrSelector
                )
            }
        })
        Log.i(TAG, "CommandManager initialized")

        // 5. 初始化健康监控管理器包装器
        healthMonitorWrapper.init {
            object : HealthMonitor.HealthContext {
                override val isRunning: Boolean
                    get() = SingBoxService.isRunning
                override val isStopping: Boolean
                    get() = coreManager.isStopping

                override fun isBoxServiceValid(): Boolean = coreManager.isBoxServiceValid()
                override fun isVpnInterfaceValid(): Boolean = coreManager.isVpnInterfaceValid()

                override suspend fun wakeBoxService() {
                    coreManager.wakeBoxService()
                }

                override fun restartVpnService(reason: String) {
                    serviceScope.launch {
                        this@SingBoxService.restartVpnService(reason)
                    }
                }

                override fun addLog(message: String) {
                    LogRepository.getInstance().addLog(message)
                }
            }
        }
        Log.i(TAG, "HealthMonitorWrapper initialized")

        // 7. 初始化屏幕状态管理器
        screenStateManager.init(object : ScreenStateManager.Callbacks {
            override val isRunning: Boolean
                get() = SingBoxService.isRunning
            override suspend fun performScreenOnCheck() {
                healthMonitorWrapper.performScreenOnCheck()
            }
            override suspend fun performAppForegroundCheck() {
                healthMonitorWrapper.performAppForegroundCheck()
            }
            override suspend fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean) {
                this@SingBoxService.resetConnectionsOptimal(reason, skipDebounce)
            }
            override fun notifyRemoteStateUpdate(force: Boolean) {
                this@SingBoxService.requestRemoteStateUpdate(force)
            }
        })
        Log.i(TAG, "ScreenStateManager initialized")

        // 8. 初始化路由组自动选择管理器
        routeGroupSelector.init(object : RouteGroupSelector.Callbacks {
            override val isRunning: Boolean
                get() = SingBoxService.isRunning
            override val isStopping: Boolean
                get() = coreManager.isStopping
            override fun getCommandClient() = commandManager.getCommandClient()
            override fun getSelectedOutbound(groupTag: String) = commandManager.getSelectedOutbound(groupTag)
        })
        Log.i(TAG, "RouteGroupSelector initialized")

        // 9. 初始化外部 VPN 监控器
        foreignVpnMonitor.init(object : ForeignVpnMonitor.Callbacks {
            override val isStarting: Boolean
                get() = SingBoxService.isStarting
            override val isRunning: Boolean
                get() = SingBoxService.isRunning
            override val isConnectingTun: Boolean
                get() = this@SingBoxService.isConnectingTun.get()
        })
        Log.i(TAG, "ForeignVpnMonitor initialized")

        // 10. 初始化核心网络重置管理器
        coreNetworkResetManager.init(object : CoreNetworkResetManager.Callbacks {
            override fun getBoxService() = boxService
            override suspend fun restartVpnService(reason: String) {
                this@SingBoxService.restartVpnService(reason)
            }
        })
        coreNetworkResetManager.debounceMs = coreResetDebounceMs
        Log.i(TAG, "CoreNetworkResetManager initialized")

        // 11. 初始化节点切换管理器
        nodeSwitchManager.init(object : NodeSwitchManager.Callbacks {
            override val isRunning: Boolean
                get() = SingBoxService.isRunning
            override suspend fun hotSwitchNode(nodeTag: String): Boolean = this@SingBoxService.hotSwitchNode(nodeTag)
            override fun getConfigPath(): String = pendingHotSwitchFallbackConfigPath
                ?: File(filesDir, "running_config.json").absolutePath
            override fun setRealTimeNodeName(name: String?) { realTimeNodeName = name }
            override fun requestNotificationUpdate(force: Boolean) {
                this@SingBoxService.requestNotificationUpdate(force)
            }
            override fun notifyRemoteStateUpdate(force: Boolean) {
                this@SingBoxService.requestRemoteStateUpdate(force)
            }
            override fun startServiceIntent(intent: Intent) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        })
        Log.i(TAG, "NodeSwitchManager initialized")

        initBackgroundPowerManager()
        Log.i(TAG, "BackgroundPowerManager initialized")

        Log.i(TAG, "All 12 Managers initialized successfully")
    }

    private fun initBackgroundPowerManager() {
        val thresholdMs = runBlocking {
            try {
                val settings = SettingsRepository.getInstance(this@SingBoxService).settings.first()
                settings.backgroundPowerSavingDelay.delayMs
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read power saving delay setting, using default", e)
                BackgroundPowerSavingDelay.MINUTES_30.delayMs
            }
        }

        backgroundPowerManager.init(
            callbacks = object : BackgroundPowerManager.Callbacks {
                override val isVpnRunning: Boolean
                    get() = isRunning

                override fun suspendNonEssentialProcesses() {
                    Log.i(TAG, "[PowerSaving] Suspending non-essential processes...")
                    commandManager.suspendNonEssential()
                    trafficMonitor.pause()
                    healthMonitorWrapper.enterPowerSavingMode()
                    LogRepository.getInstance().addLog("INFO: Entered power saving mode (background ${thresholdMs / 1000 / 60}min)")
                }

                override fun resumeNonEssentialProcesses() {
                    Log.i(TAG, "[PowerSaving] Resuming all processes...")
                    commandManager.resumeNonEssential()
                    trafficMonitor.resume()
                    healthMonitorWrapper.exitPowerSavingMode()
                    LogRepository.getInstance().addLog("INFO: Exited power saving mode (user returned)")
                }
            },
            thresholdMs = thresholdMs
        )

        // 设置 IPC Hub 的 PowerManager 引用，用于接收主进程的生命周期通知
        SingBoxIpcHub.setPowerManager(backgroundPowerManager)
        // 设置 ScreenStateManager 的 PowerManager 引用，用于接收屏幕状态通知
        screenStateManager.setPowerManager(backgroundPowerManager)
    }

    /**
     * StartupManager 回调实现
     */
    private val startupCallbacks = object : com.kunk.singbox.service.manager.StartupManager.Callbacks {
        // 状态回调
        override fun onStarting() {
            updateServiceState(ServiceState.STARTING)
            realTimeNodeName = null
            vpnLinkValidated = false
        }

        override fun onStarted(configContent: String) {
            Log.i(TAG, "KunBox VPN started successfully")
            notificationManager.setSuppressUpdates(false)
        }

        override fun onFailed(error: String) {
            Log.e(TAG, error)
            setLastError(error)
            notificationManager.setSuppressUpdates(true)
            notificationManager.cancelNotification()
            updateServiceState(ServiceState.STOPPED)
        }

        override fun onCancelled() {
            Log.i(TAG, "startVpn cancelled")
            if (!isStopping) {
                Log.w(TAG, "startVpn cancelled but not by stopVpn, resetting state to STOPPED")
                isRunning = false
                updateServiceState(ServiceState.STOPPED)
            }
        }

        // 通知管理
        override fun createNotification(): Notification = this@SingBoxService.createNotification()
        override fun markForegroundStarted() { notificationManager.markForegroundStarted() }

        // 生命周期管理
        override fun registerScreenStateReceiver() { screenStateManager.registerScreenStateReceiver() }
        override fun startForeignVpnMonitor() { foreignVpnMonitor.start() }
        override fun stopForeignVpnMonitor() { foreignVpnMonitor.stop() }

        // 组件初始化
        override fun initSelectorManager(configContent: String) {
            this@SingBoxService.initSelectorManager(configContent)
        }

        override fun startCommandServerAndClient(boxService: io.nekohasekai.libbox.BoxService) {
            commandManager.start(boxService).onFailure { e ->
                Log.e(TAG, "Failed to start Command Server/Client", e)
            }
            // 更新 serviceSelectorManager 的 commandClient (修复热切换不生效的问题)
            serviceSelectorManager.updateCommandClient(commandManager.getCommandClient())
        }

        override fun startRouteGroupAutoSelect(configContent: String) {
            routeGroupSelector.start(configContent)
        }

        override fun scheduleAsyncRuleSetUpdate() {
            this@SingBoxService.scheduleAsyncRuleSetUpdate()
        }

        override fun startHealthMonitor() {
            healthMonitorWrapper.start()
            Log.i(TAG, "Periodic health check started")
        }

        override fun scheduleKeepaliveWorker() {
            VpnKeepaliveWorker.schedule(applicationContext)
            Log.i(TAG, "VPN keepalive worker scheduled")
        }

        override fun startTrafficMonitor() {
            trafficMonitor.start(Process.myUid(), trafficListener)
            networkManager = NetworkManager(this@SingBoxService, this@SingBoxService)
        }

        // 状态管理
        override fun updateTileState() { this@SingBoxService.updateTileState() }
        override fun setIsRunning(running: Boolean) { isRunning = running; NetworkClient.onVpnStateChanged(running) }
        override fun setIsStarting(starting: Boolean) { isStarting = starting }
        override fun setLastError(error: String?) { SingBoxService.setLastError(error) }
        override fun persistVpnState(isRunning: Boolean) {
            VpnTileService.persistVpnState(applicationContext, isRunning)
            if (isRunning) {
                VpnStateStore.setMode(VpnStateStore.CoreMode.VPN)
            }
        }
        override fun persistVpnPending(pending: String) {
            VpnTileService.persistVpnPending(applicationContext, pending)
        }

        // 网络管理
        override suspend fun waitForUsablePhysicalNetwork(timeoutMs: Long): Network? {
            return this@SingBoxService.waitForUsablePhysicalNetwork(timeoutMs)
        }

        override suspend fun ensureNetworkCallbackReady(timeoutMs: Long) {
            this@SingBoxService.ensureNetworkCallbackReadyWithTimeout(timeoutMs)
        }

        override fun setLastKnownNetwork(network: Network?) { lastKnownNetwork = network }
        override fun setNetworkCallbackReady(ready: Boolean) { networkCallbackReady = ready }

        // 清理
        override suspend fun waitForCleanupJob() {
            val cleanup = cleanupJob
            if (cleanup != null && cleanup.isActive) {
                Log.i(TAG, "Waiting for previous service cleanup...")
                cleanup.join()
                Log.i(TAG, "Previous cleanup finished")
            }
        }

        override fun stopSelf() { this@SingBoxService.stopSelf() }
    }

    // ShutdownManager 回调实现
    private val shutdownCallbacks = object : ShutdownManager.Callbacks {
        // 状态管理
        override fun updateServiceState(state: ServiceState) {
            this@SingBoxService.updateServiceState(state)
        }
        override fun updateTileState() { this@SingBoxService.updateTileState() }
        override fun stopForegroundService() {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping foreground", e)
            }
        }
        override fun stopSelf() {
            if (stopSelfRequested) {
                this@SingBoxService.stopSelf()
            }
        }

        // 组件管理
        override fun cancelStartVpnJob(): Job? {
            val job = startVpnJob
            startVpnJob = null
            job?.cancel()
            return job
        }
        override fun cancelVpnHealthJob() {
            vpnHealthJob?.cancel()
            vpnHealthJob = null
        }
        override fun cancelCoreNetworkResetJob() {
            coreNetworkResetManager.cancelPendingReset()
        }
        override fun cancelRemoteStateUpdateJob() {
            remoteStateUpdateJob?.cancel()
            remoteStateUpdateJob = null
        }
        override fun cancelRouteGroupAutoSelectJob() {
            routeGroupSelector.stop()
        }

        // 资源清理
        override fun stopForeignVpnMonitor() { foreignVpnMonitor.stop() }
        override fun tryClearRunningServiceForLibbox() {
            this@SingBoxService.tryClearRunningServiceForLibbox()
        }
        override fun unregisterScreenStateReceiver() {
            screenStateManager.unregisterScreenStateReceiver()
        }
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            platformInterfaceImpl.closeDefaultInterfaceMonitor(listener)
        }

        // 获取状态
        override fun getBoxService(): BoxService? = boxService
        override fun getVpnInterface(): ParcelFileDescriptor? = vpnInterface
        override fun getCurrentInterfaceListener(): InterfaceUpdateListener? = currentInterfaceListener
        override fun getConnectivityManager(): ConnectivityManager? = connectivityManager

        // 设置状态
        override fun setBoxService(service: BoxService?) { boxService = service }
        override fun setVpnInterface(fd: ParcelFileDescriptor?) { vpnInterface = fd }
        override fun setIsRunning(running: Boolean) { isRunning = running }
        override fun setRealTimeNodeName(name: String?) { realTimeNodeName = name }
        override fun setVpnLinkValidated(validated: Boolean) { vpnLinkValidated = validated }
        override fun setNoPhysicalNetworkWarningLogged(logged: Boolean) {
            noPhysicalNetworkWarningLogged = logged
        }
        override fun setDefaultInterfaceName(name: String) { defaultInterfaceName = name }
        override fun setNetworkCallbackReady(ready: Boolean) { networkCallbackReady = ready }
        override fun setLastKnownNetwork(network: Network?) { lastKnownNetwork = network }
        override fun clearUnderlyingNetworks() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                runCatching { setUnderlyingNetworks(null) }
            }
        }

        // 获取配置路径用于重启
        override fun getPendingStartConfigPath(): String? = synchronized(this@SingBoxService) {
            val pending = pendingStartConfigPath
            stopSelfRequested = false
            pending
        }
        override fun clearPendingStartConfigPath() = synchronized(this@SingBoxService) {
            pendingStartConfigPath = null
            isStopping = false
        }
        override fun startVpn(configPath: String) {
            this@SingBoxService.startVpn(configPath)
        }

        // 检查 VPN 接口是否可复用
        override fun hasExistingTunInterface(): Boolean = vpnInterface != null
    }

    /**
     * 初始化 SelectorManager - 记录 PROXY selector 的 outbound 列表
     * 用于后续热切换时判断是否在同一 selector group 内
     */
    private fun initSelectorManager(configContent: String) {
        try {
            val config = gson.fromJson(configContent, SingBoxConfig::class.java) ?: return
            val proxySelector = config.outbounds?.find {
                it.type == "selector" && it.tag.equals("PROXY", ignoreCase = true)
            }

            if (proxySelector == null) {
                Log.w(TAG, "No PROXY selector found in config")
                return
            }

            val outboundTags = proxySelector.outbounds?.filter { it.isNotBlank() } ?: emptyList()
            val selectedTag = proxySelector.default ?: outboundTags.firstOrNull()

            SelectorManager.recordSelectorSignature(outboundTags, selectedTag)
            Log.i(TAG, "SelectorManager initialized: ${outboundTags.size} outbounds, selected=$selectedTag")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init SelectorManager", e)
        }
    }

    private fun closeRecentConnectionsBestEffort(reason: String) {
        val ids = recentConnectionIds
        if (ids.isEmpty()) return
        var closed = 0
        for (id in ids) {
            if (id.isBlank()) continue
            if (commandManager.closeConnection(id)) closed++
        }
        if (closed > 0) {
            LogRepository.getInstance().addLog("INFO: closeConnection($reason) closed=$closed")
        }
    }

    /**
     * 重置所有连接 - 渐进式降级策略
     */
    private suspend fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean = false) {
        networkHelper.resetConnectionsOptimal(
            reason = reason,
            skipDebounce = skipDebounce,
            lastResetAtMs = lastConnectionsResetAtMs,
            debounceMs = connectionsResetDebounceMs,
            commandManager = commandManager,
            closeRecentFn = { r -> closeRecentConnectionsBestEffort(r) },
            updateLastReset = { ms -> lastConnectionsResetAtMs = ms }
        )
    }

    @Deprecated("Use resetConnectionsOptimal instead")
    private suspend fun closeAllConnectionsImmediate(skipDebounce: Boolean = false) {
        resetConnectionsOptimal(reason = "legacy_closeAllImmediate", skipDebounce = skipDebounce)
    }

    enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    @Volatile private var serviceState: ServiceState = ServiceState.STOPPED

    private fun resolveEgressNodeName(repo: ConfigRepository, tagOrSelector: String?): String? {
        if (tagOrSelector.isNullOrBlank()) return null

        // 1) Direct outbound tag -> node name
        repo.resolveNodeNameFromOutboundTag(tagOrSelector)?.let { return it }

        // 2) Selector/group tag -> selected outbound -> resolve again (depth-limited)
        var current: String? = tagOrSelector
        repeat(4) {
            val next = current?.let { commandManager.getSelectedOutbound(it) }
            if (next.isNullOrBlank() || next == current) return@repeat
            repo.resolveNodeNameFromOutboundTag(next)?.let { return it }
            current = next
        }

        return null
    }

    private fun notifyRemoteStateNow() {
        val activeLabel = runCatching {
            val repo = ConfigRepository.getInstance(applicationContext)
            val activeNodeId = repo.activeNodeId.value
            // 2025-fix: 与 buildNotificationState 保持一致的优先级
            realTimeNodeName
                ?: VpnStateStore.getActiveLabel().takeIf { it.isNotBlank() }
                ?: repo.nodes.value.find { it.id == activeNodeId }?.name
                ?: ""
        }.getOrDefault("")

        SingBoxIpcHub.update(
            state = serviceState,
            activeLabel = activeLabel,
            lastError = lastErrorFlow.value.orEmpty(),
            manuallyStopped = isManuallyStopped
        )
    }

    private fun requestRemoteStateUpdate(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val last = lastRemoteStateUpdateAtMs.get()

        if (force) {
            lastRemoteStateUpdateAtMs.set(now)
            remoteStateUpdateJob?.cancel()
            remoteStateUpdateJob = null
            notifyRemoteStateNow()
            return
        }

        val delayMs = (remoteStateUpdateDebounceMs - (now - last)).coerceAtLeast(0L)
        if (delayMs <= 0L) {
            lastRemoteStateUpdateAtMs.set(now)
            remoteStateUpdateJob?.cancel()
            remoteStateUpdateJob = null
            notifyRemoteStateNow()
            return
        }

        if (remoteStateUpdateJob?.isActive == true) return
        remoteStateUpdateJob = serviceScope.launch {
            delay(delayMs)
            lastRemoteStateUpdateAtMs.set(SystemClock.elapsedRealtime())
            notifyRemoteStateNow()
        }
    }

    private fun updateServiceState(state: ServiceState) {
        if (serviceState == state) return
        serviceState = state
        requestRemoteStateUpdate(force = true)
    }

    /**
     * 暴露给 ConfigRepository 调用，尝试热切换节点
     * @return true if hot switch triggered successfully, false if restart is needed
     *
     * 2025-fix-v3: 学习 NekoBox 的简洁做法，移除网络震荡
     *
     * 核心原理:
     * sing-box 的 Selector.SelectOutbound() 内部会调用 interruptGroup.Interrupt(interruptExternalConnections)
     * 当 PROXY selector 配置了 interrupt_exist_connections=true 时,
     * selectOutbound 会自动中断所有外部连接(入站连接)
     *
     * 修复策略:
     * 1. 直接调用 selectOutbound，sing-box 内部自动处理连接中断
     * 2. 不进行网络震荡，避免触发多次 CONNECTIVITY_CHANGE 广播
     *    这是 Telegram 重复"加载中-加载完成"的根因
     */
    suspend fun hotSwitchNode(nodeTag: String): Boolean {
        if (boxService == null || !isRunning) return false

        try {
            L.connection("HotSwitch", "Starting switch to: $nodeTag")

            // Step 1: 唤醒核心
            coreManager.wakeBoxService()
            L.step("HotSwitch", 1, 2, "Called boxService.wake()")

            // Step 2: 使用 SelectorManager 切换节点 (渐进式降级)
            L.step("HotSwitch", 2, 2, "Calling SelectorManager.switchNode...")

            when (val result = serviceSelectorManager.switchNode(nodeTag)) {
                is com.kunk.singbox.service.manager.SelectorManager.SwitchResult.Success -> {
                    L.result("HotSwitch", true, "Switched to $nodeTag via ${result.method}")
                    requestNotificationUpdate(force = true)
                    return true
                }
                is com.kunk.singbox.service.manager.SelectorManager.SwitchResult.NeedRestart -> {
                    L.warn("HotSwitch", "Need restart: ${result.reason}")
                    // 需要完整重启，返回 false 让调用者处理
                    return false
                }
                is com.kunk.singbox.service.manager.SelectorManager.SwitchResult.Failed -> {
                    L.error("HotSwitch", "Failed: ${result.error}")
                    return false
                }
            }
        } catch (e: Exception) {
            L.error("HotSwitch", "Unexpected exception", e)
            return false
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    private var boxService: BoxService? = null
    private var currentSettings: AppSettings? = null
    private val serviceSupervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceSupervisorJob)
    private val cleanupSupervisorJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupSupervisorJob)
    @Volatile private var isStopping: Boolean = false
    @Volatile private var stopSelfRequested: Boolean = false
    @Volatile private var cleanupJob: Job? = null
    @Volatile private var pendingStartConfigPath: String? = null
    @Volatile private var pendingCleanCache: Boolean = false
    @Volatile private var pendingHotSwitchNodeId: String? = null
    @Volatile private var startVpnJob: Job? = null
    @Volatile private var realTimeNodeName: String? = null
    // @Volatile private var nodePollingJob: Job? = null // Removed in favor of CommandClient

    private val isConnectingTun = AtomicBoolean(false)

    // Command 相关变量已移至 CommandManager
    // 保留这些作为兼容性别名 (委托到 commandManager)
    private val activeConnectionNode: String? get() = commandManager.activeConnectionNode
    private val activeConnectionLabel: String? get() = commandManager.activeConnectionLabel
    private val recentConnectionIds: List<String> get() = commandManager.recentConnectionIds

    // 速度计算相关 - 委托给 TrafficMonitor
    @Volatile private var showNotificationSpeed: Boolean = true
    private var currentUploadSpeed: Long = 0L
    private var currentDownloadSpeed: Long = 0L

    // TrafficMonitor 实例 - 统一管理流量监控和卡死检测
    private val trafficMonitor = TrafficMonitor(serviceScope)
    private val trafficListener = object : TrafficMonitor.Listener {
        override fun onTrafficUpdate(snapshot: TrafficMonitor.TrafficSnapshot) {
            currentUploadSpeed = snapshot.uploadSpeed
            currentDownloadSpeed = snapshot.downloadSpeed
            if (showNotificationSpeed) {
                requestNotificationUpdate(force = false)
            }
        }

        override fun onTrafficStall(consecutiveCount: Int) {
            // 流量停滞检测 - 触发轻量级健康检查
            stallRefreshAttempts++
            Log.w(TAG, "Traffic stall detected (count=$consecutiveCount, refreshAttempt=$stallRefreshAttempts/$maxStallRefreshAttempts)")

            if (stallRefreshAttempts >= maxStallRefreshAttempts) {
                Log.e(TAG, "Too many stall refresh attempts ($stallRefreshAttempts), restarting VPN service")
                LogRepository.getInstance().addLog(
                    "ERROR: VPN connection stalled for too long, automatically restarting..."
                )
                stallRefreshAttempts = 0
                trafficMonitor.resetStallCounter()
                serviceScope.launch {
                    withContext(Dispatchers.Main) {
                        restartVpnService(reason = "Persistent connection stall")
                    }
                }
            } else {
                // 尝试刷新连接
                serviceScope.launch {
                    try {
                        boxService?.wake()
                        delay(30)
                        resetConnectionsOptimal(reason = "traffic_stall", skipDebounce = true)
                        Log.i(TAG, "Cleared stale connections after stall")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to clear connections after stall", e)
                    }
                    requestCoreNetworkReset(reason = "traffic_stall", force = true)
                    trafficMonitor.resetStallCounter()
                }
            }
        }

        override fun onProxyIdle(idleDurationMs: Long) {
            Log.i(TAG, "Proxy idle detected (${idleDurationMs / 1000}s), preemptively resetting connections")
            serviceScope.launch {
                resetConnectionsOptimal(reason = "proxy_idle_${idleDurationMs / 1000}s", skipDebounce = false)
            }
        }
    }

    // ⭐ P1修复: 连续stall刷新失败后自动重启服务
    private var stallRefreshAttempts: Int = 0
    private val maxStallRefreshAttempts: Int = 3 // 连续3次stall刷新后仍无流量则重启服务

    // NetworkManager 实例 - 统一管理网络状态和底层网络切换
    private var networkManager: NetworkManager? = null

    private val coreResetDebounceMs: Long = 2500L

    @Volatile private var lastRuleSetCheckMs: Long = 0L
    private val ruleSetCheckIntervalMs: Long = 6 * 60 * 60 * 1000L

    private val uidToPackageCache = ConcurrentHashMap<Int, String>()
    private val maxUidToPackageCacheSize: Int = 512

    private fun cacheUidToPackage(uid: Int, pkg: String) {
        if (uid <= 0 || pkg.isBlank()) return
        uidToPackageCache[uid] = pkg
        if (uidToPackageCache.size > maxUidToPackageCacheSize) {
            uidToPackageCache.clear()
        }
    }

    private fun requestCoreNetworkReset(reason: String, force: Boolean = false) {
        coreNetworkResetManager.requestReset(reason, force)
    }

    /**
     * 重启 VPN 服务以彻底清理网络状态
     * 用于处理网络栈重置无效的严重情况
     */
    private suspend fun restartVpnService(reason: String) = withContext(Dispatchers.Main) {
        L.vpn("Restart", "Restarting: $reason")

        // 保存当前配置路径
        val configPath = lastConfigPath ?: run {
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
            setLastError("Failed to restart VPN: ${e.message}")
        }
    }

    // 屏幕/前台状态从 ScreenStateManager 读取
    private val isScreenOn: Boolean get() = screenStateManager.isScreenOn
    private val isAppInForeground: Boolean get() = screenStateManager.isAppInForeground

    // Auto reconnect
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentInterfaceListener: InterfaceUpdateListener? = null
    private var defaultInterfaceName: String = ""
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastKnownNetwork: Network? = null
    private var vpnHealthJob: Job? = null
    @Volatile private var vpnLinkValidated: Boolean = false

    // 网络就绪标志：确保 Libbox 启动前网络回调已完成初始采样
    @Volatile private var networkCallbackReady: Boolean = false
    @Volatile private var noPhysicalNetworkWarningLogged: Boolean = false

    // setUnderlyingNetworks 防抖机制 - 避免频繁调用触发系统提示音
    private val lastSetUnderlyingNetworksAtMs = AtomicLong(0)
    private val setUnderlyingNetworksDebounceMs: Long = 2000L // 2秒防抖

    // VPN 启动窗口期保护 - 参考 NekoBox 设计
    // 在 VPN 启动后的短时间内，updateDefaultInterface 跳过 setUnderlyingNetworks 调用
    // 因为 openTun() 已经设置过底层网络，重复调用会导致 UDP 连接断开
    private val vpnStartedAtMs = AtomicLong(0)
    private val vpnStartupWindowMs: Long = 3000L // 启动后 3 秒内跳过重复设置

    // NekoBox-style: 连接重置防抖，避免多次重置导致 Telegram 反复加载
    @Volatile private var lastConnectionsResetAtMs: Long = 0L
    private val connectionsResetDebounceMs: Long = 2000L // 2秒内不重复重置

    private fun findBestPhysicalNetwork(): Network? {
        // 优先使用 ConnectManager (新架构)
        connectManager.getCurrentNetwork()?.let { return it }
        // 回退到 NetworkManager
        networkManager?.findBestPhysicalNetwork()?.let { return it }
        // 当 networkManager 为 null 时（服务重启期间），使用 NetworkHelper 的回退逻辑
        return networkHelper.findBestPhysicalNetworkFallback()
    }

    private fun updateDefaultInterface(network: Network) {
        networkHelper.updateDefaultInterface(
            network = network,
            vpnStartedAtMs = vpnStartedAtMs.get(),
            startupWindowMs = vpnStartupWindowMs,
            defaultInterfaceName = defaultInterfaceName,
            lastKnownNetwork = lastKnownNetwork,
            lastSetUnderlyingAtMs = lastSetUnderlyingNetworksAtMs.get(),
            debounceMs = setUnderlyingNetworksDebounceMs,
            isRunning = isRunning,
            settings = currentSettings,
            setUnderlyingNetworks = { networks -> setUnderlyingNetworks(networks) },
            updateInterfaceListener = { name, index, expensive, constrained ->
                currentInterfaceListener?.updateDefaultInterface(name, index, expensive, constrained)
            },
            updateState = { net, iface, now ->
                lastKnownNetwork = net
                defaultInterfaceName = iface
                lastSetUnderlyingNetworksAtMs.set(now)
                noPhysicalNetworkWarningLogged = false
            },
            requestCoreReset = { reason, force -> requestCoreNetworkReset(reason, force) },
            resetConnections = { reason, skip -> serviceScope.launch { resetConnectionsOptimal(reason, skip) } }
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "SingBoxService onCreate: pid=${android.os.Process.myPid()} instance=${System.identityHashCode(this)}")
        instance = this

        // Restore manually stopped state from persistent storage
        isManuallyStopped = VpnStateStore.isManuallyStopped()
        Log.i(TAG, "Restored isManuallyStopped state: $isManuallyStopped")

        notificationManager.createNotificationChannel()
        // 初始化 ConnectivityManager
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // ===== 初始化新架构 Managers =====
        initManagers()

        serviceScope.launch {
            lastErrorFlow.collect {
                requestRemoteStateUpdate(force = false)
            }
        }

        // 监听活动节点变化，更新通知
        serviceScope.launch {
            ConfigRepository.getInstance(this@SingBoxService).activeNodeId.collect { activeNodeId ->
                if (isRunning) {
                    requestNotificationUpdate(force = false)
                    requestRemoteStateUpdate(force = false)
                }
            }
        }

        // 监听通知栏速度显示设置变化
        serviceScope.launch {
            SettingsRepository.getInstance(this@SingBoxService)
                .settings
                .map { it.showNotificationSpeed }
                .distinctUntilChanged()
                .collect { enabled ->
                    showNotificationSpeed = enabled
                    if (isRunning) {
                        requestNotificationUpdate(force = true)
                    }
                }
        }

        // ⭐ P0修复3: 注册Activity生命周期回调，检测应用返回前台
        screenStateManager.registerActivityLifecycleCallbacks(application)
    }

    /**
     * 监听应用前后台切换 (委托给 ScreenStateManager)
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
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        runCatching {
            LogRepository.getInstance().addLog("INFO SingBoxService: onStartCommand action=${intent?.action}")
        }
        when (intent?.action) {
            ACTION_START -> {
                isManuallyStopped = false
                VpnStateStore.setManuallyStopped(false)
                VpnTileService.persistVpnPending(applicationContext, "starting")

                // 性能优化: 预创建 TUN Builder (非阻塞)
                coreManager.preallocateTunBuilder()

                var configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                val cleanCache = intent.getBooleanExtra(EXTRA_CLEAN_CACHE, false)

                // P0 Optimization: If config path is missing (Shortcut/Headless), generate it inside Service
                if (configPath == null) {
                    Log.i(TAG, "ACTION_START received without config path, generating config...")
                    serviceScope.launch {
                        try {
                            val repo = ConfigRepository.getInstance(applicationContext)
                            val result = repo.generateConfigFile()
                            if (result != null) {
                                Log.i(TAG, "Config generated successfully: ${result.path}")
                                // Recursively call start command with the generated path
                                val newIntent = Intent(applicationContext, SingBoxService::class.java).apply {
                                    action = ACTION_START
                                    putExtra(EXTRA_CONFIG_PATH, result.path)
                                    putExtra(EXTRA_CLEAN_CACHE, cleanCache)
                                }
                                startService(newIntent)
                            } else {
                                Log.e(TAG, "Failed to generate config file")
                                setLastError("Failed to generate config file")
                                withContext(Dispatchers.Main) { stopSelf() }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error generating config in Service", e)
                            setLastError("Error generating config: ${e.message}")
                            withContext(Dispatchers.Main) { stopSelf() }
                        }
                    }
                    return START_STICKY
                }

                if (configPath != null) {
                    updateServiceState(ServiceState.STARTING)
                    synchronized(this) {
                        // FIX: Ensure pendingCleanCache is set from intent even for cold start
                        if (cleanCache) pendingCleanCache = true

                        if (isStarting) {
                            pendingStartConfigPath = configPath
                            stopSelfRequested = false
                            lastConfigPath = configPath
                            // Return STICKY to allow system to restart VPN if killed due to memory pressure
                            return START_STICKY
                        }
                        if (isStopping) {
                            pendingStartConfigPath = configPath
                            stopSelfRequested = false
                            lastConfigPath = configPath
                            // Return STICKY to allow system to restart VPN if killed due to memory pressure
                            return START_STICKY
                        }
                        // If already running, do a clean restart to avoid half-broken tunnel state
                        if (isRunning) {
                            pendingStartConfigPath = configPath
                            stopSelfRequested = false
                            lastConfigPath = configPath
                        }
                    }
                    if (isRunning) {
                        // 2025-fix: 优先尝试热切换节点，避免重启 VPN 导致连接断开
                        // 只有当需要更改核心配置（如路由规则、DNS 等）时才重启
                        // 目前所有切换都视为可能包含核心变更，但我们可以尝试检测
                        // 暂时保持重启逻辑作为兜底，但在此之前尝试热切换
                        // 注意：如果只是切换节点，并不需要重启 VPN，直接 selectOutbound 即可
                        // 但我们需要一种机制来通知 Service 是在切换节点还是完全重载
                        stopVpn(stopService = false)
                    } else {
                        startVpn(configPath)
                    }
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "Received ACTION_STOP (manual) -> stopping VPN")
                isManuallyStopped = true
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
            ACTION_SWITCH_NODE -> {
                Log.i(TAG, "Received ACTION_SWITCH_NODE -> switching node")
                // 从 Intent 中获取目标节点 ID，如果未提供则切换下一个
                val targetNodeId = intent.getStringExtra("node_id")
                val outboundTag = intent.getStringExtra("outbound_tag")
                runCatching {
                    LogRepository.getInstance().addLog(
                        "INFO SingBoxService: ACTION_SWITCH_NODE nodeId=${targetNodeId.orEmpty()} outboundTag=${outboundTag.orEmpty()}"
                    )
                }
                // Remember latest config path for fallback restart if hot switch doesn't apply.
                val fallbackConfigPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
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
                        serviceClass = SingBoxService::class.java,
                        actionStart = ACTION_START,
                        extraConfigPath = EXTRA_CONFIG_PATH
                    )
                } else {
                    nodeSwitchManager.switchNextNode(
                        serviceClass = SingBoxService::class.java,
                        actionStart = ACTION_START,
                        extraConfigPath = EXTRA_CONFIG_PATH
                    )
                }
            }
            ACTION_UPDATE_SETTING -> {
                val key = intent.getStringExtra(EXTRA_SETTING_KEY)
                if (key == "show_notification_speed") {
                    val value = intent.getBooleanExtra(EXTRA_SETTING_VALUE_BOOL, true)
                    Log.i(TAG, "Received setting update: $key = $value")
                    showNotificationSpeed = value
                    if (isRunning) {
                        requestNotificationUpdate(force = true)
                    }
                }
            }
            ACTION_PREPARE_RESTART -> {
                // ⭐ 2025-fix: 跨配置切换预清理机制
                // 在 VPN 重启前先关闭所有现有连接并触发网络震荡
                // 让应用（如 Telegram）立即感知网络中断，而不是在旧连接上等待超时
                Log.i(TAG, "Received ACTION_PREPARE_RESTART -> preparing for VPN restart")
                performPrepareRestart()
            }
            ACTION_HOT_RELOAD -> {
                // ⭐ 2025-fix: 内核级热重载
                // 在 VPN 运行时重载配置，不销毁 VPN 服务
                Log.i(TAG, "Received ACTION_HOT_RELOAD -> performing hot reload")
                val configContent = intent.getStringExtra(EXTRA_CONFIG_CONTENT)
                if (configContent.isNullOrEmpty()) {
                    Log.e(TAG, "ACTION_HOT_RELOAD: config content is empty")
                } else {
                    performHotReload(configContent)
                }
            }
        }
        // Use START_STICKY to allow system auto-restart if killed due to memory pressure
        // This prevents "VPN mysteriously stops" issue on Android 14+
        // System will restart service with null intent, we handle it gracefully above
        return START_STICKY
    }

    @Volatile private var pendingHotSwitchFallbackConfigPath: String? = null

    /**
     * 执行预清理操作
     * 在跨配置切换导致 VPN 重启前调用
     * 目的是让应用（如 Telegram）立即感知网络中断，避免在旧连接上等待超时
     *
     * 2025-fix-v2: 简化流程
     * 跨配置切换时 VPN 会完全重启，boxService.close() 会强制关闭所有连接
     * 所以这里只需要提前通知应用网络变化即可，不需要手动关闭连接
     */
    private fun performPrepareRestart() {
        if (!isRunning) {
            Log.w(TAG, "performPrepareRestart: VPN not running, skip")
            return
        }

        serviceScope.launch {
            try {
                Log.i(TAG, "[PrepareRestart] Step 1/3: Wake up core")
                boxService?.wake()

                // Step 2: 设置底层网络为 null，触发系统广播 CONNECTIVITY_CHANGE
                // 这是让 Telegram 等应用感知网络变化的关键步骤
                Log.i(TAG, "[PrepareRestart] Step 2/3: Disconnect underlying network to trigger system broadcast")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    setUnderlyingNetworks(null)
                }

                // Step 3: 等待应用收到广播
                // 不需要太长时间，因为VPN重启本身也需要时间
                Log.i(TAG, "[PrepareRestart] Step 3/3: Waiting for apps to process network change...")
                delay(100)

                // 注意：不需要调用 closeAllConnectionsImmediate()
                // 因为 VPN 重启时 boxService.close() 会强制关闭所有连接

                Log.i(TAG, "[PrepareRestart] Complete - apps should now detect network interruption")
            } catch (e: Exception) {
                Log.e(TAG, "performPrepareRestart error", e)
            }
        }
    }

    /**
     * 执行内核级热重载
     * 在 VPN 运行时重载配置，不销毁 VPN 服务
     * 失败时 Toast 报错并关闭 VPN，让用户手动重新打开
     */
    private fun performHotReload(configContent: String) {
        if (!isRunning) {
            Log.w(TAG, "performHotReload: VPN not running, skip")
            return
        }

        serviceScope.launch {
            try {
                Log.i(TAG, "[HotReload] Starting kernel-level hot reload...")

                // 更新 CoreManager 的设置，确保后续操作使用最新设置
                val settings = SettingsRepository.getInstance(applicationContext).settings.first()
                coreManager.setCurrentSettings(settings)

                val result = coreManager.hotReloadConfig(configContent, preserveSelector = true)

                result.onSuccess { success ->
                    if (success) {
                        Log.i(TAG, "[HotReload] Kernel hot reload succeeded")
                        LogRepository.getInstance().addLog("INFO [HotReload] Config reloaded successfully")

                        // Re-init BoxWrapperManager
                        boxService?.let { BoxWrapperManager.init(it) }

                        // Update notification
                        requestNotificationUpdate(force = true)
                    } else {
                        handleHotReloadFailure("Kernel hot reload not available")
                    }
                }.onFailure { e ->
                    handleHotReloadFailure("Hot reload failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "performHotReload error", e)
                handleHotReloadFailure("Hot reload error: ${e.message}")
            }
        }
    }

    private fun handleHotReloadFailure(errorMsg: String) {
        Log.e(TAG, "[HotReload] $errorMsg, stopping VPN")
        LogRepository.getInstance().addLog("ERROR [HotReload] $errorMsg")

        serviceScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(
                applicationContext,
                errorMsg,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        isManuallyStopped = false
        stopVpn(stopService = true)
    }

    /**
     * 启动 VPN (重构版 - 委托给 StartupManager)
     * 原方法 ~430 行，现在简化为 ~90 行
     */
    private fun startVpn(configPath: String) {
        // 状态检查（保留在 Service 中，因为涉及多线程同步）
        synchronized(this) {
            if (isRunning) {
                Log.w(TAG, "VPN already running, ignore start request")
                return
            }
            if (isStarting) {
                Log.w(TAG, "VPN is already in starting process, ignore start request")
                return
            }
            if (isStopping) {
                Log.w(TAG, "VPN is stopping, queue start request")
                pendingStartConfigPath = configPath
                stopSelfRequested = false
                lastConfigPath = configPath
                return
            }
            isStarting = true
        }

        lastConfigPath = configPath

        // 启动前台通知（必须在协程前调用）
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call startForeground", e)
        }

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
                    boxService = coreManager.boxService
                    updateServiceState(ServiceState.RUNNING)

                    // 初始化 BoxWrapperManager
                    boxService?.let { service ->
                        if (BoxWrapperManager.init(service)) {
                            Log.i(TAG, "BoxWrapperManager initialized")
                        }
                    }

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
            if (!isRunning && !isStopping && serviceState == ServiceState.STARTING) {
                updateServiceState(ServiceState.STOPPED)
            }
            updateTileState()
        }
    }

    private fun stopVpn(stopService: Boolean) {
        // 状态同步检查（保留在 Service 中，因为涉及多线程同步）
        synchronized(this) {
            stopSelfRequested = stopSelfRequested || stopService
            if (isStopping) {
                return
            }
            isStopping = true
        }

        // 更新状态
        updateServiceState(ServiceState.STOPPING)
        notificationManager.setSuppressUpdates(true)
        notificationManager.cancelNotification()
        updateTileState()

        // 发送 Tile 刷新广播
        runCatching {
            val intent = Intent(VpnTileService.ACTION_REFRESH_TILE).apply {
                `package` = packageName
            }
            sendBroadcast(intent)
        }

        // 重置 VPN 启动时间戳
        vpnStartedAtMs.set(0)
        stallRefreshAttempts = 0

        // 清理 networkManager (stopService 时释放)
        if (stopService) {
            networkManager?.reset()
            networkManager = null
        } else {
            networkManager?.reset()
        }

        Log.i(TAG, "stopVpn(stopService=$stopService) isManuallyStopped=$isManuallyStopped")

        // 委托给 ShutdownManager
        cleanupJob = shutdownManager.stopVpn(
            options = ShutdownManager.ShutdownOptions(
                stopService = stopService,
                preserveTunInterface = !stopService
            ),
            coreManager = coreManager,
            commandManager = commandManager,
            healthMonitor = healthMonitorWrapper,
            trafficMonitor = trafficMonitor,
            networkManager = networkManager,
            notificationManager = notificationManager,
            selectorManager = serviceSelectorManager,
            platformInterfaceImpl = platformInterfaceImpl,
            callbacks = shutdownCallbacks
        )
    }

    private fun updateTileState() {
        try {
            TileService.requestListeningState(this, ComponentName(this, VpnTileService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update tile state", e)
        }
    }

    private fun buildNotificationState(): VpnNotificationManager.NotificationState {
        val configRepository = ConfigRepository.getInstance(this)
        val activeNodeId = configRepository.activeNodeId.value
        // 2025-fix: 优先使用内存中的 realTimeNodeName，然后是持久化的 VpnStateStore.activeLabel
        // 最后才回退到 configRepository（可能在跨进程时不同步）
        val nodeName = realTimeNodeName
            ?: VpnStateStore.getActiveLabel().takeIf { it.isNotBlank() }
            ?: configRepository.nodes.value.find { it.id == activeNodeId }?.name

        return VpnNotificationManager.NotificationState(
            isRunning = isRunning,
            isStopping = isStopping,
            activeNodeName = nodeName,
            showSpeed = showNotificationSpeed,
            uploadSpeed = currentUploadSpeed,
            downloadSpeed = currentDownloadSpeed
        )
    }

    private fun requestNotificationUpdate(force: Boolean = false) {
        notificationManager.requestNotificationUpdate(buildNotificationState(), this, force)
    }

    private fun createNotification(): Notification {
        return notificationManager.createNotification(buildNotificationState())
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy called -> stopVpn(stopService=false) pid=${android.os.Process.myPid()}")
        TrafficRepository.getInstance(this).saveStats()

        // 清理省电管理器引用
        SingBoxIpcHub.setPowerManager(null)
        screenStateManager.setPowerManager(null)
        backgroundPowerManager.cleanup()

        screenStateManager.unregisterActivityLifecycleCallbacks(application)

        // Ensure critical state is saved synchronously before we potentially halt
        if (!isManuallyStopped) {
            // If we are being destroyed but not manually stopped (e.g. app update or system kill),
            // ensure we don't accidentally mark it as manually stopped, but we DO mark VPN as inactive.
            VpnTileService.persistVpnState(applicationContext, false)
            VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
            Log.i(TAG, "onDestroy: Persisted vpn_active=false, mode=NONE")
        }

        val shouldStop = runCatching {
            synchronized(this@SingBoxService) {
                isRunning || isStopping || boxService != null || vpnInterface != null
            }
        }.getOrDefault(false)

        if (shouldStop) {
            // Note: stopVpn launches a cleanup job on cleanupScope.
            // If we halt() immediately, that job will die.
            // For app updates, the system kills us anyway, so cleanup might be best-effort.
            stopVpn(stopService = false)
        } else {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            VpnTileService.persistVpnState(applicationContext, false)
            updateServiceState(ServiceState.STOPPED)
            updateTileState()
        }

        serviceSupervisorJob.cancel()
        // cleanupSupervisorJob.cancel() // Allow cleanup to finish naturally

        if (instance == this) {
            instance = null
        }
        super.onDestroy()

        // Kill process to fully reset Go runtime state and prevent zombie states.
        // This ensures clean restart if system decides to recreate the service.
        Log.i(TAG, "SingBoxService destroyed. Halting process ${android.os.Process.myPid()}.")

        // 同步取消通知，防止 halt(0) 后通知残留
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.cancel(com.kunk.singbox.service.notification.VpnNotificationManager.NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }

        // Give a tiny breath for logs to flush
        try { Thread.sleep(50) } catch (e: Exception) { Log.w(TAG, "Sleep interrupted during force kill", e) }

        Runtime.getRuntime().halt(0)
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke called -> stopVpn(stopService=true)")
        isManuallyStopped = true
        // Another VPN took over. Persist OFF state immediately so QS tile won't stay active.
        VpnTileService.persistVpnState(applicationContext, false)
        VpnTileService.persistVpnPending(applicationContext, "")
        setLastError("VPN revoked by system (another VPN may have started)")
        updateServiceState(ServiceState.STOPPED)
        updateTileState()

        // 记录日志，告知用户原因
        com.kunk.singbox.repository.LogRepository.getInstance()
            .addLog("WARN: VPN permission revoked by system (possibly another VPN app started)")

        // 发送通知提醒用户
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val notification = Notification.Builder(this, VpnNotificationManager.CHANNEL_ID)
                .setContentTitle("VPN Disconnected")
                .setContentText("VPN permission revoked, possibly by another VPN app.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .build()
            manager.notify(VpnNotificationManager.NOTIFICATION_ID + 1, notification)
        }

        // 停止服务
        stopVpn(stopService = true)
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // If the user swiped away the app, we might want to keep the VPN running
        // as a foreground service, but some users expect it to stop.
        // Usually, a foreground service continues running.
        // However, if we want to ensure no "zombie" states, we can at least log or check health.
    }

    /**
     * 确保网络回调就绪，最多等待指定超时时间
     * 如果超时仍未就绪，尝试主动采样当前活跃网络
     */
    private suspend fun ensureNetworkCallbackReadyWithTimeout(timeoutMs: Long = 2000L) {
        networkHelper.ensureNetworkCallbackReady(
            networkCallbackReady = networkCallbackReady,
            lastKnownNetwork = lastKnownNetwork,
            findBestPhysicalNetwork = { findBestPhysicalNetwork() },
            updateNetworkState = { network, ready ->
                lastKnownNetwork = network
                networkCallbackReady = ready
            },
            timeoutMs = timeoutMs
        )
    }

    /**
     * 后台异步更新规则集 - 性能优化
     * VPN 启动成功后 5 秒，在后台静默更新规则集
     * 这样启动时不需要等待规则集下载
     */
    private fun scheduleAsyncRuleSetUpdate() {
        serviceScope.launch(Dispatchers.IO) {
            delay(5000) // 等待 VPN 稳定

            if (!isRunning || isStopping) {
                Log.d(TAG, "scheduleAsyncRuleSetUpdate: VPN not running, skip")
                return@launch
            }

            try {
                val ruleSetRepo = RuleSetRepository.getInstance(this@SingBoxService)
                val now = System.currentTimeMillis()
                if (now - lastRuleSetCheckMs >= ruleSetCheckIntervalMs) {
                    lastRuleSetCheckMs = now
                    Log.i(TAG, "Starting async rule set update...")
                    val allReady = ruleSetRepo.ensureRuleSetsReady(
                        forceUpdate = false,
                        allowNetwork = true
                    ) { progress ->
                        Log.d(TAG, "Async rule set update: $progress")
                    }
                    Log.i(TAG, "Async rule set update completed, allReady=$allReady")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Async rule set update failed", e)
            }
        }
    }

    private suspend fun waitForUsablePhysicalNetwork(timeoutMs: Long): Network? {
        return networkHelper.waitForUsablePhysicalNetwork(
            lastKnownNetwork = lastKnownNetwork,
            networkManager = networkManager,
            findBestPhysicalNetwork = { findBestPhysicalNetwork() },
            timeoutMs = timeoutMs
        )
    }
}
