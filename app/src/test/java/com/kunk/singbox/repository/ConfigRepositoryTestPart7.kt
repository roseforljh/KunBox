package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.RuleType
import com.kunk.singbox.model.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryTestPart7 : ConfigRepositoryTestPart6() {
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

    @Test
    override fun testBuildDnsResolverForIpUrlReturnsNull() {
        val resolver = ConfigRepository.buildDnsResolverForAddress("https://1.1.1.1/dns-query")

        assertNull(resolver)
    }

    @Test
    override fun testBuildDnsResolverForLocalValueReturnsNull() {
        val resolver = ConfigRepository.buildDnsResolverForAddress("local")

        assertNull(resolver)
    }

    @Test
    override fun testSubscriptionManagerPreservesTlsCertificateFromYamlImport() {
        val certificatePem = "-----BEGIN CERTIFICATE-----\nMIIBYAMLTEST\n-----END CERTIFICATE-----"
        val yaml = """
            proxies:
              - name: "yaml-anytls-cert"
                type: anytls
                server: anytls.example.com
                port: 443
                password: test-pass
                cert: |
                  -----BEGIN CERTIFICATE-----
                  MIIBYAMLTEST
                  -----END CERTIFICATE-----
        """.trimIndent()

        val config = subscriptionManager.parse(yaml)
        val anytls = config?.outbounds?.find { it.tag == "yaml-anytls-cert" }
        assertNotNull(anytls)
        assertEquals(certificatePem, anytls?.tls?.certificate?.trim())
    }

    @Test
    override fun testSubscriptionManagerDoesNotTreatTlsCertificateAsNodeLink() {
        val yaml = """
            proxies:
              - name: "user-info-cert"
                type: anytls
                server: anytls.example.com
                port: 443
                password: test-pass
                cert: |
                  -----BEGIN CERTIFICATE-----
                  MIIBNOTUSERINFO
                  -----END CERTIFICATE-----
        """.trimIndent()

        val config = subscriptionManager.parse(yaml)

        assertNotNull(config?.outbounds?.find { it.tag == "user-info-cert" }?.tls?.certificate)
        assertEquals(1, config?.outbounds?.size)
    }

    @Test
    override fun testSubscriptionManagerPreservesJsonTlsCertificateFields() {
        val certificatePem = "-----BEGIN CERTIFICATE-----\nMIIBJSONCERT\n-----END CERTIFICATE-----"
        val caPem = "-----BEGIN CERTIFICATE-----\nMIIBJSONCA\n-----END CERTIFICATE-----"
        val keyPem = "-----BEGIN PRIVATE KEY-----\nMIIBJSONKEY\n-----END PRIVATE KEY-----"
        val json = """
            {
              "outbounds": [
                {
                  "type": "anytls",
                  "tag": "json-anytls-cert",
                  "server": "json.example.com",
                  "server_port": 443,
                  "password": "test-pass",
                  "tls": {
                    "enabled": true,
                    "server_name": "edge.example.com",
                    "certificate": "-----BEGIN CERTIFICATE-----\nMIIBJSONCERT\n-----END CERTIFICATE-----",
                    "ca": "-----BEGIN CERTIFICATE-----\nMIIBJSONCA\n-----END CERTIFICATE-----",
                    "key": "-----BEGIN PRIVATE KEY-----\nMIIBJSONKEY\n-----END PRIVATE KEY-----"
                  }
                }
              ]
            }
        """.trimIndent()

        val config = subscriptionManager.parse(json)

        val anytls = config?.outbounds?.find { it.tag == "json-anytls-cert" }
        assertNotNull(anytls)
        assertEquals(certificatePem, anytls?.tls?.certificate)
        assertEquals(caPem, anytls?.tls?.ca)
        assertEquals(keyPem, anytls?.tls?.key)
    }

    @Test
    override fun testSubscriptionManagerParsesYamlImportWithMultipleNodes() {
        val yaml = """
            proxies:
              - name: "hk-regression"
                type: ss
                server: hk.example.com
                port: 443
                cipher: aes-128-gcm
                password: pass-a
              - name: "us-regression"
                type: trojan
                server: us.example.com
                port: 443
                password: pass-b
        """.trimIndent()

        val config = subscriptionManager.parse(yaml)

        assertNotNull(config)
        assertEquals(2, config?.outbounds?.size)
        assertEquals(listOf("hk-regression", "us-regression"), config?.outbounds?.map { it.tag })
    }

    @Test
    override fun testBuildUdpDnsServerFromNumericAddressUsesPort53() {
        val server = ConfigRepository.buildDnsServer(
            address = "223.5.5.5",
            tag = "local"
        )

        assertEquals("local", server.tag)
        assertEquals("udp", server.type)
        assertEquals("223.5.5.5", server.server)
        assertEquals(53, server.serverPort)
        assertNull(server.domainResolver)
    }

    @Test
    override fun testBuildDnsServerPreservesDomainResolverInJson() {
        val server = ConfigRepository.buildDnsServer(
            address = "https://dns.alidns.com/dns-query",
            tag = "local",
            domainStrategy = "prefer_ipv4",
            domainResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals("local", server.tag)
        assertEquals("https", server.type)
        assertEquals("dns.alidns.com", server.server)
        assertEquals("/dns-query", server.path)
        assertNotNull(server.domainResolver)
        assertEquals("dns-bootstrap", server.domainResolver?.server)

        val json = Gson().toJson(server)
        assertTrue(json.contains("\"domain_resolver\""))
        assertTrue(json.contains("\"server\":\"dns-bootstrap\""))
    }

    @Test
    override fun testBuildDynamicDnsServersDeduplicatesSameDetour() {
        val servers = ConfigRepository.buildDynamicDnsServersForTest(
            semantics = listOf(
                ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
                ConfigRepository.OutboundSemantic.RouteTag("P:HK")
            ),
            remoteDnsAddr = "https://dns.google/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals(1, servers.size)
        assertEquals("P:HK", servers.first().detour)
    }

    @Test
    override fun testBuildDynamicDnsServersIncludesDifferentDetours() {
        val servers = ConfigRepository.buildDynamicDnsServersForTest(
            semantics = listOf(
                ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
                ConfigRepository.OutboundSemantic.RouteTag("node-tag-1")
            ),
            remoteDnsAddr = "https://dns.google/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals(2, servers.size)
        assertTrue(servers.any { it.detour == "P:HK" })
        assertTrue(servers.any { it.detour == "node-tag-1" })
    }

    @Test
    override fun testBuildDynamicDnsServerTagIsStableForSameDetour() {
        val tag1 = ConfigRepository.buildDynamicDnsServerTag("P:HK")
        val tag2 = ConfigRepository.buildDynamicDnsServerTag("P:HK")

        assertEquals(tag1, tag2)
        assertTrue(tag1.startsWith("dns-remote-"))
    }

    @Test
    override fun testBuildDynamicDnsServerTagDiffersForDifferentDetours() {
        val tag1 = ConfigRepository.buildDynamicDnsServerTag("P:HK")
        val tag2 = ConfigRepository.buildDynamicDnsServerTag("P/HK")

        assertNotEquals(tag1, tag2)
    }

    @Test
    override fun testBuildDynamicDnsServerUsesGivenDetour() {
        val server = ConfigRepository.buildDynamicRemoteDnsServerForTest(
            detourTag = "P:HK",
            remoteDnsAddr = "https://dns.google/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals("P:HK", server.detour)
        assertEquals("https", server.type)
        assertEquals("dns.google", server.server)
        assertEquals("dns-bootstrap", server.domainResolver?.server)
    }

    @Test
    override fun testBuildDynamicDnsServersUsesRemoteDnsWithDetourForEchRouteTag() {
        val servers = ConfigRepository.buildDynamicDnsServersForTest(
            semantics = listOf(ConfigRepository.OutboundSemantic.RouteTag("ECH Node")),
            remoteDnsAddr = "https://1.1.1.1/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals(1, servers.size)
        assertEquals("1.1.1.1", servers.first().server)
        assertEquals("dns-bootstrap", servers.first().domainResolver?.server)
        assertEquals("ECH Node", servers.first().detour)
    }

    @Test
    override fun testBuildDynamicRemoteDnsServerForProxyDetourCarriesDetour() {
        val server = ConfigRepository.buildDynamicRemoteDnsServerForTest(
            detourTag = "PROXY",
            remoteDnsAddr = "https://1.1.1.1/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals("PROXY", server.detour)
        assertEquals("https", server.type)
        assertEquals("1.1.1.1", server.server)
        assertEquals("/dns-query", server.path)
    }

    @Test
    override fun testBuildDynamicRemoteDnsServerKeepsRemoteDnsForEchDetour() {
        val server = ConfigRepository.buildDynamicRemoteDnsServerForTest(
            detourTag = "ECH Node",
            remoteDnsAddr = "https://1.1.1.1/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("ECH Node"), server.tag)
        assertEquals("https", server.type)
        assertEquals("1.1.1.1", server.server)
        assertEquals("/dns-query", server.path)
        assertEquals("dns-bootstrap", server.domainResolver?.server)
        assertEquals("ECH Node", server.detour)
    }

    @Test
    override fun testBuildFakeIpDnsServerForTestIncludesRangesForFakeIpTransport() {
        val server = ConfigRepository.buildFakeIpDnsServerForTest("198.18.0.0/15")

        assertEquals("fakeip-dns", server.tag)
        assertEquals("fakeip", server.type)
        assertEquals("198.18.0.0/15", server.inet4Range)
        assertEquals("fc00::/18", server.inet6Range)
    }

    @Test
    override fun testBuildFakeIpDnsServerForTestPreservesCustomIpv4AndIpv6Ranges() {
        val server = ConfigRepository.buildFakeIpDnsServerForTest("198.18.0.0/15,fd00::/16")

        assertEquals("198.18.0.0/15", server.inet4Range)
        assertEquals("fd00::/16", server.inet6Range)
    }

    @Test
    override fun testBuildFakeIpDnsServerForTestRecoversNullRange() {
        val server = ConfigRepository.buildFakeIpDnsServerForTest(null)

        assertEquals("198.18.0.0/15", server.inet4Range)
        assertEquals("fc00::/18", server.inet6Range)
    }

    @Test
    override fun testDnsServerTagForRouteTagUsesDynamicServerWhenFakeDnsDisabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = false
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    override fun testDnsServerTagForRouteTagUsesDynamicServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = true
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    override fun testDnsServerTagForProxyUsesProxyServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.resolveDnsServerTagForRuleSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.Proxy,
            fakeDnsEnabled = true,
            proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("PROXY"), serverTag)
    }

    @Test
    override fun testDnsRouteToProxyUsesProxyDnsForIpQueriesWhenFakeDnsEnabled() {
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        val rules = ConfigRepository.buildDnsRouteToProxyForTest(
            fakeDnsEnabled = true,
            proxyServerTag = proxyServerTag,
            rule = com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-google"))
        )

        assertEquals(1, rules.size)
        assertEquals(proxyServerTag, rules[0].server)
        assertEquals(listOf("A", "AAAA"), rules[0].queryType)
    }

    @Test
    override fun testDnsRouteToProxyReturnsProxyDnsRuleWhenFakeDnsEnabled() {
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        val rules = ConfigRepository.buildDnsRouteToProxyForTest(
            fakeDnsEnabled = true,
            proxyServerTag = proxyServerTag,
            rule = com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-geolocation-!cn"))
        )

        assertEquals(1, rules.size)
        assertEquals(proxyServerTag, rules[0].server)
        assertEquals(listOf("A", "AAAA"), rules[0].queryType)
    }

    @Test
    override fun testDnsRouteToNonDirectReturnsSpecificDnsRuleWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.buildDynamicDnsServerTag("SG|官方优选|94ms_2")
        val rules = ConfigRepository.buildDnsRouteToNonDirectForTest(
            fakeDnsEnabled = true,
            serverTag = serverTag,
            rule = com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-geolocation-!cn"))
        )

        assertEquals(1, rules.size)
        assertEquals(serverTag, rules[0].server)
        assertEquals(listOf("A", "AAAA"), rules[0].queryType)
    }

    @Test
    override fun testNonIpDnsFallbackRoutesHttpsAndSvcbToProxyDns() {
        val rule = ConfigRepository.buildNonIpDnsFallbackRuleForTest(
            ConfigRepository.buildDynamicDnsServerTag("PROXY")
        )

        assertEquals("route", rule.action)
        assertEquals(listOf("HTTPS", "SVCB"), rule.queryType)
        assertEquals(ConfigRepository.buildDynamicDnsServerTag("PROXY"), rule.server)
    }

    @Test
    override fun testDnsRouteToDirectOnlyRoutesIpQueriesToLocalDns() {
        val rule = ConfigRepository.buildDnsRouteToDirectForTest(
            com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-cn"))
        )

        assertEquals("route", rule.action)
        assertEquals("local", rule.server)
        assertEquals(listOf("A", "AAAA"), rule.queryType)
    }

    @Test
    override fun testNormalizeRuleSetUrlAddsRawPrefixForGithubPathOnlyUrl() {
        val normalized = RuleSetRepository.normalizeRuleSetUrl(
            url = "SagerNet/sing-geosite/rule-set/geosite-google.srs",
            mirrorUrl = "https://raw.githubusercontent.com/"
        )

        assertEquals(
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs",
            normalized
        )
    }

    @Test
    override fun testNormalizeRuleSetUrlAddsRawPrefixForLeadingSlashGithubPathOnlyUrl() {
        val normalized = RuleSetRepository.normalizeRuleSetUrl(
            url = "/SagerNet/sing-geosite/rule-set/geosite-google.srs",
            mirrorUrl = "https://raw.githubusercontent.com/"
        )

        assertEquals(
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs",
            normalized
        )
    }

    @Test
    override fun testNormalizeRuleSetForSaveNormalizesRemoteRuleSetUrl() {
        val normalized = RuleSetRepository.normalizeRuleSetForSave(
            ruleSet = RuleSet(
                tag = "geosite-google",
                type = RuleSetType.REMOTE,
                url = "SagerNet/sing-geosite/rule-set/geosite-google.srs"
            ),
            mirrorUrl = "https://raw.githubusercontent.com/"
        )

        assertEquals(
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs",
            normalized.url
        )
    }

    @Test
    override fun testRuleSetDnsPriorityKeepsProxySpecificRulesBeforeDirectCountryRules() {
        val sortedRuleSets = ConfigRepository.sortRuleSetsForDnsAndRoutePriorityForTest(
            listOf(
                RuleSet(
                    tag = "geosite-cn",
                    type = RuleSetType.REMOTE,
                    outboundMode = RuleSetOutboundMode.DIRECT
                ),
                RuleSet(
                    tag = "geosite-google",
                    type = RuleSetType.REMOTE,
                    outboundMode = RuleSetOutboundMode.PROXY
                ),
                RuleSet(
                    tag = "geosite-geolocation-!cn",
                    type = RuleSetType.REMOTE,
                    outboundMode = RuleSetOutboundMode.PROXY
                )
            )
        )

        assertEquals(
            listOf("geosite-google", "geosite-cn", "geosite-geolocation-!cn"),
            sortedRuleSets.map { it.tag }
        )
    }

    @Test
    override fun testDnsServerTagForFallbackProxyUsesProxyServer() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"),
            fakeDnsEnabled = false
        )

        assertEquals("remote", serverTag)
    }

    @Test
    override fun testDnsServerTagForFallbackProxyUsesDynamicServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"),
            fakeDnsEnabled = true,
            proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("PROXY"), serverTag)
    }

    @Test
    override fun testDnsServerTagForFakeIpExcludeDomainUsesDynamicServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = true
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    override fun testResolveRouteModeForRuleSetUsesProxyDefault() {
        val resolved = ConfigRepository.resolveRouteModeForRuleSetForTest(
            RuleSet(
                tag = "geo-test",
                type = RuleSetType.LOCAL,
                path = "/tmp/geo.srs",
                outboundMode = null
            )
        )

        assertEquals(RuleSetOutboundMode.PROXY, resolved)
    }

    @Test
    override fun testResolveRouteModeForAppGroupUsesDirectDefault() {
        val resolved = ConfigRepository.resolveRouteModeForAppGroupForTest(
            AppGroup(name = "group", outboundMode = null)
        )

        assertEquals(RuleSetOutboundMode.DIRECT, resolved)
    }

    @Test
    override fun testResolveRouteModeForCustomRuleUsesLegacyOutboundDefault() {
        val resolved = ConfigRepository.resolveRouteModeForCustomRuleForTest(
            CustomRule(
                name = "rule",
                type = RuleType.DOMAIN,
                value = "example.com",
                outbound = OutboundTag.BLOCK,
                outboundMode = null
            )
        )

        assertEquals(RuleSetOutboundMode.BLOCK, resolved)
    }

    @Test
    override fun testResolveOutboundSemanticDirect() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepositoryCompanionBase.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.DIRECT,
                value = null,
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.Direct, semantic)
    }

    @Test
    override fun testResolveOutboundSemanticBlock() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepositoryCompanionBase.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.BLOCK,
                value = null,
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.Block, semantic)
    }

    @Test
    override fun testResolveOutboundSemanticProxy() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepositoryCompanionBase.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.PROXY,
                value = null,
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.Proxy, semantic)
    }

    @Test
    override fun testResolveOutboundSemanticNodeValid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepositoryCompanionBase.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.NODE,
                value = "node-id-1",
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { id -> if (id == "node-id-1") "node-tag-1" else null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.RouteTag("node-tag-1"), semantic)
    }

    @Test
    override fun testResolveOutboundSemanticNodeInvalid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepositoryCompanionBase.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.NODE,
                value = "missing-node",
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"), semantic)
    }

    @Test
    override fun testResolveOutboundSemanticProfileValid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepositoryCompanionBase.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.PROFILE,
                value = "profile-1",
                selectorTag = "PROXY",
                outbounds = listOf(com.kunk.singbox.model.Outbound(tag = "P:HK", type = "selector")),
                profiles = listOf(
                    com.kunk.singbox.database.entity.ProfileEntity(
                        id = "profile-1",
                        name = "HK",
                        type = com.kunk.singbox.model.ProfileType.Subscription,
                        url = "",
                        lastUpdated = 0L,
                        enabled = true
                    )
                ),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.RouteTag("P:HK"), semantic)
    }

    @Test
    override fun testResolveOutboundSemanticProfileInvalid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepositoryCompanionBase.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.PROFILE,
                value = "missing-profile",
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"), semantic)
    }

    @Test
    override fun testResolveProfileSelectorDefaultPrefersLowestLatencyOverRememberedNode() {
        val defaultTag = ConfigRepository.resolveProfileSelectorDefault(
            nodeIds = listOf("node-1", "node-2", "node-3"),
            nodeTagMap = mapOf(
                "node-1" to "tag-a",
                "node-2" to "tag-b",
                "node-3" to "tag-c"
            ),
            rememberedNodeId = "node-2",
            savedNodeLatencies = mapOf(
                "node-1" to 20L,
                "node-2" to 10L,
                "node-3" to 5L
            )
        )

        assertEquals("tag-c", defaultTag)
    }
}
