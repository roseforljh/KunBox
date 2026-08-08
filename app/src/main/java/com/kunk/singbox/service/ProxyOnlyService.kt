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
import com.kunk.singbox.R
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.LatencyProbeTrafficKind
import com.kunk.singbox.core.LibboxCompat
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.core.StringIteratorImpl
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.MeteredNodeConfigGuard
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.RuntimeNodeRef
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.repository.buildServiceLifecycleDiagnostic
import com.kunk.singbox.service.manager.AttributedConnectionTraffic
import com.kunk.singbox.service.manager.ConnectionTrafficAttributor
import com.kunk.singbox.service.manager.ConnectionTrafficEventData
import com.kunk.singbox.service.manager.ConnectionTrafficEventReader
import com.kunk.singbox.service.manager.ConnectionIncidentHistory
import com.kunk.singbox.service.manager.ConnectionStormDecision
import com.kunk.singbox.service.manager.ConnectionStormGuard
import com.kunk.singbox.service.manager.LayeredNetworkHealthSampler
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.RecoveryPolicy
import com.kunk.singbox.service.manager.SameNodeFailureLayer
import com.kunk.singbox.service.manager.SameNodeRecoveryCoordinator
import com.kunk.singbox.service.manager.SameNodeRecoveryGate
import com.kunk.singbox.service.manager.SameNodeRecoveryOutcome
import com.kunk.singbox.service.manager.SameNodeRecoveryPermit
import com.kunk.singbox.service.manager.SameNodeRecoveryStage
import com.kunk.singbox.service.manager.SameNodeRecoveryVerification
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.manager.TimedProbeResult
import com.kunk.singbox.service.manager.UrlTestTagMatcher
import com.kunk.singbox.service.manager.probePhysicalDns
import com.kunk.singbox.service.manager.toIncidentSnapshot
import com.kunk.singbox.service.manager.toProbeDiagnosticFields
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
        const val ACTION_RESET_CONNECTIONS = "com.kunk.singbox.RESET_CONNECTIONS"
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
    private val activeRuntimeConnectionIds = ConcurrentHashMap.newKeySet<String>()
    private val trafficMonitor = TrafficMonitor()
    private val connectionTrafficAttributor = ConnectionTrafficAttributor()
    private val connectionStormGuard = ConnectionStormGuard()
    private val connectionIncidentHistory by lazy { ConnectionIncidentHistory(this) }
    private val gson = Gson()
    private val healthSignalAggregator = HealthSignalAggregator()
    private val sameNodeRecoveryGate = SameNodeRecoveryGate()
    private val layeredNetworkHealthSampler = LayeredNetworkHealthSampler()
    private val sameNodeRecoveryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var sameNodeRecoveryJob: Job? = null

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

                val rawConfigContent = configFile.readText(Charsets.UTF_8)
                MeteredNodeConfigGuard.requireRuntimeConfigAuthorized(
                    configContent = rawConfigContent,
                    selectedNodeId = VpnStateStore.getSelectedNodeId()
                )
                val configContent = restrictLocalNetworkListenIfNeeded(rawConfigContent)
                initializeRuntimeSelector(configContent)

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

    private suspend fun performHotSwitch(
        nodeId: String,
        outboundTag: String,
        targetNodeName: String?,
        fallbackConfigPath: String?,
        recoveryIntentLease: RecoveryIntentLease
    ) {
        val startedAtMs = SystemClock.elapsedRealtime()
        recordProxyHotSwitchEvent(startedAtMs, nodeId, outboundTag, "pending")
        val configPath = fallbackConfigPath?.takeIf { File(it).isFile }
            ?: currentConfigPath?.takeIf { File(it).isFile }
        val failure = runCatching {
            check(NodeProtectionStore.effectiveSelectedNodeId(VpnStateStore.getSelectedNodeId()) == nodeId) {
                "Proxy switch target is not the active manual selection transaction: $nodeId"
            }
            check(NodeProtectionStore.isRuntimeUseAuthorized(nodeId, VpnStateStore.getSelectedNodeId())) {
                "Metered node is not manually authorized: $nodeId"
            }
            val targetRef = NodeProtectionStore.runtimeMappings()[outboundTag]
            check(targetRef?.nodeId == nodeId) {
                "Runtime outbound does not belong to selected node: $outboundTag"
            }
            val content = checkNotNull(configPath) { "Missing fallback config for proxy hot switch" }
                .let { File(it).readText(Charsets.UTF_8) }
            MeteredNodeConfigGuard.requireRuntimeConfigAuthorized(
                configContent = content,
                selectedNodeId = nodeId
            )

            when (val result = SelectorManager.switchNode(outboundTag)) {
                is SelectorManager.SwitchResult.Success -> {
                    check(closeRuntimeConnections()) {
                        "Kernel selected $outboundTag but old connections could not be closed"
                    }
                    checkNotNull(completeRecoveryIntentOnSuccess(recoveryIntentLease)) {
                        "Proxy switch was superseded before publication"
                    }
                    val concreteTag = CommandManager.resolveConcreteGroupSelection("PROXY", groupSelectedOutbounds)
                    val activeLabel = targetNodeName?.takeIf(String::isNotBlank)
                        ?: concreteTag
                        ?: outboundTag
                    VpnStateStore.setActiveLabel(activeLabel)
                    setLastError(null)
                    notifyRemoteState(state = ServiceState.RUNNING)
                    requestNotificationUpdate(force = true)
                    Log.i(TAG, "Proxy hot switch confirmed and old connections closed: $outboundTag")
                    recordProxyHotSwitchEvent(startedAtMs, nodeId, outboundTag, "success")
                }
                is SelectorManager.SwitchResult.NeedRestart -> error(result.reason)
            }
        }.exceptionOrNull()

        if (failure == null) return
        Log.w(TAG, "Proxy hot switch failed, keeping current runtime: ${failure.message}", failure)
        recordProxyHotSwitchEvent(startedAtMs, nodeId, outboundTag, "failed", failure.message)
        if (completeRecoveryIntentOnSuccess(recoveryIntentLease) == null) return
        notifyRemoteState(state = ServiceState.RUNNING)
        requestNotificationUpdate(force = true)
    }

    private fun recordProxyHotSwitchEvent(
        startedAtMs: Long,
        nodeId: String,
        outboundTag: String,
        outcome: String,
        reason: String? = null
    ) {
        val phase = if (outcome == "pending") "request" else "complete"
        val level = if (outcome == "failed") "WARN" else "INFO"
        LogRepository.getInstance().addAlwaysLog(
            "$level [HOT_SWITCH] mode=proxy phase=$phase outcome=$outcome " +
                "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs} node_id=$nodeId " +
                "outbound=$outboundTag actual=${SelectorManager.getSelectedOutbound().orEmpty()} " +
                reason?.let { "reason=$it" }.orEmpty()
        )
    }

    private fun initializeRuntimeSelector(configContent: String) {
        val selector = gson.fromJson(configContent, SingBoxConfig::class.java)
            ?.outbounds
            .orEmpty()
            .firstOrNull { it.type == "selector" && it.tag.equals("PROXY", ignoreCase = true) }
        val outboundTags = selector?.outbounds.orEmpty().filter(String::isNotBlank)
        if (outboundTags.isEmpty()) {
            SelectorManager.clear()
        } else {
            SelectorManager.recordSelectorSignature(outboundTags)
        }
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
        SelectorManager.clear()
        groupSelectedOutbounds.clear()
        activeRuntimeConnectionIds.clear()
        trafficMonitor.reset()
        connectionTrafficAttributor.clear()
        connectionStormGuard.clear()
        healthSignalAggregator.clearDnsFailures()
        if (stopService) {
            sameNodeRecoveryJob?.cancel()
            sameNodeRecoveryJob = null
            sameNodeRecoveryInFlight.set(false)
        }
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
        trafficMonitor.reset()
        connectionTrafficAttributor.clear()
        connectionStormGuard.clear()
        activeRuntimeConnectionIds.clear()
        healthSignalAggregator.clearDnsFailures()
        val options = createRuntimeCommandOptions()
        val client = Libbox.newCommandClient(object : CommandClientHandler {
            override fun connected() = Unit
            override fun disconnected(message: String?) {
                Log.w(TAG, "Runtime command client disconnected: $message")
            }
            override fun clearLogs() = Unit
            override fun setDefaultLogLevel(level: Int) = Unit
            override fun writeLogs(messageList: LogIterator?) = handleRuntimeLogs(messageList)
            override fun writeStatus(message: StatusMessage?) = handleRuntimeStatus(message)
            override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
            override fun updateClashMode(newMode: String?) = Unit
            override fun writeConnectionEvents(events: ConnectionEvents?) = handleRuntimeConnectionEvents(events)
            override fun writeGroups(groups: OutboundGroupIterator?) = handleRuntimeGroups(groups)
        }, options)
        runtimeCommandClient = client
        SelectorManager.updateCommandClient(client)
        client.connect()
    }

    private fun createRuntimeCommandOptions(): CommandClientOptions {
        return CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandGroup)
            addCommand(Libbox.CommandConnections)
            addCommand(Libbox.CommandLog)
            statusInterval = 1_000L * 1_000L * 1_000L
        }
    }

    private fun handleRuntimeLogs(messages: LogIterator?) {
        messages ?: return
        val repository = LogRepository.getInstance()
        while (messages.hasNext()) {
            val message = messages.next()?.message?.takeIf(String::isNotBlank) ?: continue
            if (repository.isEnabled()) repository.addLog(message)
            handleKernelLogForSameNodeRecovery(message)
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

    private fun handleKernelLogForSameNodeRecovery(message: String) {
        val signal = healthSignalAggregator.observeKernelLog(
            line = message,
            nowMs = SystemClock.elapsedRealtime()
        ) ?: return
        LogRepository.getInstance().addAlwaysLog(HealthSignalAggregator.buildSummary(signal))

        when (signal.kind) {
            HealthSignalKind.RESOURCE_EXHAUSTED -> {
                val registration = resourceGuardRegistration
                if (registration != null) {
                    BackgroundResourceGuard.signalResourceExhaustion(registration, "proxy_kernel_emfile")
                } else {
                    val closed = closeRuntimeConnections()
                    val reset = BoxWrapperManager.resetNetwork()
                    LogRepository.getInstance().addAlwaysLog(
                        "WARN recovery resource_exhausted mode=proxy closed=$closed reset=$reset"
                    )
                }
            }
            HealthSignalKind.ACTIVE_PROBE_FAILED -> submitSameNodeRecovery(
                layer = SameNodeFailureLayer.PROXY,
                trigger = "active_probe_failed:${signal.outboundTag.orEmpty()}"
            )
            HealthSignalKind.REMOTE_DNS_TIMEOUT -> submitSameNodeRecovery(
                layer = SameNodeFailureLayer.DNS,
                trigger = "dns_remote_timeout"
            )
        }
    }

    @Suppress("CognitiveComplexMethod", "ComplexCondition")
    private fun submitSameNodeRecovery(layer: SameNodeFailureLayer, trigger: String) {
        if (!isRunning || isStarting || isStopping || VpnStateStore.isManuallyStopped()) return
        if (!sameNodeRecoveryInFlight.compareAndSet(false, true)) return

        when (sameNodeRecoveryGate.acquire(SystemClock.elapsedRealtime())) {
            SameNodeRecoveryPermit.COOLDOWN -> {
                sameNodeRecoveryInFlight.set(false)
                LogRepository.getInstance().addAlwaysLog(
                    "INFO recovery same_node mode=proxy skipped=cooldown layer=$layer trigger=$trigger"
                )
            }
            SameNodeRecoveryPermit.BUDGET_EXHAUSTED -> {
                sameNodeRecoveryInFlight.set(false)
                LogRepository.getInstance().addAlwaysLog(
                    "WARN recovery same_node mode=proxy budget_exhausted layer=$layer trigger=$trigger"
                )
            }
            SameNodeRecoveryPermit.ACQUIRED -> {
                val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val outcome = createSameNodeRecoveryCoordinator(layer, trigger).recover(layer)
                        LogRepository.getInstance().addAlwaysLog(
                            "INFO recovery same_node mode=proxy completed layer=$layer " +
                                "trigger=$trigger outcome=$outcome"
                        )
                        if (outcome == SameNodeRecoveryOutcome.Failed) {
                            Log.e(TAG, "Proxy same-node recovery exhausted all stages")
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(TAG, "Proxy same-node recovery failed", error)
                        LogRepository.getInstance().addAlwaysLog(
                            "ERROR recovery same_node mode=proxy exception=${error.javaClass.simpleName} " +
                                "message=${error.message.orEmpty()} layer=$layer trigger=$trigger"
                        )
                    } finally {
                        sameNodeRecoveryInFlight.set(false)
                        val currentJob = coroutineContext[Job]
                        if (sameNodeRecoveryJob === currentJob) sameNodeRecoveryJob = null
                    }
                }
                sameNodeRecoveryJob = job
                job.start()
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
                return closeRuntimeConnections()
            }

            override suspend fun resetNetwork(): Boolean {
                healthSignalAggregator.clearDnsFailures()
                return BoxWrapperManager.resetNetwork()
            }

            override suspend fun reloadCurrentConfig(): Boolean {
                healthSignalAggregator.clearDnsFailures()
                return reloadCurrentConfigForSameNodeRecovery()
            }

            override fun restartCurrentConfig(): Boolean = restartCurrentConfigForSameNodeRecovery()

            override suspend fun verify(
                nodeTag: String,
                layer: SameNodeFailureLayer
            ): SameNodeRecoveryVerification = verifySameNodeRecovery(nodeTag, layer)

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
                    network = currentPhysicalNetwork(),
                    host = probeHost,
                    timeoutMs = SingBoxService.SAME_NODE_DNS_PROBE_TIMEOUT_MS
                )
            },
            proxyProbe = {
                val latency = probeProxyLatency(nodeTag)
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
        ).also {
            if (layer == SameNodeFailureLayer.DNS && dnsFailures > 0) {
                Log.w(TAG, "Proxy DNS still failing after recovery stage: count=$dnsFailures")
            }
        }
    }

    private suspend fun resolveSameNodeProbeHost(): String? {
        return runCatching {
            val settings = SettingsRepository.getInstance(applicationContext).settings.first()
            AppSettings.latencyTestUri(settings.latencyTestUrl).host.takeIf(String::isNotBlank)
        }.onFailure { error ->
            Log.w(TAG, "Failed to resolve proxy same-node probe host", error)
        }.getOrNull()
    }

    private suspend fun probeProxyLatency(targetTag: String): Long? {
        val config = loadCurrentRuntimeConfig() ?: return null
        val outbounds = config.outbounds.orEmpty()
        val target = outbounds.firstOrNull {
            UrlTestTagMatcher.normalizeTag(it.tag) == UrlTestTagMatcher.normalizeTag(targetTag)
        } ?: return null
        return runCatching {
            SingBoxCore.getInstance(this@ProxyOnlyService).testOutboundLatency(
                outbound = target,
                allOutbounds = outbounds,
                dnsConfig = config.dns,
                timeoutOverrideMs = SingBoxService.HEALTH_FAST_FAILOVER_CANDIDATE_TIMEOUT_MS,
                trafficKind = LatencyProbeTrafficKind.HEALTH_CHECK
            ).takeIf { it > 0L }
        }.onFailure { error ->
            Log.w(TAG, "Proxy same-node HTTPS verification failed: $targetTag", error)
        }.getOrNull()
    }

    private suspend fun reloadCurrentConfigForSameNodeRecovery(): Boolean {
        if (!isRunning || isStopping) return false
        val configFile = resolveCurrentRuntimeConfigFile() ?: return false
        return runCatching {
            val rawConfigContent = withContext(Dispatchers.IO) { configFile.readText(Charsets.UTF_8) }
            MeteredNodeConfigGuard.requireRuntimeConfigAuthorized(
                configContent = rawConfigContent,
                selectedNodeId = VpnStateStore.getSelectedNodeId()
            )
            val configContent = restrictLocalNetworkListenIfNeeded(rawConfigContent)
            val server = synchronized(this) {
                commandServer.takeIf { isRunning && !isStopping }
            } ?: return false
            groupSelectedOutbounds.clear()
            VpnStateStore.setActiveLabel(null)
            initializeRuntimeSelector(configContent)
            server.startOrReloadService(
                configContent,
                OverrideOptions().apply { autoRedirect = false }
            )
            BoxWrapperManager.init(server)
            true
        }.onFailure { error ->
            Log.w(TAG, "Proxy same-node hot reload failed", error)
        }.getOrDefault(false)
    }

    private fun restartCurrentConfigForSameNodeRecovery(): Boolean {
        val configPath = resolveCurrentRuntimeConfigFile()?.absolutePath ?: return false
        return queueCoreRestart(configPath, setNonResourceRecoveryIntent(false))
    }

    private fun resolveCurrentRuntimeConfigFile(): File? {
        return currentConfigPath
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?: File(filesDir, "running_config.json").takeIf(File::isFile)
    }

    private fun loadCurrentRuntimeConfig(): SingBoxConfig? {
        val configFile = resolveCurrentRuntimeConfigFile() ?: return null
        return runCatching {
            gson.fromJson(configFile.readText(Charsets.UTF_8), SingBoxConfig::class.java)
        }.onFailure { error ->
            Log.w(TAG, "Failed to load proxy runtime config", error)
        }.getOrNull()
    }

    private fun resolveCurrentProxyOutboundTag(): String? {
        return CommandManager.resolveConcreteGroupSelection("PROXY", groupSelectedOutbounds)
            ?: SelectorManager.getSelectedOutbound()
    }

    private fun currentPhysicalNetwork(): Network? {
        val manager = connectivityManager ?: getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        return network.takeIf {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    private fun hasValidatedPhysicalNetwork(): Boolean {
        val manager = connectivityManager ?: getSystemService(ConnectivityManager::class.java)
        val network = currentPhysicalNetwork() ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
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
                append("INFO recovery same_node mode=proxy stage=$stage ")
                append("phase=${if (verification == null) "action" else "verify"} ")
                append("layer=$layer trigger=$trigger ")
                append("physical=${verification?.physicalNetworkHealthy ?: "unknown"} ")
                append("dns=${verification?.dnsHealthy ?: "unknown"} ")
                append("proxy=${verification?.proxyHealthy ?: "unknown"} ")
                append("selector=${verification?.selectorMatches ?: "unknown"} ")
                append("loss=${probeLossPercent?.let { "$it%" } ?: "unknown"} ")
                append("${verification?.toProbeDiagnosticFields() ?: "remote_dns_failures=-1"} ")
                append("connections=${activeRuntimeConnectionIds.size} ")
                append("outbound=${resolveCurrentProxyOutboundTag() ?: "unknown"}")
            }
        )
    }

    private fun handleRuntimeGroups(groups: OutboundGroupIterator?) {
        if (!isRunning || isStopping) return
        groups ?: return
        while (groups.hasNext()) {
            val group = groups.next()
            val tag = group.tag
            val selected = group.selected
            if (!tag.isNullOrBlank() && !selected.isNullOrBlank()) {
                groupSelectedOutbounds[tag] = selected
                SelectorManager.recordKernelSelection(tag, selected)
            }
        }
        val concreteTag = CommandManager.resolveConcreteGroupSelection("PROXY", groupSelectedOutbounds) ?: return
        if (SelectorManager.isSelectionPending()) return
        VpnStateStore.setActiveLabel(concreteTag)
        notifyRemoteState(state = ServiceState.RUNNING)
        requestNotificationUpdate(force = false)
    }

    private fun handleRuntimeConnectionEvents(events: ConnectionEvents?) {
        if (!isRunning || isStopping) return
        events ?: return
        runCatching {
            val mappings = NodeProtectionStore.runtimeMappings()
            val eventData = ConnectionTrafficEventReader.read(events)
            if (events.reset) {
                activeRuntimeConnectionIds.clear()
                connectionTrafficAttributor.clear()
            }
            enforceConnectionStormGuard(
                connectionStormGuard.observe(
                    reset = events.reset,
                    events = eventData,
                    nowMs = SystemClock.elapsedRealtime()
                )
            )
            eventData.forEach { event ->
                if (event.type == ConnectionTrafficAttributor.EVENT_CLOSED) {
                    activeRuntimeConnectionIds.remove(event.id)
                } else {
                    activeRuntimeConnectionIds.add(event.id)
                }
            }
            enforceRuntimeMeteredProtection(eventData, mappings)
            recordAttributedTraffic(
                connectionTrafficAttributor.apply(
                    reset = false,
                    events = eventData,
                    runtimeMappings = mappings
                )
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to process proxy connection events", error)
        }
    }

    private fun enforceRuntimeMeteredProtection(
        events: List<ConnectionTrafficEventData>,
        mappings: Map<String, RuntimeNodeRef>
    ) {
        val selectedNodeId = VpnStateStore.getSelectedNodeId()
        events.asSequence()
            .filter { it.type != ConnectionTrafficAttributor.EVENT_CLOSED }
            .forEach { event ->
                val unauthorized = connectionTrafficAttributor.resolveTargets(event, mappings)
                    .asSequence()
                    .firstOrNull { ref ->
                        NodeProtectionStore.isProtected(ref.nodeId) &&
                            !NodeProtectionStore.isRuntimeRefAuthorized(ref, selectedNodeId)
                    } ?: return@forEach
                val closed = closeRuntimeConnection(event.id) || closeRuntimeConnections()
                LogRepository.getInstance().addAlwaysLog(
                    "ERROR [METERED_GUARD] mode=proxy closed=$closed connection=${event.id} " +
                        "node=${unauthorized.nodeName} node_id=${unauthorized.nodeId}"
                )
            }
    }

    private fun enforceConnectionStormGuard(decision: ConnectionStormDecision?) {
        decision ?: return
        val closed = if (decision.closeAll) {
            closeRuntimeConnections()
        } else {
            decision.connectionIds.fold(true) { success, id -> closeRuntimeConnection(id) && success }
        }
        if (closed) connectionStormGuard.acknowledgeClosed(decision)
        persistConnectionIncident(decision, closed)
        LogRepository.getInstance().addAlwaysLog(
            "ERROR [CONNECTION_STORM] mode=proxy reason=${decision.reason} closed=$closed " +
                "active=${decision.activeConnections} created=${decision.newConnectionsInWindow} " +
                "rate=${String.format(java.util.Locale.US, "%.1f", decision.creationRatePerSecond)} " +
                "uid=${decision.offender?.uid ?: -1} " +
                "package=${decision.offender?.packageNames?.joinToString(",").orEmpty()} " +
                "inbound=${decision.offender?.inbound.orEmpty()} source=${decision.offender?.source.orEmpty()}"
        )
    }

    private fun persistConnectionIncident(decision: ConnectionStormDecision, closed: Boolean) {
        val snapshot = decision.toIncidentSnapshot(
            mode = "proxy",
            closeReason = if (decision.closeAll) "close_all" else "close_quarantined_source",
            closeSucceeded = closed,
            timestampEpochMs = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime()
        )
        serviceScope.launch(Dispatchers.IO) {
            runCatching { connectionIncidentHistory.append(snapshot) }
                .onFailure { error -> Log.e(TAG, "Failed to persist proxy connection incident", error) }
        }
    }

    private fun recordAttributedTraffic(records: List<AttributedConnectionTraffic>) {
        val repository = TrafficRepository.getInstance(this)
        records.forEach { record ->
            val targets = record.targets.ifEmpty {
                setOf(
                    RuntimeNodeRef(
                        nodeId = TrafficRepository.UNATTRIBUTED_NODE_ID,
                        nodeName = getString(R.string.traffic_unattributed)
                    )
                )
            }
            targets.forEach { target ->
                repository.addTraffic(
                    nodeId = target.nodeId,
                    uploadDiff = record.uploadDelta,
                    downloadDiff = record.downloadDelta,
                    nodeName = target.nodeName
                )
            }
        }
    }

    private fun closeRuntimeConnection(connectionId: String): Boolean {
        val client = runtimeCommandClient ?: return false
        return runCatching {
            client.closeConnection(connectionId)
            true
        }.getOrDefault(false)
    }

    private fun closeRuntimeConnections(): Boolean {
        val client = runtimeCommandClient ?: return false
        return runCatching {
            client.closeConnections()
            true
        }.onFailure { error ->
            Log.w(TAG, "Failed to close proxy connections", error)
        }.getOrDefault(false)
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

            override fun closeConnections(): Boolean = closeRuntimeConnections()

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
        VpnStateStore.setActiveLabel(null)
        Log.i(TAG, "Startup selection pending kernel confirmation: ${startupTag ?: "(none)"}")
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
