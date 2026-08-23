package com.kunk.singbox.service.manager

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.RuntimeNodeRef
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.service.resolveRuntimeNodeLabel
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.service.network.TrafficMonitor
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

internal class TrafficStatusGate {
    private val lock = Any()
    private var accepting = false

    fun start() = synchronized(lock) {
        accepting = true
    }

    fun stopAndWait() = synchronized(lock) {
        accepting = false
    }

    fun runIfActive(block: () -> Unit) = synchronized(lock) {
        if (accepting) block()
    }
}

@Suppress("TooManyFunctions")
class CommandManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CommandManager"
        private const val MAX_LOG_LINES = 300
        private const val PORT_RELEASE_TIMEOUT_MS = 10000L
        private const val PORT_CHECK_INTERVAL_MS = 50L
        private const val MAX_GROUP_SELECTION_DEPTH = 4
        internal const val GROUP_STATUS_INTERVAL_MS = 500L

        internal fun dispatchKernelLog(
            message: String,
            uiLogsEnabled: Boolean,
            observer: ((String) -> Unit)?,
            addToRepository: (String) -> Unit
        ) {
            observer?.invoke(message)
            if (uiLogsEnabled) {
                addToRepository(message)
            }
        }

        internal fun resolveConcreteGroupSelection(
            rootTag: String,
            selections: Map<String, String>,
            maxDepth: Int = MAX_GROUP_SELECTION_DEPTH
        ): String? {
            var current = rootTag
            val visited = mutableSetOf<String>()
            var concreteTag: String? = null
            run resolution@{
                repeat(maxDepth) {
                    if (!visited.add(current)) return@resolution
                    val selected = selections[current]?.trim()?.takeIf { it.isNotBlank() }
                        ?: return@resolution
                    if (selected !in selections) {
                        concreteTag = selected.takeUnless { it.endsWith("#AUTO", ignoreCase = true) }
                        return@resolution
                    }
                    current = selected
                }
            }
            return concreteTag
        }
    }

    fun interface KernelLogObserver {
        fun onKernelLog(message: String)
    }

    // Command Server/Client
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var commandClientGroup: CommandClient? = null
    private var commandClientLogs: CommandClient? = null
    private var commandClientConnections: CommandClient? = null
    private val runtimeAccess = Any()
    private val runtimeGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var runtimeHandle: CommandRuntimeHandle? = null

    private data class CommandRuntimeHandle(
        val generation: Long,
        val server: CommandServer?,
        val statusClient: CommandClient?,
        val groupClient: CommandClient?,
        val logClient: CommandClient?,
        val connectionsClient: CommandClient?
    )

    @Volatile
    private var clientHandler: CommandClientHandler? = null

    @Volatile
    private var kernelLogObserver: KernelLogObserver? = null

    @Volatile
    private var isNonEssentialSuspended: Boolean = false

    @Volatile
    private var consecutiveDisconnects: Int = 0

    private val trafficStatusGate = TrafficStatusGate()
    private var connectionsSnapshot: Connections? = null

    private val groupSelectedOutbounds = ConcurrentHashMap<String, String>()
    @Volatile var realTimeNodeName: String? = null
        internal set

    @Volatile var activeConnectionNode: String? = null
        private set
    @Volatile var activeConnectionLabel: String? = null
        private set
    var recentConnectionIds: List<String> = emptyList()
        private set

    private val trafficMonitor = TrafficMonitor()
    private val connectionTrafficAttributor = ConnectionTrafficAttributor()
    private val connectionStormGuard = ConnectionStormGuard()
    private val connectionIncidentHistory = ConnectionIncidentHistory(context)
    private val directConnectionIncidentHistory = DirectConnectionIncidentHistory(
        context,
        SingBoxIpcHub.serviceInstanceId()
    )
    private var lastConnectionsLabelLogged: String? = null

    interface Callbacks {
        fun requestNotificationUpdate(force: Boolean)
        fun resolveEgressNodeName(tagOrSelector: String?): String?
        fun onGroupSelectionChanged(groupTag: String, selectedTag: String) {}
        fun onRuntimeNodeChanged(nodeName: String) {}
        fun onTrafficUpdate(snapshot: TrafficMonitor.TrafficSnapshot) {}
        fun onControlChannelHealth(ready: Boolean) {}
        fun onServiceStop(): Unit
        fun onServiceReload(): Unit
    }

    private var callbacks: Callbacks? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun setKernelLogObserver(observer: KernelLogObserver?) {
        kernelLogObserver = observer
    }

    fun handleOutboundFailureBurst(outboundTag: String, failureCount: Int, nowMs: Long): Boolean {
        val decision = connectionStormGuard.observeOutboundFailureBurst(outboundTag, failureCount, nowMs)
        enforceConnectionStormGuard(decision)
        return decision != null
    }

    internal fun connectionAttributionSnapshot(): ConnectionAttributionSnapshot {
        return connectionStormGuard.snapshot()
    }

    @Suppress("UNUSED_PARAMETER")
    fun createServer(platformInterface: PlatformInterface): Result<CommandServer> = runCatching {
        val serverHandler = object : CommandServerHandler {
            override fun serviceStop() {
                Log.i(TAG, "serviceStop requested")
                callbacks?.onServiceStop()
            }

            override fun serviceReload() {
                Log.i(TAG, "serviceReload requested")
                callbacks?.onServiceReload()
            }

            override fun getSystemProxyStatus(): SystemProxyStatus? = null

            override fun setSystemProxyEnabled(isEnabled: Boolean) {}

            override fun writeDebugMessage(message: String?) {
                if (!message.isNullOrBlank()) {
                    Log.d(TAG, message)
                }
            }
        }

        val server = Libbox.newCommandServer(serverHandler, platformInterface)
        Log.i(TAG, "CommandServer created")
        server
    }

    fun startServer(server: CommandServer): Result<Unit> = runCatching {
        server.start()
        Log.i(TAG, "CommandServer started")

        // BoxWrapperManager.init 延迟到 libbox 启动后调用
        // 避免 Libbox.hasSelector() 在 box 未运行时超时阻塞 ~1.5s
    }

    fun adoptServer(server: CommandServer) {
        check(commandServer == null || commandServer === server) { "CommandServer already adopted" }
        commandServer = server
    }

    fun startService(configContent: String, platformInterface: PlatformInterface): Result<Unit> = runCatching {
        val overrideOptions = OverrideOptions().apply {
            autoRedirect = false
        }
        commandServer?.startOrReloadService(configContent, overrideOptions)
            ?: throw IllegalStateException("CommandServer not created")
        Log.i(TAG, "CommandServer service started")
    }

    fun closeService(): Result<Unit> = runCatching {
        commandServer?.closeService()
            ?: throw IllegalStateException("CommandServer not created")
        Log.i(TAG, "CommandServer service closed")
    }

    fun startClients(): Result<Unit> = runCatching {
        trafficMonitor.reset()
        connectionStormGuard.clear()
        trafficStatusGate.start()
        val handler = createClientHandler()
        clientHandler = handler

        val optionsStatus = CommandClientOptions()
        optionsStatus.addCommand(Libbox.CommandStatus)
        optionsStatus.statusInterval = 3000L * 1000L * 1000L // 3s
        commandClient = Libbox.newCommandClient(handler, optionsStatus)
        commandClient?.connect()
        Log.i(TAG, "CommandClient connected (Status, interval=3s)")

        val optionsGroup = CommandClientOptions()
        optionsGroup.addCommand(Libbox.CommandGroup)
        optionsGroup.statusInterval = GROUP_STATUS_INTERVAL_MS * 1000L * 1000L
        commandClientGroup = Libbox.newCommandClient(handler, optionsGroup)
        commandClientGroup?.connect()
        Log.i(TAG, "CommandClient connected (Group, interval=${GROUP_STATUS_INTERVAL_MS}ms)")

        val optionsLog = CommandClientOptions()
        optionsLog.addCommand(Libbox.CommandLog)
        optionsLog.statusInterval = 1500L * 1000L * 1000L
        commandClientLogs = Libbox.newCommandClient(handler, optionsLog)
        commandClientLogs?.connect()
        Log.i(TAG, "CommandClient connected (Logs, interval=1.5s)")

        val optionsConn = CommandClientOptions()
        optionsConn.addCommand(Libbox.CommandConnections)
        optionsConn.statusInterval = 1000L * 1000L * 1000L
        commandClientConnections = Libbox.newCommandClient(handler, optionsConn)
        commandClientConnections?.connect()
        Log.i(TAG, "CommandClient connected (Connections, interval=1s)")
        synchronized(runtimeAccess) {
            runtimeHandle = CommandRuntimeHandle(
                generation = runtimeGeneration.incrementAndGet(),
                server = commandServer,
                statusClient = commandClient,
                groupClient = commandClientGroup,
                logClient = commandClientLogs,
                connectionsClient = commandClientConnections
            )
        }

        serviceScope.launch {
            delay(3500)
            val groupsSize = groupSelectedOutbounds.size
            val label = activeConnectionLabel
            if (groupsSize == 0 && label.isNullOrBlank()) {
                Log.w(TAG, "Command callbacks not observed yet")
            } else {
                Log.i(TAG, "Command callbacks OK (groups=$groupsSize)")
            }
        }
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    suspend fun stopAndWaitPortRelease(
        proxyPort: Int,
        waitTimeoutMs: Long = PORT_RELEASE_TIMEOUT_MS,
        forceKillOnTimeout: Boolean = true,
        enforceReleaseOnTimeout: Boolean = false,
        preserveNotifications: Boolean = false,
        expectedRuntimeGeneration: Long = currentRuntimeGeneration()
    ): Result<Unit> = runCatching {
        Log.i(TAG, "stopAndWaitPortRelease: port=$proxyPort, timeout=${waitTimeoutMs}ms, forceKill=$forceKillOnTimeout")
        val capturedHandle = synchronized(runtimeAccess) {
            val current = runtimeHandle
            if (expectedRuntimeGeneration > 0L && current?.generation != expectedRuntimeGeneration) {
                Log.w(
                    TAG,
                    "Skip stale command cleanup expected=$expectedRuntimeGeneration current=${current?.generation}"
                )
                return@runCatching
            }
            current ?: CommandRuntimeHandle(
                generation = runtimeGeneration.get(),
                server = commandServer,
                statusClient = commandClient,
                groupClient = commandClientGroup,
                logClient = commandClientLogs,
                connectionsClient = commandClientConnections
            )
        }
        stopTrafficUpdatesAndWait()

        capturedHandle.statusClient?.disconnect()
        capturedHandle.groupClient?.disconnect()
        capturedHandle.logClient?.disconnect()
        capturedHandle.connectionsClient?.disconnect()
        val ownsCurrentRuntime = synchronized(runtimeAccess) {
            if (runtimeHandle == null || runtimeHandle === capturedHandle) {
                runtimeHandle = null
                if (commandClient === capturedHandle.statusClient) commandClient = null
                if (commandClientGroup === capturedHandle.groupClient) commandClientGroup = null
                if (commandClientLogs === capturedHandle.logClient) commandClientLogs = null
                if (commandClientConnections === capturedHandle.connectionsClient) commandClientConnections = null
                if (commandServer === capturedHandle.server) commandServer = null
                clientHandler = null
                true
            } else {
                false
            }
        }

        if (ownsCurrentRuntime) {
            BoxWrapperManager.release()
            connectionsSnapshot = null
            connectionTrafficAttributor.clear()
            connectionStormGuard.clear()
        }

        val closeStart = SystemClock.elapsedRealtime()
        runCatching {
            capturedHandle.server?.closeService()
        }.onFailure { e ->
            // closeService 在服务已关闭时返回 invalid argument，属于正常情况
            Log.d(TAG, "CommandServer.closeService: ${e.message} (expected if already closed)")
        }
        Log.i(TAG, "CommandServer service closed in ${SystemClock.elapsedRealtime() - closeStart}ms")

        capturedHandle.server?.close()

        if (!preserveNotifications) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.cancel(VpnNotificationManager.NOTIFICATION_ID)
                nm?.cancel(11) // ProxyOnlyService NOTIFICATION_ID
            }
        }

        if (proxyPort > 0) {
            Log.i(TAG, "Waiting for port $proxyPort to be released (timeout=${waitTimeoutMs}ms)...")
            val portReleased = waitForPortRelease(proxyPort, waitTimeoutMs)
            val elapsed = SystemClock.elapsedRealtime() - closeStart
            if (portReleased) {
                Log.i(TAG, "Port $proxyPort released in ${elapsed}ms")
            } else {
                if (forceKillOnTimeout) {

                    Log.e(TAG, "Port $proxyPort NOT released after ${elapsed}ms, killing process to force release")
                    android.os.Process.killProcess(android.os.Process.myPid())
                } else {
                    if (enforceReleaseOnTimeout) {
                        throw IllegalStateException(
                            "Port $proxyPort NOT released after ${elapsed}ms in strict-stop mode"
                        )
                    }
                    Log.w(TAG, "Port $proxyPort NOT released after ${elapsed}ms, " +
                        "skip force kill (forceKillOnTimeout=false)")
                }
            }
        } else {
            Log.i(TAG, "Command Server/Client stopped (no port to wait)")
        }
    }

    fun stop(): Result<Unit> = runCatching {
        stopTrafficUpdatesAndWait()
        commandClient?.disconnect()
        commandClient = null
        commandClientGroup?.disconnect()
        commandClientGroup = null
        commandClientLogs?.disconnect()
        commandClientLogs = null
        commandClientConnections?.disconnect()
        commandClientConnections = null

        clientHandler = null

        BoxWrapperManager.release()
        connectionsSnapshot = null
        connectionTrafficAttributor.clear()
        connectionStormGuard.clear()

        runCatching { commandServer?.closeService() }
            .onFailure { Log.w(TAG, "CommandServer.closeService failed: ${it.message}") }

        commandServer?.close()
        commandServer = null
        Log.i(TAG, "Command Server/Client stopped")
    }

    fun stopTrafficUpdatesAndWait() {
        trafficStatusGate.stopAndWait()
        trafficMonitor.reset()
    }

    private suspend fun waitForPortRelease(port: Int, timeoutMs: Long): Boolean {
        val startTime = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            if (isPortAvailable(port)) {
                return true
            }
            delay(PORT_CHECK_INTERVAL_MS)
        }
        return false
    }

    private fun isPortAvailable(port: Int): Boolean {
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

    fun getCommandServer(): CommandServer? = commandServer

    fun getCommandClient(): CommandClient? = commandClient
    fun getConnectionsClient(): CommandClient? = commandClientConnections

    fun getSelectedOutbound(groupTag: String): String? = groupSelectedOutbounds[groupTag]

    fun getResolvedSelectedOutbound(groupTag: String): String? {
        return resolveConcreteGroupSelection(groupTag, groupSelectedOutbounds)
    }

    fun getGroupsCount(): Int = groupSelectedOutbounds.size

    internal fun currentRuntimeGeneration(): Long = synchronized(runtimeAccess) {
        runtimeHandle?.generation ?: 0L
    }

    fun closeConnections(): Boolean {
        val clients = listOfNotNull(commandClientConnections, commandClient)
        for (client in clients) {
            try {
                client.closeConnections()
                Log.i(TAG, "Connections closed via CommandClient")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "closeConnections failed: ${e.message}")
            }
        }
        return false
    }

    fun closeConnection(connId: String): Boolean {
        val client = commandClientConnections ?: commandClient ?: return false
        return try {
            client.closeConnection(connId)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun createClientHandler(): CommandClientHandler = object : CommandClientHandler {
        override fun connected() {
            consecutiveDisconnects = 0
            callbacks?.onControlChannelHealth(true)
        }

        override fun disconnected(message: String?) {
            Log.w(TAG, "CommandClient disconnected: $message")
            consecutiveDisconnects++
            if (consecutiveDisconnects >= 2) callbacks?.onControlChannelHealth(false)
        }

        override fun clearLogs() {
            runCatching {
                LogRepository.getInstance().clearLogs(preserveRecoveryDiagnostics = true)
            }
        }

        override fun setDefaultLogLevel(level: Int) {}

        override fun writeLogs(messageList: LogIterator?) {
            if (messageList == null) return
            val repo = LogRepository.getInstance()
            runCatching {
                while (messageList.hasNext()) {
                    val msg = messageList.next()?.message
                    if (!msg.isNullOrBlank()) {
                        val observer = kernelLogObserver
                        dispatchKernelLog(
                            message = msg,
                            uiLogsEnabled = repo.isEnabled(),
                            observer = observer?.let { logObserver ->
                                { line -> logObserver.onKernelLog(line) }
                            },
                            addToRepository = { repo.addLog(it) }
                        )
                    }
                }
            }
        }

        @Suppress("LongMethod")
        override fun writeStatus(message: StatusMessage?) {
            if (message == null) return
            trafficStatusGate.runIfActive {
                try {
                    val snapshot = trafficMonitor.updateTotals(
                        uploadTotal = message.uplinkTotal,
                        downloadTotal = message.downlinkTotal,
                        sampleTimeMs = SystemClock.elapsedRealtime()
                    )
                    callbacks?.onTrafficUpdate(snapshot)
                } catch (e: Exception) {
                    Log.e(TAG, "writeStatus callback error", e)
                }
            }
        }

        override fun writeGroups(groups: OutboundGroupIterator?) {
            if (groups == null) return
            try {
                processGroups(groups)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing groups update", e)
            }
        }

        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
        override fun updateClashMode(newMode: String?) {}

        override fun writeConnectionEvents(events: ConnectionEvents?) {
            events ?: return
            try {
                val runtimeMappings = NodeProtectionStore.runtimeMappings()
                val eventData = ConnectionTrafficEventReader.read(events)
                recordDirectIncidents(eventData)
                if (events.reset) connectionTrafficAttributor.clear()
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
                Log.e(TAG, "Error processing connection events", e)
            }
        }
    }

    private fun processGroups(groups: OutboundGroupIterator) {
        var changed = false

        Log.d(TAG, "writeGroups called")

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

    private fun processGroup(group: OutboundGroup): Boolean {
        val tag = group.tag
        val selected = group.selected
        var changed = false

        Log.d(TAG, "Processing group: $tag, selected=$selected")

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

    private fun enforceRuntimeMeteredProtection(
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

    private fun enforceConnectionStormGuard(decision: ConnectionStormDecision?) {
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

    private fun persistConnectionIncident(decision: ConnectionStormDecision, closed: Boolean) {
        val snapshot = decision.toIncidentSnapshot(
            mode = "vpn",
            closeReason = decision.incidentCloseReason(),
            closeSucceeded = closed,
            timestampEpochMs = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime()
        )
        serviceScope.launch(Dispatchers.IO) {
            runCatching { connectionIncidentHistory.append(snapshot) }
                .onFailure { error -> Log.e(TAG, "Failed to persist connection incident", error) }
        }
    }

    private fun recordDirectIncidents(events: List<ConnectionTrafficEventData>) {
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
                .onFailure { error -> Log.e(TAG, "Failed to persist direct incidents", error) }
        }
    }

    private fun recordAttributedTraffic(records: List<AttributedConnectionTraffic>) {
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

    private fun updateResolvedProxySelection(): Boolean {
        val selectedTag = resolveConcreteGroupSelection("PROXY", groupSelectedOutbounds) ?: return false
        if (SelectorManager.isSelectionPending()) {
            Log.d(TAG, "Deferring runtime node publication until explicit switch cleanup: $selectedTag")
            return false
        }
        val selected = resolveRuntimeNodeLabel(selectedTag, NodeProtectionStore.runtimeMappings()) ?: return false
        if (selected == realTimeNodeName) return false

        // 只更新运行态展示，不写回用户手选节点。
        // writeGroups 会在 urltest/自动切换时频繁回调，写回 activeNodeId 会造成节点乱飞。
        realTimeNodeName = selected
        VpnStateStore.setActiveLabel(selected)
        callbacks?.onRuntimeNodeChanged(selected)
        Log.i(TAG, "Real-time node update: tag=$selectedTag, display=$selected")
        return true
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth")
    private fun processConnections(connections: Connections) {
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
                Log.d(TAG, "Connections label updated: ${newLabel ?: "(null)"}")
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

    fun cleanup() {
        stop()
        groupSelectedOutbounds.clear()
        realTimeNodeName = null
        activeConnectionNode = null
        activeConnectionLabel = null
        recentConnectionIds = emptyList()
        connectionsSnapshot = null
        connectionTrafficAttributor.clear()
        connectionStormGuard.clear()
        callbacks = null
        isNonEssentialSuspended = false
    }

    fun suspendNonEssential() {
        if (isNonEssentialSuspended) return
        isNonEssentialSuspended = true

        commandClientLogs?.disconnect()
        commandClientLogs = null

        commandClientConnections?.disconnect()
        commandClientConnections = null

        Log.i(TAG, "Non-essential clients suspended (Logs, Connections)")
    }

    fun resumeNonEssential() {
        if (!isNonEssentialSuspended) return
        isNonEssentialSuspended = false

        if (commandServer == null) {
            Log.w(TAG, "Cannot resume: no CommandServer")
            return
        }

        val handler = clientHandler ?: createClientHandler().also { clientHandler = it }

        try {
            val optionsLog = CommandClientOptions()
            optionsLog.addCommand(Libbox.CommandLog)
            optionsLog.statusInterval = 1500L * 1000L * 1000L
            commandClientLogs = Libbox.newCommandClient(handler, optionsLog)
            commandClientLogs?.connect()
            Log.i(TAG, "CommandClient (Logs) resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume Logs client", e)
        }

        try {
            val optionsConn = CommandClientOptions()
            optionsConn.addCommand(Libbox.CommandConnections)
            optionsConn.statusInterval = 1000L * 1000L * 1000L
            commandClientConnections = Libbox.newCommandClient(handler, optionsConn)
            commandClientConnections?.connect()
            Log.i(TAG, "CommandClient (Connections) resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume Connections client", e)
        }
    }

    val isNonEssentialActive: Boolean
        get() = !isNonEssentialSuspended && (commandClientLogs != null || commandClientConnections != null)
}
