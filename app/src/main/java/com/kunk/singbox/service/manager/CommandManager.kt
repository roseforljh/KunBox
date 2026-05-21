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
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 *
 *
 */
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
    private var isNonEssentialSuspended: Boolean = false

    private var connectionsSnapshot: Connections? = null

    private val groupSelectedOutbounds = ConcurrentHashMap<String, String>()
    @Volatile var realTimeNodeName: String? = null
        private set
    @Volatile var activeConnectionNode: String? = null
        private set
    @Volatile var activeConnectionLabel: String? = null
        private set
    var recentConnectionIds: List<String> = emptyList()
        private set

    private val urlTestResults = ConcurrentHashMap<String, Int>() // tag -> delay (ms)
    private val urlTestMutex = Mutex()
    @Volatile private var pendingUrlTestGroupTag: String? = null
    @Volatile private var urlTestCompletionCallback: ((Map<String, Int>) -> Unit)? = null

    private var lastUplinkTotal: Long = 0
    private var lastDownlinkTotal: Long = 0
    private var lastSpeedUpdateTime: Long = 0L
    private var lastConnectionsLabelLogged: String? = null

    /**
     */
    interface Callbacks {
        fun requestNotificationUpdate(force: Boolean)
        fun resolveEgressNodeName(tagOrSelector: String?): String?
        fun onServiceStop(): Unit
        fun onServiceReload(): Unit
    }

    private var callbacks: Callbacks? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     */
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

    /**
     */
    fun startServer(): Result<Unit> = runCatching {
        commandServer?.start() ?: throw IllegalStateException("CommandServer not created")
        Log.i(TAG, "CommandServer started")

        // BoxWrapperManager.init 延迟到 libbox 启动后调用
        // 避免 Libbox.hasSelector() 在 box 未运行时超时阻塞 ~1.5s
    }

    /**
     */
    fun startService(configContent: String, platformInterface: PlatformInterface): Result<Unit> = runCatching {
        val overrideOptions = OverrideOptions().apply {
            autoRedirect = false
        }
        commandServer?.startOrReloadService(configContent, overrideOptions)
            ?: throw IllegalStateException("CommandServer not created")
        Log.i(TAG, "CommandServer service started")
    }

    /**
     */
    fun closeService(): Result<Unit> = runCatching {
        commandServer?.closeService()
            ?: throw IllegalStateException("CommandServer not created")
        Log.i(TAG, "CommandServer service closed")
    }

    /**
     */
    fun startClients(): Result<Unit> = runCatching {

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

    /**
     */
    @Suppress("CognitiveComplexMethod")
    suspend fun stopAndWaitPortRelease(
        proxyPort: Int,
        waitTimeoutMs: Long = PORT_RELEASE_TIMEOUT_MS,
        forceKillOnTimeout: Boolean = true,
        enforceReleaseOnTimeout: Boolean = false
    ): Result<Unit> = runCatching {
        Log.i(TAG, "stopAndWaitPortRelease: port=$proxyPort, timeout=${waitTimeoutMs}ms, forceKill=$forceKillOnTimeout")

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

    /**
     */
    fun stop(): Result<Unit> = runCatching {
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

    /**
     */
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

    /**
     */
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

    /**
     */
    fun getCommandServer(): CommandServer? = commandServer

    /**
     */
    fun getCommandClient(): CommandClient? = commandClient
    fun getConnectionsClient(): CommandClient? = commandClientConnections

    /**
     */
    fun getSelectedOutbound(groupTag: String): String? = groupSelectedOutbounds[groupTag]

    /**
     */
    fun getGroupsCount(): Int = groupSelectedOutbounds.size

    /**
     */
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

    /**
     */
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

    /**
     *
     *
     */
    suspend fun urlTestGroup(groupTag: String, timeoutMs: Long = 10000L): Map<String, Int> {
        return urlTestGroup(groupTag, timeoutMs, emptySet(), null)
    }

    suspend fun urlTestGroup(
        groupTag: String,
        timeoutMs: Long,
        expectedTags: Set<String> = emptySet(),
        onProgress: ((Map<String, Int>) -> Unit)? = null
    ): Map<String, Int> {

        val client = commandClientGroup ?: commandClient ?: return emptyMap()

        return urlTestMutex.withLock {
            val resultUpdates = Channel<Map<String, Int>>(Channel.CONFLATED)
            try {
                pendingUrlTestGroupTag = groupTag
                val latestGroupResults = AtomicReference<Map<String, Int>>(emptyMap())
                urlTestCompletionCallback = { results ->
                    latestGroupResults.set(results)
                    onProgress?.invoke(results)
                    resultUpdates.trySend(results)
                }

                Log.i(TAG, "Triggering URL test for group: $groupTag")
                client.urlTest(groupTag)

                waitForStableUrlTestResults(
                    resultUpdates = resultUpdates,
                    latestGroupResults = latestGroupResults,
                    timeoutMs = timeoutMs,
                    expectedTags = expectedTags
                )

                val results = latestGroupResults.get().ifEmpty { urlTestResults.toMap() }
                if (results.isEmpty()) {
                    Log.w(TAG, "URL test timeout or no results for group: $groupTag")
                }
                results
            } catch (e: Exception) {
                Log.e(TAG, "URL test failed for group $groupTag: ${e.message}")
                emptyMap()
            } finally {
                pendingUrlTestGroupTag = null
                urlTestCompletionCallback = null
                resultUpdates.close()
            }
        }
    }

    private suspend fun waitForStableUrlTestResults(
        resultUpdates: Channel<Map<String, Int>>,
        latestGroupResults: AtomicReference<Map<String, Int>>,
        timeoutMs: Long,
        expectedTags: Set<String>
    ) {
        val stableWindowMs = 120L
        val startTime = SystemClock.elapsedRealtime()
        val existingResults = latestGroupResults.get()
        var latestResults = if (existingResults.isNotEmpty()) {
            existingResults
        } else {
            withTimeoutOrNull(timeoutMs) {
                resultUpdates.receiveCatching().getOrNull()
            } ?: emptyMap()
        }

        if (latestResults.isEmpty()) return

        if (expectedTags.isNotEmpty() && expectedTags.all { latestResults.containsKey(it) }) {
            latestGroupResults.set(latestResults)
            Log.i(TAG, "URL test completed early with ${latestResults.size} results")
            return
        }

        while (true) {
            val remainingMs = timeoutMs - (SystemClock.elapsedRealtime() - startTime)
            if (remainingMs <= 0L) break

            val nextResults = withTimeoutOrNull(minOf(stableWindowMs, remainingMs)) {
                resultUpdates.receiveCatching().getOrNull()
            } ?: break

            latestResults = nextResults
            if (expectedTags.isNotEmpty() && expectedTags.all { latestResults.containsKey(it) }) {
                break
            }
        }

        latestGroupResults.set(latestResults)
        Log.i(TAG, "URL test completed with ${latestResults.size} results")
    }

    fun getCachedUrlTestDelay(tag: String): Int? {
        val snapshot = snapshotUrlTestKeys()
        val aliasSources = buildDelayAliasSourceMap()
        val aliasTags = buildDelayAliasTags(tag, aliasSources)
        val rawAliasSummary = aliasSources.entries.joinToString(", ") { (source, value) ->
            "$source='${value.orEmpty()}'"
        }
        Log.d(
            TAG,
            "getCachedUrlTestDelay queryTag='$tag', normalized='${UrlTestTagMatcher.normalizeTag(tag)}', " +
                "aliasSources={$rawAliasSummary}, aliases=${aliasTags.joinToString(prefix = "[", postfix = "]")}, " +
                "keysCount=${snapshot.size}, keys=${snapshot.joinToString(prefix = "[", postfix = "]")}"
        )

        val matched = UrlTestTagMatcher.resolveDelayDetail(
            results = urlTestResults,
            queryTag = tag,
            aliasTags = aliasTags
        )

        if (matched != null) {
            Log.d(
                TAG,
                "getCachedUrlTestDelay ${matched.matchType} hit: tag='$tag', " +
                    "matchedKey='${matched.matchedKey}', delay=${matched.delay}"
            )
            return matched.delay
        }
        Log.d(TAG, "getCachedUrlTestDelay miss: tag='$tag'")
        return null
    }

    fun getCachedUrlTestDelayDebug(tag: String): String {
        val snapshot = snapshotUrlTestKeys()
        val aliasSources = buildDelayAliasSourceMap()
        val aliasTags = buildDelayAliasTags(tag, aliasSources)
        val matched = UrlTestTagMatcher.resolveDelayDetail(
            results = urlTestResults,
            queryTag = tag,
            aliasTags = aliasTags
        )

        val rawAliasSummary = aliasSources.entries.joinToString(", ") { (source, value) ->
            "$source='${value.orEmpty()}'"
        }
        val keySummary = snapshot.joinToString(prefix = "[", postfix = "]")

        return if (matched != null) {
            "HIT type=${matched.matchType}, query='$tag', matchedKey='${matched.matchedKey}', " +
                "delay=${matched.delay}, aliases=${aliasTags.joinToString(prefix = "[", postfix = "]")}, " +
                "aliasSources={$rawAliasSummary}, keysCount=${snapshot.size}, keys=$keySummary"
        } else {
            "MISS query='$tag', normalized='${UrlTestTagMatcher.normalizeTag(tag)}', " +
                "aliases=${aliasTags.joinToString(prefix = "[", postfix = "]")}, " +
                "aliasSources={$rawAliasSummary}, keysCount=${snapshot.size}, keys=$keySummary"
        }
    }

    private fun snapshotUrlTestKeys(limit: Int = 20): List<String> {
        return urlTestResults.keys
            .sorted()
            .take(limit)
    }

    private fun buildDelayAliasSourceMap(): Map<String, String?> {
        return linkedMapOf(
            "groupSelectedOutbounds[PROXY]" to groupSelectedOutbounds["PROXY"],
            "realTimeNodeName" to realTimeNodeName,
            "activeConnectionNode" to activeConnectionNode,
            "activeConnectionLabel" to activeConnectionLabel,
            "vpnStateStore.activeLabel" to VpnStateStore.getActiveLabel()
        )
    }

    private fun buildDelayAliasTags(
        queryTag: String,
        aliasSources: Map<String, String?> = buildDelayAliasSourceMap()
    ): List<String> {
        return buildList {
            aliasSources.values.forEach { add(it) }
        }
            .map { it?.trim().orEmpty() }
            .filter { it.isNotBlank() && !it.equals(queryTag, ignoreCase = true) }
            .distinct()
    }

    private fun createClientHandler(): CommandClientHandler = object : CommandClientHandler {
        override fun connected() {}

        override fun disconnected(message: String?) {
            Log.w(TAG, "CommandClient disconnected: $message")
        }

        override fun clearLogs() {
            runCatching { LogRepository.getInstance().clearLogs() }
        }

        override fun setDefaultLogLevel(level: Int) {}

        override fun writeLogs(messageList: LogIterator?) {
            if (messageList == null) return
            val repo = LogRepository.getInstance()
            if (!repo.isEnabled()) return
            runCatching {
                while (messageList.hasNext()) {
                    val msg = messageList.next()?.message
                    if (!msg.isNullOrBlank()) {
                        repo.addLog(msg)
                    }
                }
            }
        }

        @Suppress("LongMethod")
        override fun writeStatus(message: StatusMessage?) {
            if (message == null) return
            try {
                val currentUp = message.uplinkTotal
                val currentDown = message.downlinkTotal
                val currentTime = System.currentTimeMillis()

                if (lastSpeedUpdateTime == 0L || currentTime < lastSpeedUpdateTime) {
                    lastSpeedUpdateTime = currentTime
                    lastUplinkTotal = currentUp
                    lastDownlinkTotal = currentDown
                    return
                }

                if (currentUp < lastUplinkTotal || currentDown < lastDownlinkTotal) {
                    lastUplinkTotal = currentUp
                    lastDownlinkTotal = currentDown
                    lastSpeedUpdateTime = currentTime
                    return
                }

                val diffUp = currentUp - lastUplinkTotal
                val diffDown = currentDown - lastDownlinkTotal

                if (diffUp > 0 || diffDown > 0) {
                    val trafficRepo = TrafficRepository.getInstance(context)
                    val configRepo = ConfigRepository.getInstance(context)

                    val perOutboundTraffic = try {
                        BoxWrapperManager.getTrafficByOutbound()
                            .filterKeys { tag ->
                                !tag.equals("direct", ignoreCase = true) &&
                                    !tag.equals("block", ignoreCase = true)
                            }
                    } catch (e: Exception) {
                        Log.w(TAG, "getTrafficByOutbound failed, fallback to activeNode", e)
                        emptyMap()
                    }

                    if (perOutboundTraffic.isNotEmpty()) {
                        var totalOutboundUp = 0L
                        var totalOutboundDown = 0L
                        perOutboundTraffic.forEach { (_, traffic) ->
                            totalOutboundUp += traffic.first
                            totalOutboundDown += traffic.second
                        }

                        if (totalOutboundUp > 0 || totalOutboundDown > 0) {
                            perOutboundTraffic.forEach { (nodeTag, traffic) ->
                                val (outboundUp, outboundDown) = traffic
                                val allocUp = if (totalOutboundUp > 0) {
                                    (diffUp * outboundUp / totalOutboundUp)
                                } else 0L
                                val allocDown = if (totalOutboundDown > 0) {
                                    (diffDown * outboundDown / totalOutboundDown)
                                } else 0L

                                if (allocUp > 0 || allocDown > 0) {
                                    val node = configRepo.getNodeByName(nodeTag)
                                    if (node != null) {
                                        trafficRepo.addTraffic(node.id, allocUp, allocDown, node.name)
                                    } else {
                                        trafficRepo.addTraffic(nodeTag, allocUp, allocDown, nodeTag)
                                    }
                                }
                            }
                        }
                    } else {
                        val activeNodeId = configRepo.activeNodeId.value
                        if (activeNodeId != null) {
                            val nodeName = configRepo.getNodeById(activeNodeId)?.name
                            trafficRepo.addTraffic(activeNodeId, diffUp, diffDown, nodeName)
                        }
                    }
                }

                lastUplinkTotal = currentUp
                lastDownlinkTotal = currentDown
                lastSpeedUpdateTime = currentTime
            } catch (e: Exception) {
                Log.e(TAG, "writeStatus callback error", e)
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
        val configRepo = ConfigRepository.getInstance(context)
        var changed = false
        val pendingGroup = pendingUrlTestGroupTag
        val testResults = mutableMapOf<String, Int>()

        Log.d(TAG, "writeGroups called, pendingGroup=$pendingGroup")

        while (groups.hasNext()) {
            val group = groups.next()
            val groupChanged = processGroup(group, pendingGroup, testResults, configRepo)
            if (groupChanged) changed = true
        }

        notifyUrlTestCompletion(pendingGroup, testResults)
        if (changed) {
            callbacks?.requestNotificationUpdate(false)
        }
    }

    private fun processGroup(
        group: OutboundGroup,
        pendingGroup: String?,
        testResults: MutableMap<String, Int>,
        configRepo: ConfigRepository
    ): Boolean {
        val tag = group.tag
        val selected = group.selected
        var changed = false

        Log.d(TAG, "Processing group: $tag, selected=$selected")

        if (!tag.isNullOrBlank() && !selected.isNullOrBlank()) {
            val prev = groupSelectedOutbounds.put(tag, selected)
            if (prev != selected) changed = true
        }

        collectGroupTestResults(group, tag, pendingGroup, testResults)
        changed = updateProxyGroupSelection(tag, selected, configRepo) || changed

        return changed
    }

    private fun collectGroupTestResults(
        group: OutboundGroup,
        tag: String?,
        pendingGroup: String?,
        testResults: MutableMap<String, Int>
    ) {
        val items = group.items ?: return
        var itemCount = 0
        var delayCount = 0

        while (items.hasNext()) {
            val item = items.next()
            val itemTag = item?.tag
            val delay = item?.urlTestDelay ?: 0
            itemCount++
            if (!itemTag.isNullOrBlank() && delay > 0) {
                delayCount++
                // Always persist delay results for getCachedUrlTestDelay()
                urlTestResults[itemTag] = delay
                if (pendingGroup != null && tag.equals(pendingGroup, ignoreCase = true)) {
                    testResults[itemTag] = delay
                }
            }
        }
        Log.d(TAG, "Group $tag: $itemCount items, $delayCount with delay")
    }

    private fun updateProxyGroupSelection(
        tag: String?,
        selected: String?,
        configRepo: ConfigRepository
    ): Boolean {
        if (!tag.equals("PROXY", ignoreCase = true)) return false
        if (selected.isNullOrBlank() || selected == realTimeNodeName) return false

        realTimeNodeName = selected
        VpnStateStore.setActiveLabel(selected)
        Log.i(TAG, "Real-time node update: $selected")
        serviceScope.launch {
            configRepo.syncActiveNodeFromProxySelection(selected)
        }
        return true
    }

    private fun notifyUrlTestCompletion(pendingGroup: String?, testResults: Map<String, Int>) {
        if (pendingGroup == null) return
        Log.i(TAG, "URL test results for $pendingGroup: ${testResults.size} items")
        if (testResults.isNotEmpty()) {
            urlTestCompletionCallback?.invoke(testResults)
        }
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
        urlTestResults.clear()
        pendingUrlTestGroupTag = null
        urlTestCompletionCallback = null
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
