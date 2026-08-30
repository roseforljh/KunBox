@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.service

import android.app.Service
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.MeteredNodeConfigGuard
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.RecoveryPolicy
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.utils.LocalNetworkPermission
import com.kunk.singbox.utils.NetworkClient
import com.kunk.singbox.utils.perf.BackgroundResourceGuard
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

private const val TAG = "ProxyOnlyService"
private const val PORT_WAIT_TIMEOUT_MS = 5_000L
private const val STOP_FOREGROUND_REMOVE = android.app.Service.STOP_FOREGROUND_REMOVE

private var isRunning: Boolean
    get() = ProxyOnlyService.isRunning
    set(value) { ProxyOnlyService.isRunning = value }

private var isStarting: Boolean
    get() = ProxyOnlyService.isStarting
    set(value) { ProxyOnlyService.isStarting = value }

private fun setLastError(message: String?) = ProxyOnlyService.setLastError(message)

private fun shouldContinueCoreStartAfterForegroundResult(started: Boolean): Boolean =
    ProxyOnlyService.shouldContinueCoreStartAfterForegroundResult(started)

@Suppress(
    "CognitiveComplexMethod",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "LongMethod",
    "ReturnCount"
)
internal fun ProxyOnlyService.startCore(configPath: String, recoveryIntentLease: RecoveryIntentLease) {
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
            val ruleSetRepo = RuleSetRepository.getInstance(this@startCore)
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

            val settingsRepository = SettingsRepository.getInstance(this@startCore)
            settingsRepository.reloadFromStorage()
            val settings = settingsRepository.settings.first()
            if (!LocalNetworkPermission.canApplySettings(this@startCore, settings)) {
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
                SingBoxCore.ensureLibboxSetup(this@startCore)
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
            val server = synchronized(this@startCore) {
                if (!ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease) ||
                    isStopping || activeStartRecoveryIntentLease !== recoveryIntentLease
                ) {
                    null
                } else {
                    Libbox.newCommandServer(serverHandler, platformInterface).also { createdServer ->
                        commandServer = createdServer
                    }
                }
            }
            if (server == null) {
                Log.w(TAG, "Proxy CommandServer creation ignored for superseded recovery lease")
                withContext(Dispatchers.Main) { stopSupersededStartup() }
                return@launch
            }
            currentCoroutineContext().ensureActive()
            server.start()
            BoxWrapperManager.init(server)
            currentCoroutineContext().ensureActive()
            server.startOrReloadService(configContent, overrideOptions)

            val baselineLease = synchronized(this@startCore) {
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
                VpnStateStore.setStopOwnerMode(VpnStateStore.CoreMode.PROXY)
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
            synchronized(this@startCore) {
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

internal suspend fun ProxyOnlyService.performHotSwitch(
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
                    ?: resolveRuntimeNodeLabel(
                        concreteTag ?: outboundTag,
                        NodeProtectionStore.runtimeMappings()
                    )
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

internal fun ProxyOnlyService.recordProxyHotSwitchEvent(
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

internal fun ProxyOnlyService.initializeRuntimeSelector(configContent: String) {
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

internal fun ProxyOnlyService.restrictLocalNetworkListenIfNeeded(configContent: String): String {
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

internal fun ProxyOnlyService.queueCoreRestart(configPath: String, recoveryIntentLease: RecoveryIntentLease): Boolean {
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

internal fun ProxyOnlyService.stopSupersededStartup() {
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

internal fun ProxyOnlyService.clearStartupFailureState(recoveryIntentLease: RecoveryIntentLease): Boolean = synchronized(this) {
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
        if (VpnStateStore.getStopOwnerMode() == VpnStateStore.CoreMode.PROXY) {
            VpnStateStore.clearStopOwnerMode()
        }
    }
    VpnTileService.persistVpnPending("")
    VpnStateStore.clearRecoveryClaim()
    notifyRemoteState(state = ServiceState.STOPPED)
    updateTileState()
    true
}

@OptIn(DelicateCoroutinesApi::class)
@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
internal fun ProxyOnlyService.stopCore(
    stopService: Boolean,
    recoveryIntentLease: RecoveryIntentLease,
    resourceRecoveryAttemptId: Long? = recoveryIntentLease.attemptId
): Job? {
    if (ServiceStateHolder.shouldIgnoreDuplicateHardStop(isStopping, stopSelfRequested)) {
        Log.i(TAG, "Ignoring duplicate hard stop while cleanup is already running")
        return cleanupJob
    }
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
            configGenerationJob?.cancel()
            configGenerationJob = null
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
            .getInstance(this@stopCore)
            .settings.value.proxyPort
    }.getOrDefault(2080)

    // ponytail: ATOMIC 仅保证销毁竞态中进入不可取消清理段，任务仍由 cleanupSupervisorJob 持有。
    val job = cleanupScope.launch(start = CoroutineStart.ATOMIC) {
        withContext(NonCancellable) {
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
                    synchronized(this@stopCore) {
                        cleanupJob = null
                    }
                    return@withContext
                }
                val (restartConfigPath, restartLease, hardStopLease) =
                    synchronized(this@stopCore) {
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
                        val completed = synchronized(this@stopCore) {
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
                                if (VpnStateStore.getStopOwnerMode() == VpnStateStore.CoreMode.PROXY) {
                                    VpnStateStore.clearStopOwnerMode()
                                }
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
                        this@stopCore
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
    if (stopService) {
        cleanupScope.launch {
            try {
                withTimeout(6_000L) { job.join() }
            } catch (_: TimeoutCancellationException) {
                Log.e(TAG, "Stop watchdog fired after 6000ms")
                forceStopProcess("shutdown_timeout")
            }
        }
    }
    job.invokeOnCompletion {
        synchronized(this) {
            if (cleanupJob === job) cleanupJob = null
        }
    }
    return job
}
