package com.kunk.singbox.service.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimeStateMachineTest {
    @Test
    fun acceptsOnlyCurrentSessionAndMonotonicGeneration() {
        val current = RootRuntimeSnapshot(
            phase = RootRuntimePhase.RUNNING,
            runtimeSessionId = "session-a",
            generation = 4
        )

        assertTrue(shouldAcceptRootSnapshot(current, current.copy(generation = 5)))
        assertTrue(shouldAcceptRootSnapshot(current, current.copy(generation = 4)))
        assertFalse(shouldAcceptRootSnapshot(current, current.copy(generation = 3)))
        assertFalse(
            shouldAcceptRootSnapshot(
                current,
                current.copy(runtimeSessionId = "session-b", generation = 10)
            )
        )
    }
}
