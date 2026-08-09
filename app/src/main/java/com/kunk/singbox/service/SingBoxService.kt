package com.kunk.singbox.service

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.R
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.LatencyProbeTrafficKind
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.MeteredNodeConfigGuard
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.buildServiceLifecycleDiagnostic
import com.kunk.singbox.service.manager.BackgroundPowerManager
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.manager.CoreManager
import com.kunk.singbox.service.manager.ForeignVpnMonitor
import com.kunk.singbox.service.manager.LayeredNetworkHealthSampler
import com.kunk.singbox.service.manager.NetworkHelper
import com.kunk.singbox.service.manager.NodeSwitchManager
import com.kunk.singbox.service.manager.PlatformInterfaceImpl
import com.kunk.singbox.service.manager.RecoveryPolicy
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.ScreenStateManager
import com.kunk.singbox.service.manager.SameNodeFailureLayer
import com.kunk.singbox.service.manager.SameNodeRecoveryCoordinator
import com.kunk.singbox.service.manager.SameNodeRecoveryGate
import com.kunk.singbox.service.manager.SameNodeRecoveryOutcome
import com.kunk.singbox.service.manager.SameNodeRecoveryPermit
import com.kunk.singbox.service.manager.SameNodeRecoveryStage
import com.kunk.singbox.service.manager.SameNodeRecoveryVerification
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.ShutdownManager
import com.kunk.singbox.service.manager.TimedProbeResult
import com.kunk.singbox.service.manager.UrlTestTagMatcher
import com.kunk.singbox.service.manager.VpnStopInitiator
import com.kunk.singbox.service.manager.probePhysicalDns
import com.kunk.singbox.service.manager.toProbeDiagnosticFields
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.utils.DefaultNetworkListener
import com.kunk.singbox.utils.L
import com.kunk.singbox.utils.LocalNetworkPermission
import com.kunk.singbox.utils.LocaleHelper
import com.kunk.singbox.utils.NetworkClient
import com.kunk.singbox.utils.VersionInfo
import com.kunk.singbox.utils.perf.StateCache
import com.kunk.singbox.utils.perf.PerfTracer
import com.kunk.singbox.utils.perf.BackgroundResourceGuard
import com.kunk.singbox.utils.perf.ResourceGuardRegistration
import com.kunk.singbox.utils.perf.ResourceGuardOwner
import com.kunk.singbox.utils.perf.readProcessStartedAtEpochMs
import io.nekohasekai.libbox.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.absoluteValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class UidPackageCacheEntry(
    val packageName: String,
    val cachedAtMs: Long
)

