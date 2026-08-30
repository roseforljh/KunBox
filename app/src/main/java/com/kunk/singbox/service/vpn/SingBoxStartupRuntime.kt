@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.service

import android.content.Intent
import android.net.NetworkCapabilities
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.MeteredNodeConfigGuard
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.manager.PlatformInterfaceImpl
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.SameNodeFailureLayer
import com.kunk.singbox.service.manager.SameNodeRecoveryCoordinator
import com.kunk.singbox.service.manager.SameNodeRecoveryOutcome
import com.kunk.singbox.service.manager.SameNodeRecoveryPermit
import com.kunk.singbox.service.manager.SameNodeRecoveryStage
import com.kunk.singbox.service.manager.SameNodeRecoveryVerification
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.TimedProbeResult
import com.kunk.singbox.service.manager.UrlTestTagMatcher
import com.kunk.singbox.service.manager.probePhysicalDns
import com.kunk.singbox.service.manager.toProbeDiagnosticFields
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.utils.L
import com.kunk.singbox.utils.NetworkClient
import com.kunk.singbox.utils.perf.BackgroundResourceGuard
import com.kunk.singbox.utils.perf.ResourceGuardRegistration
import com.kunk.singbox.utils.perf.ResourceGuardOwner
import com.kunk.singbox.utils.perf.isResourceRecoveryBudgetError
import io.nekohasekai.libbox.*
import java.io.File
import kotlin.math.absoluteValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private const val STOP_FOREGROUND_REMOVE = android.app.Service.STOP_FOREGROUND_REMOVE

internal fun SingBoxService.initializeStartupNodeLabel(configPath: String, explicitTag: String? = pendingNodeName) {
    val startupTag = runCatching {
        resolveStartupProxyTag(configPath, gson, explicitTag)
    }.onFailure { e ->
        Log.w(SingBoxService.TAG, "Failed to resolve startup node label", e)
    }.getOrNull()
    realTimeNodeName = null
    VpnStateStore.setActiveLabel(null)
    Log.i(SingBoxService.TAG, "Startup selection pending kernel confirmation: ${startupTag ?: "(none)"}")
}

internal fun SingBoxService.updateServiceState(state: ServiceState) {
    if (state == ServiceState.STARTING) {
        SingBoxService.setLastError(null)
        VpnStateStore.setLastError(null)
    }
    if (serviceState == state) return
    serviceState = state
    when (state) {
        ServiceState.STARTING -> {
            activeVpnSessionId = vpnSessionGeneration.incrementAndGet()
            SingBoxIpcHub.updateReadiness { readiness ->
                readiness.beginVpnSession(
                    serviceInstanceId = SingBoxIpcHub.serviceInstanceId(),
                    sessionId = activeVpnSessionId
                )
            }
        }
        ServiceState.STOPPED -> SingBoxIpcHub.updateReadiness { readiness ->
            com.kunk.singbox.ipc.DataPlaneReadinessSnapshot.stopped(
                serviceInstanceId = readiness.serviceInstanceId
            ).copy(lastReadinessReason = "service_stopped")
        }
        else -> Unit
    }
    if (state == ServiceState.RUNNING) {
        startResourceGuard()
    }
    requestRemoteStateUpdate(force = true)
}

internal fun SingBoxService.currentServiceStateRuntime(): ServiceState = serviceState

internal fun SingBoxService.forceStopProcess(reason: String) {
    val manuallyStopped = VpnStateStore.isManuallyStopped()
    Log.e(SingBoxService.TAG, "Force stopping VPN process reason=$reason manuallyStopped=$manuallyStopped")
    runCatching {
        cancelResourceGuard()
        SingBoxService.isRunning = false
        SingBoxService.isStarting = false
        NetworkClient.onVpnStateChanged(false)
        VpnTileService.persistVpnState(false)
        VpnStateStore.clearRuntimeState(preserveLastError = manuallyStopped)
        if (manuallyStopped) {
            VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
            if (VpnStateStore.getStopOwnerMode() == VpnStateStore.CoreMode.VPN) {
                VpnStateStore.clearStopOwnerMode()
            }
            VpnStateStore.clearRecoveryClaim()
        }
        VpnTileService.persistVpnPending("")
        updateServiceState(ServiceState.STOPPED)
        updateTileState()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }.onFailure { error ->
        Log.e(SingBoxService.TAG, "Failed to persist force-stop state", error)
    }
    stopSelf()
    Process.killProcess(Process.myPid())
}

