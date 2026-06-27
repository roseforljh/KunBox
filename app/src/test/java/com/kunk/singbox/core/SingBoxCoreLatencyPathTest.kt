package com.kunk.singbox.core

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.repository.ConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SingBoxCoreLatencyPathTest {

    @Test
    fun testLatencyDnsConfigUsesConfiguredLocalDns() {
        val config = SingBoxCore.buildLatencyTestDnsConfigForTest(
            AppSettings(localDns = "udp://47.110.75.65:8053")
        )

        val localServer = config.servers?.firstOrNull { it.tag == "local" }

        assertNotNull(localServer)
        assertEquals("udp", localServer?.type)
        assertEquals("47.110.75.65", localServer?.server)
        assertEquals(8053, localServer?.serverPort)
        assertEquals("local", config.finalServer)
        assertTrue(config.rules.orEmpty().any { it.queryType == listOf("A", "AAAA") && it.server == "local" })
    }

    @Test
    fun testLatencyOutboundDomainResolverUsesBootstrapDnsForNodeDomain() {
        val outbound = Outbound(
            type = "vless",
            tag = "airport-node",
            server = "fly-nnca.bestvmr.com"
        )

        val actual = SingBoxCore.applyLatencyBootstrapDomainResolverForTest(outbound)

        assertEquals(ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG, actual.domainResolver?.server)
    }

    @Test
    fun testLatencyOutboundDomainResolverPreservesCustomResolver() {
        val outbound = Outbound(
            type = "vless",
            tag = "airport-node",
            server = "fly-nnca.bestvmr.com",
            domainResolver = DomainResolveConfig(server = "airport-dns")
        )

        val actual = SingBoxCore.applyLatencyBootstrapDomainResolverForTest(outbound)

        assertEquals("airport-dns", actual.domainResolver?.server)
    }

    @Test
    fun testLatencyDnsConfigIncludesDnsOverrideServerAndNodeResolverRuleBeforeLocalFallback() {
        val outbounds = listOf(
            Outbound(
                type = "vless",
                tag = "airport-node",
                server = "fly-nnca.bestvmr.com",
                domainResolver = DomainResolveConfig(server = "bestvmr-dns", strategy = "prefer_ipv4")
            )
        )
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "bestvmr-dns", address = "udp://47.110.75.65:8053")),
            rules = listOf(DnsRule(domainSuffix = listOf(".bestvmr.com"), server = "bestvmr-dns"))
        )

        val config = SingBoxCore.buildLatencyTestDnsConfigForTest(
            settings = AppSettings(localDns = "local"),
            outbounds = outbounds,
            dnsOverride = override
        ) { server ->
            ConfigRepository.sanitizeInjectedDnsServerForTest(
                server = server,
                routingMode = RoutingMode.GLOBAL_DIRECT,
                proxyDetourTag = "direct"
            )
        }
        val privateDns = config.servers?.firstOrNull { it.tag == "bestvmr-dns" }
        val nodeRuleIndex = config.rules.orEmpty().indexOfFirst {
            it.domain == listOf("fly-nnca.bestvmr.com") && it.server == "bestvmr-dns"
        }
        val localFallbackIndex = config.rules.orEmpty().indexOfFirst {
            it.queryType == listOf("A", "AAAA") && it.server == "local" && it.domain == null
        }

        assertEquals("udp", privateDns?.type)
        assertEquals("47.110.75.65", privateDns?.server)
        assertEquals(8053, privateDns?.serverPort)
        assertTrue(nodeRuleIndex >= 0)
        assertTrue(localFallbackIndex >= 0)
        assertTrue(nodeRuleIndex < localFallbackIndex)
        assertEquals("prefer_ipv4", config.rules?.get(nodeRuleIndex)?.strategy)
    }

    @Test
    fun testPublicLatencyEntrypointsUseOnlyOfflineTemporaryServicePath() {
        val source = readSingBoxCoreSource()
        val singleNodeBody = extractFunctionBody(source, "testOutboundLatency")
        val batchBody = extractFunctionBody(source, "testOutboundsLatency")
        val latencyEntrypoints = singleNodeBody + "\n" + batchBody

        assertTrue(singleNodeBody.contains("testOutboundLatencyWithOfflineTemporaryService"))
        assertTrue(batchBody.contains("testOutboundsLatencyOfflineWithTemporaryService"))

        listOf(
            "SafeLatencyTester",
            "urlTestBatch",
            "urlTestGroup",
            "testOutboundLatencyWithLibbox",
            "testWithLibboxStaticUrlTest",
            "VpnStateStore.getActive()",
            "SingBoxService.instance"
        ).forEach { forbidden ->
            assertFalse("Latency entrypoints must not use $forbidden", latencyEntrypoints.contains(forbidden))
        }
    }

    @Test
    fun testProcessNetworkBindingIsSerializedForLocalProxyLatencyPath() {
        val source = readSingBoxCoreSource()
        val localProxyInternalBody = extractFunctionBody(source, "testWithLocalHttpProxyInternal")
        val functionStart = source.indexOf("suspend fun testWithLocalHttpProxyInternal(")
        val lockStart = source.indexOf("processNetworkBindMutex.withLock", functionStart)
        val bindStart = source.indexOf("bindProcessToNetwork(testNetwork)", functionStart)
        val restoreStart = source.indexOf("bindProcessToNetwork(previousNetwork)", functionStart)

        assertTrue(source.contains("private val processNetworkBindMutex = Mutex()"))
        assertTrue(localProxyInternalBody.contains("bindProcessToNetwork"))
        assertTrue(lockStart in functionStart until bindStart)
        assertTrue(lockStart < restoreStart)
    }

    @Test
    fun testBatchLatencyPortsAreMappedAfterRuntimeOutboundFiltering() {
        val source = readSingBoxCoreSource()
        val batchBody = extractFunctionBody(source, "testOutboundsLatencyBatchInternal")
        val prepareIndex = batchBody.indexOf("val fixedOutbounds = batchOutbounds.mapNotNull")
        val allocateIndex = batchBody.indexOf("allocateMultipleLocalPorts(fixedOutbounds.size)")
        val mapIndex = batchBody.indexOf("ports.zip(fixedOutbounds.map { it.tag }).toMap()")

        assertTrue(prepareIndex >= 0)
        assertTrue(allocateIndex > prepareIndex)
        assertTrue(mapIndex > allocateIndex)
        assertFalse(batchBody.contains("ports.zip(batchOutbounds.map { it.tag }).toMap()"))
    }

    @Test
    fun testLatencyTemporaryConfigsStripInternalEchMetadataBeforeJson() {
        val source = readSingBoxCoreSource()
        val singleNodeBody = extractFunctionBody(source, "testWithLocalHttpProxyInternal")
        val batchBody = extractFunctionBody(source, "testOutboundsLatencyBatchInternal")

        assertTrue(singleNodeBody.contains("gson.toJson(stripLatencyRuntimeMetadata(config))"))
        assertTrue(batchBody.contains("gson.toJson(stripLatencyRuntimeMetadata(config))"))
    }

    @Test
    fun testLatencyMethodMapsUrlTestToTotalRequestTime() {
        val source = readSingBoxCoreSource()

        assertTrue(source.contains("LatencyTestMethod.URL_TEST -> PreciseLatencyTester.Standard.TOTAL"))
        assertTrue(source.contains("standard = latencyStandardForMethod(settings.latencyTestMethod)"))
        assertTrue(source.contains("val standard = latencyStandardForMethod(settings.latencyTestMethod)"))
        assertTrue(source.contains("standard = standard"))
    }

    private fun readSingBoxCoreSource(): String {
        val candidates = listOf(
            File("src/main/java/com/kunk/singbox/core/SingBoxCore.kt"),
            File("app/src/main/java/com/kunk/singbox/core/SingBoxCore.kt")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("SingBoxCore.kt not found from ${File(".").absolutePath}")
    }

    private fun extractFunctionBody(source: String, functionName: String): String {
        val marker = "suspend fun $functionName("
        val start = source.indexOf(marker)
        require(start >= 0) { "$functionName not found" }
        val bodyStart = source.indexOf('{', start)
        require(bodyStart >= 0) { "$functionName body not found" }

        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(bodyStart, index + 1)
                    }
                }
            }
        }
        error("$functionName body end not found")
    }
}
