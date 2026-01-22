package com.kunk.singbox.service.manager

import android.content.Context
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.TrafficRepository
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Command Server/Client 管理器
 * 负责与 libbox 的命令交互，包括：
 * - 日志收集
 * - 状态监控
 * - 连接追踪
 * - 节点组管理
 */
class CommandManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CommandManager"
    }

// Command Server/Client
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var commandClientLogs: CommandClient? = null
    private var commandClientConnections: CommandClient? = null

    private var cachedBoxService: BoxService? = null
    @Volatile
    private var isNonEssentialSuspended: Boolean = false

    // 状态
    private val groupSelectedOutbounds = ConcurrentHashMap<String, String>()
    @Volatile var realTimeNodeName: String? = null
        private set
    @Volatile var activeConnectionNode: String? = null
        private set
    @Volatile var activeConnectionLabel: String? = null
        private set
    var recentConnectionIds: List<String> = emptyList()
        private set

    // 流量统计
    private var lastUplinkTotal: Long = 0
    private var lastDownlinkTotal: Long = 0
    private var lastSpeedUpdateTime: Long = 0L
    private var lastConnectionsLabelLogged: String? = null

    /**
     * 回调接口
     */
    interface Callbacks {
        fun requestNotificationUpdate(force: Boolean)
        fun resolveEgressNodeName(tagOrSelector: String?): String?
    }

    private var callbacks: Callbacks? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     * 启动 Command Server 和 Client
     */
    fun start(boxService: BoxService): Result<Unit> = runCatching {
        cachedBoxService = boxService
        val serverHandler = object : CommandServerHandler {
            override fun serviceReload() {}
            override fun postServiceClose() {}
            override fun getSystemProxyStatus(): SystemProxyStatus? = null
            override fun setSystemProxyEnabled(isEnabled: Boolean) {}
        }

        val clientHandler = createClientHandler()

        // 1. 启动 CommandServer
        commandServer = Libbox.newCommandServer(serverHandler, 300)
        commandServer?.setService(boxService)
        commandServer?.start()
        Log.i(TAG, "CommandServer started")

        // 2. 启动 CommandClient (Groups + Status)
        val options = CommandClientOptions()
        options.command = Libbox.CommandGroup or Libbox.CommandStatus
        options.statusInterval = 3000L * 1000L * 1000L // 3s (nanoseconds)
        commandClient = Libbox.newCommandClient(clientHandler, options)
        commandClient?.connect()
        Log.i(TAG, "CommandClient connected")

        // 3. 启动 CommandClient (Logs)
        val optionsLog = CommandClientOptions()
        optionsLog.command = Libbox.CommandLog
        optionsLog.statusInterval = 1500L * 1000L * 1000L
        commandClientLogs = Libbox.newCommandClient(clientHandler, optionsLog)
        commandClientLogs?.connect()
        Log.i(TAG, "CommandClient (Logs) connected")

        // 4. 启动 CommandClient (Connections)
        val optionsConn = CommandClientOptions()
        optionsConn.command = Libbox.CommandConnections
        optionsConn.statusInterval = 5000L * 1000L * 1000L
        commandClientConnections = Libbox.newCommandClient(clientHandler, optionsConn)
        commandClientConnections?.connect()
        Log.i(TAG, "CommandClient (Connections) connected")

        // 验证回调
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
     * 停止所有 Command Server/Client
     */
    fun stop(): Result<Unit> = runCatching {
        commandClient?.disconnect()
        commandClient = null
        commandClientLogs?.disconnect()
        commandClientLogs = null
        commandClientConnections?.disconnect()
        commandClientConnections = null
        commandServer?.close()
        commandServer = null
        Log.i(TAG, "Command Server/Client stopped")
    }

    /**
     * 获取 CommandClient (用于连接管理)
     */
    fun getCommandClient(): CommandClient? = commandClient
    fun getConnectionsClient(): CommandClient? = commandClientConnections

    /**
     * 获取指定 group 的选中 outbound
     */
    fun getSelectedOutbound(groupTag: String): String? = groupSelectedOutbounds[groupTag]

    /**
     * 获取所有 group 选中状态的数量
     */
    fun getGroupsCount(): Int = groupSelectedOutbounds.size

    /**
     * 关闭所有连接
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
     * 关闭指定连接
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

    private fun createClientHandler(): CommandClientHandler = object : CommandClientHandler {
        override fun connected() {}

        override fun disconnected(message: String?) {
            Log.w(TAG, "CommandClient disconnected: $message")
        }

        override fun clearLogs() {
            runCatching { LogRepository.getInstance().clearLogs() }
        }

        override fun writeLogs(messageList: StringIterator?) {
            if (messageList == null) return
            val repo = LogRepository.getInstance()
            runCatching {
                while (messageList.hasNext()) {
                    val msg = messageList.next()
                    if (!msg.isNullOrBlank()) {
                        repo.addLog(msg)
                    }
                }
            }
        }

        override fun writeStatus(message: StatusMessage?) {
            message ?: return
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
                val repo = ConfigRepository.getInstance(context)
                val activeNodeId = repo.activeNodeId.value
                if (activeNodeId != null) {
                    TrafficRepository.getInstance(context).addTraffic(activeNodeId, diffUp, diffDown)
                }
            }

            lastUplinkTotal = currentUp
            lastDownlinkTotal = currentDown
            lastSpeedUpdateTime = currentTime
        }

        override fun writeGroups(groups: OutboundGroupIterator?) {
            if (groups == null) return
            val configRepo = ConfigRepository.getInstance(context)

            try {
                var changed = false
                while (groups.hasNext()) {
                    val group = groups.next()
                    val tag = group.tag
                    val selected = group.selected

                    if (!tag.isNullOrBlank() && !selected.isNullOrBlank()) {
                        val prev = groupSelectedOutbounds.put(tag, selected)
                        if (prev != selected) changed = true
                    }

                    if (tag.equals("PROXY", ignoreCase = true)) {
                        if (!selected.isNullOrBlank() && selected != realTimeNodeName) {
                            realTimeNodeName = selected
                            // 2025-fix: 持久化 activeLabel 到 VpnStateStore，确保跨进程/重启后通知栏显示正确
                            VpnStateStore.setActiveLabel(selected)
                            Log.i(TAG, "Real-time node update: $selected")
                            serviceScope.launch {
                                configRepo.syncActiveNodeFromProxySelection(selected)
                            }
                            changed = true
                        }
                    }
                }

                if (changed) {
                    callbacks?.requestNotificationUpdate(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing groups update", e)
            }
        }

        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
        override fun updateClashMode(newMode: String?) {}

        override fun writeConnections(message: Connections?) {
            message ?: return
            try {
                processConnections(message)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing connections update", e)
            }
        }
    }

    private fun processConnections(message: Connections) {
        val iterator = message.iterator()
        var newestConnection: Connection? = null
        val ids = ArrayList<String>(64)
        val egressCounts = LinkedHashMap<String, Int>()
        val configRepo = ConfigRepository.getInstance(context)

        while (iterator.hasNext()) {
            val conn = iterator.next()
            if (conn.closedAt > 0) continue
            if (conn.rule == "dns-out") continue

            if (newestConnection == null || conn.createdAt > newestConnection.createdAt) {
                newestConnection = conn
            }

            val id = conn.id
            if (!id.isNullOrBlank()) {
                ids.add(id)
            }

            // 解析 egress
            var resolved = resolveConnectionEgress(conn, configRepo)
            if (!resolved.isNullOrBlank()) {
                egressCounts[resolved] = (egressCounts[resolved] ?: 0) + 1
            }
        }

        recentConnectionIds = ids

        // 生成标签
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
                Log.i(TAG, "Connections label updated: ${newLabel ?: "(null)"}")
            }
        }

        // 更新活跃连接节点
        var newNode: String? = null
        if (newestConnection != null) {
            val chainIter = newestConnection.chain()
            while (chainIter.hasNext()) {
                val tag = chainIter.next()
                if (!tag.isNullOrBlank() && tag != "dns-out") newNode = tag
            }
        }

        if (newNode != activeConnectionNode || labelChanged) {
            activeConnectionNode = newNode
            callbacks?.requestNotificationUpdate(false)
        }
    }

    private fun resolveConnectionEgress(conn: Connection, repo: ConfigRepository): String? {
        var candidateTag: String? = conn.rule
        if (candidateTag.isNullOrBlank() || candidateTag == "dns-out") {
            candidateTag = null
        }

        if (!candidateTag.isNullOrBlank()) {
            val resolved = callbacks?.resolveEgressNodeName(candidateTag)
                ?: repo.resolveNodeNameFromOutboundTag(candidateTag)
                ?: candidateTag
            if (!resolved.isNullOrBlank()) return resolved
        }

        // 回退到 chain
        var lastTag: String? = null
        runCatching {
            val chainIter = conn.chain()
            while (chainIter.hasNext()) {
                val tag = chainIter.next()
                if (!tag.isNullOrBlank() && tag != "dns-out") {
                    lastTag = tag
                }
            }
        }

        return callbacks?.resolveEgressNodeName(lastTag)
            ?: repo.resolveNodeNameFromOutboundTag(lastTag)
            ?: lastTag
    }

    fun cleanup() {
        stop()
        groupSelectedOutbounds.clear()
        realTimeNodeName = null
        activeConnectionNode = null
        activeConnectionLabel = null
        recentConnectionIds = emptyList()
        callbacks = null
        cachedBoxService = null
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

        val boxService = cachedBoxService
        if (boxService == null) {
            Log.w(TAG, "Cannot resume: no cached BoxService")
            return
        }

        val clientHandler = createClientHandler()

        try {
            val optionsLog = CommandClientOptions()
            optionsLog.command = Libbox.CommandLog
            optionsLog.statusInterval = 1500L * 1000L * 1000L
            commandClientLogs = Libbox.newCommandClient(clientHandler, optionsLog)
            commandClientLogs?.connect()
            Log.i(TAG, "CommandClient (Logs) resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume Logs client", e)
        }

        try {
            val optionsConn = CommandClientOptions()
            optionsConn.command = Libbox.CommandConnections
            optionsConn.statusInterval = 5000L * 1000L * 1000L
            commandClientConnections = Libbox.newCommandClient(clientHandler, optionsConn)
            commandClientConnections?.connect()
            Log.i(TAG, "CommandClient (Connections) resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume Connections client", e)
        }
    }

    val isNonEssentialActive: Boolean
        get() = !isNonEssentialSuspended && (commandClientLogs != null || commandClientConnections != null)
}
