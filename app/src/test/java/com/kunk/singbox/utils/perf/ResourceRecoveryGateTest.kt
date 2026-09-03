package com.kunk.singbox.utils.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResourceRecoveryGateTest {
    @Test
    fun `budget exhaustion notice is emitted once until resources recover`() {
        val gate = ResourceRecoveryNoticeGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())
        assertTrue(gate.clear())
        assertFalse(gate.clear())
        assertTrue(gate.claim())
    }

    @Test
    fun `only resource budget errors qualify for healthy clearing`() {
        assertTrue(
            isResourceRecoveryBudgetError("Resource recovery budget exhausted: process_reclaim:fd_emergency")
        )
        assertFalse(isResourceRecoveryBudgetError("Failed to start VPN"))
        assertFalse(isResourceRecoveryBudgetError(null))
    }

    @Test
    fun `large native socket surplus is marked as pre connect gap`() {
        assertEquals("proc_unavailable", classifySocketAttribution(null, 10))
        assertEquals("attributed", classifySocketAttribution(80, 60))
        assertEquals("native_preconnect_gap", classifySocketAttribution(900, 24))
    }

    @Test
    fun `fd recovery never amplifies pressure with global close or network reset`() {
        val source = File("src/main/java/com/kunk/singbox/utils/perf/DiagnosticResourceGuard.kt")
            .readText(Charsets.UTF_8)
        val recoverBody = source.substringAfter("private suspend fun recover(")
            .substringBefore("private fun recycleProcessIfAllowed(")

        assertFalse(recoverBody.contains("closeConnections()"))
        assertFalse(recoverBody.contains("resetNetwork()"))
        assertTrue(recoverBody.contains("ResourceRecoveryAction.CORE_RESTART"))
        assertTrue(recoverBody.contains("recycleProcessIfAllowed"))
    }

    @Test
    fun `successful core restart defers process reclaim until pressure recurs`() {
        val source = File("src/main/java/com/kunk/singbox/utils/perf/DiagnosticResourceGuard.kt")
            .readText(Charsets.UTF_8)
        val insufficientBody = source.substringAfter("if (!isFdRecoverySufficient(")
            .substringBefore("} else {")

        assertFalse(insufficientBody.contains("recycleProcessIfAllowed"))
        assertTrue(insufficientBody.contains("deferred_to_next_pressure"))
    }

    @Test
    fun `guard monitor contains only state transitions and reference snapshots`() {
        val source = File("src/main/java/com/kunk/singbox/utils/perf/DiagnosticResourceGuard.kt")
            .readText(Charsets.UTF_8)
        val guardSource = source.substringAfter("internal object BackgroundResourceGuard")
        val lockBodies = synchronizedLockBodies(guardSource)
        val forbiddenCalls = listOf(
            ".isRecoveryAllowed(",
            ".closeConnections(",
            ".resetNetwork(",
            ".restartCore(",
            ".recycleProcess(",
            ".publishBudgetExhausted(",
            "VpnStateStore.",
            "captureCurrentProcess(",
            "DiagnosticResourceSampler(",
            "DiagnosticResourceHistory(",
            ".append(",
            ".start()",
            ".cancel()",
            ".complete(",
            ".join()",
            ".await()",
            "launch("
        )

        assertTrue(lockBodies.isNotEmpty())
        forbiddenCalls.forEach { call ->
            assertTrue("synchronized(lock) must not invoke $call", lockBodies.none { call in it })
        }
    }

    private fun synchronizedLockBodies(source: String): List<String> {
        val marker = "synchronized(lock)"
        val bodies = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val markerIndex = source.indexOf(marker, cursor)
            if (markerIndex < 0) return bodies
            val openingBrace = source.indexOf('{', markerIndex + marker.length)
            check(openingBrace >= 0) { "Missing synchronized(lock) body" }
            var depth = 1
            var index = openingBrace + 1
            while (index < source.length && depth > 0) {
                when (source[index]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                index++
            }
            check(depth == 0) { "Unbalanced synchronized(lock) body" }
            bodies += source.substring(openingBrace + 1, index - 1)
            cursor = index
        }
    }

    @Test
    fun `same owner successor takes over awaiting recovery`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val first = ResourceGuardRegistration(owner, 1L)
        val second = ResourceGuardRegistration(owner, 2L)

        gate.register(first)
        val attemptId = checkNotNull(gate.beginRecovery(first))
        assertTrue(gate.awaitSuccessor(first, attemptId))
        assertEquals(attemptId, gate.detach(first, attemptId).preservedAttemptId)

        val result = gate.register(second)
        assertEquals(attemptId, result.successorAttemptId)
        assertEquals(ResourceRecoveryPhase.OBSERVING_SUCCESSOR, gate.phase(attemptId))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `ordinary stop cancels recovery`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val registration = ResourceGuardRegistration(owner, 1L)

        gate.register(registration)
        val attemptId = checkNotNull(gate.beginRecovery(registration))
        val result = gate.detach(registration, handoffAttemptId = null)

        assertEquals(attemptId, result.cancelledAttemptId)
        assertNull(gate.phase(attemptId))
        assertFalse(gate.isCurrent(registration))
    }

    @Test
    fun `different owner cannot take over recovery`() {
        val gate = ResourceRecoveryGate()
        val firstOwner = Any()
        val first = ResourceGuardRegistration(firstOwner, 1L)

        gate.register(first)
        val attemptId = checkNotNull(gate.beginRecovery(first))
        assertTrue(gate.awaitSuccessor(first, attemptId))
        gate.detach(first, attemptId)

        val result = gate.register(ResourceGuardRegistration(Any(), 1L))
        assertEquals(attemptId, result.cancelledAttemptId)
        assertNull(result.successorAttemptId)
        assertNull(gate.phase(attemptId))
    }

    @Test
    fun `old generation detach cannot clear successor`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val first = ResourceGuardRegistration(owner, 1L)
        val second = ResourceGuardRegistration(owner, 2L)

        gate.register(first)
        val attemptId = checkNotNull(gate.beginRecovery(first))
        gate.awaitSuccessor(first, attemptId)
        gate.detach(first, attemptId)
        gate.register(second)

        assertFalse(gate.detach(first, handoffAttemptId = null).detached)
        assertTrue(gate.isCurrent(second))
        assertEquals(ResourceRecoveryPhase.OBSERVING_SUCCESSOR, gate.phase(attemptId))
    }

    @Test
    fun `third generation invalidates old observation`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val first = ResourceGuardRegistration(owner, 1L)
        val second = ResourceGuardRegistration(owner, 2L)
        val third = ResourceGuardRegistration(owner, 3L)

        gate.register(first)
        val attemptId = checkNotNull(gate.beginRecovery(first))
        gate.awaitSuccessor(first, attemptId)
        gate.detach(first, attemptId)
        gate.register(second)

        val result = gate.register(third)
        assertEquals(attemptId, result.cancelledAttemptId)
        assertNull(gate.phase(attemptId))
        assertTrue(gate.isCurrent(third))
    }

    @Test
    fun `old finally cannot clear newer attempt`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val first = ResourceGuardRegistration(owner, 1L)
        val second = ResourceGuardRegistration(owner, 2L)

        gate.register(first)
        val firstAttemptId = checkNotNull(gate.beginRecovery(first))
        gate.register(second)
        val secondAttemptId = checkNotNull(gate.beginRecovery(second))

        assertFalse(gate.finish(firstAttemptId))
        assertEquals(ResourceRecoveryPhase.RESETTING, gate.phase(secondAttemptId))
    }

    @Test
    fun `exact finish clears current attempt`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val registration = ResourceGuardRegistration(owner, 1L)

        gate.register(registration)
        val attemptId = checkNotNull(gate.beginRecovery(registration))

        assertTrue(gate.finish(attemptId))
        assertNull(gate.phase(attemptId))
        assertFalse(gate.isAttemptCurrent(owner, attemptId))
        assertFalse(gate.finish(attemptId))
    }

    @Test
    fun `foreign owner cancel cannot clear current recovery`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val registration = ResourceGuardRegistration(owner, 1L)

        gate.register(registration)
        val attemptId = checkNotNull(gate.beginRecovery(registration))
        val result = gate.cancelOwner(Any())

        assertFalse(result.registrationCancelled)
        assertNull(result.cancelledAttemptId)
        assertTrue(gate.isCurrent(registration))
        assertEquals(ResourceRecoveryPhase.RESETTING, gate.phase(attemptId))
    }

    @Test
    fun `wrong owner or attempt cannot claim process reclaim`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val registration = ResourceGuardRegistration(owner, 1L)

        gate.register(registration)
        val attemptId = checkNotNull(gate.beginRecovery(registration))

        assertFalse(gate.claimProcessReclaim(Any(), attemptId))
        assertFalse(gate.claimProcessReclaim(owner, attemptId + 1L))
        assertEquals(ResourceRecoveryPhase.RESETTING, gate.phase(attemptId))
        assertTrue(gate.claimProcessReclaim(owner, attemptId))
    }

    @Test
    fun `handoff is rejected outside awaiting phase`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val registration = ResourceGuardRegistration(owner, 1L)

        gate.register(registration)
        val attemptId = checkNotNull(gate.beginRecovery(registration))
        val result = gate.detach(registration, attemptId)

        assertNull(result.preservedAttemptId)
        assertEquals(attemptId, result.cancelledAttemptId)
        assertNull(gate.phase(attemptId))
    }

    @Test
    fun `process reclaim can be claimed once`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val registration = ResourceGuardRegistration(owner, 1L)

        gate.register(registration)
        val attemptId = checkNotNull(gate.beginRecovery(registration))

        assertTrue(gate.claimProcessReclaim(owner, attemptId))
        assertFalse(gate.claimProcessReclaim(owner, attemptId))
        assertEquals(ResourceRecoveryPhase.RECLAIM_CLAIMED, gate.phase(attemptId))
    }

    @Test
    fun `stale generation cannot replace current monitor`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val current = ResourceGuardRegistration(owner, 3L)
        val stale = ResourceGuardRegistration(owner, 2L)

        gate.register(current)
        val result = gate.register(stale)

        assertTrue(result.rejected)
        assertTrue(gate.isCurrent(current))
        assertFalse(gate.isCurrent(stale))
    }

    @Test
    fun `same generation cannot replace detached recovery source`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val source = ResourceGuardRegistration(owner, 3L)

        gate.register(source)
        val attemptId = checkNotNull(gate.beginRecovery(source))
        assertTrue(gate.awaitSuccessor(source, attemptId))
        gate.detach(source, attemptId)

        val result = gate.register(ResourceGuardRegistration(owner, 3L))

        assertTrue(result.rejected)
        assertEquals(ResourceRecoveryPhase.AWAITING_SUCCESSOR, gate.phase(attemptId))
    }

    @Test
    fun `older generation cannot cancel detached recovery before successor arrives`() {
        val gate = ResourceRecoveryGate()
        val owner = Any()
        val source = ResourceGuardRegistration(owner, 3L)
        val successor = ResourceGuardRegistration(owner, 4L)

        gate.register(source)
        val attemptId = checkNotNull(gate.beginRecovery(source))
        assertTrue(gate.awaitSuccessor(source, attemptId))
        gate.detach(source, attemptId)

        val staleResult = gate.register(ResourceGuardRegistration(owner, 2L))
        val successorResult = gate.register(successor)

        assertTrue(staleResult.rejected)
        assertEquals(attemptId, successorResult.successorAttemptId)
        assertEquals(ResourceRecoveryPhase.OBSERVING_SUCCESSOR, gate.phase(attemptId))
        assertTrue(gate.isCurrent(successor))
    }
}
