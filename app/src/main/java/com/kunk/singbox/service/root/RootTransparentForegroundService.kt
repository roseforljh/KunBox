@file:Suppress("TooManyFunctions")

package com.kunk.singbox.service.root

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.aidl.IRootSingBoxService
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.DataPlaneStatus
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.PerAppVpnPolicy
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.InstalledAppsRepository
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.RootGenerationMarker
import com.kunk.singbox.repository.RootGenerationStore
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.manager.VpnStopInitiator
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.service.notification.NotificationActionConfig
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.utils.NetworkClient
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("LargeClass")
class RootTransparentForegroundService : Service() {
    companion object {
        internal const val TAG = "RootTransparentService"
        internal const val CHANNEL_ID = "root_transparent"
        internal const val NOTIFICATION_ID = 12
        const val ACTION_START = "com.kunk.singbox.action.ROOT_START"
        const val ACTION_STOP = "com.kunk.singbox.action.ROOT_STOP"
        const val ACTION_RESTART = "com.kunk.singbox.action.ROOT_RESTART"
        const val ACTION_RESET_CONNECTIONS = "com.kunk.singbox.action.ROOT_RESET_CONNECTIONS"
        const val ACTION_SWITCH_NODE = "com.kunk.singbox.action.ROOT_SWITCH_NODE"
        const val EXTRA_CONFIG_PATH = "config_path"
        const val EXTRA_OUTBOUND_TAG = "outbound_tag"
        const val EXTRA_NODE_NAME = "node_name"
        const val EXTRA_APP_ROUTE_REQUEST_ID = "app_route_request_id"

        @Volatile var isRunning: Boolean = false
            internal set
        @Volatile var isStarting: Boolean = false
            internal set
    }

    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val lifecycleMutex = Mutex()
    internal val lifecycle = RootLifecycleCoordinator()
    internal lateinit var rootConnection: RootServiceConnection
    internal lateinit var commandManager: CommandManager
    internal lateinit var autoFailover: RootAutoFailoverController
    internal lateinit var rootNotificationManager: VpnNotificationManager
    internal var monitorJob: Job? = null
    internal var lifecycleJob: Job? = null
    internal var uidRefreshJob: Job? = null
    internal val controlRecoveryScheduled = AtomicBoolean(false)
    internal val notificationNodeSwitchInFlight = AtomicBoolean(false)
    internal val uidRefreshScheduled = AtomicBoolean(false)
    internal var runtimeSessionId: String = ""
    @Volatile internal var lifecycleStartedAtMs = 0L
    @Volatile internal var lastRootSnapshot = RootRuntimeSnapshot()
    @Volatile internal var showNotificationSpeed = true
    @Volatile internal var currentUploadSpeed = 0L
    @Volatile internal var currentDownloadSpeed = 0L
    internal val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleUidRefresh(intent?.action.orEmpty())
        }
    }
    internal val userChangeReceiver = object : BroadcastReceiver() {
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
                    requestStopRuntime(stopSelfAfter = true, reason = "core_requested_stop")
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
                requestStopRuntime(stopSelfAfter = true, reason = initiator.wireValue)
            }
            ACTION_RESTART -> requestRunningRuntime(reload = true) { token ->
                restartRuntime(
                    configPathOverride = intent.getStringExtra(EXTRA_CONFIG_PATH),
                    requestId = intent.getStringExtra(EXTRA_APP_ROUTE_REQUEST_ID).orEmpty(),
                    token = token
                )
            }
            else -> requestRunningRuntime(reload = false) { token ->
                startRuntime(
                    configPathOverride = intent?.getStringExtra(EXTRA_CONFIG_PATH),
                    requestId = intent?.getStringExtra(EXTRA_APP_ROUTE_REQUEST_ID).orEmpty(),
                    token = token
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycle.requestStopped()
        syncLifecycleFlags()
        lifecycleJob?.cancel()
        uidRefreshJob?.cancel()
        monitorJob?.cancel()
        unregisterUidRefreshReceivers()
        autoFailover.stop()
        commandManager.stop()
        // RootService is a separate process. Unbinding alone can leave its
        // netfilter rules and watchdog alive when destruction races startup.
        runCatching { rootConnection.stopRootService() }
            .onFailure { error -> Log.e(TAG, "Could not stop RootService during foreground destroy", error) }
        serviceScope.cancel()
        super.onDestroy()
    }

    internal fun requestRunningRuntime(
        reload: Boolean,
        operation: suspend (Long) -> Unit
    ) {
        val before = lifecycle.snapshot()
        val supersededSession = runtimeSessionId.takeIf {
            it.isNotBlank() && before.state in setOf(RootLifecycleState.STARTING, RootLifecycleState.RELOADING)
        }
        lifecycleStartedAtMs = android.os.SystemClock.elapsedRealtime()
        val token = lifecycle.requestRunning(reload)
        syncLifecycleFlags()
        logLifecycle(
            event = if (reload) "reload_requested" else "start_requested",
            generation = token,
            reason = "service_command",
            from = before.state
        )
        if (before.state == RootLifecycleState.STOPPING) {
            val stoppingJob = lifecycleJob
            lifecycleJob = serviceScope.launch {
                stoppingJob?.join()
                val stopped = lifecycle.snapshot()
                if (lifecycle.isCurrentRunningRequest(token) && stopped.state == RootLifecycleState.STOPPED) {
                    operation(token)
                } else if (lifecycle.isCurrentRunningRequest(token)) {
                    Log.e(
                        TAG,
                        "[ROOT_LIFECYCLE] event=queued_start_blocked generation=$token state=${stopped.state}"
                    )
                }
            }
            return
        }
        lifecycleJob?.cancel()
        lifecycleJob = serviceScope.launch {
            try {
                supersededSession?.let { sessionId ->
                    runCatching { rootConnection.service?.requestStop(sessionId) }
                        .onFailure { error -> Log.w(TAG, "Root superseded generation stop signal failed", error) }
                }
                operation(token)
            } catch (_: CancellationException) {
                Log.i(TAG, "[ROOT_LIFECYCLE] event=generation_cancelled generation=$token")
            }
        }
    }

    internal fun requestStopRuntime(stopSelfAfter: Boolean, reason: String) {
        val before = lifecycle.snapshot()
        lifecycleStartedAtMs = android.os.SystemClock.elapsedRealtime()
        val token = lifecycle.requestStopped()
        syncLifecycleFlags()
        logLifecycle("stop_requested", token, reason, before.state)
        lifecycleJob?.cancel()
        uidRefreshJob?.cancel()
        uidRefreshJob = null
        monitorJob?.cancel()
        monitorJob = null
        val sessionId = runtimeSessionId
        lifecycleJob = serviceScope.launch {
            if (sessionId.isNotBlank()) {
                runCatching { rootConnection.service?.requestStop(sessionId) }
                    .onFailure { error -> Log.w(TAG, "Root stop preemption signal failed", error) }
            }
            stopRuntime(stopSelfAfter, token)
        }
    }

    @Suppress("CognitiveComplexMethod", "LongMethod")
    internal suspend fun startRuntime(
        configPathOverride: String?,
        requestId: String = "",
        token: Long
    ) = lifecycleMutex.withLock {
        ensureRunningRequest(token)
        startRuntimeLocked(configPathOverride, requestId, token)
    }

    @Suppress("CognitiveComplexMethod", "LongMethod", "CyclomaticComplexMethod")
    internal suspend fun startRuntimeLocked(
        configPathOverride: String?,
        requestId: String = "",
        token: Long
    ) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var phaseStartedAt = startedAt
        var previousMarker: RootGenerationMarker? = null
        var candidateGeneration: ConfigRepository.ConfigGenerationResult? = null
        var returnedRootSnapshot: RootRuntimeSnapshot? = null
        try {
            ensureRunningRequest(token)
            if (!transitionLifecycle(token, RootLifecycleState.STARTING, "start_entered")) {
                throw CancellationException("Root start generation $token was superseded")
            }
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
            val packageCleanup = settingsRepository.removeUninstalledPerAppPackages(
                InstalledAppsRepository.queryInstalledPackageNames(this)
            ).getOrThrow()
            if (packageCleanup.changed) {
                Log.w(
                    TAG,
                    "[ROOT_NET] event=stale_packages_removed revision=${packageCleanup.revision}"
                )
            }
            val settings = settingsRepository.settings.value
            showNotificationSpeed = settings.showNotificationSpeed
            check(settings.resolvedTrafficCaptureMode() == TrafficCaptureMode.ROOT_TRANSPARENT) {
                "Root transparent mode is not selected"
            }
            val candidateRequestId = requestId.ifBlank { UUID.randomUUID().toString() }
            val generation = configPathOverride
                ?.takeIf { it.isNotBlank() && !packageCleanup.changed }
                ?.let { path ->
                    loadRootGenerationResult(
                        path = path,
                        requestId = candidateRequestId
                    )
                } ?: ConfigRepository.getInstance(this).generateConfigFile(candidateRequestId = candidateRequestId)
                ?: error("Failed to generate Root transparent config")
            ensureRunningRequest(token)
            candidateGeneration = generation
            val configPath = generation.path
            val policy = PerAppVpnPolicy.from(settings)
            val actualAppRoutingDigest = ConfigRepository.appRoutingDigest(settings)
            logStartPhase("config", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            val rootService = rootConnection.bind()
            ensureRunningRequest(token)
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
            ).also { returnedRootSnapshot = it }
            ensureRunningRequest(token)
            lastRootSnapshot = rootSnapshot
            logStartPhase("root_runtime", phaseStartedAt)
            val rootTiming = rootSnapshot.startupTimings.ifBlank {
                "phase=${rootSnapshot.phase.name};error=${rootSnapshot.error.ifBlank { "unknown" }}"
            }
            Log.i(TAG, "[ROOT_START] inner=$rootTiming")
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            completeRootRuntime(
                rootService = rootService,
                rootSnapshot = rootSnapshot,
                generation = generation,
                requestId = generation.requestId,
                policy = policy,
                settings = settings,
                appRoutingDigest = actualAppRoutingDigest,
                token = token
            )
            logStartPhase("total", startedAt)
        } catch (error: CancellationException) {
            restoreRootGenerationAfterFailure(previousMarker, candidateGeneration)
            candidateGeneration?.requestId?.takeIf(String::isNotBlank)
                ?.let(NodeProtectionStore::discardStagedRuntimeMappings)
            Log.i(TAG, "Root transparent startup superseded generation=$token")
            throw error
        } catch (error: Exception) {
            restoreRootGenerationAfterFailure(previousMarker, candidateGeneration)
            candidateGeneration?.requestId?.takeIf(String::isNotBlank)
                ?.let(NodeProtectionStore::discardStagedRuntimeMappings)
            ensureRunningRequest(token)
            Log.e(TAG, "Root transparent startup failed", error)
            val requiresSynchronousStop = rootStartFailureRequiresSynchronousStop(returnedRootSnapshot)
            val rollbackStartedAt = android.os.SystemClock.elapsedRealtime()
            if (requiresSynchronousStop) {
                SingBoxIpcHub.update(
                    state = ServiceState.STOPPING,
                    lastError = error.message ?: "Root transparent startup failed",
                    readiness = rootReadiness(DataPlaneStatus.BLOCKING, "root_start_rollback")
                )
            }
            val stopped = if (requiresSynchronousStop) {
                runCatching {
                    rootConnection.service?.stop(runtimeSessionId)?.let(RootRuntimeSnapshot::fromBundle)
                }.getOrNull()
            } else {
                returnedRootSnapshot
            }
            Log.i(
                TAG,
                "[ROOT_START] phase=failure_rollback " +
                    "duration_ms=${android.os.SystemClock.elapsedRealtime() - rollbackStartedAt} " +
                    "synchronous=$requiresSynchronousStop returned_phase=${returnedRootSnapshot?.phase}"
            )
            if (stopped != null) {
                lastRootSnapshot = stopped
            } else if (runtimeSessionId.isNotBlank()) {
                lastRootSnapshot = RootRuntimeSnapshot(
                    phase = RootRuntimePhase.FAILED_VERIFICATION,
                    runtimeSessionId = runtimeSessionId,
                    rulesInstalled = false,
                    error = "Root startup cleanup could not be confirmed"
                )
            }
            val cleanupFailed = lastRootSnapshot.phase == RootRuntimePhase.FAILED_BLOCKED ||
                lastRootSnapshot.rulesInstalled
            transitionLifecycle(token, RootLifecycleState.FAILED, "start_failed")
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
            if (cleanupFailed) {
                updateNotification()
            } else {
                runtimeSessionId = ""
                rootConnection.stopRootService()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    @Suppress("LongParameterList", "LongMethod")
    internal suspend fun completeRootRuntime(
        rootService: IRootSingBoxService,
        rootSnapshot: RootRuntimeSnapshot,
        generation: ConfigRepository.ConfigGenerationResult,
        requestId: String,
        policy: PerAppVpnPolicy,
        settings: com.kunk.singbox.model.AppSettings,
        appRoutingDigest: String,
        token: Long
    ) {
        ensureRunningRequest(token)
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
        SingBoxCore.ensureLibboxSetup(this)
        commandManager.startClientsWithFd { rootService.openCommandConnection() }.getOrThrow()
        ensureRunningRequest(token)
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
        ensureRunningRequest(token)
        val rootMarker = generation.toRootGenerationMarker()
        check(RootGenerationStore.commit(filesDir, rootMarker, configContent)) {
            "Root generation marker could not be committed"
        }
        RootGenerationStore.writeCompatibilityCaches(filesDir, configContent, rootMarker)
        check(RootGenerationStore.readCurrentStrict(filesDir) == rootMarker)
        check(RootGenerationStore.cacheMatchesCurrent(filesDir, "running_config.json", rootMarker))
        check(NodeProtectionStore.activateStagedRuntimeMappings(requestId, configContent)) {
            "Root candidate runtime node mappings could not be activated"
        }
        Log.i(
            TAG,
            "[ROOT_GENERATION] generation=${rootMarker.generation} phase=commit " +
                "config=${rootMarker.configFileSha256} appRouting=${rootMarker.appRoutingSha256}"
        )
        ensureRunningRequest(token)
        runCatching {
            RootGenerationStore.pruneGenerations(filesDir, setOf(rootMarker.generation))
        }.onFailure { error ->
            Log.w(TAG, "Could not prune stale Root generations", error)
        }
        check(transitionLifecycle(token, RootLifecycleState.RUNNING, "runtime_ready")) {
            "Root lifecycle generation became stale before RUNNING"
        }
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

    internal fun ConfigRepository.ConfigGenerationResult.toRootGenerationMarker(): RootGenerationMarker =
        RootGenerationStore.marker(
            generation = rootRoutingGeneration,
            configFileSha256 = configDigest,
            sidecarFileSha256 = rootRoutingSidecarDigest,
            staticPlanSha256 = rootRoutingStaticPlanDigest,
            appRoutingSha256 = rootRoutingAppDigest
        )

    internal fun restoreRootGenerationAfterFailure(
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

    internal fun logStartPhase(phase: String, startedAt: Long) {
        Log.i(TAG, "[ROOT_START] phase=$phase duration_ms=${android.os.SystemClock.elapsedRealtime() - startedAt}")
    }

    internal suspend fun ensureRunningRequest(token: Long) {
        currentCoroutineContext().ensureActive()
        if (!lifecycle.isCurrentRunningRequest(token)) {
            throw CancellationException("Root lifecycle generation $token is no longer desired")
        }
    }

    internal fun transitionLifecycle(token: Long, target: RootLifecycleState, reason: String): Boolean {
        val before = lifecycle.snapshot()
        val accepted = lifecycle.transition(token, target)
        if (accepted) {
            syncLifecycleFlags()
            logLifecycle("transition", token, reason, before.state)
        }
        return accepted
    }

    internal fun syncLifecycleFlags() {
        val state = lifecycle.snapshot().state
        isRunning = state == RootLifecycleState.RUNNING
        isStarting = state == RootLifecycleState.STARTING || state == RootLifecycleState.RELOADING
    }

    internal fun logLifecycle(
        event: String,
        generation: Long,
        reason: String,
        from: RootLifecycleState
    ) {
        val current = lifecycle.snapshot()
        Log.i(
            TAG,
            "[ROOT_LIFECYCLE] event=$event from=$from to=${current.state} " +
                "desiredState=${current.desiredState} generation=$generation reason=$reason " +
                "caller=RootTransparentForegroundService thread=${Thread.currentThread().name} " +
                "elapsed_ms=${android.os.SystemClock.elapsedRealtime() - lifecycleStartedAtMs}"
        )
    }

    internal fun loadRootGenerationResult(
        path: String,
        requestId: String
    ): ConfigRepository.ConfigGenerationResult {
        val marker = RootGenerationStore.resolveConfigMarker(filesDir, path)
        val configFile = RootGenerationStore.configFile(filesDir, marker)
        val sidecarFile = RootGenerationStore.sidecarFile(filesDir, marker)
        val manifestFile = RootGenerationStore.manifestFile(filesDir, marker)
        val sidecarJson = sidecarFile.readText(Charsets.UTF_8)
        return ConfigRepository.ConfigGenerationResult(
            path = configFile.absolutePath,
            activeNodeTag = null,
            outboundTags = emptySet(),
            requestId = requestId,
            configDigest = marker.configFileSha256,
            appRoutingDigest = marker.appRoutingSha256,
            rootRoutingSidecarPath = sidecarFile.absolutePath,
            rootRoutingManifestPath = manifestFile.absolutePath,
            rootRoutingSidecarJson = sidecarJson,
            rootRoutingSidecarDigest = marker.sidecarFileSha256,
            rootRoutingStaticPlanDigest = marker.staticPlanSha256,
            rootRoutingAppDigest = marker.appRoutingSha256,
            rootRoutingGeneration = marker.generation
        )
    }

    @Suppress("LongMethod")
    internal suspend fun stopRuntime(stopSelfAfter: Boolean, token: Long) = lifecycleMutex.withLock {
        stopRuntimeLocked(stopSelfAfter, token)
    }

    @Suppress("LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
    internal suspend fun stopRuntimeLocked(stopSelfAfter: Boolean, token: Long) {
        try {
            transitionLifecycle(token, RootLifecycleState.STOPPING, "cleanup_started")
            monitorJob?.cancel()
            monitorJob = null
            uidRefreshScheduled.set(false)
            autoFailover.stop()
            SingBoxIpcHub.update(
                state = ServiceState.STOPPING,
                readiness = rootReadiness(DataPlaneStatus.BLOCKING, "root_cleanup")
            )
            commandManager.stop()
            SelectorManager.clear()
            val stopped = runCatching {
                val rootService = rootConnection.service ?: rootConnection.bind()
                runtimeSessionId.takeIf(String::isNotBlank)?.let(rootService::requestStop)
                RootRuntimeSnapshot.fromBundle(rootService.stop(runtimeSessionId))
            }.getOrElse { error ->
                RootRuntimeSnapshot(
                    phase = RootRuntimePhase.FAILED_VERIFICATION,
                    runtimeSessionId = runtimeSessionId,
                    rulesInstalled = false,
                    error = error.message ?: "Root cleanup could not be confirmed"
                )
            }
            lastRootSnapshot = stopped
            val cleanupFailed = stopped.phase == RootRuntimePhase.FAILED_BLOCKED || stopped.rulesInstalled
            val verificationFailed = stopped.phase == RootRuntimePhase.FAILED_VERIFICATION
            if (cleanupFailed) {
                transitionLifecycle(token, RootLifecycleState.FAILED, "cleanup_failed")
            } else if (verificationFailed) {
                transitionLifecycle(token, RootLifecycleState.FAILED, "cleanup_verification_failed")
            } else {
                check(
                    stopped.phase == RootRuntimePhase.STOPPED ||
                        stopped.phase == RootRuntimePhase.FAILED_UNPROTECTED
                ) {
                    stopped.error.ifBlank { "Root cleanup did not reach STOPPED" }
                }
                transitionLifecycle(token, RootLifecycleState.STOPPED, "cleanup_verified")
            }
            if (!cleanupFailed) rootConnection.stopRootService()
            if (!cleanupFailed) runtimeSessionId = ""
            VpnStateStore.setActive(false)
            VpnStateStore.setPending("")
            VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
            VpnTileService.persistVpnState(false)
            NetworkClient.onVpnStateChanged(false)
            SingBoxIpcHub.update(
                state = ServiceState.STOPPED,
                activeLabel = "",
                lastError = stopped.error,
                readiness = rootReadiness(
                    when {
                        cleanupFailed -> DataPlaneStatus.FAILED_BLOCKED
                        verificationFailed -> DataPlaneStatus.FAILED_UNPROTECTED
                        else -> DataPlaneStatus.STOPPED
                    },
                    when {
                        cleanupFailed -> "root_rules_present"
                        verificationFailed -> "root_cleanup_verification_failed"
                        else -> "root_stopped"
                    }
                )
            )
            if (cleanupFailed || verificationFailed) {
                updateNotification()
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (stopSelfAfter && lifecycle.snapshot().desiredState == RootDesiredState.STOPPED) stopSelf()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Root transparent stop failed", error)
            lastRootSnapshot = RootRuntimeSnapshot(
                phase = RootRuntimePhase.FAILED_VERIFICATION,
                runtimeSessionId = runtimeSessionId,
                rulesInstalled = false,
                error = error.message ?: "Root cleanup could not be confirmed"
            )
            transitionLifecycle(token, RootLifecycleState.FAILED, "cleanup_exception")
            VpnStateStore.setActive(false)
            VpnStateStore.setPending("")
            VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
            SingBoxIpcHub.update(
                state = ServiceState.STOPPED,
                lastError = lastRootSnapshot.error,
                readiness = rootReadiness(DataPlaneStatus.FAILED_UNPROTECTED, "root_cleanup_unconfirmed")
            )
            updateNotification()
            if (stopSelfAfter && lifecycle.snapshot().desiredState == RootDesiredState.STOPPED) {
                Log.w(TAG, "Root stop verification failed; keeping service alive for cleanup retry")
            }
        }
    }
}
