package com.kunk.singbox.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRepositoryLatencySourceTest {

    @Test
    fun singleNodeLatencyPassesRuntimeDnsConfigToCore() {
        val source = readConfigRepositorySourcesForTextTests()
        val body = extractKotlinFunctionBodyForTextTests(source, "testNodeLatency")

        assertTrue(body.contains("val runtimeContext = buildLatencyRuntimeContext"))
        assertTrue(body.contains("runtimeContext.dnsConfig"))
        assertTrue(body.contains("singBoxCore.testOutboundLatency("))
    }

    @Test
    fun batchLatencyGroupsByDnsConfigAndPassesItToCore() {
        val source = readConfigRepositorySourcesForTextTests()
        val body = extractKotlinFunctionBodyForTextTests(source, "testRegularOutboundsLatency")

        assertTrue(body.contains("val infosByDnsConfig = infos.groupBy { it.dnsConfig }"))
        assertTrue(body.contains("singBoxCore.testOutboundsLatency(preparedInfoPairs.map { it.second }, dnsConfig)"))
    }
}
