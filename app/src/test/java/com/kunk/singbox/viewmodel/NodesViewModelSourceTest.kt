package com.kunk.singbox.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NodesViewModelSourceTest {

    @Test
    fun individualLatencyTestingUpdatesTestingSetAtomically() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/NodesViewModel.kt").readText()
        val body = source.substring(
            source.indexOf("fun testLatency(nodeId: String)"),
            source.indexOf("fun clearLatencyMessage()")
        )

        assertTrue(source.contains("import kotlinx.coroutines.flow.update"))
        assertTrue(body.contains("_testingNodeIds.update { current ->"))
        assertTrue(body.contains("_testingNodeIds.update { it - nodeId }"))
        assertFalse(body.contains("_testingNodeIds.value = _testingNodeIds.value + nodeId"))
        assertFalse(body.contains("_testingNodeIds.value = _testingNodeIds.value - nodeId"))
    }

    @Test
    fun batchLatencyProgressCountersAreThreadSafeForConcurrentCallbacks() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/NodesViewModel.kt").readText()
        val body = source.substring(
            source.indexOf("fun testAllLatency()"),
            source.indexOf("fun deleteNode(nodeId: String)")
        )

        assertTrue(source.contains("import java.util.concurrent.atomic.AtomicInteger"))
        assertTrue(body.contains("val completedCount = AtomicInteger(0)"))
        assertTrue(body.contains("val successCount = AtomicInteger(0)"))
        assertTrue(body.contains("val timeoutCount = AtomicInteger(0)"))
        assertTrue(body.contains("val ipv6OnlyCount = AtomicInteger(0)"))
        assertTrue(body.contains("val completed = completedCount.incrementAndGet()"))
        assertTrue(body.contains("_testingNodeIds.update { it - finishedNodeId }"))
        assertFalse(body.contains("completedCount++"))
        assertFalse(body.contains("successCount++"))
        assertFalse(body.contains("timeoutCount++"))
        assertFalse(body.contains("ipv6OnlyCount++"))
    }

    @Test
    fun deleteNodeWaitsForRepositoryDeletionBeforeToast() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/NodesViewModel.kt").readText()
        val body = source.substring(
            source.indexOf("fun deleteNode(nodeId: String)"),
            source.indexOf("suspend fun exportNode(nodeId: String)")
        )

        val deleteIndex = body.indexOf("configRepository.deleteNode(nodeId)")
        val toastIndex = body.indexOf("emitToast(")

        assertTrue(deleteIndex >= 0)
        assertTrue(toastIndex >= 0)
        assertTrue(deleteIndex < toastIndex)
    }

    @Test
    fun exportNodeUsesSuspendRepositoryApi() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/NodesViewModel.kt").readText()
        val screenSource = File("src/main/java/com/kunk/singbox/ui/screens/NodesScreen.kt").readText()
        val body = source.substring(
            source.indexOf("suspend fun exportNode(nodeId: String)"),
            source.indexOf("fun setSortType(type: NodeSortType)")
        )
        val exportClick = screenSource.substringAfter("val onExport = remember(node.id)")
            .substringBefore("val onLatency")

        assertTrue(body.contains("return configRepository.exportNode(nodeId)"))
        assertTrue(exportClick.contains("scope.launch"))
        assertTrue(exportClick.contains("viewModel.exportNode(node.id)"))
    }
}
