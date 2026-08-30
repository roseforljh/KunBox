package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSignalAggregatorTest {

    @Test
    fun remoteHttpsRecordTimeoutsContributeToDnsFailureSignal() {
        val aggregator = HealthSignalAggregator(minDnsFailures = 3, minDnsQueryIds = 2)
        listOf("11", "12", "13").forEachIndexed { index, queryId ->
            aggregator.observeKernelLog(
                "DEBUG [$queryId 0ms] dns: match[1] => route(dns-remote-test)",
                nowMs = 1_000L + index
            )
        }

        assertNull(
            aggregator.observeKernelLog(
                "ERROR [11 10s] dns: exchange failed for a.example. IN HTTPS: context deadline exceeded",
                nowMs = 2_000L
            )
        )
        assertNull(
            aggregator.observeKernelLog(
                "ERROR [12 10s] dns: exchange failed for b.example. IN HTTPS: context deadline exceeded",
                nowMs = 2_001L
            )
        )
        val signal = aggregator.observeKernelLog(
            "ERROR [13 10s] dns: exchange failed for c.example. IN HTTPS: context deadline exceeded",
            nowMs = 2_002L
        )

        assertEquals(HealthSignalKind.REMOTE_DNS_TIMEOUT, signal?.kind)
        assertEquals("dns-remote-test", signal?.dnsServerTag)
    }

    @Test
    fun recentRemoteDnsFailureCountSurvivesSignalEmissionAndClear() {
        val aggregator = HealthSignalAggregator(
            dnsWindowMs = 7_000L,
            minDnsFailures = 3,
            minDnsQueryIds = 2
        )

        aggregator.observeKernelLog(
            "[11:36:45] DEBUG[1358] [74 0ms] dns: match[27] => route(dns-remote-hy2-aliyun-a6d30110)",
            nowMs = 1_000L
        )
        aggregator.observeKernelLog(
            "[11:36:45] DEBUG[1358] [31 0ms] dns: match[27] => route(dns-remote-hy2-aliyun-a6d30110)",
            nowMs = 1_100L
        )
        aggregator.observeKernelLog(
            "[11:36:49] ERROR[1362] [74 10.0s] dns: exchange failed for a.com. IN A: context deadline exceeded",
            nowMs = 2_000L
        )
        aggregator.observeKernelLog(
            "[11:36:49] ERROR[1362] [31 10.0s] dns: exchange failed for a.com. IN AAAA: context deadline exceeded",
            nowMs = 2_100L
        )
        aggregator.observeKernelLog(
            "[11:36:49] ERROR[1362] [74 10.0s] dns: exchange failed for b.com. IN A: context deadline exceeded",
            nowMs = 2_200L
        )

        // 信号已 emit 并消费 dnsFailures，但 live 观察计数仍保留
        assertEquals(3, aggregator.recentRemoteDnsFailureCount(nowMs = 2_300L, windowMs = 7_000L))

        aggregator.clearDnsFailures()
        assertEquals(0, aggregator.recentRemoteDnsFailureCount(nowMs = 2_400L, windowMs = 7_000L))
    }

    @Test
    fun emitsDnsSignalAfterThreeRemoteDnsFailuresAcrossTwoQueries() {
        val aggregator = HealthSignalAggregator(
            dnsWindowMs = 7_000L,
            minDnsFailures = 3,
            minDnsQueryIds = 2
        )

        aggregator.observeKernelLog(
            "[11:36:45] DEBUG[1358] [74 0ms] dns: match[27] query_type=[A AAAA] " +
                "rule_set=geosite-geolocation-!cn => route(dns-remote-hy2-aliyun-a6d30110)",
            nowMs = 1_000L
        )
        aggregator.observeKernelLog(
            "[11:36:45] DEBUG[1358] [31 0ms] dns: match[27] query_type=[A AAAA] " +
                "rule_set=geosite-geolocation-!cn => route(dns-remote-hy2-aliyun-a6d30110)",
            nowMs = 1_100L
        )

        assertNull(
            aggregator.observeKernelLog(
                "[11:36:49] ERROR[1362] [74 10.0s] dns: exchange failed for " +
                    "graph.facebook.com. IN AAAA: context deadline exceeded",
                nowMs = 2_000L
            )
        )
        assertNull(
            aggregator.observeKernelLog(
                "[11:36:49] ERROR[1362] [31 10.0s] dns: exchange failed for " +
                    "graph.facebook.com. IN A: context deadline exceeded",
                nowMs = 2_100L
            )
        )

        val signal = aggregator.observeKernelLog(
            "[11:36:49] ERROR[1362] [74 10.0s] dns: exchange failed for " +
                "b-graph.facebook.com. IN AAAA: context deadline exceeded",
            nowMs = 2_200L
        )

        assertEquals(HealthSignalKind.REMOTE_DNS_TIMEOUT, signal?.kind)
        assertEquals("dns-remote-hy2-aliyun-a6d30110", signal?.dnsServerTag)
        assertEquals(3, signal?.failureCount)
    }

    @Test
    fun emitsDnsSignalFromAnsiColoredKernelLogs() {
        val aggregator = HealthSignalAggregator(
            dnsWindowMs = 7_000L,
            minDnsFailures = 3,
            minDnsQueryIds = 2
        )

        aggregator.observeKernelLog(
            "[11:36:39] \u001B[37mDEBUG\u001B[0m[1352] [\u001B[38;5;122m78682474\u001B[0m 0ms] " +
                "dns: match[27] query_type=[A AAAA] rule_set=geosite-geolocation-!cn => " +
                "route(dns-remote-hy2-aliyun-a6d30110)",
            nowMs = 1_000L
        )
        aggregator.observeKernelLog(
            "[11:36:39] \u001B[37mDEBUG\u001B[0m[1352] [\u001B[38;5;37m4141352981\u001B[0m 0ms] " +
                "dns: match[27] query_type=[A AAAA] rule_set=geosite-geolocation-!cn => " +
                "route(dns-remote-hy2-aliyun-a6d30110)",
            nowMs = 1_100L
        )

        assertNull(
            aggregator.observeKernelLog(
                "[11:36:49] \u001B[31mERROR\u001B[0m[1362] [\u001B[38;5;122m78682474\u001B[0m 10.0s] " +
                    "dns: exchange failed for graph.facebook.com. IN AAAA: context deadline exceeded",
                nowMs = 2_000L
            )
        )
        assertNull(
            aggregator.observeKernelLog(
                "[11:36:49] \u001B[31mERROR\u001B[0m[1362] [\u001B[38;5;37m4141352981\u001B[0m 10.0s] " +
                    "dns: exchange failed for graph.facebook.com. IN A: context deadline exceeded",
                nowMs = 2_100L
            )
        )

        val signal = aggregator.observeKernelLog(
            "[11:36:49] \u001B[31mERROR\u001B[0m[1362] [\u001B[38;5;122m78682474\u001B[0m 10.0s] " +
                "dns: exchange failed for b-graph.facebook.com. IN AAAA: context deadline exceeded",
            nowMs = 2_200L
        )

        assertEquals(HealthSignalKind.REMOTE_DNS_TIMEOUT, signal?.kind)
        assertEquals("dns-remote-hy2-aliyun-a6d30110", signal?.dnsServerTag)
        assertEquals(3, signal?.failureCount)
    }

    @Test
    fun ignoresDnsTimeoutWithoutRemoteDnsBinding() {
        val aggregator = HealthSignalAggregator(
            dnsWindowMs = 7_000L,
            minDnsFailures = 1,
            minDnsQueryIds = 1
        )

        val signal = aggregator.observeKernelLog(
            "[11:36:49] ERROR[1362] [74 10.0s] dns: exchange failed for " +
                "graph.facebook.com. IN A: context deadline exceeded",
            nowMs = 2_000L
        )

        assertNull(signal)
    }

    @Test
    fun emitsActiveProbeSignalAfterThreeTransportFailures() {
        val aggregator = HealthSignalAggregator(
            transportWindowMs = 7_000L,
            minTransportFailures = 3
        )

        assertNull(
            aggregator.observeKernelLog(
                "[11:36:49] ERROR[1362] outbound/hysteria2[node-a]: dial udp: i/o timeout",
                nowMs = 2_000L
            )
        )
        assertNull(
            aggregator.observeKernelLog(
                "[11:36:50] ERROR[1362] outbound/hysteria2[node-a]: connection reset by peer",
                nowMs = 2_100L
            )
        )

        val signal = aggregator.observeKernelLog(
            "[11:36:51] ERROR[1362] outbound/hysteria2[node-a]: network is unreachable",
            nowMs = 2_200L
        )

        assertEquals(HealthSignalKind.ACTIVE_PROBE_FAILED, signal?.kind)
        assertEquals(3, signal?.failureCount)
        assertEquals("node-a", signal?.outboundTag)
    }

    @Test
    fun doesNotMergeTransportFailuresFromDifferentOutbounds() {
        val aggregator = HealthSignalAggregator(minTransportFailures = 3)

        assertNull(
            aggregator.observeKernelLog(
                "ERROR outbound/vless[node-a]: i/o timeout",
                nowMs = 1_000L
            )
        )
        assertNull(
            aggregator.observeKernelLog(
                "ERROR outbound/vless[node-b]: i/o timeout",
                nowMs = 1_100L
            )
        )
        assertNull(
            aggregator.observeKernelLog(
                "ERROR outbound/vless[node-c]: i/o timeout",
                nowMs = 1_200L
            )
        )
    }

    @Test
    fun tooManyOpenFilesEmitsResourceSignalImmediately() {
        val signal = HealthSignalAggregator().observeKernelLog(
            "ERROR outbound/direct[direct]: fcntl: too many open files",
            nowMs = 5_000L
        )

        assertEquals(HealthSignalKind.RESOURCE_EXHAUSTED, signal?.kind)
    }

    @Test
    fun summaryContainsDomainServerAndCount() {
        val signal = HealthSignal(
            kind = HealthSignalKind.REMOTE_DNS_TIMEOUT,
            dnsServerTag = "dns-remote-hy2-aliyun-a6d30110",
            domains = setOf("graph.facebook.com"),
            failureCount = 4,
            firstAtMs = 1_000L,
            lastAtMs = 4_000L
        )

        val summary = HealthSignalAggregator.buildSummary(signal)

        assertTrue(summary.contains("graph.facebook.com"))
        assertTrue(summary.contains("dns-remote-hy2-aliyun-a6d30110"))
        assertTrue(summary.contains("failures=4"))
        assertTrue(summary.contains("dns_channel=remote"))
        assertTrue(summary.contains("diagnosis=remote_dns_timeout"))
    }

    @Test
    fun activeProbeSummaryContainsFailureDiagnosis() {
        val summary = HealthSignalAggregator.buildSummary(
            HealthSignal(
                kind = HealthSignalKind.ACTIVE_PROBE_FAILED,
                failureCount = 3,
                firstAtMs = 1_000L,
                lastAtMs = 2_000L
            )
        )

        assertTrue(summary.contains("diagnosis=active_probe_failed"))
        assertTrue(summary.contains("failures=3"))
    }
}
