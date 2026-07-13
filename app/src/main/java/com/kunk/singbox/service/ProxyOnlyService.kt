package com.kunk.singbox.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
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
import com.kunk.singbox.utils.LocalNetworkPermission
import com.kunk.singbox.utils.NetworkClient
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

class ProxyOnlyService : Service() {

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
    private val gson = Gson()

    private val notificationUpdateDebounceMs: Long = 900L
    private val lastNotificationUpdateAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var notificationUpdateJob: Job? = null
    @Volatile private var suppressNotificationUpdates = false

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

    override fun onCreate() {
        super.onCreate()
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
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        runCatching {
            LogRepository.getInstance().addLog("INFO ProxyOnlyService: onStartCommand action=${intent?.action}")
        }

        when (intent?.action) {
            ACTION_START -> {
                VpnTileService.persistVpnPending("starting")
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)

                // P0 Optimization: If config path is missing, generate it inside Service
                if (configPath == null) {
                    if (shouldStartForegroundBeforeConfigGeneration(intent.action, configPath) &&
                        !startForegroundForProxyStart()
                    ) {
                        setLastError("Failed to start foreground service")
                        clearStartupFailureState()
                        stopSelf()
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
                                // Recursively call start command with the generated path
                                val newIntent = Intent(applicationContext, ProxyOnlyService::class.java).apply {
                                    action = ACTION_START
                                    putExtra(EXTRA_CONFIG_PATH, result.path)
                                }
                                startService(newIntent)
                            } else {
                                Log.e(TAG, "Failed to generate config file")
                                setLastError("Failed to generate config file")
                                withContext(Dispatchers.Main) {
                                    clearStartupFailureState()
                                    stopSelf()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error generating config in Service", e)
                            setLastError("Error generating config: ${e.message}")
                            withContext(Dispatchers.Main) {
                                clearStartupFailureState()
                                stopSelf()
                            }
                        }
                    }
                    return START_NOT_STICKY
                }

                if (!configPath.isNullOrBlank()) {
                    startCore(configPath)
                }
            }
            ACTION_STOP -> {
                VpnTileService.persistVpnPending("stopping")
                stopCore(stopService = true)
            }
            ACTION_SWITCH_NODE -> {
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (!configPath.isNullOrBlank()) {
                    serviceScope.launch {
                        stopCore(stopService = false)
                        waitForCleanupJob()
                        startCore(configPath)
                    }
                } else {
                    serviceScope.launch {
                        val repo = ConfigRepository.getInstance(this@ProxyOnlyService)
                        val generationResult = repo.generateConfigFile()
                        val generatedPath = generationResult?.path
                        if (generatedPath.isNullOrBlank()) return@launch
                        stopCore(stopService = false)
                        waitForCleanupJob()
                        startCore(generatedPath)
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

    @Suppress("CognitiveComplexMethod", "LongMethod")
    private fun startCore(configPath: String) {
        synchronized(this) {
            if (isRunning || isStarting) return
            if (isStopping) return
            isStarting = true
        }

        setLastError(null)
        initializeStartupNodeLabel(configPath)

        notifyRemoteState(state = ServiceState.STARTING)
        updateTileState()

        val foregroundStarted = startForegroundForProxyStart()
        if (!shouldContinueCoreStartAfterForegroundResult(foregroundStarted)) {
            setLastError("Failed to start foreground service")
            clearStartupFailureState()
            stopSelf()
            return
        }

        startJob?.cancel()
        startJob = serviceScope.launch {
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
                    setLastError("Config file not found: $configPath")
                    withContext(Dispatchers.Main) {
                        clearStartupFailureState()
                        stopSelf()
                    }
                    return@launch
                }

                val settingsRepository = SettingsRepository.getInstance(this@ProxyOnlyService)
                settingsRepository.reloadFromStorage()
                val settings = settingsRepository.settings.first()
                if (!LocalNetworkPermission.canApplySettings(this@ProxyOnlyService, settings)) {
                    val reason = LocalNetworkPermission.MISSING_PERMISSION_ERROR
                    Log.e(TAG, reason)
                    setLastError(reason)
                    withContext(Dispatchers.Main) {
                        clearStartupFailureState()
                        stopSelf()
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
                        Log.e(TAG, reason)
                        setLastError(reason)
                        withContext(Dispatchers.Main) {
                            clearStartupFailureState()
                            stopSelf()
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

                val server = Libbox.newCommandServer(serverHandler, platformInterface)
                commandServer = server
                server.start()

                val overrideOptions = OverrideOptions().apply {
                    autoRedirect = false
                }
                server.startOrReloadService(configContent, overrideOptions)

                isRunning = true
                NetworkClient.onVpnStateChanged(true)

                VpnTileService.persistVpnState(true)
                VpnStateStore.setMode(VpnStateStore.CoreMode.PROXY)
                VpnTileService.persistVpnPending("")
                setLastError(null)
                notifyRemoteState(state = ServiceState.RUNNING)
                updateTileState()
                requestNotificationUpdate(force = true)
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                val reason = "Failed to start proxy-only: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, reason, e)
                setLastError(reason)
                withContext(Dispatchers.Main) {
                    isRunning = false
                    notifyRemoteState(state = ServiceState.STOPPED)
                    stopCore(stopService = true)
                }
            } finally {
                isStarting = false
                startJob = null
            }
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

    private fun clearStartupFailureState() {
        isRunning = false
        isStarting = false
        NetworkClient.onVpnStateChanged(false)
        VpnTileService.persistVpnState(false)
        VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
        VpnTileService.persistVpnPending("")
        notifyRemoteState(state = ServiceState.STOPPED)
        updateTileState()
    }

    @Suppress("CognitiveComplexMethod", "LongMethod")
    private fun stopCore(stopService: Boolean): Job? {
        synchronized(this) {
            stopSelfRequested = stopSelfRequested || stopService
            if (isStopping) return cleanupJob
            isStopping = true
        }

        notifyRemoteState(state = ServiceState.STOPPING)
        updateTileState()
        isRunning = false
        NetworkClient.onVpnStateChanged(false)

        val jobToJoin = startJob
        startJob = null
        jobToJoin?.cancel()

        val serverToClose = commandServer
        commandServer = null

        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
        hasForegroundStarted.set(false)

        val proxyPort = runCatching {
            com.kunk.singbox.repository.SettingsRepository
                .getInstance(this@ProxyOnlyService)
                .settings.value.proxyPort
        }.getOrDefault(2080)

        val job = cleanupScope.launch {
            withContext(NonCancellable) {
                try {
                    jobToJoin?.join()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to join start job", e)
                }

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
                                setLastError(reason)
                            }
                        } else {
                            Log.i(TAG, "CommandServer closed in ${SystemClock.elapsedRealtime() - closeStart}ms")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to close CommandServer: ${e.message}", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    if (stopSelfRequested) {
                        stopSelf()
                    }
                    if (shouldClearRuntimeStateAfterStop(stopService = stopSelfRequested)) {
                        VpnTileService.persistVpnState(false)
                        VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
                        VpnTileService.persistVpnPending("")
                    }
                    notifyRemoteState(state = ServiceState.STOPPED)
                    updateTileState()
                }

                synchronized(this@ProxyOnlyService) {
                    isStopping = false
                    stopSelfRequested = false
                    cleanupJob = null
                }
            }
        }
        cleanupJob = job
        return job
    }

    private suspend fun waitForCleanupJob() {
        val job = cleanupJob
        if (job != null && job.isActive) {
            Log.i(TAG, "Waiting for previous cleanup to complete...")
            val waitStart = SystemClock.elapsedRealtime()
            job.join()
            Log.i(TAG, "Previous cleanup completed in ${SystemClock.elapsedRealtime() - waitStart}ms")
        }
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
            manuallyStopped = false
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
            val notification = createProxyOnlyNotification(CHANNEL_ID)
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
        val notification = createProxyOnlyNotification(CHANNEL_ID)
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

    override fun onDestroy() {
        val shouldClearRuntimeState = shouldClearRuntimeStateOnDestroy(
            isRunning = isRunning,
            isStarting = isStarting,
            isStopping = isStopping,
            pending = VpnStateStore.getPending(),
            mode = VpnStateStore.getMode()
        )
        val serverToClose = commandServer
        commandServer = null
        startJob?.cancel()
        startJob = null
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
        hasForegroundStarted.set(false)
        suppressNotificationUpdates = true

        if (shouldClearRuntimeState) {
            clearRuntimeStateOnDestroy()
        }

        runCatching {
            serverToClose?.closeService()
            serverToClose?.close()
        }.onFailure { e ->
            Log.w(TAG, "Failed to close proxy-only CommandServer on destroy", e)
        }

        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }

        runCatching { serviceSupervisorJob.cancel() }
        runCatching { cleanupSupervisorJob.cancel() }
        super.onDestroy()
    }
}
