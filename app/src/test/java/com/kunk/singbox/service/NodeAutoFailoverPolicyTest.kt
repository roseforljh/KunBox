package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeAutoFailoverPolicyTest {

    private fun validTriggerContext(): NodeAutoFailoverPolicy.TriggerContext {
        return NodeAutoFailoverPolicy.TriggerContext(
            isVpnRunning = true,
            isManuallyStopped = false,
            isAutoFailoverInFlight = false,
            inStartupGracePeriod = false,
            inNetworkChangeGracePeriod = false,
            isProxyIdle = false,
            lastMeaningfulTrafficAtMs = 99_500L,
            nowAtMs = 100_000L,
            lastAutoFailoverAtMs = 0L,
            budgetWindowStartAtMs = 0L,
            budgetCount = 0,
            isAutoSelectionEnabled = true
        )
    }

    @Test
    fun shouldNotStartProbeWithoutRecentTraffic() {
        val context = NodeAutoFailoverPolicy.TriggerContext(
            isVpnRunning = true,
            isManuallyStopped = false,
            isAutoFailoverInFlight = false,
            inStartupGracePeriod = false,
            inNetworkChangeGracePeriod = false,
            isProxyIdle = false,
            lastMeaningfulTrafficAtMs = 10_000L,
            nowAtMs = 60_001L,
            lastAutoFailoverAtMs = 0L,
            budgetWindowStartAtMs = 0L,
            budgetCount = 0,
            isAutoSelectionEnabled = true
        )

        assertFalse(NodeAutoFailoverPolicy.shouldStartProbe(context))
    }

    @Test
    fun shouldNotStartProbeDuringCooldown() {
        val context = NodeAutoFailoverPolicy.TriggerContext(
            isVpnRunning = true,
            isManuallyStopped = false,
            isAutoFailoverInFlight = false,
            inStartupGracePeriod = false,
            inNetworkChangeGracePeriod = false,
            isProxyIdle = false,
            lastMeaningfulTrafficAtMs = 95_000L,
            nowAtMs = 100_000L,
            lastAutoFailoverAtMs = 50_001L,
            budgetWindowStartAtMs = 0L,
            budgetCount = 0,
            isAutoSelectionEnabled = true
        )

        assertFalse(NodeAutoFailoverPolicy.shouldStartProbe(context))
    }

    @Test
    fun shouldNotStartProbeWhenBudgetIsExhausted() {
        val context = NodeAutoFailoverPolicy.TriggerContext(
            isVpnRunning = true,
            isManuallyStopped = false,
            isAutoFailoverInFlight = false,
            inStartupGracePeriod = false,
            inNetworkChangeGracePeriod = false,
            isProxyIdle = false,
            lastMeaningfulTrafficAtMs = 99_000L,
            nowAtMs = 100_000L,
            lastAutoFailoverAtMs = 0L,
            budgetWindowStartAtMs = 95_000L,
            budgetCount = NodeAutoFailoverPolicy.AUTO_FAILOVER_BUDGET_MAX_COUNT,
            isAutoSelectionEnabled = true
        )

        assertFalse(NodeAutoFailoverPolicy.shouldStartProbe(context))
    }

    @Test
    fun shouldStartProbeWhenAllGuardsPass() {
        val context = NodeAutoFailoverPolicy.TriggerContext(
            isVpnRunning = true,
            isManuallyStopped = false,
            isAutoFailoverInFlight = false,
            inStartupGracePeriod = false,
            inNetworkChangeGracePeriod = false,
            isProxyIdle = false,
            lastMeaningfulTrafficAtMs = 99_500L,
            nowAtMs = 100_000L,
            lastAutoFailoverAtMs = 0L,
            budgetWindowStartAtMs = 0L,
            budgetCount = 0,
            isAutoSelectionEnabled = true
        )

        assertTrue(NodeAutoFailoverPolicy.shouldStartProbe(context))
    }

    @Test
    fun shouldNotStartProbeWhenAutoSelectionIsDisabled() {
        val context = validTriggerContext().copy(isAutoSelectionEnabled = false)

        assertFalse(NodeAutoFailoverPolicy.shouldStartProbe(context, trigger = "dns_remote_timeout"))
    }

    @Test
    fun shouldNotStartProbeDuringResourceFailure() {
        val context = validTriggerContext().copy(hasResourceFailure = true)

        assertFalse(NodeAutoFailoverPolicy.shouldStartProbe(context))
    }

    @Test
    fun evaluateProbeMarksCurrentNodeHealthyWhenDelayExists() {
        val evaluation = NodeAutoFailoverPolicy.evaluateProbe(
            currentTag = "node-a",
            urlTestResults = mapOf("node-a" to 120, "node-b" to 90)
        )

        assertEquals(NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_HEALTHY, evaluation.outcome)
        assertEquals("node-a", evaluation.currentTag)
        assertEquals(120, evaluation.currentDelayMs)
        assertNull(evaluation.alternativeTag)
    }

    @Test
    fun evaluateProbeIgnoresOfflineDelayWhenCurrentMarkedFailedByLiveSignal() {
        // 远程 DNS 已超时：即使离线测速仍有 60ms，也必须切走
        val evaluation = NodeAutoFailoverPolicy.evaluateProbe(
            currentTag = "node-a",
            urlTestResults = mapOf("node-a" to 60, "node-b" to 95, "node-c" to 140),
            treatCurrentAsFailed = true
        )

        assertEquals(NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE, evaluation.outcome)
        assertEquals("node-b", evaluation.alternativeTag)
        assertEquals(95, evaluation.alternativeDelayMs)
        assertEquals(60, evaluation.currentDelayMs)
    }

    @Test
    fun evaluateProbePicksBestHealthyAlternativeWhenCurrentNodeFails() {
        val evaluation = NodeAutoFailoverPolicy.evaluateProbe(
            currentTag = "node-a",
            urlTestResults = mapOf("node-a" to 0, "node-b" to 95, "node-c" to 140)
        )

        assertEquals(NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE, evaluation.outcome)
        assertEquals("node-b", evaluation.alternativeTag)
        assertEquals(95, evaluation.alternativeDelayMs)
    }

    @Test
    fun evaluateProbeTreatsAllFailingCandidatesAsNetworkFailure() {
        val evaluation = NodeAutoFailoverPolicy.evaluateProbe(
            currentTag = "node-a",
            urlTestResults = mapOf("node-a" to 0, "node-b" to 0)
        )

        assertEquals(NodeAutoFailoverPolicy.ProbeOutcome.NETWORK_FAILURE, evaluation.outcome)
        assertNull(evaluation.alternativeTag)
    }

    @Test
    fun evaluateProbeSkipsQuarantinedAlternative() {
        val evaluation = NodeAutoFailoverPolicy.evaluateProbe(
            currentTag = "node-a",
            urlTestResults = mapOf("node-a" to 0, "node-b" to 80, "node-c" to 95),
            quarantinedTags = setOf("node-b")
        )

        assertEquals(NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE, evaluation.outcome)
        assertEquals("node-c", evaluation.alternativeTag)
    }

    @Test
    fun registerFailoverAttemptResetsExpiredBudgetWindow() {
        val state = NodeAutoFailoverPolicy.registerFailoverAttempt(
            windowStartAtMs = 1_000L,
            count = 3,
            nowAtMs = 700_001L,
            budgetWindowMs = 60_000L
        )

        assertEquals(700_001L, state.windowStartAtMs)
        assertEquals(1, state.count)
    }

    @Test
    fun quarantineCodecRoundTripsAndDropsInvalidEntries() {
        val encoded = NodeAutoFailoverPolicy.encodeQuarantine(
            listOf(
                NodeAutoFailoverPolicy.QuarantinedNode("node-a", 123L),
                NodeAutoFailoverPolicy.QuarantinedNode("node-b", 456L)
            )
        )

        val decoded = NodeAutoFailoverPolicy.decodeQuarantine("$encoded;broken-entry;node-c|bad")

        assertEquals(2, decoded.size)
        assertEquals("node-a", decoded[0].tag)
        assertEquals(123L, decoded[0].expiresAtMs)
        assertEquals("node-b", decoded[1].tag)
        assertEquals(456L, decoded[1].expiresAtMs)
    }

    @Test
    fun cleanupExpiredQuarantineRemovesExpiredAndDuplicateNormalizedTags() {
        val cleaned = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(
            records = listOf(
                NodeAutoFailoverPolicy.QuarantinedNode(" node-a ", 200L),
                NodeAutoFailoverPolicy.QuarantinedNode("node-a", 300L),
                NodeAutoFailoverPolicy.QuarantinedNode("node-b", 50L)
            ),
            nowAtMs = 100L
        )

        assertEquals(1, cleaned.size)
        assertEquals("node-a", cleaned.first().tag)
        assertEquals(300L, cleaned.first().expiresAtMs)
    }

    @Test
    fun dnsFastFailoverMayBypassCooldownButHonorsBudget() {
        val context = validTriggerContext().copy(
            lastAutoFailoverAtMs = 80_000L,
            nowAtMs = 100_000L,
            budgetWindowStartAtMs = 90_000L,
            budgetCount = 1
        )

        assertTrue(NodeAutoFailoverPolicy.shouldStartProbe(context, trigger = "dns_remote_timeout"))
    }

    @Test
    fun dnsFastFailoverStillHonorsBudget() {
        val context = validTriggerContext().copy(
            budgetWindowStartAtMs = 90_000L,
            budgetCount = NodeAutoFailoverPolicy.AUTO_FAILOVER_BUDGET_MAX_COUNT
        )

        assertFalse(NodeAutoFailoverPolicy.shouldStartProbe(context, trigger = "dns_remote_timeout"))
    }
}
