package com.kunk.singbox.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.LibboxCompat
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.core.StringIteratorImpl
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.buildServiceLifecycleDiagnostic
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.RecoveryPolicy
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.utils.LocalNetworkPermission
import com.kunk.singbox.utils.LocaleHelper
import com.kunk.singbox.utils.NetworkClient
import com.kunk.singbox.utils.VersionInfo
import com.kunk.singbox.utils.perf.BackgroundResourceGuard
import com.kunk.singbox.utils.perf.ResourceGuardRegistration
import com.kunk.singbox.utils.perf.ResourceGuardOwner
import com.kunk.singbox.utils.perf.readProcessStartedAtEpochMs
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

@Suppress("LargeClass")
class ProxyOnlyService : Service() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapFromCache(newBase))
    }

    companion object {
        private const val TAG = "ProxyOnlyService"
        private const val NOTIFICATION_ID = 11
        private const val CHANNEL_ID = "singbox_proxy_silent"
        private const val LEGACY_CHANNEL_ID = "singbox_proxy"

        private const val PORT_WAIT_TIMEOUT_MS = 5000L
        private const val PORT_CHECK_INTERVAL_MS = 100L

        const val ACTION_START = "com.kunk.singbox.START"
        const val ACTION_STOP = "com.kunk.singbox.STOP"
        const val ACTION_SWITCH_NODE = "com.kunk.singbox.SWITCH_NODE"
        const val ACTION_PREPARE_RESTART = "com.kunk.singbox.PREPARE_RESTART"
        const val EXTRA_CONFIG_PATH = "config_path"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isStarting = false
            private set

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

        private fun setLastError(message: String?) {
            _lastErrorFlow.value = message
            if (!message.isNullOrBlank()) {
                runCatching {
                    LogRepository.getInstance().addLog("ERROR ProxyOnlyService: $message")
                }
            }
        }
    }

    private var commandServer: CommandServer? = null
    private var runtimeCommandClient: CommandClient? = null
    private val groupSelectedOutbounds = ConcurrentHashMap<String, String>()
    private val trafficMonitor = TrafficMonitor()
    private val gson = Gson()

    private val notificationUpdateDebounceMs: Long = 900L
    private val lastNotificationUpdateAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var notificationUpdateJob: Job? = null
    @Volatile private var suppressNotificationUpdates = false
    @Volatile private var showNotificationSpeed = true
    @Volatile private var currentUploadSpeed = 0L
    @Volatile private var currentDownloadSpeed = 0L

    private val lastPrepareRestartAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val prepareRestartDebounceMs: Long = 1500L

    private val hasForegroundStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    private val serviceSupervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceSupervisorJob)
    private val cleanupSupervisorJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupSupervisorJob)

    @Volatile private var isStopping: Boolean = false
    @Volatile private var stopSelfRequested: Boolean = false
    @Volatile private var startJob: Job? = null
    @Volatile private var cleanupJob: Job? = null
    @Volatile private var currentConfigPath: String? = null
    @Volatile private var pendingStartConfigPath: String? = null
    @Volatile private var pendingStartRecoveryIntentLease: RecoveryIntentLease? = null
    @Volatile private var activeStartRecoveryIntentLease: RecoveryIntentLease? = null
    @Volatile private var pendingStopRecoveryIntentLease: RecoveryIntentLease? = null
    private val resourceGuardGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val resourceGuardCancellationGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val resourceGuardOwnerId = Any()
    @Volatile private var resourceGuardRegistration: ResourceGuardRegistration? = null
    @Volatile private var pendingRecoveryIntentLease: RecoveryIntentLease? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentInterfaceListener: InterfaceUpdateListener? = null

    private val platformInterface = object : PlatformInterface {
        override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport {
            return com.kunk.singbox.core.LocalResolverImpl
        }

        override fun autoDetectInterfaceControl(fd: Int) {
        }

        override fun openTun(options: TunOptions?): Int {
            setLastError("Proxy-only mode: TUN is disabled")
            return -1
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun useProcFS(): Boolean {
            val procPaths = listOf(
                "/proc/net/tcp",
                "/proc/net/tcp6",
                "/proc/net/udp",
                "/proc/net/udp6"
            )

            fun hasUidHeader(path: String): Boolean {
                return try {
                    val file = File(path)
                    if (!file.exists() || !file.canRead()) return false
                    val header = file.bufferedReader().use { it.readLine() } ?: return false
                    header.contains("uid")
                } catch (_: Exception) {
                    false
                }
            }

            return procPaths.all { path -> hasUidHeader(path) }
        }

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String?,
            sourcePort: Int,
            destinationAddress: String?,
            destinationPort: Int
        ): ConnectionOwner {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ConnectionOwner()

            fun parseAddress(value: String?): InetAddress? {
                if (value.isNullOrBlank()) return null
                val cleaned = value.trim().replace("[", "").replace("]", "").substringBefore("%")
                return try {
                    InetAddress.getByName(cleaned)
                } catch (_: Exception) {
                    null
                }
            }

            val sourceIp = parseAddress(sourceAddress)
            val destinationIp = parseAddress(destinationAddress)
            if (sourceIp == null || sourcePort <= 0 || destinationIp == null || destinationPort <= 0) {
                return ConnectionOwner()
            }

            return try {
                val cm = connectivityManager
                    ?: getSystemService(ConnectivityManager::class.java)
                    ?: return ConnectionOwner()
                val uid = cm.getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(sourceIp, sourcePort),
                    InetSocketAddress(destinationIp, destinationPort)
                )
                if (uid > 0) {
                    ConnectionOwner().apply {
                        userId = uid
                        LibboxCompat.setConnectionOwnerPackageName(
                            owner = this,
                            packageName = packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
                        )
                    }
                } else {
                    ConnectionOwner()
                }
            } catch (_: Exception) {
                ConnectionOwner()
            }
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            currentInterfaceListener = listener
            connectivityManager = getSystemService(ConnectivityManager::class.java)

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val cm = connectivityManager ?: return
                    val isActiveDefault = cm.activeNetwork == network
                    if (!isActiveDefault) return
                    val caps = cm.getNetworkCapabilities(network)
                    val isValidated =
                        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    if (!isValidated) {
                        Log.d(TAG, "Network available but not validated: $network, waiting")
                        return
                    }
                    updateDefaultInterface(network)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val cm = connectivityManager ?: return
                    if (cm.activeNetwork != network) return
                    val isValidated =
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (!isValidated) {
                        Log.d(TAG, "Active network $network not yet validated, waiting")
                        return
                    }
                    updateDefaultInterface(network)
                }

                override fun onLost(network: Network) {
                    currentInterfaceListener?.updateDefaultInterface("", 0, false, false)
                }
            }

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val callback = networkCallback ?: return
            runCatching {
                connectivityManager?.registerNetworkCallback(request, callback)
            }

            val activeNet = connectivityManager?.activeNetwork
            if (activeNet != null) {
                val caps = connectivityManager?.getNetworkCapabilities(activeNet)
                val isValidated =
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                if (isValidated) {
                    updateDefaultInterface(activeNet)
                } else {
                    Log.d(TAG, "Initial active network $activeNet not validated, deferring")
                }
            }
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            networkCallback?.let {
                runCatching {
                    connectivityManager?.unregisterNetworkCallback(it)
                }
            }
            networkCallback = null
            currentInterfaceListener = null
        }

        override fun getInterfaces(): NetworkInterfaceIterator? {
            return try {
                val interfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
                object : NetworkInterfaceIterator {
                    private val iterator = interfaces.filter { it.isUp && !it.isLoopback }.iterator()

                    override fun hasNext(): Boolean = iterator.hasNext()

                    override fun next(): io.nekohasekai.libbox.NetworkInterface {
                        val iface = iterator.next()
                        return io.nekohasekai.libbox.NetworkInterface().apply {
                            name = iface.name
                            index = iface.index
                            mtu = iface.mtu

                            var flagsStr = 0
                            if (iface.isUp) flagsStr = flagsStr or 1
                            if (iface.isLoopback) flagsStr = flagsStr or 4
                            if (iface.isPointToPoint) flagsStr = flagsStr or 8
                            if (iface.supportsMulticast()) flagsStr = flagsStr or 16
                            flags = flagsStr

                            val addrList = ArrayList<String>()
                            for (addr in iface.interfaceAddresses) {
                                val ip = addr.address.hostAddress
                                val cleanIp = if (ip != null && ip.contains("%")) ip.substring(0, ip.indexOf("%")) else ip
                                if (cleanIp != null) {
                                    addrList.add("$cleanIp/${addr.networkPrefixLength}")
                                }
                            }
                            addresses = StringIteratorImpl(addrList)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get interfaces", e)
                null
            }
        }

        override fun underNetworkExtension(): Boolean = false

        override fun includeAllNetworks(): Boolean = false

        override fun readWIFIState(): WIFIState? = null

        override fun clearDNSCache() {
        }

        override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {
        }

        override fun systemCertificates(): StringIterator? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun recordServiceLifecycle(
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
                    append("manually_stopped=${VpnStateStore.isManuallyStopped()} recovery=$recovery")
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
                val isRecoveryStart = intent.getBooleanExtra(SingBoxService.EXTRA_RECOVERY, false)
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
                    serviceScope.launch {
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
                        } catch (e: Exception) {
                            if (!setLastErrorIfCurrent(recoveryIntentLease, "Error generating config: ${e.message}")) {
                                return@launch
                            }
                            Log.e(TAG, "Error generating config in Service", e)
                            withContext(Dispatchers.Main) {
                                if (clearStartupFailureState(recoveryIntentLease)) stopSelf()
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
                VpnStateStore.setManuallyStopped(true)
                val recoveryIntentLease = setNonResourceRecoveryIntent(false)
                VpnTileService.persistVpnPending("stopping")
                notifyRemoteState(state = ServiceState.STOPPING)
                stopCore(stopService = true, recoveryIntentLease = recoveryIntentLease)
            }
            ACTION_SWITCH_NODE -> {
                val recoveryIntentLease = setNonResourceRecoveryIntent(false)
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (!configPath.isNullOrBlank()) {
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

    @Suppress(
        "CognitiveComplexMethod",
        "CyclomaticComplexMethod",
        "ComplexCondition",
        "LongMethod",
        "ReturnCount"
    )
    private fun startCore(configPath: String, recoveryIntentLease: RecoveryIntentLease) {
        val resourceRecoveryAttemptId = recoveryIntentLease.attemptId
        val shouldRestartActiveCore = synchronized(this) {
            if (!serviceSupervisorJob.isActive) return
            if (!ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease)) return
            if (isStopping) {
                pendingStartConfigPath = configPath
                pendingStartRecoveryIntentLease = recoveryIntentLease
                stopSelfRequested = false
                pendingStopRecoveryIntentLease = null
                return
            }
            if (isRunning || isStarting) return@synchronized true
            if (pendingStartRecoveryIntentLease === recoveryIntentLease) {
                pendingStartConfigPath = null
                pendingStartRecoveryIntentLease = null
            }
            isStarting = true
            activeStartRecoveryIntentLease = recoveryIntentLease
            setLastError(null)
            currentConfigPath = configPath
            initializeStartupNodeLabel(configPath)
            notifyRemoteState(state = ServiceState.STARTING)
            updateTileState()
            false
        }

        if (shouldRestartActiveCore) {
            queueCoreRestart(configPath, recoveryIntentLease)
            return
        }

        val foregroundStarted = startForegroundForProxyStart()
        if (!shouldContinueCoreStartAfterForegroundResult(foregroundStarted)) {
            if (!setLastErrorIfCurrent(recoveryIntentLease, "Failed to start foreground service")) {
                synchronized(this) {
                    if (activeStartRecoveryIntentLease === recoveryIntentLease) {
                        activeStartRecoveryIntentLease = null
                        isStarting = false
                    }
                }
                stopSupersededStartup()
                return
            }
            if (resourceRecoveryAttemptId == null) {
                if (clearStartupFailureState(recoveryIntentLease)) stopSelf()
            } else {
                serviceScope.launch {
                    BackgroundResourceGuard.failSuccessorAndAwait(
                        resourceGuardOwnerId,
                        resourceRecoveryAttemptId
                    )
                    withContext(Dispatchers.Main) {
                        if (clearStartupFailureState(recoveryIntentLease)) stopSelf()
                    }
                }
            }
            return
        }

        val nextStartJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            var activeRecoveryIntentLease = recoveryIntentLease
            try {
                val ruleSetRepo = RuleSetRepository.getInstance(this@ProxyOnlyService)
                runCatching {
                    ruleSetRepo.ensureRuleSetsReady(
                        forceUpdate = false,
                        allowNetwork = false
                    ) {}
                }

                val configFile = File(configPath)
                if (!configFile.exists()) {
                    if (!setLastErrorIfCurrent(recoveryIntentLease, "Config file not found: $configPath")) {
                        return@launch
                    }
                    BackgroundResourceGuard.failSuccessorAndAwait(
                        resourceGuardOwnerId,
                        resourceRecoveryAttemptId
                    )
                    withContext(Dispatchers.Main) {
                        if (clearStartupFailureState(recoveryIntentLease)) stopSelf()
                    }
                    return@launch
                }

                val settingsRepository = SettingsRepository.getInstance(this@ProxyOnlyService)
                settingsRepository.reloadFromStorage()
                val settings = settingsRepository.settings.first()
                if (!LocalNetworkPermission.canApplySettings(this@ProxyOnlyService, settings)) {
                    val reason = LocalNetworkPermission.MISSING_PERMISSION_ERROR
                    if (!setLastErrorIfCurrent(recoveryIntentLease, reason)) return@launch
                    Log.e(TAG, reason)
                    BackgroundResourceGuard.failSuccessorAndAwait(
                        resourceGuardOwnerId,
                        resourceRecoveryAttemptId
                    )
                    withContext(Dispatchers.Main) {
                        if (clearStartupFailureState(recoveryIntentLease)) stopSelf()
                    }
                    return@launch
                }

                val configContent = restrictLocalNetworkListenIfNeeded(configFile.readText())

                runCatching {
                    SingBoxCore.ensureLibboxSetup(this@ProxyOnlyService)
                }

                val proxyPort = settings.proxyPort
                if (proxyPort > 0 && !isPortAvailable(proxyPort)) {
                    Log.i(TAG, "Port $proxyPort in use, waiting for release...")
                    val waitStart = SystemClock.elapsedRealtime()
                    val portAvailable = waitForPortAvailable(proxyPort)
                    val waitTime = SystemClock.elapsedRealtime() - waitStart
                    if (portAvailable) {
                        Log.i(TAG, "Port $proxyPort available after ${waitTime}ms")
                    } else {
                        val reason = "Proxy port $proxyPort is unavailable after ${waitTime}ms"
                        if (!setLastErrorIfCurrent(recoveryIntentLease, reason)) return@launch
                        Log.e(TAG, reason)
                        BackgroundResourceGuard.failSuccessorAndAwait(
                            resourceGuardOwnerId,
                            resourceRecoveryAttemptId
                        )
                        withContext(Dispatchers.Main) {
                            if (clearStartupFailureState(recoveryIntentLease)) stopSelf()
                        }
                        return@launch
                    }
                }

                val serverHandler = object : CommandServerHandler {
                    override fun serviceStop() {
                        Log.i(TAG, "serviceStop requested")
                    }
                    override fun serviceReload() {
                        Log.i(TAG, "serviceReload requested")
                    }
                    override fun getSystemProxyStatus(): io.nekohasekai.libbox.SystemProxyStatus? = null
                    override fun setSystemProxyEnabled(isEnabled: Boolean) {}
                    override fun writeDebugMessage(message: String?) {
                        if (!message.isNullOrBlank()) {
                            Log.d(TAG, message)
                        }
                    }
                }

                val overrideOptions = OverrideOptions().apply {
                    autoRedirect = false
                }
                val server = synchronized(this@ProxyOnlyService) {
                    if (!ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease) ||
                        isStopping || activeStartRecoveryIntentLease !== recoveryIntentLease
                    ) {
                        null
                    } else {
                        Libbox.newCommandServer(serverHandler, platformInterface).also { createdServer ->
                            commandServer = createdServer
                            createdServer.start()
                            BoxWrapperManager.init(createdServer)
                            createdServer.startOrReloadService(configContent, overrideOptions)
                        }
                    }
                }
                if (server == null) {
                    Log.w(TAG, "Proxy CommandServer creation ignored for superseded recovery lease")
                    withContext(Dispatchers.Main) { stopSupersededStartup() }
                    return@launch
                }

                val baselineLease = synchronized(this@ProxyOnlyService) {
                    if (!ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease) ||
                        isStopping || activeStartRecoveryIntentLease !== recoveryIntentLease ||
                        commandServer !== server
                    ) {
                        return@synchronized null
                    }
                    val baseline = completeRecoveryIntentOnSuccess(recoveryIntentLease)
                        ?: return@synchronized null
                    activeRecoveryIntentLease = baseline
                    activeStartRecoveryIntentLease = baseline
                    isRunning = true
                    startRuntimeCommandClient()
                    NetworkClient.onVpnStateChanged(true)
                    VpnTileService.persistVpnState(true)
                    VpnStateStore.setMode(VpnStateStore.CoreMode.PROXY)
                    VpnStateStore.setManuallyStopped(false)
                    VpnTileService.persistVpnPending("")
                    VpnStateStore.clearRecoveryClaim()
                    setLastError(null)
                    notifyRemoteState(state = ServiceState.RUNNING)
                    updateTileState()
                    requestNotificationUpdate(force = true)
                    startResourceGuard()
                    isStarting = false
                    if (activeStartRecoveryIntentLease === baseline) {
                        activeStartRecoveryIntentLease = null
                    }
                    baseline
                }
                if (baselineLease == null) {
                    Log.w(TAG, "Proxy startup success ignored for superseded recovery lease")
                    withContext(Dispatchers.Main) { stopSupersededStartup() }
                    return@launch
                }
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                if (!ServiceStateHolder.isRecoveryIntentCurrent(activeRecoveryIntentLease)) {
                    Log.w(TAG, "Proxy startup failure ignored for superseded recovery lease", e)
                    withContext(Dispatchers.Main) { stopSupersededStartup() }
                    return@launch
                }
                val reason = "Failed to start proxy-only: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, reason, e)
                if (!setLastErrorIfCurrent(activeRecoveryIntentLease, reason)) {
                    withContext(Dispatchers.Main) { stopSupersededStartup() }
                    return@launch
                }
                BackgroundResourceGuard.failSuccessorAndAwait(
                    resourceGuardOwnerId,
                    resourceRecoveryAttemptId
                )
                withContext(Dispatchers.Main) {
                    stopCore(
                        stopService = true,
                        recoveryIntentLease = activeRecoveryIntentLease,
                        resourceRecoveryAttemptId = activeRecoveryIntentLease.attemptId
                    )
                }
            } finally {
                val runningJob = coroutineContext[Job]
                synchronized(this@ProxyOnlyService) {
                    if (startJob === runningJob) {
                        isStarting = false
                        startJob = null
                        if (activeStartRecoveryIntentLease === recoveryIntentLease ||
                            activeStartRecoveryIntentLease === activeRecoveryIntentLease
                        ) {
                            activeStartRecoveryIntentLease = null
                        }
                    }
                }
            }
        }

        val shouldStartJob = synchronized(this) {
            if (!ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease) ||
                isStopping || activeStartRecoveryIntentLease !== recoveryIntentLease
            ) {
                false
            } else {
                startJob?.cancel()
                startJob = nextStartJob
                true
            }
        }
        if (!shouldStartJob) {
            nextStartJob.cancel()
            synchronized(this) {
                if (activeStartRecoveryIntentLease === recoveryIntentLease) {
                    activeStartRecoveryIntentLease = null
                    isStarting = false
                }
            }
            stopSupersededStartup()
            return
        }
        nextStartJob.start()
    }

    private fun restrictLocalNetworkListenIfNeeded(configContent: String): String {
        if (!LocalNetworkPermission.shouldRestrictLanListen(this)) return configContent

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
                Log.i(TAG, "Restricted mixed inbound listen to loopback because local network permission is missing")
                gson.toJson(config.copy(inbounds = restrictedInbounds))
            } else {
                configContent
            }
        }.getOrElse { e ->
            Log.w(TAG, "Failed to restrict local network listen: ${e.message}")
            configContent
        }
    }

    private fun queueCoreRestart(configPath: String, recoveryIntentLease: RecoveryIntentLease): Boolean {
        synchronized(this) {
            if (!ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease)) return false
            pendingStartConfigPath = configPath
            pendingStartRecoveryIntentLease = recoveryIntentLease
            stopSelfRequested = false
            pendingStopRecoveryIntentLease = null
        }
        stopCore(
            stopService = false,
            recoveryIntentLease = recoveryIntentLease,
            resourceRecoveryAttemptId = recoveryIntentLease.attemptId
        )
        return true
    }

    private fun stopSupersededStartup() {
        val successorLease = synchronized(this) {
            pendingStartRecoveryIntentLease
                ?.takeIf(ServiceStateHolder::isRecoveryIntentCurrent)
                ?: pendingRecoveryIntentLease?.takeIf(ServiceStateHolder::isRecoveryIntentCurrent)
        } ?: return
        stopCore(
            stopService = false,
            recoveryIntentLease = successorLease,
            resourceRecoveryAttemptId = successorLease.attemptId
        )
    }

    private fun clearStartupFailureState(recoveryIntentLease: RecoveryIntentLease): Boolean = synchronized(this) {
        val consumedIntent = ServiceStateHolder.consumeRecoveryIntentOnFailure(recoveryIntentLease)
        if (consumedIntent == null) {
            Log.w(TAG, "Proxy startup failure ignored for superseded recovery lease")
            return@synchronized false
        }
        val preserveMode = RecoveryPolicy.shouldPreserveModeOnStartFailure(consumedIntent)
        if (pendingRecoveryIntentLease === recoveryIntentLease) pendingRecoveryIntentLease = null
        if (pendingStartRecoveryIntentLease === recoveryIntentLease) {
            pendingStartConfigPath = null
            pendingStartRecoveryIntentLease = null
        }
        if (activeStartRecoveryIntentLease === recoveryIntentLease) activeStartRecoveryIntentLease = null
        isRunning = false
        isStarting = false
        NetworkClient.onVpnStateChanged(false)
        VpnTileService.persistVpnState(false)
        if (preserveMode) {
            VpnStateStore.clearRuntimeState(preserveLastError = true)
        } else {
            VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
        }
        VpnTileService.persistVpnPending("")
        VpnStateStore.clearRecoveryClaim()
        notifyRemoteState(state = ServiceState.STOPPED)
        updateTileState()
        true
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    private fun stopCore(
        stopService: Boolean,
        recoveryIntentLease: RecoveryIntentLease,
        resourceRecoveryAttemptId: Long? = recoveryIntentLease.attemptId
    ): Job? {
        var jobToJoin: Job? = null
        var serverToClose: CommandServer? = null
        var runtimeClientToDisconnect: CommandClient? = null
        val shouldStartCleanup = synchronized(this) {
            if (!ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease)) {
                Log.w(TAG, "Proxy stop ignored for superseded recovery lease")
                return cleanupJob
            }
            stopSelfRequested = stopSelfRequested || stopService
            if (stopService) {
                pendingStartConfigPath = null
                pendingStartRecoveryIntentLease = null
                pendingStopRecoveryIntentLease = recoveryIntentLease
            }
            if (resourceRecoveryAttemptId == null) {
                cancelResourceGuard()
            } else {
                detachResourceGuard(resourceRecoveryAttemptId)
            }
            if (isStopping) {
                false
            } else {
                isStopping = true
                isStarting = false
                isRunning = false
                activeStartRecoveryIntentLease = null
                jobToJoin = startJob.also { startJob = null }
                serverToClose = commandServer.also { commandServer = null }
                runtimeClientToDisconnect = runtimeCommandClient.also { runtimeCommandClient = null }
                true
            }
        }
        if (!shouldStartCleanup) return cleanupJob

        notifyRemoteState(state = ServiceState.STOPPING)
        updateTileState()
        NetworkClient.onVpnStateChanged(false)

        jobToJoin?.cancel()
        runtimeClientToDisconnect?.disconnect()
        groupSelectedOutbounds.clear()
        trafficMonitor.reset()
        currentUploadSpeed = 0L
        currentDownloadSpeed = 0L

        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
        hasForegroundStarted.set(false)

        val proxyPort = runCatching {
            com.kunk.singbox.repository.SettingsRepository
                .getInstance(this@ProxyOnlyService)
                .settings.value.proxyPort
        }.getOrDefault(2080)

        // ponytail: ATOMIC 仅保证销毁竞态中进入不可取消清理段，任务仍由 cleanupSupervisorJob 持有。
        val job = cleanupScope.launch(start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) {
                try {
                    jobToJoin?.join()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to join start job", e)
                }
                BoxWrapperManager.release()

                if (serverToClose != null) {
                    Log.i(TAG, "Closing CommandServer...")
                    val closeStart = SystemClock.elapsedRealtime()
                    try {
                        serverToClose.closeService()
                        serverToClose.close()

                        if (proxyPort > 0) {
                            val portReleased = waitForPortAvailable(proxyPort, PORT_WAIT_TIMEOUT_MS)
                            val elapsed = SystemClock.elapsedRealtime() - closeStart
                            if (portReleased) {
                                Log.i(TAG, "CommandServer closed, port $proxyPort released in ${elapsed}ms")
                            } else {

                                val reason = "Proxy port $proxyPort was not released after ${elapsed}ms"
                                Log.e(TAG, reason)
                                setLastErrorIfCurrent(recoveryIntentLease, reason)
                            }
                        } else {
                            Log.i(TAG, "CommandServer closed in ${SystemClock.elapsedRealtime() - closeStart}ms")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to close CommandServer: ${e.message}", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!cleanupSupervisorJob.isActive) {
                        synchronized(this@ProxyOnlyService) {
                            cleanupJob = null
                        }
                        return@withContext
                    }
                    val (restartConfigPath, restartLease, hardStopLease) =
                        synchronized(this@ProxyOnlyService) {
                            val exactHardStopLease = pendingStopRecoveryIntentLease?.takeIf {
                                stopSelfRequested && ServiceStateHolder.isRecoveryIntentCurrent(it)
                            }
                            val queuedLease = pendingStartRecoveryIntentLease
                            val queuedPath = pendingStartConfigPath
                            val canRestart = exactHardStopLease == null &&
                                !queuedPath.isNullOrBlank() &&
                                queuedLease != null &&
                                ServiceStateHolder.isRecoveryIntentCurrent(queuedLease)
                            val restartPath = queuedPath.takeIf { canRestart }
                            val continuationLease = queuedLease.takeIf { canRestart }
                            if (canRestart) {
                                pendingStartConfigPath = null
                                pendingStartRecoveryIntentLease = null
                            }
                            isStopping = false
                            stopSelfRequested = false
                            pendingStopRecoveryIntentLease = null
                            cleanupJob = null
                            Triple(restartPath, continuationLease, exactHardStopLease)
                        }

                    when {
                        restartConfigPath != null && restartLease != null -> {
                            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                            startCore(restartConfigPath, restartLease)
                        }
                        hardStopLease != null -> {
                            val completed = synchronized(this@ProxyOnlyService) {
                                val consumedIntent = ServiceStateHolder.consumeRecoveryIntentOnFailure(
                                    hardStopLease
                                ) ?: return@synchronized false
                                if (pendingRecoveryIntentLease === hardStopLease) {
                                    pendingRecoveryIntentLease = null
                                }
                                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                                VpnTileService.persistVpnState(false)
                                val preserveMode = RecoveryPolicy.shouldPreserveModeOnStartFailure(consumedIntent)
                                if (preserveMode) {
                                    VpnStateStore.clearRuntimeState(preserveLastError = true)
                                    VpnStateStore.clearRecoveryClaim()
                                    Log.w(TAG, "Recovery start failed, mode preserved for next issuer")
                                } else {
                                    VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
                                }
                                VpnTileService.persistVpnPending("")
                                notifyRemoteState(state = ServiceState.STOPPED)
                                updateTileState()
                                stopSelf()
                                true
                            }
                            if (!completed) {
                                Log.w(TAG, "Proxy shutdown completion ignored for superseded recovery lease")
                            }
                        }
                        ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease) -> synchronized(
                            this@ProxyOnlyService
                        ) {
                            if (ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease)) {
                                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                                VpnTileService.persistVpnState(false)
                                VpnTileService.persistVpnPending("")
                                notifyRemoteState(state = ServiceState.STOPPED)
                                updateTileState()
                            }
                        }
                        else -> Log.w(TAG, "Proxy cleanup ignored for superseded recovery lease")
                    }
                }
            }
        }
        synchronized(this) {
            cleanupJob = job
        }
        job.invokeOnCompletion {
            synchronized(this) {
                if (cleanupJob === job) cleanupJob = null
            }
        }
        return job
    }

    private fun startRuntimeCommandClient() {
        val profileId = VpnStateStore.getSelectedProfileId()
        val autoSelectionEnabled = ConfigRepository.getInstance(this).isProfileAutoSelectionEnabled(profileId)
        trafficMonitor.reset()
        val options = createRuntimeCommandOptions(autoSelectionEnabled)
        val client = Libbox.newCommandClient(object : CommandClientHandler {
            override fun connected() = Unit
            override fun disconnected(message: String?) {
                Log.w(TAG, "Runtime command client disconnected: $message")
            }
            override fun clearLogs() = Unit
            override fun setDefaultLogLevel(level: Int) = Unit
            override fun writeLogs(messageList: LogIterator?) = Unit
            override fun writeStatus(message: StatusMessage?) = handleRuntimeStatus(message)
            override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
            override fun updateClashMode(newMode: String?) = Unit
            override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
            override fun writeGroups(groups: OutboundGroupIterator?) =
                handleRuntimeGroups(groups, autoSelectionEnabled)
        }, options)
        runtimeCommandClient = client
        client.connect()
    }

    private fun createRuntimeCommandOptions(autoSelectionEnabled: Boolean): CommandClientOptions {
        return CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            if (autoSelectionEnabled) {
                addCommand(Libbox.CommandGroup)
            }
            statusInterval = 3_000L * 1_000L * 1_000L
        }
    }

    private fun handleRuntimeStatus(message: StatusMessage?) {
        if (!isRunning || isStopping) return
        message ?: return
        val snapshot = trafficMonitor.updateTotals(
            uploadTotal = message.uplinkTotal,
            downloadTotal = message.downlinkTotal,
            sampleTimeMs = SystemClock.elapsedRealtime()
        )
        currentUploadSpeed = snapshot.uploadSpeed
        currentDownloadSpeed = snapshot.downloadSpeed
        if (showNotificationSpeed) {
            requestNotificationUpdate(force = false)
        }
    }

    private fun handleRuntimeGroups(groups: OutboundGroupIterator?, autoSelectionEnabled: Boolean) {
        if (!autoSelectionEnabled || !isRunning || isStopping) return
        groups ?: return
        while (groups.hasNext()) {
            val group = groups.next()
            val tag = group.tag
            val selected = group.selected
            if (!tag.isNullOrBlank() && !selected.isNullOrBlank()) {
                groupSelectedOutbounds[tag] = selected
            }
        }
        val concreteTag = CommandManager.resolveConcreteGroupSelection("PROXY", groupSelectedOutbounds) ?: return
        VpnStateStore.setActiveLabel(concreteTag)
        notifyRemoteState(state = ServiceState.RUNNING)
        requestNotificationUpdate(force = false)
    }

    private fun startResourceGuard() {
        val registration = ResourceGuardRegistration(
            ownerId = resourceGuardOwnerId,
            generation = resourceGuardGeneration.incrementAndGet()
        )
        resourceGuardRegistration = registration
        BackgroundResourceGuard.start(this, serviceScope, registration, object : ResourceGuardOwner {
            override fun isRecoveryAllowed(): Boolean {
                return !VpnStateStore.isManuallyStopped() &&
                    VpnStateStore.getMode() == VpnStateStore.CoreMode.PROXY &&
                    isResourceRecoveryLeaseCurrent()
            }

            override fun closeConnections(): Boolean = false

            override fun resetNetwork(): Boolean = BoxWrapperManager.resetNetwork()

            override fun restartCore(reason: String, attemptId: Long): Boolean {
                return restartCoreForResourceRecovery(reason, attemptId)
            }

            override fun recycleProcess(reason: String) {
                synchronized(this@ProxyOnlyService) {
                    if (VpnStateStore.isManuallyStopped() ||
                        VpnStateStore.getMode() != VpnStateStore.CoreMode.PROXY ||
                        !isResourceRecoveryLeaseCurrent()
                    ) {
                        return
                    }
                    val configPath = currentConfigPath?.takeIf { File(it).isFile }
                        ?: File(filesDir, "running_config.json").takeIf(File::isFile)?.absolutePath
                        ?: run {
                            publishBudgetExhausted("missing_config:$reason")
                            return
                        }
                    LogRepository.getInstance()
                        .addAlwaysLog("ERROR recovery resource_exhausted recycle_process=$reason")
                    recycleBackgroundProcess(
                        this@ProxyOnlyService,
                        Intent(this@ProxyOnlyService, ProxyOnlyService::class.java).apply {
                            action = ACTION_START
                            putExtra(EXTRA_CONFIG_PATH, configPath)
                            putExtra(SingBoxService.EXTRA_RECOVERY, true)
                        }
                    )
                }
            }

            override fun publishBudgetExhausted(reason: String) {
                synchronized(this@ProxyOnlyService) {
                    if (VpnStateStore.isManuallyStopped() ||
                        VpnStateStore.getMode() != VpnStateStore.CoreMode.PROXY ||
                        !isResourceRecoveryLeaseCurrent()
                    ) {
                        return
                    }
                    val message = "Resource recovery budget exhausted: $reason"
                    setLastError(message)
                    LogRepository.getInstance().addAlwaysLog("ERROR recovery resource_exhausted $message")
                    requestNotificationUpdate(force = true)
                    notifyRemoteState()
                }
            }
        })
    }

    private fun restartCoreForResourceRecovery(reason: String, attemptId: Long): Boolean {
        if (!BackgroundResourceGuard.isRecoveryAttemptActive(resourceGuardOwnerId, attemptId)) return false
        val configPath = currentConfigPath?.takeIf { File(it).isFile }
            ?: File(filesDir, "running_config.json").takeIf(File::isFile)?.absolutePath
            ?: return false
        LogRepository.getInstance().addAlwaysLog("WARN recovery resource_exhausted restart=$reason")
        val cancellationGeneration = resourceGuardCancellationGeneration.get()
        val recoveryIntentLease = claimResourceRecoveryIntent(attemptId) ?: return false
        if (resourceGuardCancellationGeneration.get() != cancellationGeneration ||
            !BackgroundResourceGuard.isRecoveryAttemptActive(resourceGuardOwnerId, attemptId)
        ) {
            clearResourceRecoveryIntent(recoveryIntentLease)
            return false
        }
        serviceScope.launch {
            if (!BackgroundResourceGuard.isRecoveryAttemptActive(resourceGuardOwnerId, attemptId)) return@launch
            val recoveryIntentStillValid =
                resourceGuardCancellationGeneration.get() == cancellationGeneration &&
                    !VpnStateStore.isManuallyStopped() &&
                    VpnStateStore.getMode() == VpnStateStore.CoreMode.PROXY &&
                    ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease)
            if (recoveryIntentStillValid) {
                queueCoreRestart(configPath, recoveryIntentLease)
            }
        }
        return true
    }

    private fun detachResourceGuard(attemptId: Long) {
        resourceGuardRegistration?.let { BackgroundResourceGuard.detach(it, attemptId) }
        resourceGuardRegistration = null
    }

    private fun cancelResourceGuard() {
        resourceGuardCancellationGeneration.incrementAndGet()
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
                VpnStateStore.getMode() != VpnStateStore.CoreMode.PROXY ||
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
            val ownedAttemptId = lease.attemptId ?: return
            if (ServiceStateHolder.clearResourceRecoveryIntent(resourceGuardOwnerId, ownedAttemptId, lease) &&
                pendingRecoveryIntentLease === lease
            ) {
                pendingRecoveryIntentLease = null
            }
        }
    }

    private fun completeRecoveryIntentOnSuccess(lease: RecoveryIntentLease): RecoveryIntentLease? = synchronized(this) {
        val baseline = ServiceStateHolder.completeRecoveryIntentOnSuccess(lease) ?: return@synchronized null
        if (pendingRecoveryIntentLease === lease) pendingRecoveryIntentLease = baseline
        baseline
    }

    private fun setLastErrorIfCurrent(lease: RecoveryIntentLease, message: String): Boolean = synchronized(this) {
        if (!ServiceStateHolder.isRecoveryIntentCurrent(lease)) return@synchronized false
        setLastError(message)
        true
    }

    private fun isPortAvailable(port: Int): Boolean {
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

    private suspend fun waitForPortAvailable(port: Int, timeoutMs: Long = PORT_WAIT_TIMEOUT_MS): Boolean {
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

    private fun updateDefaultInterface(network: Network) {
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

    private fun initializeStartupNodeLabel(configPath: String) {
        val startupTag = runCatching {
            resolveStartupProxyTag(configPath, gson)
        }.onFailure { e ->
            Log.w(TAG, "Failed to resolve startup node label", e)
        }.getOrNull()
        VpnStateStore.setActiveLabel(startupTag)
    }

    private fun notifyRemoteState(state: ServiceState? = null) {
        val st = state ?: if (isRunning) ServiceState.RUNNING else ServiceState.STOPPED
        val activeLabel = VpnStateStore.getActiveLabel()

        SingBoxIpcHub.update(
            state = st,
            activeLabel = activeLabel,
            lastError = lastErrorFlow.value.orEmpty(),
            manuallyStopped = VpnStateStore.isManuallyStopped()
        )
    }

    private fun updateTileState() {
        runCatching {
            val intent = Intent(VpnTileService.ACTION_REFRESH_TILE)
            intent.setClass(applicationContext, VpnTileService::class.java)
            startService(intent)
        }
    }

    private fun startForegroundForProxyStart(): Boolean {
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

    private fun updateNotification() {
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

    private fun requestNotificationUpdate(force: Boolean = false) {
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

    private fun clearRuntimeStateOnDestroy() {
        isRunning = false
        isStarting = false
        NetworkClient.onVpnStateChanged(false)
        VpnTileService.persistVpnState(false)
        VpnStateStore.clearRuntimeState()
        VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
        VpnTileService.persistVpnPending("")
        notifyRemoteState(state = ServiceState.STOPPED)
        updateTileState()
    }

    /** 意外销毁：只落"当前不在跑"，mode 意图留给 keepalive/冷启动恢复。 */
    private fun preserveRecoveryIntentOnUnexpectedDestroy() {
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
            serverToClose = commandServer.also { commandServer = null }
            runtimeClientToDisconnect = runtimeCommandClient.also { runtimeCommandClient = null }
            cleanupJob = null
        }
        recordServiceLifecycle(
            event = "destroy",
            reason = when {
                shouldClearRuntimeState -> "manual_or_cleanup"
                mode == VpnStateStore.CoreMode.PROXY -> "unexpected_destroy"
                else -> "inactive_destroy"
            }
        )
        cancelResourceGuard()
        startJobToCancel?.cancel()
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
        hasForegroundStarted.set(false)
        suppressNotificationUpdates = true
        runCatching { serviceSupervisorJob.cancel() }
        runCatching { cleanupSupervisorJob.cancel() }
        runtimeClientToDisconnect?.disconnect()
        groupSelectedOutbounds.clear()
        trafficMonitor.reset()

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
