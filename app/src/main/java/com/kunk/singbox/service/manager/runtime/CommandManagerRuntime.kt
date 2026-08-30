@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements")

package com.kunk.singbox.service.manager

import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.RuntimeNodeRef
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.service.resolveRuntimeNodeLabel
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.selects.select

internal fun CommandManager.createLogClientHandler(
    delegate: CommandClientHandler,
    generation: Long,
    token: Long,
    signals: CommandManager.CommandLogAttemptSignals
): CommandClientHandler = object : CommandClientHandler by delegate {
    private val replayPending = AtomicBoolean(false)

    override fun connected() = Unit

    override fun disconnected(message: String?) {
        Log.w(
            CommandManager.TAG,
            "[COMMAND_DIAG] channel=LOG event=disconnected generation=$generation token=$token " +
                "message=${message.orEmpty()} thread=${Thread.currentThread().name}"
        )
        if (acceptsCommandLogCallback(generation, token)) signals.disconnected.complete(message)
    }

    override fun clearLogs() {
        if (!acceptsCommandLogCallback(generation, token)) return
        replayPending.set(true)
        delegate.clearLogs()
    }

    override fun setDefaultLogLevel(level: Int) {
        if (!acceptsCommandLogCallback(generation, token)) return
        delegate.setDefaultLogLevel(level)
        signals.ready.complete(Unit)
    }

    override fun writeLogs(messageList: LogIterator?) {
        if (!acceptsCommandLogCallback(generation, token)) return
        val heartbeatAt = SystemClock.uptimeMillis()
        synchronized(runtimeAccess) {
            if (generation == activeCommandSessionGeneration &&
                (token == activeCommandLogClientToken || token == pendingCommandLogClientToken)
            ) {
                commandLogHeartbeatAtMs = heartbeatAt
            }
        }
        signals.heartbeat.trySend(Unit)
        consumeCommandLogMessages(
            messageList,
            notifyObserver = CommandManager.shouldNotifyCommandLogObserver(replayPending.getAndSet(false)),
            acceptsCallbackLocked = {
                CommandManager.acceptsCommandLogCallback(
                    generation,
                    activeCommandSessionGeneration,
                    token,
                    activeCommandLogClientToken,
                    pendingCommandLogClientToken
                )
            }
        )
    }
}

internal fun CommandManager.acceptsCommandLogCallback(generation: Long, token: Long): Boolean =
    synchronized(runtimeAccess) {
        CommandManager.acceptsCommandLogCallback(
            sessionGeneration = generation,
            activeSessionGeneration = activeCommandSessionGeneration,
            clientToken = token,
            activeClientToken = activeCommandLogClientToken,
            pendingClientToken = pendingCommandLogClientToken
        )
    }

internal fun CommandManager.isCommandLogSessionActive(generation: Long): Boolean = synchronized(runtimeAccess) {
    isCommandLogSessionActiveLocked(generation)
}

internal fun CommandManager.isCommandLogSessionActiveLocked(generation: Long): Boolean =
    generation > 0L && generation == activeCommandSessionGeneration &&
        commandLogReconnectEnabled

internal fun CommandManager.isCommandSessionActive(generation: Long): Boolean = synchronized(runtimeAccess) {
    generation > 0L && generation == activeCommandSessionGeneration
}

internal suspend fun CommandManager.awaitCommandLogStream(
    generation: Long,
    heartbeat: Channel<Unit>,
    disconnected: CompletableDeferred<String?>
): String? {
    while (true) {
        val result = withTimeoutOrNull(CommandManager.COMMAND_LOG_HEARTBEAT_TIMEOUT_MS) {
            select<Pair<Boolean, String?>> {
                heartbeat.onReceive { false to null }
                disconnected.onAwait { true to it }
            }
        } ?: run {
            val diagnostic = controlChannelDiagnosticSnapshot(generation)
            Log.e(CommandManager.TAG, "[COMMAND_DIAG] event=heartbeat_timeout $diagnostic")
            LogRepository.getInstance().addAlwaysLog(
                "ERROR [COMMAND_DIAG] event=heartbeat_timeout $diagnostic"
            )
            error("Command log heartbeat timeout")
        }
        if (result.first) return result.second
        requireBaseCommandHeartbeats(generation)
    }
}

