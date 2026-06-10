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
