package com.kunk.singbox.service.manager

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val FORCE_NOTIFICATION_AFTER_EXPLICIT_HOT_SWITCH: Boolean = true

internal fun resolveExplicitHotSwitchDisplayName(
    node: NodeUi?,
    targetNodeName: String? = null
): String? {
    return node?.name?.takeIf { it.isNotBlank() }
        ?: targetNodeName?.takeIf { it.isNotBlank() }
}

class NodeSwitchManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "NodeSwitchManager"
        private const val SWITCH_DEBOUNCE_MS = 800L
    }

    @Volatile
    private var lastSwitchTimeMs: Long = 0

    @Volatile
    private var isSwitching: Boolean = false

    interface Callbacks {
        val isRunning: Boolean
        suspend fun hotSwitchNode(nodeTag: String): Boolean
        fun getConfigPath(): String
        fun setRealTimeNodeName(name: String?)
        fun requestNotificationUpdate(force: Boolean)
        fun notifyRemoteStateUpdate(force: Boolean)
        fun startServiceIntent(intent: Intent)
    }

    private var callbacks: Callbacks? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun performHotSwitch(
        nodeId: String,
        outboundTag: String?,
        targetNodeName: String? = null,
        serviceClass: Class<*>,
        actionStart: String,
        extraConfigPath: String
    ) {
        serviceScope.launch {
            val configRepository = ConfigRepository.getInstance(context)
            val node = configRepository.getNodeById(nodeId)

            val nodeTag = outboundTag ?: node?.name

            if (nodeTag == null) {
                Log.w(TAG, "Hot switch failed: node not found $nodeId and no outboundTag provided")
                return@launch
            }

            val displayName = resolveExplicitHotSwitchDisplayName(
                node = node,
                targetNodeName = targetNodeName
            )
            if (displayName != null) {
                VpnStateStore.setActiveLabel(displayName)
            }

            val success = callbacks?.hotSwitchNode(nodeTag) == true

            if (success) {
                Log.i(TAG, "Hot switch successful for $nodeTag")
                if (displayName != null) {
                    callbacks?.setRealTimeNodeName(displayName)
                    runCatching { configRepository.syncActiveNodeFromProxySelection(displayName) }
                }
                callbacks?.requestNotificationUpdate(force = FORCE_NOTIFICATION_AFTER_EXPLICIT_HOT_SWITCH)
                callbacks?.notifyRemoteStateUpdate(force = true)
            } else {
                Log.w(TAG, "Hot switch failed for $nodeTag, falling back to restart")
                callbacks?.setRealTimeNodeName(null)
                val configPath = callbacks?.getConfigPath() ?: return@launch
                val restartIntent = Intent(context, serviceClass).apply {
                    action = actionStart
                    putExtra(extraConfigPath, configPath)
                    displayName?.let { putExtra("pending_node_name", it) }
                }
                callbacks?.startServiceIntent(restartIntent)
            }
        }
    }

    fun switchNextNode(
        serviceClass: Class<*>,
        actionStart: String,
        extraConfigPath: String
    ) {
        if (callbacks?.isRunning != true) {
            Log.w(TAG, "switchNextNode: VPN not running, skip")
            return
        }

        val now = System.currentTimeMillis()
        if (isSwitching) {
            Log.d(TAG, "switchNextNode: already switching, ignored")
            return
        }
        if (now - lastSwitchTimeMs < SWITCH_DEBOUNCE_MS) {
            Log.d(TAG, "switchNextNode: debounce, ignored (${now - lastSwitchTimeMs}ms < ${SWITCH_DEBOUNCE_MS}ms)")
            return
        }

        val configRepository = ConfigRepository.getInstance(context)
        val nodes = configRepository.nodes.value
        if (nodes.isEmpty()) {
            Log.w(TAG, "switchNextNode: no nodes available")
            return
        }

        val activeNodeId = configRepository.activeNodeId.value
        val currentIndex = nodes.indexOfFirst { it.id == activeNodeId }
        val nextIndex = (currentIndex + 1) % nodes.size
        val nextNode = nodes[nextIndex]

        Log.i(TAG, "switchNextNode: switching from ${nodes.getOrNull(currentIndex)?.name} to ${nextNode.name}")

        isSwitching = true
        lastSwitchTimeMs = now

        serviceScope.launch {
            try {
                val success = callbacks?.hotSwitchNode(nextNode.name) == true
                if (success) {

                    VpnStateStore.setActiveLabel(nextNode.name)
                    callbacks?.setRealTimeNodeName(nextNode.name)
                    callbacks?.requestNotificationUpdate(force = true)
                    callbacks?.notifyRemoteStateUpdate(force = true)

                    runCatching {
                        configRepository.setActiveNodeIdOnly(nextNode.id)
                        configRepository.syncActiveNodeFromProxySelection(nextNode.name)
                    }
                    Log.i(TAG, "switchNextNode: hot switch successful")
                } else {
                    Log.w(TAG, "switchNextNode: hot switch failed, falling back to restart")
                    VpnStateStore.setActiveLabel(nextNode.name)
                    callbacks?.setRealTimeNodeName(null)
                    val configPath = callbacks?.getConfigPath() ?: return@launch
                    val restartIntent = Intent(context, serviceClass).apply {
                        action = actionStart
                        putExtra(extraConfigPath, configPath)
                        putExtra("pending_node_name", nextNode.name)
                    }
                    callbacks?.startServiceIntent(restartIntent)
                }
            } finally {
                isSwitching = false
            }
        }
    }

    fun cleanup() {
        callbacks = null
    }
}
