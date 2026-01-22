package com.kunk.singbox.viewmodel

import com.kunk.singbox.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.FilterMode
import com.kunk.singbox.model.NodeFilter
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NodesViewModel(application: Application) : AndroidViewModel(application) {

    private val configRepository = ConfigRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)

    private var testingJob: Job? = null

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    // 正在测试延迟的节点 ID 集合
    private val _testingNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val testingNodeIds: StateFlow<Set<String>> = _testingNodeIds.asStateFlow()

    private val _sortType = MutableStateFlow(NodeSortType.DEFAULT)
    val sortType: StateFlow<NodeSortType> = _sortType.asStateFlow()

    private val _customNodeOrder = MutableStateFlow<List<String>>(emptyList())

    // 节点过滤状态
    private val _nodeFilter = MutableStateFlow(NodeFilter())
    val nodeFilter: StateFlow<NodeFilter> = _nodeFilter.asStateFlow()

    init {
        // 加载保存的过滤配置
        viewModelScope.launch {
            settingsRepository.getNodeFilterFlow().collect {
                _nodeFilter.value = it
            }
        }
        viewModelScope.launch {
            settingsRepository.getNodeSortType().collect { type ->
                _sortType.value = type
            }
        }
        viewModelScope.launch {
            settingsRepository.getCustomNodeOrder().collect { order ->
                _customNodeOrder.value = order
            }
        }
    }

    val nodes: StateFlow<List<NodeUi>> = combine(
        configRepository.nodes,
        _sortType,
        _nodeFilter,
        _customNodeOrder
    ) { nodes: List<NodeUi>, sortType: NodeSortType, filter: NodeFilter, customOrder: List<String> ->
        // 先过滤
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
        // 再排序
        when (sortType) {
            NodeSortType.DEFAULT -> filtered
            NodeSortType.LATENCY -> filtered.sortedWith(compareBy<NodeUi> {
                val l = it.latencyMs
                // 将未测试(null)和超时/失败(<=0)的节点排到最后
                if (l == null || l <= 0) Long.MAX_VALUE else l
            })
            NodeSortType.NAME -> filtered.sortedBy { it.name }
            NodeSortType.REGION -> filtered.sortedWith(compareBy<NodeUi> {
                getRegionWeight(it.regionFlag)
            }.thenBy { it.name })
            NodeSortType.CUSTOM -> {
                val orderMap = customOrder.withIndex().associate { it.value to it.index }
                filtered.sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private fun getRegionWeight(flag: String?): Int {
        if (flag.isNullOrBlank()) return 9999
        // Priority order: CN, HK, MO, TW, JP, KR, SG, US, Others
        return when (flag) {
            "🇨🇳" -> 0 // China
            "🇭🇰" -> 1 // Hong Kong
            "🇲🇴" -> 2 // Macau
            "🇹🇼" -> 3 // Taiwan
            "🇯🇵" -> 4 // Japan
            "🇰🇷" -> 5 // South Korea
            "🇸🇬" -> 6 // Singapore
            "🇺🇸" -> 7 // USA
            "🇻🇳" -> 8 // Vietnam
            "🇹🇭" -> 9 // Thailand
            "🇵🇭" -> 10 // Philippines
            "🇲🇾" -> 11 // Malaysia
            "🇮🇩" -> 12 // Indonesia
            "🇮🇳" -> 13 // India
            "🇷🇺" -> 14 // Russia
            "🇹🇷" -> 15 // Turkey
            "🇮🇹" -> 16 // Italy
            "🇩🇪" -> 17 // Germany
            "🇫🇷" -> 18 // France
            "🇳🇱" -> 19 // Netherlands
            "🇬🇧" -> 20 // UK
            "🇦🇺" -> 21 // Australia
            "🇨🇦" -> 22 // Canada
            "🇧🇷" -> 23 // Brazil
            "🇦🇷" -> 24 // Argentina
            else -> 1000 // Others
        }
    }

    val filteredAllNodes: StateFlow<List<NodeUi>> = combine(
        configRepository.allNodes,
        _sortType,
        _nodeFilter
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
            NodeSortType.NAME -> filtered.sortedBy { it.name }
            NodeSortType.REGION -> filtered.sortedWith(compareBy<NodeUi> {
                getRegionWeight(it.regionFlag)
            }.thenBy { it.name })
            NodeSortType.CUSTOM -> {
                // filteredAllNodes 不使用 customOrder，或者我们可以简单地回退到 DEFAULT
                // 既然 filteredAllNodes 目前主要用于后台逻辑，这里暂时使用 DEFAULT
                filtered
            }
        }
    }.stateIn(
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

    // 单节点测速反馈信息（仅在失败/超时时提示）
    private val _latencyMessage = MutableStateFlow<String?>(null)
    val latencyMessage: StateFlow<String?> = _latencyMessage.asStateFlow()

    // 批量测速进度 (已完成数 / 总数)
    private val _testProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val testProgress: StateFlow<Pair<Int, Int>?> = _testProgress.asStateFlow()

    // 添加节点结果反馈
    private val _addNodeResult = MutableStateFlow<String?>(null)
    val addNodeResult: StateFlow<String?> = _addNodeResult.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private fun emitToast(message: String) {
        _toastEvents.tryEmit(message)
    }

    fun setActiveNode(nodeId: String) {
        // 2025-fix: 先同步更新 activeNodeId，避免竞态条件
        // 场景：用户在节点页面连续快速切换节点后立即到首页启动 VPN
        // 如果不同步更新，generateConfigFile() 可能读取到旧的节点 ID
        configRepository.setActiveNodeIdOnly(nodeId)

        viewModelScope.launch {
            // 使用 configRepository 获取节点，避免因过滤导致找不到节点名称
            val node = configRepository.getNodeById(nodeId)
            // 异步处理热切换（如果 VPN 正在运行）
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
        if (_testingNodeIds.value.contains(nodeId)) return
        viewModelScope.launch {
            _testingNodeIds.value = _testingNodeIds.value + nodeId
            try {
                val node = nodes.value.find { it.id == nodeId }
                val latency = configRepository.testNodeLatency(nodeId)
                if (latency <= 0) {
                    val msg = getApplication<Application>().getString(R.string.nodes_test_failed, node?.displayName ?: "")
                    _latencyMessage.value = msg
                    emitToast(msg)
                }
            } finally {
                _testingNodeIds.value = _testingNodeIds.value - nodeId
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

            val currentOrder = nodes.value.map { it.id }
            setCustomNodeOrder(currentOrder)
            setSortType(NodeSortType.CUSTOM)

            val currentNodes = nodes.value
            val targetIds = currentNodes.map { it.id }
            val totalCount = targetIds.size
            _testingNodeIds.value = targetIds.toSet()

            var completedCount = 0
            var successCount = 0
            var timeoutCount = 0
            _testProgress.value = Pair(0, totalCount)

            try {
                configRepository.testAllNodesLatency(targetIds) { finishedNodeId, latencyMs ->
                    _testingNodeIds.value = _testingNodeIds.value - finishedNodeId
                    completedCount++
                    if (latencyMs > 0) {
                        successCount++
                    } else {
                        timeoutCount++
                    }
                    _testProgress.value = Pair(completedCount, totalCount)
                }
                setSortType(NodeSortType.LATENCY)
                emitToast(getApplication<Application>().getString(R.string.nodes_test_complete_stats, successCount, timeoutCount))
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isTesting.value = false
                _testingNodeIds.value = emptySet()
                _testProgress.value = null
                testingJob = null
            }
        }
    }

    fun deleteNode(nodeId: String) {
        viewModelScope.launch {
            val nodeName = configRepository.getNodeById(nodeId)?.displayName ?: ""
            configRepository.deleteNode(nodeId)
            emitToast(getApplication<Application>().getString(R.string.profiles_deleted) + ": $nodeName")
        }
    }

    fun exportNode(nodeId: String): String? {
        return configRepository.exportNode(nodeId)
    }

    fun setSortType(type: NodeSortType) {
        _sortType.value = type
        viewModelScope.launch {
            settingsRepository.setNodeSortType(type)
        }
    }

    // 设置节点过滤条件
    fun setNodeFilter(filter: NodeFilter) {
        _nodeFilter.value = filter
        viewModelScope.launch {
            settingsRepository.setNodeFilter(filter)
        }
        emitToast(getApplication<Application>().getString(R.string.nodes_filter_applied))
    }

    // 清除节点过滤条件
    fun clearNodeFilter() {
        val emptyFilter = NodeFilter()
        _nodeFilter.value = emptyFilter
        viewModelScope.launch {
            settingsRepository.setNodeFilter(emptyFilter)
        }
        emitToast(getApplication<Application>().getString(R.string.nodes_filter_cleared))
    }

    fun clearLatency() {
        viewModelScope.launch {
            // 清空前冻结当前顺序，防止列表跳动
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
                "tuic://", "anytls://", "wireguard://", "ssh://"
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
