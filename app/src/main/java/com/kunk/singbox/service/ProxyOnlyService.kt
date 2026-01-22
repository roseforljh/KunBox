package com.kunk.singbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.MainActivity
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.utils.NetworkClient
import com.kunk.singbox.repository.RuleSetRepository
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

class ProxyOnlyService : Service() {

    companion object {
        private const val TAG = "ProxyOnlyService"
        private const val NOTIFICATION_ID = 11
        private const val CHANNEL_ID = "singbox_proxy_silent"
        private const val LEGACY_CHANNEL_ID = "singbox_proxy"

        const val ACTION_START = SingBoxService.ACTION_START
        const val ACTION_STOP = SingBoxService.ACTION_STOP
        const val ACTION_SWITCH_NODE = SingBoxService.ACTION_SWITCH_NODE
        const val ACTION_PREPARE_RESTART = SingBoxService.ACTION_PREPARE_RESTART
        const val EXTRA_CONFIG_PATH = SingBoxService.EXTRA_CONFIG_PATH

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isStarting = false
            private set

        private val _lastErrorFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val lastErrorFlow = _lastErrorFlow.asStateFlow()

        private fun setLastError(message: String?) {
            _lastErrorFlow.value = message
            if (!message.isNullOrBlank()) {
                runCatching {
                    LogRepository.getInstance().addLog("ERROR ProxyOnlyService: $message")
                }
            }
        }
    }

    private var boxService: BoxService? = null

    private val notificationUpdateDebounceMs: Long = 900L
    private val lastNotificationUpdateAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var notificationUpdateJob: Job? = null
    @Volatile private var suppressNotificationUpdates = false

