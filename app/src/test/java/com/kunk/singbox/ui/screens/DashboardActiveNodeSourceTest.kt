package com.kunk.singbox.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardActiveNodeSourceTest {

    @Test
    fun activeNodeNameRecomputesWhenNodesChange() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/DashboardScreen.kt").readText()

        assertTrue(source.contains("val activeNodeName by remember(activeNodeId, nodes)"))
    }
}
