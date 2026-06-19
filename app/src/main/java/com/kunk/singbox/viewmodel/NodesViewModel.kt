package com.kunk.singbox.viewmodel

import com.kunk.singbox.R
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.FilterMode
import com.kunk.singbox.model.NodeFilter
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.PingResultCode
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.viewmodel.shared.NodeDisplaySettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

@Suppress("TooManyFunctions")
class NodesViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "NodesViewModel"
    }

    private val configRepository = ConfigRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)

    private val displaySettings = NodeDisplaySettings.getInstance(application)

    private var testingJob: Job? = null

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _testingNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val testingNodeIds: StateFlow<Set<String>> = _testingNodeIds.asStateFlow()

    val sortType: StateFlow<NodeSortType> = displaySettings.sortType
    val nodeFilter: StateFlow<NodeFilter> = displaySettings.nodeFilter

    val nodeColumnCount: StateFlow<Int> = settingsRepository.settings
        .map { it.nodeColumnCount }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 1
        )

    private val _customNodeOrder = MutableStateFlow<List<String>>(emptyList())

    init {

        viewModelScope.launch {
            displaySettings.customOrder.collect { order ->
                _customNodeOrder.value = order
            }
        }
    }

    val nodes: StateFlow<List<NodeUi>> = combine(
        configRepository.nodes,
        displaySettings.sortType,
        displaySettings.nodeFilter,
        _customNodeOrder
    ) { nodes: List<NodeUi>, sortType: NodeSortType, filter: NodeFilter, customOrder: List<String> ->
        val filtered = when (filter.filterMode) {
            FilterMode.NONE -> nodes
            FilterMode.INCLUDE -> {
                val keywords = filter.effectiveIncludeKeywords
                if (keywords.isEmpty()) {
                    nodes
                } else {
                    nodes.filter { node ->
                        keywords.any { keyword ->
                            node.displayName.contains(keyword, ignoreCase = true)
                        }
                    }
                }
            }
            FilterMode.EXCLUDE -> {
                val keywords = filter.effectiveExcludeKeywords
                if (keywords.isEmpty()) {
                    nodes
                } else {
                    nodes.filter { node ->
                        keywords.none { keyword ->
                            node.displayName.contains(keyword, ignoreCase = true)
                        }
                    }
                }
            }
        }
        when (sortType) {
            NodeSortType.DEFAULT -> filtered
            NodeSortType.LATENCY -> filtered.sortedWith(compareBy<NodeUi> {
                val l = it.latencyMs

                if (l == null || l <= 0) Long.MAX_VALUE else l
            })
            NodeSortType.NAME,
            NodeSortType.REGION -> filtered.sortedBy { it.name }
            NodeSortType.CUSTOM -> {
                val orderMap = customOrder.withIndex().associate { it.value to it.index }
                filtered.sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val filteredAllNodes: StateFlow<List<NodeUi>> = combine(
        configRepository.allNodes,
        displaySettings.sortType,
        displaySettings.nodeFilter
    ) { nodes, sortType, filter ->
        val filtered = when (filter.filterMode) {
            FilterMode.NONE -> nodes
            FilterMode.INCLUDE -> {
                val keywords = filter.effectiveIncludeKeywords
                if (keywords.isEmpty()) {
                    nodes
                } else {
                    nodes.filter { node ->
                        keywords.any { keyword ->
                            node.displayName.contains(keyword, ignoreCase = true)
                        }
                    }
                }
            }
            FilterMode.EXCLUDE -> {
                val keywords = filter.effectiveExcludeKeywords
                if (keywords.isEmpty()) {
                    nodes
                } else {
                    nodes.filter { node ->
                        keywords.none { keyword ->
                            node.displayName.contains(keyword, ignoreCase = true)
                        }
                    }
                }
            }
        }
        when (sortType) {
            NodeSortType.DEFAULT -> filtered
            NodeSortType.LATENCY -> filtered.sortedWith(compareBy<NodeUi> {
                val l = it.latencyMs
                if (l == null || l <= 0) Long.MAX_VALUE else l
            })
            NodeSortType.NAME,
            NodeSortType.REGION -> filtered.sortedBy { it.name }
            NodeSortType.CUSTOM -> {

                filtered
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allNodes: StateFlow<List<NodeUi>> = configRepository.allNodes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val activeNodeId: StateFlow<String?> = configRepository.activeNodeId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _switchResult = MutableStateFlow<String?>(null)
    val switchResult: StateFlow<String?> = _switchResult.asStateFlow()

    private val _latencyMessage = MutableStateFlow<String?>(null)
    val latencyMessage: StateFlow<String?> = _latencyMessage.asStateFlow()

    private val _testProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val testProgress: StateFlow<Pair<Int, Int>?> = _testProgress.asStateFlow()

    private val _addNodeResult = MutableStateFlow<String?>(null)
    val addNodeResult: StateFlow<String?> = _addNodeResult.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private fun emitToast(message: String) {
        _toastEvents.tryEmit(message)
    }

    fun setActiveNode(nodeId: String) {

        configRepository.setActiveNodeIdOnly(nodeId)

        viewModelScope.launch {

            val node = configRepository.getNodeById(nodeId)

            val success = configRepository.setActiveNode(nodeId)

            // Only show toast when VPN is running
            val isVpnRunning = VpnStateStore.getActive()
            if (isVpnRunning) {
                val nodeName = node?.displayName ?: getApplication<Application>().getString(R.string.nodes_unknown_node)
                val msg = if (success) {
                    getApplication<Application>().getString(R.string.profiles_updated) + ": $nodeName"
                } else {
                    "Failed to switch to $nodeName"
                }
                _switchResult.value = msg
                emitToast(msg)
            }
        }
    }

    fun clearSwitchResult() {
        _switchResult.value = null
    }

    fun testLatency(nodeId: String) {
        var shouldStart = false
        _testingNodeIds.update { current ->
            if (current.contains(nodeId)) {
                current
            } else {
                shouldStart = true
                current + nodeId
            }
        }
        if (!shouldStart) return

        viewModelScope.launch {
            try {
                val node = nodes.value.find { it.id == nodeId }
                val latency = configRepository.testNodeLatency(nodeId)
                if (latency <= 0) {
                    val msg = getApplication<Application>().getString(
                        R.string.nodes_test_failed,
                        node?.displayName ?: ""
                    )
                    _latencyMessage.value = msg
                    emitToast(msg)
                }
            } finally {
                _testingNodeIds.update { it - nodeId }
            }
        }
    }

    fun clearLatencyMessage() {
        _latencyMessage.value = null
    }

    fun clearAddNodeResult() {
        _addNodeResult.value = null
    }

    fun testAllLatency() {
        if (_isTesting.value) {
            testingJob?.cancel()
            testingJob = null
            _isTesting.value = false
            _testingNodeIds.value = emptySet()
            _testProgress.value = null
            return
        }

        testingJob = viewModelScope.launch {
            _isTesting.value = true

            val originalSortType = sortType.value
            val currentOrder = nodes.value.map { it.id }
            setCustomNodeOrder(currentOrder)
            setSortType(NodeSortType.CUSTOM)

            val currentNodes = nodes.value
            val targetIds = currentNodes.map { it.id }
            val totalCount = targetIds.size
            _testingNodeIds.value = targetIds.toSet()

            val completedCount = AtomicInteger(0)
            val successCount = AtomicInteger(0)
            val timeoutCount = AtomicInteger(0)
            val ipv6OnlyCount = AtomicInteger(0)
            _testProgress.value = Pair(0, totalCount)

            try {
                configRepository.testAllNodesLatency(targetIds) { finishedNodeId, latencyMs ->
                    _testingNodeIds.update { it - finishedNodeId }
                    val completed = completedCount.incrementAndGet()
                    when {
                        latencyMs > 0 -> successCount.incrementAndGet()
                        latencyMs == PingResultCode.IPV6_ONLY -> ipv6OnlyCount.incrementAndGet()
                        else -> timeoutCount.incrementAndGet()
                    }
                    _testProgress.value = Pair(completed, totalCount)
                }
                val context = getApplication<Application>()
                val summary = buildLatencyTestSummary(
                    context = context,
                    successCount = successCount.get(),
                    timeoutCount = timeoutCount.get(),
                    ipv6OnlyCount = ipv6OnlyCount.get()
                )
                emitToast(summary)
            } catch (e: Exception) {
                Log.e(TAG, "Failed during batch latency test", e)
            } finally {
                setSortType(originalSortType)
                _isTesting.value = false
                _testingNodeIds.value = emptySet()
                _testProgress.value = null
                testingJob = null
            }
        }
    }

    private fun buildLatencyTestSummary(
        context: Application,
        successCount: Int,
        timeoutCount: Int,
        ipv6OnlyCount: Int
    ): String {
        return if (ipv6OnlyCount > 0) {
            context.getString(R.string.nodes_test_complete_stats_v6, successCount, timeoutCount, ipv6OnlyCount)
        } else {
            context.getString(R.string.nodes_test_complete_stats, successCount, timeoutCount)
        }
    }

    fun deleteNode(nodeId: String) {
        viewModelScope.launch {
            val nodeName = configRepository.getNodeById(nodeId)?.displayName ?: ""
            configRepository.deleteNode(nodeId)
            emitToast(getApplication<Application>().getString(R.string.profiles_deleted) + ": $nodeName")
        }
    }

    suspend fun exportNode(nodeId: String): String? {
        return configRepository.exportNode(nodeId)
    }

    fun setSortType(type: NodeSortType) {
        viewModelScope.launch {
            settingsRepository.setNodeSortType(type)
        }
    }

    fun setNodeColumnCount(value: Int) {
        viewModelScope.launch {
            settingsRepository.setNodeColumnCount(value)
        }
    }

    fun setNodeFilter(filter: NodeFilter) {
        viewModelScope.launch {
            settingsRepository.setNodeFilter(filter)
        }
        emitToast(getApplication<Application>().getString(R.string.nodes_filter_applied))
    }

    fun clearNodeFilter() {
        val emptyFilter = NodeFilter()
        viewModelScope.launch {
            settingsRepository.setNodeFilter(emptyFilter)
        }
        emitToast(getApplication<Application>().getString(R.string.nodes_filter_cleared))
    }

    fun clearLatency() {
        viewModelScope.launch {

            val currentOrder = nodes.value.map { it.id }
            setCustomNodeOrder(currentOrder)
            setSortType(NodeSortType.CUSTOM)

            configRepository.clearAllNodesLatency()
            emitToast(getApplication<Application>().getString(R.string.nodes_latency_cleared))
        }
    }

    private fun setCustomNodeOrder(order: List<String>) {
        _customNodeOrder.value = order
        viewModelScope.launch {
            settingsRepository.setCustomNodeOrder(order)
        }
    }

    fun setAllNodesUiActive(active: Boolean) {
        configRepository.setAllNodesUiActive(active)
    }

    fun addNode(
        content: String,
        targetProfileId: String? = null,
        newProfileName: String? = null
    ) {
        viewModelScope.launch {
            val trimmedContent = content.trim()

            val supportedPrefixes = listOf(
                "vmess://", "vless://", "ss://", "trojan://",
                "hysteria2://", "hy2://", "hysteria://",
                "tuic://", "anytls://", "naive://", "naive+https://", "wireguard://", "ssh://"
            )

            if (supportedPrefixes.none { trimmedContent.startsWith(it) }) {
                val msg = getApplication<Application>().getString(R.string.nodes_unsupported_format)
                _addNodeResult.value = msg
                emitToast(msg)
                return@launch
            }

            val result = configRepository.addSingleNode(
                link = trimmedContent,
                targetProfileId = targetProfileId,
                newProfileName = newProfileName
            )
            result.onSuccess { node ->
                val msg = getApplication<Application>().getString(R.string.common_add) + ": ${node.displayName}"
                _addNodeResult.value = msg
                emitToast(msg)
            }.onFailure { e ->
                val msg = e.message ?: getApplication<Application>().getString(R.string.nodes_add_failed)
                _addNodeResult.value = msg
                emitToast(msg)
            }
        }
    }
}
