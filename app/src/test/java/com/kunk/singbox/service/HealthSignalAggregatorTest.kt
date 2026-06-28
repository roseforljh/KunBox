package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSignalAggregatorTest {

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
}