internal fun CommandManager.requireBaseCommandHeartbeats(generation: Long) {
    val staleChannels = synchronized(runtimeAccess) {
        if (generation != activeCommandSessionGeneration) {
            throw CancellationException("Command runtime changed")
        }
        val now = SystemClock.uptimeMillis()
        CommandManager.BaseCommandChannel.entries.filter { channel ->
            CommandManager.isCommandHeartbeatStale(baseCommandHeartbeatAtMs[channel], now)
        }.also { stale ->
            if (stale.isNotEmpty()) {
                readyBaseCommandChannels.removeAll(stale.toSet())
                stale.forEach(baseCommandHeartbeatAtMs::remove)
                commandBaseHealthy = false
                notifyCombinedCommandHealthLocked()
                callbacks?.onControlChannelRecoveryRequired(
                    "heartbeat_timeout_${stale.joinToString("_") { it.name.lowercase() }}"
                )
            }
        }
    }
    if (staleChannels.isNotEmpty()) {
        val diagnostic = controlChannelDiagnosticSnapshot(generation)
        Log.e(
            CommandManager.TAG,
            "[COMMAND_DIAG] event=base_heartbeat_timeout stale=${staleChannels.joinToString()} $diagnostic"
        )
        LogRepository.getInstance().addAlwaysLog(
            "ERROR [COMMAND_DIAG] event=base_heartbeat_timeout " +
                "stale=${staleChannels.joinToString()} $diagnostic"
        )
    }
    check(staleChannels.isEmpty()) { "Base command heartbeat timeout: ${staleChannels.joinToString()}" }
}

internal fun CommandManager.controlChannelDiagnosticSnapshot(generation: Long): String = synchronized(runtimeAccess) {
    val now = SystemClock.uptimeMillis()
    val ages = CommandManager.BaseCommandChannel.entries.joinToString(",") { channel ->
        val heartbeat = baseCommandHeartbeatAtMs[channel]
        "${channel.name.lowercase()}=${heartbeat?.let { (now - it).coerceAtLeast(0L) } ?: "never"}ms"
    }
    val logAge = commandLogHeartbeatAtMs.takeIf { it > 0L }?.let { (now - it).coerceAtLeast(0L) } ?: "never"
    "generation=$generation activeGeneration=$activeCommandSessionGeneration " +
        "baseHealthy=$commandBaseHealthy logHealthy=$commandLogHealthy " +
        "baseAges={$ages} logAge=${logAge}ms thread=${Thread.currentThread().name}"
}

internal fun CommandManager.markCommandLogHealth(generation: Long, ready: Boolean) {
    synchronized(runtimeAccess) {
        if (!isCommandLogSessionActiveLocked(generation)) return
        commandLogHealthy = ready
        completeCommandControlReadyLocked()
        notifyCombinedCommandHealthLocked()
    }
}

internal fun CommandManager.completeCommandControlReadyLocked() {
    if (commandBaseHealthy && commandLogHealthy) commandLogSessionReady?.complete(Unit)
}

internal fun CommandManager.notifyCombinedCommandHealthLocked() {
    val ready = commandBaseHealthy && commandLogHealthy
    if (lastPublishedControlHealth == ready) return
    lastPublishedControlHealth = ready
    callbacks?.onControlChannelHealth(ready)
}

internal fun CommandManager.markBaseCommandHealth(generation: Long, channel: CommandManager.BaseCommandChannel, ready: Boolean) {
    synchronized(runtimeAccess) {
        if (generation != activeCommandSessionGeneration) return
        if (ready) readyBaseCommandChannels += channel else readyBaseCommandChannels -= channel
        if (ready) baseCommandHeartbeatAtMs[channel] = SystemClock.uptimeMillis()
        else baseCommandHeartbeatAtMs.remove(channel)
        commandBaseHealthy = readyBaseCommandChannels.size == CommandManager.BASE_COMMAND_CLIENT_COUNT
        completeCommandControlReadyLocked()
        notifyCombinedCommandHealthLocked()
        if (!ready) callbacks?.onControlChannelRecoveryRequired("${channel.name.lowercase()}_disconnected")
    }
}

