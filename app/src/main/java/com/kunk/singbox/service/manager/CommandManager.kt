package com.kunk.singbox.service.manager

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.TrafficRepository
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

    @Volatile
    private var clientHandler: CommandClientHandler? = null

    @Volatile
    private var kernelLogObserver: KernelLogObserver? = null

    @Volatile
    private var isNonEssentialSuspended: Boolean = false

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
    private var lastConnectionsLabelLogged: String? = null

    interface Callbacks {
        fun requestNotificationUpdate(force: Boolean)
        fun resolveEgressNodeName(tagOrSelector: String?): String?
        fun onGroupSelectionChanged(groupTag: String, selectedTag: String) {}
        fun onRuntimeNodeChanged(nodeName: String) {}
        fun onTrafficUpdate(snapshot: TrafficMonitor.TrafficSnapshot) {}
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
        commandServer = server
        Log.i(TAG, "CommandServer created")
        server
    }

    fun startServer(): Result<Unit> = runCatching {
        commandServer?.start() ?: throw IllegalStateException("CommandServer not created")
        Log.i(TAG, "CommandServer started")

        // BoxWrapperManager.init 延迟到 libbox 启动后调用
        // 避免 Libbox.hasSelector() 在 box 未运行时超时阻塞 ~1.5s
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
        optionsGroup.statusInterval = 3000L * 1000L * 1000L // 3s
        commandClientGroup = Libbox.newCommandClient(handler, optionsGroup)
        commandClientGroup?.connect()
        Log.i(TAG, "CommandClient connected (Group, interval=3s)")

        val optionsLog = CommandClientOptions()
        optionsLog.addCommand(Libbox.CommandLog)
        optionsLog.statusInterval = 1500L * 1000L * 1000L
        commandClientLogs = Libbox.newCommandClient(handler, optionsLog)
        commandClientLogs?.connect()
        Log.i(TAG, "CommandClient connected (Logs, interval=1.5s)")

        val optionsConn = CommandClientOptions()
        optionsConn.addCommand(Libbox.CommandConnections)
        optionsConn.statusInterval = 5000L * 1000L * 1000L
        commandClientConnections = Libbox.newCommandClient(handler, optionsConn)
        commandClientConnections?.connect()
        Log.i(TAG, "CommandClient connected (Connections, interval=5s)")

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

    @Suppress("CognitiveComplexMethod")
    suspend fun stopAndWaitPortRelease(
        proxyPort: Int,
        waitTimeoutMs: Long = PORT_RELEASE_TIMEOUT_MS,
        forceKillOnTimeout: Boolean = true,
        enforceReleaseOnTimeout: Boolean = false
    ): Result<Unit> = runCatching {
        Log.i(TAG, "stopAndWaitPortRelease: port=$proxyPort, timeout=${waitTimeoutMs}ms, forceKill=$forceKillOnTimeout")
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

        val closeStart = SystemClock.elapsedRealtime()
        runCatching {
            commandServer?.closeService()
        }.onFailure { e ->
            // closeService 在服务已关闭时返回 invalid argument，属于正常情况
            Log.d(TAG, "CommandServer.closeService: ${e.message} (expected if already closed)")
        }
        Log.i(TAG, "CommandServer service closed in ${SystemClock.elapsedRealtime() - closeStart}ms")

        commandServer?.close()
        commandServer = null

        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.cancel(VpnNotificationManager.NOTIFICATION_ID)
            nm?.cancel(11) // ProxyOnlyService NOTIFICATION_ID
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
            val method = client.javaClass.methods.find {
                it.name == "closeConnection" && it.parameterCount == 1
            }
            method?.invoke(client, connId)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun createClientHandler(): CommandClientHandler = object : CommandClientHandler {
        override fun connected() {}

        override fun disconnected(message: String?) {
            Log.w(TAG, "CommandClient disconnected: $message")
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

                    if (snapshot.uploadDelta > 0L || snapshot.downloadDelta > 0L) {
                        val trafficRepo = TrafficRepository.getInstance(context)
                        val configRepo = ConfigRepository.getInstance(context)

                        val activeNodeId = configRepo.activeNodeId.value
                        if (activeNodeId != null) {
                            val nodeName = configRepo.getNodeById(activeNodeId)?.name
                            trafficRepo.addTraffic(
                                activeNodeId,
                                snapshot.uploadDelta,
                                snapshot.downloadDelta,
                                nodeName
                            )
                        }
                    }
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
            val prev = groupSelectedOutbounds.put(tag, selected)
            if (prev != selected) {
                changed = true
                callbacks?.onGroupSelectionChanged(tag, selected)
            }
        }

        return changed
    }

    private fun updateResolvedProxySelection(): Boolean {
        val selected = resolveConcreteGroupSelection("PROXY", groupSelectedOutbounds) ?: return false
        if (selected == realTimeNodeName) return false

        // 只更新运行态展示，不写回用户手选节点。
        // writeGroups 会在 urltest/自动切换时频繁回调，写回 activeNodeId 会造成节点乱飞。
        realTimeNodeName = selected
        VpnStateStore.setActiveLabel(selected)
        callbacks?.onRuntimeNodeChanged(selected)
        Log.i(TAG, "Real-time node update: $selected")
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
            optionsConn.statusInterval = 5000L * 1000L * 1000L
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
