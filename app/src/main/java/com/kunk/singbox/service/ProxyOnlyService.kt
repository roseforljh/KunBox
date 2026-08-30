@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.buildServiceLifecycleDiagnostic
import com.kunk.singbox.service.manager.ConnectionTrafficAttributor
import com.kunk.singbox.service.manager.ConnectionIncidentHistory
import com.kunk.singbox.service.manager.ConnectionStormGuard
import com.kunk.singbox.service.manager.LayeredNetworkHealthSampler
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.RecoveryPolicy
import com.kunk.singbox.service.manager.SameNodeRecoveryGate
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.VpnStopInitiator
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.utils.LocaleHelper
import com.kunk.singbox.utils.NetworkClient
import com.kunk.singbox.utils.VersionInfo
import com.kunk.singbox.utils.perf.ResourceGuardRegistration
import com.kunk.singbox.utils.perf.readProcessStartedAtEpochMs
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.PlatformInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

@Suppress("LargeClass")
class ProxyOnlyService : Service() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapFromCache(newBase))
    }

    companion object {
        internal const val TAG = "ProxyOnlyService"
        internal const val NOTIFICATION_ID = 11
        internal const val CHANNEL_ID = "singbox_proxy_silent"
        internal const val LEGACY_CHANNEL_ID = "singbox_proxy"

        internal const val PORT_WAIT_TIMEOUT_MS = 5000L
        internal const val PORT_CHECK_INTERVAL_MS = 100L

        const val ACTION_START = "com.kunk.singbox.START"
        const val ACTION_STOP = "com.kunk.singbox.STOP"
        val ACTION_FORCE_STOP = SingBoxService.ACTION_FORCE_STOP
        const val ACTION_SWITCH_NODE = "com.kunk.singbox.SWITCH_NODE"
        const val ACTION_PREPARE_RESTART = "com.kunk.singbox.PREPARE_RESTART"
        const val ACTION_RESET_CONNECTIONS = "com.kunk.singbox.RESET_CONNECTIONS"
        const val EXTRA_CONFIG_PATH = "config_path"

        @Volatile
        var isRunning = false
            internal set

        @Volatile
        var isStarting = false
            internal set

        private val _lastErrorFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val lastErrorFlow = _lastErrorFlow.asStateFlow()

        internal fun shouldClearRuntimeStateOnDestroy(
            isRunning: Boolean,
            isStarting: Boolean,
            isStopping: Boolean,
            pending: String,
            mode: VpnStateStore.CoreMode
        ): Boolean {
            if (isStopping || pending == "stopping") return true
            val hasActiveProxyState = isRunning || isStarting || pending.isNotBlank()
            if (mode == VpnStateStore.CoreMode.PROXY && hasActiveProxyState) {
                return false
            }
            return mode == VpnStateStore.CoreMode.NONE && pending.isNotBlank()
        }

        internal fun shouldContinueCoreStartAfterForegroundResult(foregroundStarted: Boolean): Boolean {
            return foregroundStarted
        }

        internal fun shouldClearRuntimeStateAfterStop(stopService: Boolean): Boolean {
            return stopService
        }

        internal fun shouldStartForegroundBeforeConfigGeneration(action: String?, configPath: String?): Boolean {
            return action == ACTION_START && configPath.isNullOrBlank()
        }

        internal fun shouldReloadRuntimeConfig(
            isRecoveryStart: Boolean,
            isRunning: Boolean,
            isStarting: Boolean,
            configPath: String?
        ): Boolean {
            return !isRecoveryStart && (isRunning || isStarting) && !configPath.isNullOrBlank()
        }

        internal fun setLastError(message: String?) {
            _lastErrorFlow.value = message
            if (!message.isNullOrBlank()) {
                runCatching {
                    LogRepository.getInstance().addLog("ERROR ProxyOnlyService: $message")
                }
            }
        }
    }

    internal var commandServer: CommandServer? = null
    internal var runtimeCommandClient: CommandClient? = null
    internal val groupSelectedOutbounds = ConcurrentHashMap<String, String>()
    internal val activeRuntimeConnectionIds = ConcurrentHashMap.newKeySet<String>()
    internal val trafficMonitor = TrafficMonitor()
    internal val connectionTrafficAttributor = ConnectionTrafficAttributor()
    internal val connectionStormGuard = ConnectionStormGuard()
    internal val connectionIncidentHistory by lazy { ConnectionIncidentHistory(this) }
    internal val gson = Gson()
    internal val healthSignalAggregator = HealthSignalAggregator()
    internal val sameNodeRecoveryGate = SameNodeRecoveryGate()
    internal val layeredNetworkHealthSampler = LayeredNetworkHealthSampler()
    internal val sameNodeRecoveryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile internal var sameNodeRecoveryJob: Job? = null

    internal val notificationUpdateDebounceMs: Long = 900L
    internal val lastNotificationUpdateAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile internal var notificationUpdateJob: Job? = null
    @Volatile internal var suppressNotificationUpdates = false
    @Volatile internal var showNotificationSpeed = true
    @Volatile internal var currentUploadSpeed = 0L
    @Volatile internal var currentDownloadSpeed = 0L

    internal val lastPrepareRestartAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    internal val prepareRestartDebounceMs: Long = 1500L

    internal val hasForegroundStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    internal val serviceSupervisorJob = SupervisorJob()
    internal val serviceScope = CoroutineScope(Dispatchers.IO + serviceSupervisorJob)
    internal val cleanupSupervisorJob = SupervisorJob()
    internal val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupSupervisorJob)

    @Volatile internal var isStopping: Boolean = false
    @Volatile internal var stopSelfRequested: Boolean = false
    @Volatile internal var lastStopInitiator: VpnStopInitiator = VpnStopInitiator.UNKNOWN
    @Volatile internal var startJob: Job? = null
    @Volatile internal var configGenerationJob: Job? = null
    @Volatile internal var cleanupJob: Job? = null
    @Volatile internal var currentConfigPath: String? = null
    @Volatile internal var pendingStartConfigPath: String? = null
    @Volatile internal var pendingStartRecoveryIntentLease: RecoveryIntentLease? = null
    @Volatile internal var activeStartRecoveryIntentLease: RecoveryIntentLease? = null
    @Volatile internal var pendingStopRecoveryIntentLease: RecoveryIntentLease? = null
    internal val resourceGuardGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    internal val resourceGuardCancellationGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    internal val resourceGuardOwnerId = Any()
    @Volatile internal var resourceGuardRegistration: ResourceGuardRegistration? = null
    @Volatile internal var pendingRecoveryIntentLease: RecoveryIntentLease? = null

    internal var connectivityManager: ConnectivityManager? = null
    internal var networkCallback: ConnectivityManager.NetworkCallback? = null
    internal var currentInterfaceListener: InterfaceUpdateListener? = null

    internal val platformInterface: PlatformInterface = createProxyOnlyPlatformInterface()

    override fun onBind(intent: Intent?): IBinder? = null

    internal fun recordServiceLifecycle(
        event: String,
        reason: String,
        recovery: Boolean = false,
        action: String? = null
    ) {
        LogRepository.getInstance().addAlwaysLog(
            buildServiceLifecycleDiagnostic(
                service = "proxy",
                event = event,
                reason = reason,
                pid = Process.myPid(),
                details = buildString {
                    append("process_started_at_epoch_ms=${readProcessStartedAtEpochMs() ?: -1L} ")
                    append("app_version_code=${VersionInfo.getAppVersionCode(this@ProxyOnlyService)} ")
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
        recordServiceLifecycle(event = "create", reason = "service_on_create")
        createProxyOnlyNotificationChannel(CHANNEL_ID, LEGACY_CHANNEL_ID, TAG)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        serviceScope.launch {
            lastErrorFlow.collect {
                notifyRemoteState()
            }
        }

        serviceScope.launch {
            ConfigRepository.getInstance(this@ProxyOnlyService).activeNodeId.collect {
                if (isRunning) {
                    requestNotificationUpdate(force = false)
                    notifyRemoteState()
                }
            }
        }

        serviceScope.launch {
            SettingsRepository.getInstance(this@ProxyOnlyService)
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
    }

    @Suppress(
        "ReturnCount",
        "LongMethod",
        "CyclomaticComplexMethod",
        "CognitiveComplexMethod",
        "NestedBlockDepth"
    )
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recoveryFlag = intent?.getBooleanExtra(SingBoxService.EXTRA_RECOVERY, false) == true
        Log.i(TAG, "onStartCommand action=${intent?.action} recovery=$recoveryFlag")
        recordServiceLifecycle(
            event = "start_request",
            reason = if (intent?.action == null) "sticky_restart" else "intent",
            recovery = recoveryFlag,
            action = intent?.action?.substringAfterLast('.')
        )
        if (recoveryFlag) {
            runCatching {
                LogRepository.getInstance().addAlwaysLog(
                    "INFO [Recovery] Proxy START running=$isRunning starting=$isStarting"
                )
            }
        }

        when (intent?.action) {
            ACTION_START -> {
                if (VpnStateStore.getPending() == "stopping") {
                    Log.w(TAG, "Ignoring proxy START while VPN stop is still in progress")
                    return START_NOT_STICKY
                }
                val isRecoveryStart = intent.getBooleanExtra(SingBoxService.EXTRA_RECOVERY, false)
                if (!isRecoveryStart) lastStopInitiator = VpnStopInitiator.UNKNOWN
                val requestedConfigPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                // 恢复 START 幂等：核心已在跑/正在起时只刷新状态，不重置 pending。
                if (isRecoveryStart && RecoveryPolicy.shouldIgnoreRecoveryStart(isRunning, isStarting)) {
                    Log.i(TAG, "Duplicate START ignored: proxy core already active (recovery=$isRecoveryStart)")
                    runCatching {
                        LogRepository.getInstance().addAlwaysLog(
                            "INFO [Recovery] Proxy START ignored: already active " +
                                "recovery=$isRecoveryStart running=$isRunning starting=$isStarting"
                        )
                    }
                    if (isRecoveryStart) VpnStateStore.clearRecoveryClaim()
                    notifyRemoteState(state = if (isRunning) ServiceState.RUNNING else ServiceState.STARTING)
                    return START_NOT_STICKY
                }
                if (isRecoveryStart &&
                    (VpnStateStore.isManuallyStopped() || VpnStateStore.getMode() != VpnStateStore.CoreMode.PROXY)
                ) {
                    Log.w(TAG, "Proxy recovery intent no longer matches persisted intent")
                    VpnTileService.persistVpnPending("")
                    VpnStateStore.clearRecoveryClaim()
                    return START_NOT_STICKY
                }
                val recoveryIntentLease = setNonResourceRecoveryIntent(isRecoveryStart)
                VpnTileService.persistVpnPending("starting")
                if (shouldReloadRuntimeConfig(isRecoveryStart, isRunning, isStarting, requestedConfigPath)) {
                    Log.i(TAG, "Runtime config reload requested: $requestedConfigPath")
                    queueCoreRestart(requestedConfigPath.orEmpty(), recoveryIntentLease)
                    return START_NOT_STICKY
                }
                val configPath = requestedConfigPath

                // P0 Optimization: If config path is missing, generate it inside Service
                if (configPath == null) {
                    if (shouldStartForegroundBeforeConfigGeneration(intent.action, configPath) &&
                        !startForegroundForProxyStart()
                    ) {
                        if (setLastErrorIfCurrent(recoveryIntentLease, "Failed to start foreground service") &&
                            clearStartupFailureState(recoveryIntentLease)
                        ) {
                            stopSelf()
                        }
                        return START_NOT_STICKY
                    }
                    Log.i(TAG, "ACTION_START received without config path, generating config...")
                    configGenerationJob?.cancel()
                    configGenerationJob = serviceScope.launch {
                        try {
                            SettingsRepository.getInstance(applicationContext).reloadFromStorage()
                            val repo = ConfigRepository.getInstance(applicationContext)
                            val result = repo.generateConfigFile()
                            if (result != null) {
                                Log.i(TAG, "Config generated successfully: ${result.path}")
                                startCore(result.path, recoveryIntentLease)
                            } else {
                                if (!setLastErrorIfCurrent(recoveryIntentLease, "Failed to generate config file")) {
                                    return@launch
                                }
                                Log.e(TAG, "Failed to generate config file")
                                withContext(Dispatchers.Main) {
                                    if (clearStartupFailureState(recoveryIntentLease)) stopSelf()
                                }
                            }
                        } catch (_: CancellationException) {
                            Log.i(TAG, "Proxy config generation cancelled by stop request")
                        } catch (e: Exception) {
                            if (!setLastErrorIfCurrent(recoveryIntentLease, "Error generating config: ${e.message}")) {
                                return@launch
                            }
                            Log.e(TAG, "Error generating config in Service", e)
                            withContext(Dispatchers.Main) {
                                if (clearStartupFailureState(recoveryIntentLease)) stopSelf()
                            }
                        } finally {
                            val runningJob = currentCoroutineContext()[Job]
                            synchronized(this@ProxyOnlyService) {
                                if (configGenerationJob === runningJob) configGenerationJob = null
                            }
                        }
                    }
                    return START_NOT_STICKY
                }

                if (!configPath.isNullOrBlank()) {
                    startCore(configPath, recoveryIntentLease)
                } else {
                    if (setLastErrorIfCurrent(recoveryIntentLease, "Config path is empty") &&
                        clearStartupFailureState(recoveryIntentLease)
                    ) {
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                VpnStateStore.setStopOwnerMode(VpnStateStore.CoreMode.PROXY)
                if (ServiceStateHolder.shouldIgnoreDuplicateHardStop(isStopping, stopSelfRequested)) {
                    Log.i(TAG, "Ignoring duplicate ACTION_STOP while cleanup is already running")
                    return START_NOT_STICKY
                }
                val stopInitiator = VpnStopInitiator.fromWireValue(
                    intent.getStringExtra(SingBoxService.EXTRA_STOP_INITIATOR)
                )
                lastStopInitiator = stopInitiator
                VpnStateStore.setManuallyStopped(stopInitiator.isManualStop)
                recordServiceLifecycle(
                    event = "stop_request",
                    reason = if (stopInitiator.isManualStop) "manual_stop" else "automatic_stop",
                    action = ACTION_STOP.substringAfterLast('.')
                )
                val recoveryIntentLease = setNonResourceRecoveryIntent(false)
                VpnTileService.persistVpnPending("stopping")
                notifyRemoteState(state = ServiceState.STOPPING)
                stopCore(stopService = true, recoveryIntentLease = recoveryIntentLease)
            }
            ACTION_FORCE_STOP -> {
                forceStopProcess("explicit_force_stop")
            }
            ACTION_SWITCH_NODE -> {
                val recoveryIntentLease = setNonResourceRecoveryIntent(false)
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                val targetNodeId = intent.getStringExtra("node_id")
                val outboundTag = intent.getStringExtra("outbound_tag")
                val targetNodeName = intent.getStringExtra(SingBoxService.EXTRA_PENDING_NODE_NAME)
                if (isRunning && !targetNodeId.isNullOrBlank() && !outboundTag.isNullOrBlank()) {
                    serviceScope.launch {
                        performHotSwitch(
                            nodeId = targetNodeId,
                            outboundTag = outboundTag,
                            targetNodeName = targetNodeName,
                            fallbackConfigPath = configPath,
                            recoveryIntentLease = recoveryIntentLease
                        )
                    }
                } else if (!configPath.isNullOrBlank()) {
                    queueCoreRestart(configPath, recoveryIntentLease)
                } else {
                    serviceScope.launch {
                        val repo = ConfigRepository.getInstance(this@ProxyOnlyService)
                        val generationResult = runCatching { repo.generateConfigFile() }
                            .onFailure { error -> Log.e(TAG, "Failed to generate switch config", error) }
                            .getOrNull()
                        val generatedPath = generationResult?.path
                        if (generatedPath.isNullOrBlank()) {
                            completeRecoveryIntentOnSuccess(recoveryIntentLease)
                            return@launch
                        }
                        queueCoreRestart(generatedPath, recoveryIntentLease)
                    }
                }
            }
            ACTION_RESET_CONNECTIONS -> {
                val closed = closeRuntimeConnections()
                LogRepository.getInstance().addAlwaysLog(
                    "INFO [METERED_GUARD] mode=proxy explicit_connection_reset=$closed"
                )
            }
            ACTION_PREPARE_RESTART -> {

                val reason = intent.getStringExtra(SingBoxService.EXTRA_PREPARE_RESTART_REASON).orEmpty()
                Log.i(TAG, "Received ACTION_PREPARE_RESTART (reason='$reason') -> preparing for restart")

                val now = SystemClock.elapsedRealtime()
                val last = lastPrepareRestartAtMs.get()
                val elapsed = now - last
                if (elapsed < prepareRestartDebounceMs) {
                    Log.d(TAG, "ACTION_PREPARE_RESTART skipped (debounce, elapsed=${elapsed}ms)")
                    return START_NOT_STICKY
                }
                lastPrepareRestartAtMs.set(now)

                serviceScope.launch {
                    try {

                        commandServer?.wake()
                        Log.i(TAG, "[PrepareRestart] Ensured core is awake")

                        Log.i(TAG, "[PrepareRestart] Complete")
                    } catch (e: Exception) {
                        Log.e(TAG, "PrepareRestart error", e)
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    internal fun isPortAvailable(port: Int): Boolean {
        if (port <= 0) return true
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("127.0.0.1", port))
                true
            }
        } catch (@Suppress("SwallowedException") e: Exception) {
            false
        }
    }

    internal suspend fun waitForPortAvailable(port: Int, timeoutMs: Long = PORT_WAIT_TIMEOUT_MS): Boolean {
        if (port <= 0) return true
        val startTime = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            if (isPortAvailable(port)) {
                return true
            }
            delay(PORT_CHECK_INTERVAL_MS)
        }
        return false
    }

    internal fun updateDefaultInterface(network: Network) {
        val cm = connectivityManager ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return

        val ifaceName = try {
            val linkProperties = cm.getLinkProperties(network)
            linkProperties?.interfaceName.orEmpty()
        } catch (_: Exception) {
            ""
        }

        val isExpensive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val isConstrained = false
        currentInterfaceListener?.updateDefaultInterface(ifaceName, 0, isExpensive, isConstrained)
    }

    internal fun initializeStartupNodeLabel(configPath: String) {
        val startupTag = runCatching {
            resolveStartupProxyTag(configPath, gson)
        }.onFailure { e ->
            Log.w(TAG, "Failed to resolve startup node label", e)
        }.getOrNull()
        VpnStateStore.setActiveLabel(null)
        Log.i(TAG, "Startup selection pending kernel confirmation: ${startupTag ?: "(none)"}")
    }

    internal fun notifyRemoteState(state: ServiceState? = null) {
        val st = state ?: if (isRunning) ServiceState.RUNNING else ServiceState.STOPPED
        val activeLabel = VpnStateStore.getActiveLabel()

        SingBoxIpcHub.update(
            state = st,
            activeLabel = activeLabel,
            lastError = lastErrorFlow.value.orEmpty(),
            manuallyStopped = VpnStateStore.isManuallyStopped(),
            readiness = SingBoxIpcHub.currentReadiness().copy(
                status = when (st) {
                    ServiceState.RUNNING -> com.kunk.singbox.ipc.DataPlaneStatus.READY
                    ServiceState.STARTING -> com.kunk.singbox.ipc.DataPlaneStatus.STARTING
                    ServiceState.STOPPING -> com.kunk.singbox.ipc.DataPlaneStatus.RECOVERING
                    ServiceState.STOPPED -> com.kunk.singbox.ipc.DataPlaneStatus.STOPPED
                },
                tunEstablished = false,
                systemVpnTransport = false,
                coreReady = st == ServiceState.RUNNING,
                selectorReady = st == ServiceState.RUNNING,
                recoveryActive = st == ServiceState.STOPPING,
                routingScope = "proxy",
                lastReadinessReason = "proxy_${st.name.lowercase()}"
            )
        )
    }

    internal fun forceStopProcess(reason: String) {
        val manuallyStopped = VpnStateStore.isManuallyStopped()
        Log.e(TAG, "Force stopping proxy process reason=$reason manuallyStopped=$manuallyStopped")
        runCatching {
            cancelResourceGuard()
            isRunning = false
            isStarting = false
            NetworkClient.onVpnStateChanged(false)
            VpnTileService.persistVpnState(false)
            VpnStateStore.clearRuntimeState(preserveLastError = manuallyStopped)
            if (manuallyStopped) {
                VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
                if (VpnStateStore.getStopOwnerMode() == VpnStateStore.CoreMode.PROXY) {
                    VpnStateStore.clearStopOwnerMode()
                }
                VpnStateStore.clearRecoveryClaim()
            }
            VpnTileService.persistVpnPending("")
            notifyRemoteState(state = ServiceState.STOPPED)
            updateTileState()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }.onFailure { error ->
            Log.e(TAG, "Failed to persist proxy force-stop state", error)
        }
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    internal fun updateTileState() {
        runCatching {
            val intent = Intent(VpnTileService.ACTION_REFRESH_TILE)
            intent.setClass(applicationContext, VpnTileService::class.java)
            startService(intent)
        }
    }

    internal fun startForegroundForProxyStart(): Boolean {
        if (hasForegroundStarted.get()) return true

        return try {
            val notification = createProxyOnlyNotification(
                CHANNEL_ID,
                showNotificationSpeed,
                currentUploadSpeed,
                currentDownloadSpeed
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            hasForegroundStarted.set(true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call startForeground", e)
            false
        }
    }

    internal fun updateNotification() {
        val notification = createProxyOnlyNotification(
            CHANNEL_ID,
            showNotificationSpeed,
            currentUploadSpeed,
            currentDownloadSpeed
        )
        val manager = getSystemService(NotificationManager::class.java)
        if (!hasForegroundStarted.get()) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                hasForegroundStarted.set(true)
            }.onFailure { e ->
                Log.w(TAG, "Failed to call startForeground, fallback to notify()", e)
                manager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            runCatching {
                manager.notify(NOTIFICATION_ID, notification)
            }.onFailure { e ->
                Log.w(TAG, "Failed to update notification via notify()", e)
            }
        }
    }

    internal fun requestNotificationUpdate(force: Boolean = false) {
        if (suppressNotificationUpdates) return
        if (isStopping) return
        val now = SystemClock.elapsedRealtime()
        val last = lastNotificationUpdateAtMs.get()

        if (force) {
            lastNotificationUpdateAtMs.set(now)
            notificationUpdateJob?.cancel()
            notificationUpdateJob = null
            updateNotification()
            return
        }

        val delayMs = (notificationUpdateDebounceMs - (now - last)).coerceAtLeast(0L)
        if (delayMs <= 0L) {
            lastNotificationUpdateAtMs.set(now)
            notificationUpdateJob?.cancel()
            notificationUpdateJob = null
            updateNotification()
            return
        }

        if (notificationUpdateJob?.isActive == true) return
        notificationUpdateJob = serviceScope.launch {
            delay(delayMs)
            lastNotificationUpdateAtMs.set(SystemClock.elapsedRealtime())
            updateNotification()
        }
    }

    internal fun clearRuntimeStateOnDestroy() {
        isRunning = false
        isStarting = false
        NetworkClient.onVpnStateChanged(false)
        VpnTileService.persistVpnState(false)
        VpnStateStore.clearRuntimeState()
        VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
        if (VpnStateStore.getStopOwnerMode() == VpnStateStore.CoreMode.PROXY) {
            VpnStateStore.clearStopOwnerMode()
        }
        VpnTileService.persistVpnPending("")
        notifyRemoteState(state = ServiceState.STOPPED)
        updateTileState()
    }

    /** 意外销毁：只落"当前不在跑"，mode 意图留给 keepalive/冷启动恢复。 */
    internal fun preserveRecoveryIntentOnUnexpectedDestroy() {
        isRunning = false
        isStarting = false
        NetworkClient.onVpnStateChanged(false)
        VpnTileService.persistVpnState(false)
        VpnTileService.persistVpnPending("")
        notifyRemoteState(state = ServiceState.STOPPED)
        updateTileState()
        Log.i(TAG, "onDestroy: unexpected death, recovery intent preserved")
    }

    @Suppress("LongMethod")
    override fun onDestroy() {
        val mode = VpnStateStore.getMode()
        var shouldClearRuntimeState = false
        var startJobToCancel: Job? = null
        var serverToClose: CommandServer? = null
        var runtimeClientToDisconnect: CommandClient? = null
        synchronized(this) {
            shouldClearRuntimeState = shouldClearRuntimeStateOnDestroy(
                isRunning = isRunning,
                isStarting = isStarting,
                isStopping = isStopping,
                pending = VpnStateStore.getPending(),
                mode = mode
            )
            isStopping = true
            activeStartRecoveryIntentLease = null
            pendingStartConfigPath = null
            pendingStartRecoveryIntentLease = null
            pendingStopRecoveryIntentLease = null
            startJobToCancel = startJob.also { startJob = null }
            configGenerationJob?.cancel()
            configGenerationJob = null
            serverToClose = commandServer.also { commandServer = null }
            runtimeClientToDisconnect = runtimeCommandClient.also { runtimeCommandClient = null }
            cleanupJob = null
        }
        recordServiceLifecycle(
            event = "destroy",
            reason = when {
                lastStopInitiator.isManualStop || VpnStateStore.isManuallyStopped() -> "manual_stop"
                lastStopInitiator != VpnStopInitiator.UNKNOWN -> "automatic_stop"
                shouldClearRuntimeState -> "cleanup"
                mode == VpnStateStore.CoreMode.PROXY -> "unexpected_destroy"
                else -> "inactive_destroy"
            }
        )
        cancelResourceGuard()
        startJobToCancel?.cancel()
        sameNodeRecoveryJob?.cancel()
        sameNodeRecoveryJob = null
        sameNodeRecoveryInFlight.set(false)
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
        hasForegroundStarted.set(false)
        suppressNotificationUpdates = true
        runCatching { serviceSupervisorJob.cancel() }
        runCatching { cleanupSupervisorJob.cancel() }
        runtimeClientToDisconnect?.disconnect()
        SelectorManager.clear()
        groupSelectedOutbounds.clear()
        activeRuntimeConnectionIds.clear()
        trafficMonitor.reset()
        connectionTrafficAttributor.clear()
        healthSignalAggregator.clearDnsFailures()

        runCatching {
            serverToClose?.closeService()
            serverToClose?.close()
        }.onFailure { e ->
            Log.w(TAG, "Failed to close proxy-only CommandServer on destroy", e)
        }
        BoxWrapperManager.release()

        if (shouldClearRuntimeState) {
            // 用户停/收尾：清运行态与 mode
            clearRuntimeStateOnDestroy()
        } else if (mode == VpnStateStore.CoreMode.PROXY) {
            // 意外死亡：与 VPN 一致，保留 mode，只清 active/pending
            preserveRecoveryIntentOnUnexpectedDestroy()
            runCatching {
                LogRepository.getInstance().addAlwaysLog(
                    "INFO [Recovery] Proxy onDestroy unexpectedDeath mode=$mode preserveIntent=true"
                )
            }
        }

        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }
}
