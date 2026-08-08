package com.kunk.singbox.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KernelSelectionTrackerTest {
    @Test
    fun waitsPastTransitionalMismatchUntilExpectedSelectionArrives() = runBlocking {
        val tracker = KernelSelectionTracker()
        val revision = tracker.currentRevision()
        launch {
            delay(5L)
            tracker.record("PROXY", "old-node")
            delay(5L)
            tracker.record("PROXY", "new-node")
        }

        val selected = tracker.awaitSelection("PROXY", "new-node", revision, 500L)

        assertEquals("new-node", selected)
    }

    @Test
    fun returnsLatestMismatchedSelectionAfterTimeout() = runBlocking {
        val tracker = KernelSelectionTracker()
        val revision = tracker.currentRevision()
        launch {
            delay(5L)
            tracker.record("PROXY", "unexpected-node")
        }

        val selected = tracker.awaitSelection("PROXY", "expected-node", revision, 40L)

        assertEquals("unexpected-node", selected)
    }

    @Test
    fun returnsNullWhenKernelDoesNotReportSelection() = runBlocking {
        val tracker = KernelSelectionTracker()

        val selected = tracker.awaitSelection("PROXY", "new-node", tracker.currentRevision(), 20L)

        assertNull(selected)
    }

    @Test
    fun preservesProxyAckWhenFollowingGroupArrivesBeforeCollectorStarts() = runBlocking {
        val tracker = KernelSelectionTracker()
        val revision = tracker.currentRevision()
        tracker.record("PROXY", "new-node")
        tracker.record("P:profile", "other-node")

        val selected = tracker.awaitSelection("PROXY", "new-node", revision, 20L)

        assertEquals("new-node", selected)
    }
}
