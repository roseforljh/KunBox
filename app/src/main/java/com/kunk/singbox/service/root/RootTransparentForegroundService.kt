package com.kunk.singbox.service.root

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.DataPlaneReadinessSnapshot
import com.kunk.singbox.ipc.DataPlaneStatus
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.PerAppVpnPolicy
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.service.resolveNotificationNodeLabel
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.manager.VpnStopInitiator
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.service.notification.NotificationActionConfig
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.utils.NetworkClient
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RootTransparentForegroundService : Service() {
    companion object {
        private const val TAG = "RootTransparentService"
        private const val CHANNEL_ID = "root_transparent"
        private const val NOTIFICATION_ID = 12
        const val ACTION_START = "com.kunk.singbox.action.ROOT_START"
        const val ACTION_STOP = "com.kunk.singbox.action.ROOT_STOP"
        const val ACTION_RESTART = "com.kunk.singbox.action.ROOT_RESTART"
        const val ACTION_RESET_CONNECTIONS = "com.kunk.singbox.action.ROOT_RESET_CONNECTIONS"
        const val ACTION_SWITCH_NODE = "com.kunk.singbox.action.ROOT_SWITCH_NODE"
        const val EXTRA_CONFIG_PATH = "config_path"
        const val EXTRA_OUTBOUND_TAG = "outbound_tag"
        const val EXTRA_NODE_NAME = "node_name"
        const val EXTRA_APP_ROUTE_REQUEST_ID = "app_route_request_id"
        const val EXTRA_CONFIG_DIGEST = "config_digest"
        const val EXTRA_APP_ROUTING_DIGEST = "app_routing_digest"

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var isStarting: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private lateinit var rootConnection: RootServiceConnection
    private lateinit var commandManager: CommandManager
    private lateinit var autoFailover: RootAutoFailoverController
    private lateinit var rootNotificationManager: VpnNotificationManager
    private var monitorJob: Job? = null
    private val controlRecoveryScheduled = AtomicBoolean(false)
    private val notificationNodeSwitchInFlight = AtomicBoolean(false)
    private var runtimeSessionId: String = ""
    @Volatile private var startAbortRequested = false
    @Volatile private var lastRootSnapshot = RootRuntimeSnapshot()
    @Volatile private var showNotificationSpeed = true
    @Volatile private var currentUploadSpeed = 0L
    @Volatile private var currentDownloadSpeed = 0L

    override fun onCreate() {
        super.onCreate()
        rootNotificationManager = VpnNotificationManager(
            context = this,
            serviceScope = serviceScope,
            notificationId = NOTIFICATION_ID,
            channelId = CHANNEL_ID,
            channelName = getString(R.string.root_mode_channel),
            actions = NotificationActionConfig(
                serviceClass = RootTransparentForegroundService::class.java,
                switchNodeAction = ACTION_SWITCH_NODE,
                resetConnectionsAction = ACTION_RESET_CONNECTIONS,
                stopAction = ACTION_STOP
            )
        ).also(VpnNotificationManager::createNotificationChannel)
        rootConnection = RootServiceConnection(this, ::onRootServiceDisconnected)
        commandManager = CommandManager(this, serviceScope).apply {
            init(object : CommandManager.Callbacks {
                override fun requestNotificationUpdate(force: Boolean) =
                    this@RootTransparentForegroundService.requestNotificationUpdate(force)

                override fun resolveEgressNodeName(tagOrSelector: String?): String? = tagOrSelector

                override fun onRuntimeNodeChanged(nodeName: String) {
                    VpnStateStore.setActiveLabel(nodeName)
                    SingBoxIpcHub.update(activeLabel = nodeName)
                    this@RootTransparentForegroundService.requestNotificationUpdate(force = true)
                }

                override fun onTrafficUpdate(snapshot: TrafficMonitor.TrafficSnapshot) {
                    currentUploadSpeed = snapshot.uploadSpeed
                    currentDownloadSpeed = snapshot.downloadSpeed
                    if (showNotificationSpeed) {
                        this@RootTransparentForegroundService.requestNotificationUpdate(force = false)
                    }
                }

                override fun onControlChannelHealth(ready: Boolean) {
                    SingBoxIpcHub.updateReadiness { it.copy(selectorReady = ready) }
                }

                override fun onControlChannelRecoveryRequired(reason: String) =
                    scheduleControlChannelRecovery(reason)

                override fun onServiceStop() {
                    serviceScope.launch { stopRuntime(stopSelfAfter = true) }
                }

                override fun onServiceReload() = Unit
            })
        }
        autoFailover = RootAutoFailoverController(
            context = this,
            scope = serviceScope,
            commandManager = commandManager,
            rootService = { rootConnection.service }
        ) { groupTag, targetTag ->
            if (groupTag == "PROXY") {
                VpnStateStore.setActiveLabel(targetTag)
                SingBoxIpcHub.update(activeLabel = targetTag)
            }
            updateNotification()
        }
        commandManager.setKernelLogObserver(autoFailover::onKernelLog)
        serviceScope.launch {
            SettingsRepository.getInstance(this@RootTransparentForegroundService)
                .settings
                .map { it.showNotificationSpeed }
                .distinctUntilChanged()
                .collect { enabled ->
                    showNotificationSpeed = enabled
                    if (isRunning) requestNotificationUpdate(force = true)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SWITCH_NODE -> serviceScope.launch {
                val outboundTag = intent.getStringExtra(EXTRA_OUTBOUND_TAG).orEmpty()
                if (outboundTag.isBlank()) {
                    switchNextNodeFromNotification()
                } else {
                    switchNode(
                        outboundTag,
                        intent.getStringExtra(EXTRA_NODE_NAME).orEmpty(),
                        intent.getStringExtra(EXTRA_CONFIG_PATH)
                    )
                }
            }
            ACTION_RESET_CONNECTIONS -> serviceScope.launch { resetConnectionsFromNotification() }
            ACTION_STOP -> {
                val initiator = VpnStopInitiator.fromWireValue(
                    intent.getStringExtra(SingBoxService.EXTRA_STOP_INITIATOR)
                )
                VpnStateStore.setManuallyStopped(initiator.isManualStop)
                SingBoxIpcHub.update(manuallyStopped = initiator.isManualStop)
                if (isStarting) {
                    startAbortRequested = true
                    rootConnection.stopRootService()
                }
                serviceScope.launch { stopRuntime(stopSelfAfter = true) }
            }
            ACTION_RESTART -> serviceScope.launch {
                restartRuntime(
                    configPathOverride = intent.getStringExtra(EXTRA_CONFIG_PATH),
                    requestId = intent.getStringExtra(EXTRA_APP_ROUTE_REQUEST_ID).orEmpty(),
                    expectedConfigDigest = intent.getStringExtra(EXTRA_CONFIG_DIGEST).orEmpty(),
                    expectedAppRoutingDigest = intent.getStringExtra(EXTRA_APP_ROUTING_DIGEST).orEmpty()
                )
            }
            else -> serviceScope.launch { startRuntime(intent?.getStringExtra(EXTRA_CONFIG_PATH)) }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        autoFailover.stop()
        commandManager.stop()
        rootConnection.unbind()
        serviceScope.cancel()
        isRunning = false
        isStarting = false
        super.onDestroy()
    }

    @Suppress("CognitiveComplexMethod", "LongMethod")
    private suspend fun startRuntime(configPathOverride: String?) = lifecycleMutex.withLock {
        startRuntimeLocked(configPathOverride)
    }

    @Suppress("CognitiveComplexMethod", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun startRuntimeLocked(
        configPathOverride: String?,
        requestId: String = "",
        expectedConfigDigest: String = "",
        expectedAppRoutingDigest: String = ""
    ) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var phaseStartedAt = startedAt
        try {
            if (isRunning || isStarting) return
            startAbortRequested = false
            isStarting = true
            startForegroundCompat(getString(R.string.root_mode_starting))
            VpnStateStore.setPending("starting")
            VpnStateStore.setManuallyStopped(false)
            SingBoxIpcHub.update(
                state = ServiceState.STARTING,
                lastError = "",
                manuallyStopped = false,
                readiness = rootReadiness(DataPlaneStatus.STARTING, "root_binding")
            )
            val settingsRepository = SettingsRepository.getInstance(this)
            settingsRepository.reloadFromStorage()
            val settings = settingsRepository.settings.value
            showNotificationSpeed = settings.showNotificationSpeed
            check(settings.resolvedTrafficCaptureMode() == TrafficCaptureMode.ROOT_TRANSPARENT) {
                "Root transparent mode is not selected"
            }
            val configPath = configPathOverride?.takeIf(String::isNotBlank)
                ?: ConfigRepository.getInstance(this).generateConfigFile()?.path
                ?: error("Failed to generate Root transparent config")
            val policy = PerAppVpnPolicy.from(settings)
            val actualConfigDigest = ConfigRepository.sha256(File(configPath).readText(Charsets.UTF_8))
            val actualAppRoutingDigest = ConfigRepository.appRoutingDigest(settings)
            check(expectedConfigDigest.isBlank() || expectedConfigDigest == actualConfigDigest) {
                "Root candidate config digest mismatch"
            }
            check(expectedAppRoutingDigest.isBlank() || expectedAppRoutingDigest == actualAppRoutingDigest) {
                "Root app routing digest mismatch"
            }
            logStartPhase("config", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            val rootService = rootConnection.bind()
            logStartPhase("bind", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            runtimeSessionId = UUID.randomUUID().toString()
            val ipMode = settings.ipVersionMode
            val rootSnapshot = RootRuntimeSnapshot.fromBundle(
                rootService.start(
                    configPath,
                    runtimeSessionId,
                    settings.vpnAppMode.name,
                    PerAppVpnPolicy.parsePackageNames(settings.vpnAllowlist).toTypedArray(),
                    PerAppVpnPolicy.parsePackageNames(settings.vpnBlocklist).toTypedArray(),
                    packageName,
                    // Redirect sockets belong to Root, so procfs reports UID 0 instead of the originating app.
                    true,
                    applicationInfo.uid,
                    ipMode != IpVersionMode.IPV6_ONLY,
                    ipMode != IpVersionMode.IPV4_ONLY,
                    ipMode == IpVersionMode.IPV6_ONLY,
                    ipMode == IpVersionMode.IPV4_ONLY,
                    settings.blockQuic,
                    applicationInfo.sourceDir
                )
            )
            lastRootSnapshot = rootSnapshot
            logStartPhase("root_runtime", phaseStartedAt)
            Log.i(TAG, "[ROOT_START] inner=${rootSnapshot.startupTimings}")
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            check(rootSnapshot.phase == RootRuntimePhase.RUNNING) {
                rootSnapshot.error.ifBlank { "Root runtime failed to enter RUNNING" }
            }
            check(
                NodeProtectionStore.activateStagedRuntimeMappings(
                    requestId,
                    File(configPath).readText(Charsets.UTF_8)
                )
            ) {
                "Root candidate runtime node mappings could not be activated"
            }

            SingBoxCore.ensureLibboxSetup(this)
            commandManager.startClientsWithFd { rootService.openCommandConnection() }.getOrThrow()
            logStartPhase("command_ready", phaseStartedAt)
            logStartPhase("total", startedAt)
            SelectorManager.updateCommandClient(commandManager.getCommandClient())
            recordSelector(configPath)
            VpnStateStore.clearAutoFailoverRuntimeState()
            VpnStateStore.commitAppliedPerAppPolicy(
                VpnStateStore.AppliedPerAppPolicySnapshot(
                    revision = policy.revision,
                    mode = policy.mode.name,
                    digest = policy.digest(),
                    capturedCount = if (policy.mode == com.kunk.singbox.model.VpnAppMode.ALLOWLIST) {
                        policy.allowlist.size
                    } else {
                        0
                    },
                    excludedCount = if (policy.mode == com.kunk.singbox.model.VpnAppMode.BLOCKLIST) {
                        policy.blocklist.size
                    } else {
                        0
                    },
                    appliedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
                    serviceInstanceId = SingBoxIpcHub.serviceInstanceId(),
                    runtimeGeneration = rootSnapshot.generation,
                    requestId = requestId,
                    configDigest = actualConfigDigest,
                    appRoutingDigest = actualAppRoutingDigest
                )
            )
            isRunning = true
            isStarting = false
            VpnStateStore.setMode(VpnStateStore.CoreMode.ROOT)
            VpnStateStore.setActive(true)
            VpnStateStore.setPending("")
            VpnTileService.persistVpnState(true)
            NetworkClient.onVpnStateChanged(true)
            SingBoxIpcHub.update(
                state = ServiceState.RUNNING,
                activeLabel = VpnStateStore.getActiveLabel(),
                lastError = "",
                manuallyStopped = false,
                readiness = rootReadiness(DataPlaneStatus.READY, "root_tproxy_ready")
            )
            updateNotification()
            startMonitor()
        } catch (error: Exception) {
            if (startAbortRequested) {
                Log.i(TAG, "Root transparent startup aborted by stop request")
                isStarting = false
                return
            }
            Log.e(TAG, "Root transparent startup failed", error)
            val stopped = runCatching {
                rootConnection.service?.stop(runtimeSessionId)?.let(RootRuntimeSnapshot::fromBundle)
            }.getOrNull()
            if (stopped != null) {
                lastRootSnapshot = stopped
            } else if (runtimeSessionId.isNotBlank()) {
                lastRootSnapshot = RootRuntimeSnapshot(
                    phase = RootRuntimePhase.FAILED_RULES_PRESENT,
                    runtimeSessionId = runtimeSessionId,
                    rulesInstalled = true,
                    error = "Root startup cleanup could not be confirmed"
                )
            }
            val cleanupFailed = lastRootSnapshot.phase == RootRuntimePhase.FAILED_RULES_PRESENT
            isRunning = false
            isStarting = false
            VpnStateStore.setActive(false)
            VpnStateStore.setPending("")
            VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
            SingBoxIpcHub.update(
                state = ServiceState.STOPPED,
                lastError = error.message ?: "Root transparent startup failed",
                readiness = rootReadiness(
                    if (cleanupFailed) DataPlaneStatus.FAILED_BLOCKED else DataPlaneStatus.FAILED_UNPROTECTED,
                    if (cleanupFailed) "root_rules_present" else "root_start_failed"
                )
            )
            rootConnection.unbind()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun logStartPhase(phase: String, startedAt: Long) {
        Log.i(TAG, "[ROOT_START] phase=$phase duration_ms=${android.os.SystemClock.elapsedRealtime() - startedAt}")
    }

    @Suppress("LongMethod")
    private suspend fun stopRuntime(stopSelfAfter: Boolean) = lifecycleMutex.withLock {
        stopRuntimeLocked(stopSelfAfter)
    }

    @Suppress("LongMethod")
    private suspend fun stopRuntimeLocked(stopSelfAfter: Boolean) {
        try {
            monitorJob?.cancel()
            monitorJob = null
            autoFailover.stop()
            SingBoxIpcHub.update(
                state = ServiceState.STOPPING,
                readiness = rootReadiness(DataPlaneStatus.BLOCKING, "root_cleanup")
            )
            commandManager.stop()
            SelectorManager.clear()
            val stopped = if (runtimeSessionId.isBlank() || startAbortRequested) {
                RootRuntimeSnapshot(phase = RootRuntimePhase.STOPPED)
            } else runCatching {
                val rootService = rootConnection.service
                    ?: error("RootService disconnected before cleanup confirmation")
                RootRuntimeSnapshot.fromBundle(rootService.stop(runtimeSessionId))
            }.getOrElse { error ->
                RootRuntimeSnapshot(
                    phase = RootRuntimePhase.FAILED_RULES_PRESENT,
                    runtimeSessionId = runtimeSessionId,
                    rulesInstalled = true,
                    error = error.message ?: "Root cleanup could not be confirmed"
                )
            }
            lastRootSnapshot = stopped
            rootConnection.unbind()
            val cleanupFailed = stopped.phase == RootRuntimePhase.FAILED_RULES_PRESENT
            isRunning = false
            isStarting = false
            startAbortRequested = false
            runtimeSessionId = ""
            VpnStateStore.setActive(false)
            VpnStateStore.setPending("")
            VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
            VpnTileService.persistVpnState(false)
            NetworkClient.onVpnStateChanged(false)
            SingBoxIpcHub.update(
                state = ServiceState.STOPPED,
                activeLabel = "",
                lastError = stopped.error.takeIf { cleanupFailed }.orEmpty(),
                readiness = rootReadiness(
                    if (cleanupFailed) DataPlaneStatus.FAILED_BLOCKED else DataPlaneStatus.STOPPED,
                    if (cleanupFailed) "root_rules_present" else "root_stopped"
                )
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (stopSelfAfter) stopSelf()
        } catch (error: Exception) {
            Log.e(TAG, "Root transparent stop failed", error)
            lastRootSnapshot = RootRuntimeSnapshot(
                phase = RootRuntimePhase.FAILED_RULES_PRESENT,
                runtimeSessionId = runtimeSessionId,
                rulesInstalled = true,
                error = error.message ?: "Root cleanup could not be confirmed"
            )
            isRunning = false
            isStarting = false
            VpnStateStore.setActive(false)
            VpnStateStore.setPending("")
            VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
            SingBoxIpcHub.update(
                state = ServiceState.STOPPED,
                lastError = lastRootSnapshot.error,
                readiness = rootReadiness(DataPlaneStatus.FAILED_BLOCKED, "root_cleanup_unconfirmed")
            )
            rootConnection.unbind()
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (stopSelfAfter) stopSelf()
        }
    }

    private suspend fun restartRuntime(
        configPathOverride: String?,
        requestId: String = "",
        expectedConfigDigest: String = "",
        expectedAppRoutingDigest: String = ""
    ) = lifecycleMutex.withLock {
        stopRuntimeLocked(stopSelfAfter = false)
        startRuntimeLocked(configPathOverride, requestId, expectedConfigDigest, expectedAppRoutingDigest)
    }

    private fun scheduleControlChannelRecovery(reason: String) {
        if (!isRunning || isStarting || VpnStateStore.isManuallyStopped()) return
        if (!controlRecoveryScheduled.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                Log.w(TAG, "Root command channel unhealthy, restarting runtime: $reason")
                restartRuntime(configPathOverride = null)
            } finally {
                controlRecoveryScheduled.set(false)
            }
        }
    }

    private fun recordSelector(configPath: String) {
        val config = runCatching { Gson().fromJson(File(configPath).readText(), SingBoxConfig::class.java) }.getOrNull()
        val proxy = config?.outbounds.orEmpty().firstOrNull { it.tag == "PROXY" }
        SelectorManager.recordSelectorSignature(proxy?.outbounds.orEmpty())
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isRunning) {
                delay(1_000)
                val rootSnapshot = RootRuntimeSnapshot.fromBundle(rootConnection.service?.snapshot)
                lastRootSnapshot = rootSnapshot
                if (rootSnapshot.phase != RootRuntimePhase.RUNNING) {
                    val reason = rootSnapshot.error.ifBlank { "Root runtime left RUNNING" }
                    SingBoxIpcHub.update(
                        lastError = reason,
                        readiness = rootReadiness(DataPlaneStatus.FAILED_UNPROTECTED, "root_runtime_lost")
                    )
                    stopRuntime(stopSelfAfter = true)
                    break
                }
            }
        }
    }

    private fun onRootServiceDisconnected() {
        if (!isRunning && !isStarting) return
        serviceScope.launch {
            SingBoxIpcHub.update(
                lastError = "RootService disconnected",
                readiness = rootReadiness(DataPlaneStatus.FAILED_UNPROTECTED, "root_binder_died")
            )
            stopRuntime(stopSelfAfter = true)
        }
    }

    private suspend fun switchNode(
        outboundTag: String,
        nodeName: String,
        fallbackConfigPath: String?
    ) = lifecycleMutex.withLock {
        if (outboundTag.isBlank()) return@withLock
        when (SelectorManager.switchNode(outboundTag)) {
            is SelectorManager.SwitchResult.Success -> {
                commandManager.closeConnections()
                rootConnection.service?.resetNetwork()
                if (nodeName.isNotBlank()) {
                    VpnStateStore.setActiveLabel(nodeName)
                    SingBoxIpcHub.update(activeLabel = nodeName)
                }
                updateNotification()
            }
            is SelectorManager.SwitchResult.NeedRestart -> {
                stopRuntimeLocked(stopSelfAfter = false)
                startRuntimeLocked(fallbackConfigPath)
            }
        }
    }

    private fun rootReadiness(status: DataPlaneStatus, reason: String): DataPlaneReadinessSnapshot =
        DataPlaneReadinessSnapshot(
            status = status,
            tunEstablished = false,
            systemVpnTransport = false,
            coreReady = status == DataPlaneStatus.READY,
            selectorReady = status == DataPlaneStatus.READY && commandManager.isControlChannelReady(),
            routingScope = "root_tproxy",
            rootPid = lastRootSnapshot.rootPid,
            rootFdCount = lastRootSnapshot.rootFdCount,
            rootRuntimeSessionId = lastRootSnapshot.runtimeSessionId,
            rootRuleRevision = lastRootSnapshot.ruleRevision,
            rootWatchdogReady = lastRootSnapshot.watchdogReady,
            rootRulesInstalled = lastRootSnapshot.rulesInstalled,
            lastReadinessReason = reason
        )

    private fun startForegroundCompat(text: String) {
        val notification = rootNotificationManager.createStartingNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        rootNotificationManager.markForegroundStarted()
    }

    private fun updateNotification() {
        requestNotificationUpdate(force = true)
    }

    private fun requestNotificationUpdate(force: Boolean) {
        rootNotificationManager.requestNotificationUpdate(buildNotificationState(), this, force)
    }

    private fun buildNotificationState(): VpnNotificationManager.NotificationState {
        val repository = ConfigRepository.getInstance(applicationContext)
        val selectedNodeId = repository.activeNodeId.value
        val nodeName = resolveNotificationNodeLabel(
            selectedNodeName = repository.nodes.value.find { it.id == selectedNodeId }?.name,
            selectedNodeStoreLabel = VpnStateStore.getSelectedNodeLabel(),
            runtimeNodeName = commandManager.realTimeNodeName ?: VpnStateStore.getActiveLabel()
        )
        return VpnNotificationManager.NotificationState(
            isRunning = isRunning,
            activeNodeName = nodeName,
            showSpeed = showNotificationSpeed,
            uploadSpeed = currentUploadSpeed,
            downloadSpeed = currentDownloadSpeed,
            dataPlaneStatus = SingBoxIpcHub.currentReadiness().status
        )
    }

    private suspend fun switchNextNodeFromNotification() {
        if (!isRunning || !notificationNodeSwitchInFlight.compareAndSet(false, true)) return
        try {
            val repository = ConfigRepository.getInstance(applicationContext)
            val candidates = repository.nodes.value.filter {
                it.autoSelectionEligible && !it.meteredProtected
            }
            val nextNodeId = nextRootNotificationNodeId(candidates.map { it.id }, repository.activeNodeId.value)
                ?: return
            when (val result = repository.setActiveNodeWithResult(nextNodeId)) {
                ConfigRepository.NodeSwitchResult.Success,
                ConfigRepository.NodeSwitchResult.NotRunning -> requestNotificationUpdate(force = true)
                is ConfigRepository.NodeSwitchResult.Failed -> Log.e(
                    TAG,
                    "Notification node switch failed: ${result.reason}"
                )
            }
        } finally {
            notificationNodeSwitchInFlight.set(false)
        }
    }

    private fun resetConnectionsFromNotification() {
        if (!isRunning) return
        val closed = commandManager.closeConnections()
        val reset = rootConnection.service?.resetNetwork() == true
        LogRepository.getInstance().addLog(
            "INFO: Root notification reset connections closed=$closed resetNetwork=$reset"
        )
        requestNotificationUpdate(force = true)
    }
}

internal fun nextRootNotificationNodeId(candidateIds: List<String>, activeNodeId: String?): String? {
    val candidates = candidateIds.map(String::trim).filter(String::isNotBlank).distinct()
    if (candidates.size < 2) return null
    val currentIndex = candidates.indexOf(activeNodeId)
    return candidates[(currentIndex + 1) % candidates.size]
}
