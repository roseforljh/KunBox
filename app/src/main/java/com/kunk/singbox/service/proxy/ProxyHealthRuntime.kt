@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.service

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.LatencyProbeTrafficKind
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.MeteredNodeConfigGuard
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.RuntimeNodeRef
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.service.manager.AttributedConnectionTraffic
import com.kunk.singbox.service.manager.ConnectionTrafficAttributor
import com.kunk.singbox.service.manager.ConnectionTrafficEventData
import com.kunk.singbox.service.manager.ConnectionTrafficEventReader
import com.kunk.singbox.service.manager.ConnectionStormDecision
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.SameNodeFailureLayer
import com.kunk.singbox.service.manager.SameNodeRecoveryCoordinator
import com.kunk.singbox.service.manager.SameNodeRecoveryOutcome
import com.kunk.singbox.service.manager.SameNodeRecoveryPermit
import com.kunk.singbox.service.manager.SameNodeRecoveryStage
import com.kunk.singbox.service.manager.SameNodeRecoveryVerification
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.manager.TimedProbeResult
import com.kunk.singbox.service.manager.UrlTestTagMatcher
import com.kunk.singbox.service.manager.incidentCloseReason
import com.kunk.singbox.service.manager.probePhysicalDns
import com.kunk.singbox.service.manager.toIncidentSnapshot
import com.kunk.singbox.service.manager.toProbeDiagnosticFields
import com.kunk.singbox.utils.perf.BackgroundResourceGuard
import com.kunk.singbox.utils.perf.ResourceGuardRegistration
import com.kunk.singbox.utils.perf.ResourceGuardOwner
import com.kunk.singbox.utils.perf.isResourceRecoveryBudgetError
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ProxyOnlyService"
private const val ACTION_START = ProxyOnlyService.ACTION_START
private const val EXTRA_CONFIG_PATH = ProxyOnlyService.EXTRA_CONFIG_PATH

private var isRunning: Boolean
    get() = ProxyOnlyService.isRunning
    set(value) { ProxyOnlyService.isRunning = value }

private var isStarting: Boolean
    get() = ProxyOnlyService.isStarting
    set(value) { ProxyOnlyService.isStarting = value }

private val lastErrorFlow get() = ProxyOnlyService.lastErrorFlow

private fun setLastError(message: String?) = ProxyOnlyService.setLastError(message)

internal fun ProxyOnlyService.startRuntimeCommandClient() {
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
        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
    }, options)
    runtimeCommandClient = client
    SelectorManager.updateCommandClient(client)
    client.connect()
}

internal fun ProxyOnlyService.createRuntimeCommandOptions(): CommandClientOptions {
    return CommandClientOptions().apply {
        addCommand(Libbox.CommandStatus)
        addCommand(Libbox.CommandGroup)
        addCommand(Libbox.CommandConnections)
        addCommand(Libbox.CommandLog)
        statusInterval = 1_000L * 1_000L * 1_000L
    }
}

internal fun ProxyOnlyService.handleRuntimeLogs(messages: LogIterator?) {
    messages ?: return
    val repository = LogRepository.getInstance()
    while (messages.hasNext()) {
        val message = messages.next()?.message?.takeIf(String::isNotBlank) ?: continue
        if (repository.isEnabled()) repository.addLog(message)
        handleKernelLogForSameNodeRecovery(message)
    }
}