@Suppress("CognitiveComplexMethod")
internal fun SingBoxService.startResourceGuard() {
    val registration = ResourceGuardRegistration(
        ownerId = resourceGuardOwnerId,
        generation = resourceGuardGeneration.incrementAndGet()
    )
    resourceGuardRegistration = registration
    BackgroundResourceGuard.start(this, serviceScope, registration, object : ResourceGuardOwner {
        override fun isRecoveryAllowed(): Boolean {
            return !VpnStateStore.isManuallyStopped() &&
                VpnStateStore.getMode() == VpnStateStore.CoreMode.VPN &&
                isResourceRecoveryLeaseCurrent()
        }

        override fun connectionAttributionSnapshot() = commandManager.connectionAttributionSnapshot()

        override fun restartCore(reason: String, attemptId: Long): Boolean {
            if (!BackgroundResourceGuard.isRecoveryAttemptActive(resourceGuardOwnerId, attemptId)) return false
            val configPath = SingBoxService.lastConfigPath
                ?.takeIf { File(it).isFile }
                ?: File(filesDir, "running_config.json").takeIf(File::isFile)?.absolutePath
                ?: return false
            LogRepository.getInstance().addAlwaysLog("WARN recovery resource_exhausted restart=$reason")
            val recoveryIntentLease = claimResourceRecoveryIntent(attemptId) ?: return false
            if (!BackgroundResourceGuard.isRecoveryAttemptActive(resourceGuardOwnerId, attemptId)) {
                clearResourceRecoveryIntent(recoveryIntentLease)
                return false
            }
            return performFullRestart(configPath, recoveryIntentLease)
        }

        override fun recycleProcess(reason: String) {
            synchronized(this@startResourceGuard) {
                if (VpnStateStore.isManuallyStopped() ||
                    VpnStateStore.getMode() != VpnStateStore.CoreMode.VPN ||
                    !isResourceRecoveryLeaseCurrent()
                ) {
                    return
                }
                if (SingBoxIpcHub.currentReadiness().lockdownStatus !=
                    com.kunk.singbox.ipc.VpnLockdownStatus.ENABLED
                ) {
                    publishBudgetExhausted("process_reclaim_blocked_without_lockdown:$reason")
                    SingBoxIpcHub.updateReadiness { readiness ->
                        readiness.copy(
                            status = com.kunk.singbox.ipc.DataPlaneStatus.FAILED_BLOCKED,
                            coreReady = false,
                            selectorReady = false,
                            recoveryActive = false,
                            lastReadinessReason = "process_reclaim_requires_lockdown"
                        )
                    }
                    return
                }
                val configPath = SingBoxService.lastConfigPath
                    ?.takeIf { File(it).isFile }
                    ?: File(filesDir, "running_config.json").takeIf(File::isFile)?.absolutePath
                    ?: run {
                        publishBudgetExhausted("missing_config:$reason")
                        return
                    }
                LogRepository.getInstance()
                    .addAlwaysLog("ERROR recovery resource_exhausted recycle_process=$reason")
                recycleBackgroundProcess(
                    this@startResourceGuard,
                    Intent(this@startResourceGuard, SingBoxService::class.java).apply {
                        action = SingBoxService.ACTION_START
                        putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
                        putExtra(SingBoxService.EXTRA_RECOVERY, true)
                    }
                )
            }
        }

        override fun publishBudgetExhausted(reason: String) {
            synchronized(this@startResourceGuard) {
                if (VpnStateStore.isManuallyStopped() ||
                    VpnStateStore.getMode() != VpnStateStore.CoreMode.VPN ||
                    !isResourceRecoveryLeaseCurrent()
                ) {
                    return
                }
                val message = "Resource recovery budget exhausted: $reason"
                SingBoxService.setLastError(message)
                LogRepository.getInstance().addAlwaysLog("ERROR recovery resource_exhausted $message")
                requestNotificationUpdate(force = true)
                requestRemoteStateUpdate(force = true)
            }
        }

        override fun clearBudgetExhaustedError() {
            synchronized(this@startResourceGuard) {
                if (!isResourceRecoveryBudgetError(SingBoxService.lastErrorFlow.value)) return
                SingBoxService.setLastError(null)
                if (isResourceRecoveryBudgetError(VpnStateStore.getLastError())) {
                    VpnStateStore.setLastError(null)
                }
                requestNotificationUpdate(force = true)
                requestRemoteStateUpdate(force = true)
            }
        }
    })
}

