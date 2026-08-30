@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength")

package com.kunk.singbox.service.root

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import com.kunk.singbox.aidl.IRootSingBoxService
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.ipc.DataPlaneReadinessSnapshot
import com.kunk.singbox.ipc.DataPlaneStatus
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.PerAppVpnPolicy
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.RootGenerationStore
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.service.resolveNotificationNodeLabel
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.utils.NetworkClient
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock

internal suspend fun RootTransparentForegroundService.restartRuntime(
    configPathOverride: String?,
    requestId: String = "",
    token: Long
) = lifecycleMutex.withLock {
    ensureRunningRequest(token)
    if (rootConnection.service != null && lastRootSnapshot.phase == RootRuntimePhase.RUNNING) {
        reloadRuntimeLocked(
            configPathOverride,
            requestId,
            token
        )
        return@withLock
    }
    stopRuntimeLocked(stopSelfAfter = false, token = token)
    ensureRunningRequest(token)
// A blocked cleanup is retried by startRuntimeLocked. The Root
// process checks ownership and listener absence before accepting the
// next generation; a persistent conflict remains blocked.
    startRuntimeLocked(
        configPathOverride = configPathOverride,
        requestId = requestId,
        token = token
    )
}

@Suppress("LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
internal suspend fun RootTransparentForegroundService.reloadRuntimeLocked(
    configPathOverride: String?,
    requestId: String,
    token: Long
) {
    val previousMarker = RootGenerationStore.readCurrentStrict(filesDir)
    var generation: ConfigRepository.ConfigGenerationResult? = null
    try {
        ensureRunningRequest(token)
        monitorJob?.cancel()
        monitorJob = null
        autoFailover.stop()
        VpnStateStore.setPending("starting")
        SingBoxIpcHub.update(
            state = ServiceState.STARTING,
            lastError = "",
            readiness = rootReadiness(DataPlaneStatus.BLOCKING, "root_cold_reload")
        )
        val settingsRepository = SettingsRepository.getInstance(this)
        settingsRepository.reloadFromStorage()
        val settings = settingsRepository.settings.value
        val candidateRequestId = resolveRootCandidateRequestId(
            configPathOverride = configPathOverride,
            requestId = requestId,
            generatedId = UUID.randomUUID().toString()
        )
        val candidate = configPathOverride?.takeIf(String::isNotBlank)?.let { path ->
            loadRootGenerationResult(
                path,
                candidateRequestId
            )
        } ?: ConfigRepository.getInstance(this).generateConfigFile(candidateRequestId = candidateRequestId)
            ?: error("Failed to generate Root reload config")
        ensureRunningRequest(token)
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
        ensureRunningRequest(token)
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
                restoreReloadedPreviousRuntime(rootService, rootSnapshot, snapshotError, token)
            } else {
                publishReloadFailure(rootSnapshot, snapshotError, token)
            }
            restoreRootGenerationAfterFailure(previousMarker, candidate)
            candidate.requestId.takeIf(String::isNotBlank)
                ?.let(NodeProtectionStore::discardStagedRuntimeMappings)
            return
        }
        completeRootRuntime(
            rootService,
            rootSnapshot,
            candidate,
            candidate.requestId,
            PerAppVpnPolicy.from(settings),
            settings,
            ConfigRepository.appRoutingDigest(settings),
            token
        )
    } catch (error: CancellationException) {
        restoreRootGenerationAfterFailure(previousMarker, generation)
        generation?.requestId?.takeIf(String::isNotBlank)
            ?.let(NodeProtectionStore::discardStagedRuntimeMappings)
        throw error
    } catch (error: Exception) {
        Log.e(RootTransparentForegroundService.TAG, "Root cold reload failed", error)
        restoreRootGenerationAfterFailure(previousMarker, generation)
        generation?.requestId?.takeIf(String::isNotBlank)
            ?.let(NodeProtectionStore::discardStagedRuntimeMappings)
        ensureRunningRequest(token)
        val rootService = rootConnection.service
        val snapshot = runCatching { RootRuntimeSnapshot.fromBundle(rootService?.snapshot) }
            .getOrDefault(lastRootSnapshot)
        lastRootSnapshot = snapshot
        if (rootService != null && snapshot.phase == RootRuntimePhase.RUNNING &&
            snapshot.routingGeneration == previousMarker?.generation
        ) {
            restoreReloadedPreviousRuntime(rootService, snapshot, error.message ?: "Root reload failed", token)
        } else {
            publishReloadFailure(snapshot, error.message ?: "Root reload failed", token)
        }
    }
}

