package com.kunk.singbox.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrafficStatsViewModelSourceTest {

    @Test
    fun loadTrafficDataCancelsOlderLoadAndGuardsSelectedPeriod() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/TrafficStatsViewModel.kt").readText()

        assertTrue(source.contains("private var loadTrafficDataJob: Job? = null"))
        assertTrue(source.contains("loadTrafficDataJob?.cancel()"))
        assertTrue(source.contains("loadTrafficDataJob = viewModelScope.launch"))
        assertTrue(source.contains("if (_uiState.value.selectedPeriod != period) return@launch"))
    }

    @Test
    fun loadTrafficDataMovesRepositoryInitializationAndAggregationOffMainThread() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/TrafficStatsViewModel.kt").readText()
        val function = source.substringAfter("private fun loadTrafficData()")
            .substringBefore("fun clearAllStats")

        assertFalse(source.contains("private val trafficRepository = TrafficRepository.getInstance(application)"))
        assertFalse(source.contains("private val configRepository = ConfigRepository.getInstance(application)"))
        assertTrue(function.contains("withContext(Dispatchers.IO)"))
        assertTrue(function.contains("TrafficRepository.getInstance(appContext)"))
        assertTrue(function.contains("ConfigRepository.getInstance(appContext)"))
        assertTrue(function.contains("withContext(Dispatchers.Default)"))
        assertTrue(function.contains("trafficRepository.getTrafficSummary(period)"))
        assertTrue(function.contains("trafficRepository.getTopNodes(summary, 10)"))
        assertTrue(function.contains("trafficRepository.getNodeTrafficPercentages(summary)"))
    }
}
