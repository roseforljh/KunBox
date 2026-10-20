package com.kunk.singbox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelSelectionTrackerTest {
    @Test
    fun ignoresMatchingObservationAtRevisionBoundary() = runBlocking {
        val tracker = KernelSelectionTracker()
        tracker.record("PROXY", "target")

        assertNull(tracker.awaitSelection("PROXY", "target", tracker.currentRevision(), 20L))
    }

    @Test
    fun unrelatedGroupCannotConfirmSelectionEvenWithNewerRevision() = runBlocking {
        val tracker = KernelSelectionTracker()
        val revision = tracker.currentRevision()
        tracker.record("P:profile", "target")

        assertNull(tracker.awaitSelection("PROXY", "target", revision, 20L))
    }

    @Test
    fun clearDropsOldAckWithoutResettingRevision() = runBlocking {
        val tracker = KernelSelectionTracker()
        tracker.record("PROXY", "target")
        val revision = tracker.currentRevision()
        tracker.clear()

        assertNull(tracker.awaitSelection("PROXY", "target", 0L, 20L))
        tracker.record("PROXY", "next")
        assertTrue(tracker.currentRevision() > revision)
        assertEquals("next", tracker.awaitSelection("PROXY", "next", revision, 20L))
    }

    @Test
    fun externalCancellationIsNotReportedAsConfirmationTimeout() = runBlocking {
        val tracker = KernelSelectionTracker()
        val cancelled = CompletableDeferred<Boolean>()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                tracker.awaitSelection("PROXY", "target", 0L, 10_000L)
                cancelled.complete(false)
            } catch (error: CancellationException) {
                cancelled.complete(true)
                throw error
            }
        }

        waiter.cancel()
        waiter.join()

        assertTrue(cancelled.await())
        assertFalse(waiter.isActive)
    }

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
    fun confirmsSelectionWhenKernelTrimsBoundaryWhitespace() = runBlocking {
        val tracker = KernelSelectionTracker()
        val revision = tracker.currentRevision()
        tracker.record("PROXY", "1.88u idc")

        val selected = tracker.awaitSelection("PROXY", "1.88u idc ", revision, 20L)

        assertEquals("1.88u idc ", selected)
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
