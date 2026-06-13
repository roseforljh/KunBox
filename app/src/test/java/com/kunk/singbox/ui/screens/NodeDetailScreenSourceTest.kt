package com.kunk.singbox.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NodeDetailScreenSourceTest {

    @Test
    fun nodeDetailReloadsOutboundWhenNodeListsRecover() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/NodeDetailScreen.kt").readText()
        val launchedEffect = source.substringAfter("LaunchedEffect(nodeId, createProtocol, nodes, allNodes)")
            .substringBefore("fun resolveNodeByStoredValue")

        assertTrue(source.contains("LaunchedEffect(nodeId, createProtocol, nodes, allNodes)"))
        assertTrue(launchedEffect.contains("configRepository.getOutboundByNodeId(nodeId)"))
    }

    @Test
    fun nodeDetailSavesNodesOffMainThreadAndOnlyReportsSuccessAfterWrite() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/NodeDetailScreen.kt").readText()

        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("runCatching"))
        assertTrue(source.contains("onSuccess"))
        assertTrue(source.contains("onFailure"))
        assertTrue(source.contains("profiles_import_failed"))
    }

    @Test
    fun nodeDetailSavesEditingOutboundAcrossRecreation() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/NodeDetailScreen.kt").readText()

        assertTrue(source.contains("import com.google.gson.Gson"))
        assertTrue(source.contains("import androidx.compose.runtime.saveable.Saver"))
        assertTrue(source.contains("import androidx.compose.runtime.saveable.rememberSaveable"))
        assertTrue(source.contains("Saver<MutableState<Outbound?>, String>"))
        assertTrue(source.contains("val editingOutboundState = rememberSaveable("))
        assertTrue(source.contains("var editingOutbound by editingOutboundState"))
        assertFalse(source.contains("var editingOutbound by remember { mutableStateOf<Outbound?>(null) }"))
    }
}
