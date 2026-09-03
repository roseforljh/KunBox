package com.kunk.singbox.service.manager

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.service.network.TrafficMonitor
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.selects.select

internal class TrafficStatusGate {
    internal val lock = Any()
    internal var accepting = false

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

@Suppress("LargeClass", "TooManyFunctions")
class CommandManager(
    internal val context: Context,
    internal val serviceScope: CoroutineScope
) {
    companion object {
        internal const val TAG = "CommandManager"
        internal const val MAX_LOG_LINES = 300
        internal const val PORT_RELEASE_TIMEOUT_MS = 10000L
        internal const val PORT_CHECK_INTERVAL_MS = 50L
        internal const val MAX_GROUP_SELECTION_DEPTH = 4
        internal const val BASE_COMMAND_CLIENT_COUNT = 3
        internal const val TELEGRAM_PACKAGE = "org.telegram.messenger"
        internal const val GROUP_STATUS_INTERVAL_MS = 500L
        internal const val COMMAND_LOG_READY_TIMEOUT_MS = 5_000L
        internal const val COMMAND_LOG_HEARTBEAT_TIMEOUT_MS = 15_000L
        internal const val COMMAND_LOG_STABLE_WINDOW_MS = 30_000L
        internal const val BASE_COMMAND_HEARTBEAT_TIMEOUT_MS = 15_000L
        internal val COMMAND_LOG_RECONNECT_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)
        internal val TARGETED_CLOSE_DISPATCHER = Dispatchers.IO.limitedParallelism(8)

        internal fun commandLogReconnectDelay(failureCount: Int): Long {
            require(failureCount > 0)
            return COMMAND_LOG_RECONNECT_DELAYS_MS[
                (failureCount - 1).coerceAtMost(COMMAND_LOG_RECONNECT_DELAYS_MS.lastIndex)
            ]
        }

        internal fun acceptsCommandLogCallback(
            sessionGeneration: Long,
            activeSessionGeneration: Long,
            clientToken: Long,
            activeClientToken: Long,
            pendingClientToken: Long
        ): Boolean = sessionGeneration > 0L &&
            sessionGeneration == activeSessionGeneration &&
            clientToken > 0L &&
            (clientToken == activeClientToken || clientToken == pendingClientToken)

        internal fun shouldNotifyCommandLogObserver(replayed: Boolean): Boolean = !replayed

        internal fun nextCommandLogFailureCount(previous: Int, stable: Boolean): Int =
            if (stable) 1 else previous + 1

        internal fun isCommandHeartbeatStale(
            lastHeartbeatMs: Long?,
            nowMs: Long,
            timeoutMs: Long = BASE_COMMAND_HEARTBEAT_TIMEOUT_MS
        ): Boolean = lastHeartbeatMs == null || nowMs < lastHeartbeatMs || nowMs - lastHeartbeatMs > timeoutMs

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
    internal var commandServer: CommandServer? = null
    internal var commandClient: CommandClient? = null
    internal var commandClientGroup: CommandClient? = null
    internal var commandClientLogs: CommandClient? = null
    internal var commandClientConnections: CommandClient? = null
    internal val runtimeAccess = Any()
    internal val runtimeGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile internal var runtimeHandle: CommandRuntimeHandle? = null

    internal data class CommandRuntimeHandle(
        val generation: Long,
        val server: CommandServer?,
        val statusClient: CommandClient?,
        val groupClient: CommandClient?,
        val logClient: CommandClient?,
        val connectionsClient: CommandClient?
    )

    internal data class DetachedCommandRuntime(
        val handle: CommandRuntimeHandle?,
        val logSupervisor: Job?,
        val logReady: CompletableDeferred<Unit>?
    )

    internal data class CommandLogAttemptSignals(
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
        val disconnected: CompletableDeferred<String?> = CompletableDeferred(),
        val heartbeat: Channel<Unit> = Channel(Channel.CONFLATED)
    )

    internal enum class BaseCommandChannel {
        STATUS,
        GROUP,
        CONNECTIONS
    }

    @Volatile
    internal var kernelLogObserver: KernelLogObserver? = null

    internal var commandFdProvider: (() -> ParcelFileDescriptor?)? = null

    internal var commandLogReconnectEnabled = false

    internal var commandLogReconnectJob: Job? = null

    internal var activeCommandSessionGeneration = 0L
    internal var activeCommandLogClientToken = 0L
    internal var pendingCommandLogClientToken = 0L
    internal val commandLogClientTokenSequence = AtomicLong(0L)
    internal var commandLogSessionReady: CompletableDeferred<Unit>? = null
    internal val readyBaseCommandChannels = mutableSetOf<BaseCommandChannel>()
    internal val baseCommandHeartbeatAtMs = mutableMapOf<BaseCommandChannel, Long>()
    internal var commandBaseHealthy = false
    internal var commandLogHealthy = false
    internal var commandLogHeartbeatAtMs = 0L
    internal var lastPublishedControlHealth: Boolean? = null

    internal val trafficStatusGate = TrafficStatusGate()
    internal var connectionsSnapshot: Connections? = null

    internal val groupSelectedOutbounds = ConcurrentHashMap<String, String>()
    @Volatile var realTimeNodeName: String? = null
        internal set

    @Volatile var activeConnectionNode: String? = null
        internal set
    @Volatile var activeConnectionLabel: String? = null
        internal set
    var recentConnectionIds: List<String> = emptyList()
        internal set

    internal val trafficMonitor = TrafficMonitor()
    internal val connectionTrafficAttributor = ConnectionTrafficAttributor()
    internal val connectionStormGuard = ConnectionStormGuard()
    internal val connectionIncidentHistory = ConnectionIncidentHistory(context)
    internal val directConnectionIncidentHistory = DirectConnectionIncidentHistory(
        context,
        SingBoxIpcHub.serviceInstanceId()
    )
    internal val applicationRouteTraceSignatures = mutableMapOf<String, String>()
    internal val telegramUid: Int? = runCatching {
        context.packageManager.getApplicationInfo(TELEGRAM_PACKAGE, 0).uid
    }.getOrNull()
    internal var lastConnectionsLabelLogged: String? = null

    interface Callbacks {
        fun requestNotificationUpdate(force: Boolean)
        fun resolveEgressNodeName(tagOrSelector: String?): String?
        fun onGroupSelectionChanged(groupTag: String, selectedTag: String) {}
        fun onRuntimeNodeChanged(nodeName: String) {}
        fun onTrafficUpdate(snapshot: TrafficMonitor.TrafficSnapshot) {}
        fun onControlChannelHealth(ready: Boolean) {}
        fun onControlChannelRecoveryRequired(reason: String) {}
        fun onServiceStop(): Unit
        fun onServiceReload(): Unit
    }

    internal var callbacks: Callbacks? = null

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

            override fun triggerNativeCrash() = Unit

            override fun connectSSHAgent(): Int = -1

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

    fun clearStaleServerForStartup(): Result<Boolean> = runCatching {
        val staleServer = synchronized(runtimeAccess) {
            if (runtimeHandle != null) return@runCatching false
            commandServer.also { commandServer = null }
        } ?: return@runCatching false

        runCatching { staleServer.closeService() }
            .onFailure { Log.d(TAG, "Stale CommandServer service was already closed: ${it.message}") }
        staleServer.close()
        Log.w(TAG, "Cleared stale CommandServer before startup")
        true
    }

    fun hasActiveRuntime(): Boolean = synchronized(runtimeAccess) { runtimeHandle != null }

    fun adoptServer(server: CommandServer) {
        val staleServer = synchronized(runtimeAccess) {
            if (commandServer == null || commandServer === server) {
                commandServer = server
                return@synchronized null
            }
            check(runtimeHandle == null) { "Active CommandServer cannot be replaced" }
            commandServer.also { commandServer = server }
        }
        if (staleServer != null) {
            runCatching { staleServer.closeService() }
            runCatching { staleServer.close() }
            Log.w(TAG, "Replaced stale CommandServer during adoption")
        }
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

    fun startClients(): Result<Unit> = startClients(null)

    suspend fun startClientsWithFd(
        fdProvider: () -> ParcelFileDescriptor?,
        preserveServerOnFailure: Boolean = false
    ): Result<Unit> {
        val started = startClients(fdProvider, preserveServerOnFailure)
        if (started.isFailure) return started
        val generation = currentRuntimeGeneration()
        return runCatching { awaitCommandLogReady(generation) }.onFailure {
            if (isCommandSessionActive(generation)) stop(closeServer = !preserveServerOnFailure)
        }
    }

    internal fun startClients(
        fdProvider: (() -> ParcelFileDescriptor?)?,
        preserveServerOnFailure: Boolean = false
    ): Result<Unit> =
        runCatching { beginCommandRuntime(fdProvider) }.fold(
            onSuccess = { generation ->
                startCommandClients(generation, fdProvider, preserveServerOnFailure)
            },
            onFailure = Result.Companion::failure
        )

    suspend fun reconnectControlClientsWithFd(
        fdProvider: () -> ParcelFileDescriptor?
    ): Result<Unit> = runCatching {
        val detached = detachCommandRuntime()
            ?: error("Control runtime is not active")
        val handle = detached.handle
            ?: error("Command runtime handle is unavailable")
        val server = handle.server
            ?: error("Command server is unavailable")
        detached.logSupervisor?.cancel()
        detached.logReady?.cancel()
        stopTrafficUpdatesAndWait()
        handle.statusClient?.disconnect()
        handle.groupClient?.disconnect()
        handle.logClient?.disconnect()
        handle.connectionsClient?.disconnect()
        connectionsSnapshot = null
        synchronized(runtimeAccess) { commandServer = server }
        runCatching {
            startClientsWithFd(fdProvider, preserveServerOnFailure = true).getOrThrow()
        }.onFailure {
            synchronized(runtimeAccess) {
                if (commandServer == null) commandServer = server
            }
            throw it
        }
        Log.i(TAG, "Control clients reconnected without restarting CommandServer")
    }

    @Suppress("LongMethod")
    internal fun startCommandClients(
        generation: Long,
        fdProvider: (() -> ParcelFileDescriptor?)?,
        preserveServerOnFailure: Boolean = false
    ): Result<Unit> = runCatching {
        trafficMonitor.reset()
        connectionStormGuard.clear()
        trafficStatusGate.start()
        val statusHandler = createClientHandler(generation, BaseCommandChannel.STATUS)
        val groupHandler = createClientHandler(generation, BaseCommandChannel.GROUP)
        val connectionsHandler = createClientHandler(generation, BaseCommandChannel.CONNECTIONS)
        val logHandler = createClientHandler(generation, channel = null)
        val createdClients = mutableListOf<CommandClient>()
        try {
            val statusClient = createConnectedCommandClient(statusHandler, fdProvider) {
                addCommand(Libbox.CommandStatus)
                statusInterval = 3000L * 1000L * 1000L
            }
            createdClients += statusClient
            Log.i(TAG, "CommandClient connected (Status, interval=3s)")

            val groupClient = createConnectedCommandClient(groupHandler, fdProvider) {
                addCommand(Libbox.CommandGroup)
                statusInterval = GROUP_STATUS_INTERVAL_MS * 1000L * 1000L
            }
            createdClients += groupClient
            Log.i(TAG, "CommandClient connected (Group, interval=${GROUP_STATUS_INTERVAL_MS}ms)")

            val connectionsClient = createConnectedCommandClient(connectionsHandler, fdProvider) {
                addCommand(Libbox.CommandConnections)
                statusInterval = 1000L * 1000L * 1000L
            }
            createdClients += connectionsClient
            Log.i(TAG, "CommandClient connected (Connections, interval=1s)")
            synchronized(runtimeAccess) {
                check(generation == activeCommandSessionGeneration) { "Command runtime changed during startup" }
                commandClient = statusClient
                commandClientGroup = groupClient
                commandClientConnections = connectionsClient
                runtimeHandle = CommandRuntimeHandle(
                    generation = generation,
                    server = commandServer,
                    statusClient = statusClient,
                    groupClient = groupClient,
                    logClient = null,
                    connectionsClient = connectionsClient
                )
            }
        } catch (error: Exception) {
            createdClients.forEach { client -> runCatching { client.disconnect() } }
            throw error
        }
        startCommandLogSupervisor(generation, logHandler, fdProvider)

        serviceScope.launch {
            delay(3500)
            if (!isCommandSessionActive(generation)) return@launch
            val groupsSize = groupSelectedOutbounds.size
            val label = activeConnectionLabel
            if (groupsSize == 0 && label.isNullOrBlank()) {
                Log.w(TAG, "Command callbacks not observed yet")
            } else {
                Log.i(TAG, "Command callbacks OK (groups=$groupsSize)")
            }
        }
        Unit
    }.onFailure {
        val stillOwnsStartup = synchronized(runtimeAccess) {
            generation == activeCommandSessionGeneration
        }
        if (stillOwnsStartup) stop(closeServer = !preserveServerOnFailure)
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
        val detached = detachCommandRuntime(expectedRuntimeGeneration) ?: return@runCatching
        val capturedHandle = detached.handle
        detached.logSupervisor?.cancel()
        detached.logReady?.cancel()
        stopTrafficUpdatesAndWait()

        capturedHandle?.statusClient?.disconnect()
        capturedHandle?.groupClient?.disconnect()
        capturedHandle?.logClient?.disconnect()
        capturedHandle?.connectionsClient?.disconnect()
        BoxWrapperManager.release()
        connectionsSnapshot = null
        connectionTrafficAttributor.clear()
        connectionStormGuard.clear()

        val closeStart = SystemClock.elapsedRealtime()
        runCatching {
            capturedHandle?.server?.closeService()
        }.onFailure { e ->
            // closeService 在服务已关闭时返回 invalid argument，属于正常情况
            Log.d(TAG, "CommandServer.closeService: ${e.message} (expected if already closed)")
        }
        Log.i(TAG, "CommandServer service closed in ${SystemClock.elapsedRealtime() - closeStart}ms")

        capturedHandle?.server?.close()

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

    fun stop(closeServer: Boolean = true): Result<Unit> = runCatching {
        val detached = requireNotNull(detachCommandRuntime())
        val capturedHandle = detached.handle
        detached.logSupervisor?.cancel()
        detached.logReady?.cancel()
        stopTrafficUpdatesAndWait()
        capturedHandle?.statusClient?.disconnect()
        capturedHandle?.groupClient?.disconnect()
        capturedHandle?.logClient?.disconnect()
        capturedHandle?.connectionsClient?.disconnect()

        if (closeServer) BoxWrapperManager.release()
        connectionsSnapshot = null
        connectionTrafficAttributor.clear()
        connectionStormGuard.clear()

        if (closeServer) {
            runCatching { capturedHandle?.server?.closeService() }
                .onFailure { Log.w(TAG, "CommandServer.closeService failed: ${it.message}") }
            capturedHandle?.server?.close()
        }
        Log.i(TAG, "Command Server/Client stopped")
    }

    fun stopTrafficUpdatesAndWait() {
        trafficStatusGate.stopAndWait()
        trafficMonitor.reset()
    }

    internal suspend fun waitForPortRelease(port: Int, timeoutMs: Long): Boolean {
        val startTime = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            if (isPortAvailable(port)) {
                return true
            }
            delay(PORT_CHECK_INTERVAL_MS)
        }
        return false
    }

    internal fun isPortAvailable(port: Int): Boolean {
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

    fun getCommandServer(): CommandServer? = synchronized(runtimeAccess) { commandServer }

    fun getCommandClient(): CommandClient? = synchronized(runtimeAccess) { commandClient }
    fun getConnectionsClient(): CommandClient? = synchronized(runtimeAccess) { commandClientConnections }

    fun getSelectedOutbound(groupTag: String): String? = groupSelectedOutbounds[groupTag]

    fun getResolvedSelectedOutbound(groupTag: String): String? {
        return resolveConcreteGroupSelection(groupTag, groupSelectedOutbounds)
    }

    fun getGroupsCount(): Int = groupSelectedOutbounds.size

    internal fun currentRuntimeGeneration(): Long = synchronized(runtimeAccess) {
        runtimeHandle?.generation ?: activeCommandSessionGeneration
    }

    fun closeConnections(): Boolean {
        val clients = synchronized(runtimeAccess) {
            listOfNotNull(commandClientConnections, commandClient)
        }
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
        val client = synchronized(runtimeAccess) {
            commandClientConnections ?: commandClient
        } ?: return false
        return try {
            client.closeConnection(connId)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun connectionIdsForOutboundTag(outboundTag: String): Set<String> {
        return connectionStormGuard.activeConnectionIdsForOutbound(outboundTag)
    }

    suspend fun closeConnectionsById(connectionIds: Collection<String>): Set<String> {
        val ids = connectionIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return emptySet()
        val client = synchronized(runtimeAccess) {
            commandClientConnections ?: commandClient
        } ?: return emptySet()
        val closed = coroutineScope {
            ids.map { id ->
                async(TARGETED_CLOSE_DISPATCHER) {
                    try {
                        client.closeConnection(id)
                        id
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "closeConnection failed id=$id: ${error.message}")
                        null
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }
        connectionStormGuard.acknowledgeClosedConnectionIds(closed)
        return closed
    }

    internal fun beginCommandRuntime(fdProvider: (() -> ParcelFileDescriptor?)?): Long =
        synchronized(runtimeAccess) {
            check(runtimeHandle == null && activeCommandSessionGeneration == 0L) {
                "Command runtime is already active"
            }
            commandLogReconnectJob?.cancel()
            val generation = runtimeGeneration.incrementAndGet()
            activeCommandSessionGeneration = generation
            commandFdProvider = fdProvider
            commandLogReconnectEnabled = true
            commandLogReconnectJob = null
            activeCommandLogClientToken = 0L
            pendingCommandLogClientToken = 0L
            commandLogSessionReady = CompletableDeferred()
            readyBaseCommandChannels.clear()
            baseCommandHeartbeatAtMs.clear()
            commandBaseHealthy = false
            commandLogHealthy = false
            commandLogHeartbeatAtMs = 0L
            lastPublishedControlHealth = null
            commandClientLogs = null
            groupSelectedOutbounds.clear()
            generation
        }

    @Suppress("ComplexCondition")
    internal fun detachCommandRuntime(expectedGeneration: Long = 0L): DetachedCommandRuntime? =
        synchronized(runtimeAccess) {
            val observedGeneration = runtimeHandle?.generation ?: activeCommandSessionGeneration
            if (expectedGeneration > 0L && observedGeneration != expectedGeneration) {
                Log.w(TAG, "Skip stale command cleanup expected=$expectedGeneration current=$observedGeneration")
                return@synchronized null
            }
            val handle = runtimeHandle ?: if (
                commandServer != null || commandClient != null || commandClientGroup != null ||
                commandClientLogs != null || commandClientConnections != null
            ) {
                CommandRuntimeHandle(
                    generation = observedGeneration,
                    server = commandServer,
                    statusClient = commandClient,
                    groupClient = commandClientGroup,
                    logClient = commandClientLogs,
                    connectionsClient = commandClientConnections
                )
            } else {
                null
            }
            val supervisor = commandLogReconnectJob
            val logReady = commandLogSessionReady
            activeCommandSessionGeneration = 0L
            commandLogReconnectEnabled = false
            commandLogReconnectJob = null
            activeCommandLogClientToken = 0L
            pendingCommandLogClientToken = 0L
            commandLogSessionReady = null
            readyBaseCommandChannels.clear()
            baseCommandHeartbeatAtMs.clear()
            commandBaseHealthy = false
            commandLogHealthy = false
            commandLogHeartbeatAtMs = 0L
            lastPublishedControlHealth = null
            commandFdProvider = null
            runtimeHandle = null
            commandServer = null
            commandClient = null
            commandClientGroup = null
            commandClientLogs = null
            commandClientConnections = null
            groupSelectedOutbounds.clear()
            DetachedCommandRuntime(handle, supervisor, logReady)
        }

    internal suspend fun awaitCommandLogReady(generation: Long) {
        val ready = synchronized(runtimeAccess) {
            check(generation > 0L && generation == activeCommandSessionGeneration) {
                "Command runtime changed before log readiness"
            }
            commandLogSessionReady ?: error("Command log readiness is unavailable")
        }
        withTimeout(COMMAND_LOG_READY_TIMEOUT_MS) { ready.await() }
        check(synchronized(runtimeAccess) {
            generation == activeCommandSessionGeneration && commandBaseHealthy && commandLogHealthy
        }) { "Command control channels are not ready" }
    }

    fun isControlChannelReady(): Boolean = synchronized(runtimeAccess) {
        activeCommandSessionGeneration > 0L && commandBaseHealthy && commandLogHealthy
    }

    internal fun startCommandLogSupervisor(
        generation: Long,
        handler: CommandClientHandler,
        fdProvider: (() -> ParcelFileDescriptor?)?
    ) {
        val supervisor = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            superviseCommandLog(generation, handler, fdProvider)
        }
        val accepted = synchronized(runtimeAccess) {
            if (isCommandLogSessionActiveLocked(generation) && commandLogReconnectJob == null) {
                commandLogReconnectJob = supervisor
                true
            } else {
                false
            }
        }
        if (accepted) {
            supervisor.invokeOnCompletion {
                synchronized(runtimeAccess) {
                    if (commandLogReconnectJob === supervisor) commandLogReconnectJob = null
                }
            }
            supervisor.start()
        } else {
            supervisor.cancel()
        }
    }

    internal suspend fun superviseCommandLog(
        generation: Long,
        handler: CommandClientHandler,
        fdProvider: (() -> ParcelFileDescriptor?)?
    ) {
        var failureCount = 0
        while (isCommandLogSessionActive(generation)) {
            if (failureCount > 0) delay(commandLogReconnectDelay(failureCount))
            val retryCount = failureCount
            var readyAtMs = 0L
            try {
                val reason = runCommandLogAttempt(generation, handler, fdProvider) {
                    readyAtMs = SystemClock.uptimeMillis()
                    markCommandLogHealth(generation, ready = true)
                    if (retryCount > 0) {
                        LogRepository.getInstance().addAlwaysLog(
                            "INFO [COMMAND_LOG] reconnected attempt=$retryCount"
                        )
                    }
                }
                markCommandLogHealth(generation, ready = false)
                LogRepository.getInstance().addAlwaysLog(
                    "WARN [COMMAND_LOG] disconnected reconnect=true reason=${reason.orEmpty()}"
                )
                val stable = readyAtMs > 0L &&
                    SystemClock.uptimeMillis() - readyAtMs >= COMMAND_LOG_STABLE_WINDOW_MS
                failureCount = nextCommandLogFailureCount(failureCount, stable)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val stable = readyAtMs > 0L &&
                    SystemClock.uptimeMillis() - readyAtMs >= COMMAND_LOG_STABLE_WINDOW_MS
                failureCount = nextCommandLogFailureCount(failureCount, stable)
                markCommandLogHealth(generation, ready = false)
                LogRepository.getInstance().addAlwaysLog(
                    "WARN [COMMAND_LOG] reconnect_failed attempt=$failureCount " +
                        "reason=${error.message.orEmpty()}"
                )
            }
        }
    }

    @Suppress("CognitiveComplexMethod", "LongMethod")
    internal suspend fun runCommandLogAttempt(
        generation: Long,
        delegate: CommandClientHandler,
        fdProvider: (() -> ParcelFileDescriptor?)?,
        onReady: () -> Unit
    ): String? {
        val token = commandLogClientTokenSequence.incrementAndGet()
        val signals = CommandLogAttemptSignals()
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandLog)
            statusInterval = 1500L * 1000L * 1000L
        }
        val client = requireNotNull(
            Libbox.newCommandClient(
                createLogClientHandler(delegate, generation, token, signals),
                options
            )
        ) { "Command log client creation failed" }
        val registered = synchronized(runtimeAccess) {
            if (isCommandLogSessionActiveLocked(generation) && pendingCommandLogClientToken == 0L) {
                pendingCommandLogClientToken = token
                true
            } else {
                false
            }
        }
        if (!registered) {
            runCatching { client.disconnect() }
            throw CancellationException("Command log session is inactive")
        }
        try {
            connectClient(client, fdProvider)
            currentCoroutineContext().ensureActive()
            withTimeout(COMMAND_LOG_READY_TIMEOUT_MS) {
                select {
                    signals.ready.onAwait { }
                    signals.disconnected.onAwait { reason ->
                        error("Command log disconnected before ready: ${reason.orEmpty()}")
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            synchronized(runtimeAccess) {
                check(isCommandLogSessionActiveLocked(generation) && pendingCommandLogClientToken == token) {
                    "Command log session changed before ready"
                }
                pendingCommandLogClientToken = 0L
                activeCommandLogClientToken = token
                commandClientLogs = client
                runtimeHandle?.takeIf { it.generation == generation }?.let { handle ->
                    runtimeHandle = handle.copy(logClient = client)
                }
            }
            onReady()
            return awaitCommandLogStream(generation, signals.heartbeat, signals.disconnected)
        } finally {
            synchronized(runtimeAccess) {
                if (pendingCommandLogClientToken == token) pendingCommandLogClientToken = 0L
                if (activeCommandLogClientToken == token) activeCommandLogClientToken = 0L
                if (commandClientLogs === client) commandClientLogs = null
                runtimeHandle?.takeIf { it.generation == generation }?.let { handle ->
                    if (handle.logClient === client) runtimeHandle = handle.copy(logClient = null)
                }
            }
            runCatching { client.disconnect() }
        }
    }
}
