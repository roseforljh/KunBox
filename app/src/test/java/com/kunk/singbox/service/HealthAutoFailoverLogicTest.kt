package com.kunk.singbox.service

import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthAutoFailoverLogicTest {

    @Test
    fun dnsAndActiveProbeFastPathsUseOneSecondRetry() {
        assertEquals(1_000L, SingBoxService.resolveAutoFailoverRetryDelayMsForTest("dns_remote_timeout"))
        assertEquals(1_000L, SingBoxService.resolveAutoFailoverRetryDelayMsForTest("active_probe_failed"))
        assertEquals(2_500L, SingBoxService.resolveAutoFailoverRetryDelayMsForTest("traffic_stall:3"))
    }

    @Test
    fun fastPathLimitsCandidateProbeCountAndKeepsCurrent() {
        val selected = SingBoxService.selectAutoFailoverProbeCandidatesForTest(
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
        assertTrue(SingBoxService.isHealthFastPathTriggerForTest("dns_remote_timeout"))
        assertTrue(SingBoxService.isHealthFastPathTriggerForTest("active_probe_failed"))
        assertFalse(SingBoxService.isHealthFastPathTriggerForTest("traffic_stall:3"))
    }

    @Test
    fun fastPathClosesConnectionsAfterSwitch() {
        assertTrue(SingBoxService.shouldResetAfterAutoFailoverForTest("dns_remote_timeout"))
        assertTrue(SingBoxService.shouldResetAfterAutoFailoverForTest("active_probe_failed"))
        assertFalse(SingBoxService.shouldResetAfterAutoFailoverForTest("traffic_stall:3"))
    }

    @Test
    fun fastPathCandidateLatencyUsesBoundedTimeoutAndParallelism() {
        assertEquals(
            1_200,
            SingBoxService.resolveAutoFailoverCandidateTimeoutMsForTest(
                trigger = "active_probe_failed",
                userTimeoutMs = 5_000
            )
        )
        assertEquals(
            3,
            SingBoxService.resolveAutoFailoverCandidateConcurrencyForTest(
                trigger = "active_probe_failed",
                userConcurrency = 1,
                candidateCount = 5
            )
        )
        assertEquals(
            1,
            SingBoxService.resolveAutoFailoverCandidateConcurrencyForTest(
                trigger = "active_probe_failed",
                userConcurrency = 1,
                candidateCount = 1
            )
        )
        assertEquals(
            5_000,
            SingBoxService.resolveAutoFailoverCandidateTimeoutMsForTest(
                trigger = "traffic_stall:3",
                userTimeoutMs = 5_000
            )
        )
    }

    @Test
    fun fastPathCandidateLatencyRunsInSingleBatchWave() {
        assertEquals(
            1,
            SingBoxService.resolveAutoFailoverCandidateProbeWavesForTest(
                trigger = "active_probe_failed",
                userConcurrency = 1,
                candidateCount = 3
            )
        )
        assertEquals(
            3,
            SingBoxService.resolveAutoFailoverCandidateProbeWavesForTest(
                trigger = "traffic_stall:3",
                userConcurrency = 1,
                candidateCount = 3
            )
        )
    }

    @Test
    fun activeProbeDoesNotRunForForegroundOnlyWithoutRecentTraffic() {
        assertFalse(
            SingBoxService.shouldRunActiveHealthProbeForSignalsForTest(
                isAppInForeground = true,
                lastMeaningfulTrafficAtMs = 0L,
                nowAtMs = 100_000L
            )
        )
        assertTrue(
            SingBoxService.shouldRunActiveHealthProbeForSignalsForTest(
                isAppInForeground = false,
                lastMeaningfulTrafficAtMs = 99_000L,
                nowAtMs = 100_000L
            )
        )
    }

    @Test
    fun activeProbeTrafficDoesNotRefreshMeaningfulTrafficByItself() {
        assertFalse(
            SingBoxService.shouldRecordMeaningfulTrafficForAutoFailoverForTest(
                totalSpeed = 8_000L,
                nowAtMs = 10_000L,
                activeProbeTrafficIgnoreUntilMs = 12_000L
            )
        )
        assertTrue(
            SingBoxService.shouldRecordMeaningfulTrafficForAutoFailoverForTest(
                totalSpeed = 128_000L,
                nowAtMs = 10_000L,
                activeProbeTrafficIgnoreUntilMs = 12_000L
            )
        )
        assertTrue(
            SingBoxService.shouldRecordMeaningfulTrafficForAutoFailoverForTest(
                totalSpeed = 8_000L,
                nowAtMs = 13_000L,
                activeProbeTrafficIgnoreUntilMs = 12_000L
            )
        )
    }

    @Test
    fun healthFastPathHasNineSecondTotalBudget() {
        assertEquals(9_000L, SingBoxService.resolveHealthFastFailoverTotalTimeoutMsForTest())
    }

    @Test
    fun activeProbeUsesMobileSafeTimeout() {
        assertEquals(1_500L, SingBoxService.resolveActiveHealthProbeTimeoutMsForTest())
    }

    @Test
    fun healthFastPathBudgetCoversTwoProbeRoundsBeforeTotalBudget() {
        val perRoundMs = SingBoxService.resolveAutoFailoverPortReadyTimeoutMsForTest("active_probe_failed") +
            SingBoxService.resolveAutoFailoverCandidateTimeoutMsForTest("active_probe_failed", 5_000) +
            SingBoxService.resolveActiveHealthProbeTimeoutMsForTest()
        val twoRoundProbeMs = perRoundMs * 2 +
            SingBoxService.resolveAutoFailoverRetryDelayMsForTest("active_probe_failed")

        assertTrue(twoRoundProbeMs < SingBoxService.resolveHealthFastFailoverTotalTimeoutMsForTest())
    }

    @Test
    fun activeProbeFailureSummaryIncludesDiagnosis() {
        val remoteDnsSummary = SingBoxService.buildActiveProbeFailureSummaryForTest(
            googleProbeOk = true,
            cloudflareProbeOk = true,
            metaProbeOk = false,
            coreAvailable = true
        )
        val nodeSummary = SingBoxService.buildActiveProbeFailureSummaryForTest(
            googleProbeOk = false,
            cloudflareProbeOk = false,
            metaProbeOk = false,
            coreAvailable = true
        )
        val appServiceSummary = SingBoxService.buildActiveProbeFailureSummaryForTest(
            googleProbeOk = false,
            cloudflareProbeOk = false,
            metaProbeOk = false,
            coreAvailable = false
        )

        assertTrue(remoteDnsSummary.contains("diagnosis=remote_dns_timeout"))
        assertTrue(nodeSummary.contains("diagnosis=node_unreachable"))
        assertTrue(appServiceSummary.contains("diagnosis=app_service_unavailable"))
        assertTrue(remoteDnsSummary.contains("dns_channel=remote"))
    }

    @Test
    fun activeProbeRequiresAtLeastTwoFailedTargetsBeforeMainFailover() {
        assertFalse(
            SingBoxService.shouldTreatActiveProbeAsNodeFailure(
                googleProbeOk = true,
                cloudflareProbeOk = true,
                metaProbeOk = false
            )
        )
        assertFalse(
            SingBoxService.shouldTreatActiveProbeAsNodeFailure(
                googleProbeOk = true,
                cloudflareProbeOk = false,
                metaProbeOk = true
            )
        )
        assertTrue(
            SingBoxService.shouldTreatActiveProbeAsNodeFailure(
                googleProbeOk = false,
                cloudflareProbeOk = false,
                metaProbeOk = true
            )
        )
        assertTrue(
            SingBoxService.shouldTreatActiveProbeAsNodeFailure(
                googleProbeOk = false,
                cloudflareProbeOk = true,
                metaProbeOk = false
            )
        )
    }

    @Test
    fun singleNodeRouteFailureDetectionOnlyMatchesConcreteNonCurrentNodeDetour() {
        val config = routeFailureConfig()

        assertEquals(
            "node-b",
            SingBoxService.resolveSingleNodeRouteFailureTagForTest(
                dnsServerTag = "dns-remote-node-b",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertNull(
            SingBoxService.resolveSingleNodeRouteFailureTagForTest(
                dnsServerTag = "dns-remote-profile",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertNull(
            SingBoxService.resolveSingleNodeRouteFailureTagForTest(
                dnsServerTag = "dns-remote-current",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertNull(
            SingBoxService.resolveSingleNodeRouteFailureTagForTest(
                dnsServerTag = "dns-remote-current",
                currentProxyTag = "node-b",
                config = config
            )
        )
        assertNull(
            SingBoxService.resolveSingleNodeRouteFailureTagForTest(
                dnsServerTag = "dns-remote-nested-current",
                currentProxyTag = "P:HK",
                config = config
            )
        )
        assertNull(
            SingBoxService.resolveSingleNodeRouteFailureTagForTest(
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
            SingBoxService.shouldSubmitMainAutoFailoverForDnsSignalForTest(
                dnsServerTag = "dns-remote-current",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertTrue(
            SingBoxService.shouldSubmitMainAutoFailoverForDnsSignalForTest(
                dnsServerTag = "dns-remote-nested-current",
                currentProxyTag = "P:HK",
                config = config
            )
        )
        assertFalse(
            SingBoxService.shouldSubmitMainAutoFailoverForDnsSignalForTest(
                dnsServerTag = "dns-remote-node-b",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertFalse(
            SingBoxService.shouldSubmitMainAutoFailoverForDnsSignalForTest(
                dnsServerTag = "dns-remote-profile",
                currentProxyTag = "node-a",
                config = config
            )
        )
        assertTrue(
            SingBoxService.shouldSubmitMainAutoFailoverForDnsSignalForTest(
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
