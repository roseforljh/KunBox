package com.kunk.singbox.service.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
