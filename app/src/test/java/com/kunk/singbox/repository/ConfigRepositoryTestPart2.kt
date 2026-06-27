package com.kunk.singbox.repository

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryTestPart2 : ConfigRepositoryTestPart1() {
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

        val actual = ConfigRepository.applyDnsOverrideForTest(base, override)

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

        val actual = ConfigRepository.applyDnsOverrideForTest(base, override)

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
        val config = ConfigRepository.parseDnsOverrideForTest(bestvmrDnsOverrideJson())

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
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarningForTest(bestvmrDnsOverrideJson())

        assertNotNull(warning)
        assertTrue(warning?.contains("DNS 覆写使用了旧版 sing-box 格式") == true)
        assertTrue(warning?.contains("address") == true)
        assertTrue(warning?.contains("action") == true)
    }

    @Test
    override fun testDnsOverrideCompatibilityWarningDetectsMissingServerAndGlobalRule() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarningForTest(
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
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarningForTest("{")

        assertNotNull(warning)
        assertTrue(warning?.contains("无法解析") == true)
    }

    @Test
    override fun testDnsOverrideCompatibilityWarningDetectsLegacyAddressResolver() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarningForTest(
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
    override fun testDnsOverrideCompatibilityWarningDetectsOtherMigrationRisks() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarningForTest(
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

        assertNotNull(warning)
        assertTrue(warning?.contains("independent_cache") == true)
        assertTrue(warning?.contains("outbound") == true)
        assertTrue(warning?.contains("旧地址过滤字段") == true)
    }

    @Test
    override fun testDnsOverrideCompatibilityWarningIgnoresLatestFormat() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarningForTest(
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
        val override = ConfigRepository.parseDnsOverrideForTest(bestvmrDnsOverrideJson())
            ?: error("override should parse")
        val localDns = ConfigRepository.buildDnsServer(
            address = ConfigRepository.normalizeLocalDns("local"),
            tag = "local"
        )
        val outbounds = ConfigRepository.applyDnsOverrideDomainResolversForTest(
            ConfigRepository.applyDefaultOutboundDomainResolverForTest(
                outbounds = listOf(bestvmrNodeOutbound()),
                defaultResolverTag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG,
                defaultResolverStrategy = "prefer_ipv4"
            ),
            override
        )
        val directDnsTags = ConfigRepository.resolveDnsOverrideDirectDnsServerTagsForTest(outbounds, override)
        val mergedDns = ConfigRepository.applyDnsOverrideForTest(
            baseConfig = DnsConfig(
                servers = listOf(localDns),
                rules = ConfigRepository.buildOutboundDomainResolverDnsRulesForTest(outbounds)
            ),
            overrideConfig = override
        ) { server ->
            ConfigRepository.sanitizeInjectedDnsServerForTest(
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
        assertEquals("dns.alidns.com", localDns.server)
        assertEquals("/dns-query", localDns.path)
        assertEquals("bestvmr-dns", outbounds.first().domainResolver?.server)
        assertEquals("udp", privateDns?.type)
        assertEquals("47.110.75.65", privateDns?.server)
        assertEquals(8053, privateDns?.serverPort)
        assertNull(privateDns?.detour)
        assertNotNull(nodeRule)
        assertEquals("prefer_ipv4", nodeRule?.strategy)
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

        val actual = ConfigRepository.applyDnsOverrideDomainResolversForTest(outbounds, override)

        assertEquals("airport-dns", actual[0].domainResolver?.server)
        assertEquals("dns-bootstrap", actual[1].domainResolver?.server)
    }

    @Test
    override fun testOutboundDomainResolverDnsRulesProtectNodeDomainFromFakeIp() {
        val rules = ConfigRepository.buildOutboundDomainResolverDnsRulesForTest(
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
        assertEquals("prefer_ipv4", rules.first().strategy)
        assertEquals(true, rules.first().disableCache)
    }

    @Test
    override fun testOutboundDomainResolverDnsRulesSkipIpAndFakeIpResolver() {
        val rules = ConfigRepository.buildOutboundDomainResolverDnsRulesForTest(
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
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolverForTest(
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
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolverForTest(
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

        val actual = ConfigRepository.applyDnsOverrideDomainResolversForTest(outbounds, override)

        assertEquals("airport-dns", actual.first().domainResolver?.server)
    }

    @Test
    override fun testDefaultOutboundDomainResolverAppliesServerAddressStrategy() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolverForTest(
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
    override fun testDnsOverrideDomainResolverKeepsServerAddressStrategyWhenRuleHasNoStrategy() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolverForTest(
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

        val actual = ConfigRepository.applyDnsOverrideDomainResolversForTest(outbounds, override)

        assertEquals("airport-dns", actual.first().domainResolver?.server)
        assertEquals("prefer_ipv4", actual.first().domainResolver?.strategy)
    }

    @Test
    override fun testDnsOverrideCatchAllRuleWinsOverBootstrapDefaultDomainResolver() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolverForTest(
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

        val actual = ConfigRepository.applyDnsOverrideDomainResolversForTest(outbounds, override)

        assertEquals("airport-dns", actual.first().domainResolver?.server)
    }

    @Test
    override fun testDnsOverrideOutboundAnyRuleWinsOverBootstrapDefaultDomainResolver() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolverForTest(
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

        val actual = ConfigRepository.applyDnsOverrideDomainResolversForTest(outbounds, override)

        assertEquals("airport-dns", actual.first().domainResolver?.server)
    }

    @Test
    override fun testDnsOverrideSpecificOutboundRuleOnlyAppliesMatchingOutbound() {
        val outbounds = ConfigRepository.applyDefaultOutboundDomainResolverForTest(
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

        val actual = ConfigRepository.applyDnsOverrideDomainResolversForTest(outbounds, override)

        assertEquals("airport-dns", actual[0].domainResolver?.server)
        assertEquals(ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG, actual[1].domainResolver?.server)
    }

    @Test
    override fun testDnsOverrideMatchingDomainSkipsProfileDnsPreResolve() {
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(domainSuffix = listOf("bestvmr.com"), server = "airport-dns"))
        )

        val shouldPreResolve = ConfigRepository.shouldApplyDnsPreResolveToDomainForTest(
            domain = "fly-nnca.bestvmr.com",
            dnsOverride = override,
            outboundTag = "airport-node"
        )

        assertFalse(shouldPreResolve)
    }

    @Test
    override fun testDnsOverrideCatchAllRuleSkipsProfileDnsPreResolve() {
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(queryType = listOf("A", "AAAA"), server = "airport-dns"))
        )

        val shouldPreResolve = ConfigRepository.shouldApplyDnsPreResolveToDomainForTest(
            domain = "fly-nnca.bestvmr.com",
            dnsOverride = override
        )

        assertFalse(shouldPreResolve)
    }

    @Test
    override fun testDnsOverrideOutboundAnyRuleSkipsProfileDnsPreResolve() {
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(outboundRaw = "any", server = "airport-dns"))
        )

        val shouldPreResolve = ConfigRepository.shouldApplyDnsPreResolveToDomainForTest(
            domain = "fly-nnca.bestvmr.com",
            dnsOverride = override
        )

        assertFalse(shouldPreResolve)
    }

    @Test
    override fun testDnsOverrideSpecificOutboundRuleSkipsMatchingProfileDnsPreResolve() {
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(outboundRaw = "airport-node", server = "airport-dns"))
        )

        val shouldPreResolve = ConfigRepository.shouldApplyDnsPreResolveToDomainForTest(
            domain = "fly-nnca.bestvmr.com",
            dnsOverride = override,
            outboundTag = "airport-node"
        )

        assertFalse(shouldPreResolve)
    }

    @Test
    override fun testDnsOverrideSpecificOutboundRuleKeepsNonMatchingProfileDnsPreResolve() {
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(outboundRaw = "airport-node", server = "airport-dns"))
        )

        val shouldPreResolve = ConfigRepository.shouldApplyDnsPreResolveToDomainForTest(
            domain = "fly-nnca.bestvmr.com",
            dnsOverride = override,
            outboundTag = "other-node"
        )

        assertTrue(shouldPreResolve)
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

        val directDnsServerTags = ConfigRepository.resolveDnsOverrideDirectDnsServerTagsForTest(outbounds, override)
        val airportDns = ConfigRepository.sanitizeInjectedDnsServerForTest(
            server = DnsServer(tag = "airport-dns", address = "udp://47.110.75.65:8053"),
            routingMode = RoutingMode.GLOBAL_PROXY,
            proxyDetourTag = "node-hk",
            directDnsServerTags = directDnsServerTags
        )
        val customDns = ConfigRepository.sanitizeInjectedDnsServerForTest(
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
    override fun testDnsOverrideNonMatchingDomainKeepsProfileDnsPreResolve() {
        val override = DnsConfig(
            servers = listOf(DnsServer(tag = "airport-dns", type = "https", server = "dns.example.com")),
            rules = listOf(DnsRule(domainSuffix = listOf("bestvmr.com"), server = "airport-dns"))
        )

        val shouldPreResolve = ConfigRepository.shouldApplyDnsPreResolveToDomainForTest(
            domain = "other.example.com",
            dnsOverride = override
        )

        assertTrue(shouldPreResolve)
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

    @Test
    override fun testNormalizeLocalDnsRejectsBareDomainAddress() {
        val normalized = ConfigRepository.normalizeLocalDns("dns.example.com")

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, normalized)
    }

    @Test
    override fun testNormalizeRemoteDnsReplacesBlankValue() {
        val normalized = ConfigRepository.normalizeRemoteDns("   ")

        assertEquals("https://dns.google/dns-query", normalized)
    }

    @Test
    override fun testNormalizeRemoteDnsRewritesCloudflareIpDohToDomain() {
        val normalized = ConfigRepository.normalizeRemoteDns("https://1.1.1.1/dns-query")

        assertEquals("https://cloudflare-dns.com/dns-query", normalized)
    }

    @Test
    override fun testNormalizeRemoteDnsRewritesCloudflareIpv6DohToDomain() {
        val normalized = ConfigRepository.normalizeRemoteDns("https://[2606:4700:4700::1111]/dns-query")

        assertEquals("https://cloudflare-dns.com/dns-query", normalized)
    }

    @Test
    override fun testNormalizeRemoteDnsKeepsNonCloudflareIpDoh() {
        val normalized = ConfigRepository.normalizeRemoteDns("https://8.8.8.8/dns-query")

        assertEquals("https://8.8.8.8/dns-query", normalized)
    }

    @Test
    override fun testBuildDnsResolverForDomainUrlReturnsBootstrapResolver() {
        val resolver = ConfigRepository.buildDnsResolverForAddress("https://dns.alidns.com/dns-query")

        assertNotNull(resolver)
        assertEquals("dns-bootstrap", resolver?.server)
    }
}
