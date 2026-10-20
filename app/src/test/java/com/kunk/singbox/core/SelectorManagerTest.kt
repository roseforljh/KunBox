package com.kunk.singbox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelectorManagerTest {
    @Before
    fun reset() {
        SelectorManager.clear()
        SelectorManager.recordSelectorSignature(listOf("direct-node", "detour-node"))
    }

    @After
    fun cleanup() {
        SelectorManager.clear()
    }

    @Test
    fun confirmsSynchronousKernelAckForBothSwitchDirections() = runBlocking {
        for (target in listOf("detour-node", "direct-node")) {
            val result = switchWith(target) { group, selected ->
                assertTrue(SelectorManager.isSelectionPending())
                SelectorManager.recordKernelSelection(group, selected)
            }
            assertEquals(SelectorManager.SwitchResult.Success("CommandClient+KernelAck"), result)
            assertEquals(target, SelectorManager.getSelectedOutbound())
            assertFalse(SelectorManager.isSelectionPending())
        }
    }

    @Test
    fun restoringSelectorAfterRuntimeClearAllowsHotSwitchAgain() = runBlocking {
        val tags = SelectorManager.getCurrentOutboundTags()
        SelectorManager.clear()
        assertFalse(SelectorManager.hasSelector())
        assertTrue(SelectorManager.getCurrentOutboundTags().isEmpty())

        SelectorManager.recordSelectorSignature(tags)
        val result = switchWith("detour-node") { group, tag -> SelectorManager.recordKernelSelection(group, tag) }

        assertTrue(SelectorManager.hasSelector())
        assertTrue(SelectorManager.canHotSwitch(tags))
        assertTrue(result is SelectorManager.SwitchResult.Success)
    }

    @Test
    fun oldMatchingObservationCannotConfirmNewRequest() = runBlocking {
        SelectorManager.recordKernelSelection("PROXY", "detour-node")

        val result = switchWith("detour-node") { _, _ -> }

        assertTrue(result is SelectorManager.SwitchResult.NeedRestart)
        assertTrue((result as SelectorManager.SwitchResult.NeedRestart).reason.contains("timed out"))
        assertFalse(SelectorManager.isSelectionPending())
    }

    @Test
    fun mismatchedAckDoesNotConfirmSwitch() = runBlocking {
        val result = switchWith("detour-node") { group, _ ->
            SelectorManager.recordKernelSelection(group, "direct-node")
        }

        assertTrue(result is SelectorManager.SwitchResult.NeedRestart)
        assertTrue((result as SelectorManager.SwitchResult.NeedRestart).reason.contains("mismatched"))
        assertFalse(SelectorManager.isSelectionPending())
    }

    @Test
    fun invalidTargetDoesNotInvokeNativeCommand() = runBlocking {
        val result = switchWith("missing-node") { _, _ -> error("Must not invoke native command") }

        assertEquals(SelectorManager.SwitchResult.NeedRestart("Node not in current selector"), result)
        assertFalse(SelectorManager.isSelectionPending())
    }

    @Test
    fun missingClientRequestsReloadWithoutPublishingSelection() = runBlocking {
        val result = SelectorManager.switchNode("detour-node", 20L)

        assertEquals(SelectorManager.SwitchResult.NeedRestart("CommandClient hot switch unavailable"), result)
        assertNull(SelectorManager.getSelectedOutbound())
        assertFalse(SelectorManager.isSelectionPending())
    }

    @Test
    fun nativeExceptionRequestsReloadAndClearsPendingState() = runBlocking {
        val result = switchWith("detour-node") { _, _ -> throw IllegalStateException("client disconnected") }

        assertEquals(SelectorManager.SwitchResult.NeedRestart("CommandClient hot switch unavailable"), result)
        assertNull(SelectorManager.getSelectedOutbound())
        assertFalse(SelectorManager.isSelectionPending())
    }

    @Test
    fun nativeCancellationPropagatesInsteadOfRequestingReload() = runBlocking {
        val cancellation = CancellationException("switch superseded")
        val thrown = try {
            switchWith("detour-node") { _, _ -> throw cancellation }
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, thrown)
        assertFalse(SelectorManager.isSelectionPending())
    }

    @Test
    fun cancellingAckWaitDoesNotReturnNeedRestartAndReleasesMutex() = runBlocking {
        val returned = CompletableDeferred<SelectorManager.SwitchResult>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            returned.complete(switchWith("detour-node", timeoutMs = 10_000L) { _, _ -> })
        }
        assertTrue(SelectorManager.isSelectionPending())

        pending.cancel()
        pending.join()

        assertFalse("Cancelled switch must not produce a restart result", returned.isCompleted)
        assertFalse(SelectorManager.isSelectionPending())
        val next = switchWith("direct-node") { group, tag -> SelectorManager.recordKernelSelection(group, tag) }
        assertTrue(next is SelectorManager.SwitchResult.Success)
    }

    @Test
    fun cancellationDuringNativeCallCannotPublishSuccess() = runBlocking {
        val returned = CompletableDeferred<SelectorManager.SwitchResult>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            val job = currentCoroutineContext().job
            returned.complete(
                switchWith("detour-node") { group, tag ->
                    SelectorManager.recordKernelSelection(group, tag)
                    job.cancel()
                }
            )
        }
        pending.join()

        assertFalse("Cancelled native call must not publish success or request reload", returned.isCompleted)
        assertFalse(SelectorManager.isSelectionPending())
    }

    private suspend fun switchWith(
        target: String,
        timeoutMs: Long = 20L,
        command: (String, String) -> Unit
    ): SelectorManager.SwitchResult = SelectorManager.switchNodeWithCommand(
        groupTag = "PROXY",
        nodeTag = target,
        allowedOutboundTags = SelectorManager.getCurrentOutboundTags(),
        confirmationTimeoutMs = timeoutMs,
        commandProvider = { command }
    )
}
