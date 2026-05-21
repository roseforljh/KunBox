package com.kunk.singbox.repository

import com.kunk.singbox.model.NodeUi
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 *
 *
 */
@Suppress("TooManyFunctions")
class NodeStateManager {

    private val _nodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val nodes: StateFlow<List<NodeUi>> = _nodes.asStateFlow()

    private val _allNodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val allNodes: StateFlow<List<NodeUi>> = _allNodes.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _activeNodeId = MutableStateFlow<String?>(null)
    val activeNodeId: StateFlow<String?> = _activeNodeId.asStateFlow()

    val profileNodes = ConcurrentHashMap<String, List<NodeUi>>()
    val savedNodeLatencies = ConcurrentHashMap<String, Long>()

    private val allNodesUiActiveCount = AtomicInteger(0)
    @Volatile
    var allNodesLoadedForUi: Boolean = false
        private set

    private val profileLastSelectedNode = ConcurrentHashMap<String, String>()
    private val profileNodeMemoryMmkv: MMKV by lazy {
        MMKV.mmkvWithID("profile_node_memory", MMKV.SINGLE_PROCESS_MODE)
    }

    fun loadProfileNodeMemory() {
        profileNodeMemoryMmkv.allKeys()?.forEach { profileId ->
            val nodeId = profileNodeMemoryMmkv.decodeString(profileId, null)
            if (!nodeId.isNullOrBlank()) {
                profileLastSelectedNode[profileId] = nodeId
            }
        }
    }

    fun saveProfileNodeMemory(profileId: String, nodeId: String) {
        profileLastSelectedNode[profileId] = nodeId
        profileNodeMemoryMmkv.encode(profileId, nodeId)
    }

    fun getProfileLastSelectedNode(profileId: String): String? {
        return profileLastSelectedNode[profileId]
    }

    fun setNodes(nodes: List<NodeUi>) {
        _nodes.value = nodes
    }

    fun updateNodes(transform: (List<NodeUi>) -> List<NodeUi>) {
        _nodes.update(transform)
    }

    fun setAllNodes(nodes: List<NodeUi>) {
        _allNodes.value = nodes
    }

    fun updateAllNodes(transform: (List<NodeUi>) -> List<NodeUi>) {
        _allNodes.update(transform)
    }

    fun setActiveProfileId(profileId: String?) {
        _activeProfileId.value = profileId
    }

    fun setActiveNodeId(nodeId: String?) {
        _activeNodeId.value = nodeId
    }

    fun getActiveProfileIdValue(): String? = _activeProfileId.value

    fun getActiveNodeIdValue(): String? = _activeNodeId.value

    fun updateAllNodesAndGroups() {
        if (allNodesUiActiveCount.get() <= 0) {
            _allNodes.value = emptyList()
            return
        }
        _allNodes.value = profileNodes.values.flatten()
    }

    fun incrementAllNodesUiActive(): Int {
        return allNodesUiActiveCount.incrementAndGet()
    }

    fun decrementAllNodesUiActive(): Int {
        while (true) {
            val cur = allNodesUiActiveCount.get()
            if (cur <= 0) return 0
            if (allNodesUiActiveCount.compareAndSet(cur, cur - 1)) {
                return cur - 1
            }
        }
    }

    fun getAllNodesUiActiveCount(): Int = allNodesUiActiveCount.get()

    fun setAllNodesLoadedForUi(loaded: Boolean) {
        allNodesLoadedForUi = loaded
    }

    @Suppress("CognitiveComplexMethod")
    fun updateNodeLatency(nodeId: String, latencyMs: Long) {
        val latencyValue = if (latencyMs > 0) latencyMs else -1L
        savedNodeLatencies[nodeId] = latencyValue

        _nodes.update { list ->
            list.map { if (it.id == nodeId) it.copy(latencyMs = latencyValue) else it }
        }

        val node = _nodes.value.find { it.id == nodeId }
        if (node != null) {
            profileNodes[node.sourceProfileId] = profileNodes[node.sourceProfileId]?.map {
                if (it.id == nodeId) it.copy(latencyMs = latencyValue) else it
            } ?: emptyList()
        }

        _allNodes.update { list ->
            list.map { if (it.id == nodeId) it.copy(latencyMs = latencyValue) else it }
        }
    }

    fun clearAllNodesLatency() {
        savedNodeLatencies.clear()

        _nodes.update { list ->
            list.map { it.copy(latencyMs = null) }
        }

        profileNodes.keys.forEach { profileId ->
            profileNodes[profileId] = profileNodes[profileId]?.map {
                it.copy(latencyMs = null)
            } ?: emptyList()
        }

        _allNodes.update { list ->
            list.map { it.copy(latencyMs = null) }
        }
    }

    fun clearProfileNodesExcept(keepProfileId: String?) {
        val keep = keepProfileId?.let { profileNodes[it] }
        profileNodes.clear()
        if (keepProfileId != null && keep != null) {
            profileNodes[keepProfileId] = keep
        }
        _allNodes.value = emptyList()
    }

    fun getNodeById(nodeId: String): NodeUi? {
        return _nodes.value.find { it.id == nodeId }
            ?: _allNodes.value.find { it.id == nodeId }
            ?: profileNodes.values.flatten().find { it.id == nodeId }
    }

    fun restoreLatencies(latencies: Map<String, Long>) {
        savedNodeLatencies.clear()
        savedNodeLatencies.putAll(latencies)
    }

    fun collectLatencies(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        profileNodes.values.flatten().forEach { node ->
            node.latencyMs?.let { result[node.id] = it }
        }
        return result
    }
}
