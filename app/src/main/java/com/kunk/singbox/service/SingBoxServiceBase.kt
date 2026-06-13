package com.kunk.singbox.service

import android.app.Notification
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.service.network.NetworkManager
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.service.manager.ConnectManager
import com.kunk.singbox.service.manager.SelectorManager as ServiceSelectorManager
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.manager.CoreManager
import com.kunk.singbox.service.manager.NetworkHelper
import com.kunk.singbox.service.manager.PlatformInterfaceImpl
import com.kunk.singbox.service.manager.ShutdownManager
import com.kunk.singbox.service.manager.ScreenStateManager
import com.kunk.singbox.service.manager.RouteGroupSelector
import com.kunk.singbox.service.manager.ForeignVpnMonitor
import com.kunk.singbox.service.manager.NodeSwitchManager
import com.kunk.singbox.service.manager.BackgroundPowerManager
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import com.kunk.singbox.utils.KernelHttpClient
import com.kunk.singbox.utils.NetworkClient
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Suppress("TooManyFunctions")
abstract class SingBoxServiceBase : VpnService() {
    protected val gson = Gson()

    // ===== 新架构 Managers =====
    // 核心管理器 (VPN 启动/停止)

    protected val coreManager: CoreManager by lazy {
        CoreManager(this, this, serviceScope)
    }

    // 连接管理器

    protected val connectManager: ConnectManager by lazy {
        ConnectManager(this, serviceScope)
    }

    // 节点选择管理器

    protected val serviceSelectorManager: ServiceSelectorManager by lazy {
        ServiceSelectorManager()
    }

    // 路由组自动选择管理器

    protected val routeGroupSelector: RouteGroupSelector by lazy {
        RouteGroupSelector(this, serviceScope)
    }

    // Command 管理器 (Server/Client 交互)

    protected val commandManager: CommandManager by lazy {
        CommandManager(this, serviceScope)
    }

    // Platform Interface 实现 (提取自原内联实现)

    protected val platformInterfaceImpl: PlatformInterfaceImpl by lazy {
        PlatformInterfaceImpl(
            context = this,
            serviceScope = serviceScope,
            mainHandler = mainHandler,
            callbacks = platformCallbacks
        )
    }

    // 网络辅助工具

    protected val networkHelper: NetworkHelper by lazy {
        NetworkHelper(this, serviceScope)
    }

    // 启动管理器

    protected val startupManager: com.kunk.singbox.service.manager.StartupManager by lazy {
        com.kunk.singbox.service.manager.StartupManager(this, this, serviceScope)
    }

    // 关闭管理器

    protected val shutdownManager: com.kunk.singbox.service.manager.ShutdownManager by lazy {
        com.kunk.singbox.service.manager.ShutdownManager(this, cleanupScope)
    }

    // 屏幕状态管理器

    protected val screenStateManager: ScreenStateManager by lazy {
        ScreenStateManager(this, serviceScope)
    }

    // 外部 VPN 监控器

    protected val foreignVpnMonitor: ForeignVpnMonitor by lazy {
        ForeignVpnMonitor(this)
    }

    protected val nodeSwitchManager: NodeSwitchManager by lazy {
        NodeSwitchManager(this, serviceScope)
    }

    protected val backgroundPowerManager: BackgroundPowerManager by lazy {
        BackgroundPowerManager(serviceScope)
    }

    @Volatile
    protected var backgroundPowerSavingThresholdMs: Long = BackgroundPowerSavingDelay.MINUTES_30.delayMs

    // PlatformInterfaceImpl 回调实现