    // 华为设备修复: 追踪是否已经调用过 startForeground(),避免重复调用触发提示音
    private val hasForegroundStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    private val serviceSupervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceSupervisorJob)
    private val cleanupSupervisorJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupSupervisorJob)

    @Volatile private var isStopping: Boolean = false
    @Volatile private var stopSelfRequested: Boolean = false
    @Volatile private var startJob: Job? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentInterfaceListener: InterfaceUpdateListener? = null

    private val uidToPackageCache = ConcurrentHashMap<Int, String>()
    private val maxUidToPackageCacheSize: Int = 512

    private fun cacheUidToPackage(uid: Int, pkg: String) {
        if (uid <= 0 || pkg.isBlank()) return
        uidToPackageCache[uid] = pkg
        if (uidToPackageCache.size > maxUidToPackageCacheSize) {
            uidToPackageCache.clear()
        }
    }

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
        ): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

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
            if (sourceIp == null || sourcePort <= 0 || destinationIp == null || destinationPort <= 0) return 0

            return try {
                val cm = connectivityManager ?: getSystemService(ConnectivityManager::class.java) ?: return 0
                val protocol = ipProtocol
                val uid = cm.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(sourceIp, sourcePort),
                    InetSocketAddress(destinationIp, destinationPort)
                )
                if (uid > 0) uid else 0
            } catch (_: Exception) {
                0
            }
        }

        override fun packageNameByUid(uid: Int): String {
            if (uid <= 0) return ""
            return try {
                val pkgs = packageManager.getPackagesForUid(uid)
                if (!pkgs.isNullOrEmpty()) {
                    pkgs[0]
                } else {
                    val name = runCatching { packageManager.getNameForUid(uid) }.getOrNull().orEmpty()
                    if (name.isNotBlank()) {
                        cacheUidToPackage(uid, name)
                        name
                    } else {
                        uidToPackageCache[uid] ?: ""
                    }
                }
            } catch (_: Exception) {
                val name = runCatching { packageManager.getNameForUid(uid) }.getOrNull().orEmpty()
                if (name.isNotBlank()) {
                    cacheUidToPackage(uid, name)
                    name
                } else {
                    uidToPackageCache[uid] ?: ""
                }
            }
        }

        override fun uidByPackageName(packageName: String?): Int {
            if (packageName.isNullOrBlank()) return 0
            return try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val uid = appInfo.uid
                if (uid > 0) uid else 0
            } catch (_: Exception) {
                0
            }
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            currentInterfaceListener = listener
            connectivityManager = getSystemService(ConnectivityManager::class.java)

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateDefaultInterface(network)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
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

            runCatching {
                connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            }

            val activeNet = connectivityManager?.activeNetwork
            if (activeNet != null) {
                updateDefaultInterface(activeNet)
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

        override fun writeLog(message: String?) {
            if (message.isNullOrBlank()) return
            if (Log.isLoggable(TAG, Log.DEBUG)) {
            }
            LogRepository.getInstance().addLog(message)
        }
    }

    private class StringIteratorImpl(private val list: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): String = list[index++]
        override fun len(): Int = list.size
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        runCatching {
            LogRepository.getInstance().addLog("INFO ProxyOnlyService: onStartCommand action=${intent?.action}")
        }

        when (intent?.action) {
            ACTION_START -> {
                VpnTileService.persistVpnPending(applicationContext, "starting")
                var configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)

                // P0 Optimization: If config path is missing, generate it inside Service
                if (configPath == null) {
                    Log.i(TAG, "ACTION_START received without config path, generating config...")
                    serviceScope.launch {
                        try {
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
                                withContext(Dispatchers.Main) { stopSelf() }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error generating config in Service", e)
                            setLastError("Error generating config: ${e.message}")
                            withContext(Dispatchers.Main) { stopSelf() }
                        }
                    }
                    return START_NOT_STICKY
                }

                if (!configPath.isNullOrBlank()) {
                    startCore(configPath)
                }
            }
            ACTION_STOP -> {
                VpnTileService.persistVpnPending(applicationContext, "stopping")
                stopCore(stopService = true)
            }
            ACTION_SWITCH_NODE -> {
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (!configPath.isNullOrBlank()) {
                    stopCore(stopService = false)
                    serviceScope.launch {
                        delay(350)
                        startCore(configPath)
                    }
                } else {
                    serviceScope.launch {
                        val repo = ConfigRepository.getInstance(this@ProxyOnlyService)
                        val generationResult = repo.generateConfigFile()
                        if (generationResult?.path.isNullOrBlank()) return@launch
                        stopCore(stopService = false)
                        delay(350)
                        startCore(generationResult!!.path)
                    }
                }
            }
            ACTION_PREPARE_RESTART -> {
                // 跨配置切换预清理机制
                // ProxyOnlyService 模式下：唤醒核心 + 重置网络 + 关闭连接
                // 2025-fix: 简化流程，减少过度的重置次数
                Log.i(TAG, "Received ACTION_PREPARE_RESTART -> preparing for restart")
                serviceScope.launch {
                    try {
                        // Step 1: 唤醒核心
                        boxService?.wake()
                        Log.i(TAG, "[PrepareRestart] Step 1/3: Woke up core")

                        // Step 2: 重置网络栈，让 libbox 更新路由信息
                        Log.i(TAG, "[PrepareRestart] Step 2/3: Reset network stack")
                        delay(50)
                        try {
                            boxService?.resetNetwork()
                        } catch (e: Exception) {
                            Log.w(TAG, "resetNetwork failed: ${e.message}")
                        }

                        // Step 3: 关闭所有连接
                        Log.i(TAG, "[PrepareRestart] Step 3/3: Close connections")
                        boxService?.let { service ->
                            try {
                                val method = service.javaClass.methods.find {
                                    it.name == "closeConnections" && it.parameterCount == 0
                                }
                                method?.invoke(service)
                            } catch (e: Exception) {
                                Log.w(TAG, "closeConnections failed: ${e.message}")
                            }
                        }

                        Log.i(TAG, "[PrepareRestart] Complete")
                    } catch (e: Exception) {
                        Log.e(TAG, "PrepareRestart error", e)
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startCore(configPath: String) {
        synchronized(this) {
            if (isRunning || isStarting) return
            if (isStopping) return
            isStarting = true
        }

        setLastError(null)

        notifyRemoteState(state = SingBoxService.ServiceState.STARTING)
        updateTileState()

        try {
            startForeground(NOTIFICATION_ID, createNotification())
            hasForegroundStarted.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call startForeground", e)
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
                    withContext(Dispatchers.Main) { stopSelf() }
                    return@launch
                }

                val configContent = configFile.readText()

                runCatching {
                    SingBoxCore.ensureLibboxSetup(this@ProxyOnlyService)
                }

                boxService = Libbox.newService(configContent, platformInterface)
                boxService?.start()

                isRunning = true
                NetworkClient.onVpnStateChanged(true)
                VpnTileService.persistVpnState(applicationContext, true)
                VpnStateStore.setMode(VpnStateStore.CoreMode.PROXY)
                VpnTileService.persistVpnPending(applicationContext, "")
                setLastError(null)
                notifyRemoteState(state = SingBoxService.ServiceState.RUNNING)
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
                    notifyRemoteState(state = SingBoxService.ServiceState.STOPPED)
                    stopCore(stopService = true)
                }
            } finally {
                isStarting = false
                startJob = null
            }
        }
    }

    private fun stopCore(stopService: Boolean) {
        synchronized(this) {
            stopSelfRequested = stopSelfRequested || stopService
            if (isStopping) return
            isStopping = true
        }

        notifyRemoteState(state = SingBoxService.ServiceState.STOPPING)
        updateTileState()
        isRunning = false
        NetworkClient.onVpnStateChanged(false)

        val jobToJoin = startJob
        startJob = null
        jobToJoin?.cancel()

        val serviceToClose = boxService
        boxService = null

        cleanupScope.launch(NonCancellable) {
            try {
                jobToJoin?.join()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close command server", e)
            }

            runCatching {
                try { serviceToClose?.close() } catch (e: Exception) { Log.w(TAG, "Failed to close box service", e) }
            }

            withContext(Dispatchers.Main) {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                if (stopSelfRequested) {
                    stopSelf()
                }
                VpnTileService.persistVpnState(applicationContext, false)
                VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
                VpnTileService.persistVpnPending(applicationContext, "")
                notifyRemoteState(state = SingBoxService.ServiceState.STOPPED)
                updateTileState()
            }

            synchronized(this@ProxyOnlyService) {
                isStopping = false
                stopSelfRequested = false
            }
        }

        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
        hasForegroundStarted.set(false)
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

    private fun notifyRemoteState(state: SingBoxService.ServiceState? = null) {
        val st = state ?: if (isRunning) SingBoxService.ServiceState.RUNNING else SingBoxService.ServiceState.STOPPED
        val repo = runCatching { ConfigRepository.getInstance(applicationContext) }.getOrNull()
        val activeId = repo?.activeNodeId?.value
        // 2025-fix: 优先使用 VpnStateStore.getActiveLabel()，然后回退到 configRepository
        val activeLabel = runCatching {
            VpnStateStore.getActiveLabel().takeIf { it.isNotBlank() }
                ?: if (repo != null && activeId != null) repo.nodes.value.find { it.id == activeId }?.name else ""
        }.getOrNull().orEmpty()

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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            try {
                manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete legacy notification channel", e)
            }

            val channel = NotificationChannel(
                CHANNEL_ID,
                "SingBox Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("KunBox")
                .setContentText("Proxy-only running")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("KunBox")
                .setContentText("Proxy-only running")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification() {
        val notification = createNotification()
        val manager = getSystemService(NotificationManager::class.java)
        if (!hasForegroundStarted.get()) {
            runCatching {
                startForeground(NOTIFICATION_ID, notification)
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

    override fun onDestroy() {
        super.onDestroy()
        runCatching { serviceSupervisorJob.cancel() }
        runCatching { cleanupSupervisorJob.cancel() }
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
        hasForegroundStarted.set(false)
    }
}
