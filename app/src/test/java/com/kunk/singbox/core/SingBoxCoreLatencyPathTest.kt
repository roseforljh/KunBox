package com.kunk.singbox.core

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.Outbound
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
    fun testLatencyOutboundDomainResolverUsesLocalDnsForNodeDomain() {
        val outbound = Outbound(
            type = "vless",
            tag = "airport-node",
            server = "fly-nnca.bestvmr.com",
            domainResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        val actual = SingBoxCore.applyLatencyLocalDomainResolverForTest(outbound)

        assertEquals("local", actual.domainResolver?.server)
    }

    @Test
    fun testLatencyOutboundDomainResolverReplacesCustomResolverWithLocalDns() {
        val outbound = Outbound(
            type = "vless",
            tag = "airport-node",
            server = "fly-nnca.bestvmr.com",
            domainResolver = DomainResolveConfig(server = "airport-dns")
        )

        val actual = SingBoxCore.applyLatencyLocalDomainResolverForTest(outbound)

        assertEquals("local", actual.domainResolver?.server)
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
