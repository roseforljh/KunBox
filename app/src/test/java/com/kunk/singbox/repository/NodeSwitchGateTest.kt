package com.kunk.singbox.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSwitchGateTest {

    @Test
    fun concurrentSwitchRequestsRunInOrderInsteadOfBeingDropped() = runBlocking {
        val gate = NodeSwitchGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val executed = mutableListOf<String>()

        val first = async {
            gate.run {
                executed.add("first")
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val second = async {
            gate.run {
                executed.add("second")
            }
        }
        delay(50)

        assertEquals(listOf("first"), executed)
        assertFalse(second.isCompleted)

        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertTrue(second.isCompleted)
        assertEquals(listOf("first", "second"), executed)
    }
}
