package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetConfig
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
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

        assertEquals("dns.example.com", normalized)
    }

    @Test
    override fun testNormalizeRemoteDnsReplacesBlankValue() {
        val normalized = ConfigRepository.normalizeRemoteDns("   ")

        assertEquals(AppSettings.DEFAULT_REMOTE_DNS, normalized)
    }

    @Test
    override fun testNormalizeRemoteDnsKeepsCloudflareIpDoh() {
        val normalized = ConfigRepository.normalizeRemoteDns("https://1.1.1.1/dns-query")

        assertEquals("https://1.1.1.1/dns-query", normalized)
    }

    @Test
    override fun testNormalizeRemoteDnsKeepsCloudflareIpv6Doh() {
        val normalized = ConfigRepository.normalizeRemoteDns("https://[2606:4700:4700::1111]/dns-query")

        assertEquals("https://[2606:4700:4700::1111]/dns-query", normalized)
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
        assertEquals(certificatePem, anytls?.tls?.certificate?.singleOrNull()?.trim())
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
        assertEquals(listOf(certificatePem), anytls?.tls?.certificate)
        assertEquals(listOf(caPem), anytls?.tls?.ca)
        assertEquals(listOf(keyPem), anytls?.tls?.key)
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
        assertTrue(json.contains("\"domain_resolver\":\"dns-bootstrap\""))
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
        val server = ConfigRepository.buildDynamicRemoteDnsServer(
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
        val server = ConfigRepository.buildDynamicRemoteDnsServer(
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
        val server = ConfigRepository.buildDynamicRemoteDnsServer(
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
        val server = ConfigRepository.buildFakeIpDnsServer("198.18.0.0/15")

        assertEquals("fakeip-dns", server.tag)
        assertEquals("fakeip", server.type)
        assertEquals("198.18.0.0/15", server.inet4Range)
        assertEquals("fc00::/18", server.inet6Range)
    }

    @Test
    override fun testBuildFakeIpDnsServerForTestPreservesCustomIpv4AndIpv6Ranges() {
        val server = ConfigRepository.buildFakeIpDnsServer("198.18.0.0/15,fd00::/16")

        assertEquals("198.18.0.0/15", server.inet4Range)
        assertEquals("fd00::/16", server.inet6Range)
    }

    @Test
    override fun testBuildFakeIpDnsServerForTestRecoversNullRange() {
        val server = ConfigRepository.buildFakeIpDnsServer(null)

        assertEquals("198.18.0.0/15", server.inet4Range)
        assertEquals("fc00::/18", server.inet6Range)
    }

    @Test
    override fun testDnsServerTagForRouteTagUsesDynamicServerWhenFakeDnsDisabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemantic(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = false
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    override fun testDnsServerTagForRouteTagUsesDynamicServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemantic(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = true
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    override fun testDnsServerTagForProxyUsesProxyServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemantic(
            semantic = ConfigRepository.OutboundSemantic.Proxy,
            fakeDnsEnabled = true,
            proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("PROXY"), serverTag)
    }

    @Test
    override fun testDnsRouteToProxyUsesProxyDnsForIpQueriesWhenFakeDnsEnabled() {
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        val rules = ConfigRepository.buildDnsRouteToNonDirect(
            fakeDnsEnabled = true,
            serverTag = proxyServerTag,
            rule = com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-google"))
        )

        assertEquals(1, rules.size)
        assertEquals(proxyServerTag, rules[0].server)
        assertNull(rules[0].queryType)
    }

    @Test
    override fun testDnsRouteToProxyReturnsProxyDnsRuleWhenFakeDnsEnabled() {
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        val rules = ConfigRepository.buildDnsRouteToNonDirect(
            fakeDnsEnabled = true,
            serverTag = proxyServerTag,
            rule = com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-geolocation-!cn"))
        )

        assertEquals(1, rules.size)
        assertEquals(proxyServerTag, rules[0].server)
        assertNull(rules[0].queryType)
    }

    @Test
    override fun testDnsRouteToNonDirectReturnsSpecificDnsRuleWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.buildDynamicDnsServerTag("SG|官方优选|94ms_2")
        val rules = ConfigRepository.buildDnsRouteToNonDirect(
            fakeDnsEnabled = true,
            serverTag = serverTag,
            rule = com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-geolocation-!cn"))
        )

        assertEquals(1, rules.size)
        assertEquals(serverTag, rules[0].server)
        assertNull(rules[0].queryType)
    }

    @Test
    override fun testDnsRouteToDirectOnlyRoutesIpQueriesToLocalDns() {
        val rule = ConfigRepository.buildDnsRouteToDirect("local",
            com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-cn"))
        )

        assertEquals("route", rule.action)
        assertEquals("local", rule.server)
        assertNull(rule.queryType)
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
    override fun testRuleSetDnsOrderMatchesPersistedOrder() {
        val rules = ConfigRepository.buildOrderedDnsRules(
            entries = listOf(
                DnsRule(ruleSet = listOf("geosite-cn")) to ConfigRepository.OutboundSemantic.Direct,
                DnsRule(ruleSet = listOf("geosite-google")) to ConfigRepository.OutboundSemantic.Proxy,
                DnsRule(ruleSet = listOf("geosite-geolocation-!cn")) to
                    ConfigRepository.OutboundSemantic.Proxy
            ),
            fakeDnsEnabled = false,
            directServerTag = "local",
            proxyServerTag = "remote"
        )

        assertEquals(
            listOf("geosite-cn", "geosite-google", "geosite-geolocation-!cn"),
            rules.map { it.ruleSet?.single() }
        )
        assertEquals(listOf("local", "remote", "remote"), rules.map { it.server })
    }

    @Test
    override fun testUserDnsRulePriorityMatchesRouteOrder() {
        val domainRule = DnsRule(domain = listOf("example.com"))
        val appRule = DnsRule(packageName = listOf("com.example.app"))
        val ruleSetRule = DnsRule(ruleSet = listOf("geosite-cn"))

        val rules = ConfigRepository.mergeUserDnsRules(
            domainRules = listOf(domainRule),
            appRules = listOf(appRule),
            ruleSetRules = listOf(ruleSetRule)
        )

        assertEquals(listOf(appRule, domainRule, ruleSetRule), rules)
    }

    @Test
    override fun testRouteRulesDoNotInjectGoogleOverride() {
        val rules = ConfigRepository.buildRunRouteRulesForTest(
            settings = AppSettings(
                ruleSets = listOf(
                    RuleSet(
                        tag = "geosite-cn",
                        type = RuleSetType.REMOTE,
                        outboundMode = RuleSetOutboundMode.DIRECT,
                        enabled = true
                    )
                )
            ),
            selectorTag = "PROXY",
            outbounds = listOf(Outbound(type = "selector", tag = "PROXY")),
            profiles = emptyList(),
            validRuleSets = listOf(RuleSetConfig(tag = "geosite-cn"))
        )

        val countryIndex = rules.indexOfFirst {
            it.ruleSet == listOf("geosite-cn") && it.outbound == "direct"
        }

        assertTrue(countryIndex >= 0)
        assertTrue(rules.none { it.domain.orEmpty().contains("connectivitycheck.gstatic.com") })
    }

    @Test
    override fun testDnsServerTagForFallbackProxyUsesProxyServer() {
        val serverTag = ConfigRepository.dnsServerTagForSemantic(
            semantic = ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"),
            fakeDnsEnabled = false
        )

        assertEquals("remote", serverTag)
    }

    @Test
    override fun testDnsServerTagForFallbackProxyUsesDynamicServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemantic(
            semantic = ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"),
            fakeDnsEnabled = true,
            proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("PROXY"), serverTag)
    }

    @Test
    override fun testDnsServerTagForFakeIpExcludeDomainUsesDynamicServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemantic(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = true
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    override fun testResolveRouteModeForRuleSetUsesProxyDefault() {
        val resolved = ConfigRepository.resolveRuleSetOutboundMode(null)

        assertEquals(RuleSetOutboundMode.PROXY, resolved)
    }

    @Test
    override fun testResolveRouteModeForAppGroupUsesDirectDefault() {
        val resolved = ConfigRepository.resolveAppGroupOutboundMode(null)

        assertEquals(RuleSetOutboundMode.DIRECT, resolved)
    }

    @Test
    override fun testResolveRouteModeForCustomRuleUsesLegacyOutboundDefault() {
        val resolved = ConfigRepository.resolveCustomRuleOutboundMode(null, OutboundTag.BLOCK)

        assertEquals(RuleSetOutboundMode.BLOCK, resolved)
    }

    @Test
    override fun testResolveOutboundSemanticDirect() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.OutboundSemanticTestInput(
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
            ConfigRepository.OutboundSemanticTestInput(
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
            ConfigRepository.OutboundSemanticTestInput(
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
            ConfigRepository.OutboundSemanticTestInput(
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
            ConfigRepository.OutboundSemanticTestInput(
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
            ConfigRepository.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.PROFILE,
                value = "profile-1",
                selectorTag = "PROXY",
                outbounds = listOf(
                    com.kunk.singbox.model.Outbound(tag = "P:HK#profile-1", type = "selector")
                ),
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

        assertEquals(ConfigRepository.OutboundSemantic.RouteTag("P:HK#profile-1"), semantic)
    }

    @Test
    override fun testResolveOutboundSemanticProfileInvalid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.OutboundSemanticTestInput(
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
}
