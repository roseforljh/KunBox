package com.kunk.singbox.repository

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsFakeIpConfig
import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.DnsStrategy
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryTestPart6 : ConfigRepositoryTestPart5() {
    override fun testDnsOverrideReplacesServersPrependsRulesAndOverridesTopLevelFields() {
        val base = DnsConfig(
            servers = listOf(
                DnsServer(tag = "remote", type = "https", server = "dns.google"),
                DnsServer(tag = "local", type = "local")
            ),
            rules = listOf(DnsRule(domain = listOf("example.com"), server = "remote")),
            finalServer = "remote",
            strategy = "ipv4_only",
            disableCache = false,
            disableExpire = false,
            independentCache = false
        )
        val override = DnsConfig(
            servers = listOf(
                DnsServer(tag = "remote", type = "https", server = "cloudflare-dns.com"),
                DnsServer(tag = "custom", type = "tls", server = "dns.example.com")
            ),
            rules = listOf(DnsRule(domain = listOf("example.com"), server = "local")),
            finalServer = "local",
            strategy = "prefer_ipv6",
            disableCache = true,
            disableExpire = true,
            independentCache = true,
            fakeip = DnsFakeIpConfig(enabled = true, inet4Range = "198.18.0.0/15")
        )

        val actual = ConfigRepository.applyDnsOverride(base, override) {
            it.copy(detour = "selected-node")
        }
        val remoteServer = actual.servers?.firstOrNull { it.tag == "remote" }
        val customServer = actual.servers?.firstOrNull { it.tag == "custom" }

        assertEquals(3, actual.servers?.size)
        assertEquals("cloudflare-dns.com", remoteServer?.server)
        assertEquals("selected-node", remoteServer?.detour)
        assertEquals("dns.example.com", customServer?.server)
        assertEquals("selected-node", customServer?.detour)
        assertEquals("local", actual.rules?.firstOrNull()?.server)
        assertEquals("remote", actual.rules?.getOrNull(1)?.server)
        assertEquals("local", actual.finalServer)
        assertEquals("prefer_ipv6", actual.strategy)
        assertEquals(true, actual.disableCache)
        assertEquals(true, actual.disableExpire)
        assertEquals(true, actual.independentCache)
        assertEquals("198.18.0.0/15", actual.fakeip?.inet4Range)
    }

    @Test
    override fun testDnsOverrideKeepsBlankTopLevelFieldsAndAllowsFalseBooleans() {
        val base = DnsConfig(
            finalServer = "remote",
            strategy = "prefer_ipv4",
            disableCache = true,
            disableExpire = true,
            independentCache = true
        )
        val override = DnsConfig(
            finalServer = "   ",
            strategy = "",
            disableCache = false,
            disableExpire = false,
            independentCache = false
        )

        val actual = ConfigRepository.applyDnsOverride(base, override)

        assertEquals("remote", actual.finalServer)
        assertEquals("prefer_ipv4", actual.strategy)
        assertEquals(false, actual.disableCache)
        assertEquals(false, actual.disableExpire)
        assertEquals(false, actual.independentCache)
    }

    @Test
    override fun testDnsOverrideServerRuleDefaultsToRouteAction() {
        val base = DnsConfig(
            servers = listOf(DnsServer(tag = "remote", type = "https", server = "dns.google")),
            rules = listOf(DnsRule(domain = listOf("fallback.example.com"), action = "route", server = "remote"))
        )
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(domainSuffix = listOf("bestvmr.com"), server = "airport-dns"))
        )

        val actual = ConfigRepository.applyDnsOverride(base, override)

        assertEquals("route", actual.rules?.firstOrNull()?.action)
        assertEquals("airport-dns", actual.rules?.firstOrNull()?.server)
        assertEquals(listOf("bestvmr.com"), actual.rules?.firstOrNull()?.domainSuffix)
    }

    @Test
    override fun testDnsOverrideRuleStringFieldsParseAsLists() {
        val config = gson.fromJson(
            """
            {
              "servers": [
                { "tag": "airport-dns", "type": "https", "server": "dns.example.com" }
              ],
              "rules": [
                {
                  "domain_suffix": "bestvmr.com",
                  "query_type": "A",
                  "inbound": "tun-in",
                  "package_name": "com.example.app",
                  "server": "airport-dns"
                }
              ]
            }
            """.trimIndent(),
            DnsConfig::class.java
        )

        val rule = config.rules?.firstOrNull()

        assertEquals(listOf("bestvmr.com"), rule?.domainSuffix)
        assertEquals(listOf("A"), rule?.queryType)
        assertEquals(listOf("tun-in"), rule?.inbound)
        assertEquals(listOf("com.example.app"), rule?.packageName)
    }

    @Test
    override fun testDnsOverrideAcceptsFullConfigDnsWrapper() {
        val config = ConfigRepository.parseDnsOverrideConfig(bestvmrDnsOverrideJson())

        assertEquals("bestvmr-dns", config?.servers?.firstOrNull()?.tag)
        assertEquals("udp://47.110.75.65:8053", config?.servers?.firstOrNull()?.address)
        assertEquals(2, config?.rules?.size)
        assertEquals(listOf(".bestvmr.com"), config?.rules?.firstOrNull()?.domainSuffix)
        assertEquals("bestvmr-dns", config?.rules?.firstOrNull()?.server)
        assertEquals(true, config?.rules?.firstOrNull()?.disableCache)
        assertEquals(listOf("bestvmr.com"), config?.rules?.getOrNull(1)?.domain)
    }

    @Test
    override fun testDnsOverrideCompatibilityWarningDetectsLegacyFieldsAndImplicitRouteAction() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarning(bestvmrDnsOverrideJson())

        assertNotNull(warning)
        assertTrue(warning?.contains("DNS 覆写使用了旧版 sing-box 格式") == true)
        assertTrue(warning?.contains("address") == true)
        assertTrue(warning?.contains("action") == true)
    }

    @Test
    override fun testDnsOverrideCompatibilityWarningDetectsMissingServerAndGlobalRule() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarning(
            """
            {
              "servers": [
                { "tag": "defined-dns", "type": "udp", "server": "1.1.1.1" }
              ],
              "rules": [
                {
                  "action": "route",
                  "server": "missing-dns"
                }
              ]
            }
            """.trimIndent()
        )

        assertNotNull(warning)
        assertTrue(warning?.contains("missing-dns") == true)
        assertTrue(warning?.contains("全局规则") == true)
    }

    @Test
    override fun testDnsOverrideCompatibilityWarningDetectsInvalidJson() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarning("{")

        assertNotNull(warning)
        assertTrue(warning?.contains("无法解析") == true)
    }

    @Test
    override fun testDnsOverrideCompatibilityWarningDetectsLegacyAddressResolver() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarning(
            """
            {
              "servers": [
                {
                  "tag": "airport-dns",
                  "address": "https://dns.example.com/dns-query",
                  "address_resolver": "bootstrap"
                }
              ],
              "rules": [
                {
                  "domain": ["example.com"],
                  "action": "route",
                  "server": "airport-dns"
                }
              ]
            }
            """.trimIndent()
        )

        assertNotNull(warning)
        assertTrue(warning?.contains("address") == true)
        assertTrue(warning?.contains("address_resolver") == true)
    }

    @Test
    override fun testDnsOverrideCompatibilityWarningAcceptsCurrentMatchers() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarning(
            """
            {
              "dns": {
                "independent_cache": true,
                "servers": [
                  {
                    "tag": "airport-dns",
                    "type": "udp",
                    "server": "47.110.75.65"
                  }
                ],
                "rules": [
                  {
                    "outbound": "any",
                    "ip_cidr": ["1.1.1.1/32"],
                    "action": "route",
                    "server": "airport-dns"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertNull(warning)
    }

    @Test
    override fun testDnsOverrideCompatibilityWarningIgnoresLatestFormat() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarning(
            """
            {
              "dns": {
                "servers": [
                  {
                    "tag": "bestvmr-dns",
                    "type": "udp",
                    "server": "47.110.75.65",
                    "server_port": 8053
                  }
                ],
                "rules": [
                  {
                    "domain_suffix": ["bestvmr.com"],
                    "action": "route",
                    "server": "bestvmr-dns",
                    "disable_cache": true
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertNull(warning)
    }

    @Test
    override fun testLocalDnsWithFullDnsOverrideRoutesNodeDomainToPrivateDns() {
        val override = ConfigRepository.parseDnsOverrideConfig(bestvmrDnsOverrideJson())
            ?: error("override should parse")
        val localDns = ConfigRepository.buildDnsServer(
            address = ConfigRepository.normalizeLocalDns("local"),
            tag = "local"
        )
        val outbounds = ConfigRepository.applyDnsOverrideDomainResolvers(
            ConfigRepository.applyDefaultOutboundDomainResolver(
                outbounds = listOf(bestvmrNodeOutbound()),
                defaultResolverTag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG,
                defaultResolverStrategy = "prefer_ipv4"
            ),
            override
        )
        val directDnsTags = ConfigRepository.resolveDnsOverrideDirectDnsServerTags(outbounds, override)
        val mergedDns = ConfigRepository.applyDnsOverride(
            baseConfig = DnsConfig(
                servers = listOf(localDns),
                rules = ConfigRepository.buildOutboundDomainResolverDnsRules(outbounds)
            ),
            overrideConfig = override
        ) { server ->
            ConfigRepository.sanitizeInjectedDnsServerForRuntime(
                server = server,
                routingMode = RoutingMode.RULE,
                proxyDetourTag = "airport-node",
                directDnsServerTags = directDnsTags
            )
        }

        val privateDns = mergedDns.servers?.firstOrNull { it.tag == "bestvmr-dns" }
        val nodeRule = mergedDns.rules?.firstOrNull {
            it.domain == listOf("fly-nnca.bestvmr.com") && it.server == "bestvmr-dns"
        }

        assertEquals("https", localDns.type)
        assertEquals("223.5.5.5", localDns.server)
        assertEquals("/dns-query", localDns.path)
        assertEquals("bestvmr-dns", outbounds.first().domainResolver?.server)
        assertEquals("prefer_ipv4", outbounds.first().domainResolver?.strategy)
        assertEquals("udp", privateDns?.type)
        assertEquals("47.110.75.65", privateDns?.server)
        assertEquals(8053, privateDns?.serverPort)
        assertNull(privateDns?.detour)
        assertNotNull(nodeRule)
        assertNull(nodeRule?.strategy)
    }

    @Test
    override fun testDnsOverrideDomainRuleAppliesToOutboundDomainResolver() {
        val outbounds = listOf(
            Outbound(
                type = "vless",
                tag = "airport-node",
                server = "fly-nnca.bestvmr.com",
                serverPort = 443,
                domainResolver = DomainResolveConfig(server = "dns-bootstrap")
            ),
            Outbound(
                type = "vless",
                tag = "other-node",
                server = "other.example.com",
                serverPort = 443,
                domainResolver = DomainResolveConfig(server = "dns-bootstrap")
            )
        )
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(domainSuffix = listOf("bestvmr.com"), server = "airport-dns"))
        )

        val actual = ConfigRepository.applyDnsOverrideDomainResolvers(outbounds, override)

        assertEquals("airport-dns", actual[0].domainResolver?.server)
        assertEquals("dns-bootstrap", actual[1].domainResolver?.server)
    }

    @Test
    override fun testOutboundDomainResolverDnsRulesProtectNodeDomainFromFakeIp() {
        val rules = ConfigRepository.buildOutboundDomainResolverDnsRules(
            listOf(
                Outbound(
                    type = "vless",
                    tag = "airport-node",
                    server = "fly-nnca.bestvmr.com",
                    serverPort = 443,
                    domainResolver = DomainResolveConfig(
                        server = "airport-dns",
                        strategy = "prefer_ipv4",
                        disableCache = true
                    )
                )
            )
        )

        assertEquals(1, rules.size)
        assertEquals("route", rules.first().action)
        assertEquals(listOf("fly-nnca.bestvmr.com"), rules.first().domain)
        assertEquals(listOf("A", "AAAA"), rules.first().queryType)
        assertEquals("airport-dns", rules.first().server)
        assertNull(rules.first().strategy)
        assertEquals(true, rules.first().disableCache)
    }

    @Test
    override fun testOutboundDomainResolverDnsRulesSkipIpAndFakeIpResolver() {
        val rules = ConfigRepository.buildOutboundDomainResolverDnsRules(
            listOf(
                Outbound(
                    type = "vless",
                    tag = "ip-node",
                    server = "1.2.3.4",
                    serverPort = 443,
                    domainResolver = DomainResolveConfig(server = "airport-dns")
                ),
                Outbound(
                    type = "vless",
                    tag = "fake-node",
                    server = "fake.example.com",
                    serverPort = 443,
                    domainResolver = DomainResolveConfig(server = "fakeip-dns")
                )
            )
        )

        assertTrue(rules.isEmpty())
    }

    @Test
    override fun testDefaultDomainResolverUsesBootstrapDnsForNodeDomains() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "node-a",
                    server = "node-a.example.com",
                    domainResolver = DomainResolveConfig(server = "dns-bootstrap")
                ),
                Outbound(
                    type = "vless",
                    tag = "node-b",
                    server = "node-b.example.com"
                ),
                Outbound(
                    type = "vless",
                    tag = "node-c",
                    server = "node-c.example.com",
                    domainResolver = DomainResolveConfig(server = "custom-dns")
                )
            ),
            defaultResolverTag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
        )

        assertEquals(ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG, outbounds[0].domainResolver?.server)
        assertEquals(ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG, outbounds[1].domainResolver?.server)
        assertEquals("custom-dns", outbounds[2].domainResolver?.server)
    }

    @Test
    override fun testDnsOverrideWinsOverBootstrapDefaultDomainResolver() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "airport-node",
                    server = "fly-nnca.bestvmr.com",
                    domainResolver = DomainResolveConfig(server = "dns-bootstrap")
                )
            ),
            defaultResolverTag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
        )
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(domainSuffix = listOf("bestvmr.com"), server = "airport-dns"))
        )

        val actual = ConfigRepository.applyDnsOverrideDomainResolvers(outbounds, override)

        assertEquals("airport-dns", actual.first().domainResolver?.server)
    }

    @Test
    override fun testDefaultOutboundDomainResolverAppliesServerAddressStrategy() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            outbounds = listOf(
                Outbound(
                    type = "naive",
                    tag = "naive-node",
                    server = "34.kuz7.com"
                )
            ),
            defaultResolverTag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG,
            defaultResolverStrategy = "prefer_ipv4"
        )

        assertEquals(ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG, outbounds.first().domainResolver?.server)
        assertEquals("prefer_ipv4", outbounds.first().domainResolver?.strategy)
    }

    @Test
    fun defaultOutboundDomainResolverKeepsDualStackAutoServerStrategy() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            outbounds = listOf(
                Outbound(
                    type = "trojan",
                    tag = "trojan-node",
                    server = "us-1.tr202613.com"
                )
            ),
            defaultResolverTag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG,
            defaultResolverStrategy = ConfigRepository.resolveOutboundServerAddressStrategy(
                DnsStrategy.AUTO,
                IpVersionMode.DUAL_STACK
            )
        )

        assertEquals(ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG, outbounds.first().domainResolver?.server)
        assertEquals("prefer_ipv4", outbounds.first().domainResolver?.strategy)
    }

    @Test
    override fun testDnsOverrideDomainResolverKeepsServerAddressStrategyWhenRuleHasNoStrategy() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "airport-node",
                    server = "fly-nnca.bestvmr.com"
                )
            ),
            defaultResolverTag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG,
            defaultResolverStrategy = "prefer_ipv4"
        )
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", address = "udp://47.110.75.65:8053")),
            rules = listOf(DnsRule(domainSuffix = listOf("bestvmr.com"), server = "airport-dns"))
        )

        val actual = ConfigRepository.applyDnsOverrideDomainResolvers(outbounds, override)

        assertEquals("airport-dns", actual.first().domainResolver?.server)
        assertEquals("prefer_ipv4", actual.first().domainResolver?.strategy)
    }

    @Test
    fun dnsOverrideStillWinsOverIpv6PreferredBootstrapDefault() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            outbounds = listOf(
                Outbound(
                    type = "trojan",
                    tag = "trojan-node",
                    server = "us-1.tr202613.com"
                )
            ),
            defaultResolverTag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG,
            defaultResolverStrategy = "prefer_ipv6"
        )
        val override = DnsConfig(
            servers = listOf(
                DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")
            ),
            rules = listOf(
                DnsRule(outboundRaw = "trojan-node", server = "airport-dns")
            )
        )

        val actual = ConfigRepository.applyDnsOverrideDomainResolvers(outbounds, override)

        assertEquals("airport-dns", actual.first().domainResolver?.server)
        assertEquals("prefer_ipv6", actual.first().domainResolver?.strategy)
    }

    @Test
    override fun testDnsOverrideCatchAllRuleWinsOverBootstrapDefaultDomainResolver() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "airport-node",
                    server = "fly-nnca.bestvmr.com"
                )
            ),
            defaultResolverTag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
        )
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(queryType = listOf("A", "AAAA"), server = "airport-dns"))
        )

        val actual = ConfigRepository.applyDnsOverrideDomainResolvers(outbounds, override)

        assertEquals("airport-dns", actual.first().domainResolver?.server)
    }

    @Test
    override fun testDnsOverrideOutboundAnyRuleWinsOverBootstrapDefaultDomainResolver() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "airport-node",
                    server = "fly-nnca.bestvmr.com"
                )
            ),
            defaultResolverTag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
        )
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(outboundRaw = "any", server = "airport-dns"))
        )

        val actual = ConfigRepository.applyDnsOverrideDomainResolvers(outbounds, override)

        assertEquals("airport-dns", actual.first().domainResolver?.server)
    }

    @Test
    override fun testDnsOverrideSpecificOutboundRuleOnlyAppliesMatchingOutbound() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "airport-node",
                    server = "fly-nnca.bestvmr.com"
                ),
                Outbound(
                    type = "vless",
                    tag = "other-node",
                    server = "other.example.com"
                )
            ),
            defaultResolverTag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
        )
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(outboundRaw = "airport-node", server = "airport-dns"))
        )

        val actual = ConfigRepository.applyDnsOverrideDomainResolvers(outbounds, override)

        assertEquals("airport-dns", actual[0].domainResolver?.server)
        assertEquals(ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG, actual[1].domainResolver?.server)
    }

    @Test
    override fun testDnsOverrideNodeDomainResolverSkipsAutomaticProxyDetour() {
        val outbounds = listOf(
            Outbound(
                type = "vless",
                tag = "airport-node",
                server = "fly-nnca.bestvmr.com",
                domainResolver = DomainResolveConfig(server = "airport-dns")
            ),
            Outbound(
                type = "vless",
                tag = "other-node",
                server = "other.example.com",
                domainResolver = DomainResolveConfig(server = "local")
            )
        )
        val override = DnsConfig(
            servers = listOf(
                DnsServer(tag = "airport-dns", address = "udp://47.110.75.65:8053"),
                DnsServer(tag = "custom-dns", type = "udp", server = "8.8.8.8")
            ),
            rules = listOf(DnsRule(domainSuffix = listOf("bestvmr.com"), server = "airport-dns"))
        )

        val directDnsServerTags = ConfigRepository.resolveDnsOverrideDirectDnsServerTags(outbounds, override)
        val airportDns = ConfigRepository.sanitizeInjectedDnsServerForRuntime(
            server = DnsServer(tag = "airport-dns", address = "udp://47.110.75.65:8053"),
            routingMode = RoutingMode.GLOBAL_PROXY,
            proxyDetourTag = "node-hk",
            directDnsServerTags = directDnsServerTags
        )
        val customDns = ConfigRepository.sanitizeInjectedDnsServerForRuntime(
            server = DnsServer(tag = "custom-dns", type = "udp", server = "8.8.8.8"),
            routingMode = RoutingMode.GLOBAL_PROXY,
            proxyDetourTag = "node-hk",
            directDnsServerTags = directDnsServerTags
        )

        assertEquals(setOf("airport-dns"), directDnsServerTags)
        assertEquals("udp", airportDns.type)
        assertEquals("47.110.75.65", airportDns.server)
        assertEquals(8053, airportDns.serverPort)
        assertNull(airportDns.detour)
        assertEquals("node-hk", customDns.detour)
    }

    @Test
    override fun testNormalizeLocalDnsReplacesLegacyLocalValue() {
        val normalized = ConfigRepository.normalizeLocalDns(AppSettings.LEGACY_LOCAL_DNS)

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, normalized)
    }

    @Test
    override fun testNormalizeLocalDnsReplacesBlankValue() {
        val normalized = ConfigRepository.normalizeLocalDns("   ")

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, normalized)
    }

    @Test
    override fun testNormalizeLocalDnsKeepsNumericAddress() {
        val normalized = ConfigRepository.normalizeLocalDns(" 223.5.5.5 ")

        assertEquals("223.5.5.5", normalized)
    }
}
