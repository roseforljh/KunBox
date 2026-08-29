@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.*
import com.kunk.singbox.service.manager.BackgroundPowerManager
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.manager.CoreManager
import com.kunk.singbox.service.manager.ForeignVpnMonitor
import com.kunk.singbox.service.manager.LayeredNetworkHealthSampler
import com.kunk.singbox.service.manager.NetworkHelper
import com.kunk.singbox.service.manager.NodeSwitchManager
import com.kunk.singbox.service.manager.PlatformInterfaceImpl
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.ScreenStateManager
import com.kunk.singbox.service.manager.SameNodeRecoveryGate
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.ShutdownManager
import com.kunk.singbox.service.manager.UrlTestTagMatcher
import com.kunk.singbox.service.manager.VpnStopInitiator
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.utils.LocaleHelper
import com.kunk.singbox.utils.NetworkClient
import com.kunk.singbox.utils.perf.ResourceGuardRegistration
import io.nekohasekai.libbox.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

internal data class UidPackageCacheEntry(
    val packageName: String,
    val cachedAtMs: Long
)

@Suppress("TooManyFunctions", "LargeClass", "ProtectedMemberInFinalClass")
class SingBoxService : VpnService() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapFromCache(newBase))
    }

    internal val gson = Gson()
    internal val defaultNetworkListenerKey = Any()

    // ===== 新架构 Managers =====
    // 核心管理器 (VPN 启动/停止)

    internal val coreManager: CoreManager by lazy {
        CoreManager(this, this, serviceScope)
    }

    // Command 管理器 (Server/Client 交互)

    internal val commandManager: CommandManager by lazy {
        CommandManager(this, serviceScope)
    }

    // Platform Interface 实现 (提取自原内联实现)

    internal val platformInterfaceImpl: PlatformInterfaceImpl by lazy {
        PlatformInterfaceImpl(
            context = this,
            serviceScope = serviceScope,
            mainHandler = mainHandler,
            callbacks = platformCallbacks
        )
    }

    // 网络辅助工具

    internal val networkHelper: NetworkHelper by lazy {
        NetworkHelper(this)
    }

    // 启动管理器

    internal val startupManager: com.kunk.singbox.service.manager.StartupManager by lazy {
        com.kunk.singbox.service.manager.StartupManager(this, this, serviceScope)
    }

    // 关闭管理器

    internal val shutdownManager: com.kunk.singbox.service.manager.ShutdownManager by lazy {
        com.kunk.singbox.service.manager.ShutdownManager(this, cleanupScope)
    }

    // 屏幕状态管理器

    internal val screenStateManager: ScreenStateManager by lazy {
        ScreenStateManager(this, serviceScope)
    }

    // 外部 VPN 监控器

    internal val foreignVpnMonitor: ForeignVpnMonitor by lazy {
        ForeignVpnMonitor(this)
    }

    internal val nodeSwitchManager: NodeSwitchManager by lazy {
        NodeSwitchManager(this, serviceScope)
    }

    internal val backgroundPowerManager: BackgroundPowerManager by lazy {
        BackgroundPowerManager(serviceScope)
    }

    @Volatile
    internal var backgroundPowerSavingThresholdMs: Long = BackgroundPowerSavingDelay.MINUTES_30.delayMs

    // PlatformInterfaceImpl 回调实现

    internal val platformCallbacks: PlatformInterfaceImpl.Callbacks = createPlatformCallbacks()

    internal val notificationManager: VpnNotificationManager by lazy {
        VpnNotificationManager(this, serviceScope)
    }
    internal val remoteStateUpdateDebounceMs: Long = 250L
    internal val lastRemoteStateUpdateAtMs = AtomicLong(0L)
    @Volatile internal var remoteStateUpdateJob: Job? = null

    internal val startupCallbacks = object : com.kunk.singbox.service.manager.StartupManager.Callbacks {
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
        override fun startForeignVpnMonitor() { foreignVpnMonitor.start(activeVpnSessionId) }
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
                    if (!prepareCommandServerStartup(startToken, recoveryIntentLease)) return@runCatching false
                    val createdServer = commandManager.createServer(platformInterfaceImpl).getOrThrow()
                    server = createdServer
                    if (!isCommandServerStartupCurrent(startToken, recoveryIntentLease)) {
                        return@runCatching false
                    }
                    commandManager.startServer(createdServer).getOrThrow()
                    adopted = adoptCommandServerIfCurrent(createdServer, startToken, recoveryIntentLease)
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
            pendingNodeName = null
            updateServiceState(ServiceState.RUNNING)
            SingBoxIpcHub.updateReadiness { readiness ->
                val updated = readiness.copy(
                    coreReady = true,
                    selectorReady = false,
                    recoveryActive = false,
                    lastReadinessReason = "selector_pending"
                )
                updated.copy(status = updated.resolveVpnStatus(canBeReady = false))
            }
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

    internal val shutdownCallbacks: ShutdownManager.Callbacks = createShutdownCallbacks()

    internal var vpnInterface: ParcelFileDescriptor? = null

    internal val serviceSupervisorJob = SupervisorJob()

    internal val serviceScope = CoroutineScope(Dispatchers.IO + serviceSupervisorJob)

    internal val cleanupSupervisorJob = SupervisorJob()

    internal val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupSupervisorJob)

    internal val autoFailoverSupervisorJob = SupervisorJob()

    internal val autoFailoverScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) + autoFailoverSupervisorJob
    )
    @Volatile internal var isStopping: Boolean = false
    @Volatile internal var stopSelfRequested: Boolean = false
    @Volatile internal var lastStopInitiator: VpnStopInitiator = VpnStopInitiator.UNKNOWN
    @Volatile internal var hardStopRecoveryIntentLease: RecoveryIntentLease? = null
    @Volatile internal var preserveRuntimeStateOnDestroy: Boolean = false
    @Volatile internal var cleanupJob: Job? = null
    @Volatile internal var autoFailoverJob: Job? = null
    @Volatile internal var pendingStartConfigPath: String? = null
    @Volatile internal var pendingStartRecoveryIntentLease: RecoveryIntentLease? = null
    @Volatile internal var pendingPerAppPolicyRevision: Long = 0L
    @Volatile internal var pendingAppRouteRequestId: String = ""
    @Volatile internal var pendingConfigDigest: String = ""
    @Volatile internal var pendingAppRoutingDigest: String = ""

    @Volatile internal var pendingHotSwitchFallbackConfigPath: String? = null
    @Volatile internal var pendingNodeName: String? = null
    @Volatile internal var pendingCleanCache: Boolean = false

    @Volatile internal var startVpnJob: Job? = null
    @Volatile internal var postStartJob: Job? = null
    @Volatile internal var hotReloadJob: Job? = null
    internal val postStartGeneration = AtomicLong(0L)
    internal val resourceGuardGeneration = AtomicLong(0L)
    internal val resourceGuardOwnerId = Any()
    @Volatile internal var resourceGuardRegistration: ResourceGuardRegistration? = null
    @Volatile internal var pendingRecoveryIntentLease: RecoveryIntentLease? = null
    @Volatile internal var realTimeNodeName: String? = null
    // @Volatile protected var nodePollingJob: Job? = null // Removed in favor of CommandClient

    internal val isConnectingTun = AtomicBoolean(false)
    internal val vpnSessionGeneration = AtomicLong(0L)
    @Volatile internal var activeVpnSessionId = 0L

    // Command 相关变量已移至 CommandManager
    // 保留这些作为兼容性别名 (委托到 commandManager)

    internal val activeConnectionNode: String? get() = commandManager.activeConnectionNode

    internal val activeConnectionLabel: String? get() = commandManager.activeConnectionLabel

    internal val recentConnectionIds: List<String> get() = commandManager.recentConnectionIds

    // 速度计算使用 sing-box CommandStatus 的真实代理流量
    @Volatile internal var showNotificationSpeed: Boolean = true

    internal var currentUploadSpeed: Long = 0L

    internal var currentDownloadSpeed: Long = 0L

    internal val healthSignalAggregator = HealthSignalAggregator()
    internal val sameNodeRecoveryGate = SameNodeRecoveryGate()
    internal val layeredNetworkHealthSampler = LayeredNetworkHealthSampler()
    internal val sameNodeRecoveryInFlight = AtomicBoolean(false)
    internal val autoFailoverCandidateCache = AutoFailoverCandidateCache()
    internal val autoGroupRestoreInFlight = AtomicBoolean(false)
    @Volatile internal var autoFailoverOverrideActive = false
    @Volatile internal var activeAutoGroupTag: String? = null
    @Volatile internal var lastMeaningfulTrafficAtMs: Long = 0L
    @Volatile internal var autoFailoverServiceStartedAtMs: Long = 0L
    @Volatile internal var lastAutoFailoverNetworkEventAtMs: Long = 0L
    internal val singleNodeRouteFailureNotificationTimes = ConcurrentHashMap<String, Long>()

    @Volatile internal var lastRuleSetCheckMs: Long = 0L

    internal val ruleSetCheckIntervalMs: Long = 6 * 60 * 60 * 1000L

    internal val uidToPackageCache = ConcurrentHashMap<Int, UidPackageCacheEntry>()

    internal val maxUidToPackageCacheSize: Int = 512

    internal val isScreenOn: Boolean get() = screenStateManager.isScreenOn

    internal val isAppInForeground: Boolean get() = screenStateManager.isAppInForeground

    // Auto reconnect

    internal var connectivityManager: ConnectivityManager? = null

    internal val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    internal var lastKnownNetwork: Network? = null

    // 网络就绪标志：确保 Libbox 启动前网络回调已完成初始采样
    @Volatile internal var networkCallbackReady: Boolean = false
    // ACTION_PREPARE_RESTART 防抖：避免短时间内重复触发导致网络反复震荡

    internal val lastPrepareRestartAtMs = AtomicLong(0L)

    internal val prepareRestartDebounceMs: Long = 1500L

    @Volatile internal var serviceState: ServiceState = ServiceState.STOPPED

    override fun onCreate() {
        super.onCreate()
        onCreateRuntime()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        onTrimMemoryRuntime(level)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        onStartCommandRuntime(intent, flags, startId)

    override fun onDestroy() {
        onDestroyRuntime()
        super.onDestroy()
    }

    override fun onRevoke() {
        onRevokeRuntime()
        super.onRevoke()
    }

    internal fun currentServiceState(): ServiceState = currentServiceStateRuntime()

    fun performHotReloadSync(configContent: String): Boolean = performHotReloadSyncRuntime(configContent)

    companion object {
        internal const val RECOVERY_CLAIM_WINDOW_MS = 12_000L

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

        const val ACTION_FORCE_STOP = ServiceStateHolder.ACTION_FORCE_STOP

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
        val EXTRA_PER_APP_POLICY_REVISION = ServiceStateHolder.EXTRA_PER_APP_POLICY_REVISION
        val EXTRA_APP_ROUTE_REQUEST_ID = ServiceStateHolder.EXTRA_APP_ROUTE_REQUEST_ID
        val EXTRA_CONFIG_DIGEST = ServiceStateHolder.EXTRA_CONFIG_DIGEST
        val EXTRA_APP_ROUTING_DIGEST = ServiceStateHolder.EXTRA_APP_ROUTING_DIGEST

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