internal suspend fun RootTransparentForegroundService.restoreReloadedPreviousRuntime(
    rootService: IRootSingBoxService,
    snapshot: RootRuntimeSnapshot,
    reason: String,
    token: Long
) {
    ensureRunningRequest(token)
    commandManager.startClientsWithFd { rootService.openCommandConnection() }.getOrThrow()
    SelectorManager.updateCommandClient(commandManager.getCommandClient())
    check(transitionLifecycle(token, RootLifecycleState.RUNNING, "reload_rolled_back")) {
        "Root rollback generation became stale"
    }
    VpnStateStore.setStopOwnerMode(VpnStateStore.CoreMode.ROOT)
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

internal fun RootTransparentForegroundService.publishReloadFailure(snapshot: RootRuntimeSnapshot, reason: String, token: Long) {
    if (snapshot.phase == RootRuntimePhase.FAILED_BLOCKED) {
        transitionLifecycle(token, RootLifecycleState.FAILED, "reload_rules_present")
        publishUidRefreshBlocked(snapshot.copy(error = snapshot.error.ifBlank { reason }), token)
        return
    }
    transitionLifecycle(token, RootLifecycleState.FAILED, "reload_failed")
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

internal fun RootTransparentForegroundService.scheduleUidRefresh(reason: String) {
    if (!RootTransparentForegroundService.isRunning || runtimeSessionId.isBlank() || VpnStateStore.isManuallyStopped()) return
    if (!uidRefreshScheduled.compareAndSet(false, true)) return
    val before = lifecycle.snapshot()
    lifecycleStartedAtMs = android.os.SystemClock.elapsedRealtime()
    val token = lifecycle.requestRunning(reload = true) ?: run {
        uidRefreshScheduled.set(false)
        return
    }
    syncLifecycleFlags()
    logLifecycle("uid_refresh_requested", token, reason, before.state)
    uidRefreshJob?.cancel()
    uidRefreshJob = serviceScope.launch {
        try {
            lifecycleMutex.withLock {
                ensureRunningRequest(token)
                refreshUidRoutingLocked(reason, token)
            }
        } catch (_: CancellationException) {
            Log.i(RootTransparentForegroundService.TAG, "Root UID refresh superseded generation=$token")
        } finally {
            uidRefreshScheduled.set(false)
        }
    }
}

@Suppress("LongMethod")
internal suspend fun RootTransparentForegroundService.refreshUidRoutingLocked(reason: String, token: Long) {
    ensureRunningRequest(token)
    if (runtimeSessionId.isBlank() || VpnStateStore.isManuallyStopped()) return
    val rootService = rootConnection.service ?: runCatching { rootConnection.bind() }.getOrNull() ?: return
    try {
        monitorJob?.cancel()
        monitorJob = null
        autoFailover.stop()
        VpnStateStore.setActive(false)
        VpnStateStore.setPending("uid_refresh")
        SingBoxIpcHub.update(
            state = ServiceState.STARTING,
            lastError = "",
            readiness = rootReadiness(DataPlaneStatus.BLOCKING, "root_uid_refresh")
        )
        Log.i(RootTransparentForegroundService.TAG, "Refreshing Root UID routing: $reason")
        commandManager.stop()
        SelectorManager.clear()
        val refreshed = RootRuntimeSnapshot.fromBundle(rootService.blockForUidRefresh(runtimeSessionId))
        ensureRunningRequest(token)
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
        check(transitionLifecycle(token, RootLifecycleState.RUNNING, "uid_refresh_ready")) {
            "Root UID refresh generation became stale"
        }
        VpnStateStore.setStopOwnerMode(VpnStateStore.CoreMode.ROOT)
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
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e(RootTransparentForegroundService.TAG, "Root UID routing refresh failed", error)
        val current = runCatching {
            RootRuntimeSnapshot.fromBundle(rootService.snapshot)
        }.getOrDefault(lastRootSnapshot)
        lastRootSnapshot = current
        if (current.phase == RootRuntimePhase.FAILED_BLOCKED) {
            publishUidRefreshBlocked(
                current.copy(error = current.error.ifBlank { error.message.orEmpty() }),
                token
            )
        } else {
            stopRuntimeLocked(stopSelfAfter = true, token = token)
        }
    }
}

internal fun RootTransparentForegroundService.publishUidRefreshBlocked(snapshot: RootRuntimeSnapshot, token: Long) {
    lastRootSnapshot = snapshot
    transitionLifecycle(token, RootLifecycleState.FAILED, "uid_refresh_blocked")
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

@Suppress("DEPRECATION")
internal fun RootTransparentForegroundService.registerUidRefreshReceivers() {
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

internal fun RootTransparentForegroundService.unregisterUidRefreshReceivers() {
    runCatching { unregisterReceiver(packageChangeReceiver) }
    runCatching { unregisterReceiver(userChangeReceiver) }
}

@Suppress("ComplexCondition")
internal fun RootTransparentForegroundService.scheduleControlChannelRecovery(reason: String) {
    if (!RootTransparentForegroundService.isRunning || RootTransparentForegroundService.isStarting || lastRootSnapshot.phase != RootRuntimePhase.RUNNING ||
        VpnStateStore.isManuallyStopped()
    ) return
    if (!controlRecoveryScheduled.compareAndSet(false, true)) return
    serviceScope.launch {
        try {
            Log.w(RootTransparentForegroundService.TAG, "Root command channel unhealthy, restarting runtime: $reason")
            requestRunningRuntime(reload = true) { token ->
                restartRuntime(configPathOverride = null, token = token)
            }
        } finally {
            controlRecoveryScheduled.set(false)
        }
    }
}

internal fun RootTransparentForegroundService.recordSelector(configPath: String) {
    val config = runCatching { Gson().fromJson(File(configPath).readText(), SingBoxConfig::class.java) }.getOrNull()
    val proxy = config?.outbounds.orEmpty().firstOrNull { it.tag == "PROXY" }
    SelectorManager.recordSelectorSignature(proxy?.outbounds.orEmpty())
}

@Suppress("LoopWithTooManyJumpStatements")
internal fun RootTransparentForegroundService.startMonitor() {
    monitorJob?.cancel()
    monitorJob = serviceScope.launch {
        while (currentCoroutineContext().isActive && RootTransparentForegroundService.isRunning) {
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
                    val token = lifecycle.snapshot().generation
                    publishUidRefreshBlocked(rootSnapshot, token)
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
                    requestStopRuntime(stopSelfAfter = true, reason = "root_runtime_lost")
                    break
                }
            }
        }
    }
}

internal fun RootTransparentForegroundService.onRootServiceDisconnected() {
    if (!RootTransparentForegroundService.isRunning && !RootTransparentForegroundService.isStarting) return
    serviceScope.launch {
        SingBoxIpcHub.update(
            lastError = "RootService disconnected",
            readiness = rootReadiness(DataPlaneStatus.FAILED_UNPROTECTED, "root_binder_died")
        )
        requestStopRuntime(stopSelfAfter = true, reason = "root_binder_died")
    }
}

internal suspend fun RootTransparentForegroundService.switchNode(
    outboundTag: String,
    nodeName: String,
    fallbackConfigPath: String?,
    fallbackRequestId: String
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
            requestRunningRuntime(reload = true) { token ->
                restartRuntime(fallbackConfigPath, fallbackRequestId, token)
            }
        }
    }
}