internal fun SingBoxService.detachResourceGuard(attemptId: Long) {
    resourceGuardRegistration?.let { BackgroundResourceGuard.detach(it, attemptId) }
    resourceGuardRegistration = null
}

internal fun SingBoxService.cancelResourceGuard() {
    BackgroundResourceGuard.cancelOwner(resourceGuardOwnerId)
    resourceGuardRegistration = null
    clearResourceRecoveryIntent(synchronized(this) { pendingRecoveryIntentLease })
}

internal fun SingBoxService.isResourceRecoveryLeaseCurrent(): Boolean {
    val lease = pendingRecoveryIntentLease ?: return false
    return lease.allowsResourceClaim && ServiceStateHolder.isRecoveryIntentCurrent(lease)
}

internal fun SingBoxService.claimResourceRecoveryIntent(attemptId: Long): RecoveryIntentLease? {
    return synchronized(this) {
        if (VpnStateStore.isManuallyStopped() ||
            VpnStateStore.getMode() != VpnStateStore.CoreMode.VPN ||
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

internal fun SingBoxService.setNonResourceRecoveryIntent(preserve: Boolean): RecoveryIntentLease = synchronized(this) {
    ServiceStateHolder.setRecoveryIntentOnFailure(preserve).also { lease ->
        pendingRecoveryIntentLease = lease
    }
}

internal fun SingBoxService.clearResourceRecoveryIntent(lease: RecoveryIntentLease?) {
    lease ?: return
    synchronized(this) {
        if (pendingRecoveryIntentLease !== lease) return
        val ownedAttemptId = lease.attemptId ?: return
        if (ServiceStateHolder.clearResourceRecoveryIntent(resourceGuardOwnerId, ownedAttemptId, lease) &&
            pendingRecoveryIntentLease === lease
        ) {
            pendingRecoveryIntentLease = null
        }
    }
}

internal fun SingBoxService.completeRecoveryIntentOnSuccess(lease: RecoveryIntentLease): Boolean = synchronized(this) {
    val baseline = ServiceStateHolder.completeRecoveryIntentOnSuccess(lease) ?: return@synchronized false
    if (pendingRecoveryIntentLease === lease) pendingRecoveryIntentLease = baseline
    true
}

/**
 *
 * @return true if hot switch triggered successfully, false if restart is needed
 *
 * 核心原理:
 * PROXY selector 固定使用 interrupt_exist_connections=false。
 * 内核确认新选择后主动关闭旧连接，防止旧节点的 TCP、QUIC 和复用连接继续产生流量。
 */

internal suspend fun SingBoxService.hotSwitchNode(nodeTag: String): Boolean {
    if (!coreManager.isServiceRunning() || !SingBoxService.isRunning) return false

    try {
        L.connection("HotSwitch", "Starting switch to: $nodeTag")

        // Step 1: 唤醒核心
        coreManager.wakeService()
        L.step("HotSwitch", 1, 2, "Called wakeService()")

        L.step("HotSwitch", 2, 2, "Calling SelectorManager.switchNode...")

        when (val result = SelectorManager.switchNode(nodeTag)) {
            is SelectorManager.SwitchResult.Success -> {
                val connectionsClosed = commandManager.closeConnections()
                if (!connectionsClosed) {
                    L.warn("HotSwitch", "Kernel selected $nodeTag but old connections could not be closed")
                    return false
                }
                L.result("HotSwitch", true, "Switched to $nodeTag via ${result.method}")
                requestNotificationUpdate(force = true)
                return true
            }
            is SelectorManager.SwitchResult.NeedRestart -> {
                L.warn("HotSwitch", "Need restart: ${result.reason}")
                // 需要完整重启，返回 false 让调用者处理
                return false
            }
        }
    } catch (e: Exception) {
        L.error("HotSwitch", "Unexpected exception", e)
        return false
    }
}

internal fun SingBoxService.cacheUidToPackage(uid: Int, pkg: String) {
    if (uid <= 0 || pkg.isBlank()) return
    uidToPackageCache[uid] = UidPackageCacheEntry(
        packageName = pkg,
        cachedAtMs = SystemClock.elapsedRealtime()
    )
    if (uidToPackageCache.size > maxUidToPackageCacheSize) {
        uidToPackageCache.clear()
    }
}

internal fun SingBoxService.getCachedPackageForUid(uid: Int): String? {
    val entry = uidToPackageCache[uid] ?: return null
    if (!PlatformInterfaceImpl.isUidPackageCacheFresh(entry.cachedAtMs, SystemClock.elapsedRealtime())) {
        uidToPackageCache.remove(uid, entry)
        return null
    }
    return entry.packageName
}

internal fun SingBoxService.handleDefaultNetworkChanged() {
    val serviceUnavailable = !SingBoxService.isRunning || SingBoxService.isStarting
    val shutdownInProgress = isStopping || SingBoxService.isManuallyStopped
    if (serviceUnavailable || shutdownInProgress) {
        Log.d(SingBoxService.TAG, "Default network reset skipped because service is not runnable")
        return
    }

    lastAutoFailoverNetworkEventAtMs = System.currentTimeMillis()
    val reset = BoxWrapperManager.resetNetwork()
    Log.i(SingBoxService.TAG, "Default physical network changed, core reset=$reset")
}

internal fun SingBoxService.handleTrafficUpdateForAutoFailover(snapshot: TrafficMonitor.TrafficSnapshot) {
    val totalSpeed = snapshot.uploadSpeed + snapshot.downloadSpeed
    if (!SingBoxService.shouldRecordMeaningfulTrafficForAutoFailover(totalSpeed)) {
        return
    }
    lastMeaningfulTrafficAtMs = System.currentTimeMillis()
}

internal fun SingBoxService.handleKernelLogForHealthSignal(message: String) {
    val signal = healthSignalAggregator.observeKernelLog(
        line = message,
        nowMs = SystemClock.elapsedRealtime()
    ) ?: return

    LogRepository.getInstance().addAlwaysLog(HealthSignalAggregator.buildSummary(signal))
    when (signal.kind) {
        HealthSignalKind.RESOURCE_EXHAUSTED -> {
            handleResourceExhaustionSignal("kernel_emfile")
            return
        }
        HealthSignalKind.ACTIVE_PROBE_FAILED -> {
            handleActiveOutboundFailure(signal)
            return
        }
        HealthSignalKind.REMOTE_DNS_TIMEOUT -> Unit
    }

    val runningConfig = loadLastRunningConfig()
    val currentProxyTag = resolveCurrentProxyOutboundTag()
    if (runningConfig != null) {
        notifySingleNodeRouteFailureIfNeeded(signal, currentProxyTag, runningConfig)
    }
    if (runningConfig == null ||
        SingBoxService.shouldSubmitMainAutoFailoverForDnsSignal(
            dnsServerTag = signal.dnsServerTag,
            currentProxyTag = currentProxyTag,
            config = runningConfig
        )
    ) {
        submitSameNodeRecovery(
            layer = SameNodeFailureLayer.DNS,
            trigger = "dns_remote_timeout"
        )
    }
}

internal fun SingBoxService.handleActiveOutboundFailure(signal: HealthSignal) {
    val failureTag = signal.outboundTag?.trim().orEmpty()
    val runningConfig = loadLastRunningConfig()
    val currentProxyTag = resolveCurrentProxyOutboundTag()
    if (failureTag.isBlank() || runningConfig == null ||
        SingBoxService.shouldRecoverMainOutboundFailure(failureTag, currentProxyTag, runningConfig)
    ) {
        submitSameNodeRecovery(
            layer = SameNodeFailureLayer.PROXY,
            trigger = "active_probe_failed:$failureTag"
        )
        return
    }

    val guardTriggered = commandManager.handleOutboundFailureBurst(
        outboundTag = failureTag,
        failureCount = signal.failureCount,
        nowMs = SystemClock.elapsedRealtime()
    )
    notifySingleNodeRouteFailureIfNeeded(failureTag)
    LogRepository.getInstance().addAlwaysLog(
        "WARN recovery app_route_failure outbound=$failureTag selected=${currentProxyTag.orEmpty()} " +
            "guard=$guardTriggered scope=failed_outbound_only"
    )
}

internal fun SingBoxService.handleResourceExhaustionSignal(reason: String) {
    autoFailoverJob?.cancel()
    autoFailoverJob = null
    autoFailoverCandidateCache.clear()
    val registration = resourceGuardRegistration ?: run {
        startResourceGuard()
        resourceGuardRegistration
    }
    if (registration != null) {
        BackgroundResourceGuard.signalResourceExhaustion(registration, reason)
    } else {
        LogRepository.getInstance().addAlwaysLog(
            "ERROR recovery resource_exhausted stage=guard_registration_failed reason=$reason"
        )
    }
}

internal fun SingBoxService.notifySingleNodeRouteFailureIfNeeded(
    signal: HealthSignal,
    currentProxyTag: String?,
    runningConfig: SingBoxConfig
) {
    val failureTag = SingBoxService.resolveSingleNodeRouteFailureTag(
        dnsServerTag = signal.dnsServerTag,
        currentProxyTag = currentProxyTag,
        config = runningConfig
    ) ?: return

    notifySingleNodeRouteFailureIfNeeded(failureTag)
}

internal fun SingBoxService.notifySingleNodeRouteFailureIfNeeded(failureTag: String) {

    val now = SystemClock.elapsedRealtime()
    val lastNotifyAt = singleNodeRouteFailureNotificationTimes[failureTag] ?: 0L
    if (!SingBoxService.shouldNotifySingleNodeRouteFailure(failureTag, lastNotifyAt, now)) {
        return
    }
    singleNodeRouteFailureNotificationTimes[failureTag] = now

    val configRepository = ConfigRepository.getInstance(this@notifySingleNodeRouteFailureIfNeeded)
    val displayName = configRepository.resolveNodeNameFromOutboundTag(failureTag) ?: failureTag
    val message = SingBoxService.buildSingleNodeRouteFailureNotificationText(displayName)
    LogRepository.getInstance().addLog("WARN: $message")

    val notificationId = 2600 + (failureTag.hashCode().absoluteValue % 500)
    val notification = notificationManager.createStartingNotification(message)
    notificationManager.showTemporaryNotification(notificationId, notification)
    serviceScope.launch {
        delay(8000)
        notificationManager.cancelNotification(VpnNotificationManager.NOTIFICATION_ID + notificationId)
    }
}

@Suppress("CognitiveComplexMethod", "ComplexCondition")
internal fun SingBoxService.submitSameNodeRecovery(layer: SameNodeFailureLayer, trigger: String) {
    if (!SingBoxService.isRunning || SingBoxService.isStarting || isStopping || SingBoxService.isManuallyStopped) {
        return
    }
    if (autoFailoverJob?.isActive == true || !sameNodeRecoveryInFlight.compareAndSet(false, true)) {
        Log.d(SingBoxService.TAG, "[SameNodeRecovery] ignored because recovery/failover is already running")
        return
    }

    when (sameNodeRecoveryGate.acquire(SystemClock.elapsedRealtime())) {
        SameNodeRecoveryPermit.COOLDOWN -> {
            sameNodeRecoveryInFlight.set(false)
            LogRepository.getInstance().addAlwaysLog(
                "INFO recovery same_node skipped=cooldown layer=$layer trigger=$trigger"
            )
        }
        SameNodeRecoveryPermit.BUDGET_EXHAUSTED -> {
            sameNodeRecoveryInFlight.set(false)
            LogRepository.getInstance().addAlwaysLog(
                "WARN recovery same_node budget_exhausted layer=$layer trigger=$trigger escalate=auto_failover"
            )
            submitAutoFailoverSuspicion(trigger)
        }
        SameNodeRecoveryPermit.ACQUIRED -> {
            val recoveryJob = autoFailoverScope.launch(start = CoroutineStart.LAZY) {
                var escalateToFailover = false
                try {
                    val outcome = createSameNodeRecoveryCoordinator(layer, trigger).recover(layer)
                    escalateToFailover = outcome == SameNodeRecoveryOutcome.Failed
                    LogRepository.getInstance().addAlwaysLog(
                        "INFO recovery same_node completed layer=$layer trigger=$trigger outcome=$outcome"
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    escalateToFailover = true
                    Log.e(SingBoxService.TAG, "[SameNodeRecovery] failed", error)
                    LogRepository.getInstance().addAlwaysLog(
                        "ERROR recovery same_node exception=${error.javaClass.simpleName} " +
                            "message=${error.message.orEmpty()} layer=$layer trigger=$trigger"
                    )
                } finally {
                    sameNodeRecoveryInFlight.set(false)
                    val currentJob = coroutineContext[Job]
                    if (autoFailoverJob === currentJob) {
                        autoFailoverJob = null
                    }
                }
                if (escalateToFailover) {
                    submitAutoFailoverSuspicion(trigger)
                }
            }
            autoFailoverJob = recoveryJob
            recoveryJob.start()
        }
    }
}

internal fun SingBoxService.createSameNodeRecoveryCoordinator(
    layer: SameNodeFailureLayer,
    trigger: String
): SameNodeRecoveryCoordinator {
    return SameNodeRecoveryCoordinator(object : SameNodeRecoveryCoordinator.Actions {
        override fun hasPhysicalNetwork(): Boolean = hasValidatedPhysicalNetwork()

        override fun currentNodeTag(): String? = resolveCurrentProxyOutboundTag()

        override suspend fun closeConnections(): Boolean {
            healthSignalAggregator.clearDnsFailures()
            return commandManager.closeConnections()
        }

        override suspend fun resetNetwork(): Boolean {
            healthSignalAggregator.clearDnsFailures()
            return BoxWrapperManager.resetNetwork()
        }

        override suspend fun reloadCurrentConfig(): Boolean {
            healthSignalAggregator.clearDnsFailures()
            return reloadCurrentConfigForSameNodeRecovery()
        }

        override fun restartCurrentConfig(): Boolean {
            return restartCurrentConfigForSameNodeRecovery()
        }

        override suspend fun verify(
            nodeTag: String,
            layer: SameNodeFailureLayer
        ): SameNodeRecoveryVerification {
            return verifySameNodeRecovery(nodeTag, layer)
        }

        override fun record(stage: SameNodeRecoveryStage, verification: SameNodeRecoveryVerification?) {
            recordSameNodeRecoveryStage(stage, layer, trigger, verification)
        }
    })
}

internal suspend fun SingBoxService.verifySameNodeRecovery(
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
                network = getCurrentPhysicalNetwork(),
                host = probeHost,
                timeoutMs = SingBoxService.SAME_NODE_DNS_PROBE_TIMEOUT_MS
            )
        },
        proxyProbe = {
            val latency = probeAutoFailoverTargetLatency(nodeTag)
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
    ).also { verification ->
        if (layer == SameNodeFailureLayer.DNS && dnsFailures > 0) {
            Log.w(SingBoxService.TAG, "[SameNodeRecovery] DNS still failing count=$dnsFailures")
        }
    }
}

internal suspend fun SingBoxService.resolveSameNodeProbeHost(): String? {
    return runCatching {
        val settings = SettingsRepository.getInstance(applicationContext).settings.first()
        AppSettings.latencyTestUri(settings.latencyTestUrl).host.takeIf(String::isNotBlank)
    }.onFailure { error ->
        Log.w(SingBoxService.TAG, "[SameNodeRecovery] failed to resolve probe host", error)
    }.getOrNull()
}

internal suspend fun SingBoxService.reloadCurrentConfigForSameNodeRecovery(): Boolean {
    if (!SingBoxService.isRunning || isStopping) return false
    val configFile = resolveCurrentRuntimeConfigFile() ?: return false
    return runCatching {
        val configContent = withContext(Dispatchers.IO) { configFile.readText(Charsets.UTF_8) }
        MeteredNodeConfigGuard.requireRuntimeConfigAuthorized(
            configContent = configContent,
            selectedNodeId = VpnStateStore.getSelectedNodeId()
        )
        val settingsRepository = SettingsRepository.getInstance(applicationContext)
        settingsRepository.reloadFromStorage()
        val settings = settingsRepository.settings.first()
        coreManager.setCurrentSettings(settings)
        val startToken = coreManager.captureStartToken() ?: return false
        val runtimeConfig = prepareRuntimeConfigForLocalNetwork(configContent, settings)
        val reloaded = coreManager.hotReloadConfig(runtimeConfig, startToken).getOrThrow()
        if (reloaded) {
            commandManager.getCommandServer()?.let(BoxWrapperManager::init)
        }
        reloaded
    }.onFailure { error ->
        Log.w(SingBoxService.TAG, "[SameNodeRecovery] hot reload failed", error)
    }.getOrDefault(false)
}

internal fun SingBoxService.restartCurrentConfigForSameNodeRecovery(): Boolean {
    val configFile = resolveCurrentRuntimeConfigFile() ?: return false
    return performFullRestart(
        configPath = configFile.absolutePath,
        recoveryIntentLease = setNonResourceRecoveryIntent(false)
    )
}

internal fun SingBoxService.resolveCurrentRuntimeConfigFile(): File? {
    return SingBoxService.lastConfigPath
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?: File(filesDir, "running_config.json").takeIf(File::isFile)
}

internal fun SingBoxService.hasValidatedPhysicalNetwork(): Boolean {
    val network = getCurrentPhysicalNetwork() ?: return false
    val capabilities = connectivityManager?.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

internal fun SingBoxService.recordSameNodeRecoveryStage(
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
            append("INFO recovery same_node stage=$stage ")
            append("phase=${if (verification == null) "action" else "verify"} ")
            append("layer=$layer trigger=$trigger ")
            append("network=${physicalNetworkSummary()} ")
            append("selected=${resolveCurrentProxyOutboundTag() ?: "(none)"} ")
            append("connections=${recentConnectionIds.size} ")
            append("dns=${verification?.dnsHealthy ?: "unknown"} ")
            append("proxy=${verification?.proxyHealthy ?: "unknown"} ")
            append("selector=${verification?.selectorMatches ?: "unknown"} ")
            append("probe_attempts=${verification?.probeAttempts ?: 0} ")
            append("probe_loss_pct=${probeLossPercent ?: -1} ")
            append(verification?.toProbeDiagnosticFields() ?: "remote_dns_failures=-1")
        }
    )
}

internal fun SingBoxService.physicalNetworkSummary(): String {
    val network = getCurrentPhysicalNetwork() ?: return "missing"
    val capabilities = connectivityManager?.getNetworkCapabilities(network) ?: return "unknown"
    val transport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
    return "$transport:validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
}

internal fun SingBoxService.submitAutoFailoverSuspicion(trigger: String) {
    if (autoFailoverJob?.isActive == true) {
        Log.d(SingBoxService.TAG, "[AutoFailover] suspicion ignored, job already running: $trigger")
        return
    }

    val now = System.currentTimeMillis()
    val context = NodeAutoFailoverPolicy.TriggerContext(
        isVpnRunning = SingBoxService.isRunning,
        isManuallyStopped = SingBoxService.isManuallyStopped,
        isAutoFailoverInFlight = autoFailoverJob?.isActive == true,
        inStartupGracePeriod = isAutoFailoverStartupGracePeriod(now),
        inNetworkChangeGracePeriod = isAutoFailoverNetworkGracePeriod(now),
        isProxyIdle = false,
        lastMeaningfulTrafficAtMs = SingBoxService.resolveAutoFailoverTrafficSignalAtMs(
            trigger = trigger,
            isAppInForeground = isAppInForeground,
            lastMeaningfulTrafficAtMs = lastMeaningfulTrafficAtMs,
            nowAtMs = now
        ),
        nowAtMs = now,
        lastAutoFailoverAtMs = VpnStateStore.getLastAutoFailoverAtMs(),
        budgetWindowStartAtMs = VpnStateStore.getAutoFailoverWindowStartAtMs(),
        budgetCount = VpnStateStore.getAutoFailoverCountInWindow(),
        isAutoSelectionEnabled = ConfigRepository.getInstance(this@submitAutoFailoverSuspicion)
            .isProfileAutoSelectionEnabled(VpnStateStore.getSelectedProfileId()),
        hasResourceFailure = BackgroundResourceGuard.isRecovering(),
        hasPhysicalNetwork = getCurrentPhysicalNetwork() != null
    )

    if (!NodeAutoFailoverPolicy.shouldStartProbe(context, trigger = trigger)) {
        Log.d(SingBoxService.TAG, "[AutoFailover] suspicion ignored by policy: $trigger")
        return
    }

    autoFailoverJob = autoFailoverScope.launch {
        runAutoFailoverProbeSequence(trigger)
    }
}

internal fun SingBoxService.isAutoFailoverStartupGracePeriod(nowAtMs: Long): Boolean {
    val startedAtMs = autoFailoverServiceStartedAtMs
    if (startedAtMs <= 0L || nowAtMs < startedAtMs) {
        return false
    }
    return nowAtMs - startedAtMs < SingBoxService.AUTO_FAILOVER_STARTUP_GRACE_MS
}

internal fun SingBoxService.isAutoFailoverNetworkGracePeriod(nowAtMs: Long): Boolean {
    val eventAtMs = lastAutoFailoverNetworkEventAtMs
    if (eventAtMs <= 0L || nowAtMs < eventAtMs) {
        return false
    }
    return nowAtMs - eventAtMs < SingBoxService.AUTO_FAILOVER_NETWORK_GRACE_MS
}
