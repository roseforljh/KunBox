package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.NodeTrafficStats
import com.kunk.singbox.repository.TrafficPeriod
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.repository.TrafficSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrafficStatsUiState(
    val isLoading: Boolean = true,
    val selectedPeriod: TrafficPeriod = TrafficPeriod.THIS_MONTH,
    val summary: TrafficSummary? = null,
    val topNodes: List<NodeTrafficStats> = emptyList(),
    val nodePercentages: List<Pair<NodeTrafficStats, Float>> = emptyList(),
    val nodeNames: Map<String, String> = emptyMap()
)

internal fun buildNodeNameMap(
    topNodes: List<NodeTrafficStats>,
    percentages: List<Pair<NodeTrafficStats, Float>>,
    fallbackName: (String) -> String?
): Map<String, String> {
    val nodeIds = linkedSetOf<String>()
    val nodeNames = linkedMapOf<String, String>()

    fun collect(stats: NodeTrafficStats) {
        val nodeId = stats.nodeId ?: return
        nodeIds += nodeId
        stats.nodeName
            ?.takeIf { it.isNotBlank() }
            ?.let { nodeNames.putIfAbsent(nodeId, it) }
    }

    topNodes.forEach(::collect)
    percentages.forEach { (stats, _) -> collect(stats) }
    nodeIds.forEach { nodeId ->
        if (nodeId !in nodeNames) {
            fallbackName(nodeId)?.let { nodeNames[nodeId] = it }
        }
    }
    return nodeNames
}

class TrafficStatsViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    private val _uiState = MutableStateFlow(TrafficStatsUiState())
    val uiState: StateFlow<TrafficStatsUiState> = _uiState.asStateFlow()

    private var loadTrafficDataJob: Job? = null

    init {
        loadTrafficData()
    }

    fun selectPeriod(period: TrafficPeriod) {
        _uiState.value = _uiState.value.copy(selectedPeriod = period, isLoading = true)
        loadTrafficData()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                TrafficRepository.getInstance(appContext).reloadFromDisk()
            }
            loadTrafficData()
        }
    }

    private fun loadTrafficData() {
        loadTrafficDataJob?.cancel()
        loadTrafficDataJob = viewModelScope.launch {
            val period = _uiState.value.selectedPeriod
            val trafficRepository = withContext(Dispatchers.IO) {
                TrafficRepository.getInstance(appContext)
            }
            val configRepository = withContext(Dispatchers.IO) {
                ConfigRepository.getInstance(appContext)
            }

            val loadedState = withContext(Dispatchers.Default) {
                val summary = trafficRepository.getTrafficSummary(period)
                val topNodes = trafficRepository.getTopNodes(summary, 10)
                val percentages = trafficRepository.getNodeTrafficPercentages(summary)
                val fallbackNames = (
                    configRepository.allNodes.value + configRepository.nodes.value
                    ).associate { node -> node.id to node.name }

                val nodeNames = buildNodeNameMap(topNodes, percentages) { nodeId ->
                    fallbackNames[nodeId]
                }

                TrafficStatsUiState(
                    isLoading = false,
                    selectedPeriod = period,
                    summary = summary,
                    topNodes = topNodes,
                    nodePercentages = percentages,
                    nodeNames = nodeNames
                )
            }

            if (_uiState.value.selectedPeriod != period) return@launch

            _uiState.value = loadedState
        }
    }

    fun clearAllStats() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                TrafficRepository.getInstance(appContext).clearAllStats()
            }
            loadTrafficData()
        }
    }

    fun getNodeDisplayName(nodeId: String): String {
        return _uiState.value.nodeNames[nodeId] ?: nodeId
    }
}
