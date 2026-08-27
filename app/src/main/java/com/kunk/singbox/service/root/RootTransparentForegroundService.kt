package com.kunk.singbox.service.root

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.aidl.IRootSingBoxService
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
import com.kunk.singbox.model.RootAppRoutingCanonical
import com.kunk.singbox.model.RootRoutingArtifactValidator
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.RootGenerationMarker
import com.kunk.singbox.repository.RootGenerationStore
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("LargeClass")
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
        const val EXTRA_SIDECAR_DIGEST = "root_sidecar_digest"
        const val EXTRA_SIDECAR_JSON = "root_sidecar_json"
        const val EXTRA_STATIC_PLAN_DIGEST = "root_static_plan_digest"
        const val EXTRA_ROUTING_GENERATION = "root_routing_generation"

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
    private var uidRefreshRetryJob: Job? = null
    private val controlRecoveryScheduled = AtomicBoolean(false)
    private val notificationNodeSwitchInFlight = AtomicBoolean(false)
    private val uidRefreshScheduled = AtomicBoolean(false)
    private var runtimeSessionId: String = ""
    @Volatile private var startAbortRequested = false
    @Volatile private var lastRootSnapshot = RootRuntimeSnapshot()
    @Volatile private var showNotificationSpeed = true
    @Volatile private var currentUploadSpeed = 0L
    @Volatile private var currentDownloadSpeed = 0L
    private var uidRefreshRetryCount = 0
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleUidRefresh(intent?.action.orEmpty())
        }
    }
    private val userChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleUidRefresh(intent?.action.orEmpty())
        }
    }

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
        registerUidRefreshReceivers()
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
                    expectedAppRoutingDigest = intent.getStringExtra(EXTRA_APP_ROUTING_DIGEST).orEmpty(),
                    expectedSidecarDigest = intent.getStringExtra(EXTRA_SIDECAR_DIGEST).orEmpty(),
                    expectedSidecarJson = intent.getStringExtra(EXTRA_SIDECAR_JSON).orEmpty(),
                    expectedStaticPlanDigest = intent.getStringExtra(EXTRA_STATIC_PLAN_DIGEST).orEmpty(),
                    expectedRoutingGeneration = intent.getLongExtra(EXTRA_ROUTING_GENERATION, 0L)
                )
            }
            else -> serviceScope.launch { startRuntime(intent?.getStringExtra(EXTRA_CONFIG_PATH)) }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        startAbortRequested = true
        monitorJob?.cancel()
        uidRefreshRetryJob?.cancel()
        unregisterUidRefreshReceivers()
        autoFailover.stop()
        commandManager.stop()
        // RootService is a separate process. Unbinding alone can leave its
        // netfilter rules and watchdog alive when destruction races startup.
        runCatching { rootConnection.stopRootService() }
            .onFailure { error -> Log.e(TAG, "Could not stop RootService during foreground destroy", error) }
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
        expectedAppRoutingDigest: String = "",
        expectedSidecarDigest: String = "",
        expectedSidecarJson: String = "",
        expectedStaticPlanDigest: String = "",
        expectedRoutingGeneration: Long = 0L
    ) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var phaseStartedAt = startedAt
        var previousMarker: RootGenerationMarker? = null
        var candidateGeneration: ConfigRepository.ConfigGenerationResult? = null
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
            previousMarker = RootGenerationStore.readCurrentStrict(filesDir)
            settingsRepository.reloadFromStorage()
            val settings = settingsRepository.settings.value
            showNotificationSpeed = settings.showNotificationSpeed
            check(settings.resolvedTrafficCaptureMode() == TrafficCaptureMode.ROOT_TRANSPARENT) {
                "Root transparent mode is not selected"
            }
            val generation = configPathOverride?.takeIf(String::isNotBlank)?.let { path ->
                loadRootGenerationResult(
                    path = path,
                    requestId = requestId,
                    expectedConfigDigest = expectedConfigDigest,
                    expectedSidecarDigest = expectedSidecarDigest,
                    expectedSidecarJson = expectedSidecarJson,
                    expectedStaticPlanDigest = expectedStaticPlanDigest,
                    expectedAppRoutingDigest = expectedAppRoutingDigest,
                    expectedRoutingGeneration = expectedRoutingGeneration
                )
            } ?: ConfigRepository.getInstance(this).generateConfigFile()
                ?: error("Failed to generate Root transparent config")
            candidateGeneration = generation
            val configPath = generation.path
            val policy = PerAppVpnPolicy.from(settings)
            val actualAppRoutingDigest = ConfigRepository.appRoutingDigest(settings)
            check(expectedConfigDigest.isBlank() || expectedConfigDigest == generation.configDigest) {
                "Root candidate config digest mismatch"
            }
            check(
                expectedAppRoutingDigest.isBlank() ||
                    expectedAppRoutingDigest == generation.rootRoutingAppDigest
            ) {
                "Root lane app routing digest mismatch"
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
                    // Explicit Root app routing is bound by inbound lane before libbox sees the socket.
                    false,
                    applicationInfo.uid,
                    ipMode != IpVersionMode.IPV6_ONLY,
                    ipMode != IpVersionMode.IPV4_ONLY,
                    ipMode == IpVersionMode.IPV6_ONLY,
                    ipMode == IpVersionMode.IPV4_ONLY,
                    settings.blockQuic,
                    applicationInfo.sourceDir,
                    generation.configDigest,
                    generation.rootRoutingSidecarDigest,
                    generation.rootRoutingSidecarJson,
                    generation.rootRoutingStaticPlanDigest,
                    generation.rootRoutingAppDigest,
                    generation.rootRoutingGeneration
                )
            )
            lastRootSnapshot = rootSnapshot
            logStartPhase("root_runtime", phaseStartedAt)
            Log.i(TAG, "[ROOT_START] inner=${rootSnapshot.startupTimings}")
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            completeRootRuntime(
                rootService = rootService,
                rootSnapshot = rootSnapshot,
                generation = generation,
                requestId = requestId,
                policy = policy,
                settings = settings,
                appRoutingDigest = actualAppRoutingDigest
            )
            logStartPhase("total", startedAt)
        } catch (error: Exception) {
            restoreRootGenerationAfterFailure(previousMarker, candidateGeneration)
            requestId.takeIf(String::isNotBlank)?.let(NodeProtectionStore::discardStagedRuntimeMappings)
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
                    phase = RootRuntimePhase.FAILED_BLOCKED,
                    runtimeSessionId = runtimeSessionId,
                    rulesInstalled = true,
                    error = "Root startup cleanup could not be confirmed"
                )
            }
            val cleanupFailed = lastRootSnapshot.phase == RootRuntimePhase.FAILED_BLOCKED
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

    @Suppress("LongParameterList", "LongMethod")
    private suspend fun completeRootRuntime(
        rootService: IRootSingBoxService,
        rootSnapshot: RootRuntimeSnapshot,
        generation: ConfigRepository.ConfigGenerationResult,
        requestId: String,
        policy: PerAppVpnPolicy,
        settings: com.kunk.singbox.model.AppSettings,
        appRoutingDigest: String
    ) {
        rootRunningSnapshotError(
            rootSnapshot,
            RootRuntimeExpectation(
                runtimeSessionId = runtimeSessionId,
                routingGeneration = generation.rootRoutingGeneration,
                configFileSha256 = generation.configDigest,
                sidecarFileSha256 = generation.rootRoutingSidecarDigest,
                staticPlanSha256 = generation.rootRoutingStaticPlanDigest,
                appRoutingSha256 = generation.rootRoutingAppDigest,
                tproxyIpv4 = settings.ipVersionMode != IpVersionMode.IPV6_ONLY,
                tproxyIpv6 = settings.ipVersionMode != IpVersionMode.IPV4_ONLY
            )
        )?.let(::error)
        val configContent = File(generation.path).readText(Charsets.UTF_8)
        check(NodeProtectionStore.activateStagedRuntimeMappings(requestId, configContent)) {
            "Root candidate runtime node mappings could not be activated"
        }
        SingBoxCore.ensureLibboxSetup(this)
        commandManager.startClientsWithFd { rootService.openCommandConnection() }.getOrThrow()
        SelectorManager.updateCommandClient(commandManager.getCommandClient())
        recordSelector(generation.path)
        VpnStateStore.clearAutoFailoverRuntimeState()
        check(VpnStateStore.commitAppliedPerAppPolicy(
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
                runtimeGeneration = rootSnapshot.routingGeneration,
                requestId = requestId,
                configDigest = generation.configDigest,
                appRoutingDigest = appRoutingDigest,
                sidecarFileSha256 = generation.rootRoutingSidecarDigest,
                staticPlanSha256 = generation.rootRoutingStaticPlanDigest,
                rootRoutingAppSha256 = generation.rootRoutingAppDigest,
                resolvedPlanSha256 = rootSnapshot.resolvedPlanSha256,
                rootRuntimeSessionId = rootSnapshot.runtimeSessionId
            )
        )) { "Root applied application policy could not be committed" }
        val rootMarker = generation.toRootGenerationMarker()
        check(RootGenerationStore.commit(filesDir, rootMarker, configContent)) {
            "Root generation marker could not be committed"
        }
        RootGenerationStore.writeCompatibilityCaches(filesDir, configContent, rootMarker)
        check(RootGenerationStore.readCurrentStrict(filesDir) == rootMarker)
        check(RootGenerationStore.cacheMatchesCurrent(filesDir, "running_config.json", rootMarker))
        runCatching {
            RootGenerationStore.pruneGenerations(filesDir, setOf(rootMarker.generation))
        }.onFailure { error ->
            Log.w(TAG, "Could not prune stale Root generations", error)
        }
        isRunning = true
        isStarting = false
        VpnStateStore.setMode(VpnStateStore.CoreMode.ROOT)
        VpnStateStore.setActive(true)
        VpnStateStore.setPending("")
        VpnTileService.persistVpnState(true)
        NetworkClient.onVpnStateChanged(true)
        lastRootSnapshot = rootSnapshot
        SingBoxIpcHub.update(
            state = ServiceState.RUNNING,
            activeLabel = VpnStateStore.getActiveLabel(),
            lastError = "",
            manuallyStopped = false,
            readiness = rootReadiness(DataPlaneStatus.READY, "root_tproxy_ready")
        )
        updateNotification()
        startMonitor()
    }

    private fun ConfigRepository.ConfigGenerationResult.toRootGenerationMarker(): RootGenerationMarker =
        RootGenerationStore.marker(
            generation = rootRoutingGeneration,
            configFileSha256 = configDigest,
            sidecarFileSha256 = rootRoutingSidecarDigest,
            staticPlanSha256 = rootRoutingStaticPlanDigest,
            appRoutingSha256 = rootRoutingAppDigest
        )

    private fun restoreRootGenerationAfterFailure(
        previousMarker: RootGenerationMarker?,
        candidate: ConfigRepository.ConfigGenerationResult?
    ) {
        val generation = candidate?.rootRoutingGeneration?.takeIf { it > 0L } ?: return
        runCatching {
            val current = RootGenerationStore.readCurrentStrict(filesDir)
            val lastGood = RootGenerationStore.readLastGoodStrict(filesDir)
            if (current?.generation == generation || lastGood?.generation == generation) {
                check(RootGenerationStore.restorePrevious(filesDir, previousMarker)) {
                    "Cannot restore previous Root generation marker"
                }
                RootGenerationStore.restoreCompatibilityCaches(filesDir, previousMarker)
            }
            val restoredCurrent = RootGenerationStore.readCurrent(filesDir)
            val restoredLastGood = RootGenerationStore.readLastGood(filesDir)
            if (restoredCurrent?.generation != generation && restoredLastGood?.generation != generation) {
                RootGenerationStore.deleteGeneration(filesDir, generation)
            }
        }.onFailure { restoreError ->
            Log.e(TAG, "Root generation rollback failed", restoreError)
        }
    }

    private fun logStartPhase(phase: String, startedAt: Long) {
        Log.i(TAG, "[ROOT_START] phase=$phase duration_ms=${android.os.SystemClock.elapsedRealtime() - startedAt}")
    }

    @Suppress("LongParameterList")
    private fun loadRootGenerationResult(
        path: String,
        requestId: String,
        expectedConfigDigest: String,
        expectedSidecarDigest: String,
        expectedSidecarJson: String,
        expectedStaticPlanDigest: String,
        expectedAppRoutingDigest: String,
        expectedRoutingGeneration: Long
    ): ConfigRepository.ConfigGenerationResult {
        check(expectedRoutingGeneration > 0L) { "Root candidate routing generation is missing" }
        check(
            RootGenerationStore.generationForConfigPath(filesDir, path) == expectedRoutingGeneration
        ) {
            "Root candidate config is outside its generation directory"
        }
        val configFile = File(path).canonicalFile
        check(configFile.isFile) { "Root candidate config does not exist" }
        val configContent = configFile.readText(Charsets.UTF_8)
        val sidecarFile = File(configFile.parentFile, "root-routing.json")
        val manifestFile = File(configFile.parentFile, "manifest.json")
        check(sidecarFile.isFile && manifestFile.isFile) { "Root candidate routing artifacts are incomplete" }
        val sidecarJson = sidecarFile.readText(Charsets.UTF_8)
        val plan = RootRoutingArtifactValidator.requireBoundPlanJson(sidecarJson)
        val manifest = RootRoutingArtifactValidator.requireManifestJson(manifestFile.readText(Charsets.UTF_8))
        val configDigest = ConfigRepository.sha256(configContent)
        val sidecarDigest = ConfigRepository.sha256(sidecarJson)
        check(configDigest == manifest.configFileSha256 && sidecarDigest == manifest.sidecarFileSha256) {
            "Root candidate routing artifact digest mismatch"
        }
        check(plan.configFileSha256 == configDigest) { "Root candidate plan config digest mismatch" }
        check(plan.staticPlanSha256 == RootAppRoutingCanonical.staticPlanSha256(plan)) {
            "Root candidate static plan digest mismatch"
        }
        check(plan.appRoutingSha256 == RootAppRoutingCanonical.appRoutingSha256(plan)) {
            "Root candidate app routing digest mismatch"
        }
        check(expectedConfigDigest.isBlank() || expectedConfigDigest == configDigest)
        check(expectedSidecarDigest.isBlank() || expectedSidecarDigest == sidecarDigest)
        check(expectedSidecarJson.isBlank() || expectedSidecarJson == sidecarJson)
        check(expectedStaticPlanDigest.isBlank() || expectedStaticPlanDigest == plan.staticPlanSha256)
        check(expectedAppRoutingDigest.isBlank() || expectedAppRoutingDigest == plan.appRoutingSha256)
        check(expectedRoutingGeneration == 0L || expectedRoutingGeneration == plan.generation)
        return ConfigRepository.ConfigGenerationResult(
            path = configFile.absolutePath,
            activeNodeTag = null,
            outboundTags = emptySet(),
            requestId = requestId,
            configDigest = configDigest,
            appRoutingDigest = plan.appRoutingSha256,
            rootRoutingSidecarPath = sidecarFile.absolutePath,
            rootRoutingManifestPath = manifestFile.absolutePath,
            rootRoutingSidecarJson = sidecarJson,
            rootRoutingSidecarDigest = sidecarDigest,
            rootRoutingStaticPlanDigest = plan.staticPlanSha256,
            rootRoutingAppDigest = plan.appRoutingSha256,
            rootRoutingGeneration = plan.generation
        )
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
            uidRefreshRetryJob?.cancel()
            uidRefreshRetryJob = null
            uidRefreshScheduled.set(false)
            uidRefreshRetryCount = 0
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
                    phase = RootRuntimePhase.FAILED_BLOCKED,
                    runtimeSessionId = runtimeSessionId,
                    rulesInstalled = true,
                    error = error.message ?: "Root cleanup could not be confirmed"
                )
            }
            lastRootSnapshot = stopped
            val cleanupFailed = stopped.phase == RootRuntimePhase.FAILED_BLOCKED
            if (!cleanupFailed) rootConnection.unbind()
            isRunning = false
            isStarting = false
            startAbortRequested = false
            if (!cleanupFailed) runtimeSessionId = ""
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
            if (stopSelfAfter && !cleanupFailed) stopSelf()
        } catch (error: Exception) {
            Log.e(TAG, "Root transparent stop failed", error)
            lastRootSnapshot = RootRuntimeSnapshot(
                phase = RootRuntimePhase.FAILED_BLOCKED,
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
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (stopSelfAfter) {
                Log.w(TAG, "Root stop remains blocked; keeping service alive for cleanup retry")
            }
        }
    }

    private suspend fun restartRuntime(
        configPathOverride: String?,
        requestId: String = "",
        expectedConfigDigest: String = "",
        expectedAppRoutingDigest: String = "",
        expectedSidecarDigest: String = "",
        expectedSidecarJson: String = "",
        expectedStaticPlanDigest: String = "",
        expectedRoutingGeneration: Long = 0L
    ) = lifecycleMutex.withLock {
        if (isRunning && rootConnection.service != null && lastRootSnapshot.phase == RootRuntimePhase.RUNNING) {
            reloadRuntimeLocked(
                configPathOverride,
                requestId,
                expectedConfigDigest,
                expectedAppRoutingDigest,
                expectedSidecarDigest,
                expectedSidecarJson,
                expectedStaticPlanDigest,
                expectedRoutingGeneration
            )
            return@withLock
        }
        stopRuntimeLocked(stopSelfAfter = false)
        // A blocked cleanup is retried by startRuntimeLocked. The Root
        // process checks ownership and listener absence before accepting the
        // next generation; a persistent conflict remains blocked.
        startRuntimeLocked(
            configPathOverride = configPathOverride,
            requestId = requestId,
            expectedConfigDigest = expectedConfigDigest,
            expectedAppRoutingDigest = expectedAppRoutingDigest,
            expectedSidecarDigest = expectedSidecarDigest,
            expectedSidecarJson = expectedSidecarJson,
            expectedStaticPlanDigest = expectedStaticPlanDigest,
            expectedRoutingGeneration = expectedRoutingGeneration
        )
    }

    @Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
    private suspend fun reloadRuntimeLocked(
        configPathOverride: String?,
        requestId: String,
        expectedConfigDigest: String,
        expectedAppRoutingDigest: String,
        expectedSidecarDigest: String,
        expectedSidecarJson: String,
        expectedStaticPlanDigest: String,
        expectedRoutingGeneration: Long
    ) {
        val previousMarker = RootGenerationStore.readCurrentStrict(filesDir)
        var generation: ConfigRepository.ConfigGenerationResult? = null
        try {
            monitorJob?.cancel()
            monitorJob = null
            autoFailover.stop()
            isStarting = true
            VpnStateStore.setPending("starting")
            SingBoxIpcHub.update(
                state = ServiceState.STARTING,
                lastError = "",
                readiness = rootReadiness(DataPlaneStatus.BLOCKING, "root_cold_reload")
            )
            val settingsRepository = SettingsRepository.getInstance(this)
            settingsRepository.reloadFromStorage()
            val settings = settingsRepository.settings.value
            val candidate = configPathOverride?.takeIf(String::isNotBlank)?.let { path ->
                loadRootGenerationResult(
                    path,
                    requestId,
                    expectedConfigDigest,
                    expectedSidecarDigest,
                    expectedSidecarJson,
                    expectedStaticPlanDigest,
                    expectedAppRoutingDigest,
                    expectedRoutingGeneration
                )
            } ?: ConfigRepository.getInstance(this).generateConfigFile()
                ?: error("Failed to generate Root reload config")
            generation = candidate
            check(candidate.rootRoutingGeneration > (previousMarker?.generation ?: 0L)) {
                "Root reload candidate is not newer than the committed generation"
            }
            val rootService = rootConnection.service ?: error("RootService disconnected before reload")
            commandManager.stop()
            SelectorManager.clear()
            val rootSnapshot = RootRuntimeSnapshot.fromBundle(
                rootService.hotReload(
                    candidate.path,
                    runtimeSessionId,
                    candidate.configDigest,
                    candidate.rootRoutingSidecarDigest,
                    candidate.rootRoutingSidecarJson,
                    candidate.rootRoutingStaticPlanDigest,
                    candidate.rootRoutingAppDigest,
                    candidate.rootRoutingGeneration
                )
            )
            lastRootSnapshot = rootSnapshot
            val snapshotError = rootRunningSnapshotError(
                rootSnapshot,
                RootRuntimeExpectation(
                    runtimeSessionId,
                    candidate.rootRoutingGeneration,
                    candidate.configDigest,
                    candidate.rootRoutingSidecarDigest,
                    candidate.rootRoutingStaticPlanDigest,
                    candidate.rootRoutingAppDigest,
                    settings.ipVersionMode != IpVersionMode.IPV6_ONLY,
                    settings.ipVersionMode != IpVersionMode.IPV4_ONLY
                )
            )
            if (snapshotError != null) {
                if (rootSnapshot.phase == RootRuntimePhase.RUNNING &&
                    rootSnapshot.routingGeneration == previousMarker?.generation
                ) {
                    restoreReloadedPreviousRuntime(rootService, rootSnapshot, snapshotError)
                } else {
                    publishReloadFailure(rootSnapshot, snapshotError)
                }
                restoreRootGenerationAfterFailure(previousMarker, candidate)
                requestId.takeIf(String::isNotBlank)?.let(NodeProtectionStore::discardStagedRuntimeMappings)
                return
            }
            completeRootRuntime(
                rootService,
                rootSnapshot,
                candidate,
                requestId,
                PerAppVpnPolicy.from(settings),
                settings,
                ConfigRepository.appRoutingDigest(settings)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Root cold reload failed", error)
            restoreRootGenerationAfterFailure(previousMarker, generation)
            requestId.takeIf(String::isNotBlank)?.let(NodeProtectionStore::discardStagedRuntimeMappings)
            val rootService = rootConnection.service
            val snapshot = runCatching { RootRuntimeSnapshot.fromBundle(rootService?.snapshot) }
                .getOrDefault(lastRootSnapshot)
            lastRootSnapshot = snapshot
            if (rootService != null && snapshot.phase == RootRuntimePhase.RUNNING &&
                snapshot.routingGeneration == previousMarker?.generation
            ) {
                restoreReloadedPreviousRuntime(rootService, snapshot, error.message ?: "Root reload failed")
            } else {
                publishReloadFailure(snapshot, error.message ?: "Root reload failed")
            }
        }
    }

    private suspend fun restoreReloadedPreviousRuntime(
        rootService: IRootSingBoxService,
        snapshot: RootRuntimeSnapshot,
        reason: String
    ) {
        commandManager.startClientsWithFd { rootService.openCommandConnection() }.getOrThrow()
        SelectorManager.updateCommandClient(commandManager.getCommandClient())
        isRunning = true
        isStarting = false
        VpnStateStore.setMode(VpnStateStore.CoreMode.ROOT)
        VpnStateStore.setActive(true)
        VpnStateStore.setPending("")
        lastRootSnapshot = snapshot
        SingBoxIpcHub.update(
            state = ServiceState.RUNNING,
            lastError = reason,
            readiness = rootReadiness(DataPlaneStatus.READY, "root_reload_rolled_back")
        )
        updateNotification()
        startMonitor()
    }

    private fun publishReloadFailure(snapshot: RootRuntimeSnapshot, reason: String) {
        if (snapshot.phase == RootRuntimePhase.FAILED_BLOCKED) {
            isRunning = true
            publishUidRefreshBlocked(snapshot.copy(error = snapshot.error.ifBlank { reason }))
            scheduleUidRefreshRetry()
            return
        }
        isRunning = false
        isStarting = false
        VpnStateStore.setActive(false)
        VpnStateStore.setPending("")
        VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
        SingBoxIpcHub.update(
            state = ServiceState.STOPPED,
            lastError = reason,
            readiness = rootReadiness(
                DataPlaneStatus.FAILED_UNPROTECTED,
                "root_reload_failed"
            )
        )
        updateNotification()
    }

    private fun scheduleUidRefresh(reason: String) {
        if (!isRunning || runtimeSessionId.isBlank() || VpnStateStore.isManuallyStopped()) return
        if (!uidRefreshScheduled.compareAndSet(false, true)) return
        uidRefreshRetryJob?.cancel()
        uidRefreshRetryJob = null
        serviceScope.launch {
            try {
                lifecycleMutex.withLock { refreshUidRoutingLocked(reason) }
            } finally {
                uidRefreshScheduled.set(false)
            }
            if (lastRootSnapshot.phase == RootRuntimePhase.FAILED_BLOCKED) {
                scheduleUidRefreshRetry()
            }
        }
    }

    @Suppress("LongMethod")
    private suspend fun refreshUidRoutingLocked(reason: String) {
        if (!isRunning || runtimeSessionId.isBlank() || VpnStateStore.isManuallyStopped()) return
        val rootService = rootConnection.service ?: runCatching { rootConnection.bind() }.getOrNull() ?: return
        try {
            monitorJob?.cancel()
            monitorJob = null
            autoFailover.stop()
            isStarting = true
            VpnStateStore.setActive(false)
            VpnStateStore.setPending("uid_refresh")
            SingBoxIpcHub.update(
                state = ServiceState.STARTING,
                lastError = "",
                readiness = rootReadiness(DataPlaneStatus.BLOCKING, "root_uid_refresh")
            )
            Log.i(TAG, "Refreshing Root UID routing: $reason")
            commandManager.stop()
            SelectorManager.clear()
            val refreshed = RootRuntimeSnapshot.fromBundle(rootService.blockForUidRefresh(runtimeSessionId))
            lastRootSnapshot = refreshed
            val marker = RootGenerationStore.readCurrentStrict(filesDir)
                ?: error("Committed Root generation is unavailable during UID refresh")
            val settings = SettingsRepository.getInstance(this).settings.value
            rootRunningSnapshotError(
                refreshed,
                RootRuntimeExpectation(
                    runtimeSessionId = runtimeSessionId,
                    routingGeneration = marker.generation,
                    configFileSha256 = marker.configFileSha256,
                    sidecarFileSha256 = marker.sidecarFileSha256,
                    staticPlanSha256 = marker.staticPlanSha256,
                    appRoutingSha256 = marker.appRoutingSha256,
                    tproxyIpv4 = settings.ipVersionMode != IpVersionMode.IPV6_ONLY,
                    tproxyIpv6 = settings.ipVersionMode != IpVersionMode.IPV4_ONLY
                )
            )?.let(::error)
            commandManager.startClientsWithFd { rootService.openCommandConnection() }.getOrThrow()
            SelectorManager.updateCommandClient(commandManager.getCommandClient())
            val applied = VpnStateStore.getAppliedPerAppPolicy()
            check(VpnStateStore.commitAppliedPerAppPolicy(
                applied.copy(
                    appliedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
                    runtimeGeneration = refreshed.routingGeneration,
                    resolvedPlanSha256 = refreshed.resolvedPlanSha256,
                    rootRuntimeSessionId = refreshed.runtimeSessionId
                )
            )) { "Refreshed Root UID policy could not be committed" }
            uidRefreshRetryCount = 0
            isStarting = false
            VpnStateStore.setMode(VpnStateStore.CoreMode.ROOT)
            VpnStateStore.setActive(true)
            VpnStateStore.setPending("")
            VpnTileService.persistVpnState(true)
            NetworkClient.onVpnStateChanged(true)
            SingBoxIpcHub.update(
                state = ServiceState.RUNNING,
                lastError = "",
                readiness = rootReadiness(DataPlaneStatus.READY, "root_uid_refresh_ready")
            )
            updateNotification()
            startMonitor()
        } catch (error: Exception) {
            Log.e(TAG, "Root UID routing refresh failed", error)
            val current = runCatching {
                RootRuntimeSnapshot.fromBundle(rootService.snapshot)
            }.getOrDefault(lastRootSnapshot)
            lastRootSnapshot = current
            if (current.phase == RootRuntimePhase.FAILED_BLOCKED) {
                publishUidRefreshBlocked(current.copy(error = current.error.ifBlank { error.message.orEmpty() }))
            } else {
                isStarting = false
                stopRuntimeLocked(stopSelfAfter = true)
            }
        }
    }

    private fun publishUidRefreshBlocked(snapshot: RootRuntimeSnapshot) {
        lastRootSnapshot = snapshot
        isStarting = false
        VpnStateStore.setMode(VpnStateStore.CoreMode.ROOT)
        VpnStateStore.setActive(false)
        VpnStateStore.setPending("")
        VpnTileService.persistVpnState(false)
        NetworkClient.onVpnStateChanged(false)
        SingBoxIpcHub.update(
            state = ServiceState.STOPPED,
            lastError = snapshot.error.ifBlank { "Root UID refresh is blocked" },
            readiness = rootReadiness(DataPlaneStatus.FAILED_BLOCKED, "root_uid_refresh_blocked")
        )
        updateNotification()
    }

    private fun scheduleUidRefreshRetry() {
        if (!isRunning || runtimeSessionId.isBlank() || VpnStateStore.isManuallyStopped()) return
        uidRefreshRetryJob?.cancel()
        val retryDelay = minOf(30_000L, 2_000L shl minOf(uidRefreshRetryCount, 4))
        uidRefreshRetryCount++
        uidRefreshRetryJob = serviceScope.launch {
            delay(retryDelay)
            scheduleUidRefresh("root_uid_refresh_retry")
        }
    }

    @Suppress("DEPRECATION")
    private fun registerUidRefreshReceivers() {
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val userFilter = IntentFilter().apply {
            addAction("android.intent.action.USER_ADDED")
            addAction("android.intent.action.USER_REMOVED")
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageChangeReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(userChangeReceiver, userFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageChangeReceiver, packageFilter)
            registerReceiver(userChangeReceiver, userFilter)
        }
    }

    private fun unregisterUidRefreshReceivers() {
        runCatching { unregisterReceiver(packageChangeReceiver) }
        runCatching { unregisterReceiver(userChangeReceiver) }
    }

    @Suppress("ComplexCondition")
    private fun scheduleControlChannelRecovery(reason: String) {
        if (!isRunning || isStarting || lastRootSnapshot.phase != RootRuntimePhase.RUNNING ||
            VpnStateStore.isManuallyStopped()
        ) return
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

    @Suppress("LoopWithTooManyJumpStatements")
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (currentCoroutineContext().isActive && isRunning) {
                delay(1_000)
                val rootSnapshot = RootRuntimeSnapshot.fromBundle(rootConnection.service?.snapshot)
                lastRootSnapshot = rootSnapshot
                when (rootSnapshot.phase) {
                    RootRuntimePhase.RUNNING -> Unit
                    RootRuntimePhase.FAIL_CLOSED -> {
                        scheduleUidRefresh(rootSnapshot.error.ifBlank { "root_uid_snapshot_changed" })
                        break
                    }
                    RootRuntimePhase.FAILED_BLOCKED -> {
                        publishUidRefreshBlocked(rootSnapshot)
                        scheduleUidRefreshRetry()
                        break
                    }
                    RootRuntimePhase.VALIDATING_PLAN,
                    RootRuntimePhase.UID_SNAPSHOT_1,
                    RootRuntimePhase.CORE_STARTING,
                    RootRuntimePhase.CORE_VERIFYING,
                    RootRuntimePhase.RULES_STAGING,
                    RootRuntimePhase.UID_SNAPSHOT_2,
                    RootRuntimePhase.RULES_ACTIVATING,
                    RootRuntimePhase.ROLLBACK -> SingBoxIpcHub.update(
                        readiness = rootReadiness(DataPlaneStatus.BLOCKING, "root_uid_refresh")
                    )
                    else -> {
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
            rootRoutingGeneration = lastRootSnapshot.routingGeneration,
            rootConfigSha256 = lastRootSnapshot.configFileSha256,
            rootSidecarSha256 = lastRootSnapshot.sidecarFileSha256,
            rootStaticPlanSha256 = lastRootSnapshot.staticPlanSha256,
            rootAppRoutingSha256 = lastRootSnapshot.appRoutingSha256,
            rootResolvedPlanSha256 = lastRootSnapshot.resolvedPlanSha256,
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
