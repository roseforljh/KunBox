package com.kunk.singbox.service.manager

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.selects.select

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

@Suppress("LargeClass", "TooManyFunctions")
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
        private const val BASE_COMMAND_CLIENT_COUNT = 3
        private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
        internal const val GROUP_STATUS_INTERVAL_MS = 500L
        internal const val COMMAND_LOG_READY_TIMEOUT_MS = 5_000L
        internal const val COMMAND_LOG_HEARTBEAT_TIMEOUT_MS = 15_000L
        internal const val COMMAND_LOG_STABLE_WINDOW_MS = 30_000L
        internal const val BASE_COMMAND_HEARTBEAT_TIMEOUT_MS = 15_000L
        internal val COMMAND_LOG_RECONNECT_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)
        private val TARGETED_CLOSE_DISPATCHER = Dispatchers.IO.limitedParallelism(8)

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

    private data class DetachedCommandRuntime(
        val handle: CommandRuntimeHandle?,
        val logSupervisor: Job?,
        val logReady: CompletableDeferred<Unit>?
    )

    private data class CommandLogAttemptSignals(
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
        val disconnected: CompletableDeferred<String?> = CompletableDeferred(),
        val heartbeat: Channel<Unit> = Channel(Channel.CONFLATED)
    )

    private enum class BaseCommandChannel {
        STATUS,
        GROUP,
        CONNECTIONS
    }

    @Volatile
    private var kernelLogObserver: KernelLogObserver? = null

    private var commandFdProvider: (() -> ParcelFileDescriptor?)? = null

    private var commandLogReconnectEnabled = false

    private var commandLogReconnectJob: Job? = null

    private var activeCommandSessionGeneration = 0L
    private var activeCommandLogClientToken = 0L
    private var pendingCommandLogClientToken = 0L
    private val commandLogClientTokenSequence = AtomicLong(0L)
    private var commandLogSessionReady: CompletableDeferred<Unit>? = null
    private val readyBaseCommandChannels = mutableSetOf<BaseCommandChannel>()
    private val baseCommandHeartbeatAtMs = mutableMapOf<BaseCommandChannel, Long>()
    private var commandBaseHealthy = false
    private var commandLogHealthy = false
    private var lastPublishedControlHealth: Boolean? = null

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
    private val applicationRouteTraceSignatures = mutableMapOf<String, String>()
    private val telegramUid: Int? = runCatching {
        context.packageManager.getApplicationInfo(TELEGRAM_PACKAGE, 0).uid
    }.getOrNull()
    private var lastConnectionsLabelLogged: String? = null

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

    suspend fun startClientsWithFd(fdProvider: () -> ParcelFileDescriptor?): Result<Unit> {
        val started = startClients(fdProvider)
        if (started.isFailure) return started
        val generation = currentRuntimeGeneration()
        return runCatching { awaitCommandLogReady(generation) }.onFailure {
            if (isCommandSessionActive(generation)) stop()
        }
    }

    private fun startClients(fdProvider: (() -> ParcelFileDescriptor?)?): Result<Unit> =
        runCatching { beginCommandRuntime(fdProvider) }.fold(
            onSuccess = { generation -> startCommandClients(generation, fdProvider) },
            onFailure = Result.Companion::failure
        )

    @Suppress("LongMethod")
    private fun startCommandClients(
        generation: Long,
        fdProvider: (() -> ParcelFileDescriptor?)?
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
        if (stillOwnsStartup) stop()
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

    fun stop(): Result<Unit> = runCatching {
        val detached = requireNotNull(detachCommandRuntime())
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

        runCatching { capturedHandle?.server?.closeService() }
            .onFailure { Log.w(TAG, "CommandServer.closeService failed: ${it.message}") }

        capturedHandle?.server?.close()
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

    private fun beginCommandRuntime(fdProvider: (() -> ParcelFileDescriptor?)?): Long =
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
            lastPublishedControlHealth = null
            commandClientLogs = null
            groupSelectedOutbounds.clear()
            generation
        }

    @Suppress("ComplexCondition")
    private fun detachCommandRuntime(expectedGeneration: Long = 0L): DetachedCommandRuntime? =
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

    private suspend fun awaitCommandLogReady(generation: Long) {
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

    private fun startCommandLogSupervisor(
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

    private suspend fun superviseCommandLog(
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
    private suspend fun runCommandLogAttempt(
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

    private fun createLogClientHandler(
        delegate: CommandClientHandler,
        generation: Long,
        token: Long,
        signals: CommandLogAttemptSignals
    ): CommandClientHandler = object : CommandClientHandler by delegate {
        private val replayPending = AtomicBoolean(false)

        override fun connected() = Unit

        override fun disconnected(message: String?) {
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
            signals.heartbeat.trySend(Unit)
            consumeCommandLogMessages(
                messageList,
                notifyObserver = shouldNotifyCommandLogObserver(replayPending.getAndSet(false)),
                acceptsCallbackLocked = {
                    acceptsCommandLogCallback(
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

    private fun acceptsCommandLogCallback(generation: Long, token: Long): Boolean =
        synchronized(runtimeAccess) {
            acceptsCommandLogCallback(
                sessionGeneration = generation,
                activeSessionGeneration = activeCommandSessionGeneration,
                clientToken = token,
                activeClientToken = activeCommandLogClientToken,
                pendingClientToken = pendingCommandLogClientToken
            )
        }

    private fun isCommandLogSessionActive(generation: Long): Boolean = synchronized(runtimeAccess) {
        isCommandLogSessionActiveLocked(generation)
    }

    private fun isCommandLogSessionActiveLocked(generation: Long): Boolean =
        generation > 0L && generation == activeCommandSessionGeneration &&
            commandLogReconnectEnabled

    private fun isCommandSessionActive(generation: Long): Boolean = synchronized(runtimeAccess) {
        generation > 0L && generation == activeCommandSessionGeneration
    }

    private suspend fun awaitCommandLogStream(
        generation: Long,
        heartbeat: Channel<Unit>,
        disconnected: CompletableDeferred<String?>
    ): String? {
        while (true) {
            val result = withTimeoutOrNull(COMMAND_LOG_HEARTBEAT_TIMEOUT_MS) {
                select<Pair<Boolean, String?>> {
                    heartbeat.onReceive { false to null }
                    disconnected.onAwait { true to it }
                }
            } ?: error("Command log heartbeat timeout")
            if (result.first) return result.second
            requireBaseCommandHeartbeats(generation)
        }
    }

    private fun requireBaseCommandHeartbeats(generation: Long) {
        val staleChannels = synchronized(runtimeAccess) {
            if (generation != activeCommandSessionGeneration) {
                throw CancellationException("Command runtime changed")
            }
            val now = SystemClock.uptimeMillis()
            BaseCommandChannel.entries.filter { channel ->
                isCommandHeartbeatStale(baseCommandHeartbeatAtMs[channel], now)
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
        check(staleChannels.isEmpty()) {
            "Base command heartbeat timeout: ${staleChannels.joinToString()}"
        }
    }

    private fun markCommandLogHealth(generation: Long, ready: Boolean) {
        synchronized(runtimeAccess) {
            if (!isCommandLogSessionActiveLocked(generation)) return
            commandLogHealthy = ready
            completeCommandControlReadyLocked()
            notifyCombinedCommandHealthLocked()
        }
    }

    private fun completeCommandControlReadyLocked() {
        if (commandBaseHealthy && commandLogHealthy) commandLogSessionReady?.complete(Unit)
    }

    private fun notifyCombinedCommandHealthLocked() {
        val ready = commandBaseHealthy && commandLogHealthy
        if (lastPublishedControlHealth == ready) return
        lastPublishedControlHealth = ready
        callbacks?.onControlChannelHealth(ready)
    }

    private fun markBaseCommandHealth(generation: Long, channel: BaseCommandChannel, ready: Boolean) {
        synchronized(runtimeAccess) {
            if (generation != activeCommandSessionGeneration) return
            if (ready) readyBaseCommandChannels += channel else readyBaseCommandChannels -= channel
            if (ready) baseCommandHeartbeatAtMs[channel] = SystemClock.uptimeMillis()
            else baseCommandHeartbeatAtMs.remove(channel)
            commandBaseHealthy = readyBaseCommandChannels.size == BASE_COMMAND_CLIENT_COUNT
            completeCommandControlReadyLocked()
            notifyCombinedCommandHealthLocked()
            if (!ready) callbacks?.onControlChannelRecoveryRequired("${channel.name.lowercase()}_disconnected")
        }
    }

    @Suppress("CognitiveComplexMethod")
    private fun consumeCommandLogMessages(
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
                        dispatchKernelLog(
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
    private fun createClientHandler(
        generation: Long,
        channel: BaseCommandChannel?
    ): CommandClientHandler = object : CommandClientHandler {
        override fun connected() = Unit

        override fun disconnected(message: String?) {
            channel?.let { markBaseCommandHealth(generation, it, ready = false) }
            Log.w(TAG, "CommandClient disconnected: $message")
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
                    Log.e(TAG, "writeStatus callback error", e)
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
                Log.e(TAG, "Error processing groups update", e)
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

    private fun connectClient(
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

    private fun createConnectedCommandClient(
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

    private fun recordApplicationRouteTrace(
        events: List<ConnectionTrafficEventData>,
        runtimeMappings: Map<String, RuntimeNodeRef>
    ) {
        events.forEach { event ->
            if (event.uid != telegramUid && TELEGRAM_PACKAGE !in event.packageNames) return@forEach
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
            Log.i(TAG, line)
            LogRepository.getInstance().addAlwaysLog("INFO $line")
            if (event.type == ConnectionTrafficAttributor.EVENT_CLOSED) {
                applicationRouteTraceSignatures.remove(event.id)
            }
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
        applicationRouteTraceSignatures.clear()
        connectionTrafficAttributor.clear()
        connectionStormGuard.clear()
        callbacks = null
    }
}
