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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxCoreLatencyPathTest {

    @Test
    fun testLatencyDnsConfigUsesConfiguredLocalDns() {
        val config = SingBoxCore.buildLatencyTestDnsConfig(
            AppSettings(localDns = "udp://47.110.75.65:8053")
        )

        val localServer = config.servers?.firstOrNull { it.tag == "local" }
        val bootstrapServer = config.servers?.firstOrNull {
            it.tag == ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
        }

        assertNotNull(localServer)
        assertEquals("udp", localServer?.type)
        assertEquals("47.110.75.65", localServer?.server)
        assertEquals(8053, localServer?.serverPort)
        assertEquals("udp", bootstrapServer?.type)
        assertEquals("47.110.75.65", bootstrapServer?.server)
        assertEquals(8053, bootstrapServer?.serverPort)
        assertEquals("local", config.finalServer)
        assertTrue(config.rules.orEmpty().any { it.queryType == listOf("A", "AAAA") && it.server == "local" })
    }

    @Test
    fun testLatencyDnsConfigUsesSystemBootstrapWhenConfiguredDnsHostsNeedResolution() {
        val config = SingBoxCore.buildLatencyTestDnsConfig(
            AppSettings(
                localDns = "https://dns.alidns.com/dns-query",
                remoteDns = "https://dns.google/dns-query"
            )
        )

        val bootstrapServer = config.servers?.firstOrNull {
            it.tag == ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
        }

        assertEquals("local", bootstrapServer?.type)
        assertNull(bootstrapServer?.server)
        assertFalse(config.servers.orEmpty().any { it.server == "223.5.5.5" || it.server == "119.29.29.29" })
    }

    @Test
    fun testLatencyOutboundDomainResolverUsesBootstrapDnsForNodeDomain() {
        val outbound = Outbound(
            type = "vless",
            tag = "airport-node",
            server = "fly-nnca.bestvmr.com"
        )

        val actual = SingBoxCore.applyLatencyBootstrapDomainResolver(outbound)

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

        val actual = SingBoxCore.applyLatencyBootstrapDomainResolver(outbound)

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

        val config = SingBoxCore.buildLatencyTestDnsConfig(
            settings = AppSettings(localDns = "local"),
            outbounds = outbounds,
            dnsOverride = override
        ) { server ->
            ConfigRepository.sanitizeInjectedDnsServerForRuntime(
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
}