internal fun RootTransparentForegroundService.rootReadiness(status: DataPlaneStatus, reason: String): DataPlaneReadinessSnapshot =
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

internal fun RootTransparentForegroundService.startForegroundCompat(text: String) {
    val notification = rootNotificationManager.createStartingNotification(text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(RootTransparentForegroundService.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        startForeground(RootTransparentForegroundService.NOTIFICATION_ID, notification)
    }
    rootNotificationManager.markForegroundStarted()
}

internal fun RootTransparentForegroundService.updateNotification() {
    requestNotificationUpdate(force = true)
}

internal fun RootTransparentForegroundService.requestNotificationUpdate(force: Boolean) {
    rootNotificationManager.requestNotificationUpdate(buildNotificationState(), this, force)
}

internal fun RootTransparentForegroundService.buildNotificationState(): VpnNotificationManager.NotificationState {
    val repository = ConfigRepository.getInstance(applicationContext)
    val selectedNodeId = repository.activeNodeId.value
    val nodeName = resolveNotificationNodeLabel(
        selectedNodeName = repository.nodes.value.find { it.id == selectedNodeId }?.name,
        selectedNodeStoreLabel = VpnStateStore.getSelectedNodeLabel(),
        runtimeNodeName = commandManager.realTimeNodeName ?: VpnStateStore.getActiveLabel()
    )
    return VpnNotificationManager.NotificationState(
        isRunning = RootTransparentForegroundService.isRunning,
        activeNodeName = nodeName,
        showSpeed = showNotificationSpeed,
        uploadSpeed = currentUploadSpeed,
        downloadSpeed = currentDownloadSpeed,
        dataPlaneStatus = SingBoxIpcHub.currentReadiness().status
    )
}

internal suspend fun RootTransparentForegroundService.switchNextNodeFromNotification() {
    if (!RootTransparentForegroundService.isRunning || !notificationNodeSwitchInFlight.compareAndSet(false, true)) return
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
                RootTransparentForegroundService.TAG,
                "Notification node switch failed: ${result.reason}"
            )
        }
    } finally {
        notificationNodeSwitchInFlight.set(false)
    }
}

internal fun RootTransparentForegroundService.resetConnectionsFromNotification() {
    if (!RootTransparentForegroundService.isRunning) return
    val closed = commandManager.closeConnections()
    val reset = rootConnection.service?.resetNetwork() == true
    LogRepository.getInstance().addLog(
        "INFO: Root notification reset connections closed=$closed resetNetwork=$reset"
    )
    requestNotificationUpdate(force = true)
}
internal fun nextRootNotificationNodeId(candidateIds: List<String>, activeNodeId: String?): String? {
    val candidates = candidateIds.map(String::trim).filter(String::isNotBlank).distinct()
    if (candidates.size < 2) return null
    val currentIndex = candidates.indexOf(activeNodeId)
    return candidates[(currentIndex + 1) % candidates.size]
}