@Suppress("CognitiveComplexMethod")
internal fun CommandManager.consumeCommandLogMessages(
    messageList: LogIterator?,
    notifyObserver: Boolean,
    acceptsCallbackLocked: () -> Boolean
) {
    if (messageList == null) return
    val repo = LogRepository.getInstance()
    runCatching {
        while (messageList.hasNext()) {
            val message = messageList.next()?.message
            if (!message.isNullOrBlank()) {
                synchronized(runtimeAccess) {
                    if (!acceptsCallbackLocked()) return@runCatching
                    val observer = if (notifyObserver) kernelLogObserver else null
                    CommandManager.dispatchKernelLog(
                        message = message,
                        uiLogsEnabled = repo.isEnabled(),
                        observer = observer?.let { logObserver ->
                            { line -> logObserver.onKernelLog(line) }
                        },
                        addToRepository = repo::addLog
                    )
                }
            }
        }
    }
}

@Suppress("CognitiveComplexMethod")
internal fun CommandManager.createClientHandler(
    generation: Long,
    channel: CommandManager.BaseCommandChannel?
): CommandClientHandler = object : CommandClientHandler {
    override fun connected() = Unit

    override fun disconnected(message: String?) {
        Log.w(
            CommandManager.TAG,
            "[COMMAND_DIAG] channel=${channel?.name ?: "UNKNOWN"} event=disconnected " +
                "generation=$generation message=${message.orEmpty()} thread=${Thread.currentThread().name}"
        )
        channel?.let { markBaseCommandHealth(generation, it, ready = false) }
        Log.w(CommandManager.TAG, "CommandClient disconnected: $message")
    }

    override fun clearLogs() {
        if (!isCommandSessionActive(generation)) return
        runCatching {
            LogRepository.getInstance().clearLogs(preserveRecoveryDiagnostics = true)
        }
    }

    override fun setDefaultLogLevel(level: Int) {}

    override fun writeLogs(messageList: LogIterator?) {
        if (!isCommandSessionActive(generation)) return
        consumeCommandLogMessages(
            messageList,
            notifyObserver = true,
            acceptsCallbackLocked = { generation == activeCommandSessionGeneration }
        )
    }

    @Suppress("LongMethod")
    override fun writeStatus(message: StatusMessage?) {
        if (!isCommandSessionActive(generation)) return
        if (message == null) return
        channel?.let { markBaseCommandHealth(generation, it, ready = true) }
        trafficStatusGate.runIfActive {
            try {
                val snapshot = trafficMonitor.updateTotals(
                    uploadTotal = message.uplinkTotal,
                    downloadTotal = message.downlinkTotal,
                    sampleTimeMs = SystemClock.elapsedRealtime()
                )
                callbacks?.onTrafficUpdate(snapshot)
            } catch (e: Exception) {
                Log.e(CommandManager.TAG, "writeStatus callback error", e)
            }
        }
    }

    override fun writeGroups(groups: OutboundGroupIterator?) {
        if (!isCommandSessionActive(generation)) return
        if (groups == null) return
        channel?.let { markBaseCommandHealth(generation, it, ready = true) }
        try {
            processGroups(groups)
        } catch (e: Exception) {
            Log.e(CommandManager.TAG, "Error processing groups update", e)
        }
    }

    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
    override fun updateClashMode(newMode: String?) {}

    override fun writeConnectionEvents(events: ConnectionEvents?) {
        if (!isCommandSessionActive(generation)) return
        events ?: return
        channel?.let { markBaseCommandHealth(generation, it, ready = true) }
        try {
            val eventData = ConnectionTrafficEventReader.read(events)
            if (!events.reset && eventData.isEmpty()) return
            val runtimeMappings = NodeProtectionStore.runtimeMappings()
            recordDirectIncidents(eventData)
            if (events.reset) connectionTrafficAttributor.clear()
            recordApplicationRouteTrace(eventData, runtimeMappings)
            enforceConnectionStormGuard(
                connectionStormGuard.observe(
                    reset = events.reset,
                    events = eventData,
                    nowMs = SystemClock.elapsedRealtime()
                )
            )
            enforceRuntimeMeteredProtection(eventData, runtimeMappings)
            recordAttributedTraffic(
                connectionTrafficAttributor.apply(
                    reset = false,
                    events = eventData,
                    runtimeMappings = runtimeMappings
                )
            )
            val snapshot = connectionsSnapshot ?: Libbox.newConnections().also {
                connectionsSnapshot = it
            }
            snapshot.applyEvents(events)
            processConnections(snapshot)
        } catch (e: Exception) {
            Log.e(CommandManager.TAG, "Error processing connection events", e)
        }
    }
}

