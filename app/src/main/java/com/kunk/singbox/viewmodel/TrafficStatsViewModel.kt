package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.NodeTrafficStats
import com.kunk.singbox.repository.TrafficPeriod
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.repository.TrafficSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TrafficStatsUiState(
    val isLoading: Boolean = true,
    val selectedPeriod: TrafficPeriod = TrafficPeriod.THIS_MONTH,
    val summary: TrafficSummary? = null,
    val topNodes: List<NodeTrafficStats> = emptyList(),
    val nodePercentages: List<Pair<NodeTrafficStats, Float>> = emptyList(),
    val nodeNames: Map<String, String> = emptyMap()
)

class TrafficStatsViewModel(application: Application) : AndroidViewModel(application) {

    private val trafficRepository = TrafficRepository.getInstance(application)
    private val configRepository = ConfigRepository.getInstance(application)

    private val _uiState = MutableStateFlow(TrafficStatsUiState())
    val uiState: StateFlow<TrafficStatsUiState> = _uiState.asStateFlow()

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
            trafficRepository.reloadFromDisk()
            loadTrafficData()
        }
    }

    private fun loadTrafficData() {
        viewModelScope.launch {
            val period = _uiState.value.selectedPeriod
            val summary = trafficRepository.getTrafficSummary(period)
            val topNodes = trafficRepository.getTopNodes(period, 10)
            val percentages = trafficRepository.getNodeTrafficPercentages(period)

            val nodeNames = mutableMapOf<String, String>()
            val allNodeIds = mutableSetOf<String>()
            topNodes.forEach { it.nodeId?.let { id -> allNodeIds.add(id) } }
            percentages.forEach { it.first.nodeId?.let { id -> allNodeIds.add(id) } }

            allNodeIds.forEach { nodeId ->
                val storedName = topNodes.find { it.nodeId == nodeId }?.nodeName
                    ?: percentages.find { it.first.nodeId == nodeId }?.first?.nodeName
                if (!storedName.isNullOrBlank()) {
                    nodeNames[nodeId] = storedName
                } else {
                    val node = configRepository.getNodeById(nodeId)
                    if (node != null) {
                        nodeNames[nodeId] = node.name
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                summary = summary,
                topNodes = topNodes,
                nodePercentages = percentages,
                nodeNames = nodeNames
            )
        }
    }

    fun clearAllStats() {
        viewModelScope.launch {
            trafficRepository.clearAllStats()
            loadTrafficData()
        }
    }

    fun getNodeDisplayName(nodeId: String): String {
        return _uiState.value.nodeNames[nodeId] ?: nodeId
    }
}
