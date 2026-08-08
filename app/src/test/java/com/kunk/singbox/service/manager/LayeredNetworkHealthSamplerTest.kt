package com.kunk.singbox.service.manager

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayeredNetworkHealthSamplerTest {
    @Test
    fun statisticsReportLossAverageAndJitterAcrossRepeatedSamples() {
        val statistics = summarizeTimedProbeResults(
            listOf(
                TimedProbeResult(succeeded = true, latencyMs = 100L),
                TimedProbeResult(succeeded = false),
                TimedProbeResult(succeeded = true, latencyMs = 160L)
            )
        )

        assertEquals(3, statistics.attempts)
        assertEquals(2, statistics.successes)
        assertEquals(1, statistics.failures)
        assertEquals(33, statistics.lossPercent)
        assertEquals(130L, statistics.averageLatencyMs)
        assertEquals(60L, statistics.jitterMs)
        assertTrue(statistics.hasMajoritySuccess)
    }

    @Test
    fun physicalFailureSkipsDnsAndProxyForThatAttempt() = runBlocking {
        var physicalCalls = 0
        var dnsCalls = 0
        var proxyCalls = 0
        val sampler = LayeredNetworkHealthSampler(attempts = 3, intervalMs = 0L)

        val result = sampler.sample(
            physicalProbe = {
                physicalCalls++
                TimedProbeResult(succeeded = physicalCalls != 2)
            },
            dnsProbe = {
                dnsCalls++
                TimedProbeResult(succeeded = true, latencyMs = 20L)
            },
            proxyProbe = {
                proxyCalls++
                TimedProbeResult(succeeded = true, latencyMs = 80L)
            }
        )

        assertEquals(3, physicalCalls)
        assertEquals(2, dnsCalls)
        assertEquals(2, proxyCalls)
        assertEquals(33, result.physical.lossPercent)
        assertEquals(33, result.dns.lossPercent)
        assertEquals(33, result.proxy.lossPercent)
        assertNull(result.physical.averageLatencyMs)
    }
}