internal fun CommandManager.processGroups(groups: OutboundGroupIterator) {
    var changed = false

    Log.d(CommandManager.TAG, "writeGroups called")

    while (groups.hasNext()) {
        val group = groups.next()
        val groupChanged = processGroup(group)
        if (groupChanged) changed = true
    }
    changed = updateResolvedProxySelection() || changed

    if (changed) {
        callbacks?.requestNotificationUpdate(false)
    }
}

internal fun CommandManager.processGroup(group: OutboundGroup): Boolean {
    val tag = group.tag
    val selected = group.selected
    var changed = false

    Log.d(CommandManager.TAG, "Processing group: $tag, selected=$selected")

    if (!tag.isNullOrBlank() && !selected.isNullOrBlank()) {
        SelectorManager.recordKernelSelection(tag, selected)
        val prev = groupSelectedOutbounds.put(tag, selected)
        if (prev != selected) {
            changed = true
            callbacks?.onGroupSelectionChanged(tag, selected)
        }
    }

    return changed
}

internal fun CommandManager.enforceRuntimeMeteredProtection(
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
            val closed = closeConnection(event.id) || closeConnections()
            LogRepository.getInstance().addAlwaysLog(
                "ERROR [METERED_GUARD] closed=$closed connection=${event.id} " +
                    "node=${unauthorized.nodeName} node_id=${unauthorized.nodeId}"
            )
        }
}

internal fun CommandManager.enforceConnectionStormGuard(decision: ConnectionStormDecision?) {
    decision ?: return
    val closed = if (decision.closeAll) {
        closeConnections()
    } else {
        decision.connectionIds.fold(true) { success, id -> closeConnection(id) && success }
    }
    if (closed) connectionStormGuard.acknowledgeClosed(decision)
    persistConnectionIncident(decision, closed)
    LogRepository.getInstance().addAlwaysLog(
        "ERROR [CONNECTION_STORM] mode=vpn reason=${decision.reason} closed=$closed " +
            "active=${decision.activeConnections} created=${decision.newConnectionsInWindow} " +
            "rate=${String.format(java.util.Locale.US, "%.1f", decision.creationRatePerSecond)} " +
            "uid=${decision.offender?.uid ?: -1} " +
            "package=${decision.offender?.packageNames?.joinToString(",").orEmpty()} " +
            "inbound=${decision.offender?.inbound.orEmpty()} source=${decision.offender?.source.orEmpty()}"
    )
}

internal fun CommandManager.persistConnectionIncident(decision: ConnectionStormDecision, closed: Boolean) {
    val snapshot = decision.toIncidentSnapshot(
        mode = "vpn",
        closeReason = decision.incidentCloseReason(),
        closeSucceeded = closed,
        timestampEpochMs = System.currentTimeMillis(),
        elapsedRealtimeMs = SystemClock.elapsedRealtime()
    )
    serviceScope.launch(Dispatchers.IO) {
        runCatching { connectionIncidentHistory.append(snapshot) }
            .onFailure { error -> Log.e(CommandManager.TAG, "Failed to persist connection incident", error) }
    }
}

