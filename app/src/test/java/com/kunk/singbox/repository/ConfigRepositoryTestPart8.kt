package com.kunk.singbox.repository

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.DnsStrategy
import com.kunk.singbox.model.EchConfig
import com.kunk.singbox.model.Endpoint
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetConfig
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.RouteConfig
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.model.resolveDnsStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryTestPart8 : ConfigRepositoryTestPart7() {
    @Test
    override fun testBuildProfileRouteGroupOutboundsCreatesNestedAutoStructure() {
        val outbounds = ConfigRepository.buildProfileRouteGroupOutbounds(
            groupTag = "P:HK",
            nodeTags = listOf("node-a", "node-b"),
            testUrl = "https://probe.example/204",
            autoSelectionEnabled = true,
            preferredNodeTag = "node-a"
        )

        assertEquals(2, outbounds.size)

        val autoGroup = outbounds[0]
        assertEquals("urltest", autoGroup.type)
        assertEquals("P:HK#AUTO", autoGroup.tag)
        assertEquals(listOf("node-a", "node-b"), autoGroup.outbounds)
        assertNull(autoGroup.default)
        assertEquals("https://probe.example/204", autoGroup.url)
        assertEquals("10m", autoGroup.interval)
        assertEquals(50, autoGroup.tolerance)

        val outerGroup = outbounds[1]
        assertEquals("selector", outerGroup.type)
        assertEquals("P:HK", outerGroup.tag)
        assertEquals(listOf("P:HK#AUTO", "node-a", "node-b"), outerGroup.outbounds)
        assertEquals("P:HK#AUTO", outerGroup.default)
    }

    @Test
    fun buildProfileRouteGroupOutboundsExcludesDisabledNodeFromProfileSelectorAndUrlTest() {
        val outbounds = ConfigRepository.buildProfileRouteGroupOutbounds(
            groupTag = "P:HK",
            nodeTags = listOf("regular", "metered"),
            eligibleNodeTags = listOf("regular"),
            testUrl = "https://probe.example/204",
            autoSelectionEnabled = true,
            preferredNodeTag = "metered"
        )

        assertEquals(listOf("regular"), outbounds.first { it.type == "urltest" }.outbounds)
        assertEquals(
            listOf("P:HK#AUTO", "regular"),
            outbounds.first { it.type == "selector" }.outbounds
        )
        assertEquals("P:HK#AUTO", outbounds.first { it.type == "selector" }.default)
    }

    @Test
    fun filterAutomaticGroupCandidatesKeepsManualReferences() {
        val filtered = ConfigRepository.filterAutomaticGroupCandidates(
            outbounds = listOf(
                Outbound(type = "urltest", tag = "AUTO", outbounds = listOf("regular", "metered")),
                Outbound(type = "selector", tag = "MANUAL", outbounds = listOf("regular", "metered"))
            ),
            excludedNodeTags = setOf("metered")
        )

        assertEquals(listOf("regular"), filtered.first { it.tag == "AUTO" }.outbounds)
        assertEquals(listOf("regular", "metered"), filtered.first { it.tag == "MANUAL" }.outbounds)
    }

    @Test
    fun buildProfileRouteGroupOutboundsSkipsProfileWhenNoEligibleCandidateExists() {
        val outbounds = ConfigRepository.buildProfileRouteGroupOutbounds(
            groupTag = "P:HK",
            nodeTags = listOf("metered"),
            eligibleNodeTags = emptyList(),
            testUrl = "https://probe.example/204",
            autoSelectionEnabled = true,
            preferredNodeTag = "metered"
        )

        assertTrue(outbounds.isEmpty())
    }

    @Test
    fun buildProfileRouteGroupOutboundsOmitsUrlTestInManualMode() {
        val outbounds = ConfigRepository.buildProfileRouteGroupOutbounds(
            groupTag = "P:HK",
            nodeTags = listOf("node-b", "node-a"),
            testUrl = "https://probe.example/204",
            autoSelectionEnabled = false,
            preferredNodeTag = "node-a"
        )

        assertEquals(1, outbounds.size)
        assertEquals("selector", outbounds.single().type)
        assertEquals(listOf("node-b", "node-a"), outbounds.single().outbounds)
        assertEquals("node-a", outbounds.single().default)
    }

    @Test
    fun pruneUnreachableGroupOutboundsRemovesIsolatedSelectorAndUrlTest() {
        val pruned = ConfigRepository.pruneUnreachableGroupOutbounds(
            outbounds = listOf(
                Outbound(type = "direct", tag = "direct"),
                Outbound(type = "shadowsocks", tag = "node-a"),
                Outbound(type = "urltest", tag = "orphan#AUTO", outbounds = listOf("node-a")),
                Outbound(type = "selector", tag = "orphan", outbounds = listOf("orphan#AUTO"))
            ),
            route = RouteConfig(finalOutbound = "direct"),
            dns = DnsConfig()
        )

        assertEquals(listOf("direct", "node-a"), pruned.map { it.tag })
    }

    @Test
    fun pruneUnreachableGroupOutboundsKeepsProxyAutoChain() {
        val pruned = ConfigRepository.pruneUnreachableGroupOutbounds(
            outbounds = listOf(
                Outbound(type = "shadowsocks", tag = "node-a"),
                Outbound(type = "shadowsocks", tag = "node-b"),
                Outbound(type = "urltest", tag = "P:HK#AUTO", outbounds = listOf("node-a", "node-b")),
                Outbound(type = "selector", tag = "P:HK", outbounds = listOf("P:HK#AUTO", "node-a")),
                Outbound(type = "selector", tag = "PROXY", outbounds = listOf("P:HK"))
            ),
            route = RouteConfig(finalOutbound = "PROXY"),
            dns = DnsConfig()
        )

        assertEquals(
            listOf("node-a", "node-b", "P:HK#AUTO", "P:HK", "PROXY"),
            pruned.map { it.tag }
        )
    }

    @Test
    fun pruneUnreachableGroupOutboundsKeepsRouteAndDnsRoots() {
        val groupTags = listOf("rule-group", "download-group", "dns-group")
        val pruned = ConfigRepository.pruneUnreachableGroupOutbounds(
            outbounds = listOf(Outbound(type = "shadowsocks", tag = "node-a")) +
                groupTags.map { tag -> Outbound(type = "selector", tag = tag, outbounds = listOf("node-a")) },
            route = RouteConfig(
                rules = listOf(RouteRule(outbound = "rule-group")),
                ruleSet = listOf(RuleSetConfig(downloadDetour = "download-group"))
            ),
            dns = DnsConfig(servers = listOf(DnsServer(tag = "remote", detour = "dns-group")))
        )

        assertTrue(pruned.map { it.tag }.containsAll(groupTags))
    }

    @Test
    fun pruneUnreachableGroupOutboundsKeepsOutboundAndEndpointDetours() {
        val pruned = ConfigRepository.pruneUnreachableGroupOutbounds(
            outbounds = listOf(
                Outbound(type = "selector", tag = "outbound-detour", outbounds = listOf("node-a")),
                Outbound(type = "selector", tag = "endpoint-detour", outbounds = listOf("node-a")),
                Outbound(type = "shadowsocks", tag = "node-a"),
                Outbound(type = "shadowtls", tag = "wrapped-node", detour = "outbound-detour")
            ),
            route = RouteConfig(finalOutbound = "wrapped-node"),
            dns = DnsConfig(),
            endpoints = listOf(Endpoint(type = "wireguard", tag = "wg-endpoint", detour = "endpoint-detour"))
        )

        assertTrue(pruned.any { it.tag == "outbound-detour" })
        assertTrue(pruned.any { it.tag == "endpoint-detour" })
    }

    @Test
    override fun testApplySelectorSafeOutboundsKeepsUrlTestDefaultNull() {
        val safeOutbounds = ConfigRepository.sanitizeSelectorSafeOutbounds(
            listOf(
                Outbound(
                    type = "urltest",
                    tag = "P:HK#AUTO",
                    outbounds = listOf("node-a", "missing-node"),
                    default = "node-a"
                ),
                Outbound(type = "direct", tag = "direct"),
                Outbound(type = "shadowsocks", tag = "node-a"),
                Outbound(
                    type = "selector",
                    tag = "P:HK",
                    outbounds = listOf("P:HK#AUTO", "missing-selector-ref"),
                    default = "missing-selector-ref"
                )
            )
        )

        val autoGroup = safeOutbounds.first { it.tag == "P:HK#AUTO" }
        assertEquals(listOf("node-a"), autoGroup.outbounds)
        assertNull(autoGroup.default)

        val selectorGroup = safeOutbounds.first { it.tag == "P:HK" }
        assertEquals(listOf("P:HK#AUTO"), selectorGroup.outbounds)
        assertEquals("P:HK#AUTO", selectorGroup.default)
    }

    @Test
    override fun testBuildAppRoutingRulesUsesSemanticRejectForBlockRule() {
        val routeRule = ConfigRepository.toRouteRule(
            ConfigRepository.OutboundSemantic.Block,
            "PROXY"
        )

        assertEquals("reject", routeRule.action)
        assertNull(routeRule.outbound)
    }

    @Test
    override fun testResolveDnsStrategyClampsIpv4OnlyMode() {
        assertEquals(
            "ipv4_only",
            IpVersionMode.IPV4_ONLY.resolveDnsStrategy(DnsStrategy.AUTO)
        )
        assertEquals(
            "ipv4_only",
            IpVersionMode.IPV4_ONLY.resolveDnsStrategy(DnsStrategy.PREFER_IPV6)
        )
        assertEquals(
            "ipv4_only",
            IpVersionMode.IPV4_ONLY.resolveDnsStrategy(DnsStrategy.ONLY_IPV6)
        )
    }

    @Test
    override fun testResolveDnsStrategyClampsIpv6OnlyMode() {
        assertEquals(
            "ipv6_only",
            IpVersionMode.IPV6_ONLY.resolveDnsStrategy(DnsStrategy.AUTO)
        )
        assertEquals(
            "ipv6_only",
            IpVersionMode.IPV6_ONLY.resolveDnsStrategy(DnsStrategy.PREFER_IPV4)
        )
        assertEquals(
            "ipv6_only",
            IpVersionMode.IPV6_ONLY.resolveDnsStrategy(DnsStrategy.ONLY_IPV4)
        )
    }

    @Test
    override fun testResolveDnsStrategyPrefersIpv6InPreferMode() {
        assertEquals(
            "prefer_ipv6",
            IpVersionMode.PREFER_IPV6.resolveDnsStrategy(DnsStrategy.AUTO)
        )
        assertEquals(
            "prefer_ipv4",
            IpVersionMode.PREFER_IPV6.resolveDnsStrategy(DnsStrategy.PREFER_IPV4)
        )
    }

    @Test
    override fun testBuildQuicBlockRuleReturnsEmptyWhenBlockQuicDisabled() {
        val rules = ConfigRepository.buildQuicBlockRuleStatic(AppSettings(blockQuic = false))

        assertTrue(rules.isEmpty())
    }

    @Test
    override fun testBuildQuicBlockRuleOnlyRejectsSniffedQuicWhenBlockQuicEnabled() {
        val rules = ConfigRepository.buildQuicBlockRuleStatic(AppSettings(blockQuic = true))

        assertEquals(1, rules.size)
        assertTrue(rules.any { it.protocol?.contains("quic") == true })
        assertEquals("reject", rules.first().action)
        assertNull(rules.first().outbound)
        assertFalse(rules.any { it.network?.contains("udp") == true && it.port == listOf(443) })
    }

    @Test
    override fun testBuildTunFakeIpDnsRuleReturnsEmptyWhenFakeDnsDisabled() {
        val rules = ConfigRepository.buildTunFakeIpDnsRulesStatic(false)

        assertTrue(rules.isEmpty())
    }

    @Test
    override fun testBuildTunFakeIpDnsRuleRoutesTunAaaaAndAWhenFakeDnsEnabled() {
        val rules = ConfigRepository.buildTunFakeIpDnsRulesStatic(true)

        assertEquals(1, rules.size)
        assertEquals(listOf("A", "AAAA"), rules.first().queryType)
        assertEquals(listOf("tun-in"), rules.first().inbound)
        assertEquals("route", rules.first().action)
        assertEquals("fakeip-dns", rules.first().server)
    }

    @Test
    override fun testBuildEchDnsRulesRoutesHttpsQueryServerNameToGivenDnsServer() {
        val rules = ConfigRepository.buildEchDnsRules(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "cf-node-a",
                    tls = TlsConfig(ech = EchConfig(enabled = true, queryServerName = "cloudflare-ech.com"))
                ),
                Outbound(
                    type = "vless",
                    tag = "cf-node-b",
                    tls = TlsConfig(ech = EchConfig(enabled = true, queryServerName = "cloudflare-ech.com"))
                )
            ),
            serverTag = ConfigRepository.buildDynamicDnsServerTag("cf-node-a")
        )

        assertEquals(1, rules.size)
        assertEquals("route", rules.first().action)
        assertEquals(listOf("cloudflare-ech.com"), rules.first().domain)
        assertEquals(listOf("HTTPS", "SVCB"), rules.first().queryType)
        assertEquals(ConfigRepository.buildDynamicDnsServerTag("cf-node-a"), rules.first().server)
    }

    @Test
    override fun testBuildEchAwareHttpsSvcbRulesRoutesEchBeforeRejectWhenBlockQuicEnabled() {
        val rules = ConfigRepository.buildEchAwareHttpsSvcbDnsRules(
            blockQuic = true,
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "cf-node",
                    tls = TlsConfig(ech = EchConfig(enabled = true, queryServerName = "cloudflare-ech.com"))
                )
            ),
            echQueryServerTag = "dns-bootstrap"
        )

        assertEquals(2, rules.size)
        assertEquals("route", rules[0].action)
        assertEquals(listOf("cloudflare-ech.com"), rules[0].domain)
        assertEquals(listOf("HTTPS", "SVCB"), rules[0].queryType)
        assertEquals("dns-bootstrap", rules[0].server)
        assertEquals("predefined", rules[1].action)
        assertEquals("NOERROR", rules[1].rcode?.asString)
        assertEquals(listOf("HTTPS", "SVCB"), rules[1].queryType)
    }

    @Test
    override fun testResolveActiveEchDnsServerRequiresActiveNode() {
        val outbounds = listOf(
            Outbound(
                type = "vless",
                tag = "plain-node"
            ),
            Outbound(
                type = "vless",
                tag = "ech-node",
                tls = TlsConfig(
                    ech = EchConfig(
                        enabled = true,
                        queryServerName = "cloudflare-ech.com",
                        dnsServer = "https://dns.alidns.com/dns-query"
                    )
                )
            )
        )

        assertNull(ConfigRepository.resolveActiveEchDnsServer("plain-node", outbounds))
        assertEquals(
            "https://dns.alidns.com/dns-query",
            ConfigRepository.resolveActiveEchDnsServer("ech-node", outbounds)
        )
    }

    @Test
    override fun testResolveActiveEchDnsServerFallsBackToUniqueEchResolver() {
        val outbounds = listOf(
            Outbound(
                type = "vless",
                tag = "active-ech-node",
                tls = TlsConfig(
                    ech = EchConfig(
                        enabled = true,
                        queryServerName = "cloudflare-ech.com"
                    )
                )
            ),
            Outbound(
                type = "vless",
                tag = "sibling-ech-node",
                tls = TlsConfig(
                    ech = EchConfig(
                        enabled = true,
                        queryServerName = "cloudflare-ech.com",
                        dnsServer = "https://dns.alidns.com/dns-query"
                    )
                )
            )
        )

        assertEquals(
            "https://dns.alidns.com/dns-query",
            ConfigRepository.resolveActiveEchDnsServer("active-ech-node", outbounds)
        )
    }

    @Test
    override fun testNeedsLegacyEchDnsRepairWhenResolverMetadataMissing() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "legacy-ech-node",
                    tls = TlsConfig(
                        ech = EchConfig(
                            enabled = true,
                            queryServerName = "cloudflare-ech.com"
                        )
                    )
                )
            )
        )

        assertTrue(ConfigRepository.needsLegacyEchDnsRepair(config))
    }

    @Test
    override fun testResolveDefaultRouteDomainResolverUsesBootstrapAlways() {
        assertEquals("dns-bootstrap", ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG)
    }

    @Test
    override fun testResolveRunDnsFinalServerUsesStableRemoteWhenGlobalProxyAndFakeDnsEnabled() {
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("node-b")
        val finalServer = ConfigRepository.resolveRunDnsFinalServerForTest(
            routingMode = RoutingMode.GLOBAL_PROXY,
            defaultRule = DefaultRule.PROXY,
            fakeDnsEnabled = true,
            proxyServerTag = proxyServerTag
        )

        assertEquals("remote", finalServer)
    }

    @Test
    override fun testResolveRunDnsFinalServerUsesProxyDetourWhenRuleProxyAndFakeDnsEnabled() {
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("node-b")
        val finalServer = ConfigRepository.resolveRunDnsFinalServerForTest(
            routingMode = RoutingMode.RULE,
            defaultRule = DefaultRule.PROXY,
            fakeDnsEnabled = true,
            proxyServerTag = proxyServerTag
        )

        assertEquals(proxyServerTag, finalServer)
    }

    @Test
    override fun testResolveProxyDnsDetourTagKeepsSelectorAcrossNodeSwitch() {
        val detourTag = ConfigRepository.resolveProxyDnsDetourTagForTest("PROXY")

        assertEquals("PROXY", detourTag)
    }

    @Test
    override fun testResolveProxyDnsDetourTagKeepsSelectorForUrlTest() {
        val detourTag = ConfigRepository.resolveProxyDnsDetourTagForTest("PROXY")

        assertEquals("PROXY", detourTag)
    }

    @Test
    override fun testBypassLanRulesUseIpIsPrivate() {
        val rules = ConfigRepository.buildBypassLanRulesStatic(AppSettings(bypassLan = true))

        assertEquals(1, rules.size)
        assertEquals(true, rules.first().ipIsPrivate)
        assertEquals("direct", rules.first().outbound)
    }

    @Test
    override fun testHijackDnsRulesCatchTunDnsPortBeforeProtocolSniffing() {
        val rules = ConfigRepository.buildHijackDnsRulesStatic()

        assertEquals(4, rules.size)
        assertEquals(listOf("tun-in"), rules[0].inbound)
        assertEquals(listOf(53), rules[0].port)
        assertEquals("hijack-dns", rules[0].action)
        assertEquals(listOf("tun-in", "mixed-in"), rules[1].inbound)
        assertEquals("sniff", rules[1].action)
        assertEquals(listOf("dns"), rules[2].protocol)
        assertEquals("hijack-dns", rules[2].action)
        assertEquals(listOf(853), rules[3].port)
        assertEquals("reject", rules[3].action)
    }

    @Test
    fun testRootCaptureRulesUseTproxyInbound() {
        val settings = AppSettings(trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT)
        val routeRules = ConfigRepository.buildHijackDnsRulesStatic(settings)
        val dnsRules = ConfigRepository.buildTunFakeIpDnsRulesStatic(true, settings)

        val rootTags = listOf("redirect-in-v4", "tproxy-in-v4", "redirect-in-v6", "tproxy-in-v6")
        assertEquals(rootTags, routeRules[0].inbound)
        assertEquals(rootTags + "mixed-in", routeRules[1].inbound)
        assertEquals(rootTags, dnsRules.single().inbound)
    }

    @Test
    fun testRuntimeRouteBuilderPassesCaptureModeToDnsRules() {
        val source = java.io.File(
            "src/main/java/com/kunk/singbox/repository/ConfigRepository.kt"
        ).readText()

        assertTrue(
            source.contains(
                "val hijackDnsRule = ConfigRepository.buildHijackDnsRulesStatic(settings, rootRoutingPlan)"
            )
        )
    }

    @Test
    override fun testRoutingModeGlobalProxySkipsProfileRuleSetRouteRules() {
        val rules = ConfigRepository.buildRunRouteRulesForTest(
            settings = AppSettings(
                routingMode = RoutingMode.GLOBAL_PROXY,
                ruleSets = listOf(
                    RuleSet(
                        tag = "geosite-google",
                        type = RuleSetType.LOCAL,
                        path = "/tmp/geosite-google.srs",
                        outboundMode = RuleSetOutboundMode.PROFILE,
                        outboundValue = "profile-1",
                        enabled = true
                    )
                )
            ),
            selectorTag = "PROXY",
            outbounds = listOf(
                Outbound(type = "selector", tag = "PROXY"),
                Outbound(type = "selector", tag = "P:鹰#profile-1")
            ),
            profiles = listOf(
                com.kunk.singbox.database.entity.ProfileEntity(
                    id = "profile-1",
                    name = "鹰",
                    type = ProfileType.Subscription,
                    url = "",
                    lastUpdated = 0L,
                    enabled = true
                )
            ),
            validRuleSets = listOf(RuleSetConfig(tag = "geosite-google"))
        )

        val googleRule = rules.firstOrNull { it.ruleSet == listOf("geosite-google") }
        assertNull(googleRule)
    }

    @Test
    override fun testGlobalProxyDnsFinalUsesRemoteServerWhenFakeDnsEnabled() {
        val finalServer = ConfigRepository.resolveRunDnsFinalServerForTest(
            routingMode = RoutingMode.GLOBAL_PROXY,
            defaultRule = DefaultRule.PROXY,
            fakeDnsEnabled = true,
            proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("selected-node")
        )

        assertEquals("remote", finalServer)
    }

    @Test
    override fun testBypassLanRulesDisabledWhenSettingOff() {
        val rules = ConfigRepository.buildBypassLanRulesStatic(AppSettings(bypassLan = false))

        assertTrue(rules.isEmpty())
    }

    @Test
    override fun testMulticastRejectRulesCoverIpv4AndIpv6WhenDualStack() {
        val rules = ConfigRepository.buildMulticastRejectRulesStatic(
            AppSettings(ipVersionMode = IpVersionMode.DUAL_STACK)
        )

        assertEquals(listOf("224.0.0.0/3", "ff00::/8"), rules.first().ipCidr)
        assertEquals("reject", rules.first().action)
    }

    @Test
    override fun testMulticastRejectRulesFollowIpVersionMode() {
        val ipv4Only = ConfigRepository.buildMulticastRejectRulesStatic(
            AppSettings(ipVersionMode = IpVersionMode.IPV4_ONLY)
        )
        val ipv6Only = ConfigRepository.buildMulticastRejectRulesStatic(
            AppSettings(ipVersionMode = IpVersionMode.IPV6_ONLY)
        )

        assertEquals(listOf("224.0.0.0/3"), ipv4Only.first().ipCidr)
        assertEquals(listOf("ff00::/8"), ipv6Only.first().ipCidr)
    }

    @Test
    override fun testAppliedRemoteRuleSetFilterIncludesEnabledRemoteRuleSet() {
        val ruleSet = RuleSet(tag = "remote-enabled", type = RuleSetType.REMOTE, enabled = true)

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "remote-enabled"))
        )

        assertEquals(listOf("remote-enabled"), filtered.map { it.tag })
    }

    @Test
    override fun testAppliedRemoteRuleSetFilterExcludesDisabledRemoteRuleSet() {
        val ruleSet = RuleSet(tag = "remote-disabled", type = RuleSetType.REMOTE, enabled = false)

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "remote-disabled"))
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    override fun testAppliedRemoteRuleSetFilterExcludesRemoteRuleSetOutsideValidTags() {
        val ruleSet = RuleSet(tag = "remote-missing", type = RuleSetType.REMOTE, enabled = true)

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "another-tag"))
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    override fun testAppliedRemoteRuleSetFilterExcludesLocalRuleSet() {
        val ruleSet = RuleSet(
            tag = "local-enabled",
            type = RuleSetType.LOCAL,
            path = "/tmp/local-enabled.srs",
            enabled = true
        )

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "local-enabled"))
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    override fun testAtomicTextWriteReplacesExistingFileAndCleansTempFiles() {
        val tempDir = java.nio.file.Files.createTempDirectory("running_config_write_").toFile()
        val target = java.io.File(tempDir, "running_config.json")
        target.writeText("""{"old":true}""")

        ConfigRepository.writeTextFileAtomically(target, """{"new":true}""")

        assertEquals("""{"new":true}""", target.readText())
        assertFalse(java.io.File(tempDir, "running_config.json.tmp").exists())
        assertFalse(java.io.File(tempDir, "running_config.json.bak").exists())
    }

    @Test
    override fun testAtomicTextWriteDoesNotUseSharedFixedTempPath() {
        val tempDir = java.nio.file.Files.createTempDirectory("running_config_write_unique_").toFile()
        val target = java.io.File(tempDir, "running_config.json")
        val blockedTempPath = java.io.File(tempDir, "running_config.json.tmp")
        target.writeText("""{"old":true}""")
        blockedTempPath.mkdir()

        ConfigRepository.writeTextFileAtomically(target, """{"new":true}""")

        assertEquals("""{"new":true}""", target.readText())
        assertTrue(blockedTempPath.isDirectory)
    }

    @Test
    override fun testDetectRuleSetRuleTypeIpRules() {
        val tempFile =
            createTempRuleSetFile("""
            1.0.1.0/24
            1.0.2.0/23
            192.168.0.0/16
            10.0.0.0/8
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.IP, ruleType)
    }

    @Test
    override fun testDetectRuleSetRuleTypeDomainRules() {
        val tempFile =
            createTempRuleSetFile("""
            domain:google.com
            domain:facebook.com
            geosite:youtube
            domain:twitter.com
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.DOMAIN, ruleType)
    }

    @Test
    override fun testDetectRuleSetRuleTypeMixedRules() {
        val tempFile =
            createTempRuleSetFile("""
            1.0.1.0/24
            domain:google.com
            192.168.0.0/16
            geosite:youtube
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.MIXED, ruleType)
    }

    @Test
    override fun testDetectRuleSetRuleTypeWithIpCidrPrefix() {
        val tempFile =
            createTempRuleSetFile("""
            ip-cidr:1.0.1.0/24
            ip-cidr:1.0.2.0/23
            geoip:cn
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.IP, ruleType)
    }

    @Test
    override fun testDetectRuleSetRuleTypeWithDomainPrefix() {
        val tempFile =
            createTempRuleSetFile("""
            domain:google.com
            domain-suffix:facebook.com
            domain-keyword:twitter
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.DOMAIN, ruleType)
    }

    @Test
    override fun testDetectRuleSetRuleTypeIpv6Rules() {
        val tempFile =
            createTempRuleSetFile("""
            2001:db8::/32
            fe80::/10
            ::1/128
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.IP, ruleType)
    }

    @Test
    override fun testDetectRuleSetRuleTypeEmptyFile() {
        val tempFile = createTempRuleSetFile("")

        val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.UNKNOWN, ruleType)
    }

    @Test
    override fun testDetectRuleSetRuleTypeOnlyComments() {
        val tempFile =
            createTempRuleSetFile("""
            # This is a comment
            // Another comment
            ! Yet another
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.UNKNOWN, ruleType)
    }
}