    protected val platformCallbacks = object : PlatformInterfaceImpl.Callbacks {
        override fun protect(fd: Int): Boolean = this@SingBoxServiceBase.protect(fd)

        override fun openTun(options: TunOptions): Result<Int> {
            isConnectingTun.set(true)
            return try {
                val network = connectManager.getCurrentNetwork()
                val result = coreManager.openTun(options, network, reuseExisting = true)
                result.onSuccess { _ ->
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
            this@SingBoxServiceBase.requestCoreNetworkReset(reason, force)
        }
        override fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean) {
            serviceScope.launch {
                BoxWrapperManager.resetAllConnections(true)
                Log.i(SingBoxService.TAG, "resetConnectionsOptimal: $reason")
            }
        }
        override fun setUnderlyingNetworks(networks: Array<Network>?) {
            this@SingBoxServiceBase.setUnderlyingNetworks(networks)
        }

        override fun isRunning(): Boolean = ServiceStateHolder.isRunning
        override fun isStarting(): Boolean = ServiceStateHolder.isStarting
        override fun isManuallyStopped(): Boolean = ServiceStateHolder.isManuallyStopped
        override fun getLastConfigPath(): String? = ServiceStateHolder.lastConfigPath
        override fun getCurrentSettings(): AppSettings? = currentSettings

        override fun incrementConnectionOwnerCalls() { ServiceStateHolder.incrementConnectionOwnerCalls() }
        override fun incrementConnectionOwnerInvalidArgs() { ServiceStateHolder.incrementConnectionOwnerInvalidArgs() }
        override fun incrementConnectionOwnerUidResolved() { ServiceStateHolder.incrementConnectionOwnerUidResolved() }
        override fun incrementConnectionOwnerSecurityDenied() {
            ServiceStateHolder.incrementConnectionOwnerSecurityDenied()
        }
        override fun incrementConnectionOwnerOtherException() {
            ServiceStateHolder.incrementConnectionOwnerOtherException()
        }
        override fun setConnectionOwnerLastEvent(event: String) {
            ServiceStateHolder.setConnectionOwnerLastEvent(event)
        }
        override fun setConnectionOwnerLastUid(uid: Int) {
            ServiceStateHolder.setConnectionOwnerLastUid(uid)
        }
        override fun isConnectionOwnerPermissionDeniedLogged(): Boolean =
            ServiceStateHolder.connectionOwnerPermissionDeniedLogged
        override fun setConnectionOwnerPermissionDeniedLogged(logged: Boolean) {
            ServiceStateHolder.connectionOwnerPermissionDeniedLogged = logged
        }

        override fun cacheUidToPackage(uid: Int, packageName: String) {
            this@SingBoxServiceBase.cacheUidToPackage(uid, packageName)
        }
        override fun getUidFromCache(uid: Int): String? = uidToPackageCache[uid]

        override fun findBestPhysicalNetwork(): Network? = this@SingBoxServiceBase.findBestPhysicalNetwork()
    }

    // 通知管理器 (原有)

    protected val notificationManager: VpnNotificationManager by lazy {
        VpnNotificationManager(this, serviceScope)
    }

    protected val remoteStateUpdateDebounceMs: Long = 250L

    protected val lastRemoteStateUpdateAtMs = AtomicLong(0L)
    @Volatile protected var remoteStateUpdateJob: Job? = null

    @Suppress("TooManyFunctions")
    protected val startupCallbacks = object : com.kunk.singbox.service.manager.StartupManager.Callbacks {
        // 状态回调
        override fun onStarting() {
            updateServiceState(ServiceState.STARTING)
            realTimeNodeName = null
            vpnLinkValidated = false
        }

        override fun onStarted(configContent: String) {
            Log.i(SingBoxService.TAG, "KunBox VPN started successfully")
            notificationManager.setSuppressUpdates(false)
            autoFailoverServiceStartedAtMs = System.currentTimeMillis()
            isProxyIdleForAutoFailover = false

            // BoxWrapperManager 在 libbox 启动后初始化，避免 hasSelector() 超时
            commandManager.getCommandServer()?.let { server ->
                BoxWrapperManager.init(server)
            }
            Log.i(SingBoxService.TAG, "BoxWrapperManager initialized")

            // 初始化 KernelHttpClient 的代理端口
            serviceScope.launch {
                KernelHttpClient.updateProxyPortFromSettings(this@SingBoxServiceBase)
            }
        }

        override fun onFailed(error: String) {
            Log.e(SingBoxService.TAG, error)
            setLastError(error)
            notificationManager.setSuppressUpdates(true)
            notificationManager.cancelNotification()
            updateServiceState(ServiceState.STOPPED)
        }

        override fun onCancelled() {
            Log.i(SingBoxService.TAG, "startVpn cancelled")
            if (!isStopping) {
                Log.w(SingBoxService.TAG, "startVpn cancelled but not by stopVpn, resetting state to STOPPED")
                SingBoxService.isRunning = false
                updateServiceState(ServiceState.STOPPED)
            }
        }

        // 通知管理
        override fun createNotification(): Notification = this@SingBoxServiceBase.createNotification()
        override fun markForegroundStarted() { notificationManager.markForegroundStarted() }

        // 生命周期管理
        override fun registerScreenStateReceiver() { screenStateManager.registerScreenStateReceiver() }
        override fun startForeignVpnMonitor() { foreignVpnMonitor.start() }
        override fun stopForeignVpnMonitor() { foreignVpnMonitor.stop() }
        override fun detectExistingVpns(): Boolean = foreignVpnMonitor.hasExistingVpn()

        // 组件初始化
        override fun initSelectorManager(configContent: String) {
            this@SingBoxServiceBase.initSelectorManager(configContent)
        }

        override fun createAndStartCommandServer(): Result<Unit> {
            return runCatching {
                // 1. 创建 CommandServer
                val server = commandManager.createServer(platformInterfaceImpl).getOrThrow()
                // 2. 设置到 CoreManager
                coreManager.setCommandServer(server)
                // 3. 启动 CommandServer
                commandManager.startServer().getOrThrow()
                Log.i(SingBoxService.TAG, "CommandServer created and started")
            }
        }

        override fun startCommandClients() {
            commandManager.startClients().onFailure { e ->
                Log.e(SingBoxService.TAG, "Failed to start Command Clients", e)
            }
            serviceSelectorManager.updateCommandClient(commandManager.getCommandClient())
        }

        override fun startRouteGroupAutoSelect(configContent: String) {
            routeGroupSelector.start(configContent)
        }

        override fun scheduleAsyncRuleSetUpdate() {
            this@SingBoxServiceBase.scheduleAsyncRuleSetUpdate()
        }

        override fun startHealthMonitor() {
            // 健康监控已移除，保留空实现
            Log.i(SingBoxService.TAG, "Health monitor disabled (simplified mode)")
        }

        override fun scheduleKeepaliveWorker() {
            VpnKeepaliveWorker.schedule(applicationContext)
            Log.i(SingBoxService.TAG, "VPN keepalive worker scheduled")
        }

        override fun startTrafficMonitor() {
            trafficMonitor.start(Process.myUid(), trafficListener)
            networkManager = NetworkManager(this@SingBoxServiceBase, this@SingBoxServiceBase)
        }

        // 状态管理
        override fun updateTileState() { this@SingBoxServiceBase.updateTileState() }
        override fun setIsRunning(running: Boolean) { SingBoxService.isRunning = running; NetworkClient.onVpnStateChanged(running) }
        override fun setIsStarting(starting: Boolean) { SingBoxService.isStarting = starting }
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
            return this@SingBoxServiceBase.waitForUsablePhysicalNetwork(timeoutMs)
        }

        override suspend fun ensureNetworkCallbackReady(timeoutMs: Long) {
            this@SingBoxServiceBase.ensureNetworkCallbackReadyWithTimeout(timeoutMs)
        }

        override fun setLastKnownNetwork(network: Network?) { lastKnownNetwork = network }
        override fun setNetworkCallbackReady(ready: Boolean) { networkCallbackReady = ready }

        override fun restoreUnderlyingNetwork(network: Network) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setUnderlyingNetworks(arrayOf(network))
                Log.i(SingBoxService.TAG, "Underlying network restored before libbox start: $network")
            }
        }

        // 清理
        override suspend fun waitForCleanupJob() {
            val cleanup = cleanupJob
            if (cleanup != null && cleanup.isActive) {
                Log.i(SingBoxService.TAG, "Waiting for previous service cleanup...")
                cleanup.join()
                Log.i(SingBoxService.TAG, "Previous cleanup finished")
            }
        }

        override fun stopSelf() { this@SingBoxServiceBase.stopSelf() }
    }

    // ShutdownManager 回调实现

    protected val shutdownCallbacks = object : ShutdownManager.Callbacks {
        // 状态管理
        override fun updateServiceState(state: ServiceState) {
            this@SingBoxServiceBase.updateServiceState(state)
        }
        override fun updateTileState() { this@SingBoxServiceBase.updateTileState() }
        override fun stopForegroundService() {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.e(SingBoxService.TAG, "Error stopping foreground", e)
            }
        }
        override fun stopSelf() {
            if (stopSelfRequested) {
                this@SingBoxServiceBase.stopSelf()
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
        override fun cancelRemoteStateUpdateJob() {
            remoteStateUpdateJob?.cancel()
            remoteStateUpdateJob = null
        }
        override fun cancelRouteGroupAutoSelectJob() {
            routeGroupSelector.stop()
        }
        override fun cancelAutoFailoverJob() {
            autoFailoverJob?.cancel()
            autoFailoverJob = null
            isProxyIdleForAutoFailover = false
            autoFailoverServiceStartedAtMs = 0L
        }

        // 资源清理
        override fun stopForeignVpnMonitor() { foreignVpnMonitor.stop() }
        override fun tryClearRunningServiceForLibbox() {
            this@SingBoxServiceBase.tryClearRunningServiceForLibbox()
        }
        override fun unregisterScreenStateReceiver() {
            screenStateManager.unregisterScreenStateReceiver()
        }
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            platformInterfaceImpl.closeDefaultInterfaceMonitor(listener)
        }

        // 获取状态
        override fun isServiceRunning(): Boolean = coreManager.isServiceRunning()
        override fun getVpnInterface(): ParcelFileDescriptor? = vpnInterface
        override fun getCurrentInterfaceListener(): InterfaceUpdateListener? = currentInterfaceListener
        override fun getConnectivityManager(): ConnectivityManager? = connectivityManager

        // 设置状态
        override fun setVpnInterface(fd: ParcelFileDescriptor?) { vpnInterface = fd }
        override fun setIsRunning(running: Boolean) { SingBoxService.isRunning = running }
        override fun setRealTimeNodeName(name: String?) {
            realTimeNodeName = name
            if (!name.isNullOrBlank() && name == pendingNodeName) {
                pendingNodeName = null
            }
        }
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
        override fun getPendingStartConfigPath(): String? = synchronized(this@SingBoxServiceBase) {
            val pending = pendingStartConfigPath
            stopSelfRequested = false
            pending
        }
        override fun clearPendingStartConfigPath() = synchronized(this@SingBoxServiceBase) {
            pendingStartConfigPath = null
            isStopping = false
        }
        override fun startVpn(configPath: String) {
            this@SingBoxServiceBase.startVpn(configPath)
        }

        // 检查 VPN 接口是否可复用
        override fun hasExistingTunInterface(): Boolean = vpnInterface != null
    }

    /**
     * 初始化 SelectorManager - 记录 PROXY selector 的 outbound 列表
     *
     */

    protected var vpnInterface: ParcelFileDescriptor? = null

    protected var currentSettings: AppSettings? = null

    protected val serviceSupervisorJob = SupervisorJob()

    protected val serviceScope = CoroutineScope(Dispatchers.IO + serviceSupervisorJob)

    protected val cleanupSupervisorJob = SupervisorJob()

    protected val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupSupervisorJob)

    protected val autoFailoverSupervisorJob = SupervisorJob()

    protected val autoFailoverDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vpn-auto-failover").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }.asCoroutineDispatcher()

    protected val autoFailoverScope = CoroutineScope(autoFailoverDispatcher + autoFailoverSupervisorJob)
    @Volatile protected var isStopping: Boolean = false
    @Volatile protected var stopSelfRequested: Boolean = false
    @Volatile protected var cleanupJob: Job? = null
    @Volatile protected var autoFailoverJob: Job? = null
    @Volatile protected var pendingStartConfigPath: String? = null

    @Volatile protected var pendingHotSwitchFallbackConfigPath: String? = null
    @Volatile protected var pendingNodeName: String? = null
    @Volatile protected var pendingCleanCache: Boolean = false

    @Volatile protected var startVpnJob: Job? = null
    @Volatile protected var realTimeNodeName: String? = null
    // @Volatile protected var nodePollingJob: Job? = null // Removed in favor of CommandClient

    protected val isConnectingTun = AtomicBoolean(false)

    // Command 相关变量已移至 CommandManager
    // 保留这些作为兼容性别名 (委托到 commandManager)

    protected val activeConnectionNode: String? get() = commandManager.activeConnectionNode

    protected val activeConnectionLabel: String? get() = commandManager.activeConnectionLabel

    protected val recentConnectionIds: List<String> get() = commandManager.recentConnectionIds

    // 速度计算相关 - 委托给 TrafficMonitor
    @Volatile protected var showNotificationSpeed: Boolean = true

    protected var currentUploadSpeed: Long = 0L

    protected var currentDownloadSpeed: Long = 0L

    // TrafficMonitor 实例 - 统一管理流量监控和卡死检测

    protected val trafficMonitor = TrafficMonitor(serviceScope)
    @Volatile protected var lastMeaningfulTrafficAtMs: Long = 0L
    @Volatile protected var isProxyIdleForAutoFailover: Boolean = false
    @Volatile protected var autoFailoverServiceStartedAtMs: Long = 0L
    @Volatile protected var lastAutoFailoverNetworkEventAtMs: Long = 0L

    protected val trafficListener = object : TrafficMonitor.Listener {
        override fun onTrafficUpdate(snapshot: TrafficMonitor.TrafficSnapshot) {
            currentUploadSpeed = snapshot.uploadSpeed
            currentDownloadSpeed = snapshot.downloadSpeed
            handleTrafficUpdateForAutoFailover(snapshot)
            if (showNotificationSpeed) {
                requestNotificationUpdate(force = false)
            }
        }

        override fun onTrafficStall(consecutiveCount: Int) {
            stallRefreshAttempts++
            val maxAttempts = maxStallRefreshAttempts
            Log.d(SingBoxService.TAG, "Traffic stall detected (count=$consecutiveCount, attempt=$stallRefreshAttempts/$maxAttempts)")

            if (stallRefreshAttempts >= maxStallRefreshAttempts * 2) {
                Log.w(SingBoxService.TAG, "Persistent traffic stall after $stallRefreshAttempts attempts")
                LogRepository.getInstance().addLog(
                    "WARN: Traffic stall detected, attempting gentle recovery..."
                )
                stallRefreshAttempts = 0
                trafficMonitor.resetStallCounter()
                serviceScope.launch {
                    val closed = BoxWrapperManager.closeIdleConnections(30)
                    Log.i(SingBoxService.TAG, "Closed $closed idle connections for traffic stall")
                }
            } else {
                serviceScope.launch {
                    try {
                        val closed = BoxWrapperManager.closeIdleConnections(30)
                        if (closed > 0) {
                            Log.i(SingBoxService.TAG, "Closed $closed idle connections after stall")
                        }
                    } catch (e: Exception) {
                        Log.w(SingBoxService.TAG, "Failed to close idle connections after stall", e)
                    }
                    trafficMonitor.resetStallCounter()
                }
            }

            submitAutoFailoverSuspicion("traffic_stall:$consecutiveCount")
        }

        override fun onProxyIdle(idleDurationMs: Long) {
            isProxyIdleForAutoFailover = true
            val idleSeconds = idleDurationMs / 1000

            // 条件化恢复：避免在“无连接/无需恢复”时触发重置导致抖动。
            if (!BoxWrapperManager.isAvailable()) {
                Log.d(SingBoxService.TAG, "Proxy idle detected (${idleSeconds}s) but Box not available, skip reset")
                return
            }

            val connCount = runCatching { BoxWrapperManager.getConnectionCount() }.getOrDefault(0)
            val needRecovery = runCatching { BoxWrapperManager.isNetworkRecoveryNeeded() }.getOrDefault(false)

            if (connCount <= 0 && !needRecovery) {
                Log.d(
                    SingBoxService.TAG,
                    "Proxy idle detected (${idleSeconds}s) but no active connections and recovery not needed"
                )
                return
            }

            Log.i(
                SingBoxService.TAG,
                "Proxy idle ($idleSeconds s), reset conn (cnt=$connCount need=$needRecovery)"
            )
            serviceScope.launch {
                BoxWrapperManager.resetAllConnections(true)
            }
        }
    }

    protected var stallRefreshAttempts: Int = 0

    protected val maxStallRefreshAttempts: Int = 3 // 连续3次stall刷新后仍无流量则重启服务

    protected var networkManager: NetworkManager? = null

    @Volatile protected var lastRuleSetCheckMs: Long = 0L

    protected val ruleSetCheckIntervalMs: Long = 6 * 60 * 60 * 1000L

    protected val uidToPackageCache = ConcurrentHashMap<Int, String>()

    protected val maxUidToPackageCacheSize: Int = 512

    protected val isScreenOn: Boolean get() = screenStateManager.isScreenOn

    protected val isAppInForeground: Boolean get() = screenStateManager.isAppInForeground

    // Auto reconnect

    protected var connectivityManager: ConnectivityManager? = null

    protected var currentInterfaceListener: InterfaceUpdateListener? = null

    protected var defaultInterfaceName: String = ""

    protected val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    protected var lastKnownNetwork: Network? = null

    protected var vpnHealthJob: Job? = null
    @Volatile protected var vpnLinkValidated: Boolean = false

    // 网络就绪标志：确保 Libbox 启动前网络回调已完成初始采样
    @Volatile protected var networkCallbackReady: Boolean = false
    @Volatile protected var noPhysicalNetworkWarningLogged: Boolean = false

    // setUnderlyingNetworks 防抖机制 - 避免频繁调用触发系统提示音

    protected val lastSetUnderlyingNetworksAtMs = AtomicLong(0)

    protected val setUnderlyingNetworksDebounceMs: Long = 2000L // 2秒防抖

    // VPN 启动窗口期保护
    // 在 VPN 启动后的短时间内，updateDefaultInterface 跳过 setUnderlyingNetworks 调用

    protected val vpnStartedAtMs = AtomicLong(0)

    protected val vpnStartupWindowMs: Long = 3000L

    @Volatile protected var lastConnectionsResetAtMs: Long = 0L

    protected val connectionsResetDebounceMs: Long = 2000L

    // ACTION_PREPARE_RESTART 防抖：避免短时间内重复触发导致网络反复震荡

    protected val lastPrepareRestartAtMs = AtomicLong(0L)

    protected val prepareRestartDebounceMs: Long = 1500L

    protected val recoveryGlobalDebounceMs: Long = 800L

    protected val recoveryFastLaneGlobalDebounceMs: Long = 150L

    protected val recoveryFastLaneSourceDebounceCapMs: Long = 400L

    protected val recoveryMergeWindowMs: Long = 250L

    @Volatile protected var recoveryInFlight: Boolean = false
    @Volatile protected var pendingRecoveryRequest: RecoveryRequest? = null
    @Volatile protected var recoveryMergeJob: Job? = null
    @Volatile protected var pendingMergeRequest: RecoveryRequest? = null

    protected val recoveryLastTriggeredAtMs = AtomicLong(0L)

    protected val recoveryTriggerCount = AtomicLong(0L)

    protected val recoveryMergedCount = AtomicLong(0L)

    protected val recoverySkippedDebounceCount = AtomicLong(0L)

    protected val recoverySoftCount = AtomicLong(0L)

    protected val recoveryHardCount = AtomicLong(0L)

    protected val recoverySuccessCount = AtomicLong(0L)

    protected val recoveryFailureCount = AtomicLong(0L)

    protected val recoveryConsecutiveFailureCount = AtomicInteger(0)

    protected val recoveryReasonLastAtMs = ConcurrentHashMap<String, Long>()

    protected val foregroundRecoveryGraceMs: Long = 3000L

    protected var foregroundHardFallbackJob: Job? = null

    protected val lastForegroundHardFallbackAtMs = AtomicLong(0L)

    protected val foregroundHardFallbackDebounceMs: Long = 15000L

    protected val networkTypeChangedRecoveryGraceMs: Long = 4000L

    protected var networkTypeChangedFallbackJob: Job? = null

    protected val lastNetworkTypeChangedHardFallbackAtMs = AtomicLong(0L)

    protected val lastNetworkTypeChangedRestartAtMs = AtomicLong(0L)

    protected val networkTypeChangedHardFallbackDebounceMs: Long = 8000L

    protected val networkTypeChangedRestartDebounceMs: Long = 20000L

    // Virtual declarations keep split class logic callable across files.
    protected abstract fun tryRegisterRunningServiceForLibbox()

    protected abstract fun tryClearRunningServiceForLibbox()

    protected abstract fun initManagers()

    protected abstract fun initConnectManager()

    protected abstract fun initServiceSelectorManager()

    protected abstract fun initCommandManager()

    protected abstract fun initSecondaryManagers()

    protected abstract fun initBackgroundPowerManager()

    protected abstract fun initSelectorManager(configContent: String)

    abstract suspend fun urlTestGroup(groupTag: String, timeoutMs: Long = 10000L): Map<String, Int>

    abstract suspend fun urlTestGroup(
        groupTag: String,
        timeoutMs: Long,
        expectedTags: Set<String>,
        onProgress: ((Map<String, Int>) -> Unit)? = null
    ): Map<String, Int>

    protected abstract fun closeRecentConnectionsBestEffort(reason: String)

    protected abstract suspend fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean = false)

    protected abstract fun resolveEgressNodeName(repo: ConfigRepository, tagOrSelector: String?): String?

    protected abstract fun notifyRemoteStateNow()

    protected abstract fun requestRemoteStateUpdate(force: Boolean = false)

    protected abstract fun updateServiceState(state: ServiceState)

    abstract suspend fun hotSwitchNode(nodeTag: String): Boolean

    protected abstract fun cacheUidToPackage(uid: Int, pkg: String)

    protected abstract fun requestCoreNetworkReset(reason: String, force: Boolean = false)

    protected abstract fun parseRecoveryReason(reason: String): RecoveryReason

    protected abstract fun handleTrafficUpdateForAutoFailover(snapshot: TrafficMonitor.TrafficSnapshot)

    protected abstract fun submitAutoFailoverSuspicion(trigger: String)

    protected abstract fun isAutoFailoverStartupGracePeriod(nowAtMs: Long): Boolean

    protected abstract fun isAutoFailoverNetworkGracePeriod(nowAtMs: Long): Boolean

    protected abstract suspend fun runAutoFailoverProbeSequence(trigger: String)

    protected abstract suspend fun handleSecondAutoFailoverProbe(
        currentTag: String,
        firstEvaluation: NodeAutoFailoverPolicy.ProbeEvaluation,
        trigger: String
    )

    protected abstract suspend fun runAutoFailoverProbeRound(
        currentTag: String
    ): NodeAutoFailoverPolicy.ProbeEvaluation

    protected abstract suspend fun testGroupCandidatesLatency(groupTag: String): Map<String, Int>

    protected abstract fun loadLastRunningConfig(): SingBoxConfig?

    protected abstract suspend fun performAutoFailoverSwitch(
        currentTag: String,
        targetTag: String,
        trigger: String
    )

    protected abstract fun loadActiveAutoFailoverQuarantine(nowAtMs: Long): List<NodeAutoFailoverPolicy.QuarantinedNode>

    protected abstract fun resolveCurrentProxyOutboundTag(): String?

    protected abstract fun submitRecoveryRequest(request: RecoveryRequest)

    protected abstract fun mergeRecoveryRequests(
        existing: RecoveryRequest,
        incoming: RecoveryRequest
    ): RecoveryRequest

    protected abstract fun cancelPendingRecoveryWork()

    protected abstract fun recoveryInvalidStateSummary(): String?

    protected abstract fun buildRecoveryDebounceContext(request: RecoveryRequest): SingBoxServiceRecoveryDebounceContext

    protected abstract fun shouldSkipByGlobalDebounce(
        request: RecoveryRequest,
        context: SingBoxServiceRecoveryDebounceContext
    ): Boolean

    protected abstract fun shouldSkipBySourceDebounce(
        request: RecoveryRequest,
        context: SingBoxServiceRecoveryDebounceContext
    ): Boolean

    protected abstract fun requestImmediateRouteGroupReselectIfNeeded(request: RecoveryRequest)

    protected abstract fun convergeConnectionsAfterImmediateRouteGroupSwitch(
        groupTag: String,
        previousSelectedTag: String,
        newSelectedTag: String,
        rawReason: String
    )

    protected abstract suspend fun executeRecoveryRequest(request: RecoveryRequest)

    protected abstract fun calculateRecoverySuccessRate(): String

    protected abstract fun isSelectedHysteria2Outbound(): Boolean

    protected abstract fun getRecoveryProfile(): RecoveryProfile

    protected abstract fun executeForegroundFastRecovery(request: RecoveryRequest)

    protected abstract fun evaluateForegroundFallbackState(): SingBoxServiceForegroundFallbackState

    protected abstract fun scheduleForegroundHardFallbackIfNeeded(
        request: RecoveryRequest,
        mode: BoxWrapperManager.RecoveryMode,
        success: Boolean
    )

    protected abstract suspend fun collectNetworkTypeChangedRecoverySignal(): SingBoxServiceNetworkTypeChangedRecoverySignal

    protected abstract fun evaluateNetworkTypeChangedFallbackState(
        mode: BoxWrapperManager.RecoveryMode,
        signal: SingBoxServiceNetworkTypeChangedRecoverySignal
    ): SingBoxServiceNetworkTypeChangedFallbackState

    protected abstract fun buildTriggeredNetworkTypeChangedFallbackState(
        mode: BoxWrapperManager.RecoveryMode,
        signalOutcome: String
    ): SingBoxServiceNetworkTypeChangedFallbackState

    protected abstract fun buildNetworkTypeChangedStateSkip(): SingBoxServiceNetworkTypeChangedFallbackState?

    protected abstract fun resolveLastNetworkTypeChangedFallbackAtMs(
        action: NetworkTypeChangedFallbackAction
    ): Long

    protected abstract fun resolveNetworkTypeChangedFallbackDebounceMs(
        action: NetworkTypeChangedFallbackAction
    ): Long

    protected abstract fun recordNetworkTypeChangedFallbackAt(
        action: NetworkTypeChangedFallbackAction,
        now: Long
    )

    protected abstract fun scheduleNetworkTypeChangedFallbackIfNeeded(
        request: RecoveryRequest,
        mode: BoxWrapperManager.RecoveryMode,
        success: Boolean
    )

    protected abstract fun logRecoveryEvent(
        event: String,
        request: RecoveryRequest,
        mode: BoxWrapperManager.RecoveryMode?,
        merged: Boolean,
        skipped: Boolean,
        outcome: String?
    )

    protected abstract suspend fun restartVpnService(reason: String)

    protected abstract fun findBestPhysicalNetwork(): Network?

    protected abstract fun updateDefaultInterface(network: Network)

    protected abstract fun handleStickyRestartIntent()

    protected abstract fun clearStartCommandFailureState()

    protected abstract fun performPrepareRestart()

    protected abstract fun performHotReload(configContent: String)

    protected abstract fun handleHotReloadFailure(errorMsg: String)

    protected abstract fun performFullRestart(configPath: String)

    abstract fun performHotReloadSync(configContent: String): Boolean

    protected abstract fun startVpn(configPath: String)

    protected abstract fun continueStartVpnAfterForeground(configPath: String)

    protected abstract fun stopVpn(stopService: Boolean, broadcastStoppingState: Boolean = true)

    protected abstract fun updateTileState()

    protected abstract fun buildNotificationState(): VpnNotificationManager.NotificationState

    protected abstract fun requestNotificationUpdate(force: Boolean = false)

    protected abstract fun createNotification(): Notification

    protected abstract suspend fun ensureNetworkCallbackReadyWithTimeout(timeoutMs: Long = 2000L)

    protected abstract fun scheduleAsyncRuleSetUpdate()

    protected abstract suspend fun waitForUsablePhysicalNetwork(timeoutMs: Long): Network?
}