internal fun CommandManager.connectClient(
    client: CommandClient?,
    fdProvider: (() -> ParcelFileDescriptor?)?
) {
    requireNotNull(client) { "CommandClient creation failed" }
    if (fdProvider == null) {
        client.connect()
        return
    }
    val descriptor = requireNotNull(fdProvider()) { "Root command connection is unavailable" }
    descriptor.use {
        val fd = it.detachFd()
        // libbox 会在成功和失败路径消费并关闭该 FD，Kotlin 侧禁止再次 adopt/close。
        client.connectWithFD(fd)
    }
}

internal fun CommandManager.createConnectedCommandClient(
    handler: CommandClientHandler,
    fdProvider: (() -> ParcelFileDescriptor?)?,
    configure: CommandClientOptions.() -> Unit
): CommandClient {
    val client = requireNotNull(Libbox.newCommandClient(handler, CommandClientOptions().apply(configure)))
    return try {
        connectClient(client, fdProvider)
        client
    } catch (error: Exception) {
        runCatching { client.disconnect() }
        throw error
    }
}

internal fun CommandManager.recordDirectIncidents(events: List<ConnectionTrafficEventData>) {
    serviceScope.launch(Dispatchers.IO) {
        runCatching { directConnectionIncidentHistory.recordNew(events) }
            .onSuccess { incidents ->
                incidents.filter { it.routeRuleSemantic == "unknown" }.forEach { incident ->
                    LogRepository.getInstance().addAlwaysLog(
                        "WARN [DIRECT_INCIDENT] connection=${incident.connectionId} " +
                            "uid=${incident.uid ?: -1} outbound=${incident.outbound.orEmpty()} " +
                            "chain=${incident.chain.joinToString(">")} semantic=unknown"
                    )
                }
            }
            .onFailure { error -> Log.e(CommandManager.TAG, "Failed to persist direct incidents", error) }
    }
}

internal fun CommandManager.recordApplicationRouteTrace(
    events: List<ConnectionTrafficEventData>,
    runtimeMappings: Map<String, RuntimeNodeRef>
) {
    events.forEach { event ->
        if (event.uid != telegramUid && CommandManager.TELEGRAM_PACKAGE !in event.packageNames) return@forEach
        val resolvedTargets = connectionTrafficAttributor.resolveTargets(event, runtimeMappings)
            .joinToString(",") { target -> "${target.nodeName}/${target.nodeId}" }
        val signature = listOf(
            event.type,
            event.uid,
            event.packageNames.joinToString("|"),
            event.inbound,
            event.routeRule,
            event.outbound,
            event.fromOutbound,
            event.chain.joinToString(">"),
            resolvedTargets,
            event.destination,
            event.domain,
            event.attributionStatus
        ).joinToString(";")
        if (applicationRouteTraceSignatures[event.id] == signature) return@forEach
        applicationRouteTraceSignatures[event.id] = signature
        val line = "[APP_ROUTE_TRACE] connection=${event.id} type=${event.type} " +
            "uid=${event.uid ?: -1} packages=${event.packageNames.joinToString("|")} " +
            "inbound=${event.inbound.orEmpty()} rule=${event.routeRule.orEmpty()} " +
            "outbound=${event.outbound.orEmpty()} from=${event.fromOutbound.orEmpty()} " +
            "chain=${event.chain.joinToString(">")} targets=$resolvedTargets " +
            "destination=${event.destination.orEmpty()} domain=${event.domain.orEmpty()} " +
            "attribution=${event.attributionStatus}"
        Log.i(CommandManager.TAG, line)
        LogRepository.getInstance().addAlwaysLog("INFO $line")
        if (event.type == ConnectionTrafficAttributor.EVENT_CLOSED) {
            applicationRouteTraceSignatures.remove(event.id)
        }
    }
}

