package com.kunk.singbox.viewmodel

import com.kunk.singbox.R
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.NodeFilter
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.PingResultCode
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.utils.parser.NodeLinkParser
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

    private val _switchingNodeId = MutableStateFlow<String?>(null)
    val switchingNodeId: StateFlow<String?> = _switchingNodeId.asStateFlow()

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

    val rawNodes: StateFlow<List<NodeUi>> = configRepository.nodes

    val nodes: StateFlow<List<NodeUi>> = combine(
        configRepository.nodes,
        displaySettings.sortType,
        displaySettings.nodeFilter,
        displaySettings.customOrder
    ) { nodes: List<NodeUi>, sortType: NodeSortType, filter: NodeFilter, customOrder: List<String> ->
        buildDashboardNodes(nodes, filter, sortType, customOrder)
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
        buildDashboardNodes(nodes, filter, sortType, emptyList())
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allNodes: StateFlow<List<NodeUi>> = configRepository.allNodes
    val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles
    val activeProfileId: StateFlow<String?> = configRepository.activeProfileId
    val activeNodeId: StateFlow<String?> = configRepository.activeNodeId
    val isAutoSelectionEnabled: StateFlow<Boolean> = combine(
        activeProfileId,
        configRepository.profileAutoSelections
    ) { profileId, selections ->
        profileId != null && selections[profileId] == true
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = configRepository.isProfileAutoSelectionEnabled(activeProfileId.value)
    )

    private val _testProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val testProgress: StateFlow<Pair<Int, Int>?> = _testProgress.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private fun emitToast(message: String) {
        _toastEvents.tryEmit(message)
    }

    fun setActiveNode(nodeId: String) {
        if (_switchingNodeId.value != null) return
        _switchingNodeId.value = nodeId
        viewModelScope.launch {
            try {
                val node = configRepository.getNodeById(nodeId)
                val result = configRepository.setActiveNodeWithResult(nodeId)

                // VPN 运行时才显示切换结果
                val isVpnRunning = VpnStateStore.getActive()
                if (isVpnRunning) {
                    val nodeName = node?.displayName
                        ?: getApplication<Application>().getString(R.string.nodes_unknown_node)
                    val msg = when (result) {
                        is ConfigRepository.NodeSwitchResult.Success,
                        is ConfigRepository.NodeSwitchResult.NotRunning ->
                            getApplication<Application>().getString(R.string.profiles_updated) + ": $nodeName"
                        is ConfigRepository.NodeSwitchResult.Failed -> result.reason
                    }
                    emitToast(msg)
                }
            } finally {
                _switchingNodeId.value = null
            }
        }
    }

    fun enableAutoSelection() {
        val profileId = activeProfileId.value ?: return
        viewModelScope.launch {
            val result = configRepository.enableAutoSelectionWithResult(profileId)
            val messageRes = if (result is ConfigRepository.NodeSwitchResult.Failed) {
                R.string.nodes_auto_selection_failed
            } else {
                R.string.nodes_auto_selection_enabled
            }
            emitToast(getApplication<Application>().getString(messageRes))
        }
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
                    emitToast(msg)
                }
            } finally {
                _testingNodeIds.update { it - nodeId }
            }
        }
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
        newProfileName: String? = null,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val trimmedContent = content.trim()

            if (!NodeLinkParser.isSupportedLink(trimmedContent)) {
                val msg = getApplication<Application>().getString(R.string.nodes_unsupported_format)
                emitToast(msg)
                onResult(false)
                return@launch
            }

            val result = configRepository.addSingleNode(
                link = trimmedContent,
                targetProfileId = targetProfileId,
                newProfileName = newProfileName
            )
            result.onSuccess { node ->
                val msg = getApplication<Application>().getString(R.string.common_add) + ": ${node.displayName}"
                emitToast(msg)
                onResult(true)
            }.onFailure { e ->
                val msg = e.message ?: getApplication<Application>().getString(R.string.nodes_add_failed)
                emitToast(msg)
                onResult(false)
            }
        }
    }
}
