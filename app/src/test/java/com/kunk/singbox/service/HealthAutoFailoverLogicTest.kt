package com.kunk.singbox.service

import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthAutoFailoverLogicTest {

    @Test
    fun autoFailoverUsesSharedBoundedDispatcher() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt").readText()

        assertFalse(source.contains("newSingleThreadExecutor"))
        assertTrue(source.contains("Dispatchers.IO.limitedParallelism(1)"))
    }

    @Test
    fun healthMonitoringDoesNotSchedulePeriodicExternalRequests() {
        val serviceSource = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt").readText()
        val wrapperSource = File("src/main/java/com/kunk/singbox/core/BoxWrapperManager.kt").readText()

        assertFalse(serviceSource.contains("runActiveHealthProbeTick"))
        assertFalse(serviceSource.contains("ACTIVE_HEALTH_PROBE_CANARY_INTERVAL_MS"))
        assertFalse(wrapperSource.contains("connect.facebook.net"))
        assertFalse(wrapperSource.contains("www.cloudflare.com/cdn-cgi/trace"))
    }

    @Test
    fun dnsAndActiveProbeFastPathsUseOneSecondRetry() {
        assertEquals(1_000L, SingBoxService.resolveAutoFailoverRetryDelayMs("dns_remote_timeout"))
        assertEquals(1_000L, SingBoxService.resolveAutoFailoverRetryDelayMs("active_probe_failed"))
        assertEquals(2_500L, SingBoxService.resolveAutoFailoverRetryDelayMs("traffic_stall:3"))
    }

    @Test
    fun fastPathLimitsCandidateProbeCountAndKeepsCurrent() {
        val selected = SingBoxService.selectAutoFailoverProbeCandidates(
            currentTag = "current",
            cachedBackupTag = "backup",
            candidateTags = listOf("current", "a", "backup", "b", "c", "d"),
            trigger = "active_probe_failed"
        )

        assertTrue(selected.contains("current"))
        assertTrue(selected.contains("backup"))
        assertTrue(selected.size <= 3)
    }

    @Test
    fun healthSignalTriggerNamesAreRecognized() {
        assertTrue(SingBoxService.isHealthFastPathTrigger("dns_remote_timeout"))
        assertTrue(SingBoxService.isHealthFastPathTrigger("active_probe_failed"))
        assertFalse(SingBoxService.isHealthFastPathTrigger("traffic_stall:3"))
    }

    @Test
    fun fastPathClosesConnectionsAfterSwitch() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt").readText()
        val start = source.indexOf("protected suspend fun performAutoFailoverSwitch")
        val end = source.indexOf("protected fun loadActiveAutoFailoverQuarantine", start)
        val body = source.substring(start, end)

        assertTrue(body.contains("commandManager.closeConnections()"))
        assertTrue(body.contains("BoxWrapperManager.resetNetwork()"))
        assertTrue(body.contains("evaluateAutoFailoverLiveCheck"))
        assertTrue(body.contains("Auto failover liveCheck FAIL"))
        assertTrue(body.contains("Auto failover escalate resetNetwork"))
        assertTrue(body.contains("AUTO_FAILOVER_LIVE_OBSERVE_MS"))
        // 自动切换不得把运行态写回用户手选节点
        assertFalse(body.contains("syncActiveNodeFromProxySelection"))
    }

    @Test
    fun liveObserveWindowIsBounded() {
        assertEquals(2_000L, SingBoxService.AUTO_FAILOVER_LIVE_OBSERVE_MS)
    }

    @Test
    fun fastPathCandidateLatencyUsesBoundedTimeoutAndParallelism() {
        assertEquals(
            1_200,
            SingBoxService.resolveAutoFailoverCandidateTimeoutMs(
                trigger = "active_probe_failed",
                userTimeoutMs = 5_000
            )
        )
        assertEquals(
            3,
            SingBoxService.resolveAutoFailoverCandidateConcurrency(
                trigger = "active_probe_failed",
                userConcurrency = 1,
                candidateCount = 5
            )
        )
        assertEquals(
            1,
            SingBoxService.resolveAutoFailoverCandidateConcurrency(
                trigger = "active_probe_failed",
                userConcurrency = 1,
                candidateCount = 1
            )
        )
        assertEquals(
            5_000,
            SingBoxService.resolveAutoFailoverCandidateTimeoutMs(
                trigger = "traffic_stall:3",
                userTimeoutMs = 5_000
            )
        )
    }

    @Test
    fun fastPathCandidateLatencyRunsInSingleBatchWave() {
        assertEquals(
            1,
            SingBoxService.resolveAutoFailoverCandidateProbeWaves(
                trigger = "active_probe_failed",
                userConcurrency = 1,
                candidateCount = 3
            )
        )
        assertEquals(
            3,
            SingBoxService.resolveAutoFailoverCandidateProbeWaves(
                trigger = "traffic_stall:3",
                userConcurrency = 1,
                candidateCount = 3
            )
        )
    }

    @Test
    fun meaningfulTrafficUsesOnlyObservedApplicationTraffic() {
        assertFalse(
            SingBoxService.shouldRecordMeaningfulTrafficForAutoFailover(totalSpeed = 512L)
        )
        assertTrue(
            SingBoxService.shouldRecordMeaningfulTrafficForAutoFailover(totalSpeed = 8_000L)
        )
    }

    @Test
    fun healthFastPathHasNineSecondTotalBudget() {
        assertEquals(9_000L, SingBoxService.HEALTH_FAST_FAILOVER_TOTAL_TIMEOUT_MS)
    }

    @Test
    fun healthFastPathBudgetCoversSingleNativeLatencyRoundBeforeTotalBudget() {
        // dns/active 快路径：一轮离线候选 + 立即切换，不再二次确认
        val perRoundMs = SingBoxService.resolveAutoFailoverPortReadyTimeoutMs("active_probe_failed") +
            SingBoxService.resolveAutoFailoverCandidateTimeoutMs("active_probe_failed", 5_000)

        assertTrue(perRoundMs < SingBoxService.HEALTH_FAST_FAILOVER_TOTAL_TIMEOUT_MS)
    }

    @Test
    fun dnsFastPathSwitchesOnFirstProbeWithoutSecondRound() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt").readText()
        val bodyStart = source.indexOf("private suspend fun runAutoFailoverProbeSequenceBody")
        val bodyEnd = source.indexOf("protected suspend fun handleSecondAutoFailoverProbe", bodyStart)
        val body = source.substring(bodyStart, bodyEnd)
        val roundStart = source.indexOf("protected suspend fun runAutoFailoverProbeRound")
        val roundEnd = source.indexOf("protected suspend fun testGroupCandidatesLatency(groupTag: String)", roundStart)
        val roundBody = source.substring(roundStart, roundEnd)

        assertTrue(body.contains("isHealthFastPathTrigger(trigger)"))
        assertTrue(body.contains("Health failover fast switch"))
        assertTrue(body.contains("performAutoFailoverSwitch"))
        assertTrue(roundBody.contains("treatCurrentAsFailed"))
        assertTrue(roundBody.contains("resolveAutoFailoverFallbackDelays"))
        assertTrue(roundBody.contains("fallback_saved"))
    }

    @Test
    fun liveCheckNoLongerHardDependsOnOfflineDelay() {
        val source = File("src/main/java/com/kunk/singbox/service/LiveNodeHealthChecker.kt").readText()
        assertFalse(source.contains("offline_delay_failed"))
        assertTrue(source.contains("live_remote_dns_timeout"))
        assertTrue(source.contains("selected_mismatch"))
    }

    @Test
    fun singleNodeRouteFailureDetectionOnlyMatchesConcreteNonCurrentNodeDetour() {
        val config = routeFailureConfig()

        assertEquals(
            "node-b",
            SingBoxService.resolveSingleNodeRouteFailureTag(
                dnsServerTag = "dns-remote-node-b",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertNull(
            SingBoxService.resolveSingleNodeRouteFailureTag(
                dnsServerTag = "dns-remote-profile",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertNull(
            SingBoxService.resolveSingleNodeRouteFailureTag(
                dnsServerTag = "dns-remote-current",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertNull(
            SingBoxService.resolveSingleNodeRouteFailureTag(
                dnsServerTag = "dns-remote-current",
                currentProxyTag = "node-b",
                config = config
            )
        )
        assertNull(
            SingBoxService.resolveSingleNodeRouteFailureTag(
                dnsServerTag = "dns-remote-nested-current",
                currentProxyTag = "P:HK",
                config = config
            )
        )
        assertNull(
            SingBoxService.resolveSingleNodeRouteFailureTag(
                dnsServerTag = "dns-remote-node-b",
                currentProxyTag = null,
                config = config
            )
        )
    }

    @Test
    fun mainAutoFailoverOnlyUsesCurrentProxyDnsFailures() {
        val config = routeFailureConfig()

        assertTrue(
            SingBoxService.shouldSubmitMainAutoFailoverForDnsSignal(
                dnsServerTag = "dns-remote-current",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertTrue(
            SingBoxService.shouldSubmitMainAutoFailoverForDnsSignal(
                dnsServerTag = "dns-remote-nested-current",
                currentProxyTag = "P:HK",
                config = config
            )
        )
        assertFalse(
            SingBoxService.shouldSubmitMainAutoFailoverForDnsSignal(
                dnsServerTag = "dns-remote-node-b",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertFalse(
            SingBoxService.shouldSubmitMainAutoFailoverForDnsSignal(
                dnsServerTag = "dns-remote-profile",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertTrue(
            SingBoxService.shouldSubmitMainAutoFailoverForDnsSignal(
                dnsServerTag = "dns-remote-unknown",
                currentProxyTag = "node-a",
                config = config
            )
        )
    }

    private fun routeFailureConfig(): SingBoxConfig {
        return SingBoxConfig(
            dns = DnsConfig(
                servers = listOf(
                    DnsServer(tag = "dns-remote-node-b", detour = "node-b"),
                    DnsServer(tag = "dns-remote-profile", detour = "P:HK"),
                    DnsServer(tag = "dns-remote-current", detour = "node-a"),
                    DnsServer(tag = "dns-remote-nested-current", detour = "node-a")
                )
            ),
            outbounds = listOf(
                Outbound(type = "vless", tag = "node-a"),
                Outbound(type = "vless", tag = "node-b"),
                Outbound(
                    type = "selector",
                    tag = "P:HK",
                    outbounds = listOf("P:HK#AUTO", "PROXY"),
                    default = "P:HK#AUTO"
                ),
                Outbound(type = "urltest", tag = "P:HK#AUTO", outbounds = listOf("node-a", "node-b")),
                Outbound(type = "selector", tag = "PROXY", outbounds = listOf("node-a", "node-b"))
            )
        )
    }
}