internal fun CommandManager.recordAttributedTraffic(records: List<AttributedConnectionTraffic>) {
    val repository = TrafficRepository.getInstance(context)
    records.forEach { record ->
        val targets = record.targets.ifEmpty {
            setOf(
                RuntimeNodeRef(
                    nodeId = TrafficRepository.UNATTRIBUTED_NODE_ID,
                    nodeName = context.getString(R.string.traffic_unattributed)
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

internal fun CommandManager.updateResolvedProxySelection(): Boolean {
    val selectedTag = CommandManager.resolveConcreteGroupSelection("PROXY", groupSelectedOutbounds) ?: return false
    if (SelectorManager.isSelectionPending()) {
        Log.d(CommandManager.TAG, "Deferring runtime node publication until explicit switch cleanup: $selectedTag")
        return false
    }
    val selected = resolveRuntimeNodeLabel(selectedTag, NodeProtectionStore.runtimeMappings()) ?: return false
    if (selected == realTimeNodeName) return false

// 只更新运行态展示，不写回用户手选节点。
// writeGroups 会在 urltest/自动切换时频繁回调，写回 activeNodeId 会造成节点乱飞。
    realTimeNodeName = selected
    VpnStateStore.setActiveLabel(selected)
    callbacks?.onRuntimeNodeChanged(selected)
    Log.i(CommandManager.TAG, "Real-time node update: tag=$selectedTag, display=$selected")
    return true
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth")
internal fun CommandManager.processConnections(connections: Connections) {
    val iterator = connections.iterator()
    var newestConnection: io.nekohasekai.libbox.Connection? = null
    val ids = ArrayList<String>(64)
    val egressCounts = LinkedHashMap<String, Int>()
    val configRepo = ConfigRepository.getInstance(context)

    while (iterator.hasNext()) {
        val connection = iterator.next() ?: continue

        if (connection.closedAt > 0) continue
        val outbound = connection.outbound

        if (newestConnection == null || connection.createdAt > newestConnection.createdAt) {
            newestConnection = connection
        }

        val id = connection.id
        if (!id.isNullOrBlank()) {
            ids.add(id)
        }

        var candidateTag: String? = outbound
        if (candidateTag.isNullOrBlank()) {
            candidateTag = null
        }

        if (!candidateTag.isNullOrBlank()) {
            val resolved = callbacks?.resolveEgressNodeName(candidateTag)
                ?: configRepo.resolveNodeNameFromOutboundTag(candidateTag)
                ?: candidateTag
            if (!resolved.isNullOrBlank()) {
                egressCounts[resolved] = (egressCounts[resolved] ?: 0) + 1
            }
        }
    }

    recentConnectionIds = ids

    val newLabel = when {
        egressCounts.isEmpty() -> null
        egressCounts.size == 1 -> egressCounts.keys.first()
        else -> {
            val sorted = egressCounts.entries.sortedByDescending { it.value }.map { it.key }
            val top = sorted.take(2)
            val more = sorted.size - top.size
            if (more > 0) "Mixed: ${top.joinToString(" + ")}(+$more)"
            else "Mixed: ${top.joinToString(" + ")}"
        }
    }

    val labelChanged = newLabel != activeConnectionLabel
    if (labelChanged) {
        activeConnectionLabel = newLabel
        if (newLabel != lastConnectionsLabelLogged) {
            lastConnectionsLabelLogged = newLabel
            Log.d(CommandManager.TAG, "Connections label updated: ${newLabel ?: "(null)"}")
        }
    }

    var newNode: String? = null
    if (newestConnection != null) {
        val chainIter = newestConnection.chain()
        val chainList = mutableListOf<String>()
        if (chainIter != null) {
            while (chainIter.hasNext()) {
                val tag = chainIter.next()
                if (!tag.isNullOrBlank()) {
                    chainList.add(tag)
                }
            }
        }
        newNode = chainList.lastOrNull()
    }

    if (newNode != activeConnectionNode || labelChanged) {
        activeConnectionNode = newNode
        callbacks?.requestNotificationUpdate(false)
    }
}

internal fun CommandManager.cleanup() {
    stop()
    groupSelectedOutbounds.clear()
    realTimeNodeName = null
    activeConnectionNode = null
    activeConnectionLabel = null
    recentConnectionIds = emptyList()
    connectionsSnapshot = null
    applicationRouteTraceSignatures.clear()
    connectionTrafficAttributor.clear()
    connectionStormGuard.clear()
    callbacks = null
}
