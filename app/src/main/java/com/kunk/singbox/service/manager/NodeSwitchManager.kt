package com.kunk.singbox.service.manager

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.utils.perf.PerfTracer
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

    @Suppress("CognitiveComplexMethod")
    fun performHotSwitch(
        nodeId: String,
        outboundTag: String?,
        targetNodeName: String? = null
    ) {
        serviceScope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            val configRepository = ConfigRepository.getInstance(context)
            val node = configRepository.getNodeById(nodeId)

            val nodeTag = outboundTag ?: node?.name

            if (nodeTag == null) {
                Log.w(TAG, "Hot switch failed: node not found $nodeId and no outboundTag provided")
                recordHotSwitchEvent(startedAtMs, nodeId, null, "invalid_target")
                recordSwitchMetric(startedAtMs, "invalid_target")
                return@launch
            }
            if (NodeProtectionStore.effectiveSelectedNodeId(VpnStateStore.getSelectedNodeId()) != nodeId) {
                Log.w(TAG, "Hot switch rejected because the manual selection transaction is missing: $nodeId")
                recordHotSwitchEvent(startedAtMs, nodeId, nodeTag, "unauthorized_target")
                recordSwitchMetric(startedAtMs, "unauthorized_target")
                return@launch
            }
            val targetRef = NodeProtectionStore.runtimeMappings()[nodeTag]
            if (targetRef?.nodeId != nodeId ||
                !NodeProtectionStore.isRuntimeUseAuthorized(nodeId, VpnStateStore.getSelectedNodeId())
            ) {
                Log.w(TAG, "Hot switch rejected because runtime outbound ownership is invalid: $nodeTag")
                recordHotSwitchEvent(startedAtMs, nodeId, nodeTag, "invalid_runtime_mapping")
                recordSwitchMetric(startedAtMs, "invalid_runtime_mapping")
                return@launch
            }

            LogRepository.getInstance().addAlwaysLog(
                "INFO [HOT_SWITCH] mode=vpn phase=request node_id=$nodeId outbound=$nodeTag"
            )

            val displayName = resolveExplicitHotSwitchDisplayName(
                node = node,
                targetNodeName = targetNodeName
            )

            val success = callbacks?.hotSwitchNode(nodeTag) == true

            if (success) {
                Log.i(TAG, "Hot switch successful for $nodeTag")
                if (displayName != null) {
                    VpnStateStore.setActiveLabel(displayName)
                    callbacks?.setRealTimeNodeName(displayName)
                }
                callbacks?.requestNotificationUpdate(force = FORCE_NOTIFICATION_AFTER_EXPLICIT_HOT_SWITCH)
                callbacks?.notifyRemoteStateUpdate(force = true)
                recordHotSwitchEvent(startedAtMs, nodeId, nodeTag, "success")
                recordSwitchMetric(startedAtMs, "success")
            } else {
                Log.w(TAG, "Hot switch failed for $nodeTag, keeping current runtime")
                callbacks?.requestNotificationUpdate(force = true)
                callbacks?.notifyRemoteStateUpdate(force = true)
                recordHotSwitchEvent(startedAtMs, nodeId, nodeTag, "failed")
                recordSwitchMetric(startedAtMs, "failed")
            }
        }
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
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
        val nodes = configRepository.nodes.value.filter {
            it.autoSelectionEligible && !it.meteredProtected
        }
        if (nodes.isEmpty()) {
            Log.w(TAG, "switchNextNode: no nodes available")
            return
        }

        val activeNodeId = configRepository.activeNodeId.value
        val currentIndex = nodes.indexOfFirst { it.id == activeNodeId }
        val nextIndex = (currentIndex + 1) % nodes.size
        val nextNode = nodes[nextIndex]
        val requiresProtectedConfigPurge = activeNodeId?.let(configRepository::isNodeMeteredProtected) == true ||
            NodeProtectionStore.manuallyAuthorizedNodeId() != null

        Log.i(TAG, "switchNextNode: switching from ${nodes.getOrNull(currentIndex)?.name} to ${nextNode.name}")

        isSwitching = true
        lastSwitchTimeMs = now

        serviceScope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            var metricOutcome = "error"
            try {
                if (requiresProtectedConfigPurge) {
                    when (val result = configRepository.setActiveNodeWithResult(nextNode.id)) {
                        ConfigRepository.NodeSwitchResult.Success,
                        ConfigRepository.NodeSwitchResult.NotRunning -> {
                            metricOutcome = "protected_config_purged"
                            Log.i(TAG, "switchNextNode: protected source removed through full selection transaction")
                        }
                        is ConfigRepository.NodeSwitchResult.Failed -> {
                            metricOutcome = "protected_config_purge_failed"
                            Log.e(TAG, "switchNextNode: failed to purge protected source: ${result.reason}")
                        }
                    }
                } else if (callbacks?.hotSwitchNode(nextNode.name) == true) {

                    VpnStateStore.setActiveLabel(nextNode.name)
                    callbacks?.setRealTimeNodeName(nextNode.name)
                    callbacks?.requestNotificationUpdate(force = true)
                    callbacks?.notifyRemoteStateUpdate(force = true)

                    runCatching {
                        configRepository.setActiveNodeIdOnly(nextNode.id)
                        configRepository.syncActiveNodeFromProxySelection(nextNode.name)
                    }
                    Log.i(TAG, "switchNextNode: hot switch successful")
                    metricOutcome = "success"
                } else {
                    Log.w(TAG, "switchNextNode: hot switch failed, falling back to restart")
                    callbacks?.setRealTimeNodeName(null)
                    val configPath = callbacks?.getConfigPath() ?: return@launch
                    val restartIntent = Intent(context, serviceClass).apply {
                        action = actionStart
                        putExtra(extraConfigPath, configPath)
                        putExtra(ServiceStateHolder.EXTRA_CLEAN_CACHE, true)
                        putExtra("pending_node_name", nextNode.name)
                    }
                    callbacks?.startServiceIntent(restartIntent)
                    metricOutcome = "restart_fallback"
                }
            } finally {
                recordSwitchMetric(startedAtMs, metricOutcome)
                isSwitching = false
            }
        }
    }

    private fun recordSwitchMetric(startedAtMs: Long, outcome: String) {
        PerfTracer.recordDuration(
            name = PerfTracer.Phases.NODE_SWITCH,
            durationMs = SystemClock.elapsedRealtime() - startedAtMs,
            outcome = outcome
        )
    }

    private fun recordHotSwitchEvent(
        startedAtMs: Long,
        nodeId: String,
        outboundTag: String?,
        outcome: String
    ) {
        LogRepository.getInstance().addAlwaysLog(
            "INFO [HOT_SWITCH] mode=vpn phase=complete outcome=$outcome " +
                "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs} node_id=$nodeId " +
                "outbound=${outboundTag.orEmpty()} actual=${SelectorManager.getSelectedOutbound().orEmpty()}"
        )
    }

    fun cleanup() {
        callbacks = null
    }
}
