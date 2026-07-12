package com.kunk.singbox.repository

import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.RoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@Suppress("TooManyFunctions")
open class ConfigRepositoryTestPart9 : ConfigRepositoryTestPart8() {
    override fun testDetectRuleSetRuleTypeUsesGeositeTagForBinaryRuleSet() {
        val tempFile = createTempRuleSetBytes(byteArrayOf(0, 1, 2, 3))

        val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(tempFile, "geosite-cn")

        assertEquals(ConfigRepository.RuleSetRuleType.DOMAIN, ruleType)
    }

    @Test
    override fun testDetectRuleSetRuleTypeKeepsUnknownBinaryAsUnknownWithoutTagHint() {
        val tempFile = createTempRuleSetBytes(byteArrayOf('S'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), 1))

        val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(tempFile, "ads")

        assertEquals(ConfigRepository.RuleSetRuleType.UNKNOWN, ruleType)
    }

    protected override fun createTempRuleSetBytes(content: ByteArray): java.io.File {
        val tempFile = java.io.File.createTempFile("ruleset_test_", ".srs")
        tempFile.writeBytes(content)
        tempFile.deleteOnExit()
        return tempFile
    }

    protected override fun createTempRuleSetFile(content: String): java.io.File {
        val tempFile = java.io.File.createTempFile("ruleset_test_", ".srs")
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        return tempFile
    }

    @Test
    override fun testSanitizeInjectedDnsServerForcesDetourOnUdpWithoutDetour() {
        val server = com.kunk.singbox.model.DnsServer(
            tag = "ad-block", type = "udp", server = "8.8.8.8"
        )
        val result = ConfigRepository.sanitizeInjectedDnsServerForRuntime(
            server = server,
            routingMode = RoutingMode.GLOBAL_PROXY,
            proxyDetourTag = "node-hk"
        )
        assertEquals("node-hk", result.detour)
    }

    @Test
    override fun testSanitizeInjectedDnsServerPreservesExistingDetour() {
        val server = com.kunk.singbox.model.DnsServer(
            tag = "custom", type = "https", server = "dns.google", detour = "my-proxy"
        )
        val result = ConfigRepository.sanitizeInjectedDnsServerForRuntime(
            server = server,
            routingMode = RoutingMode.GLOBAL_PROXY,
            proxyDetourTag = "node-hk"
        )
        assertEquals("my-proxy", result.detour)
    }

    @Test
    override fun testSanitizeInjectedDnsServerSkipsFakeip() {
        val server = com.kunk.singbox.model.DnsServer(tag = "fakeip-dns", type = "fakeip")
        val result = ConfigRepository.sanitizeInjectedDnsServerForRuntime(
            server = server,
            routingMode = RoutingMode.GLOBAL_PROXY,
            proxyDetourTag = "node-hk"
        )
        assertNull(result.detour)
    }

    @Test
    override fun testSanitizeInjectedDnsServerSkipsInGlobalDirectMode() {
        val server = com.kunk.singbox.model.DnsServer(
            tag = "leak", type = "udp", server = "1.1.1.1"
        )
        val result = ConfigRepository.sanitizeInjectedDnsServerForRuntime(
            server = server,
            routingMode = RoutingMode.GLOBAL_DIRECT,
            proxyDetourTag = "node-hk"
        )
        assertNull(result.detour)
    }
}
