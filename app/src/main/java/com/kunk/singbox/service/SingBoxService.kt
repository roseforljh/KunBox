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
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.ProbeManager
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.core.SingBoxCore
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
import com.kunk.singbox.utils.L
import com.kunk.singbox.utils.KernelHttpClient
import com.kunk.singbox.utils.NetworkClient
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.absoluteValue

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

    private val nodeSwitchManager: NodeSwitchManager by lazy {
        NodeSwitchManager(this, serviceScope)
    }

    private val backgroundPowerManager: BackgroundPowerManager by lazy {
        BackgroundPowerManager(serviceScope)
    }

    @Volatile
    private var backgroundPowerSavingThresholdMs: Long = BackgroundPowerSavingDelay.MINUTES_30.delayMs

    // PlatformInterfaceImpl 回调实现
    private val platformCallbacks = object : PlatformInterfaceImpl.Callbacks {
        override fun protect(fd: Int): Boolean = this@SingBoxService.protect(fd)

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
            this@SingBoxService.requestCoreNetworkReset(reason, force)
        }
        override fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean) {
            serviceScope.launch {
                BoxWrapperManager.resetAllConnections(true)
                Log.i(TAG, "resetConnectionsOptimal: $reason")
            }
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

    @Suppress("TooManyFunctions")
    companion object {
        private const val TAG = "SingBoxService"

        const val ACTION_START = ServiceStateHolder.ACTION_START
        const val ACTION_STOP = ServiceStateHolder.ACTION_STOP
        const val ACTION_SWITCH_NODE = ServiceStateHolder.ACTION_SWITCH_NODE
        const val ACTION_SERVICE = ServiceStateHolder.ACTION_SERVICE
        const val ACTION_UPDATE_SETTING = ServiceStateHolder.ACTION_UPDATE_SETTING
        const val ACTION_RESET_CONNECTIONS = ServiceStateHolder.ACTION_RESET_CONNECTIONS
        const val ACTION_PREPARE_RESTART = ServiceStateHolder.ACTION_PREPARE_RESTART
        const val ACTION_HOT_RELOAD = ServiceStateHolder.ACTION_HOT_RELOAD
        const val ACTION_FULL_RESTART = ServiceStateHolder.ACTION_FULL_RESTART
        const val ACTION_NETWORK_BUMP = "com.kunk.singbox.action.NETWORK_BUMP"
        const val EXTRA_CONFIG_PATH = ServiceStateHolder.EXTRA_CONFIG_PATH
        const val EXTRA_PENDING_NODE_NAME = "pending_node_name"
        const val EXTRA_CONFIG_CONTENT = ServiceStateHolder.EXTRA_CONFIG_CONTENT
        const val EXTRA_CLEAN_CACHE = ServiceStateHolder.EXTRA_CLEAN_CACHE
        const val EXTRA_SETTING_KEY = ServiceStateHolder.EXTRA_SETTING_KEY
        const val EXTRA_SETTING_VALUE_BOOL = ServiceStateHolder.EXTRA_SETTING_VALUE_BOOL
        const val EXTRA_PREPARE_RESTART_REASON = ServiceStateHolder.EXTRA_PREPARE_RESTART_REASON
        private const val AUTO_FAILOVER_MEANINGFUL_TRAFFIC_BPS = 1024L
        private const val AUTO_FAILOVER_STARTUP_GRACE_MS = 30_000L
        private const val AUTO_FAILOVER_NETWORK_GRACE_MS = 4_000L
        private const val AUTO_FAILOVER_PROBE_RETRY_DELAY_MS = 2_500L
        private val LATENCY_SKIPPED_OUTBOUND_TYPES = setOf(
            "direct",
            "block",
            "dns",
            "selector",
            "urltest",
            "url-test"
        )

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

        internal fun chooseHigherPriorityRecovery(
            a: RecoveryRequest,
            b: RecoveryRequest
        ): RecoveryRequest {
            return when {
                a.force != b.force -> if (a.force) a else b
                a.reason.priority != b.reason.priority -> if (a.reason.priority >= b.reason.priority) a else b
                else -> if (a.requestedAtMs >= b.requestedAtMs) a else b
            }
        }

        internal fun shouldDowngradeForceForHysteria2(
            profile: RecoveryProfile,
            reason: RecoveryReason,
            force: Boolean
        ): Boolean {
            return profile == RecoveryProfile.HYSTERIA2 &&
                reason == RecoveryReason.NETWORK_TYPE_CHANGED &&
                force
        }

        internal fun shouldTriggerRouteGroupImmediateReselect(reason: RecoveryReason): Boolean {
            return reason == RecoveryReason.NETWORK_TYPE_CHANGED ||
                reason == RecoveryReason.NETWORK_VALIDATED
        }

        internal fun shouldConvergeConnectionsAfterImmediateRouteGroupSwitch(reason: RecoveryReason): Boolean {
            return shouldTriggerRouteGroupImmediateReselect(reason)
        }

        internal fun shouldRunRouteGroupSwitchConvergence(
            lastTriggeredAtMs: Long,
            nowAtMs: Long,
            debounceMs: Long
        ): Boolean {
            return lastTriggeredAtMs <= 0L || nowAtMs - lastTriggeredAtMs >= debounceMs
        }

        internal fun shouldContinueCoreStartAfterForegroundResultForTest(foregroundStarted: Boolean): Boolean {
            return shouldContinueCoreStartAfterForegroundResult(foregroundStarted)
        }

        private fun shouldContinueCoreStartAfterForegroundResult(foregroundStarted: Boolean): Boolean {
            return foregroundStarted
        }

        internal fun shouldRecoverFromStickyRestartForTest(
            manuallyStopped: Boolean,
            mode: VpnStateStore.CoreMode,
            runningConfigUsable: Boolean
        ): Boolean {
            return shouldRecoverFromStickyRestart(manuallyStopped, mode, runningConfigUsable)
        }

        private fun shouldRecoverFromStickyRestart(
            manuallyStopped: Boolean,
            mode: VpnStateStore.CoreMode,
            runningConfigUsable: Boolean
        ): Boolean {
            return !manuallyStopped &&
                mode == VpnStateStore.CoreMode.VPN &&
                runningConfigUsable
        }

        private fun isRunningConfigUsable(file: File): Boolean {
            return file.exists() && file.isFile && file.canRead() && file.length() > 0L
        }

        internal fun shouldScheduleNetworkTypeChangedFallback(
            request: RecoveryRequest,
            success: Boolean
        ): Boolean {
            return request.reason == RecoveryReason.NETWORK_TYPE_CHANGED && success
        }

        internal fun shouldUseForegroundFastLane(request: RecoveryRequest): Boolean {
            return request.reason == RecoveryReason.APP_FOREGROUND &&
                request.force &&
                request.rawReason == "app_foreground"
        }

        internal fun shouldScheduleForegroundHardFallback(
            request: RecoveryRequest,
            mode: BoxWrapperManager.RecoveryMode,
            success: Boolean
        ): Boolean {
            return request.reason == RecoveryReason.APP_FOREGROUND &&
                mode == BoxWrapperManager.RecoveryMode.SOFT &&
                success
        }

        internal fun hasStrongNetworkTypeChangedRecoverySignal(
            probeSucceeded: Boolean,
            networkRecoveryNeeded: Boolean
        ): Boolean {
            return probeSucceeded && !networkRecoveryNeeded
        }

        internal fun shouldRunNetworkTypeChangedFallback(
            lastTriggeredAtMs: Long,
            nowAtMs: Long,
            debounceMs: Long
        ): Boolean {
            return lastTriggeredAtMs <= 0L || nowAtMs - lastTriggeredAtMs >= debounceMs
        }

        internal fun shouldSkipNetworkTypeChangedFallbackByState(
            isRunning: Boolean,
            isStarting: Boolean,
            isStopping: Boolean,
            isManuallyStopped: Boolean
        ): Boolean {
            return !shouldAllowRecoveryExecution(
                isRunning = isRunning,
                isStarting = isStarting,
                isStopping = isStopping,
                isManuallyStopped = isManuallyStopped
            )
        }

        internal fun shouldAllowRecoveryExecution(
            isRunning: Boolean,
            isStarting: Boolean,
            isStopping: Boolean,
            isManuallyStopped: Boolean
        ): Boolean {
            return isRunning && !isStarting && !isStopping && !isManuallyStopped
        }

        internal fun buildRecoveryInvalidStateSummary(
            isRunning: Boolean,
            isStarting: Boolean,
            isStopping: Boolean,
            isManuallyStopped: Boolean
        ): String? {
            if (shouldAllowRecoveryExecution(
                    isRunning = isRunning,
                    isStarting = isStarting,
                    isStopping = isStopping,
                    isManuallyStopped = isManuallyStopped
                )
            ) {
                return null
            }
            return "running=$isRunning, starting=$isStarting, stopping=$isStopping, manuallyStopped=$isManuallyStopped"
        }

        internal fun shouldAllowUserReturnRecovery(
            isRunning: Boolean,
            isStarting: Boolean,
            isStopping: Boolean,
            isManuallyStopped: Boolean
        ): Boolean {
            return shouldAllowRecoveryExecution(
                isRunning = isRunning,
                isStarting = isStarting,
                isStopping = isStopping,
                isManuallyStopped = isManuallyStopped
            )
        }

        internal fun determineNetworkTypeChangedFallbackAction(
            mode: BoxWrapperManager.RecoveryMode
        ): NetworkTypeChangedFallbackAction {
            return if (mode == BoxWrapperManager.RecoveryMode.SOFT) {
                NetworkTypeChangedFallbackAction.ESCALATE_HARD
            } else {
                NetworkTypeChangedFallbackAction.RESTART_VPN
            }
        }

        internal fun shouldCloseConnectionsDuringForegroundFastRecovery(profile: RecoveryProfile): Boolean {
            return when (profile) {
                RecoveryProfile.DEFAULT,
                RecoveryProfile.HYSTERIA2 -> false
            }
        }
    }

    private fun tryRegisterRunningServiceForLibbox() {
        // No longer needed with new CommandServer API
    }

    private fun tryClearRunningServiceForLibbox() {
        // No longer needed with new CommandServer API
    }

    /**
     * 初始化新架构 Managers (7个核心模块)
     */
    @Suppress("CognitiveComplexMethod")
    private fun initManagers() {
        // 1. 初始化核心管理器
        coreManager.init(platformInterfaceImpl)
        Log.i(TAG, "CoreManager initialized")

        initConnectManager()
        initServiceSelectorManager()
        initCommandManager()
        initSecondaryManagers()

        Log.i(TAG, "All managers initialized")
    }

    private fun initConnectManager() {
        connectManager.init(
            onNetworkChanged = { network ->
                if (network != null) {
                    Log.d(TAG, "Network changed: $network")
                }
            },
            onNetworkLost = {
                Log.i(TAG, "Network lost")
            },
            setUnderlyingNetworksFn = { nets ->
                setUnderlyingNetworks(nets)
            }
        )
        Log.i(TAG, "ConnectManager initialized")
    }

    private fun initServiceSelectorManager() {
        // 3. 初始化节点选择管理器
        serviceSelectorManager.init(commandManager.getCommandClient())
        Log.i(TAG, "ServiceSelectorManager initialized")
    }

    private fun initCommandManager() {
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
            override fun onServiceStop() {
                Log.i(TAG, "CommandManager: onServiceStop requested")
                serviceScope.launch {
                    stopVpn(stopService = true)
                }
            }
            override fun onServiceReload() {
                Log.i(TAG, "CommandManager: onServiceReload requested")
            }
        })
        Log.i(TAG, "CommandManager initialized")
    }

    private fun initSecondaryManagers() {
        // 初始化屏幕状态管理器
        screenStateManager.init(object : ScreenStateManager.Callbacks {
            override val isRunning: Boolean
                get() = SingBoxService.isRunning

            override fun notifyRemoteStateUpdate(force: Boolean) {
                this@SingBoxService.requestRemoteStateUpdate(force)
            }

            override fun requestCoreNetworkRecovery(reason: String, force: Boolean) {
                this@SingBoxService.requestCoreNetworkReset(reason, force)
            }
        })
        Log.i(TAG, "ScreenStateManager initialized")

        // 初始化路由组自动选择管理器
        routeGroupSelector.init(object : RouteGroupSelector.Callbacks {
            override val isRunning: Boolean
                get() = SingBoxService.isRunning
            override val isStopping: Boolean
                get() = coreManager.isStopping
            override fun getCommandClient() = commandManager.getCommandClient()
            override fun getSelectedOutbound(groupTag: String) = commandManager.getSelectedOutbound(groupTag)
            override fun onRouteGroupFallback(groupTag: String, actualSelectedTag: String?) {
                val targetTag = actualSelectedTag?.takeIf { it.isNotBlank() } ?: "当前全局节点"
                val message =
                    "配置分流 $groupTag 节点全部不可用，已临时回退到全局节点 $targetTag"
                val notificationId = 2000 + (groupTag.hashCode().absoluteValue % 500)
                val notification = notificationManager.createStartingNotification(message)
                notificationManager.showTemporaryNotification(notificationId, notification)
                serviceScope.launch {
                    delay(8000)
                    notificationManager.cancelNotification(notificationId)
                }
            }

            override fun onRouteGroupImmediateSwitch(
                groupTag: String,
                previousSelectedTag: String,
                newSelectedTag: String,
                reason: String
            ) {
                this@SingBoxService.convergeConnectionsAfterImmediateRouteGroupSwitch(
                    groupTag = groupTag,
                    previousSelectedTag = previousSelectedTag,
                    newSelectedTag = newSelectedTag,
                    rawReason = reason
                )
            }
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

        nodeSwitchManager.init(object : NodeSwitchManager.Callbacks {
            override val isRunning: Boolean
                get() = SingBoxService.isRunning
            override suspend fun hotSwitchNode(nodeTag: String): Boolean = this@SingBoxService.hotSwitchNode(nodeTag)
            override fun getConfigPath(): String = pendingHotSwitchFallbackConfigPath
                ?: File(filesDir, "running_config.json").absolutePath
            override fun setRealTimeNodeName(name: String?) {
                realTimeNodeName = name
                if (!name.isNullOrBlank() && name == pendingNodeName) {
                    pendingNodeName = null
                }
            }
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

        Log.i(TAG, "KunBox VPN started successfully")
        notificationManager.setSuppressUpdates(false)
    }

    private fun initBackgroundPowerManager() {
        val initialThresholdMs = backgroundPowerSavingThresholdMs

        backgroundPowerManager.init(
            callbacks = object : BackgroundPowerManager.Callbacks {
                override val isVpnRunning: Boolean
                    get() = isRunning

                override val isVpnStarting: Boolean
                    get() = isStarting

                override val isVpnStopping: Boolean
                    get() = this@SingBoxService.isStopping

                override val isManuallyStopped: Boolean
                    get() = ServiceStateHolder.isManuallyStopped

                override fun requestCoreNetworkRecovery(reason: String, force: Boolean) {
                    this@SingBoxService.requestCoreNetworkReset(reason, force)
                }

                override fun suspendNonEssentialProcesses() {
                    Log.d(TAG, "[PowerSaving] suspendNonEssentialProcesses ignored")
                }

                override fun resumeNonEssentialProcesses() {
                    Log.d(TAG, "[PowerSaving] resumeNonEssentialProcesses ignored")
                }
            },
            thresholdMs = initialThresholdMs
        )

        // Load user setting asynchronously to avoid blocking service initialization.
        serviceScope.launch {
            val thresholdMs = runCatching {
                val settings = SettingsRepository.getInstance(this@SingBoxService).settings.first()
                settings.backgroundPowerSavingDelay.delayMs
            }.getOrElse { e ->
                Log.w(TAG, "Failed to read power saving delay setting, using default", e)
                BackgroundPowerSavingDelay.MINUTES_30.delayMs
            }
            backgroundPowerSavingThresholdMs = thresholdMs
            backgroundPowerManager.setThreshold(thresholdMs)
        }

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
            autoFailoverServiceStartedAtMs = System.currentTimeMillis()
            isProxyIdleForAutoFailover = false

            // BoxWrapperManager 在 libbox 启动后初始化，避免 hasSelector() 超时
            commandManager.getCommandServer()?.let { server ->
                BoxWrapperManager.init(server)
            }
            Log.i(TAG, "BoxWrapperManager initialized")

            // 初始化 KernelHttpClient 的代理端口
            serviceScope.launch {
                KernelHttpClient.updateProxyPortFromSettings(this@SingBoxService)
            }
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
        override fun detectExistingVpns(): Boolean = foreignVpnMonitor.hasExistingVpn()

        // 组件初始化
        override fun initSelectorManager(configContent: String) {
            this@SingBoxService.initSelectorManager(configContent)
        }

        override fun createAndStartCommandServer(): Result<Unit> {
            return runCatching {
                // 1. 创建 CommandServer
                val server = commandManager.createServer(platformInterfaceImpl).getOrThrow()
                // 2. 设置到 CoreManager
                coreManager.setCommandServer(server)
                // 3. 启动 CommandServer
                commandManager.startServer().getOrThrow()
                Log.i(TAG, "CommandServer created and started")
            }
        }

        override fun startCommandClients() {
            commandManager.startClients().onFailure { e ->
                Log.e(TAG, "Failed to start Command Clients", e)
            }
            serviceSelectorManager.updateCommandClient(commandManager.getCommandClient())
        }

        override fun startRouteGroupAutoSelect(configContent: String) {
            routeGroupSelector.start(configContent)
        }

        override fun scheduleAsyncRuleSetUpdate() {
            this@SingBoxService.scheduleAsyncRuleSetUpdate()
        }

        override fun startHealthMonitor() {
            // 健康监控已移除，保留空实现
            Log.i(TAG, "Health monitor disabled (simplified mode)")
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

        override fun restoreUnderlyingNetwork(network: Network) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setUnderlyingNetworks(arrayOf(network))
                Log.i(TAG, "Underlying network restored before libbox start: $network")
            }
        }

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
            this@SingBoxService.tryClearRunningServiceForLibbox()
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
        override fun setIsRunning(running: Boolean) { isRunning = running }
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
     *
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

    /**
     * 使用统一离线临时服务测速路径并返回结果
     *
     * @param groupTag 要测试的 group 标签 (如 "PROXY")
     * @param timeoutMs 等待结果的超时时间
     * @return 节点延迟映射 (tag -> delay ms)，失败返回空 Map
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun urlTestGroup(groupTag: String, timeoutMs: Long = 10000L): Map<String, Int> {
        return testGroupCandidatesLatency(groupTag)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun urlTestGroup(
        groupTag: String,
        timeoutMs: Long,
        expectedTags: Set<String>,
        onProgress: ((Map<String, Int>) -> Unit)? = null
    ): Map<String, Int> {
        val results = testGroupCandidatesLatency(groupTag)
            .filterKeys { expectedTags.isEmpty() || it in expectedTags }
        onProgress?.invoke(results)
        return results
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
            val nodeName = resolveNotificationNodeLabel(
                selectedNodeName = repo.nodes.value.find { it.id == activeNodeId }?.name,
                selectedNodeStoreLabel = VpnStateStore.getSelectedNodeLabel()
            )
            nodeName.orEmpty()
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
     *
     * @return true if hot switch triggered successfully, false if restart is needed
     *
     * 核心原理:
     * sing-box 的 Selector.SelectOutbound() 内部会调用 interruptGroup.Interrupt(interruptExternalConnections)
     * 当 PROXY selector 配置了 interrupt_exist_connections=true 时,
     * selectOutbound 会自动中断所有外部连接(入站连接)
     */
    suspend fun hotSwitchNode(nodeTag: String): Boolean {
        if (!coreManager.isServiceRunning() || !isRunning) return false

        try {
            L.connection("HotSwitch", "Starting switch to: $nodeTag")

            // Step 1: 唤醒核心
            coreManager.wakeService()
            L.step("HotSwitch", 1, 2, "Called wakeService()")

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

    private var currentSettings: AppSettings? = null
    private val serviceSupervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceSupervisorJob)
    private val cleanupSupervisorJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupSupervisorJob)
    private val autoFailoverSupervisorJob = SupervisorJob()
    private val autoFailoverDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vpn-auto-failover").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }.asCoroutineDispatcher()
    private val autoFailoverScope = CoroutineScope(autoFailoverDispatcher + autoFailoverSupervisorJob)
    @Volatile private var isStopping: Boolean = false
    @Volatile private var stopSelfRequested: Boolean = false
    @Volatile private var cleanupJob: Job? = null
    @Volatile private var autoFailoverJob: Job? = null
    @Volatile private var pendingStartConfigPath: String? = null
    @Volatile private var pendingNodeName: String? = null
    @Volatile private var pendingCleanCache: Boolean = false

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
    @Volatile private var lastMeaningfulTrafficAtMs: Long = 0L
    @Volatile private var isProxyIdleForAutoFailover: Boolean = false
    @Volatile private var autoFailoverServiceStartedAtMs: Long = 0L
    @Volatile private var lastAutoFailoverNetworkEventAtMs: Long = 0L
    private val trafficListener = object : TrafficMonitor.Listener {
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
            Log.d(TAG, "Traffic stall detected (count=$consecutiveCount, attempt=$stallRefreshAttempts/$maxAttempts)")

            if (stallRefreshAttempts >= maxStallRefreshAttempts * 2) {
                Log.w(TAG, "Persistent traffic stall after $stallRefreshAttempts attempts")
                LogRepository.getInstance().addLog(
                    "WARN: Traffic stall detected, attempting gentle recovery..."
                )
                stallRefreshAttempts = 0
                trafficMonitor.resetStallCounter()
                serviceScope.launch {
                    val closed = BoxWrapperManager.closeIdleConnections(30)
                    Log.i(TAG, "Closed $closed idle connections for traffic stall")
                }
            } else {
                serviceScope.launch {
                    try {
                        val closed = BoxWrapperManager.closeIdleConnections(30)
                        if (closed > 0) {
                            Log.i(TAG, "Closed $closed idle connections after stall")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to close idle connections after stall", e)
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
                Log.d(TAG, "Proxy idle detected (${idleSeconds}s) but Box not available, skip reset")
                return
            }

            val connCount = runCatching { BoxWrapperManager.getConnectionCount() }.getOrDefault(0)
            val needRecovery = runCatching { BoxWrapperManager.isNetworkRecoveryNeeded() }.getOrDefault(false)

            if (connCount <= 0 && !needRecovery) {
                Log.d(
                    TAG,
                    "Proxy idle detected (${idleSeconds}s) but no active connections and recovery not needed"
                )
                return
            }

            Log.i(
                TAG,
                "Proxy idle ($idleSeconds s), reset conn (cnt=$connCount need=$needRecovery)"
            )
            serviceScope.launch {
                BoxWrapperManager.resetAllConnections(true)
            }
        }
    }

    private var stallRefreshAttempts: Int = 0
    private val maxStallRefreshAttempts: Int = 3 // 连续3次stall刷新后仍无流量则重启服务

    private var networkManager: NetworkManager? = null

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
        val now = SystemClock.elapsedRealtime()
        val parsedReason = parseRecoveryReason(reason)
        if (
            parsedReason == RecoveryReason.NETWORK_TYPE_CHANGED ||
            parsedReason == RecoveryReason.NETWORK_VALIDATED
        ) {
            lastAutoFailoverNetworkEventAtMs = System.currentTimeMillis()
        }
        val request = RecoveryRequest(
            reason = parsedReason,
            rawReason = reason,
            force = force,
            requestedAtMs = now,
            merged = false
        )
        submitRecoveryRequest(request)
    }

    private fun parseRecoveryReason(reason: String): RecoveryReason {
        val normalized = reason.trim().lowercase()
        return when {
            normalized.contains("network_type_changed") ||
                normalized.contains("typechange") -> RecoveryReason.NETWORK_TYPE_CHANGED
            normalized.contains("doze_exit") -> RecoveryReason.DOZE_EXIT
            normalized.contains("network_validated") -> RecoveryReason.NETWORK_VALIDATED
            normalized.contains("vpnhealth") || normalized.contains("vpn_health") -> RecoveryReason.VPN_HEALTH
            normalized.contains("app_foreground") -> RecoveryReason.APP_FOREGROUND
            normalized.contains("screen_on") -> RecoveryReason.SCREEN_ON
            else -> RecoveryReason.UNKNOWN
        }
    }

    private fun handleTrafficUpdateForAutoFailover(snapshot: TrafficMonitor.TrafficSnapshot) {
        val totalSpeed = snapshot.uploadSpeed + snapshot.downloadSpeed
        if (totalSpeed < AUTO_FAILOVER_MEANINGFUL_TRAFFIC_BPS) {
            return
        }
        lastMeaningfulTrafficAtMs = System.currentTimeMillis()
        isProxyIdleForAutoFailover = false
    }

    private fun submitAutoFailoverSuspicion(trigger: String) {
        if (autoFailoverJob?.isActive == true) {
            Log.d(TAG, "[AutoFailover] suspicion ignored, job already running: $trigger")
            return
        }

        val now = System.currentTimeMillis()
        val context = NodeAutoFailoverPolicy.TriggerContext(
            isVpnRunning = isRunning,
            isManuallyStopped = isManuallyStopped,
            isAutoFailoverInFlight = autoFailoverJob?.isActive == true,
            isRecoveryInFlight = recoveryInFlight,
            inStartupGracePeriod = isAutoFailoverStartupGracePeriod(now),
            inNetworkChangeGracePeriod = isAutoFailoverNetworkGracePeriod(now),
            isProxyIdle = isProxyIdleForAutoFailover,
            lastMeaningfulTrafficAtMs = lastMeaningfulTrafficAtMs,
            nowAtMs = now,
            lastAutoFailoverAtMs = VpnStateStore.getLastAutoFailoverAtMs(),
            budgetWindowStartAtMs = VpnStateStore.getAutoFailoverWindowStartAtMs(),
            budgetCount = VpnStateStore.getAutoFailoverCountInWindow()
        )

        if (!NodeAutoFailoverPolicy.shouldStartProbe(context)) {
            Log.d(TAG, "[AutoFailover] suspicion ignored by policy: $trigger")
            return
        }

        autoFailoverJob = autoFailoverScope.launch {
            runAutoFailoverProbeSequence(trigger)
        }
    }

    private fun isAutoFailoverStartupGracePeriod(nowAtMs: Long): Boolean {
        val startedAtMs = autoFailoverServiceStartedAtMs
        if (startedAtMs <= 0L || nowAtMs < startedAtMs) {
            return false
        }
        return nowAtMs - startedAtMs < AUTO_FAILOVER_STARTUP_GRACE_MS
    }

    private fun isAutoFailoverNetworkGracePeriod(nowAtMs: Long): Boolean {
        val eventAtMs = lastAutoFailoverNetworkEventAtMs
        if (eventAtMs <= 0L || nowAtMs < eventAtMs) {
            return false
        }
        return nowAtMs - eventAtMs < AUTO_FAILOVER_NETWORK_GRACE_MS
    }

    private suspend fun runAutoFailoverProbeSequence(trigger: String) {
        try {
            val currentTag = resolveCurrentProxyOutboundTag()
            if (currentTag.isNullOrBlank()) {
                Log.d(TAG, "[AutoFailover] skip, no current PROXY selection: $trigger")
                return
            }

            val firstEvaluation = runAutoFailoverProbeRound(currentTag)
            when {
                firstEvaluation.outcome == NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_HEALTHY -> {
                    Log.i(TAG, "[AutoFailover] current node healthy on first probe: $currentTag")
                }

                firstEvaluation.outcome !=
                    NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE -> {
                    Log.i(
                        TAG,
                        "[AutoFailover] probe did not find a healthy alternative: ${firstEvaluation.outcome}"
                    )
                }

                else -> {
                    handleSecondAutoFailoverProbe(
                        currentTag = currentTag,
                        firstEvaluation = firstEvaluation,
                        trigger = trigger
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[AutoFailover] probe sequence failed: $trigger", e)
        } finally {
            autoFailoverJob = null
        }
    }

    private suspend fun handleSecondAutoFailoverProbe(
        currentTag: String,
        firstEvaluation: NodeAutoFailoverPolicy.ProbeEvaluation,
        trigger: String
    ) {
        delay(AUTO_FAILOVER_PROBE_RETRY_DELAY_MS)
        val secondEvaluation = runAutoFailoverProbeRound(currentTag)
        when {
            secondEvaluation.outcome !=
                NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE -> {
                Log.i(
                    TAG,
                    "[AutoFailover] second probe recovered or no alternative: ${secondEvaluation.outcome}"
                )
            }

            secondEvaluation.alternativeTag.isNullOrBlank() && firstEvaluation.alternativeTag.isNullOrBlank() -> {
                Log.i(TAG, "[AutoFailover] second probe has no target alternative")
            }

            else -> {
                val targetTag = secondEvaluation.alternativeTag
                    ?: firstEvaluation.alternativeTag.orEmpty()
                performAutoFailoverSwitch(currentTag, targetTag, trigger)
            }
        }
    }

    private suspend fun runAutoFailoverProbeRound(
        currentTag: String
    ): NodeAutoFailoverPolicy.ProbeEvaluation {
        val results = testGroupCandidatesLatency("PROXY")
        val quarantined = loadActiveAutoFailoverQuarantine(System.currentTimeMillis())
        val evaluation = NodeAutoFailoverPolicy.evaluateProbe(
            currentTag = currentTag,
            urlTestResults = results,
            quarantinedTags = quarantined.map { it.tag }.toSet()
        )
        Log.i(
            TAG,
            "[AutoFailover] probe current=$currentTag outcome=${evaluation.outcome} " +
                "alt=${evaluation.alternativeTag ?: "(none)"} delays=${results.size}"
        )
        return evaluation
    }

    private suspend fun testGroupCandidatesLatency(groupTag: String): Map<String, Int> = coroutineScope {
        val config = loadLastRunningConfig() ?: return@coroutineScope emptyMap()
        val outbounds = config.outbounds.orEmpty()
        val byTag = outbounds.associateBy { it.tag }
        val groupCandidates = byTag[groupTag]
            ?.outbounds
            .orEmpty()
            .mapNotNull { byTag[it] }
            .ifEmpty {
                outbounds.filter { outbound -> outbound.type !in LATENCY_SKIPPED_OUTBOUND_TYPES }
            }
        if (groupCandidates.isEmpty()) return@coroutineScope emptyMap()

        val settings = SettingsRepository.getInstance(this@SingBoxService).settings.first()
        val semaphore = Semaphore(settings.latencyTestConcurrency.coerceIn(1, 20))
        val core = SingBoxCore.getInstance(this@SingBoxService)
        val results = ConcurrentHashMap<String, Int>()

        groupCandidates.map { outbound ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val latency = runCatching {
                        core.testOutboundLatency(outbound, outbounds)
                    }.getOrDefault(-1L)
                    if (latency > 0L && latency <= Int.MAX_VALUE) {
                        results[outbound.tag] = latency.toInt()
                    }
                }
            }
        }.awaitAll()

        results.toMap()
    }

    private fun loadLastRunningConfig(): SingBoxConfig? {
        val configPath = lastConfigPath ?: File(filesDir, "running_config.json").absolutePath
        return runCatching {
            val configContent = File(configPath).readText()
            gson.fromJson(configContent, SingBoxConfig::class.java)
        }.onFailure { e ->
            Log.w(TAG, "[AutoFailover] failed to load running config for latency test: ${e.message}")
        }.getOrNull()
    }

    private suspend fun performAutoFailoverSwitch(
        currentTag: String,
        targetTag: String,
        trigger: String
    ) {
        val now = System.currentTimeMillis()
        val currentQuarantine = loadActiveAutoFailoverQuarantine(now).toMutableList()
        currentQuarantine.add(NodeAutoFailoverPolicy.createQuarantineRecord(currentTag, now))
        val cleanedQuarantine = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(currentQuarantine, now)
        val budgetState = NodeAutoFailoverPolicy.registerFailoverAttempt(
            windowStartAtMs = VpnStateStore.getAutoFailoverWindowStartAtMs(),
            count = VpnStateStore.getAutoFailoverCountInWindow(),
            nowAtMs = now
        )

        VpnStateStore.setLastAutoFailoverAtMs(now)
        VpnStateStore.setAutoFailoverWindowStartAtMs(budgetState.windowStartAtMs)
        VpnStateStore.setAutoFailoverCountInWindow(budgetState.count)
        VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(cleanedQuarantine))
        VpnStateStore.setLastAutoFailoverNodeTag(currentTag)

        val success = hotSwitchNode(targetTag)
        if (success) {
            val configRepository = ConfigRepository.getInstance(this@SingBoxService)
            val node = configRepository.getNodeByName(targetTag)
            val displayName = node?.name ?: targetTag
            VpnStateStore.setActiveLabel(displayName)
            realTimeNodeName = displayName
            runCatching {
                configRepository.syncActiveNodeFromProxySelection(displayName)
            }
            trafficMonitor.resetStallCounter()
            stallRefreshAttempts = 0
            isProxyIdleForAutoFailover = false
            requestNotificationUpdate(force = true)
            requestRemoteStateUpdate(force = true)
            routeGroupSelector.requestImmediateReselect("vpn_health_auto_failover")
            LogRepository.getInstance().addLog(
                "INFO: Auto failover switched from $currentTag to $displayName (trigger=$trigger)"
            )
            Log.i(TAG, "[AutoFailover] switched from $currentTag to $displayName, trigger=$trigger")
            return
        }

        Log.w(TAG, "[AutoFailover] hot switch failed, falling back to restart: $targetTag")
        val configPath = pendingHotSwitchFallbackConfigPath ?: File(filesDir, "running_config.json").absolutePath
        val restartIntent = Intent(this@SingBoxService, SingBoxService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_CONFIG_PATH, configPath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }

    private fun loadActiveAutoFailoverQuarantine(nowAtMs: Long): List<NodeAutoFailoverPolicy.QuarantinedNode> {
        val records = NodeAutoFailoverPolicy.decodeQuarantine(VpnStateStore.getAutoFailoverQuarantinedTags())
        val cleaned = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(records, nowAtMs)
        if (cleaned.size != records.size) {
            VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(cleaned))
        }
        return cleaned
    }

    private fun resolveCurrentProxyOutboundTag(): String? {
        return commandManager.getSelectedOutbound("PROXY")
            ?.takeIf { it.isNotBlank() }
            ?: SelectorManager.getSelectedOutbound()?.takeIf { it.isNotBlank() }
            ?: BoxWrapperManager.getSelectedOutbound()?.takeIf { it.isNotBlank() }
    }

    @Suppress("CognitiveComplexMethod", "LongMethod")
    private fun submitRecoveryRequest(request: RecoveryRequest) {
        val invalidState = recoveryInvalidStateSummary()
        if (invalidState != null) {
            logRecoveryEvent(
                event = "skipped_invalid_state",
                request = request,
                mode = null,
                merged = request.merged,
                skipped = true,
                outcome = "invalid_state($invalidState)"
            )
            return
        }

        synchronized(this) {
            // 2025-fix-v7: APP_FOREGROUND + force 走快车道，不进合并窗口
            // 直接 wake + resetNetwork，跳过 800ms 合并等待和多级探测
            if (shouldUseForegroundFastLane(request) && !recoveryInFlight) {
                recoveryInFlight = true
                serviceScope.launch {
                    try {
                        executeForegroundFastRecovery(request)
                    } finally {
                        val nextRequest = synchronized(this@SingBoxService) {
                            recoveryInFlight = false
                            val next = pendingRecoveryRequest
                            pendingRecoveryRequest = null
                            next
                        }
                        if (nextRequest != null) {
                            executeRecoveryRequest(nextRequest)
                        }
                    }
                }
                return
            }

            if (recoveryInFlight) {
                val current = pendingRecoveryRequest
                pendingRecoveryRequest = if (current == null) {
                    request.copy(merged = true)
                } else {
                    mergeRecoveryRequests(current, request)
                }
                recoveryMergedCount.incrementAndGet()
                logRecoveryEvent(
                    event = "merged_inflight",
                    request = request,
                    mode = null,
                    merged = true,
                    skipped = false,
                    outcome = null
                )
                return
            }

            val existingMerge = pendingMergeRequest
            pendingMergeRequest = if (existingMerge == null) {
                request
            } else {
                mergeRecoveryRequests(existingMerge, request)
            }

            val hadExisting = existingMerge != null
            if (hadExisting) {
                recoveryMergedCount.incrementAndGet()
                logRecoveryEvent(
                    event = "merged_window",
                    request = request,
                    mode = null,
                    merged = true,
                    skipped = false,
                    outcome = null
                )
            }

            if (recoveryMergeJob?.isActive != true) {
                recoveryMergeJob = serviceScope.launch {
                    delay(recoveryMergeWindowMs)
                    val toRun = synchronized(this@SingBoxService) {
                        val r = pendingMergeRequest
                        pendingMergeRequest = null
                        r
                    }
                    if (toRun != null) {
                        executeRecoveryRequest(toRun)
                    }
                }
            }
        }
    }

    private fun mergeRecoveryRequests(
        existing: RecoveryRequest,
        incoming: RecoveryRequest
    ): RecoveryRequest {
        val winning = chooseHigherPriorityRecovery(existing, incoming)
        return if (winning.merged) winning else winning.copy(merged = true)
    }

    private fun cancelPendingRecoveryWork() {
        recoveryMergeJob?.cancel()
        recoveryMergeJob = null
        pendingMergeRequest = null
        pendingRecoveryRequest = null

        foregroundHardFallbackJob?.cancel()
        foregroundHardFallbackJob = null

        networkTypeChangedFallbackJob?.cancel()
        networkTypeChangedFallbackJob = null
    }

    private fun recoveryInvalidStateSummary(): String? {
        return buildRecoveryInvalidStateSummary(
            isRunning = isRunning,
            isStarting = isStarting,
            isStopping = isStopping,
            isManuallyStopped = isManuallyStopped
        )
    }

    private data class RecoveryDebounceContext(
        val now: Long,
        val lane: String,
        val effectiveGlobalDebounceMs: Long,
        val effectiveSourceDebounceMs: Long,
        val reasonKey: String
    )

    private fun buildRecoveryDebounceContext(request: RecoveryRequest): RecoveryDebounceContext {
        val lane = if (request.reason.isFastLane) "fast" else "normal"
        val effectiveGlobalDebounceMs = if (request.reason.isFastLane) {
            recoveryFastLaneGlobalDebounceMs
        } else {
            recoveryGlobalDebounceMs
        }
        val effectiveSourceDebounceMs = if (request.reason.isFastLane) {
            minOf(request.reason.sourceDebounceMs, recoveryFastLaneSourceDebounceCapMs)
        } else {
            request.reason.sourceDebounceMs
        }
        return RecoveryDebounceContext(
            now = SystemClock.elapsedRealtime(),
            lane = lane,
            effectiveGlobalDebounceMs = effectiveGlobalDebounceMs,
            effectiveSourceDebounceMs = effectiveSourceDebounceMs,
            reasonKey = request.reason.name
        )
    }

    private fun shouldSkipByGlobalDebounce(
        request: RecoveryRequest,
        context: RecoveryDebounceContext
    ): Boolean {
        val lastGlobal = recoveryLastTriggeredAtMs.get()
        if (!request.force && context.now - lastGlobal < context.effectiveGlobalDebounceMs) {
            recoverySkippedDebounceCount.incrementAndGet()
            logRecoveryEvent(
                event = "skipped_global_debounce",
                request = request,
                mode = null,
                merged = request.merged,
                skipped = true,
                outcome = "debounce(lane=${context.lane},threshold=${context.effectiveGlobalDebounceMs}ms)"
            )
            return true
        }
        return false
    }

    private fun shouldSkipBySourceDebounce(
        request: RecoveryRequest,
        context: RecoveryDebounceContext
    ): Boolean {
        val reasonLast = recoveryReasonLastAtMs[context.reasonKey] ?: 0L
        if (!request.force && context.now - reasonLast < context.effectiveSourceDebounceMs) {
            recoverySkippedDebounceCount.incrementAndGet()
            logRecoveryEvent(
                event = "skipped_source_debounce",
                request = request,
                mode = null,
                merged = request.merged,
                skipped = true,
                outcome = "debounce(lane=${context.lane},threshold=${context.effectiveSourceDebounceMs}ms)"
            )
            return true
        }
        return false
    }

    private fun requestImmediateRouteGroupReselectIfNeeded(request: RecoveryRequest) {
        if (!shouldTriggerRouteGroupImmediateReselect(request.reason)) {
            return
        }
        routeGroupSelector.requestImmediateReselect(request.rawReason)
    }

    private fun convergeConnectionsAfterImmediateRouteGroupSwitch(
        groupTag: String,
        previousSelectedTag: String,
        newSelectedTag: String,
        rawReason: String
    ) {
        val reason = RecoveryReason.fromReasonString(rawReason)
        if (!shouldConvergeConnectionsAfterImmediateRouteGroupSwitch(reason)) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (!shouldRunRouteGroupSwitchConvergence(
                lastTriggeredAtMs = lastConnectionsResetAtMs,
                nowAtMs = now,
                debounceMs = connectionsResetDebounceMs
            )
        ) {
            Log.d(
                TAG,
                "[RouteGroupConvergence] skipped by debounce, group=$groupTag, " +
                    "from=$previousSelectedTag, to=$newSelectedTag, reason=$rawReason"
            )
            return
        }

        lastConnectionsResetAtMs = now
        val closedTrackedConnections = BoxWrapperManager.closeAllTrackedConnections()
        val resetAllTriggered = BoxWrapperManager.resetAllConnections(true)
        Log.i(
            TAG,
            "[RouteGroupConvergence] group=$groupTag from=$previousSelectedTag to=$newSelectedTag, " +
                "reason=${reason.name}, closedTracked=$closedTrackedConnections, " +
                "resetAllTriggered=$resetAllTriggered"
        )
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    private suspend fun executeRecoveryRequest(request: RecoveryRequest) {
        synchronized(this) {
            recoveryInFlight = true
        }
        try {
            val invalidState = recoveryInvalidStateSummary()
            if (invalidState != null) {
                logRecoveryEvent(
                    event = "skipped_invalid_state",
                    request = request,
                    mode = null,
                    merged = request.merged,
                    skipped = true,
                    outcome = "invalid_state($invalidState)"
                )
                return
            }

            val recoveryProfile = getRecoveryProfile()
            val forceDowngraded = shouldDowngradeForceForHysteria2(
                profile = recoveryProfile,
                reason = request.reason,
                force = request.force
            )
            val executionForce = if (forceDowngraded) false else request.force
            val context = buildRecoveryDebounceContext(request)
            if (shouldSkipByGlobalDebounce(request, context)) return
            if (shouldSkipBySourceDebounce(request, context)) return

            recoveryLastTriggeredAtMs.set(context.now)
            recoveryReasonLastAtMs[context.reasonKey] = context.now
            recoveryTriggerCount.incrementAndGet()

            val smartResult = BoxWrapperManager.smartRecover(
                context = this@SingBoxService,
                source = request.rawReason,
                skipProbe = executionForce
            )

            val mode = when (smartResult.level) {
                BoxWrapperManager.RecoveryLevel.NONE,
                BoxWrapperManager.RecoveryLevel.PROBE -> BoxWrapperManager.RecoveryMode.SOFT
                BoxWrapperManager.RecoveryLevel.SELECTIVE -> {
                    recoverySoftCount.incrementAndGet()
                    BoxWrapperManager.RecoveryMode.SOFT
                }
                BoxWrapperManager.RecoveryLevel.NUCLEAR -> {
                    recoveryHardCount.incrementAndGet()
                    BoxWrapperManager.RecoveryMode.HARD
                }
            }

            val success = smartResult.success
            if (success) {
                recoverySuccessCount.incrementAndGet()
                recoveryConsecutiveFailureCount.set(0)
            } else {
                recoveryFailureCount.incrementAndGet()
                recoveryConsecutiveFailureCount.incrementAndGet()
            }

            val successRate = calculateRecoverySuccessRate()
            val outcomeDetail = buildString {
                append(if (success) "success" else "failed")
                append("(level=${smartResult.level}")
                smartResult.probeLatencyMs?.let { append(",probe=${it}ms") }
                if (smartResult.closedConnections > 0) {
                    append(",closed=${smartResult.closedConnections}")
                }
                if (forceDowngraded) {
                    append(",force_downgraded=true")
                }
                append(",rate=$successRate)")
            }
            logRecoveryEvent(
                event = "executed",
                request = request,
                mode = mode,
                merged = request.merged,
                skipped = false,
                outcome = outcomeDetail
            )

            requestImmediateRouteGroupReselectIfNeeded(request)

            if (smartResult.level == BoxWrapperManager.RecoveryLevel.PROBE) {
                scheduleForegroundHardFallbackIfNeeded(request, mode, success)
            }
            scheduleNetworkTypeChangedFallbackIfNeeded(request, mode, success)
        } finally {
            val nextRequest = synchronized(this) {
                recoveryInFlight = false
                val next = pendingRecoveryRequest
                pendingRecoveryRequest = null
                next
            }
            if (nextRequest != null) {
                executeRecoveryRequest(nextRequest)
            }
        }
    }

    private fun calculateRecoverySuccessRate(): String {
        val success = recoverySuccessCount.get()
        val failure = recoveryFailureCount.get()
        val total = success + failure
        if (total <= 0L) return "n/a"
        val percentage = (success * 100.0) / total.toDouble()
        return "%.1f%%".format(java.util.Locale.US, percentage)
    }

    /**
     * 2025-fix-v7: 前台快速恢复 - 跳过探测，直接 wake + resetNetwork
     * 比 smartRecover 少 2-5 秒（不做 PROBE + SELECTIVE 的验证循环）
     * 仅在 APP_FOREGROUND + force 时使用
     */
    private fun isSelectedHysteria2Outbound(): Boolean {
        val selectedTag = SelectorManager.getSelectedOutbound()
            ?: BoxWrapperManager.getSelectedOutbound()
            ?: return false

        return try {
            val configPath = lastConfigPath ?: File(filesDir, "running_config.json").absolutePath
            val configContent = File(configPath).takeIf { it.exists() }?.readText() ?: return false
            val config = gson.fromJson(configContent, SingBoxConfig::class.java) ?: return false
            config.outbounds
                ?.firstOrNull { it.tag == selectedTag }
                ?.type
                ?.equals("hysteria2", ignoreCase = true) == true
        } catch (e: Exception) {
            Log.w(TAG, "isSelectedHysteria2Outbound failed: ${e.message}")
            false
        }
    }

    private fun getRecoveryProfile(): RecoveryProfile {
        return if (isSelectedHysteria2Outbound()) RecoveryProfile.HYSTERIA2 else RecoveryProfile.DEFAULT
    }

    private fun executeForegroundFastRecovery(request: RecoveryRequest) {
        val invalidState = recoveryInvalidStateSummary()
        if (invalidState != null) {
            logRecoveryEvent(
                event = "foreground_fast_recovery_skipped_state",
                request = request,
                mode = BoxWrapperManager.RecoveryMode.SOFT,
                merged = false,
                skipped = true,
                outcome = "invalid_state($invalidState)"
            )
            return
        }

        val startMs = SystemClock.elapsedRealtime()
        val isHy2 = isSelectedHysteria2Outbound()

        val recoveryProfile = getRecoveryProfile()
        BoxWrapperManager.wake()
        if (shouldCloseConnectionsDuringForegroundFastRecovery(recoveryProfile)) {
            BoxWrapperManager.closeAllTrackedConnections()
            BoxWrapperManager.resetAllConnections(true)
        } else if (isHy2) {
            Log.i(TAG, "[ForegroundFastRecovery] hysteria2 selected, skip aggressive reset")
        }
        BoxWrapperManager.resetNetwork()

        val elapsedMs = SystemClock.elapsedRealtime() - startMs
        Log.i(TAG, "[ForegroundFastRecovery] completed in ${elapsedMs}ms")

        recoveryLastTriggeredAtMs.set(SystemClock.elapsedRealtime())
        recoveryTriggerCount.incrementAndGet()
        recoverySoftCount.incrementAndGet()
        recoverySuccessCount.incrementAndGet()
        recoveryConsecutiveFailureCount.set(0)

        logRecoveryEvent(
            event = "foreground_fast_recovery",
            request = request,
            mode = BoxWrapperManager.RecoveryMode.SOFT,
            merged = false,
            skipped = false,
            outcome = if (isHy2) "hy2_fast_path(${elapsedMs}ms)" else "fast_path(${elapsedMs}ms)"
        )

        scheduleForegroundHardFallbackIfNeeded(
            request = request,
            mode = BoxWrapperManager.RecoveryMode.SOFT,
            success = true
        )
    }

    private fun evaluateForegroundFallbackState(): ForegroundFallbackState {
        val invalidState = recoveryInvalidStateSummary()
        val stateSkipOutcome = invalidState?.let { "state_$it" } ?: ""
        val shouldSkipByState = invalidState != null

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastForegroundHardFallbackAtMs.get()
        val shouldSkipByDebounce = elapsed in 0 until foregroundHardFallbackDebounceMs

        val skipReason = when {
            shouldSkipByState -> "state"
            vpnLinkValidated -> "validated"
            shouldSkipByDebounce -> "debounce"
            else -> null
        }

        return when (skipReason) {
            "state" -> ForegroundFallbackState(
                shouldSkip = true,
                event = "foreground_hard_fallback_skipped_state",
                outcome = stateSkipOutcome
            )
            "validated" -> ForegroundFallbackState(
                shouldSkip = true,
                event = "foreground_hard_fallback_skipped_validated",
                outcome = "vpn_link_validated"
            )
            "debounce" -> ForegroundFallbackState(
                shouldSkip = true,
                event = "foreground_hard_fallback_skipped_debounce",
                outcome = "debounce(elapsed=${elapsed}ms," +
                    "threshold=${foregroundHardFallbackDebounceMs}ms)"
            )
            else -> {
                lastForegroundHardFallbackAtMs.set(now)
                ForegroundFallbackState(
                    shouldSkip = false,
                    event = "foreground_hard_fallback_enqueued",
                    outcome = "grace=${foregroundRecoveryGraceMs}ms"
                )
            }
        }
    }

    private fun scheduleForegroundHardFallbackIfNeeded(
        request: RecoveryRequest,
        mode: BoxWrapperManager.RecoveryMode,
        success: Boolean
    ) {
        if (!shouldScheduleForegroundHardFallback(request, mode, success)) {
            return
        }

        foregroundHardFallbackJob?.cancel()
        foregroundHardFallbackJob = serviceScope.launch {
            delay(foregroundRecoveryGraceMs)

            // 先探测 VPN 链路，如果正常则跳过 HARD fallback
            val probeOk = runCatching {
                ProbeManager.probeFirstSuccessViaVpn(
                    context = this@SingBoxService,
                    timeoutMs = 1500L
                )
            }.getOrNull() != null

            if (probeOk) {
                logRecoveryEvent(
                    event = "foreground_hard_fallback_skipped_probe_ok",
                    request = request,
                    mode = BoxWrapperManager.RecoveryMode.HARD,
                    merged = false,
                    skipped = true,
                    outcome = "vpn_link_healthy_on_probe"
                )
                return@launch
            }

            val state = evaluateForegroundFallbackState()
            logRecoveryEvent(
                event = state.event,
                request = request,
                mode = BoxWrapperManager.RecoveryMode.HARD,
                merged = false,
                skipped = state.shouldSkip,
                outcome = state.outcome
            )
            if (state.shouldSkip) {
                return@launch
            }

            val hardRequest = RecoveryRequest(
                reason = RecoveryReason.APP_FOREGROUND,
                rawReason = "app_foreground_hard_fallback",
                force = true,
                requestedAtMs = SystemClock.elapsedRealtime(),
                merged = false
            )

            submitRecoveryRequest(hardRequest)
        }
    }

    private suspend fun collectNetworkTypeChangedRecoverySignal(): NetworkTypeChangedRecoverySignal {
        val probeSucceeded = runCatching {
            ProbeManager.probeFirstSuccessViaVpn(
                context = this@SingBoxService,
                timeoutMs = 1500L
            )
        }.getOrNull() != null

        val networkRecoveryNeeded = runCatching {
            !BoxWrapperManager.isAvailable() || BoxWrapperManager.isNetworkRecoveryNeeded()
        }.getOrDefault(true)

        return NetworkTypeChangedRecoverySignal(
            probeSucceeded = probeSucceeded,
            networkRecoveryNeeded = networkRecoveryNeeded,
            strongSignal = hasStrongNetworkTypeChangedRecoverySignal(
                probeSucceeded = probeSucceeded,
                networkRecoveryNeeded = networkRecoveryNeeded
            )
        )
    }

    private fun evaluateNetworkTypeChangedFallbackState(
        mode: BoxWrapperManager.RecoveryMode,
        signal: NetworkTypeChangedRecoverySignal
    ): NetworkTypeChangedFallbackState {
        val signalOutcome = "probe_ok=${signal.probeSucceeded},network_recovery_needed=${signal.networkRecoveryNeeded}"
        val fallbackState = buildNetworkTypeChangedStateSkip()
            ?: if (signal.strongSignal) {
                NetworkTypeChangedFallbackState(
                    shouldSkip = true,
                    event = "network_type_changed_fallback_skipped_recovered",
                    outcome = signalOutcome
                )
            } else {
                buildTriggeredNetworkTypeChangedFallbackState(mode, signalOutcome)
            }
        return fallbackState
    }

    private fun buildTriggeredNetworkTypeChangedFallbackState(
        mode: BoxWrapperManager.RecoveryMode,
        signalOutcome: String
    ): NetworkTypeChangedFallbackState {
        val action = determineNetworkTypeChangedFallbackAction(mode)
        val now = SystemClock.elapsedRealtime()
        val debounceMs = resolveNetworkTypeChangedFallbackDebounceMs(action)
        val lastActionAtMs = resolveLastNetworkTypeChangedFallbackAtMs(action)
        return if (!shouldRunNetworkTypeChangedFallback(lastActionAtMs, now, debounceMs)) {
            NetworkTypeChangedFallbackState(
                shouldSkip = true,
                event = "network_type_changed_fallback_skipped_debounce",
                outcome = "$signalOutcome,action=${action.name},debounce=${debounceMs}ms"
            )
        } else {
            recordNetworkTypeChangedFallbackAt(action, now)
            NetworkTypeChangedFallbackState(
                shouldSkip = false,
                event = "network_type_changed_fallback_triggered",
                outcome = "$signalOutcome,action=${action.name}",
                action = action
            )
        }
    }

    private fun buildNetworkTypeChangedStateSkip(): NetworkTypeChangedFallbackState? {
        val stateSkipOutcome = recoveryInvalidStateSummary()?.let { "state_$it" } ?: ""
        val shouldSkipByState = shouldSkipNetworkTypeChangedFallbackByState(
            isRunning = isRunning,
            isStarting = isStarting,
            isStopping = isStopping,
            isManuallyStopped = isManuallyStopped
        )
        return if (shouldSkipByState) {
            NetworkTypeChangedFallbackState(
                shouldSkip = true,
                event = "network_type_changed_fallback_skipped_state",
                outcome = stateSkipOutcome
            )
        } else {
            null
        }
    }

    private fun resolveLastNetworkTypeChangedFallbackAtMs(
        action: NetworkTypeChangedFallbackAction
    ): Long {
        return if (action == NetworkTypeChangedFallbackAction.ESCALATE_HARD) {
            lastNetworkTypeChangedHardFallbackAtMs.get()
        } else {
            lastNetworkTypeChangedRestartAtMs.get()
        }
    }

    private fun resolveNetworkTypeChangedFallbackDebounceMs(
        action: NetworkTypeChangedFallbackAction
    ): Long {
        return if (action == NetworkTypeChangedFallbackAction.ESCALATE_HARD) {
            networkTypeChangedHardFallbackDebounceMs
        } else {
            networkTypeChangedRestartDebounceMs
        }
    }

    private fun recordNetworkTypeChangedFallbackAt(
        action: NetworkTypeChangedFallbackAction,
        now: Long
    ) {
        if (action == NetworkTypeChangedFallbackAction.ESCALATE_HARD) {
            lastNetworkTypeChangedHardFallbackAtMs.set(now)
        } else {
            lastNetworkTypeChangedRestartAtMs.set(now)
        }
    }

    private fun scheduleNetworkTypeChangedFallbackIfNeeded(
        request: RecoveryRequest,
        mode: BoxWrapperManager.RecoveryMode,
        success: Boolean
    ) {
        if (!shouldScheduleNetworkTypeChangedFallback(request, success)) {
            return
        }

        networkTypeChangedFallbackJob?.cancel()
        networkTypeChangedFallbackJob = serviceScope.launch {
            delay(networkTypeChangedRecoveryGraceMs)

            val signal = collectNetworkTypeChangedRecoverySignal()
            val state = evaluateNetworkTypeChangedFallbackState(mode, signal)
            logRecoveryEvent(
                event = state.event,
                request = request,
                mode = mode,
                merged = false,
                skipped = state.shouldSkip,
                outcome = state.outcome
            )
            if (state.shouldSkip) {
                return@launch
            }

            when (state.action) {
                NetworkTypeChangedFallbackAction.ESCALATE_HARD -> {
                    val hardRequest = RecoveryRequest(
                        reason = RecoveryReason.NETWORK_TYPE_CHANGED,
                        rawReason = "network_type_changed_hard_fallback",
                        force = true,
                        requestedAtMs = SystemClock.elapsedRealtime(),
                        merged = false
                    )
                    submitRecoveryRequest(hardRequest)
                }

                NetworkTypeChangedFallbackAction.RESTART_VPN -> {
                    restartVpnService("network_type_changed_unrecovered")
                }

                null -> Unit
            }
        }
    }

    @Suppress("LongParameterList")
    private fun logRecoveryEvent(
        event: String,
        request: RecoveryRequest,
        mode: BoxWrapperManager.RecoveryMode?,
        merged: Boolean,
        skipped: Boolean,
        outcome: String?
    ) {
        val modeText = mode?.name ?: "n/a"
        val lane = if (request.reason.isFastLane) "fast" else "normal"
        val message = buildString {
            append("[RecoveryGate] event=")
            append(event)
            append(" lane=")
            append(lane)
            append(" reason=")
            append(request.reason.name)
            append(" raw=")
            append(request.rawReason)
            append(" priority=")
            append(request.reason.priority)
            append(" mode=")
            append(modeText)
            append(" merged=")
            append(merged)
            append(" skipped=")
            append(skipped)
            append(" force=")
            append(request.force)
            append(" trigger_count=")
            append(recoveryTriggerCount.get())
            append(" merged_count=")
            append(recoveryMergedCount.get())
            append(" skipped_debounce=")
            append(recoverySkippedDebounceCount.get())
            append(" soft_count=")
            append(recoverySoftCount.get())
            append(" hard_count=")
            append(recoveryHardCount.get())
            append(" success_rate=")
            append(calculateRecoverySuccessRate())
            if (!outcome.isNullOrBlank()) {
                append(" outcome=")
                append(outcome)
            }
        }
        Log.i(TAG, message)
        runCatching { LogRepository.getInstance().addLog("INFO: $message") }
    }

/**
     * 重启 VPN 服务以彻底清理网络状态
     * 用于处理网络栈重置无效的严重情况
     */
    @Suppress("UnusedPrivateMember")
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

// VPN 启动窗口期保护
// 在 VPN 启动后的短时间内，updateDefaultInterface 跳过 setUnderlyingNetworks 调用
    private val vpnStartedAtMs = AtomicLong(0)
    private val vpnStartupWindowMs: Long = 3000L

    @Volatile private var lastConnectionsResetAtMs: Long = 0L
    private val connectionsResetDebounceMs: Long = 2000L

// ACTION_PREPARE_RESTART 防抖：避免短时间内重复触发导致网络反复震荡
    private val lastPrepareRestartAtMs = AtomicLong(0L)
    private val prepareRestartDebounceMs: Long = 1500L

    internal enum class RecoveryReason(
        val priority: Int,
        val sourceDebounceMs: Long,
        val isFastLane: Boolean
    ) {
        NETWORK_TYPE_CHANGED(priority = 100, sourceDebounceMs = 3000L, isFastLane = true),
        DOZE_EXIT(priority = 90, sourceDebounceMs = 3000L, isFastLane = true),
        NETWORK_VALIDATED(priority = 80, sourceDebounceMs = 3000L, isFastLane = false),
        VPN_HEALTH(priority = 70, sourceDebounceMs = 30000L, isFastLane = false),
        APP_FOREGROUND(priority = 50, sourceDebounceMs = 1500L, isFastLane = true),
        SCREEN_ON(priority = 50, sourceDebounceMs = 1500L, isFastLane = true),
        UNKNOWN(priority = 10, sourceDebounceMs = 3000L, isFastLane = false);

        companion object {
            @JvmStatic
            fun fromReasonString(reason: String): RecoveryReason {
                val normalized = reason.trim().lowercase()
                return when {
                    normalized.contains("network_type_changed") ||
                        normalized.contains("typechange") -> NETWORK_TYPE_CHANGED
                    normalized.contains("doze_exit") -> DOZE_EXIT
                    normalized.contains("network_validated") -> NETWORK_VALIDATED
                    normalized.contains("vpnhealth") || normalized.contains("vpn_health") -> VPN_HEALTH
                    normalized.contains("app_foreground") -> APP_FOREGROUND
                    normalized.contains("screen_on") -> SCREEN_ON
                    else -> UNKNOWN
                }
            }
        }
    }

    internal enum class RecoveryProfile {
        DEFAULT,
        HYSTERIA2
    }

    private val recoveryGlobalDebounceMs: Long = 800L
    private val recoveryFastLaneGlobalDebounceMs: Long = 150L
    private val recoveryFastLaneSourceDebounceCapMs: Long = 400L
    private val recoveryMergeWindowMs: Long = 250L

    @Volatile private var recoveryInFlight: Boolean = false
    @Volatile private var pendingRecoveryRequest: RecoveryRequest? = null
    @Volatile private var recoveryMergeJob: Job? = null
    @Volatile private var pendingMergeRequest: RecoveryRequest? = null

    private val recoveryLastTriggeredAtMs = AtomicLong(0L)
    private val recoveryTriggerCount = AtomicLong(0L)
    private val recoveryMergedCount = AtomicLong(0L)
    private val recoverySkippedDebounceCount = AtomicLong(0L)
    private val recoverySoftCount = AtomicLong(0L)
    private val recoveryHardCount = AtomicLong(0L)
    private val recoverySuccessCount = AtomicLong(0L)
    private val recoveryFailureCount = AtomicLong(0L)
    private val recoveryConsecutiveFailureCount = AtomicInteger(0)

    private val recoveryReasonLastAtMs = ConcurrentHashMap<String, Long>()

    private val foregroundRecoveryGraceMs: Long = 3000L
    private var foregroundHardFallbackJob: Job? = null
    private val lastForegroundHardFallbackAtMs = AtomicLong(0L)
    private val foregroundHardFallbackDebounceMs: Long = 15000L

    private val networkTypeChangedRecoveryGraceMs: Long = 4000L
    private var networkTypeChangedFallbackJob: Job? = null
    private val lastNetworkTypeChangedHardFallbackAtMs = AtomicLong(0L)
    private val lastNetworkTypeChangedRestartAtMs = AtomicLong(0L)
    private val networkTypeChangedHardFallbackDebounceMs: Long = 8000L
    private val networkTypeChangedRestartDebounceMs: Long = 20000L

    private data class ForegroundFallbackState(
        val shouldSkip: Boolean,
        val event: String,
        val outcome: String
    )

    internal enum class NetworkTypeChangedFallbackAction {
        ESCALATE_HARD,
        RESTART_VPN
    }

    private data class NetworkTypeChangedFallbackState(
        val shouldSkip: Boolean,
        val event: String,
        val outcome: String,
        val action: NetworkTypeChangedFallbackAction? = null
    )

    private data class NetworkTypeChangedRecoverySignal(
        val probeSucceeded: Boolean,
        val networkRecoveryNeeded: Boolean,
        val strongSignal: Boolean
    )

    internal data class RecoveryRequest(
        val reason: RecoveryReason,
        val rawReason: String,
        val force: Boolean,
        val requestedAtMs: Long,
        val merged: Boolean
    )

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
            ConfigRepository.getInstance(this@SingBoxService).activeNodeId.collect { _ ->
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
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        runCatching {
            LogRepository.getInstance().addLog("INFO SingBoxService: onStartCommand action=${intent?.action}")
        }
        if (intent?.action == null) {
            handleStickyRestartIntent()
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                isManuallyStopped = false
                VpnStateStore.setManuallyStopped(false)
                VpnTileService.persistVpnPending(applicationContext, "starting")

                // 性能优化: 预创建 TUN Builder (非阻塞)
                coreManager.preallocateTunBuilder()

                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                val pendingNode = intent.getStringExtra(EXTRA_PENDING_NODE_NAME)
                if (!pendingNode.isNullOrBlank()) {
                    pendingNodeName = pendingNode
                    realTimeNodeName = null
                    VpnStateStore.setActiveLabel(pendingNode)
                    requestNotificationUpdate(force = true)
                    requestRemoteStateUpdate(force = true)
                }
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
                                withContext(Dispatchers.Main) {
                                    clearStartCommandFailureState()
                                    stopSelf()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error generating config in Service", e)
                            setLastError("Error generating config: ${e.message}")
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
                val targetNodeId = intent.getStringExtra("node_id")
                val outboundTag = intent.getStringExtra("outbound_tag")
                val targetNodeName = intent.getStringExtra(EXTRA_PENDING_NODE_NAME)
                runCatching {
                    LogRepository.getInstance().addLog(
                        "INFO SingBoxService: ACTION_SWITCH_NODE nodeId=${targetNodeId.orEmpty()} " +
                            "outboundTag=${outboundTag.orEmpty()} targetNodeName=${targetNodeName.orEmpty()}"
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
                        targetNodeName = targetNodeName,
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
                val reason = intent.getStringExtra(EXTRA_PREPARE_RESTART_REASON).orEmpty()
                Log.i(TAG, "Received ACTION_PREPARE_RESTART (reason='$reason') -> preparing for VPN restart")
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
            ACTION_FULL_RESTART -> {
                Log.i(TAG, "Received ACTION_FULL_RESTART -> performing full restart (TUN rebuild)")
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (configPath.isNullOrEmpty()) {
                    Log.e(TAG, "ACTION_FULL_RESTART: config path is empty")
                } else {
                    performFullRestart(configPath)
                }
            }
            ACTION_RESET_CONNECTIONS -> {
                Log.i(TAG, "Received ACTION_RESET_CONNECTIONS -> user requested connection reset")
                if (isRunning) {
                    serviceScope.launch {
                        BoxWrapperManager.resetAllConnections(true)
                        runCatching {
                            LogRepository.getInstance().addLog("INFO: User triggered connection reset via notification")
                        }
                    }
                }
            }
            ACTION_NETWORK_BUMP -> {
                Log.i(TAG, "Received ACTION_NETWORK_BUMP -> triggering network bump")
                if (isRunning) {
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

    private fun handleStickyRestartIntent() {
        if (isRunning || isStarting) {
            Log.i(TAG, "Sticky restart ignored: service is already running or starting")
            return
        }

        val runningConfigFile = File(filesDir, "running_config.json")
        val runningConfigUsable = isRunningConfigUsable(runningConfigFile)
        val mode = VpnStateStore.getMode()
        val manuallyStopped = VpnStateStore.isManuallyStopped()
        if (!shouldRecoverFromStickyRestart(manuallyStopped, mode, runningConfigUsable)) {
            Log.i(
                TAG,
                "Sticky restart skipped: manuallyStopped=$manuallyStopped, mode=$mode, " +
                    "runningConfigUsable=$runningConfigUsable"
            )
            return
        }

        Log.w(TAG, "Sticky restart recovering VPN from ${runningConfigFile.absolutePath}")
        isManuallyStopped = false
        VpnStateStore.setManuallyStopped(false)
        VpnTileService.persistVpnPending(applicationContext, "starting")
        updateServiceState(ServiceState.STARTING)
        startVpn(runningConfigFile.absolutePath)
    }

    private fun clearStartCommandFailureState() {
        synchronized(this) {
            isRunning = false
            isStarting = false
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

    @Volatile private var pendingHotSwitchFallbackConfigPath: String? = null

    /**
     * 执行预清理操作
     */
    private fun performPrepareRestart() {
        if (!isRunning) {
            Log.w(TAG, "performPrepareRestart: VPN not running, skip")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val last = lastPrepareRestartAtMs.get()
        val elapsed = now - last
        if (elapsed < prepareRestartDebounceMs) {
            Log.d(TAG, "performPrepareRestart: skipped (debounce, elapsed=${elapsed}ms)")
            return
        }
        lastPrepareRestartAtMs.set(now)

        serviceScope.launch {
            try {
                Log.i(TAG, "[PrepareRestart] Step 1/3: Wake up core")
                coreManager.wakeService()

                Log.i(TAG, "[PrepareRestart] Step 2/3: Disconnect underlying network")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    setUnderlyingNetworks(null)
                }

                // Step 3: 等待应用收到广播
                // 不需要太长时间，因为VPN重启本身也需要时间
                Log.i(TAG, "[PrepareRestart] Step 3/3: Waiting for apps to process network change...")
                delay(100)

                // 注意：不需要调用 closeAllConnectionsImmediate()
                // 因为 VPN 重启时服务关闭会强制关闭所有连接

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
                Log.e(TAG, "performHotReload error", e)
                handleHotReloadFailure("Hot reload error: ${e.message}")
            }
        }
    }

    private fun handleHotReloadFailure(errorMsg: String) {
        Log.e(TAG, "[HotReload] $errorMsg, stopping VPN")
        LogRepository.getInstance().addLog("ERROR [HotReload] $errorMsg")

        serviceScope.launch(Dispatchers.Main) {
            AppNotificationManager.showMessage(
                context = applicationContext,
                message = errorMsg,
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
        }

        isManuallyStopped = false
        stopVpn(stopService = true)
    }

    private fun performFullRestart(configPath: String) {
        if (!isRunning) {
            Log.w(TAG, "performFullRestart: VPN not running, starting directly")
            startVpn(configPath)
            return
        }

        serviceScope.launch {
            try {
                Log.i(TAG, "[FullRestart] Step 1/3: Stopping VPN completely...")

                coreManager.closeTunInterface()

                stopVpn(stopService = false)

                var waitCount = 0
                while (isStopping && waitCount < 50) {
                    delay(100)
                    waitCount++
                }

                Log.i(TAG, "[FullRestart] Step 2/3: VPN stopped, waiting for cleanup...")
                delay(200)

                Log.i(TAG, "[FullRestart] Step 3/3: Restarting VPN with new config...")
                lastConfigPath = configPath
                startVpn(configPath)

                Log.i(TAG, "[FullRestart] Complete")
            } catch (e: Exception) {
                Log.e(TAG, "performFullRestart error", e)
                setLastError("Full restart failed: ${e.message}")
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
    fun performHotReloadSync(configContent: String): Boolean {
        if (!isRunning) {
            Log.w(TAG, "performHotReloadSync: VPN not running")
            return false
        }

        return try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                Log.i(TAG, "[HotReload-Sync] Starting kernel-level hot reload...")

                val settings = SettingsRepository.getInstance(applicationContext).settings.first()
                coreManager.setCurrentSettings(settings)

                val result = coreManager.hotReloadConfig(configContent, preserveSelector = true)

                result.getOrNull() == true && result.isSuccess.also { success ->
                    if (success && result.getOrNull() == true) {
                        Log.i(TAG, "[HotReload-Sync] Kernel hot reload succeeded")
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
            Log.e(TAG, "performHotReloadSync error", e)
            false
        }
    }

    /**
     *
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
            Log.e(TAG, "Failed to call startForeground", e)
        }
        if (!shouldContinueCoreStartAfterForegroundResult(foregroundStarted)) {
            setLastError("Failed to start foreground service")
            clearStartCommandFailureState()
            stopSelf()
        } else {
            continueStartVpnAfterForeground(configPath)
        }
    }

    private fun continueStartVpnAfterForeground(configPath: String) {
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
            if (!isRunning && !isStopping && serviceState == ServiceState.STARTING) {
                updateServiceState(ServiceState.STOPPED)
            }
            updateTileState()
        }
    }

    private fun stopVpn(stopService: Boolean, broadcastStoppingState: Boolean = true) {
        // 状态同步检查（保留在 Service 中，因为涉及多线程同步）
        synchronized(this) {
            stopSelfRequested = stopSelfRequested || stopService
            if (isStopping) {
                return
            }
            isStopping = true
        }

        cancelPendingRecoveryWork()

        // 更新状态
        if (broadcastStoppingState) {
            updateServiceState(ServiceState.STOPPING)
        } else {
            requestRemoteStateUpdate(force = true)
        }
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
        autoFailoverServiceStartedAtMs = 0L
        isProxyIdleForAutoFailover = false

        // 清理 networkManager (stopService 时释放)
        if (stopService) {
            networkManager?.reset()
            networkManager = null
        } else {
            networkManager?.reset()
        }

        Log.i(TAG, "stopVpn(stopService=$stopService) isManuallyStopped=$isManuallyStopped")

        // 获取代理端口用于等待释放
        val proxyPort = currentSettings?.proxyPort ?: 2080

        // 委托给 ShutdownManager
        // 不需要严格等待端口释放，启动时会强杀进程确保端口可用
        cleanupJob = shutdownManager.stopVpn(
            options = ShutdownManager.ShutdownOptions(
                stopService = stopService,
                preserveTunInterface = !stopService,
                proxyPort = proxyPort,
                strictPortRelease = false
            ),
            coreManager = coreManager,
            commandManager = commandManager,
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

            // 显式触发 TileService 刷新，避免仅依赖 listening/bind 回调导致状态滞后
            val refreshIntent = Intent(this, VpnTileService::class.java).apply {
                action = VpnTileService.ACTION_REFRESH_TILE
            }
            startService(refreshIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update tile state", e)
        }
    }

    private fun buildNotificationState(): VpnNotificationManager.NotificationState {
        val configRepository = ConfigRepository.getInstance(this)
        val activeNodeId = configRepository.activeNodeId.value
        val nodeName = resolveNotificationNodeLabel(
            selectedNodeName = configRepository.nodes.value.find { it.id == activeNodeId }?.name,
            selectedNodeStoreLabel = VpnStateStore.getSelectedNodeLabel()
        )

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
        cancelPendingRecoveryWork()

        val shouldStop = runCatching {
            synchronized(this@SingBoxService) {
                isRunning || isStopping || coreManager.isServiceRunning() || vpnInterface != null
            }
        }.getOrDefault(false)

        // Ensure critical state is saved synchronously before we potentially halt
        if (!isManuallyStopped && shouldStop) {
            // If we are being destroyed but not manually stopped (e.g. app update or system kill),
            // keep the persisted intent recoverable for VpnKeepaliveWorker.
            VpnTileService.persistVpnState(applicationContext, true)
            VpnTileService.persistVpnPending(applicationContext, "")
            VpnStateStore.setMode(VpnStateStore.CoreMode.VPN)
            Log.i(TAG, "onDestroy: Preserved recoverable VPN state")
        }

        if (shouldStop) {
            // Note: stopVpn launches a cleanup job on cleanupScope.
            // If we halt() immediately, that job will die.
            // For app updates, the system kills us anyway, so cleanup might be best-effort.
            stopVpn(stopService = false)
        } else {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            VpnTileService.persistVpnState(applicationContext, false)
            VpnTileService.persistVpnPending(applicationContext, "")
            updateServiceState(ServiceState.STOPPED)
            updateTileState()
        }

        serviceSupervisorJob.cancel()
        autoFailoverJob?.cancel()
        autoFailoverJob = null
        autoFailoverSupervisorJob.cancel()
        autoFailoverDispatcher.close()
        // cleanupSupervisorJob.cancel() // Allow cleanup to finish naturally

        if (instance == this) {
            instance = null
        }
        super.onDestroy()

        Log.i(TAG, "SingBoxService cleanup complete, pid=${android.os.Process.myPid()}.")
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke called -> stopVpn(stopService=true)")
        isManuallyStopped = true
        VpnStateStore.setManuallyStopped(true)
        // Another VPN took over. Persist OFF state immediately so QS tile won't stay active.
        VpnTileService.persistVpnState(applicationContext, false)
        VpnTileService.persistVpnPending(applicationContext, "")
        setLastError("VPN revoked by system (another VPN may have started)")
        updateServiceState(ServiceState.STOPPED)
        requestRemoteStateUpdate(force = true)
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
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .build()
            manager.notify(VpnNotificationManager.NOTIFICATION_ID + 1, notification)
        }

        // 停止服务
        stopVpn(stopService = true, broadcastStoppingState = false)
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
            isCallbackReady = { networkCallbackReady },
            lastKnownNetwork = { lastKnownNetwork },
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
     * VPN 启动成功后延迟执行，在后台静默更新规则集
     * 这样启动时不需要等待规则集下载
     *
     * 2026-fix: 增加延迟时间并检查 CommandClient 状态，防止与 gomobile 回调并发导致
     * go/Seq Unknown reference 崩溃
     */
    private fun scheduleAsyncRuleSetUpdate() {
        serviceScope.launch(Dispatchers.IO) {
            // 2026-fix: 增加延迟到 15 秒，确保 CommandClient 回调已稳定
            delay(15000)

            if (!isRunning || isStopping) {
                Log.d(TAG, "scheduleAsyncRuleSetUpdate: VPN not running, skip")
                return@launch
            }

            // 2026-fix: 检查 CommandClient 是否已收到回调，避免在初始化阶段并发访问
            val groupsCount = commandManager.getGroupsCount()
            if (groupsCount == 0) {
                Log.d(TAG, "scheduleAsyncRuleSetUpdate: CommandClient not ready yet, skip")
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

enum class ServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING
}
