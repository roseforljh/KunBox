package com.kunk.singbox.service.manager

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandManagerGroupSelectionTest {

    @Test
    fun resolvesNestedAutomaticGroupToConcreteNode() {
        val selected = CommandManager.resolveConcreteGroupSelection(
            rootTag = "PROXY",
            selections = mapOf(
                "PROXY" to "P:profile#AUTO",
                "P:profile#AUTO" to "node-a"
            )
        )

        assertEquals("node-a", selected)
    }

    @Test
    fun preservesPreviousStateWhenIntermediateCallbackIsMissing() {
        assertNull(
            CommandManager.resolveConcreteGroupSelection(
                rootTag = "PROXY",
                selections = mapOf("PROXY" to "P:profile#AUTO")
            )
        )
    }

    @Test
    fun rejectsCyclesAndExcessiveDepth() {
        assertNull(
            CommandManager.resolveConcreteGroupSelection(
                rootTag = "PROXY",
                selections = mapOf("PROXY" to "AUTO", "AUTO" to "PROXY")
            )
        )
        assertNull(
            CommandManager.resolveConcreteGroupSelection(
                rootTag = "A",
                selections = mapOf("A" to "B", "B" to "C", "C" to "D", "D" to "E", "E" to "node")
            )
        )
    }

    @Test
    fun resolvedAutomaticNodeIsPropagatedToServiceAndMainProcess() {
        val managerSource = File(
            "src/main/java/com/kunk/singbox/service/manager/CommandManager.kt"
        ).readText()
        val serviceSource = File(
            "src/main/java/com/kunk/singbox/service/SingBoxService.kt"
        ).readText()

        assertTrue(managerSource.contains("callbacks?.onRuntimeNodeChanged(selected)"))
        assertTrue(serviceSource.contains("override fun onRuntimeNodeChanged(nodeName: String)"))
        assertTrue(serviceSource.contains("realTimeNodeName = nodeName"))
        assertTrue(serviceSource.contains("requestRemoteStateUpdate(force = false)"))
    }
}