internal fun ProxyOnlyService.handleRuntimeStatus(message: StatusMessage?) {
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

internal fun ProxyOnlyService.handleKernelLogForSameNodeRecovery(message: String) {
    val signal = healthSignalAggregator.observeKernelLog(
        line = message,
        nowMs = SystemClock.elapsedRealtime()
    ) ?: return
    LogRepository.getInstance().addAlwaysLog(HealthSignalAggregator.buildSummary(signal))

    when (signal.kind) {
        HealthSignalKind.RESOURCE_EXHAUSTED -> {
            val registration = resourceGuardRegistration ?: run {
                startResourceGuard()
                resourceGuardRegistration
            }
            if (registration != null) {
                BackgroundResourceGuard.signalResourceExhaustion(registration, "proxy_kernel_emfile")
            } else {
                LogRepository.getInstance().addAlwaysLog(
                    "ERROR recovery resource_exhausted mode=proxy stage=guard_registration_failed"
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
internal fun ProxyOnlyService.submitSameNodeRecovery(layer: SameNodeFailureLayer, trigger: String) {
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

internal fun ProxyOnlyService.createSameNodeRecoveryCoordinator(
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

internal suspend fun ProxyOnlyService.verifySameNodeRecovery(
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

internal suspend fun ProxyOnlyService.resolveSameNodeProbeHost(): String? {
    return runCatching {
        val settings = SettingsRepository.getInstance(applicationContext).settings.first()
        AppSettings.latencyTestUri(settings.latencyTestUrl).host.takeIf(String::isNotBlank)
    }.onFailure { error ->
        Log.w(TAG, "Failed to resolve proxy same-node probe host", error)
    }.getOrNull()
}

internal suspend fun ProxyOnlyService.probeProxyLatency(targetTag: String): Long? {
    val config = loadCurrentRuntimeConfig() ?: return null
    val outbounds = config.outbounds.orEmpty()
    val target = outbounds.firstOrNull {
        UrlTestTagMatcher.normalizeTag(it.tag) == UrlTestTagMatcher.normalizeTag(targetTag)
    } ?: return null
    return runCatching {
        SingBoxCore.getInstance(this@probeProxyLatency).testOutboundLatency(
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

internal suspend fun ProxyOnlyService.reloadCurrentConfigForSameNodeRecovery(): Boolean {
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

internal fun ProxyOnlyService.restartCurrentConfigForSameNodeRecovery(): Boolean {
    val configPath = resolveCurrentRuntimeConfigFile()?.absolutePath ?: return false
    return queueCoreRestart(configPath, setNonResourceRecoveryIntent(false))
}

internal fun ProxyOnlyService.resolveCurrentRuntimeConfigFile(): File? {
    return currentConfigPath
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?: File(filesDir, "running_config.json").takeIf(File::isFile)
}

internal fun ProxyOnlyService.loadCurrentRuntimeConfig(): SingBoxConfig? {
    val configFile = resolveCurrentRuntimeConfigFile() ?: return null
    return runCatching {
        gson.fromJson(configFile.readText(Charsets.UTF_8), SingBoxConfig::class.java)
    }.onFailure { error ->
        Log.w(TAG, "Failed to load proxy runtime config", error)
    }.getOrNull()
}

internal fun ProxyOnlyService.resolveCurrentProxyOutboundTag(): String? {
    return CommandManager.resolveConcreteGroupSelection("PROXY", groupSelectedOutbounds)
        ?: SelectorManager.getSelectedOutbound()
}

internal fun ProxyOnlyService.currentPhysicalNetwork(): Network? {
    val manager = connectivityManager ?: getSystemService(ConnectivityManager::class.java)
    val network = manager.activeNetwork ?: return null
    val capabilities = manager.getNetworkCapabilities(network) ?: return null
    return network.takeIf {
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
}

internal fun ProxyOnlyService.hasValidatedPhysicalNetwork(): Boolean {
    val manager = connectivityManager ?: getSystemService(ConnectivityManager::class.java)
    val network = currentPhysicalNetwork() ?: return false
    return manager.getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}

internal fun ProxyOnlyService.recordSameNodeRecoveryStage(
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

internal fun ProxyOnlyService.handleRuntimeGroups(groups: OutboundGroupIterator?) {
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
    VpnStateStore.setActiveLabel(
        resolveRuntimeNodeLabel(concreteTag, NodeProtectionStore.runtimeMappings())
    )
    notifyRemoteState(state = ServiceState.RUNNING)
    requestNotificationUpdate(force = false)
}

internal fun ProxyOnlyService.handleRuntimeConnectionEvents(events: ConnectionEvents?) {
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

internal fun ProxyOnlyService.enforceRuntimeMeteredProtection(
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

internal fun ProxyOnlyService.enforceConnectionStormGuard(decision: ConnectionStormDecision?) {
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

internal fun ProxyOnlyService.persistConnectionIncident(decision: ConnectionStormDecision, closed: Boolean) {
    val snapshot = decision.toIncidentSnapshot(
        mode = "proxy",
        closeReason = decision.incidentCloseReason(),
        closeSucceeded = closed,
        timestampEpochMs = System.currentTimeMillis(),
        elapsedRealtimeMs = SystemClock.elapsedRealtime()
    )
    serviceScope.launch(Dispatchers.IO) {
        runCatching { connectionIncidentHistory.append(snapshot) }
            .onFailure { error -> Log.e(TAG, "Failed to persist proxy connection incident", error) }
    }
}

internal fun ProxyOnlyService.recordAttributedTraffic(records: List<AttributedConnectionTraffic>) {
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

internal fun ProxyOnlyService.closeRuntimeConnection(connectionId: String): Boolean {
    val client = runtimeCommandClient ?: return false
    return runCatching {
        client.closeConnection(connectionId)
        true
    }.getOrDefault(false)
}

internal fun ProxyOnlyService.closeRuntimeConnections(): Boolean {
    val client = runtimeCommandClient ?: return false
    return runCatching {
        client.closeConnections()
        true
    }.onFailure { error ->
        Log.w(TAG, "Failed to close proxy connections", error)
    }.getOrDefault(false)
}

internal fun ProxyOnlyService.startResourceGuard() {
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

        override fun connectionAttributionSnapshot() = connectionStormGuard.snapshot()

        override fun restartCore(reason: String, attemptId: Long): Boolean {
            return restartCoreForResourceRecovery(reason, attemptId)
        }

        override fun recycleProcess(reason: String) {
            synchronized(this@startResourceGuard) {
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
                    this@startResourceGuard,
                    Intent(this@startResourceGuard, ProxyOnlyService::class.java).apply {
                        action = ACTION_START
                        putExtra(EXTRA_CONFIG_PATH, configPath)
                        putExtra(SingBoxService.EXTRA_RECOVERY, true)
                    }
                )
            }
        }

        override fun publishBudgetExhausted(reason: String) {
            synchronized(this@startResourceGuard) {
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

        override fun clearBudgetExhaustedError() {
            clearResourceRecoveryError()
        }
    })
}

internal fun ProxyOnlyService.clearResourceRecoveryError() = synchronized(this) {
    if (!isResourceRecoveryBudgetError(lastErrorFlow.value)) return@synchronized
    setLastError(null)
    if (isResourceRecoveryBudgetError(VpnStateStore.getLastError())) {
        VpnStateStore.setLastError(null)
    }
    requestNotificationUpdate(force = true)
    notifyRemoteState()
}

internal fun ProxyOnlyService.restartCoreForResourceRecovery(reason: String, attemptId: Long): Boolean {
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

internal fun ProxyOnlyService.detachResourceGuard(attemptId: Long) {
    resourceGuardRegistration?.let { BackgroundResourceGuard.detach(it, attemptId) }
    resourceGuardRegistration = null
}

internal fun ProxyOnlyService.cancelResourceGuard() {
    resourceGuardCancellationGeneration.incrementAndGet()
    BackgroundResourceGuard.cancelOwner(resourceGuardOwnerId)
    resourceGuardRegistration = null
    clearResourceRecoveryIntent(synchronized(this) { pendingRecoveryIntentLease })
}

internal fun ProxyOnlyService.isResourceRecoveryLeaseCurrent(): Boolean {
    val lease = pendingRecoveryIntentLease ?: return false
    return lease.allowsResourceClaim && ServiceStateHolder.isRecoveryIntentCurrent(lease)
}

internal fun ProxyOnlyService.claimResourceRecoveryIntent(attemptId: Long): RecoveryIntentLease? {
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

internal fun ProxyOnlyService.setNonResourceRecoveryIntent(preserve: Boolean): RecoveryIntentLease = synchronized(this) {
    ServiceStateHolder.setRecoveryIntentOnFailure(preserve).also { lease ->
        pendingRecoveryIntentLease = lease
    }
}

internal fun ProxyOnlyService.clearResourceRecoveryIntent(lease: RecoveryIntentLease?) {
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

internal fun ProxyOnlyService.completeRecoveryIntentOnSuccess(lease: RecoveryIntentLease): RecoveryIntentLease? = synchronized(this) {
    val baseline = ServiceStateHolder.completeRecoveryIntentOnSuccess(lease) ?: return@synchronized null
    if (pendingRecoveryIntentLease === lease) pendingRecoveryIntentLease = baseline
    baseline
}

internal fun ProxyOnlyService.setLastErrorIfCurrent(lease: RecoveryIntentLease, message: String): Boolean = synchronized(this) {
    if (!ServiceStateHolder.isRecoveryIntentCurrent(lease)) return@synchronized false
    setLastError(message)
    true
}