@Suppress("TooManyFunctions", "LargeClass", "ProtectedMemberInFinalClass")
class SingBoxService : VpnService() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapFromCache(newBase))
    }

    protected val gson = Gson()
    private val defaultNetworkListenerKey = Any()

    // ===== 新架构 Managers =====
    // 核心管理器 (VPN 启动/停止)

    protected val coreManager: CoreManager by lazy {
        CoreManager(this, this, serviceScope)
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
        NetworkHelper(this)
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
        override fun protect(fd: Int): Boolean = this@SingBoxService.protect(fd)

        override fun openTun(options: TunOptions): Result<Int> {
            isConnectingTun.set(true)
            return try {
                val network = getCurrentPhysicalNetwork()
                val result = coreManager.openTun(options, network, reuseExisting = true)
                result.onSuccess { _ ->
                    vpnInterface = coreManager.vpnInterface
                    if (network != null) {
                        lastKnownNetwork = network
                        markPhysicalNetworkChanged()
                    }
                }
                result
            } finally {
                isConnectingTun.set(false)
            }
        }

        override fun getConnectivityManager(): ConnectivityManager? = connectivityManager
        override fun getCurrentNetwork(): Network? = getCurrentPhysicalNetwork()
        override fun getLastKnownNetwork(): Network? = lastKnownNetwork
        override fun setLastKnownNetwork(network: Network?) { lastKnownNetwork = network }
        override fun markVpnStarted() { markPhysicalNetworkChanged() }

        override fun onDefaultNetworkChanged() {
            this@SingBoxService.handleDefaultNetworkChanged()
        }
        override fun setUnderlyingNetworks(networks: Array<Network>?) {
            this@SingBoxService.setUnderlyingNetworks(networks)
        }

        override fun getCurrentSettings(): AppSettings? = coreManager.currentSettings

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
        override fun getUidFromCache(uid: Int): String? = getCachedPackageForUid(uid)

        override fun findBestPhysicalNetwork(): Network? = this@SingBoxService.findBestPhysicalNetwork()
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
        override fun onStarting(recoveryIntentLease: RecoveryIntentLease): Boolean =
            synchronized(this@SingBoxService) {
                if (!ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease)) {
                    return@synchronized false
                }
                updateServiceState(ServiceState.STARTING)
                true
            }

        override fun clearIsStartingIfCurrent(recoveryIntentLease: RecoveryIntentLease) {
            synchronized(this@SingBoxService) {
                if (ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease)) {
                    SingBoxService.isStarting = false
                }
            }
        }

        override fun onStarted(configContent: String) {
            Log.i(SingBoxService.TAG, "KunBox VPN started successfully")
            notificationManager.setSuppressUpdates(false)
            autoFailoverServiceStartedAtMs = System.currentTimeMillis()
        }

        override fun onFailed(error: String) {
            Log.e(SingBoxService.TAG, error)
            setLastError(error)
            VpnStateStore.setLastError(error)
            notificationManager.setSuppressUpdates(true)
            notificationManager.cancelNotification()
        }

        override fun onCancelled(recoveryIntentLease: RecoveryIntentLease) {
            synchronized(this@SingBoxService) {
                if (!ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease)) return
                Log.i(SingBoxService.TAG, "startVpn cancelled")
                if (!isStopping) {
                    Log.w(SingBoxService.TAG, "startVpn cancelled but not by stopVpn, resetting state to STOPPED")
                    SingBoxService.isRunning = false
                    updateServiceState(ServiceState.STOPPED)
                }
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

        override fun createAndStartCommandServer(
            startToken: Long,
            recoveryIntentLease: RecoveryIntentLease
        ): Result<Boolean> {
            return runCatching {
                var server: CommandServer? = null
                var adopted = false
                try {
                    if (!isCommandServerStartupCurrent(startToken, recoveryIntentLease)) {
                        return@runCatching false
                    }
                    val createdServer = commandManager.createServer(platformInterfaceImpl).getOrThrow()
                    server = createdServer
                    if (!isCommandServerStartupCurrent(startToken, recoveryIntentLease)) {
                        return@runCatching false
                    }
                    commandManager.startServer(createdServer).getOrThrow()
                    adopted = synchronized(this@SingBoxService) {
                        if (!isCommandServerStartupCurrentLocked(startToken, recoveryIntentLease)) {
                            false
                        } else {
                            commandManager.adoptServer(createdServer)
                            coreManager.setCommandServer(createdServer)
                            true
                        }
                    }
                    if (adopted) {
                        Log.i(SingBoxService.TAG, "CommandServer created and started")
                    }
                    adopted
                } finally {
                    if (!adopted) {
                        runCatching { server?.close() }
                            .onFailure { Log.w(SingBoxService.TAG, "Failed to close unowned CommandServer", it) }
                    }
                }
            }
        }

        override fun launchPostStartTasks(configContent: String) {
            this@SingBoxService.launchPostStartTasks(configContent)
        }

        // 状态管理
        override fun updateTileState() { this@SingBoxService.updateTileState() }
        override fun setIsRunning(running: Boolean) { SingBoxService.isRunning = running; NetworkClient.onVpnStateChanged(running) }
        override fun setLastError(error: String?) { SingBoxService.setLastError(error) }
        override fun completeRecoveryIntentOnSuccess(
            lease: RecoveryIntentLease,
            configContent: String
        ): Boolean = synchronized(this@SingBoxService) {
            if (!this@SingBoxService.completeRecoveryIntentOnSuccess(lease)) return@synchronized false

            SingBoxService.isRunning = true
            SingBoxService.isStarting = false
            NetworkClient.onVpnStateChanged(true)
            SingBoxService.setLastError(null)
            VpnStateStore.setLastError(null)
            VpnTileService.persistVpnState(true)
            VpnStateStore.setMode(VpnStateStore.CoreMode.VPN)
            VpnStateStore.clearRecoveryClaim()
            VpnTileService.persistVpnPending("")
            foreignVpnMonitor.stop()
            pendingNodeName = null
            updateServiceState(ServiceState.RUNNING)
            notificationManager.setSuppressUpdates(false)
            autoFailoverServiceStartedAtMs = System.currentTimeMillis()
            tryRegisterRunningServiceForLibbox()
            updateTileState()
            launchPostStartTasks(configContent)
            Log.i(SingBoxService.TAG, "KunBox VPN started successfully")
            true
        }
        override fun persistVpnState(isRunning: Boolean) {
            VpnTileService.persistVpnState(isRunning)
            if (isRunning) {
                VpnStateStore.setMode(VpnStateStore.CoreMode.VPN)
            }
        }
        override fun persistVpnPending(pending: String) {
            VpnTileService.persistVpnPending(pending)
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
            setUnderlyingNetworks(arrayOf(network))
            Log.i(SingBoxService.TAG, "Underlying network restored before libbox start: $network")
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

        override fun stopSelf() { this@SingBoxService.stopSelf() }
    }

    // ShutdownManager 回调实现

    protected val shutdownCallbacks = object : ShutdownManager.Callbacks {
        // 状态管理
        override fun updateServiceState(state: ServiceState) {
            this@SingBoxService.updateServiceState(state)
        }
        override fun updateTileState() { this@SingBoxService.updateTileState() }
        override fun stopForegroundService() {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.e(SingBoxService.TAG, "Error stopping foreground", e)
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
        override fun cancelPostStartJob(): Job? {
            postStartGeneration.incrementAndGet()
            val job = postStartJob
            postStartJob = null
            job?.cancel()
            return job
        }
        override fun cancelHotReloadJob(): Job? {
            val job = hotReloadJob
            hotReloadJob = null
            job?.cancel()
            return job
        }
        override fun cancelRemoteStateUpdateJob() {
            remoteStateUpdateJob?.cancel()
            remoteStateUpdateJob = null
        }
        override fun cancelAutoFailoverJob() {
            autoFailoverJob?.cancel()
            autoFailoverJob = null
            sameNodeRecoveryInFlight.set(false)
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
        override fun closeDefaultInterfaceMonitor() {
            platformInterfaceImpl.closeDefaultInterfaceMonitor(null)
        }

        // 获取状态
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
        override fun setNetworkCallbackReady(ready: Boolean) { networkCallbackReady = ready }
        override fun setLastKnownNetwork(network: Network?) { lastKnownNetwork = network }
        override fun clearUnderlyingNetworks() {
            runCatching { setUnderlyingNetworks(null) }
        }

        // 获取配置路径用于重启
        override fun hasPendingStartConfigPath(): Boolean = synchronized(this@SingBoxService) {
            !pendingStartConfigPath.isNullOrBlank()
        }
        override fun completeStop(
            initialStopService: Boolean,
            recoveryIntentLease: RecoveryIntentLease
        ): ShutdownManager.StopCompletion =
            synchronized(this@SingBoxService) {
                val completion = ShutdownManager.resolveStopCompletion(
                    initialStopService = initialStopService,
                    hardStopRequested = stopSelfRequested,
                    cleanupRecoveryIntentLease = recoveryIntentLease,
                    hardStopRecoveryIntentLease = hardStopRecoveryIntentLease,
                    pendingStartConfigPath = pendingStartConfigPath,
                    pendingRecoveryIntentLease = pendingStartRecoveryIntentLease
                )
                coreManager.completeStop()
                stopSelfRequested = completion.stopService
                hardStopRecoveryIntentLease = completion.recoveryIntentLease.takeIf { completion.stopService }
                pendingStartConfigPath = null
                pendingStartRecoveryIntentLease = null
                SingBoxService.isStarting = false
                isStopping = false
                completion
            }
        override fun startVpn(configPath: String, recoveryIntentLease: RecoveryIntentLease?) {
            this@SingBoxService.startVpn(configPath, recoveryIntentLease)
        }
    }

    private fun isCommandServerStartupCurrent(
        startToken: Long,
        recoveryIntentLease: RecoveryIntentLease
    ): Boolean = synchronized(this) {
        isCommandServerStartupCurrentLocked(startToken, recoveryIntentLease)
    }

    private fun isCommandServerStartupCurrentLocked(
        startToken: Long,
        recoveryIntentLease: RecoveryIntentLease
    ): Boolean {
        return !isStopping &&
            coreManager.isStartTokenCurrent(startToken) &&
            pendingRecoveryIntentLease === recoveryIntentLease &&
            ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease)
    }

    /**
     * 初始化 SelectorManager - 记录 PROXY selector 的 outbound 列表
     *
     */

    protected var vpnInterface: ParcelFileDescriptor? = null

    protected val serviceSupervisorJob = SupervisorJob()

    protected val serviceScope = CoroutineScope(Dispatchers.IO + serviceSupervisorJob)

    protected val cleanupSupervisorJob = SupervisorJob()

    protected val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupSupervisorJob)

    protected val autoFailoverSupervisorJob = SupervisorJob()

    protected val autoFailoverScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) + autoFailoverSupervisorJob
    )
    @Volatile protected var isStopping: Boolean = false
    @Volatile protected var stopSelfRequested: Boolean = false
    @Volatile private var lastStopInitiator: VpnStopInitiator = VpnStopInitiator.UNKNOWN
    @Volatile private var hardStopRecoveryIntentLease: RecoveryIntentLease? = null
    @Volatile private var preserveRuntimeStateOnDestroy: Boolean = false
    @Volatile protected var cleanupJob: Job? = null
    @Volatile protected var autoFailoverJob: Job? = null
    @Volatile protected var pendingStartConfigPath: String? = null
    @Volatile private var pendingStartRecoveryIntentLease: RecoveryIntentLease? = null

    @Volatile protected var pendingHotSwitchFallbackConfigPath: String? = null
    @Volatile protected var pendingNodeName: String? = null
    @Volatile protected var pendingCleanCache: Boolean = false

    @Volatile protected var startVpnJob: Job? = null
    @Volatile protected var postStartJob: Job? = null
    @Volatile protected var hotReloadJob: Job? = null
    protected val postStartGeneration = AtomicLong(0L)
    private val resourceGuardGeneration = AtomicLong(0L)
    private val resourceGuardOwnerId = Any()
    @Volatile private var resourceGuardRegistration: ResourceGuardRegistration? = null
    @Volatile private var pendingRecoveryIntentLease: RecoveryIntentLease? = null
    @Volatile protected var realTimeNodeName: String? = null
    // @Volatile protected var nodePollingJob: Job? = null // Removed in favor of CommandClient

    protected val isConnectingTun = AtomicBoolean(false)

    // Command 相关变量已移至 CommandManager
    // 保留这些作为兼容性别名 (委托到 commandManager)

    protected val activeConnectionNode: String? get() = commandManager.activeConnectionNode

    protected val activeConnectionLabel: String? get() = commandManager.activeConnectionLabel

    protected val recentConnectionIds: List<String> get() = commandManager.recentConnectionIds

    // 速度计算使用 sing-box CommandStatus 的真实代理流量
    @Volatile protected var showNotificationSpeed: Boolean = true

    protected var currentUploadSpeed: Long = 0L

    protected var currentDownloadSpeed: Long = 0L

    private val healthSignalAggregator = HealthSignalAggregator()
    private val sameNodeRecoveryGate = SameNodeRecoveryGate()
    private val layeredNetworkHealthSampler = LayeredNetworkHealthSampler()
    private val sameNodeRecoveryInFlight = AtomicBoolean(false)
    private val autoFailoverCandidateCache = AutoFailoverCandidateCache()
    private val autoGroupRestoreInFlight = AtomicBoolean(false)
    @Volatile private var autoFailoverOverrideActive = false
    @Volatile private var activeAutoGroupTag: String? = null
    @Volatile protected var lastMeaningfulTrafficAtMs: Long = 0L
    @Volatile protected var autoFailoverServiceStartedAtMs: Long = 0L
    @Volatile protected var lastAutoFailoverNetworkEventAtMs: Long = 0L
    private val singleNodeRouteFailureNotificationTimes = ConcurrentHashMap<String, Long>()

    @Volatile protected var lastRuleSetCheckMs: Long = 0L

    protected val ruleSetCheckIntervalMs: Long = 6 * 60 * 60 * 1000L

    private val uidToPackageCache = ConcurrentHashMap<Int, UidPackageCacheEntry>()

    private val maxUidToPackageCacheSize: Int = 512

    protected val isScreenOn: Boolean get() = screenStateManager.isScreenOn

    protected val isAppInForeground: Boolean get() = screenStateManager.isAppInForeground

    // Auto reconnect

    protected var connectivityManager: ConnectivityManager? = null

    protected val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    protected var lastKnownNetwork: Network? = null

    // 网络就绪标志：确保 Libbox 启动前网络回调已完成初始采样
    @Volatile protected var networkCallbackReady: Boolean = false
    // ACTION_PREPARE_RESTART 防抖：避免短时间内重复触发导致网络反复震荡

    protected val lastPrepareRestartAtMs = AtomicLong(0L)

    protected val prepareRestartDebounceMs: Long = 1500L

    protected fun tryRegisterRunningServiceForLibbox() {
        // No longer needed with new CommandServer API
    }

    protected fun tryClearRunningServiceForLibbox() {
        // No longer needed with new CommandServer API
    }

    /**
     * 初始化新架构 Managers (7个核心模块)
     */
    @Suppress("CognitiveComplexMethod")
    protected fun initManagers() {
        // 1. 初始化核心管理器
        coreManager.init(platformInterfaceImpl)
        Log.i(SingBoxService.TAG, "CoreManager initialized")

        initCommandManager()
        initSecondaryManagers()

        Log.i(SingBoxService.TAG, "All managers initialized")
    }

    protected fun initCommandManager() {
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
            override fun onGroupSelectionChanged(groupTag: String, selectedTag: String) {
                this@SingBoxService.handleAutoGroupSelectionChanged(groupTag, selectedTag)
            }
            override fun onRuntimeNodeChanged(nodeName: String) {
                realTimeNodeName = nodeName
                if (nodeName == pendingNodeName) {
                    pendingNodeName = null
                }
                requestRemoteStateUpdate(force = false)
            }
            override fun onTrafficUpdate(snapshot: TrafficMonitor.TrafficSnapshot) {
                currentUploadSpeed = snapshot.uploadSpeed
                currentDownloadSpeed = snapshot.downloadSpeed
                handleTrafficUpdateForAutoFailover(snapshot)
                if (showNotificationSpeed) {
                    requestNotificationUpdate(force = false)
                }
            }
            override fun onServiceStop() {
                Log.i(SingBoxService.TAG, "CommandManager: onServiceStop requested")
                serviceScope.launch {
                    val recoveryLease = setNonResourceRecoveryIntent(false)
                    stopVpn(stopService = true, recoveryIntentLease = recoveryLease)
                }
            }
            override fun onServiceReload() {
                Log.i(SingBoxService.TAG, "CommandManager: onServiceReload requested")
            }
        })
        commandManager.setKernelLogObserver { message ->
            serviceScope.launch(Dispatchers.IO) {
                handleKernelLogForHealthSignal(message)
            }
        }
        Log.i(SingBoxService.TAG, "CommandManager initialized")
    }

    protected fun initSecondaryManagers() {
        // 初始化屏幕状态管理器
        screenStateManager.init(object : ScreenStateManager.Callbacks {
            override val isRunning: Boolean
                get() = SingBoxService.isRunning

            override fun notifyRemoteStateUpdate(force: Boolean) {
                this@SingBoxService.requestRemoteStateUpdate(force)
            }
        })
        Log.i(SingBoxService.TAG, "ScreenStateManager initialized")

        // 9. 初始化外部 VPN 监控器
        foreignVpnMonitor.init(object : ForeignVpnMonitor.Callbacks {
            override val isStarting: Boolean
                get() = SingBoxService.isStarting
            override val isRunning: Boolean
                get() = SingBoxService.isRunning
            override val isConnectingTun: Boolean
                get() = this@SingBoxService.isConnectingTun.get()
        })
        Log.i(SingBoxService.TAG, "ForeignVpnMonitor initialized")

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
        Log.i(SingBoxService.TAG, "NodeSwitchManager initialized")

        initBackgroundPowerManager()
        Log.i(SingBoxService.TAG, "BackgroundPowerManager initialized")

        Log.i(SingBoxService.TAG, "KunBox VPN started successfully")
        notificationManager.setSuppressUpdates(false)
    }

    protected fun initBackgroundPowerManager() {
        val initialThresholdMs = backgroundPowerSavingThresholdMs

        backgroundPowerManager.init(
            callbacks = object : BackgroundPowerManager.Callbacks {
                override fun suspendNonEssentialProcesses() {
                    coreManager.enterPowerSavingMode().onFailure { e ->
                        Log.w(SingBoxService.TAG, "[PowerSaving] Failed to release locks", e)
                    }
                }

                override fun resumeNonEssentialProcesses() {
                    coreManager.exitPowerSavingMode().onFailure { e ->
                        Log.w(SingBoxService.TAG, "[PowerSaving] Failed to restore locks", e)
                    }
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
                Log.w(SingBoxService.TAG, "Failed to read power saving delay setting, using default", e)
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

    protected fun initSelectorManager(configContent: String): String? {
        return try {
            val config = gson.fromJson(configContent, SingBoxConfig::class.java) ?: return null
            val proxySelector = config.outbounds?.find {
                it.type == "selector" && it.tag.equals("PROXY", ignoreCase = true)
            }

            if (proxySelector == null) {
                Log.w(SingBoxService.TAG, "No PROXY selector found in config")
                return null
            }

            val outboundTags = proxySelector.outbounds?.filter { it.isNotBlank() } ?: emptyList()
            val preferredTag = resolvePreferredProxyTag(outboundTags, proxySelector.default)

            SelectorManager.recordSelectorSignature(outboundTags)
            Log.i(
                SingBoxService.TAG,
                "SelectorManager initialized: ${outboundTags.size} outbounds, selected=$preferredTag"
            )
            preferredTag
        } catch (e: Exception) {
            Log.e(SingBoxService.TAG, "Failed to init SelectorManager", e)
            null
        }
    }

    /**
     * 启动后强制 PROXY 到手选节点。
     * 优先使用 intent 指定节点，其次使用主进程生成配置时写入的 default。
     */
    protected fun resolvePreferredProxyTag(
        outboundTags: List<String>,
        configDefault: String?
    ): String? {
        fun pick(name: String?): String? {
            if (name.isNullOrBlank()) return null
            if (name in outboundTags) return name
            return outboundTags.firstOrNull { it.equals(name, ignoreCase = true) }
        }

        return pick(pendingNodeName) ?: pick(configDefault) ?: outboundTags.firstOrNull()
    }

    protected suspend fun applyPreferredProxySelection(preferredTag: String?) {
        if (preferredTag.isNullOrBlank()) return

        val currentSelectedTag = commandManager.getSelectedOutbound("PROXY")
        if (currentSelectedTag.isNullOrBlank()) {
            Log.w(SingBoxService.TAG, "Waiting for initial PROXY selection callback: $preferredTag")
            return
        }
        val result = if (currentSelectedTag.equals(preferredTag, ignoreCase = true)) {
            SelectorManager.SwitchResult.Success("AlreadySelected")
        } else {
            SelectorManager.switchNode(preferredTag)
        }
        when (result) {
            is SelectorManager.SwitchResult.Success -> {
                val concreteTag = resolveConfirmedProxyRuntimeLabel(
                    kernelResolvedTag = commandManager.getResolvedSelectedOutbound("PROXY"),
                    preferredTag = preferredTag,
                    currentRuntimeTag = commandManager.realTimeNodeName ?: realTimeNodeName
                )
                if (concreteTag != null) {
                    realTimeNodeName = concreteTag
                    commandManager.realTimeNodeName = concreteTag
                    VpnStateStore.setActiveLabel(concreteTag)
                } else {
                    Log.w(
                        SingBoxService.TAG,
                        "Kernel confirmed automatic group but concrete node is not available yet: $preferredTag"
                    )
                }
                if (pendingNodeName == preferredTag) {
                    pendingNodeName = null
                }
                requestNotificationUpdate(force = true)
                requestRemoteStateUpdate(force = true)
                Log.i(SingBoxService.TAG, "Applied preferred PROXY selection: $preferredTag")
            }
            is SelectorManager.SwitchResult.NeedRestart -> Log.w(
                SingBoxService.TAG,
                "Preferred PROXY selection not applied: ${result.reason}, tag=$preferredTag"
            )
        }
    }

    protected fun launchPostStartTasks(configContent: String) {
        val generation = postStartGeneration.incrementAndGet()
        val previousJob = postStartJob
        previousJob?.cancel()
        postStartJob = serviceScope.launch {
            try {
                previousJob?.join()
                if (!isPostStartTaskActive(generation)) return@launch

                commandManager.getCommandServer()?.let { server ->
                    BoxWrapperManager.init(server)
                }
                Log.i(SingBoxService.TAG, "BoxWrapperManager initialized")

                commandManager.startClients().onFailure { error ->
                    Log.e(SingBoxService.TAG, "Failed to start Command Clients", error)
                }
                if (!isPostStartTaskActive(generation)) return@launch

                SelectorManager.updateCommandClient(commandManager.getCommandClient())
                applyPreferredProxySelection(initSelectorManager(configContent))
                if (!isPostStartTaskActive(generation)) return@launch

                scheduleAsyncRuleSetUpdate()

                Log.i(SingBoxService.TAG, "VPN post-start tasks completed")
            } finally {
                if (postStartGeneration.get() == generation) {
                    postStartJob = null
                }
            }
        }
    }

    private fun isPostStartTaskActive(generation: Long): Boolean {
        return postStartGeneration.get() == generation && SingBoxService.isRunning && !isStopping
    }

    /**
     * 使用统一离线临时服务测速路径并返回结果
     *
     * @param groupTag 要测试的 group 标签 (如 "PROXY")
     * @param timeoutMs 等待结果的超时时间
     * @return 节点延迟映射 (tag -> delay ms)，失败返回空 Map
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun urlTestGroup(groupTag: String, timeoutMs: Long): Map<String, Int> {
        return testGroupCandidatesLatency(groupTag)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun urlTestGroup(
        groupTag: String,
        timeoutMs: Long,
        expectedTags: Set<String>,
        onProgress: ((Map<String, Int>) -> Unit)?): Map<String, Int> {
        val results = testGroupCandidatesLatency(groupTag)
            .filterKeys { expectedTags.isEmpty() || it in expectedTags }
        onProgress?.invoke(results)
        return results
    }

    protected fun closeRecentConnectionsBestEffort(reason: String) {
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

    @Volatile protected var serviceState: ServiceState = ServiceState.STOPPED

    protected fun resolveEgressNodeName(repo: ConfigRepository, tagOrSelector: String?): String? {
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

    protected fun notifyRemoteStateNow() {
        val activeLabel = runCatching {
            val repo = ConfigRepository.getInstance(applicationContext)
            val activeNodeId = repo.activeNodeId.value
            val nodeName = resolveNotificationNodeLabel(
                selectedNodeName = repo.nodes.value.find { it.id == activeNodeId }?.name,
                selectedNodeStoreLabel = VpnStateStore.getSelectedNodeLabel(),
                runtimeNodeName = realTimeNodeName ?: VpnStateStore.getActiveLabel()
            )
            nodeName.orEmpty()
        }.getOrDefault("")

        SingBoxIpcHub.update(
            state = serviceState,
            activeLabel = activeLabel,
            lastError = SingBoxService.lastErrorFlow.value.orEmpty(),
            manuallyStopped = SingBoxService.isManuallyStopped
        )
    }

    protected fun requestRemoteStateUpdate(force: Boolean) {
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

    protected fun initializeStartupNodeLabel(configPath: String, explicitTag: String? = pendingNodeName) {
        val startupTag = runCatching {
            resolveStartupProxyTag(configPath, gson, explicitTag)
        }.onFailure { e ->
            Log.w(SingBoxService.TAG, "Failed to resolve startup node label", e)
        }.getOrNull()
        realTimeNodeName = null
        VpnStateStore.setActiveLabel(null)
        Log.i(SingBoxService.TAG, "Startup selection pending kernel confirmation: ${startupTag ?: "(none)"}")
    }

    protected fun updateServiceState(state: ServiceState) {
        if (serviceState == state) return
        serviceState = state
        if (state == ServiceState.RUNNING) {
            startResourceGuard()
        }
        requestRemoteStateUpdate(force = true)
    }

    @Suppress("CognitiveComplexMethod")
    private fun startResourceGuard() {
        val registration = ResourceGuardRegistration(
            ownerId = resourceGuardOwnerId,
            generation = resourceGuardGeneration.incrementAndGet()
        )
        resourceGuardRegistration = registration
        BackgroundResourceGuard.start(this, serviceScope, registration, object : ResourceGuardOwner {
            override fun isRecoveryAllowed(): Boolean {
                return !VpnStateStore.isManuallyStopped() &&
                    VpnStateStore.getMode() == VpnStateStore.CoreMode.VPN &&
                    isResourceRecoveryLeaseCurrent()
            }

            override fun connectionAttributionSnapshot() = commandManager.connectionAttributionSnapshot()

            override fun restartCore(reason: String, attemptId: Long): Boolean {
                if (!BackgroundResourceGuard.isRecoveryAttemptActive(resourceGuardOwnerId, attemptId)) return false
                val configPath = SingBoxService.lastConfigPath
                    ?.takeIf { File(it).isFile }
                    ?: File(filesDir, "running_config.json").takeIf(File::isFile)?.absolutePath
                    ?: return false
                LogRepository.getInstance().addAlwaysLog("WARN recovery resource_exhausted restart=$reason")
                val recoveryIntentLease = claimResourceRecoveryIntent(attemptId) ?: return false
                if (!BackgroundResourceGuard.isRecoveryAttemptActive(resourceGuardOwnerId, attemptId)) {
                    clearResourceRecoveryIntent(recoveryIntentLease)
                    return false
                }
                return performFullRestart(configPath, recoveryIntentLease)
            }

            override fun recycleProcess(reason: String) {
                synchronized(this@SingBoxService) {
                    if (VpnStateStore.isManuallyStopped() ||
                        VpnStateStore.getMode() != VpnStateStore.CoreMode.VPN ||
                        !isResourceRecoveryLeaseCurrent()
                    ) {
                        return
                    }
                    val configPath = SingBoxService.lastConfigPath
                        ?.takeIf { File(it).isFile }
                        ?: File(filesDir, "running_config.json").takeIf(File::isFile)?.absolutePath
                        ?: run {
                            publishBudgetExhausted("missing_config:$reason")
                            return
                        }
                    LogRepository.getInstance()
                        .addAlwaysLog("ERROR recovery resource_exhausted recycle_process=$reason")
                    recycleBackgroundProcess(
                        this@SingBoxService,
                        Intent(this@SingBoxService, SingBoxService::class.java).apply {
                            action = SingBoxService.ACTION_START
                            putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
                            putExtra(SingBoxService.EXTRA_RECOVERY, true)
                        }
                    )
                }
            }

            override fun publishBudgetExhausted(reason: String) {
                synchronized(this@SingBoxService) {
                    if (VpnStateStore.isManuallyStopped() ||
                        VpnStateStore.getMode() != VpnStateStore.CoreMode.VPN ||
                        !isResourceRecoveryLeaseCurrent()
                    ) {
                        return
                    }
                    val message = "Resource recovery budget exhausted: $reason"
                    SingBoxService.setLastError(message)
                    LogRepository.getInstance().addAlwaysLog("ERROR recovery resource_exhausted $message")
                    requestNotificationUpdate(force = true)
                    requestRemoteStateUpdate(force = true)
                }
            }
        })
    }

    private fun detachResourceGuard(attemptId: Long) {
        resourceGuardRegistration?.let { BackgroundResourceGuard.detach(it, attemptId) }
        resourceGuardRegistration = null
    }

    private fun cancelResourceGuard() {
        BackgroundResourceGuard.cancelOwner(resourceGuardOwnerId)
        resourceGuardRegistration = null
        clearResourceRecoveryIntent(synchronized(this) { pendingRecoveryIntentLease })
    }

    private fun isResourceRecoveryLeaseCurrent(): Boolean {
        val lease = pendingRecoveryIntentLease ?: return false
        return lease.allowsResourceClaim && ServiceStateHolder.isRecoveryIntentCurrent(lease)
    }

    private fun claimResourceRecoveryIntent(attemptId: Long): RecoveryIntentLease? {
        return synchronized(this) {
            if (VpnStateStore.isManuallyStopped() ||
                VpnStateStore.getMode() != VpnStateStore.CoreMode.VPN ||
                !isResourceRecoveryLeaseCurrent()
            ) {
                return@synchronized null
            }
            val lease = ServiceStateHolder.claimResourceRecoveryIntent(resourceGuardOwnerId, attemptId)
                ?: return@synchronized null
            pendingRecoveryIntentLease = lease
            lease
        }
    }

    private fun setNonResourceRecoveryIntent(preserve: Boolean): RecoveryIntentLease = synchronized(this) {
        ServiceStateHolder.setRecoveryIntentOnFailure(preserve).also { lease ->
            pendingRecoveryIntentLease = lease
        }
    }

    private fun clearResourceRecoveryIntent(lease: RecoveryIntentLease?) {
        lease ?: return
        synchronized(this) {
            if (pendingRecoveryIntentLease !== lease) return
            val ownedAttemptId = lease.attemptId ?: return
            if (ServiceStateHolder.clearResourceRecoveryIntent(resourceGuardOwnerId, ownedAttemptId, lease) &&
                pendingRecoveryIntentLease === lease
            ) {
                pendingRecoveryIntentLease = null
            }
        }
    }

    private fun completeRecoveryIntentOnSuccess(lease: RecoveryIntentLease): Boolean = synchronized(this) {
        val baseline = ServiceStateHolder.completeRecoveryIntentOnSuccess(lease) ?: return@synchronized false
        if (pendingRecoveryIntentLease === lease) pendingRecoveryIntentLease = baseline
        true
    }

    /**
     *
     * @return true if hot switch triggered successfully, false if restart is needed
     *
     * 核心原理:
     * PROXY selector 固定使用 interrupt_exist_connections=false。
     * 内核确认新选择后主动关闭旧连接，防止旧节点的 TCP、QUIC 和复用连接继续产生流量。
     */

    suspend fun hotSwitchNode(nodeTag: String): Boolean {
        if (!coreManager.isServiceRunning() || !SingBoxService.isRunning) return false

        try {
            L.connection("HotSwitch", "Starting switch to: $nodeTag")

            // Step 1: 唤醒核心
            coreManager.wakeService()
            L.step("HotSwitch", 1, 2, "Called wakeService()")

            L.step("HotSwitch", 2, 2, "Calling SelectorManager.switchNode...")

            when (val result = SelectorManager.switchNode(nodeTag)) {
                is SelectorManager.SwitchResult.Success -> {
                    val connectionsClosed = commandManager.closeConnections()
                    if (!connectionsClosed) {
                        L.warn("HotSwitch", "Kernel selected $nodeTag but old connections could not be closed")
                        return false
                    }
                    L.result("HotSwitch", true, "Switched to $nodeTag via ${result.method}")
                    requestNotificationUpdate(force = true)
                    return true
                }
                is SelectorManager.SwitchResult.NeedRestart -> {
                    L.warn("HotSwitch", "Need restart: ${result.reason}")
                    // 需要完整重启，返回 false 让调用者处理
                    return false
                }
            }
        } catch (e: Exception) {
            L.error("HotSwitch", "Unexpected exception", e)
            return false
        }
    }

    private fun cacheUidToPackage(uid: Int, pkg: String) {
        if (uid <= 0 || pkg.isBlank()) return
        uidToPackageCache[uid] = UidPackageCacheEntry(
            packageName = pkg,
            cachedAtMs = SystemClock.elapsedRealtime()
        )
        if (uidToPackageCache.size > maxUidToPackageCacheSize) {
            uidToPackageCache.clear()
        }
    }

    private fun getCachedPackageForUid(uid: Int): String? {
        val entry = uidToPackageCache[uid] ?: return null
        if (!PlatformInterfaceImpl.isUidPackageCacheFresh(entry.cachedAtMs, SystemClock.elapsedRealtime())) {
            uidToPackageCache.remove(uid, entry)
            return null
        }
        return entry.packageName
    }

    protected fun handleDefaultNetworkChanged() {
        val serviceUnavailable = !SingBoxService.isRunning || SingBoxService.isStarting
        val shutdownInProgress = isStopping || SingBoxService.isManuallyStopped
        if (serviceUnavailable || shutdownInProgress) {
            Log.d(SingBoxService.TAG, "Default network reset skipped because service is not runnable")
            return
        }

        lastAutoFailoverNetworkEventAtMs = System.currentTimeMillis()
        val reset = BoxWrapperManager.resetNetwork()
        Log.i(SingBoxService.TAG, "Default physical network changed, core reset=$reset")
    }

    protected fun handleTrafficUpdateForAutoFailover(snapshot: TrafficMonitor.TrafficSnapshot) {
        val totalSpeed = snapshot.uploadSpeed + snapshot.downloadSpeed
        if (!SingBoxService.shouldRecordMeaningfulTrafficForAutoFailover(totalSpeed)) {
            return
        }
        lastMeaningfulTrafficAtMs = System.currentTimeMillis()
    }

    protected fun handleKernelLogForHealthSignal(message: String) {
        val signal = healthSignalAggregator.observeKernelLog(
            line = message,
            nowMs = SystemClock.elapsedRealtime()
        ) ?: return

        LogRepository.getInstance().addAlwaysLog(HealthSignalAggregator.buildSummary(signal))
        when (signal.kind) {
            HealthSignalKind.RESOURCE_EXHAUSTED -> {
                handleResourceExhaustionSignal("kernel_emfile")
                return
            }
            HealthSignalKind.ACTIVE_PROBE_FAILED -> {
                handleActiveOutboundFailure(signal)
                return
            }
            HealthSignalKind.REMOTE_DNS_TIMEOUT -> Unit
        }

        val runningConfig = loadLastRunningConfig()
        val currentProxyTag = resolveCurrentProxyOutboundTag()
        if (runningConfig != null) {
            notifySingleNodeRouteFailureIfNeeded(signal, currentProxyTag, runningConfig)
        }
        if (runningConfig == null ||
            SingBoxService.shouldSubmitMainAutoFailoverForDnsSignal(
                dnsServerTag = signal.dnsServerTag,
                currentProxyTag = currentProxyTag,
                config = runningConfig
            )
        ) {
            submitSameNodeRecovery(
                layer = SameNodeFailureLayer.DNS,
                trigger = "dns_remote_timeout"
            )
        }
    }

    private fun handleActiveOutboundFailure(signal: HealthSignal) {
        val failureTag = signal.outboundTag?.trim().orEmpty()
        val runningConfig = loadLastRunningConfig()
        val currentProxyTag = resolveCurrentProxyOutboundTag()
        if (failureTag.isBlank() || runningConfig == null ||
            SingBoxService.shouldRecoverMainOutboundFailure(failureTag, currentProxyTag, runningConfig)
        ) {
            submitSameNodeRecovery(
                layer = SameNodeFailureLayer.PROXY,
                trigger = "active_probe_failed:$failureTag"
            )
            return
        }

        val guardTriggered = commandManager.handleOutboundFailureBurst(
            outboundTag = failureTag,
            failureCount = signal.failureCount,
            nowMs = SystemClock.elapsedRealtime()
        )
        notifySingleNodeRouteFailureIfNeeded(failureTag)
        LogRepository.getInstance().addAlwaysLog(
            "WARN recovery app_route_failure outbound=$failureTag selected=${currentProxyTag.orEmpty()} " +
                "guard=$guardTriggered scope=failed_outbound_only"
        )
    }

    protected fun handleResourceExhaustionSignal(reason: String) {
        autoFailoverJob?.cancel()
        autoFailoverJob = null
        autoFailoverCandidateCache.clear()
        val registration = resourceGuardRegistration ?: run {
            startResourceGuard()
            resourceGuardRegistration
        }
        if (registration != null) {
            BackgroundResourceGuard.signalResourceExhaustion(registration, reason)
        } else {
            LogRepository.getInstance().addAlwaysLog(
                "ERROR recovery resource_exhausted stage=guard_registration_failed reason=$reason"
            )
        }
    }

    private fun notifySingleNodeRouteFailureIfNeeded(
        signal: HealthSignal,
        currentProxyTag: String?,
        runningConfig: SingBoxConfig
    ) {
        val failureTag = SingBoxService.resolveSingleNodeRouteFailureTag(
            dnsServerTag = signal.dnsServerTag,
            currentProxyTag = currentProxyTag,
            config = runningConfig
        ) ?: return

        notifySingleNodeRouteFailureIfNeeded(failureTag)
    }

    private fun notifySingleNodeRouteFailureIfNeeded(failureTag: String) {

        val now = SystemClock.elapsedRealtime()
        val lastNotifyAt = singleNodeRouteFailureNotificationTimes[failureTag] ?: 0L
        if (!SingBoxService.shouldNotifySingleNodeRouteFailure(failureTag, lastNotifyAt, now)) {
            return
        }
        singleNodeRouteFailureNotificationTimes[failureTag] = now

        val configRepository = ConfigRepository.getInstance(this@SingBoxService)
        val displayName = configRepository.resolveNodeNameFromOutboundTag(failureTag) ?: failureTag
        val message = SingBoxService.buildSingleNodeRouteFailureNotificationText(displayName)
        LogRepository.getInstance().addLog("WARN: $message")

        val notificationId = 2600 + (failureTag.hashCode().absoluteValue % 500)
        val notification = notificationManager.createStartingNotification(message)
        notificationManager.showTemporaryNotification(notificationId, notification)
        serviceScope.launch {
            delay(8000)
            notificationManager.cancelNotification(VpnNotificationManager.NOTIFICATION_ID + notificationId)
        }
    }

    @Suppress("CognitiveComplexMethod", "ComplexCondition")
    private fun submitSameNodeRecovery(layer: SameNodeFailureLayer, trigger: String) {
        if (!SingBoxService.isRunning || SingBoxService.isStarting || isStopping || SingBoxService.isManuallyStopped) {
            return
        }
        if (autoFailoverJob?.isActive == true || !sameNodeRecoveryInFlight.compareAndSet(false, true)) {
            Log.d(SingBoxService.TAG, "[SameNodeRecovery] ignored because recovery/failover is already running")
            return
        }

        when (sameNodeRecoveryGate.acquire(SystemClock.elapsedRealtime())) {
            SameNodeRecoveryPermit.COOLDOWN -> {
                sameNodeRecoveryInFlight.set(false)
                LogRepository.getInstance().addAlwaysLog(
                    "INFO recovery same_node skipped=cooldown layer=$layer trigger=$trigger"
                )
            }
            SameNodeRecoveryPermit.BUDGET_EXHAUSTED -> {
                sameNodeRecoveryInFlight.set(false)
                LogRepository.getInstance().addAlwaysLog(
                    "WARN recovery same_node budget_exhausted layer=$layer trigger=$trigger escalate=auto_failover"
                )
                submitAutoFailoverSuspicion(trigger)
            }
            SameNodeRecoveryPermit.ACQUIRED -> {
                val recoveryJob = autoFailoverScope.launch(start = CoroutineStart.LAZY) {
                    var escalateToFailover = false
                    try {
                        val outcome = createSameNodeRecoveryCoordinator(layer, trigger).recover(layer)
                        escalateToFailover = outcome == SameNodeRecoveryOutcome.Failed
                        LogRepository.getInstance().addAlwaysLog(
                            "INFO recovery same_node completed layer=$layer trigger=$trigger outcome=$outcome"
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        escalateToFailover = true
                        Log.e(SingBoxService.TAG, "[SameNodeRecovery] failed", error)
                        LogRepository.getInstance().addAlwaysLog(
                            "ERROR recovery same_node exception=${error.javaClass.simpleName} " +
                                "message=${error.message.orEmpty()} layer=$layer trigger=$trigger"
                        )
                    } finally {
                        sameNodeRecoveryInFlight.set(false)
                        val currentJob = coroutineContext[Job]
                        if (autoFailoverJob === currentJob) {
                            autoFailoverJob = null
                        }
                    }
                    if (escalateToFailover) {
                        submitAutoFailoverSuspicion(trigger)
                    }
                }
                autoFailoverJob = recoveryJob
                recoveryJob.start()
            }
        }
    }

    private fun createSameNodeRecoveryCoordinator(
        layer: SameNodeFailureLayer,
        trigger: String
    ): SameNodeRecoveryCoordinator {
        return SameNodeRecoveryCoordinator(object : SameNodeRecoveryCoordinator.Actions {
            override fun hasPhysicalNetwork(): Boolean = hasValidatedPhysicalNetwork()

            override fun currentNodeTag(): String? = resolveCurrentProxyOutboundTag()

            override suspend fun closeConnections(): Boolean {
                healthSignalAggregator.clearDnsFailures()
                return commandManager.closeConnections()
            }

            override suspend fun resetNetwork(): Boolean {
                healthSignalAggregator.clearDnsFailures()
                return BoxWrapperManager.resetNetwork()
            }

            override suspend fun reloadCurrentConfig(): Boolean {
                healthSignalAggregator.clearDnsFailures()
                return reloadCurrentConfigForSameNodeRecovery()
            }

            override fun restartCurrentConfig(): Boolean {
                return restartCurrentConfigForSameNodeRecovery()
            }

            override suspend fun verify(
                nodeTag: String,
                layer: SameNodeFailureLayer
            ): SameNodeRecoveryVerification {
                return verifySameNodeRecovery(nodeTag, layer)
            }

            override fun record(stage: SameNodeRecoveryStage, verification: SameNodeRecoveryVerification?) {
                recordSameNodeRecoveryStage(stage, layer, trigger, verification)
            }
        })
    }

    private suspend fun verifySameNodeRecovery(
        nodeTag: String,
        layer: SameNodeFailureLayer
    ): SameNodeRecoveryVerification {
        delay(SingBoxService.SAME_NODE_RECOVERY_SETTLE_MS)
        val selectedTag = resolveCurrentProxyOutboundTag()
        val selectorMatches = !selectedTag.isNullOrBlank() &&
            UrlTestTagMatcher.normalizeTag(selectedTag) == UrlTestTagMatcher.normalizeTag(nodeTag)
        val probeHost = resolveSameNodeProbeHost()
        val probes = layeredNetworkHealthSampler.sample(
            physicalProbe = {
                TimedProbeResult(succeeded = hasValidatedPhysicalNetwork())
            },
            dnsProbe = {
                probePhysicalDns(
                    network = getCurrentPhysicalNetwork(),
                    host = probeHost,
                    timeoutMs = SingBoxService.SAME_NODE_DNS_PROBE_TIMEOUT_MS
                )
            },
            proxyProbe = {
                val latency = probeAutoFailoverTargetLatency(nodeTag)
                TimedProbeResult(succeeded = latency != null, latencyMs = latency)
            }
        )
        val physicalNetworkHealthy = probes.physical.hasMajoritySuccess
        val proxyHealthy = physicalNetworkHealthy && selectorMatches && probes.proxy.hasMajoritySuccess
        val dnsFailures = healthSignalAggregator.recentRemoteDnsFailureCount(
            nowMs = SystemClock.elapsedRealtime(),
            windowMs = SingBoxService.SAME_NODE_RECOVERY_DNS_OBSERVE_MS
        )
        return SameNodeRecoveryVerification(
            physicalNetworkHealthy = physicalNetworkHealthy,
            selectorMatches = selectorMatches,
            dnsHealthy = proxyHealthy && dnsFailures == 0,
            proxyHealthy = proxyHealthy,
            probeAttempts = probes.proxy.attempts,
            probeFailures = probes.proxy.failures,
            physicalProbe = probes.physical,
            dnsProbe = probes.dns,
            proxyProbe = probes.proxy,
            remoteDnsFailures = dnsFailures
        ).also { verification ->
            if (layer == SameNodeFailureLayer.DNS && dnsFailures > 0) {
                Log.w(SingBoxService.TAG, "[SameNodeRecovery] DNS still failing count=$dnsFailures")
            }
        }
    }

    private suspend fun resolveSameNodeProbeHost(): String? {
        return runCatching {
            val settings = SettingsRepository.getInstance(applicationContext).settings.first()
            AppSettings.latencyTestUri(settings.latencyTestUrl).host.takeIf(String::isNotBlank)
        }.onFailure { error ->
            Log.w(SingBoxService.TAG, "[SameNodeRecovery] failed to resolve probe host", error)
        }.getOrNull()
    }

    private suspend fun reloadCurrentConfigForSameNodeRecovery(): Boolean {
        if (!SingBoxService.isRunning || isStopping) return false
        val configFile = resolveCurrentRuntimeConfigFile() ?: return false
        return runCatching {
            val configContent = withContext(Dispatchers.IO) { configFile.readText(Charsets.UTF_8) }
            MeteredNodeConfigGuard.requireRuntimeConfigAuthorized(
                configContent = configContent,
                selectedNodeId = VpnStateStore.getSelectedNodeId()
            )
            val settingsRepository = SettingsRepository.getInstance(applicationContext)
            settingsRepository.reloadFromStorage()
            val settings = settingsRepository.settings.first()
            coreManager.setCurrentSettings(settings)
            val startToken = coreManager.captureStartToken() ?: return false
            val runtimeConfig = prepareRuntimeConfigForLocalNetwork(configContent, settings)
            val reloaded = coreManager.hotReloadConfig(runtimeConfig, startToken).getOrThrow()
            if (reloaded) {
                commandManager.getCommandServer()?.let(BoxWrapperManager::init)
            }
            reloaded
        }.onFailure { error ->
            Log.w(SingBoxService.TAG, "[SameNodeRecovery] hot reload failed", error)
        }.getOrDefault(false)
    }

    private fun restartCurrentConfigForSameNodeRecovery(): Boolean {
        val configFile = resolveCurrentRuntimeConfigFile() ?: return false
        return performFullRestart(
            configPath = configFile.absolutePath,
            recoveryIntentLease = setNonResourceRecoveryIntent(false)
        )
    }

    private fun resolveCurrentRuntimeConfigFile(): File? {
        return SingBoxService.lastConfigPath
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?: File(filesDir, "running_config.json").takeIf(File::isFile)
    }

    private fun hasValidatedPhysicalNetwork(): Boolean {
        val network = getCurrentPhysicalNetwork() ?: return false
        val capabilities = connectivityManager?.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun recordSameNodeRecoveryStage(
        stage: SameNodeRecoveryStage,
        layer: SameNodeFailureLayer,
        trigger: String,
        verification: SameNodeRecoveryVerification?
    ) {
        val probeLossPercent = verification?.let { result ->
            if (result.probeAttempts <= 0) 0 else result.probeFailures * 100 / result.probeAttempts
        }
        LogRepository.getInstance().addAlwaysLog(
            buildString {
                append("INFO recovery same_node stage=$stage ")
                append("phase=${if (verification == null) "action" else "verify"} ")
                append("layer=$layer trigger=$trigger ")
                append("network=${physicalNetworkSummary()} ")
                append("selected=${resolveCurrentProxyOutboundTag() ?: "(none)"} ")
                append("connections=${recentConnectionIds.size} ")
                append("dns=${verification?.dnsHealthy ?: "unknown"} ")
                append("proxy=${verification?.proxyHealthy ?: "unknown"} ")
                append("selector=${verification?.selectorMatches ?: "unknown"} ")
                append("probe_attempts=${verification?.probeAttempts ?: 0} ")
                append("probe_loss_pct=${probeLossPercent ?: -1} ")
                append(verification?.toProbeDiagnosticFields() ?: "remote_dns_failures=-1")
            }
        )
    }

    private fun physicalNetworkSummary(): String {
        val network = getCurrentPhysicalNetwork() ?: return "missing"
        val capabilities = connectivityManager?.getNetworkCapabilities(network) ?: return "unknown"
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        return "$transport:validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    protected fun submitAutoFailoverSuspicion(trigger: String) {
        if (autoFailoverJob?.isActive == true) {
            Log.d(SingBoxService.TAG, "[AutoFailover] suspicion ignored, job already running: $trigger")
            return
        }

        val now = System.currentTimeMillis()
        val context = NodeAutoFailoverPolicy.TriggerContext(
            isVpnRunning = SingBoxService.isRunning,
            isManuallyStopped = SingBoxService.isManuallyStopped,
            isAutoFailoverInFlight = autoFailoverJob?.isActive == true,
            inStartupGracePeriod = isAutoFailoverStartupGracePeriod(now),
            inNetworkChangeGracePeriod = isAutoFailoverNetworkGracePeriod(now),
            isProxyIdle = false,
            lastMeaningfulTrafficAtMs = SingBoxService.resolveAutoFailoverTrafficSignalAtMs(
                trigger = trigger,
                isAppInForeground = isAppInForeground,
                lastMeaningfulTrafficAtMs = lastMeaningfulTrafficAtMs,
                nowAtMs = now
            ),
            nowAtMs = now,
            lastAutoFailoverAtMs = VpnStateStore.getLastAutoFailoverAtMs(),
            budgetWindowStartAtMs = VpnStateStore.getAutoFailoverWindowStartAtMs(),
            budgetCount = VpnStateStore.getAutoFailoverCountInWindow(),
            isAutoSelectionEnabled = ConfigRepository.getInstance(this@SingBoxService)
                .isProfileAutoSelectionEnabled(VpnStateStore.getSelectedProfileId()),
            hasResourceFailure = BackgroundResourceGuard.isRecovering(),
            hasPhysicalNetwork = getCurrentPhysicalNetwork() != null
        )

        if (!NodeAutoFailoverPolicy.shouldStartProbe(context, trigger = trigger)) {
            Log.d(SingBoxService.TAG, "[AutoFailover] suspicion ignored by policy: $trigger")
            return
        }

        autoFailoverJob = autoFailoverScope.launch {
            runAutoFailoverProbeSequence(trigger)
        }
    }

    protected fun isAutoFailoverStartupGracePeriod(nowAtMs: Long): Boolean {
        val startedAtMs = autoFailoverServiceStartedAtMs
        if (startedAtMs <= 0L || nowAtMs < startedAtMs) {
            return false
        }
        return nowAtMs - startedAtMs < SingBoxService.AUTO_FAILOVER_STARTUP_GRACE_MS
    }

    protected fun isAutoFailoverNetworkGracePeriod(nowAtMs: Long): Boolean {
        val eventAtMs = lastAutoFailoverNetworkEventAtMs
        if (eventAtMs <= 0L || nowAtMs < eventAtMs) {
            return false
        }
        return nowAtMs - eventAtMs < SingBoxService.AUTO_FAILOVER_NETWORK_GRACE_MS
    }

    protected suspend fun runAutoFailoverProbeSequence(trigger: String) {
        try {
            val completed = if (SingBoxService.isHealthFastPathTrigger(trigger)) {
                withTimeoutOrNull(SingBoxService.HEALTH_FAST_FAILOVER_TOTAL_TIMEOUT_MS) {
                    runAutoFailoverProbeSequenceBody(trigger)
                    true
                } == true
            } else {
                runAutoFailoverProbeSequenceBody(trigger)
                true
            }
            if (!completed) {
                LogRepository.getInstance().addLog(
                    "WARN: Health failover probe timed out trigger=$trigger " +
                        "budget=${SingBoxService.HEALTH_FAST_FAILOVER_TOTAL_TIMEOUT_MS}ms"
                )
                Log.w(SingBoxService.TAG, "[AutoFailover] health fast path timed out: $trigger")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(SingBoxService.TAG, "[AutoFailover] probe sequence failed: $trigger", e)
        } finally {
            autoFailoverJob = null
        }
    }

    private suspend fun runAutoFailoverProbeSequenceBody(trigger: String) {
        val currentTag = resolveCurrentProxyOutboundTag()
        if (currentTag.isNullOrBlank()) {
            LogRepository.getInstance().addLog(
                "WARN: Health failover probe skipped reason=no_proxy_selection trigger=$trigger"
            )
            return
        }

        LogRepository.getInstance().addLog(
            "INFO: Health failover probe started current=$currentTag trigger=$trigger"
        )

        val firstEvaluation = runAutoFailoverProbeRound(currentTag, trigger)
        when {
            firstEvaluation.outcome == NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_HEALTHY -> {
                LogRepository.getInstance().addLog(
                    "INFO: Health failover probe keep current=$currentTag reason=offline_healthy " +
                        "delay=${firstEvaluation.currentDelayMs ?: -1} trigger=$trigger"
                )
            }

            firstEvaluation.outcome !=
                NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE -> {
                LogRepository.getInstance().addLog(
                    "WARN: Health failover probe no switch current=$currentTag " +
                        "outcome=${firstEvaluation.outcome} " +
                        "delay=${firstEvaluation.currentDelayMs ?: -1} trigger=$trigger"
                )
            }

            // 运行态已死（远程 DNS 超时等）：有候选就立刻切，不再做第二轮离线确认
            SingBoxService.isHealthFastPathTrigger(trigger) -> {
                val targetTag = firstEvaluation.alternativeTag.orEmpty()
                if (targetTag.isBlank()) {
                    LogRepository.getInstance().addLog(
                        "WARN: Health failover probe no switch current=$currentTag " +
                            "outcome=no_target trigger=$trigger"
                    )
                    return
                }
                LogRepository.getInstance().addLog(
                    "INFO: Health failover fast switch current=$currentTag " +
                        "to=$targetTag altDelay=${firstEvaluation.alternativeDelayMs ?: -1} trigger=$trigger"
                )
                performAutoFailoverSwitch(currentTag, targetTag, trigger)
            }

            else -> {
                handleSecondAutoFailoverProbe(
                    currentTag = currentTag,
                    firstEvaluation = firstEvaluation,
                    trigger = trigger
                )
            }
        }
    }

    protected suspend fun handleSecondAutoFailoverProbe(
        currentTag: String,
        firstEvaluation: NodeAutoFailoverPolicy.ProbeEvaluation,
        trigger: String
    ) {
        delay(SingBoxService.resolveAutoFailoverRetryDelayMs(trigger))
        val secondEvaluation = runAutoFailoverProbeRound(currentTag, trigger)
        when {
            secondEvaluation.outcome !=
                NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE -> {
                LogRepository.getInstance().addLog(
                    "INFO: Health failover second probe no switch current=$currentTag " +
                        "outcome=${secondEvaluation.outcome} trigger=$trigger"
                )
            }

            secondEvaluation.alternativeTag.isNullOrBlank() && firstEvaluation.alternativeTag.isNullOrBlank() -> {
                LogRepository.getInstance().addLog(
                    "WARN: Health failover second probe no target current=$currentTag trigger=$trigger"
                )
            }

            else -> {
                val targetTag = secondEvaluation.alternativeTag
                    ?: firstEvaluation.alternativeTag.orEmpty()
                performAutoFailoverSwitch(currentTag, targetTag, trigger)
            }
        }
    }

    protected suspend fun runAutoFailoverProbeRound(
        currentTag: String,
        trigger: String
    ): NodeAutoFailoverPolicy.ProbeEvaluation {
        val quarantined = loadActiveAutoFailoverQuarantine(System.currentTimeMillis())
        val quarantinedTags = quarantined.map { it.tag }.toSet()
        var results = testGroupCandidatesLatency("PROXY", currentTag, trigger)
        var sampleSource = "live_probe"

        // 当前轮无结果时仅允许使用一分钟内的实时探测缓存
        if (results.isEmpty() && SingBoxService.isHealthFastPathTrigger(trigger)) {
            results = resolveAutoFailoverFallbackDelays(currentTag, quarantinedTags)
            sampleSource = "recent_live_cache"
        }

        // dns/active 失败已证明运行态挂了，离线测速不得把当前节点判回健康
        val evaluation = NodeAutoFailoverPolicy.evaluateProbe(
            currentTag = currentTag,
            urlTestResults = results,
            quarantinedTags = quarantinedTags,
            treatCurrentAsFailed = SingBoxService.isHealthFastPathTrigger(trigger)
        )
        LogRepository.getInstance().addLog(
            "INFO: Health failover probe result current=$currentTag outcome=${evaluation.outcome} " +
                "currentDelay=${evaluation.currentDelayMs ?: -1} " +
                "alt=${evaluation.alternativeTag ?: "(none)"} " +
                "altDelay=${evaluation.alternativeDelayMs ?: -1} " +
                "samples=${results.size} source=$sampleSource trigger=$trigger"
        )
        return evaluation
    }

    private fun resolveAutoFailoverFallbackDelays(
        currentTag: String,
        quarantinedTags: Set<String>
    ): Map<String, Int> {
        val config = loadLastRunningConfig() ?: return emptyMap()
        val outbounds = config.outbounds.orEmpty()
        val byTag = outbounds.associateBy { it.tag }
        val groupTags = resolveAutoFailoverGroupCandidates("PROXY", byTag)
            .map { it.tag }
            .filter { tag ->
                tag.isNotBlank() &&
                    UrlTestTagMatcher.normalizeTag(tag) != UrlTestTagMatcher.normalizeTag(currentTag) &&
                    quarantinedTags.none { q ->
                        UrlTestTagMatcher.normalizeTag(q) == UrlTestTagMatcher.normalizeTag(tag)
                    }
            }
        if (groupTags.isEmpty()) return emptyMap()

        val cached = autoFailoverCandidateCache.resolve(
            currentTag = currentTag,
            nowMs = System.currentTimeMillis(),
            quarantinedTags = quarantinedTags
        )
        return cached
            ?.takeIf { candidate ->
                groupTags.any {
                    UrlTestTagMatcher.normalizeTag(it) == UrlTestTagMatcher.normalizeTag(candidate)
                }
            }
            ?.let { mapOf(it to 1) }
            .orEmpty()
    }

    protected suspend fun testGroupCandidatesLatency(groupTag: String): Map<String, Int> {
        return testGroupCandidatesLatency(groupTag, currentTag = null, trigger = "")
    }

    protected suspend fun testGroupCandidatesLatency(
        groupTag: String,
        currentTag: String?,
        trigger: String
    ): Map<String, Int> = coroutineScope {
        val config = loadLastRunningConfig() ?: return@coroutineScope emptyMap()
        val outbounds = config.outbounds.orEmpty()
        val byTag = outbounds.associateBy { it.tag }
        var groupCandidates = resolveAutoFailoverGroupCandidates(groupTag, byTag)
        if (groupCandidates.isEmpty()) return@coroutineScope emptyMap()

        val quarantined = loadActiveAutoFailoverQuarantine(System.currentTimeMillis()).map { it.tag }.toSet()
        groupCandidates = limitAutoFailoverCandidatesForTrigger(
            groupCandidates = groupCandidates,
            currentTag = currentTag,
            trigger = trigger,
            quarantinedTags = quarantined
        )
        if (groupCandidates.isEmpty()) return@coroutineScope emptyMap()

        val resultMap = measureAutoFailoverCandidateLatencies(groupCandidates, outbounds, trigger)
        updateAutoFailoverCandidateCache(currentTag, resultMap, quarantined)
        resultMap
    }

    private fun resolveAutoFailoverGroupCandidates(
        groupTag: String,
        byTag: Map<String, Outbound>
    ): List<Outbound> {
        val requestedGroup = byTag[groupTag] ?: return emptyList()
        val autoGroup = requestedGroup.takeIf { it.type.equals("urltest", ignoreCase = true) }
            ?: requestedGroup.outbounds.orEmpty()
                .asSequence()
                .mapNotNull(byTag::get)
                .firstOrNull { it.type.equals("urltest", ignoreCase = true) }
            ?: return emptyList()
        return autoGroup.outbounds.orEmpty()
            .mapNotNull(byTag::get)
            .filter { candidate -> candidate.type !in SingBoxService.LATENCY_SKIPPED_OUTBOUND_TYPES }
    }

    private fun limitAutoFailoverCandidatesForTrigger(
        groupCandidates: List<Outbound>,
        currentTag: String?,
        trigger: String,
        quarantinedTags: Set<String>
    ): List<Outbound> {
        if (!SingBoxService.isHealthFastPathTrigger(trigger)) {
            return groupCandidates
        }
        val cachedBackup = currentTag?.let {
            autoFailoverCandidateCache.resolve(
                currentTag = it,
                nowMs = System.currentTimeMillis(),
                quarantinedTags = quarantinedTags
            )
        }
        val selectedTags = SingBoxService.selectAutoFailoverProbeCandidates(
            currentTag = currentTag.orEmpty(),
            cachedBackupTag = cachedBackup,
            candidateTags = groupCandidates.map { it.tag },
            trigger = trigger,
            quarantinedTags = quarantinedTags
        ).toSet()
        return groupCandidates.filter { it.tag in selectedTags }
    }

    @Suppress("CognitiveComplexMethod")
    private suspend fun measureAutoFailoverCandidateLatencies(
        groupCandidates: List<Outbound>,
        outbounds: List<Outbound>,
        trigger: String
    ): Map<String, Int> = coroutineScope {
        if (SingBoxService.isHealthFastPathTrigger(trigger)) {
            return@coroutineScope measureFastAutoFailoverCandidateLatencies(groupCandidates, outbounds, trigger)
        }
        val settings = SettingsRepository.getInstance(this@SingBoxService).settings.first()
        val concurrency = SingBoxService.resolveAutoFailoverCandidateConcurrency(
            trigger = trigger,
            userConcurrency = settings.latencyTestConcurrency,
            candidateCount = groupCandidates.size
        )
        val timeoutMs = SingBoxService.resolveAutoFailoverCandidateTimeoutMs(
            trigger = trigger,
            userTimeoutMs = settings.latencyTestTimeout
        )
        val semaphore = Semaphore(concurrency)
        val core = SingBoxCore.getInstance(this@SingBoxService)
        val results = ConcurrentHashMap<String, Int>()
        groupCandidates.map { outbound ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val latency = runCatching {
                        core.testOutboundLatency(
                            outbound = outbound,
                            allOutbounds = outbounds,
                            timeoutOverrideMs = timeoutMs,
                            trafficKind = if (trigger.isBlank()) {
                                LatencyProbeTrafficKind.BACKGROUND_PROBE
                            } else {
                                LatencyProbeTrafficKind.HEALTH_CHECK
                            }
                        )
                    }.getOrDefault(-1L)
                    if (latency > 0L && latency <= Int.MAX_VALUE) {
                        results[outbound.tag] = latency.toInt()
                    }
                }
            }
        }.awaitAll()
        results.toMap()
    }

    private suspend fun measureFastAutoFailoverCandidateLatencies(
        groupCandidates: List<Outbound>,
        outbounds: List<Outbound>,
        trigger: String
    ): Map<String, Int> {
        val settings = SettingsRepository.getInstance(this@SingBoxService).settings.first()
        val concurrency = SingBoxService.resolveAutoFailoverCandidateConcurrency(
            trigger = trigger,
            userConcurrency = settings.latencyTestConcurrency,
            candidateCount = groupCandidates.size
        )
        val timeoutMs = SingBoxService.resolveAutoFailoverCandidateTimeoutMs(
            trigger = trigger,
            userTimeoutMs = settings.latencyTestTimeout
        )
        val results = ConcurrentHashMap<String, Int>()
        SingBoxCore.getInstance(this@SingBoxService).testOutboundsLatency(
            outbounds = groupCandidates,
            allOutbounds = outbounds,
            timeoutOverrideMs = timeoutMs,
            concurrencyOverride = concurrency,
            portReadyTimeoutOverrideMs = SingBoxService.resolveAutoFailoverPortReadyTimeoutMs(trigger),
            trafficKind = if (trigger.isBlank()) {
                LatencyProbeTrafficKind.BACKGROUND_PROBE
            } else {
                LatencyProbeTrafficKind.HEALTH_CHECK
            }
        ) { tag, latency ->
            if (latency > 0L && latency <= Int.MAX_VALUE) {
                results[tag] = latency.toInt()
            }
        }
        return results.toMap()
    }

    private fun updateAutoFailoverCandidateCache(
        currentTag: String?,
        resultMap: Map<String, Int>,
        quarantinedTags: Set<String>
    ) {
        if (!currentTag.isNullOrBlank() && resultMap.isNotEmpty()) {
            autoFailoverCandidateCache.update(
                currentTag = currentTag,
                delays = resultMap,
                nowMs = System.currentTimeMillis(),
                quarantinedTags = quarantinedTags
            )
        }
    }

    protected fun loadLastRunningConfig(): SingBoxConfig? {
        val configPath = SingBoxService.lastConfigPath ?: File(filesDir, "running_config.json").absolutePath
        return runCatching {
            val configContent = File(configPath).readText()
            gson.fromJson(configContent, SingBoxConfig::class.java)
        }.onFailure { e ->
            Log.w(SingBoxService.TAG, "[AutoFailover] failed to load running config for latency test: ${e.message}")
        }.getOrNull()
    }

    private suspend fun probeAutoFailoverTargetLatency(targetTag: String): Long? {
        val config = loadLastRunningConfig() ?: return null
        val outbounds = config.outbounds.orEmpty()
        val target = outbounds.firstOrNull {
            UrlTestTagMatcher.normalizeTag(it.tag) == UrlTestTagMatcher.normalizeTag(targetTag)
        } ?: return null
        return runCatching {
            SingBoxCore.getInstance(this@SingBoxService).testOutboundLatency(
                outbound = target,
                allOutbounds = outbounds,
                dnsConfig = config.dns,
                timeoutOverrideMs = SingBoxService.HEALTH_FAST_FAILOVER_CANDIDATE_TIMEOUT_MS,
                trafficKind = LatencyProbeTrafficKind.HEALTH_CHECK
            ).takeIf { it > 0L }
        }.onFailure { error ->
            Log.w(SingBoxService.TAG, "[AutoFailover] target HTTPS probe failed: $targetTag", error)
        }.getOrNull()
    }

    private suspend fun verifyAutoFailoverTargetConnectivity(targetTag: String): Boolean {
        return probeAutoFailoverTargetLatency(targetTag) != null
    }

    private fun handleAutoGroupSelectionChanged(groupTag: String, selectedTag: String) {
        val autoTag = activeAutoGroupTag ?: return
        if (!autoFailoverOverrideActive || groupTag != autoTag) return
        if (!autoGroupRestoreInFlight.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                val profileId = VpnStateStore.getSelectedProfileId()
                val repository = ConfigRepository.getInstance(this@SingBoxService)
                if (!repository.isProfileAutoSelectionEnabled(profileId)) {
                    autoFailoverOverrideActive = false
                    activeAutoGroupTag = null
                    return@launch
                }
                if (!verifyAutoFailoverTargetConnectivity(selectedTag)) return@launch
                if (hotSwitchNode(autoTag)) {
                    autoFailoverOverrideActive = false
                    LogRepository.getInstance().addLog(
                        "INFO: Auto failover returned control to automatic group=$autoTag node=$selectedTag"
                    )
                }
            } finally {
                autoGroupRestoreInFlight.set(false)
            }
        }
    }

    private fun resolveActiveAutoGroupTag(): String? {
        val outbounds = loadLastRunningConfig()?.outbounds.orEmpty()
        val byTag = outbounds.associateBy { it.tag }
        return byTag["PROXY"]?.outbounds.orEmpty()
            .asSequence()
            .mapNotNull(byTag::get)
            .firstOrNull { it.type.equals("urltest", ignoreCase = true) }
            ?.tag
    }

    @Suppress("LongMethod")
    protected suspend fun performAutoFailoverSwitch(
        currentTag: String,
        targetTag: String,
        trigger: String
    ) {
        val now = System.currentTimeMillis()
        val startedAtMs = SystemClock.elapsedRealtime()
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

        LogRepository.getInstance().addLog(
            "INFO: Auto failover screened from=$currentTag to=$targetTag trigger=$trigger"
        )

        val success = hotSwitchNode(targetTag)
        if (!success) {
            PerfTracer.recordDuration(
                PerfTracer.Phases.AUTO_FAILOVER,
                SystemClock.elapsedRealtime() - startedAtMs,
                "hot_switch_failed"
            )
            LogRepository.getInstance().addLog(
                "WARN: Auto failover escalate restart reason=hot_switch_failed target=$targetTag"
            )
            restartVpnForAutoFailoverRecovery(targetTag)
            return
        }

        // L2/L3：收敛连接 + 重置网络栈，缩小与冷启动恢复能力的差距
        val closed = commandManager.closeConnections()
        LogRepository.getInstance().addLog(
            "INFO: Health failover converged connections, trigger=$trigger, closed=$closed"
        )
        val reset = BoxWrapperManager.resetNetwork()
        LogRepository.getInstance().addLog(
            "INFO: Auto failover escalate resetNetwork result=$reset trigger=$trigger"
        )

        // live 终验：只看选中正确 + 观察窗远程 DNS，不再依赖离线延迟
        healthSignalAggregator.clearDnsFailures()
        delay(SingBoxService.AUTO_FAILOVER_LIVE_OBSERVE_MS)
        val selectedTag = resolveCurrentProxyOutboundTag()
        val targetProbeSucceeded = verifyAutoFailoverTargetConnectivity(targetTag)
        val recentDnsFailures = healthSignalAggregator.recentRemoteDnsFailureCount(
            nowMs = SystemClock.elapsedRealtime(),
            windowMs = SingBoxService.AUTO_FAILOVER_LIVE_OBSERVE_MS
        )
        val failReason = evaluateAutoFailoverLiveCheck(
            targetTag = targetTag,
            selectedTag = selectedTag,
            targetProbeSucceeded = targetProbeSucceeded,
            recentRemoteDnsFailures = recentDnsFailures
        )
        if (failReason != null) {
            autoFailoverOverrideActive = false
            activeAutoGroupTag = null
            quarantineAutoFailoverNode(targetTag)
            val rolledBack = hotSwitchNode(currentTag)
            commandManager.closeConnections()
            BoxWrapperManager.resetNetwork()
            val failureLog = "WARN: Auto failover liveCheck FAIL node=$targetTag reason=$failReason " +
                "targetProbe=$targetProbeSucceeded dnsFails=$recentDnsFailures " +
                "selected=${selectedTag ?: "(none)"} rollback=${if (rolledBack) "ok" else "failed"}"
            LogRepository.getInstance().addLog(failureLog)
            if (!rolledBack) {
                LogRepository.getInstance().addLog(
                    "WARN: Auto failover escalate restart reason=rollback_failed from=$currentTag"
                )
                restartVpnForAutoFailoverRecovery(currentTag)
            }
            PerfTracer.recordDuration(
                PerfTracer.Phases.AUTO_FAILOVER,
                SystemClock.elapsedRealtime() - startedAtMs,
                if (rolledBack) "live_check_failed" else "rollback_failed"
            )
            return
        }

        val configRepository = ConfigRepository.getInstance(this@SingBoxService)
        val displayName = configRepository.getNodeByName(targetTag)?.name ?: targetTag
        // 自动切换只更新运行态标签，不改用户手选偏好
        VpnStateStore.setActiveLabel(displayName)
        realTimeNodeName = displayName
        requestNotificationUpdate(force = true)
        requestRemoteStateUpdate(force = true)
        activeAutoGroupTag = resolveActiveAutoGroupTag()
        autoFailoverOverrideActive = activeAutoGroupTag != null
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        PerfTracer.recordDuration(
            PerfTracer.Phases.AUTO_FAILOVER,
            elapsedMs,
            "success"
        )
        LogRepository.getInstance().addLog(
            "INFO: Auto failover committed $currentTag -> $displayName trigger=$trigger " +
                "elapsed=${elapsedMs}ms dnsFails=$recentDnsFailures"
        )
        Log.i(SingBoxService.TAG, "[AutoFailover] switched from $currentTag to $displayName, trigger=$trigger")
    }

    private fun quarantineAutoFailoverNode(tag: String) {
        val now = System.currentTimeMillis()
        val current = loadActiveAutoFailoverQuarantine(now).toMutableList()
        current.add(NodeAutoFailoverPolicy.createQuarantineRecord(tag, now))
        val cleaned = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(current, now)
        VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(cleaned))
    }

    private fun restartVpnForAutoFailoverRecovery(preferredTag: String?) {
        val configPath = pendingHotSwitchFallbackConfigPath ?: File(filesDir, "running_config.json").absolutePath
        val restartIntent = Intent(this@SingBoxService, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_START
            putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
            preferredTag?.takeIf { it.isNotBlank() }?.let {
                putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, it)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }

    protected fun loadActiveAutoFailoverQuarantine(nowAtMs: Long): List<NodeAutoFailoverPolicy.QuarantinedNode> {
        val records = NodeAutoFailoverPolicy.decodeQuarantine(VpnStateStore.getAutoFailoverQuarantinedTags())
        val cleaned = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(records, nowAtMs)
        if (cleaned.size != records.size) {
            VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(cleaned))
        }
        return cleaned
    }

    protected fun resolveCurrentProxyOutboundTag(): String? {
        return commandManager.getResolvedSelectedOutbound("PROXY")
            ?.takeIf { it.isNotBlank() }
            ?: SelectorManager.getSelectedOutbound()
                ?.takeIf { tag ->
                    tag.isNotBlank() && ConfigRepository.getInstance(applicationContext)
                        .resolveNodeNameFromOutboundTag(tag) != null
                }
    }

    // 屏幕/前台状态从 ScreenStateManager 读取

    protected fun getCurrentPhysicalNetwork(): Network? {
        return StateCache.getNetwork {
            connectivityManager?.let { DefaultNetworkListener.selectBestPhysicalNetwork(it) }
        }
    }

    protected fun markPhysicalNetworkChanged() {
        StateCache.invalidateNetworkCache()
    }

    protected fun findBestPhysicalNetwork(): Network? {
        return getCurrentPhysicalNetwork()
    }

    private fun recordServiceLifecycle(
        event: String,
        reason: String,
        recovery: Boolean = false,
        action: String? = null
    ) {
        LogRepository.getInstance().addAlwaysLog(
            buildServiceLifecycleDiagnostic(
                service = "vpn",
                event = event,
                reason = reason,
                pid = Process.myPid(),
                details = buildString {
                    append("process_started_at_epoch_ms=${readProcessStartedAtEpochMs() ?: -1L} ")
                    append("app_version_code=${VersionInfo.getAppVersionCode(this@SingBoxService)} ")
                    append("mode=${VpnStateStore.getMode().name} ")
                    append("manually_stopped=${VpnStateStore.isManuallyStopped()} recovery=$recovery ")
                    append("stop_initiator=${lastStopInitiator.wireValue}")
                    action?.takeIf(String::isNotBlank)?.let { append(" action=$it") }
                }
            )
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.e(SingBoxService.TAG, "SingBoxService onCreate: pid=${android.os.Process.myPid()} SingBoxService.instance=${System.identityHashCode(this)}")
        SingBoxService.instance = this

        // Restore manually stopped state from persistent storage
        SingBoxService.isManuallyStopped = VpnStateStore.isManuallyStopped()
        Log.i(SingBoxService.TAG, "Restored SingBoxService.isManuallyStopped state: $SingBoxService.isManuallyStopped")
        recordServiceLifecycle(event = "create", reason = "service_on_create")

        notificationManager.createNotificationChannel()
        // 初始化 ConnectivityManager
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        connectivityManager?.let { manager ->
            // 保证 Start 消息先于 onDestroy 的 Stop 入队，避免监听器残留。
            DefaultNetworkListener.start(manager, defaultNetworkListenerKey) {
                markPhysicalNetworkChanged()
            }
        }

        // ===== 初始化新架构 Managers =====
        initManagers()

        serviceScope.launch {
            SingBoxService.lastErrorFlow.collect {
                requestRemoteStateUpdate(force = false)
            }
        }

        // 监听活动节点变化，更新通知
        serviceScope.launch {
            ConfigRepository.getInstance(this@SingBoxService).activeNodeId.collect { _ ->
                if (SingBoxService.isRunning) {
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
                    if (SingBoxService.isRunning) {
                        requestNotificationUpdate(force = true)
                    }
                }
        }

        // ⭐ P0修复3: 注册Activity生命周期回调，检测应用返回前台
        screenStateManager.registerActivityLifecycleCallbacks(application)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                screenStateManager.onAppBackground()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        preserveRuntimeStateOnDestroy = false
        val recoveryFlag = intent?.getBooleanExtra(SingBoxService.EXTRA_RECOVERY, false) == true
        Log.i(SingBoxService.TAG, "onStartCommand action=${intent?.action} recovery=$recoveryFlag")
        recordServiceLifecycle(
            event = "start_request",
            reason = if (intent?.action == null) "sticky_restart" else "intent",
            recovery = recoveryFlag,
            action = intent?.action?.substringAfterLast('.')
        )
        if (recoveryFlag) {
            runCatching {
                LogRepository.getInstance().addAlwaysLog(
                    "INFO [Recovery] SingBoxService START " +
                        "running=${SingBoxService.isRunning} starting=${SingBoxService.isStarting}"
                )
            }
        }
        if (intent?.action == null) {
            handleStickyRestartIntent()
            return START_STICKY
        }
        when (intent.action) {
            SingBoxService.ACTION_START -> {
                // 恢复 START 幂等：核心已在跑/正在起时只刷新状态，禁止 clean restart
                val isRecoveryStart = intent.getBooleanExtra(SingBoxService.EXTRA_RECOVERY, false)
                if (isRecoveryStart &&
                    RecoveryPolicy.shouldIgnoreRecoveryStart(SingBoxService.isRunning, SingBoxService.isStarting)
                ) {
                    Log.i(SingBoxService.TAG, "Recovery START ignored: core already active")
                    runCatching {
                        LogRepository.getInstance().addAlwaysLog(
                            "INFO [Recovery] START ignored: already active " +
                                "running=${SingBoxService.isRunning} starting=${SingBoxService.isStarting}"
                        )
                    }
                    VpnStateStore.clearRecoveryClaim()
                    requestNotificationUpdate(force = true)
                    requestRemoteStateUpdate(force = true)
                    return START_STICKY
                }
                if (isRecoveryStart && !isValidRecoveryStart(VpnStateStore.CoreMode.VPN)) {
                    rejectStaleRecoveryStart("VPN recovery intent no longer matches persisted intent")
                    return START_STICKY
                }
                // recovery 不覆盖 manuallyStopped：意图由 claim 前判定，失败路径也不应洗成"未手动停"
                if (!isRecoveryStart) {
                    SingBoxService.isManuallyStopped = false
                    VpnStateStore.setManuallyStopped(false)
                    lastStopInitiator = VpnStopInitiator.UNKNOWN
                }
                val recoveryLease = setNonResourceRecoveryIntent(isRecoveryStart)
                SingBoxService.setLastError(null)
                VpnStateStore.setLastError(null)
                VpnTileService.persistVpnPending("starting")

                // 性能优化: 预创建 TUN Builder (非阻塞)
                coreManager.preallocateTunBuilder()

                val configPath = intent.getStringExtra(SingBoxService.EXTRA_CONFIG_PATH)
                val pendingNode = intent.getStringExtra(SingBoxService.EXTRA_PENDING_NODE_NAME)
                pendingNodeName = pendingNode?.takeIf { it.isNotBlank() }
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
                                    putExtra(SingBoxService.EXTRA_RECOVERY, isRecoveryStart)
                                    pendingNodeName?.let {
                                        putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, it)
                                    }
                                }
                                synchronized(this@SingBoxService) {
                                    if (ServiceStateHolder.isRecoveryIntentCurrent(recoveryLease)) {
                                        startService(newIntent)
                                    }
                                }
                            } else {
                                Log.e(SingBoxService.TAG, "Failed to generate config file")
                                withContext(Dispatchers.Main) {
                                    if (clearStartCommandFailureState(recoveryLease) {
                                            SingBoxService.setLastError("Failed to generate config file")
                                        }
                                    ) {
                                        stopSelf()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(SingBoxService.TAG, "Error generating config in Service", e)
                            withContext(Dispatchers.Main) {
                                if (clearStartCommandFailureState(recoveryLease) {
                                        SingBoxService.setLastError("Error generating config: ${e.message}")
                                    }
                                ) {
                                    stopSelf()
                                }
                            }
                        }
                    }
                    return START_STICKY
                }

                // 同配置重复 START 幂等：已在跑且配置未变（且未要求清缓存）时只刷新状态，禁止 clean restart
                if (SingBoxService.isRunning &&
                    !cleanCache &&
                    configPath == SingBoxService.lastConfigPath
                ) {
                    Log.i(SingBoxService.TAG, "Duplicate START ignored: same config already running")
                    if (!completeRecoveryIntentOnSuccess(recoveryLease)) return START_STICKY
                    runCatching {
                        LogRepository.getInstance().addAlwaysLog(
                            "INFO [Recovery] Duplicate START ignored: same config already running " +
                                "recovery=$isRecoveryStart path=$configPath"
                        )
                    }
                    VpnTileService.persistVpnPending("")
                    requestNotificationUpdate(force = true)
                    requestRemoteStateUpdate(force = true)
                    return START_STICKY
                }

                initializeStartupNodeLabel(configPath)
                updateServiceState(ServiceState.STARTING)
                synchronized(this) {
                    // FIX: Ensure pendingCleanCache is set from intent even for cold start
                    if (cleanCache) pendingCleanCache = true

                    if (SingBoxService.isStarting) {
                        pendingStartConfigPath = configPath
                        pendingStartRecoveryIntentLease = recoveryLease
                        stopSelfRequested = false
                        hardStopRecoveryIntentLease = null
                        SingBoxService.lastConfigPath = configPath
                        // Return STICKY to allow system to restart VPN if killed due to memory pressure
                        return START_STICKY
                    }
                    if (isStopping) {
                        pendingStartConfigPath = configPath
                        pendingStartRecoveryIntentLease = recoveryLease
                        stopSelfRequested = false
                        hardStopRecoveryIntentLease = null
                        SingBoxService.lastConfigPath = configPath
                        // Return STICKY to allow system to restart VPN if killed due to memory pressure
                        return START_STICKY
                    }
                    // If already running, do a clean restart to avoid half-broken tunnel state
                    if (SingBoxService.isRunning) {
                        pendingStartConfigPath = configPath
                        pendingStartRecoveryIntentLease = recoveryLease
                        stopSelfRequested = false
                        hardStopRecoveryIntentLease = null
                        SingBoxService.lastConfigPath = configPath
                    }
                }
                if (SingBoxService.isRunning) {
                    val activeLabel = pendingNodeName ?: runCatching {
                        val repo = ConfigRepository.getInstance(applicationContext)
                        val profileId = repo.activeProfileId.value
                        if (repo.isProfileAutoSelectionEnabled(profileId)) {
                            null
                        } else {
                            val nodeId = repo.activeNodeId.value
                            repo.nodes.value.find { it.id == nodeId }?.name
                        }
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
                val stopInitiator = VpnStopInitiator.fromWireValue(
                    intent.getStringExtra(SingBoxService.EXTRA_STOP_INITIATOR)
                )
                lastStopInitiator = stopInitiator
                Log.i(
                    SingBoxService.TAG,
                    "Received SingBoxService.ACTION_STOP initiator=${stopInitiator.wireValue} -> stopping VPN"
                )
                SingBoxService.isManuallyStopped = stopInitiator.isManualStop
                VpnStateStore.setManuallyStopped(stopInitiator.isManualStop)
                recordServiceLifecycle(
                    event = "stop_request",
                    reason = if (stopInitiator.isManualStop) "manual_stop" else "automatic_stop",
                    action = SingBoxService.ACTION_STOP.substringAfterLast('.')
                )
                val recoveryLease = setNonResourceRecoveryIntent(false)
                VpnTileService.persistVpnPending("stopping")
                updateServiceState(ServiceState.STOPPING)
                notificationManager.setSuppressUpdates(true)
                notificationManager.cancelNotification()
                synchronized(this) {
                    pendingStartConfigPath = null
                    pendingStartRecoveryIntentLease = null
                }
                stopVpn(stopService = true, recoveryIntentLease = recoveryLease)
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
                        targetNodeName = targetNodeName
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
                val isPerAppRuleRestart = intent.getBooleanExtra(
                    SingBoxService.EXTRA_PER_APP_RULE_RESTART,
                    false
                )
                val manuallyStopped = isPerAppRuleRestart && VpnStateStore.isManuallyStopped()
                val mode = if (isPerAppRuleRestart) {
                    VpnStateStore.getMode()
                } else {
                    VpnStateStore.CoreMode.VPN
                }
                val shouldRejectPerAppRuleRestart = isPerAppRuleRestart && (
                    !SingBoxService.isRunning ||
                        isStopping ||
                        manuallyStopped ||
                        mode != VpnStateStore.CoreMode.VPN
                    )
                if (shouldRejectPerAppRuleRestart) {
                    Log.w(
                        SingBoxService.TAG,
                        "Per-app rule FULL_RESTART rejected: " +
                            "running=${SingBoxService.isRunning}, starting=${SingBoxService.isStarting}, " +
                            "stopping=$isStopping, manuallyStopped=$manuallyStopped, mode=$mode"
                    )
                    val shouldStopIdleService = !SingBoxService.isRunning &&
                        !SingBoxService.isStarting &&
                        !isStopping
                    if (shouldStopIdleService) {
                        preserveRuntimeStateOnDestroy = true
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    return START_STICKY
                }
                Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_FULL_RESTART -> performing full restart (TUN rebuild)")
                val configPath = intent.getStringExtra(SingBoxService.EXTRA_CONFIG_PATH)
                if (configPath.isNullOrEmpty()) {
                    PerfTracer.recordEvent(PerfTracer.Phases.FULL_RESTART, "missing_config")
                    Log.e(SingBoxService.TAG, "SingBoxService.ACTION_FULL_RESTART: config path is empty")
                } else {
                    PerfTracer.recordEvent(PerfTracer.Phases.FULL_RESTART, "requested")
                    performFullRestart(configPath)
                }
            }
            SingBoxService.ACTION_RESET_CONNECTIONS -> {
                Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_RESET_CONNECTIONS -> user requested connection reset")
                if (SingBoxService.isRunning) {
                    serviceScope.launch {
                        val closed = commandManager.closeConnections()
                        val reset = BoxWrapperManager.resetNetwork()
                        runCatching {
                            LogRepository.getInstance().addLog(
                                "INFO: User triggered connection reset via notification " +
                                    "closed=$closed resetNetwork=$reset"
                            )
                        }
                    }
                }
            }
        }
        // Use START_STICKY to allow system auto-restart if killed due to memory pressure
        // This prevents "VPN mysteriously stops" issue on Android 14+
        // System will restart service with null intent, we handle it gracefully above
        return START_STICKY
    }

    private fun isValidRecoveryStart(expectedMode: VpnStateStore.CoreMode): Boolean {
        return !VpnStateStore.isManuallyStopped() && VpnStateStore.getMode() == expectedMode
    }

    private fun rejectStaleRecoveryStart(reason: String) {
        Log.w(SingBoxService.TAG, reason)
        runCatching {
            LogRepository.getInstance().addAlwaysLog("INFO [Recovery] START rejected: $reason")
        }
        VpnTileService.persistVpnPending("")
        VpnStateStore.clearRecoveryClaim()
    }

    protected fun handleStickyRestartIntent() {
        if (SingBoxService.isRunning || SingBoxService.isStarting) {
            Log.i(SingBoxService.TAG, "Sticky restart ignored: service is already running or starting")
            return
        }

        val runningConfigFile = File(filesDir, "running_config.json")
        val runningConfigUsable = SingBoxService.isRunningConfigUsable(runningConfigFile)
        val mode = VpnStateStore.getMode()
        val manuallyStopped = VpnStateStore.isManuallyStopped()
        if (!RecoveryPolicy.shouldRecoverFromStickyRestart(manuallyStopped, mode, runningConfigUsable)) {
            Log.i(
                SingBoxService.TAG,
                "Sticky restart skipped: manuallyStopped=$manuallyStopped, mode=$mode, " +
                    "runningConfigUsable=$runningConfigUsable"
            )
            return
        }
        if (!VpnStateStore.tryClaimRecovery(RECOVERY_CLAIM_WINDOW_MS)) {
            Log.i(SingBoxService.TAG, "Sticky restart skipped: another recovery was issued recently")
            return
        }

        // 只冷恢复：running_config 原样拉起，不 CLEAN_CACHE、不重新生成配置、不覆盖 manuallyStopped
        Log.w(SingBoxService.TAG, "Sticky restart recovering VPN from ${runningConfigFile.absolutePath}")
        runCatching {
            LogRepository.getInstance().addAlwaysLog(
                "INFO [Recovery] Sticky restart recovering path=${runningConfigFile.absolutePath} " +
                    "mode=$mode manuallyStopped=$manuallyStopped"
            )
        }
        setNonResourceRecoveryIntent(true)
        VpnTileService.persistVpnPending("starting")
        initializeStartupNodeLabel(runningConfigFile.absolutePath, explicitTag = null)
        updateServiceState(ServiceState.STARTING)
        startVpn(runningConfigFile.absolutePath)
    }

    private fun recordStartFailureIfCurrent(recoveryLease: RecoveryIntentLease, error: String) {
        synchronized(this) {
            if (ServiceStateHolder.isRecoveryIntentCurrent(recoveryLease)) {
                startupCallbacks.onFailed(error)
            }
        }
    }

    protected fun clearStartCommandFailureState(
        recoveryLease: RecoveryIntentLease,
        beforeCleanup: (() -> Unit)? = null
    ): Boolean = synchronized(this) {
        val consumedIntent = ServiceStateHolder.consumeRecoveryIntentOnFailure(recoveryLease)
        if (consumedIntent == null) {
            Log.w(SingBoxService.TAG, "Startup failure ignored for superseded recovery lease")
            return@synchronized false
        }
        beforeCleanup?.invoke()
        val preserveMode = RecoveryPolicy.shouldPreserveModeOnStartFailure(consumedIntent)
        synchronized(this) {
            if (pendingRecoveryIntentLease === recoveryLease) pendingRecoveryIntentLease = null
        }
        synchronized(this) {
            SingBoxService.isRunning = false
            SingBoxService.isStarting = false
            isStopping = false
            pendingStartConfigPath = null
            pendingStartRecoveryIntentLease = null
            pendingCleanCache = false
            stopSelfRequested = false
            hardStopRecoveryIntentLease = null
        }
        NetworkClient.onVpnStateChanged(false)
        VpnTileService.persistVpnState(false)
        if (preserveMode) {
            // 恢复失败：只清 runtime，保留 mode，留给 keepalive/冷启动再试
            VpnStateStore.clearRuntimeState(preserveLastError = true)
        } else {
            VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
        }
        VpnTileService.persistVpnPending("")
        // 启动失败立即释放恢复互斥，让后续触发源按当时意图重新判定，而不是干等窗口过期
        VpnStateStore.clearRecoveryClaim()
        updateServiceState(ServiceState.STOPPED)
        updateTileState()
        true
    }

    /**
     * 执行预清理操作
     */

    protected fun performPrepareRestart() {
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
                setUnderlyingNetworks(null)

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

    protected fun prepareRuntimeConfigForLocalNetwork(configContent: String, settings: AppSettings): String {
        if (!LocalNetworkPermission.canApplySettings(applicationContext, settings)) {
            throw IllegalStateException(LocalNetworkPermission.MISSING_PERMISSION_ERROR)
        }
        if (!LocalNetworkPermission.shouldRestrictLanListen(applicationContext)) return configContent

        return runCatching {
            val config = gson.fromJson(configContent, SingBoxConfig::class.java)
            val inbounds = config.inbounds ?: return configContent
            var changed = false
            val restrictedInbounds = inbounds.map { inbound ->
                val restrictedInbound = LocalNetworkPermission.restrictInboundListen(inbound)
                if (restrictedInbound != inbound) {
                    changed = true
                }
                restrictedInbound
            }
            if (changed) {
                Log.i(SingBoxService.TAG, "Restricted hot reload inbound listen to loopback")
                gson.toJson(config.copy(inbounds = restrictedInbounds))
            } else {
                configContent
            }
        }.getOrElse { e ->
            Log.w(SingBoxService.TAG, "Failed to restrict hot reload local network listen: ${e.message}")
            configContent
        }
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "LongMethod", "ReturnCount")
    protected fun performHotReload(configContent: String) {
        synchronized(this) {
            if (!SingBoxService.isRunning || isStopping) {
                Log.w(SingBoxService.TAG, "performHotReload: VPN not running or stopping, skip")
                return
            }
            val startToken = coreManager.captureStartToken()
            if (startToken == null) {
                Log.w(SingBoxService.TAG, "performHotReload: lifecycle token unavailable")
                return
            }
            hotReloadJob?.cancel()

            val job = serviceScope.launch {
                val startedAtMs = SystemClock.elapsedRealtime()
                try {
                    Log.i(SingBoxService.TAG, "[HotReload] Starting kernel-level hot reload...")
                    LogRepository.getInstance().addAlwaysLog("INFO [HotReload] phase=request source=service")

                    MeteredNodeConfigGuard.requireRuntimeConfigAuthorized(
                        configContent = configContent,
                        selectedNodeId = VpnStateStore.getSelectedNodeId()
                    )

                    val settingsRepository = SettingsRepository.getInstance(applicationContext)
                    settingsRepository.reloadFromStorage()
                    val settings = settingsRepository.settings.first()
                    coreManager.setCurrentSettings(settings)
                    val runtimeConfigContent = prepareRuntimeConfigForLocalNetwork(configContent, settings)

                    val result = coreManager.hotReloadConfig(runtimeConfigContent, startToken)

                    result.onSuccess { success ->
                        if (success) {
                            PerfTracer.recordDuration(
                                PerfTracer.Phases.HOT_RELOAD,
                                SystemClock.elapsedRealtime() - startedAtMs,
                                "success"
                            )
                            Log.i(SingBoxService.TAG, "[HotReload] Kernel hot reload succeeded")
                            LogRepository.getInstance().addAlwaysLog(
                                "INFO [HotReload] phase=complete outcome=success " +
                                    "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs}"
                            )

                            commandManager.getCommandServer()?.let { server ->
                                BoxWrapperManager.init(server)
                            }

                            requestNotificationUpdate(force = true)
                        } else if (!isStopping && SingBoxService.isRunning) {
                            PerfTracer.recordDuration(
                                PerfTracer.Phases.HOT_RELOAD,
                                SystemClock.elapsedRealtime() - startedAtMs,
                                "kernel_error"
                            )
                            handleHotReloadFailure("Kernel hot reload not available")
                        }
                    }.onFailure { e ->
                        if (!isStopping && SingBoxService.isRunning) {
                            PerfTracer.recordDuration(
                                PerfTracer.Phases.HOT_RELOAD,
                                SystemClock.elapsedRealtime() - startedAtMs,
                                "exception"
                            )
                            handleHotReloadFailure("Hot reload failed: ${e.message}")
                        }
                    }
                } catch (e: CancellationException) {
                    PerfTracer.recordDuration(
                        PerfTracer.Phases.HOT_RELOAD,
                        SystemClock.elapsedRealtime() - startedAtMs,
                        "cancelled"
                    )
                    Log.i(SingBoxService.TAG, "performHotReload cancelled")
                    LogRepository.getInstance().addAlwaysLog(
                        "INFO [HotReload] phase=complete outcome=cancelled " +
                            "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs}"
                    )
                    throw e
                } catch (e: Exception) {
                    PerfTracer.recordDuration(
                        PerfTracer.Phases.HOT_RELOAD,
                        SystemClock.elapsedRealtime() - startedAtMs,
                        "exception"
                    )
                    Log.e(SingBoxService.TAG, "performHotReload error", e)
                    if (!isStopping && SingBoxService.isRunning) {
                        handleHotReloadFailure("Hot reload error: ${e.message}")
                    }
                }
            }
            hotReloadJob = job
            job.invokeOnCompletion {
                synchronized(this@SingBoxService) {
                    if (hotReloadJob === job) hotReloadJob = null
                }
            }
        }
    }

    protected fun handleHotReloadFailure(errorMsg: String) {
        Log.e(SingBoxService.TAG, "[HotReload] $errorMsg, stopping VPN")
        LogRepository.getInstance().addAlwaysLog("ERROR [HotReload] phase=complete outcome=failed reason=$errorMsg")

        serviceScope.launch(Dispatchers.Main) {
            AppNotificationManager.showMessage(
                context = applicationContext,
                message = errorMsg,
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
        }

        SingBoxService.isManuallyStopped = false
        val recoveryLease = setNonResourceRecoveryIntent(false)
        stopVpn(stopService = true, recoveryIntentLease = recoveryLease)
    }

    protected fun performFullRestart(configPath: String) {
        performFullRestart(configPath, setNonResourceRecoveryIntent(false))
    }

    private fun performFullRestart(
        configPath: String,
        recoveryIntentLease: RecoveryIntentLease
    ): Boolean {
        val resourceRecoveryAttemptId = recoveryIntentLease.attemptId
        if (resourceRecoveryAttemptId == null) {
            cancelResourceGuard()
        }
        val startDirectly = synchronized(this) {
            when {
                pendingRecoveryIntentLease !== recoveryIntentLease -> null
                !ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease) -> null
                !isResourceRecoveryRestartAllowedLocked(recoveryIntentLease) -> null
                !SingBoxService.isRunning && !SingBoxService.isStarting && !isStopping -> true
                else -> {
                    pendingStartConfigPath = configPath
                    pendingStartRecoveryIntentLease = recoveryIntentLease
                    stopSelfRequested = false
                    hardStopRecoveryIntentLease = null
                    SingBoxService.lastConfigPath = configPath
                    false
                }
            }
        } ?: return false

        if (startDirectly) {
            Log.i(SingBoxService.TAG, "performFullRestart: VPN inactive, starting directly")
            startVpn(configPath, recoveryIntentLease)
        } else {
            Log.i(SingBoxService.TAG, "performFullRestart: queued restart after unified cleanup")
            stopVpn(stopService = false, recoveryIntentLease = recoveryIntentLease)
        }
        return true
    }

    private fun isResourceRecoveryRestartAllowedLocked(recoveryIntentLease: RecoveryIntentLease): Boolean = when {
        recoveryIntentLease.attemptId == null -> true
        recoveryIntentLease.ownerId !== resourceGuardOwnerId -> false
        VpnStateStore.isManuallyStopped() -> false
        VpnStateStore.getMode() != VpnStateStore.CoreMode.VPN -> false
        else -> true
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
        if (!SingBoxService.isRunning || isStopping) {
            Log.w(SingBoxService.TAG, "performHotReloadSync: VPN not running or stopping")
            return false
        }
        val startToken = coreManager.captureStartToken() ?: return false
        val startedAtMs = SystemClock.elapsedRealtime()

        return try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                Log.i(SingBoxService.TAG, "[HotReload-Sync] Starting kernel-level hot reload...")
                LogRepository.getInstance().addAlwaysLog("INFO [HotReload] phase=request source=ipc")

                MeteredNodeConfigGuard.requireRuntimeConfigAuthorized(
                    configContent = configContent,
                    selectedNodeId = VpnStateStore.getSelectedNodeId()
                )

                val settingsRepository = SettingsRepository.getInstance(applicationContext)
                settingsRepository.reloadFromStorage()
                val settings = settingsRepository.settings.first()
                coreManager.setCurrentSettings(settings)
                val runtimeConfigContent = prepareRuntimeConfigForLocalNetwork(configContent, settings)

                val result = coreManager.hotReloadConfig(runtimeConfigContent, startToken)

                val success = result.getOrNull() == true
                if (success) {
                    Log.i(SingBoxService.TAG, "[HotReload-Sync] Kernel hot reload succeeded")
                    LogRepository.getInstance().addAlwaysLog(
                        "INFO [HotReload] phase=complete outcome=success source=ipc " +
                            "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs}"
                    )

                    commandManager.getCommandServer()?.let { server ->
                        BoxWrapperManager.init(server)
                    }

                    requestNotificationUpdate(force = true)
                    requestRemoteStateUpdate(force = true)
                } else {
                    val reason = result.exceptionOrNull()?.message ?: "kernel_returned_false"
                    LogRepository.getInstance().addAlwaysLog(
                        "ERROR [HotReload] phase=complete outcome=failed source=ipc " +
                            "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs} reason=$reason"
                    )
                }
                success
            }
        } catch (e: CancellationException) {
            LogRepository.getInstance().addAlwaysLog(
                "INFO [HotReload] phase=complete outcome=cancelled source=ipc " +
                    "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs}"
            )
            throw e
        } catch (e: Exception) {
            Log.e(SingBoxService.TAG, "performHotReloadSync error", e)
            LogRepository.getInstance().addAlwaysLog(
                "ERROR [HotReload] phase=complete outcome=failed source=ipc " +
                    "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs} reason=${e.message.orEmpty()}"
            )
            false
        }
    }

    /**
     *
     * 原方法 ~430 行，现在简化为 ~90 行
     */

    @Suppress("LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod", "ReturnCount")
    protected fun startVpn(
        configPath: String,
        requestedRecoveryIntentLease: RecoveryIntentLease? = null
    ) {
        // 状态检查（保留在 Service 中，因为涉及多线程同步）
        val (startToken, recoveryLease) = synchronized(this) {
            val capturedRecoveryLease = requestedRecoveryIntentLease
                ?: pendingRecoveryIntentLease
                ?: ServiceStateHolder.setRecoveryIntentOnFailure(false).also { pendingRecoveryIntentLease = it }
            if (requestedRecoveryIntentLease != null &&
                !ServiceStateHolder.isRecoveryIntentCurrent(requestedRecoveryIntentLease)
            ) {
                Log.w(SingBoxService.TAG, "VPN start ignored for superseded recovery lease")
                return
            }
            if (SingBoxService.isManuallyStopped) {
                Log.w(SingBoxService.TAG, "VPN was manually stopped, reject stale start request")
                return
            }
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
                pendingStartRecoveryIntentLease = capturedRecoveryLease
                stopSelfRequested = false
                hardStopRecoveryIntentLease = null
                SingBoxService.lastConfigPath = configPath
                return
            }
            val token = coreManager.captureStartToken()
            if (token == null) {
                Log.w(SingBoxService.TAG, "VPN lifecycle is stopping, reject stale start request")
                return
            }
            SingBoxService.isStarting = true
            token to capturedRecoveryLease
        }

        SingBoxService.lastConfigPath = configPath
        initializeStartupNodeLabel(configPath)

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
            val recoveryAttemptId = recoveryLease.attemptId
            if (recoveryAttemptId == null) {
                if (clearStartCommandFailureState(recoveryLease) {
                        SingBoxService.setLastError("Failed to start foreground service")
                    }
                ) {
                    stopSelf()
                }
            } else {
                serviceScope.launch {
                    BackgroundResourceGuard.failSuccessorAndAwait(resourceGuardOwnerId, recoveryAttemptId)
                    withContext(Dispatchers.Main) {
                        if (clearStartCommandFailureState(recoveryLease) {
                                SingBoxService.setLastError("Failed to start foreground service")
                            }
                        ) {
                            stopSelf()
                        }
                    }
                }
            }
        } else {
            continueStartVpnAfterForeground(configPath, startToken, recoveryLease)
        }
    }

    protected fun continueStartVpnAfterForeground(
        configPath: String,
        startToken: Long,
        recoveryLease: RecoveryIntentLease
    ) {
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
                startToken = startToken,
                recoveryIntentLease = recoveryLease,
                coreManager = coreManager,
                callbacks = startupCallbacks
            )

            when (result) {
                is com.kunk.singbox.service.manager.StartupManager.StartResult.Success -> {
                    // 成功状态已在 exact lease 门内一次性发布。
                }
                is com.kunk.singbox.service.manager.StartupManager.StartResult.Failed -> {
                    recordStartFailureIfCurrent(recoveryLease, result.error)
                    val recoveryAttemptId = recoveryLease.attemptId
                    BackgroundResourceGuard.failSuccessorAndAwait(resourceGuardOwnerId, recoveryAttemptId)
                    stopVpn(
                        stopService = true,
                        recoveryIntentLease = recoveryLease
                    )
                }
                is com.kunk.singbox.service.manager.StartupManager.StartResult.NeedPermission -> {
                    BackgroundResourceGuard.failSuccessorAndAwait(
                        resourceGuardOwnerId,
                        recoveryLease.attemptId
                    )
                    if (clearStartCommandFailureState(recoveryLease) {
                            startupManager.handlePermissionRequired(result.prepareIntent)
                        }
                    ) {
                        stopSelf()
                    }
                }
                is com.kunk.singbox.service.manager.StartupManager.StartResult.Cancelled -> {
                    // 已在 callbacks.onCancelled() 中处理
                }
                is com.kunk.singbox.service.manager.StartupManager.StartResult.Superseded -> {
                    stopVpn(false, recoveryIntentLease = recoveryLease)
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

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    protected fun stopVpn(
        stopService: Boolean,
        broadcastStoppingState: Boolean = true,
        recoveryIntentLease: RecoveryIntentLease? = null
    ) {
        val cleanupRecoveryLease = recoveryIntentLease ?: synchronized(this) {
            pendingRecoveryIntentLease
                ?: ServiceStateHolder.setRecoveryIntentOnFailure(false).also { pendingRecoveryIntentLease = it }
        }
        val cleanupRecoveryAttemptId = cleanupRecoveryLease.attemptId
        val cleanupIntentCurrent = ServiceStateHolder.isRecoveryIntentCurrent(cleanupRecoveryLease)
        val ownedStopService = stopService && cleanupIntentCurrent
        if (stopService && !ownedStopService) {
            Log.w(SingBoxService.TAG, "Hard stop downgraded for superseded recovery lease")
        }
        if (cleanupIntentCurrent) {
            if (cleanupRecoveryAttemptId == null) {
                cancelResourceGuard()
            } else {
                detachResourceGuard(cleanupRecoveryAttemptId)
            }
        }
        // 状态同步检查（保留在 Service 中，因为涉及多线程同步）
        val startCleanup = synchronized(this) {
            coreManager.beginStop()
            stopSelfRequested = stopSelfRequested || ownedStopService
            if (ownedStopService) {
                hardStopRecoveryIntentLease = cleanupRecoveryLease
                pendingStartConfigPath = null
                pendingStartRecoveryIntentLease = null
            }
            if (isStopping) {
                false
            } else {
                isStopping = true
                true
            }
        }
        if (!startCleanup) return

        autoFailoverCandidateCache.clear()
        autoFailoverOverrideActive = false
        activeAutoGroupTag = null
        autoGroupRestoreInFlight.set(false)

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
        autoFailoverServiceStartedAtMs = 0L

        Log.i(SingBoxService.TAG, "stopVpn(stopService=$stopService) SingBoxService.isManuallyStopped=$SingBoxService.isManuallyStopped")

        // 获取代理端口用于等待释放
        val proxyPort = coreManager.currentSettings?.proxyPort ?: 2080

        // 委托给 ShutdownManager
        // 不需要严格等待端口释放，启动时会强杀进程确保端口可用
        cleanupJob = shutdownManager.stopVpn(
            options = ShutdownManager.ShutdownOptions(
                stopService = ownedStopService,
                proxyPort = proxyPort,
                recoveryIntentLease = cleanupRecoveryLease,
                resourceRecoveryAttemptId = cleanupRecoveryAttemptId
            ),
            coreManager = coreManager,
            commandManager = commandManager,
            notificationManager = notificationManager,
            callbacks = shutdownCallbacks
        )
    }

    protected fun updateTileState() {
        try {
            TileService.requestListeningState(this, ComponentName(this, VpnTileService::class.java))

            // 显式触发 TileService 刷新，避免仅依赖 listening/bind 回调导致状态滞后
            val refreshIntent = Intent(this, VpnTileService::class.java).apply {
                action = VpnTileService.ACTION_REFRESH_TILE
            }
            startService(refreshIntent)
        } catch (e: Exception) {
            Log.e(SingBoxService.TAG, "Failed to update tile state", e)
        }
    }

    protected fun buildNotificationState(): VpnNotificationManager.NotificationState {
        val configRepository = ConfigRepository.getInstance(this)
        val activeNodeId = configRepository.activeNodeId.value
        val nodeName = resolveNotificationNodeLabel(
            selectedNodeName = configRepository.nodes.value.find { it.id == activeNodeId }?.name,
            selectedNodeStoreLabel = VpnStateStore.getSelectedNodeLabel(),
            runtimeNodeName = realTimeNodeName ?: VpnStateStore.getActiveLabel()
        )

        return VpnNotificationManager.NotificationState(
            isRunning = SingBoxService.isRunning,
            isStopping = isStopping,
            activeNodeName = nodeName,
            showSpeed = showNotificationSpeed,
            uploadSpeed = currentUploadSpeed,
            downloadSpeed = currentDownloadSpeed
        )
    }

    protected fun requestNotificationUpdate(force: Boolean) {
        notificationManager.requestNotificationUpdate(buildNotificationState(), this, force)
    }

    protected fun createNotification(): Notification {
        return notificationManager.createNotification(buildNotificationState())
    }

    override fun onDestroy() {
        Log.i(SingBoxService.TAG, "onDestroy called -> stopVpn(stopService=false) pid=${android.os.Process.myPid()}")
        cancelResourceGuard()

        // 清理省电管理器引用
        SingBoxIpcHub.setPowerManager(null)
        screenStateManager.setPowerManager(null)
        backgroundPowerManager.cleanup()

        screenStateManager.unregisterActivityLifecycleCallbacks(application)
        DefaultNetworkListener.stop(defaultNetworkListenerKey)

        val shouldStop = runCatching {
            synchronized(this@SingBoxService) {
                SingBoxService.isRunning || isStopping || coreManager.isServiceRunning() || vpnInterface != null
            }
        }.getOrDefault(false)

        val manualStop = lastStopInitiator.isManualStop || SingBoxService.isManuallyStopped
        val requestedStop = lastStopInitiator != VpnStopInitiator.UNKNOWN || manualStop
        val unexpectedDeath = !requestedStop && shouldStop
        recordServiceLifecycle(
            event = "destroy",
            reason = when {
                manualStop -> "manual_stop"
                requestedStop -> "automatic_stop"
                unexpectedDeath -> "unexpected_destroy"
                shouldStop -> "active_cleanup"
                else -> "inactive_destroy"
            }
        )
        if (unexpectedDeath) {
            // 意外死亡（划卡/系统杀）：恢复意图（mode、manuallyStopped）在启动成功时已持久化，这里不动。
            // active/pending 落成"当前不在跑"，不做完整 stop→restart 收尾；
            // 恢复交给 sticky / keepalive / 冷启动单路（共用 VpnStateStore 互斥）。
            VpnTileService.persistVpnState(false)
            VpnTileService.persistVpnPending("")
            Log.i(SingBoxService.TAG, "onDestroy: unexpected death, recovery intent preserved")
            runCatching {
                LogRepository.getInstance().addAlwaysLog(
                    "INFO [Recovery] onDestroy unexpectedDeath mode=${VpnStateStore.getMode()} " +
                        "manuallyStopped=${VpnStateStore.isManuallyStopped()} preserveIntent=true"
                )
            }
        } else if (shouldStop) {
            // Note: stopVpn launches a cleanup job on cleanupScope.
            // If we halt() immediately, that job will die.
            // For app updates, the system kills us anyway, so cleanup might be best-effort.
            stopVpn(stopService = false)
        } else {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            if (!preserveRuntimeStateOnDestroy) {
                VpnTileService.persistVpnState(false)
                VpnTileService.persistVpnPending("")
                updateServiceState(ServiceState.STOPPED)
                updateTileState()
            }
        }

        serviceSupervisorJob.cancel()
        autoFailoverJob?.cancel()
        autoFailoverJob = null
        sameNodeRecoveryInFlight.set(false)
        autoFailoverSupervisorJob.cancel()
        // cleanupSupervisorJob.cancel() // Allow cleanup to finish naturally

        if (SingBoxService.instance == this) {
            SingBoxService.instance = null
        }
        super.onDestroy()

        Log.i(SingBoxService.TAG, "SingBoxService cleanup complete, pid=${android.os.Process.myPid()}.")
    }

    override fun onRevoke() {
        Log.i(SingBoxService.TAG, "onRevoke called -> stopVpn(stopService=true)")
        lastStopInitiator = VpnStopInitiator.SYSTEM_REVOKE
        SingBoxService.isManuallyStopped = false
        VpnStateStore.setManuallyStopped(false)
        recordServiceLifecycle(
            event = "stop_request",
            reason = "automatic_stop",
            action = "SYSTEM_REVOKE"
        )
        val recoveryLease = setNonResourceRecoveryIntent(false)
        VpnTileService.persistVpnPending("stopping")
        SingBoxService.setLastError("VPN revoked by system (another VPN may have started)")
        updateServiceState(ServiceState.STOPPING)
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
        stopVpn(
            stopService = true,
            broadcastStoppingState = false,
            recoveryIntentLease = recoveryLease
        )
        super.onRevoke()
    }

    /**
     * 确保网络回调就绪，最多等待指定超时时间
     * 如果超时仍未就绪，尝试主动采样当前活跃网络
     */

    protected suspend fun ensureNetworkCallbackReadyWithTimeout(timeoutMs: Long) {
        networkHelper.ensureNetworkCallbackReady(
            isCallbackReady = { networkCallbackReady },
            lastKnownNetwork = { lastKnownNetwork },
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

    protected fun scheduleAsyncRuleSetUpdate() {
        serviceScope.launch(Dispatchers.IO) {
            // 2026-fix: 增加延迟到 15 秒，确保 CommandClient 回调已稳定
            delay(15000)

            if (!SingBoxService.isRunning || isStopping) {
                Log.d(SingBoxService.TAG, "scheduleAsyncRuleSetUpdate: VPN not running, skip")
                return@launch
            }

            // 2026-fix: 检查 CommandClient 是否已收到回调，避免在初始化阶段并发访问
            val groupsCount = commandManager.getGroupsCount()
            if (groupsCount == 0) {
                Log.d(SingBoxService.TAG, "scheduleAsyncRuleSetUpdate: CommandClient not ready yet, skip")
                return@launch
            }

            try {
                val ruleSetRepo = RuleSetRepository.getInstance(this@SingBoxService)
                val now = System.currentTimeMillis()
                if (now - lastRuleSetCheckMs >= ruleSetCheckIntervalMs) {
                    lastRuleSetCheckMs = now
                    Log.i(SingBoxService.TAG, "Starting async rule set update...")
                    val allReady = ruleSetRepo.ensureRuleSetsReady(
                        forceUpdate = false,
                        allowNetwork = true
                    ) { progress ->
                        Log.d(SingBoxService.TAG, "Async rule set update: $progress")
                    }
                    Log.i(SingBoxService.TAG, "Async rule set update completed, allReady=$allReady")
                }
            } catch (e: Exception) {
                Log.w(SingBoxService.TAG, "Async rule set update failed", e)
            }
        }
    }

    protected suspend fun waitForUsablePhysicalNetwork(timeoutMs: Long): Network? {
        return networkHelper.waitForUsablePhysicalNetwork(timeoutMs)
    }

    companion object {
        private const val RECOVERY_CLAIM_WINDOW_MS = 12_000L

        internal fun formatRestartFailure(error: Exception): String {
            val detail = error.message?.takeIf { it.isNotBlank() }
            return buildString {
                append("Failed to restart VPN: ")
                append(error.javaClass.simpleName)
                if (detail != null) {
                    append(": ")
                    append(detail)
                }
            }
        }

        internal val TAG = "SingBoxService"

        val ACTION_START = ServiceStateHolder.ACTION_START

        val ACTION_STOP = ServiceStateHolder.ACTION_STOP

        val ACTION_SWITCH_NODE = ServiceStateHolder.ACTION_SWITCH_NODE

        val ACTION_SERVICE = ServiceStateHolder.ACTION_SERVICE

        val ACTION_UPDATE_SETTING = ServiceStateHolder.ACTION_UPDATE_SETTING

        val ACTION_RESET_CONNECTIONS = ServiceStateHolder.ACTION_RESET_CONNECTIONS

        val ACTION_PREPARE_RESTART = ServiceStateHolder.ACTION_PREPARE_RESTART

        val ACTION_HOT_RELOAD = ServiceStateHolder.ACTION_HOT_RELOAD

        val ACTION_FULL_RESTART = ServiceStateHolder.ACTION_FULL_RESTART

        val EXTRA_CONFIG_PATH = ServiceStateHolder.EXTRA_CONFIG_PATH

        val EXTRA_PENDING_NODE_NAME = "pending_node_name"

        val EXTRA_CONFIG_CONTENT = ServiceStateHolder.EXTRA_CONFIG_CONTENT

        val EXTRA_CLEAN_CACHE = ServiceStateHolder.EXTRA_CLEAN_CACHE

        val EXTRA_PER_APP_RULE_RESTART = ServiceStateHolder.EXTRA_PER_APP_RULE_RESTART

        val EXTRA_SETTING_KEY = ServiceStateHolder.EXTRA_SETTING_KEY

        val EXTRA_SETTING_VALUE_BOOL = ServiceStateHolder.EXTRA_SETTING_VALUE_BOOL

        val EXTRA_STOP_INITIATOR = ServiceStateHolder.EXTRA_STOP_INITIATOR

        val EXTRA_PREPARE_RESTART_REASON = ServiceStateHolder.EXTRA_PREPARE_RESTART_REASON

        val EXTRA_RECOVERY = ServiceStateHolder.EXTRA_RECOVERY

        internal val AUTO_FAILOVER_MEANINGFUL_TRAFFIC_BPS = 1024L

        internal val AUTO_FAILOVER_STARTUP_GRACE_MS = 30_000L

        internal val AUTO_FAILOVER_NETWORK_GRACE_MS = 4_000L

        internal val AUTO_FAILOVER_PROBE_RETRY_DELAY_MS = 2_500L

        internal const val DNS_FAILOVER_PROBE_RETRY_DELAY_MS = 1_000L

        internal const val HEALTH_FAST_FAILOVER_TOTAL_TIMEOUT_MS = 9_000L

        internal const val HEALTH_FAST_FAILOVER_CANDIDATE_TIMEOUT_MS = 1_200

        internal const val HEALTH_FAST_FAILOVER_PORT_READY_TIMEOUT_MS = 600L

        internal const val HEALTH_FAST_FAILOVER_CANDIDATE_CONCURRENCY = 3

        /** 切换后 live 观察窗：等内核日志暴露远程 DNS 超时。 */
        internal const val AUTO_FAILOVER_LIVE_OBSERVE_MS = 2_000L

        internal const val SAME_NODE_RECOVERY_SETTLE_MS = 350L

        internal const val SAME_NODE_RECOVERY_DNS_OBSERVE_MS = 5_000L

        internal const val SAME_NODE_DNS_PROBE_TIMEOUT_MS = 1_000L

        internal const val SINGLE_NODE_ROUTE_FAILURE_NOTIFICATION_DEBOUNCE_MS = 60_000L

        internal val LATENCY_SKIPPED_OUTBOUND_TYPES = setOf(
            "direct",
            "block",
            "dns",
            "selector",
            "urltest",
            "url-test"
        )

        var instance: SingBoxService?
            get() = ServiceStateHolder.instance
            internal set(value) { ServiceStateHolder.instance = value }

        var isRunning: Boolean
            get() = ServiceStateHolder.isRunning
            internal set(value) { ServiceStateHolder.isRunning = value }

        val isRunningFlow get() = ServiceStateHolder.isRunningFlow

        var isStarting: Boolean
            get() = ServiceStateHolder.isStarting
            internal set(value) { ServiceStateHolder.isStarting = value }

        val isStartingFlow get() = ServiceStateHolder.isStartingFlow

        val lastErrorFlow get() = ServiceStateHolder.lastErrorFlow

        var isManuallyStopped: Boolean
            get() = ServiceStateHolder.isManuallyStopped
            internal set(value) { ServiceStateHolder.isManuallyStopped = value }

        internal var lastConfigPath: String?
            get() = ServiceStateHolder.lastConfigPath
            set(value) { ServiceStateHolder.lastConfigPath = value }

        internal fun setLastError(message: String?) = ServiceStateHolder.setLastError(message)

        fun getConnectionOwnerStatsSnapshot() = ServiceStateHolder.getConnectionOwnerStatsSnapshot()

        fun resetConnectionOwnerStats() = ServiceStateHolder.resetConnectionOwnerStats()

        internal fun shouldContinueCoreStartAfterForegroundResult(foregroundStarted: Boolean): Boolean {
            return foregroundStarted
        }

        internal fun isRunningConfigUsable(file: File): Boolean {
            return file.exists() && file.isFile && file.canRead() && file.length() > 0L
        }

        internal fun isHealthFastPathTrigger(trigger: String): Boolean {
            return NodeAutoFailoverPolicy.isHealthFastPathTrigger(trigger)
        }

        internal fun resolveAutoFailoverRetryDelayMs(trigger: String): Long {
            return if (isHealthFastPathTrigger(trigger)) {
                DNS_FAILOVER_PROBE_RETRY_DELAY_MS
            } else {
                AUTO_FAILOVER_PROBE_RETRY_DELAY_MS
            }
        }

        internal fun resolveAutoFailoverCandidateTimeoutMs(trigger: String, userTimeoutMs: Int): Int {
            val safeUserTimeout = userTimeoutMs.coerceAtLeast(1)
            return if (isHealthFastPathTrigger(trigger)) {
                safeUserTimeout.coerceAtMost(HEALTH_FAST_FAILOVER_CANDIDATE_TIMEOUT_MS)
            } else {
                safeUserTimeout
            }
        }

        internal fun resolveAutoFailoverPortReadyTimeoutMs(trigger: String): Long {
            return if (isHealthFastPathTrigger(trigger)) {
                HEALTH_FAST_FAILOVER_PORT_READY_TIMEOUT_MS
            } else {
                3_000L
            }
        }

        internal fun resolveAutoFailoverCandidateConcurrency(
            trigger: String,
            userConcurrency: Int,
            candidateCount: Int
        ): Int {
            val safeCandidateCount = candidateCount.coerceAtLeast(1)
            val desired = if (isHealthFastPathTrigger(trigger)) {
                HEALTH_FAST_FAILOVER_CANDIDATE_CONCURRENCY
            } else {
                userConcurrency.coerceIn(1, 20)
            }
            return desired.coerceAtMost(safeCandidateCount)
        }

        internal fun resolveAutoFailoverCandidateProbeWaves(
            trigger: String,
            userConcurrency: Int,
            candidateCount: Int
        ): Int {
            if (candidateCount <= 0) {
                return 0
            }
            if (isHealthFastPathTrigger(trigger)) {
                return 1
            }
            val concurrency = resolveAutoFailoverCandidateConcurrency(
                trigger = trigger,
                userConcurrency = userConcurrency,
                candidateCount = candidateCount
            )
            return (candidateCount + concurrency - 1) / concurrency
        }

        internal fun resolveSingleNodeRouteFailureTag(
            dnsServerTag: String?,
            currentProxyTag: String?,
            config: SingBoxConfig
        ): String? {
            val currentTag = currentProxyTag?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val mainConcreteTags = resolveMainConcreteOutboundTags(currentTag, config)
            val detourTag = resolveDnsSignalDetourTag(dnsServerTag, config)
            val outbound = detourTag?.let { tag ->
                config.outbounds.orEmpty().firstOrNull { it.tag == tag }
            }
            val outboundType = outbound?.type?.trim()?.lowercase().orEmpty()
            val isCurrentProxyTag = detourTag?.let { tag ->
                mainConcreteTags.any { mainTag ->
                    UrlTestTagMatcher.normalizeTag(tag) == UrlTestTagMatcher.normalizeTag(mainTag)
                }
            } == true

            return when {
                detourTag.isNullOrBlank() -> null
                isCurrentProxyTag -> null
                outbound == null -> null
                outboundType.isBlank() || outboundType in LATENCY_SKIPPED_OUTBOUND_TYPES -> null
                else -> detourTag
            }
        }

        internal fun shouldSubmitMainAutoFailoverForDnsSignal(
            dnsServerTag: String?,
            currentProxyTag: String?,
            config: SingBoxConfig
        ): Boolean {
            val detourTag = resolveDnsSignalDetourTag(dnsServerTag, config) ?: return true
            if (detourTag.equals("PROXY", ignoreCase = true)) return true

            val outbound = config.outbounds.orEmpty().firstOrNull { it.tag == detourTag } ?: return true
            val outboundType = outbound.type.trim().lowercase()
            if (outboundType == "selector" || outboundType == "urltest" || outboundType == "url-test") {
                return false
            }
            val currentTag = currentProxyTag?.trim()?.takeIf { it.isNotBlank() } ?: return true
            val mainConcreteTags = resolveMainConcreteOutboundTags(currentTag, config)
            val normalizedDetour = UrlTestTagMatcher.normalizeTag(detourTag)
            return mainConcreteTags.any { tag ->
                UrlTestTagMatcher.normalizeTag(tag) == normalizedDetour
            }
        }

        internal fun shouldRecoverMainOutboundFailure(
            failureTag: String,
            currentProxyTag: String?,
            config: SingBoxConfig
        ): Boolean {
            val normalizedFailure = UrlTestTagMatcher.normalizeTag(failureTag.trim())
            if (normalizedFailure.isBlank() || currentProxyTag.isNullOrBlank()) return true
            return resolveMainConcreteOutboundTags(currentProxyTag, config).any { tag ->
                UrlTestTagMatcher.normalizeTag(tag) == normalizedFailure
            }
        }

        private fun resolveDnsSignalDetourTag(dnsServerTag: String?, config: SingBoxConfig): String? {
            val dnsTag = dnsServerTag?.trim().orEmpty()
            if (dnsTag.isBlank()) return null
            return config.dns
                ?.servers
                .orEmpty()
                .firstOrNull { it.tag?.trim() == dnsTag }
                ?.detour
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

        private fun resolveMainConcreteOutboundTags(currentProxyTag: String?, config: SingBoxConfig): List<String> {
            val tags = mutableListOf<String>()
            fun add(tag: String?) {
                val value = tag?.trim()?.takeIf { it.isNotBlank() } ?: return
                val normalized = UrlTestTagMatcher.normalizeTag(value)
                if (tags.none { UrlTestTagMatcher.normalizeTag(it) == normalized }) {
                    tags.add(value)
                }
            }

            add(resolveConcreteOutboundTag(currentProxyTag, config))
            add(resolveConcreteOutboundTag("PROXY", config))
            return tags
        }

        private fun resolveConcreteOutboundTag(tag: String?, config: SingBoxConfig): String? {
            var current = tag?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val outboundsByTag = config.outbounds.orEmpty().associateBy { it.tag }
            var remainingDepth = 4
            var resolved = false
            while (!resolved && !current.isNullOrBlank() && remainingDepth > 0) {
                val activeTag = current.orEmpty()
                val outbound = outboundsByTag[activeTag]
                val next = when (outbound?.type?.trim()?.lowercase()) {
                    "selector" -> outbound.default?.trim()?.takeIf { it.isNotBlank() } ?: firstOutboundRef(outbound)
                    "urltest", "url-test" -> firstOutboundRef(outbound)
                    else -> {
                        resolved = true
                        null
                    }
                }
                if (next == null ||
                    UrlTestTagMatcher.normalizeTag(next) == UrlTestTagMatcher.normalizeTag(activeTag)
                ) {
                    resolved = true
                } else {
                    current = next
                    remainingDepth--
                }
            }
            return current
        }

        private fun firstOutboundRef(outbound: Outbound): String? {
            return outbound.outbounds
                .orEmpty()
                .firstNotNullOfOrNull { it.trim().takeIf(String::isNotBlank) }
        }

        internal fun shouldNotifySingleNodeRouteFailure(
            failureTag: String,
            lastNotifyAtMs: Long,
            nowAtMs: Long,
            debounceMs: Long = SINGLE_NODE_ROUTE_FAILURE_NOTIFICATION_DEBOUNCE_MS
        ): Boolean {
            if (failureTag.isBlank()) return false
            return lastNotifyAtMs <= 0L || nowAtMs - lastNotifyAtMs >= debounceMs
        }

        internal fun buildSingleNodeRouteFailureNotificationText(displayName: String): String {
            val safeName = displayName.trim().ifBlank { "未知节点" }
            return "单节点分流节点 $safeName 连接异常"
        }

        internal fun shouldRecordMeaningfulTrafficForAutoFailover(totalSpeed: Long): Boolean {
            return totalSpeed >= AUTO_FAILOVER_MEANINGFUL_TRAFFIC_BPS
        }

        internal fun resolveAutoFailoverTrafficSignalAtMs(
            trigger: String,
            isAppInForeground: Boolean,
            lastMeaningfulTrafficAtMs: Long,
            nowAtMs: Long
        ): Long {
            return if (isHealthFastPathTrigger(trigger) && isAppInForeground) {
                nowAtMs
            } else {
                lastMeaningfulTrafficAtMs
            }
        }

        internal fun selectAutoFailoverProbeCandidates(
            currentTag: String,
            cachedBackupTag: String?,
            candidateTags: List<String>,
            trigger: String,
            quarantinedTags: Set<String> = emptySet()
        ): List<String> {
            if (!isHealthFastPathTrigger(trigger)) {
                return candidateTags.distinct()
            }

            val selected = mutableListOf<String>()
            fun addCandidate(tag: String?) {
                val value = tag?.trim().orEmpty()
                if (value.isBlank()) return
                val normalized = UrlTestTagMatcher.normalizeTag(value)
                if (quarantinedTags.any { UrlTestTagMatcher.normalizeTag(it) == normalized }) return
                if (selected.none { UrlTestTagMatcher.normalizeTag(it) == normalized }) {
                    selected.add(value)
                }
            }

            addCandidate(currentTag)
            addCandidate(cachedBackupTag)
            for (tag in candidateTags) {
                if (selected.size >= 3) break
                addCandidate(tag)
            }
            return selected.take(3)
        }
    }
}

enum class ServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING
}
