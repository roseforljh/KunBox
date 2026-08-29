package com.kunk.singbox.service.root

import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAutoFailoverControllerTest {
    @Test
    fun transportFailureUsesFirstOutboundInConnectionChain() {
        val tracker = RootRuntimeFailureTracker()

        repeat(2) { index ->
            val id = 100 + index
            tracker.observe("INFO [$id 0ms] outbound/hysteria2[bad-node]: outbound connection", index * 100L)
            assertNull(
                tracker.observe(
                    "time ERROR[$id 5s] remote error using outbound/direct[direct]: i/o timeout",
                    index * 100L + 50L
                )
            )
        }
        tracker.observe("INFO [102 0ms] outbound/hysteria2[bad-node]: outbound connection", 200L)

        assertEquals(
            RootFailoverTarget("bad-node"),
            tracker.observe("time ERROR[102 5s] outbound/direct[direct]: i/o timeout", 250L)
        )
    }

    @Test
    fun detectsCurrentSingBoxNoRecentActivityTimeoutFormat() {
        val tracker = RootRuntimeFailureTracker()

        repeat(3) { index ->
            val id = 200 + index
            tracker.observe(
                "INFO[0010] [$id 0ms] outbound/hysteria2[bad-node]: outbound connection",
                index * 100L
            )
            val failed = tracker.observe(
                "ERROR[0015] [$id 5.3s] connection: timeout: no recent network activity",
                index * 100L + 50L
            )
            if (index < 2) assertNull(failed) else assertEquals(RootFailoverTarget("bad-node"), failed)
        }
    }

    @Test
    fun threeSequentialFiveSecondTimeoutsStillTriggerFailover() {
        val tracker = RootRuntimeFailureTracker()

        repeat(3) { index ->
            val id = 300 + index
            val startedAt = index * 6_000L
            tracker.observe(
                "INFO[0010] [$id 0ms] outbound/hysteria2[bad-node]: outbound connection",
                startedAt
            )
            val failed = tracker.observe(
                "ERROR[0015] [$id 5.3s] connection: timeout: no recent network activity",
                startedAt + 5_300L
            )
            if (index < 2) assertNull(failed) else assertEquals(RootFailoverTarget("bad-node"), failed)
        }
    }

    @Test
    fun resolvesFailingNestedSelectorInsteadOfMainProxy() {
        val outbounds = listOf(
            Outbound(type = "selector", tag = "PROXY", outbounds = listOf("main", "bad-node")),
            Outbound(type = "selector", tag = "P:鹰", outbounds = listOf("bad-node", "backup")),
            Outbound(type = "vless", tag = "main"),
            Outbound(type = "hysteria2", tag = "bad-node"),
            Outbound(type = "vless", tag = "backup")
        )
        val selections = mapOf("PROXY" to "main", "P:鹰" to "bad-node")

        val group = requireNotNull(
            RootFailoverGroups.resolve(outbounds, "bad-node", selectedTag = selections::get)
        )
        assertEquals("P:鹰", group.tag)
        assertEquals("bad-node", group.currentSelectionTag)
        assertEquals(listOf(RootFailoverCandidate("backup", "backup")), group.candidates)
    }

    @Test
    fun transportFailureKeepsTheActualRoutingGroup() {
        val tracker = RootRuntimeFailureTracker(minimumFailures = 1)
        tracker.observe("DEBUG [401 1ms] router: match[8] => route(F:youtube)", 0L)
        tracker.observe("INFO [401 2ms] outbound/hysteria2[bad-node]: outbound connection", 1L)

        assertEquals(
            RootFailoverTarget("bad-node", "F:youtube"),
            tracker.observe("ERROR [401 5s] timeout: no recent network activity", 5_000L)
        )
    }

    @Test
    fun remoteDnsFailureResolvesItsOwnSelectorInsteadOfMainProxy() {
        val config = SingBoxConfig(
            dns = DnsConfig(
                servers = listOf(
                    DnsServer(tag = "dns-google", type = "https", server = "1.1.1.1", detour = "P:鹰")
                )
            ),
            outbounds = listOf(
                Outbound(type = "selector", tag = "PROXY", outbounds = listOf("main")),
                Outbound(type = "selector", tag = "P:鹰", outbounds = listOf("bad-node", "backup")),
                Outbound(type = "vless", tag = "main"),
                Outbound(type = "hysteria2", tag = "bad-node"),
                Outbound(type = "vless", tag = "backup")
            )
        )
        val selections = mapOf("PROXY" to "main", "P:鹰" to "bad-node")

        assertEquals(
            RootFailoverTarget("bad-node", "P:鹰", RootFailoverSignalSource.DNS),
            RootFailoverGroups.resolveDnsFailureTarget(config, "dns-google", selections::get)
        )
    }

    @Test
    fun genericTransportTrackerDoesNotStealRemoteDnsFailure() {
        val tracker = RootRuntimeFailureTracker(minimumFailures = 1)
        tracker.observe("INFO [501 1ms] outbound/hysteria2[bad-node]: outbound connection", 1L)

        val target = tracker.observe(
            "ERROR [501 10s] dns: exchange failed for example.com. IN A: context deadline exceeded",
            10_000L
        )

        assertNull(target)
    }

    @Test
    fun resolveAllIncludesEverySelectorUsingTheFailedConcreteNode() {
        val outbounds = listOf(
            Outbound(type = "selector", tag = "F:google", outbounds = listOf("bad-node", "backup-a")),
            Outbound(type = "selector", tag = "P:鹰", outbounds = listOf("bad-node", "backup-b")),
            Outbound(type = "selector", tag = "PROXY", outbounds = listOf("main", "bad-node")),
            Outbound(type = "hysteria2", tag = "bad-node"),
            Outbound(type = "vless", tag = "backup-a"),
            Outbound(type = "vless", tag = "backup-b"),
            Outbound(type = "vless", tag = "main")
        )
        val selected = mapOf("F:google" to "bad-node", "P:鹰" to "bad-node", "PROXY" to "main")

        val groups = RootFailoverGroups.resolveAll(
            outbounds = outbounds,
            failedTag = "bad-node",
            preferredGroupTag = "P:鹰",
            selectedTag = selected::get,
            resolvedTag = selected::get
        )

        assertEquals(listOf("P:鹰", "F:google"), groups.map(RootFailoverGroup::tag))
        assertEquals("backup-b", groups.first().candidates.single().selectTag)
    }

    @Test
    fun nestedSelectorUsesDirectSelectTagAndResolvedProbeTag() {
        val outbounds = listOf(
            Outbound(type = "selector", tag = "PROXY", outbounds = listOf("P:鹰", "main")),
            Outbound(type = "selector", tag = "P:鹰", outbounds = listOf("bad-node", "backup")),
            Outbound(type = "hysteria2", tag = "bad-node"),
            Outbound(type = "vless", tag = "backup"),
            Outbound(type = "vless", tag = "main")
        )
        val selected = mapOf("PROXY" to "P:鹰", "P:鹰" to "bad-node")
        val resolved = mapOf("PROXY" to "bad-node", "P:鹰" to "bad-node")

        val groups = RootFailoverGroups.resolveAll(
            outbounds = outbounds,
            failedTag = "bad-node",
            selectedTag = selected::get,
            resolvedTag = resolved::get
        )

        assertEquals(listOf("P:鹰", "PROXY"), groups.map(RootFailoverGroup::tag))
        assertEquals(RootFailoverCandidate("backup", "backup"), groups.first().candidates.single())
        assertEquals(RootFailoverCandidate("main", "main"), groups.last().candidates.single())
    }

    @Test
    fun urlTestStillResolvingToFailedNodeIsNotAUsableCandidate() {
        val outbounds = listOf(
            Outbound(type = "selector", tag = "F:test", outbounds = listOf("bad-node", "auto")),
            Outbound(type = "urltest", tag = "auto", outbounds = listOf("bad-node", "backup")),
            Outbound(type = "hysteria2", tag = "bad-node"),
            Outbound(type = "vless", tag = "backup")
        )
        val selected = mapOf("F:test" to "bad-node", "auto" to "bad-node")

        val group = RootFailoverGroups.resolveAll(
            outbounds = outbounds,
            failedTag = "bad-node",
            selectedTag = selected::get,
            resolvedTag = selected::get
        ).single()

        assertTrue(group.candidates.isEmpty())
    }

    @Test
    fun expectedTargetedTeardownDoesNotBecomeANewFailureSignal() {
        val tracker = RootRuntimeFailureTracker(minimumFailures = 1)
        tracker.observe("INFO [601 1ms] outbound/hysteria2[bad-node]: outbound connection", 1L)
        tracker.expectTeardown("bad-node", nowMs = 2L, windowMs = 5_000L)

        val target = tracker.observe(
            "ERROR [601 5s] quic: transport closed: use of closed network connection",
            3L
        )

        assertNull(target)
    }

    @Test
    fun fullClosedTransportBurstFromFailoverNeverReentersFailureThreshold() {
        val tracker = RootRuntimeFailureTracker(minimumFailures = 3)
        val connectionIds = (1..98).map { it.toString() }.toSet()
        connectionIds.forEach { connectionId ->
            tracker.observe(
                "INFO [$connectionId 1ms] outbound/hysteria2[bad-node]: outbound connection",
                1L
            )
        }
        tracker.expectTeardown("bad-node", nowMs = 2L, windowMs = 5_000L)

        connectionIds.forEach { connectionId ->
            assertNull(
                tracker.observe(
                    "ERROR [$connectionId 5s] quic: transport closed: use of closed network connection",
                    3L
                )
            )
        }
    }

    @Test
    fun teardownSuppressionExpiresAndRealFailuresCountAgain() {
        val tracker = RootRuntimeFailureTracker(minimumFailures = 1)
        tracker.observe("INFO [701 1ms] outbound/hysteria2[bad-node]: outbound connection", 1L)
        tracker.expectTeardown("bad-node", nowMs = 2L, windowMs = 10L)

        val target = tracker.observe(
            "ERROR [701 5s] quic: transport closed: use of closed network connection",
            13L
        )

        assertEquals(RootFailoverTarget("bad-node"), target)
    }

    @Test
    fun incidentGateDeduplicatesSameNodeWithoutBlockingDifferentNode() {
        val gate = RootFailoverIncidentGate(cooldownMs = 100L, budgetWindowMs = 1_000L, budgetMaxCount = 3)

        assertEquals(RootFailoverPermit.ACQUIRED, gate.acquire("node-a", 1_000L))
        assertEquals(RootFailoverPermit.IN_FLIGHT, gate.acquire("node-a", 1_001L))
        assertEquals(RootFailoverPermit.ACQUIRED, gate.acquire("node-b", 1_001L))
        assertEquals(1, gate.complete("node-a", 1_010L))
        assertEquals(RootFailoverPermit.COOLDOWN, gate.acquire("node-a", 1_050L))
        assertEquals(RootFailoverPermit.ACQUIRED, gate.acquire("node-a", 1_111L))
    }

    @Test
    fun dnsTargetWinsWhileConnectionBoundTransportWinsOverGroupLessActiveProbe() {
        val transport = RootFailoverTarget("node-a", "P:鹰", RootFailoverSignalSource.TRANSPORT)
        val dns = RootFailoverTarget("node-a", "F:dns", RootFailoverSignalSource.DNS)
        val activeProbe = RootFailoverTarget("node-a", source = RootFailoverSignalSource.ACTIVE_PROBE)

        assertEquals(dns, chooseRootFailoverTarget(transport, dns))
        assertEquals(transport, chooseRootFailoverTarget(transport, activeProbe))
        assertEquals(activeProbe, chooseRootFailoverTarget(null, activeProbe))
    }

    @Test
    fun plannerProbesSharedLeafOnceAndBuildsOnePlanPerAffectedGroup() {
        val shared = RootFailoverCandidate("shared-group", "healthy-node")
        val groups = listOf(
            failoverGroup("F:first", "bad-node", listOf(shared)),
            failoverGroup("P:second", "bad-node", listOf(shared))
        )

        val plans = RootFailoverPlanner.build(
            groups = groups,
            delaysByProbeTag = mapOf("healthy-node" to 80),
            quarantinedTags = emptySet()
        )

        assertEquals(setOf("healthy-node"), RootFailoverPlanner.probeTags(groups))
        assertEquals(listOf("F:first", "P:second"), plans.map { it.group.tag })
        assertTrue(plans.all { it.candidate == shared && it.delayMs == 80 })
    }

    @Test
    fun plannerNeverSelectsCandidateWhoseResolvedLeafIsQuarantined() {
        val group = failoverGroup(
            tag = "F:first",
            current = "bad-node",
            candidates = listOf(RootFailoverCandidate("nested", "quarantined-node"))
        )

        val plans = RootFailoverPlanner.build(
            groups = listOf(group),
            delaysByProbeTag = mapOf("quarantined-node" to 50),
            quarantinedTags = setOf("quarantined-node")
        )

        assertTrue(plans.isEmpty())
    }

    @Test
    fun incidentBudgetIsPerNode() {
        val gate = RootFailoverIncidentGate(cooldownMs = 1L, budgetWindowMs = 1_000L, budgetMaxCount = 1)
        assertEquals(RootFailoverPermit.ACQUIRED, gate.acquire("node-a", 1_000L))
        gate.complete("node-a", 1_001L)

        assertEquals(RootFailoverPermit.BUDGET_EXHAUSTED, gate.acquire("node-a", 1_002L))
        assertEquals(RootFailoverPermit.ACQUIRED, gate.acquire("node-b", 1_002L))
    }

    @Test
    fun runtimeFenceRejectsStoppedMissingAndStaleRootSessions() {
        assertTrue(isRootFailoverRuntimeCurrent(7L, 7L, true, false, true))
        assertFalse(isRootFailoverRuntimeCurrent(7L, 8L, true, false, true))
        assertFalse(isRootFailoverRuntimeCurrent(7L, 7L, false, false, true))
        assertFalse(isRootFailoverRuntimeCurrent(7L, 7L, true, true, true))
        assertFalse(isRootFailoverRuntimeCurrent(7L, 7L, true, false, false))
    }

    @Test
    fun missingSelectorStateIsNeverClassifiedAsHealed() {
        assertEquals(
            RootFailoverGroupRuntimeState.UNAVAILABLE,
            classifyRootFailoverGroupRuntime(null, "bad-node")
        )
        assertEquals(
            RootFailoverGroupRuntimeState.NEEDS_SWITCH,
            classifyRootFailoverGroupRuntime("bad-node", "bad-node")
        )
        assertEquals(
            RootFailoverGroupRuntimeState.HEALED,
            classifyRootFailoverGroupRuntime("healthy-node", "bad-node")
        )
    }

    @Test
    fun targetedDrainWaitsUntilEveryAffectedGroupHasConverged() {
        assertTrue(shouldDrainRootFailoverConnections(failedGroupCount = 0))
        assertFalse(shouldDrainRootFailoverConnections(failedGroupCount = 1))
    }

    @Test
    fun automaticFailoverUsesTargetedDrainWithoutGlobalNetworkReset() {
        val source = java.io.File(
            "src/main/java/com/kunk/singbox/service/root/RootAutoFailoverController.kt"
        ).readText()
        val failoverBody = source.substringAfter("private suspend fun failover(")

        assertTrue(failoverBody.contains("closeConnectionsById"))
        assertFalse(failoverBody.contains("commandManager.closeConnections()"))
        assertFalse(failoverBody.contains("rootService()?.resetNetwork()"))
    }

    private fun failoverGroup(
        tag: String,
        current: String,
        candidates: List<RootFailoverCandidate>
    ): RootFailoverGroup = RootFailoverGroup(
        tag = tag,
        currentSelectionTag = current,
        currentResolvedTag = current,
        selectableTags = listOf(current) + candidates.map(RootFailoverCandidate::selectTag),
        candidates = candidates,
        dependencyDepth = 0
    )

    @Test
    fun rootManualSwitchConvergesOldConnectionsAndNetwork() {
        val source = java.io.File(
            "src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt"
        ).readText()
        val successBranch = source.substringAfter("is SelectorManager.SwitchResult.Success ->")
            .substringBefore("is SelectorManager.SwitchResult.NeedRestart")

        assertTrue(successBranch.contains("commandManager.closeConnections()"))
        assertTrue(successBranch.contains("rootConnection.service?.resetNetwork()"))
    }
}
