package com.kunk.singbox.repository

import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.RootAppRoutingPlanCompiler
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetConfig
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRepositoryRoutingP1Test {

    @Test
    fun explicitNodeRootLaneAlwaysUsesThePhysicalOutbound() {
        val semantic = ConfigRepository.resolveAppOutboundSemanticStrict(
            mode = RuleSetOutboundMode.NODE,
            value = "node-id-1",
            context = ConfigRepositoryOutboundSemanticContext(
                selectorTag = "PROXY",
                outbounds = listOf(
                    Outbound(type = "selector", tag = "PROXY", outbounds = listOf("physical-node")),
                    Outbound(type = "socks", tag = "physical-node")
                ),
                profiles = emptyList(),
                nodeTagResolver = { id -> if (id == "node-id-1") "physical-node" else null }
            ),
            label = "应用「Telegram」"
        )
        val assignment = ConfigRepository.toRootAppRoutingAssignment(
            packageNames = listOf("org.telegram.messenger"),
            semantic = semantic,
            selectorTag = "PROXY",
            sourceLabel = "Telegram"
        )
        val lane = RootAppRoutingPlanCompiler.compile(AppSettings(), listOf(assignment), 1L).lanes.single()

        assertEquals("physical-node", lane.outboundTag)
        assertFalse(lane.outboundTag == "PROXY" || lane.outboundTag.startsWith("F:"))
    }

    @Test
    fun profileRouteTagsAreStableUniqueAndReadableForDuplicateNames() {
        val firstTag = ConfigRepository.buildProfileRouteTag("profile-a", "共享线路")
        val secondTag = ConfigRepository.buildProfileRouteTag("profile-b", "共享线路")

        assertEquals("P:共享线路#profile-a", firstTag)
        assertEquals(firstTag, ConfigRepository.buildProfileRouteTag("profile-a", "共享线路"))
        assertNotEquals(firstTag, secondTag)
        assertTrue(firstTag.startsWith("P:共享线路#"))
    }

    @Test
    fun profileRouteResolutionUsesTheSameUniqueTagAsGeneration() {
        val profiles = listOf(
            profile(id = "profile-a", name = "共享线路"),
            profile(id = "profile-b", name = "共享线路")
        )
        val outbounds = profiles.map { profile ->
            Outbound(
                type = "selector",
                tag = ConfigRepository.buildProfileRouteTag(profile.id, profile.name)
            )
        }

        val semantics = profiles.map { profile ->
            ConfigRepository.resolveOutboundSemanticForTest(
                ConfigRepository.OutboundSemanticTestInput(
                    mode = RuleSetOutboundMode.PROFILE,
                    value = profile.id,
                    selectorTag = "PROXY",
                    outbounds = outbounds,
                    profiles = profiles,
                    nodeTagResolver = { null }
                )
            )
        }

        assertEquals(
            listOf(
                ConfigRepository.OutboundSemantic.RouteTag("P:共享线路#profile-a"),
                ConfigRepository.OutboundSemantic.RouteTag("P:共享线路#profile-b")
            ),
            semantics
        )
    }

    @Test
    fun routeRuleSetsKeepPersistedDragOrderEvenWhenBlockComesFirst() {
        val ruleSets = listOf(
            RuleSet(
                tag = "block-first",
                type = RuleSetType.LOCAL,
                outboundMode = RuleSetOutboundMode.BLOCK
            ),
            RuleSet(
                tag = "proxy-second",
                type = RuleSetType.LOCAL,
                outboundMode = RuleSetOutboundMode.PROXY
            )
        )
        val rules = ConfigRepository.buildRunRouteRulesForTest(
            settings = AppSettings(routingMode = RoutingMode.RULE, ruleSets = ruleSets),
            selectorTag = "PROXY",
            outbounds = listOf(Outbound(type = "selector", tag = "PROXY")),
            profiles = emptyList(),
            validRuleSets = ruleSets.map { RuleSetConfig(tag = it.tag) }
        ).filter { !it.ruleSet.isNullOrEmpty() }

        assertEquals(listOf("block-first", "proxy-second"), rules.map { it.ruleSet?.single() })
        assertEquals("reject", rules.first().action)
        assertEquals("PROXY", rules.last().outbound)
    }

    @Test
    fun specificServiceRuleSetsPrecedeGeolocationGenericRules() {
        val ruleSets = listOf(
            RuleSet(
                tag = "geosite-geolocation-!cn",
                type = RuleSetType.LOCAL,
                outboundMode = RuleSetOutboundMode.PROXY
            ),
            RuleSet(
                tag = "geosite-cn",
                type = RuleSetType.LOCAL,
                outboundMode = RuleSetOutboundMode.DIRECT
            ),
            RuleSet(
                tag = "geosite-openai",
                type = RuleSetType.LOCAL,
                outboundMode = RuleSetOutboundMode.PROFILE,
                outboundValue = "profile-1"
            ),
            RuleSet(
                tag = "geosite-google",
                type = RuleSetType.LOCAL,
                outboundMode = RuleSetOutboundMode.PROFILE,
                outboundValue = "profile-1"
            ),
            RuleSet(
                tag = "geosite-category-ads-all",
                type = RuleSetType.LOCAL,
                outboundMode = RuleSetOutboundMode.BLOCK
            )
        )
        val profileTag = ConfigRepository.buildProfileRouteTag("profile-1", "鹰")
        val rules = ConfigRepository.buildRunRouteRulesForTest(
            settings = AppSettings(routingMode = RoutingMode.RULE, ruleSets = ruleSets),
            selectorTag = "PROXY",
            outbounds = listOf(
                Outbound(type = "selector", tag = "PROXY"),
                Outbound(type = "selector", tag = profileTag)
            ),
            profiles = listOf(profile(id = "profile-1", name = "鹰")),
            validRuleSets = ruleSets.map { RuleSetConfig(tag = it.tag) }
        ).filter { !it.ruleSet.isNullOrEmpty() }
            .map { it.ruleSet?.single() }

        assertEquals(
            listOf(
                "geosite-openai",
                "geosite-google",
                "geosite-category-ads-all",
                "geosite-cn",
                "geosite-geolocation-!cn"
            ),
            rules
        )
    }

    @Test
    fun dnsRuleSetsKeepPersistedDragOrderWhenBlockComesFirst() {
        val rules = ConfigRepository.buildOrderedDnsRules(
            entries = listOf(
                com.kunk.singbox.model.DnsRule(ruleSet = listOf("block-first")) to
                    ConfigRepository.OutboundSemantic.Block,
                com.kunk.singbox.model.DnsRule(ruleSet = listOf("proxy-second")) to
                    ConfigRepository.OutboundSemantic.Proxy
            ),
            fakeDnsEnabled = false,
            directServerTag = "local",
            proxyServerTag = "remote"
        )

        assertEquals(listOf("block-first", "proxy-second"), rules.map { it.ruleSet?.single() })
        assertEquals("predefined", rules.first().action)
        assertEquals("remote", rules.last().server)
    }

    @Test
    fun geositeCustomRuleProducesMatchingDnsRule() {
        val rule = ConfigRepository.buildCustomDnsRuleMatcher(
            RuleType.GEOSITE,
            listOf("cn", "private")
        )

        assertEquals(listOf("cn", "private"), rule?.geosite)
    }

    @Test
    fun routingModeRuleKeepsEveryUserRuleBeforeProxyFallback() {
        val base = listOf(RouteRule(action = "sniff"))
        val bypass = listOf(RouteRule(ipIsPrivate = true, outbound = "direct"))
        val custom = listOf(RouteRule(domain = listOf("example.cn"), outbound = "direct"))
        val app = listOf(RouteRule(packageName = listOf("com.example.app"), outbound = "direct"))
        val ruleSet = listOf(RouteRule(ruleSet = listOf("geosite-cn"), outbound = "direct"))
        val fallback = listOf(RouteRule(outbound = "PROXY"))

        val rules = ConfigRepository.selectRunRouteRulesStatic(
            settings = AppSettings(routingMode = RoutingMode.RULE),
            baseRules = base,
            bypassLanRules = bypass,
            customDomainRules = custom,
            appRoutingRules = app,
            customRuleSetRules = ruleSet,
            defaultRuleCatchAll = fallback
        )

        assertEquals(base + bypass + app + custom + ruleSet + fallback, rules)
        val fallbackIndex = rules.indexOf(fallback.single())
        assertTrue(rules.indexOf(custom.single()) < fallbackIndex)
        assertTrue(rules.indexOf(app.single()) < fallbackIndex)
        assertTrue(rules.indexOf(ruleSet.single()) < fallbackIndex)
    }

    @Test
    fun appNodeTargetDoesNotSilentlyFallbackToGlobalProxy() {
        val failure = runCatching {
            ConfigRepository.resolveAppOutboundSemanticStrict(
                mode = RuleSetOutboundMode.NODE,
                value = "profile::missing-node",
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = "PROXY",
                    outbounds = listOf(Outbound(type = "selector", tag = "PROXY")),
                    profiles = emptyList(),
                    nodeTagResolver = { null }
                ),
                label = "应用分组「Telegram」"
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("已阻止回退到全局代理"))
    }

    @Test
    fun finalApplicationRouteRejectsMissingOrConflictingTargets() {
        val missingTarget = runCatching {
            ConfigRepository.requireValidApplicationRoutes(
                route = com.kunk.singbox.model.RouteConfig(
                    rules = listOf(RouteRule(packageName = listOf("org.telegram.messenger"), outbound = "missing"))
                ),
                availableTags = setOf("PROXY", "germany")
            )
        }
        assertTrue(missingTarget.isFailure)

        val conflicting = runCatching {
            ConfigRepository.requireValidApplicationRoutes(
                route = com.kunk.singbox.model.RouteConfig(
                    rules = listOf(
                        RouteRule(packageName = listOf("org.telegram.messenger"), outbound = "germany"),
                        RouteRule(packageName = listOf("org.telegram.messenger"), outbound = "PROXY")
                    )
                ),
                availableTags = setOf("PROXY", "germany")
            )
        }
        assertTrue(conflicting.isFailure)
    }

    @Test
    fun globalModesIgnoreEveryUserRuleFamily() {
        val base = listOf(RouteRule(action = "sniff"))
        val userRule = listOf(RouteRule(domain = listOf("example.cn"), outbound = "direct"))

        val proxyRules = ConfigRepository.selectRunRouteRulesStatic(
            settings = AppSettings(routingMode = RoutingMode.GLOBAL_PROXY),
            baseRules = base,
            bypassLanRules = userRule,
            customDomainRules = userRule,
            appRoutingRules = userRule,
            customRuleSetRules = userRule,
            defaultRuleCatchAll = listOf(RouteRule(outbound = "PROXY"))
        )
        val directRules = ConfigRepository.selectRunRouteRulesStatic(
            settings = AppSettings(routingMode = RoutingMode.GLOBAL_DIRECT),
            baseRules = base,
            bypassLanRules = userRule,
            customDomainRules = userRule,
            appRoutingRules = userRule,
            customRuleSetRules = userRule,
            defaultRuleCatchAll = listOf(RouteRule(outbound = "PROXY"))
        )

        assertEquals(base, proxyRules)
        assertEquals(base + RouteRule(outbound = "direct"), directRules)
    }

    @Test
    fun mixedInboundDnsProtocolRuleRunsAfterNonFinalSniffWhileTunPortStaysFirst() {
        val rules = ConfigRepository.buildRunRouteRulesForTest(
            settings = AppSettings(routingMode = RoutingMode.RULE),
            selectorTag = "PROXY",
            outbounds = listOf(Outbound(type = "selector", tag = "PROXY")),
            profiles = emptyList(),
            validRuleSets = emptyList()
        )

        val tunDnsIndex = rules.indexOfFirst { it.inbound == listOf("tun-in") && it.port == listOf(53) }
        val sniffIndex = rules.indexOfFirst { it.inbound == listOf("tun-in", "mixed-in") && it.action == "sniff" }
        val protocolDnsIndex = rules.indexOfFirst { it.protocol == listOf("dns") && it.action == "hijack-dns" }
        val dotIndex = rules.indexOfFirst { it.port == listOf(853) && it.action == "reject" }
        val stunIndex = rules.indexOfFirst { it.protocol == listOf("stun") && it.action == "reject" }

        assertTrue(tunDnsIndex >= 0)
        assertEquals(tunDnsIndex + 1, sniffIndex)
        assertEquals(sniffIndex + 1, protocolDnsIndex)
        assertEquals(protocolDnsIndex + 1, dotIndex)
        assertEquals(dotIndex + 1, stunIndex)
        assertTrue(rules.take(tunDnsIndex).all { !it.ipCidr.isNullOrEmpty() })
    }

    private fun profile(id: String, name: String): ProfileEntity {
        return ProfileEntity(
            id = id,
            name = name,
            type = ProfileType.Subscription,
            url = "",
            lastUpdated = 0L,
            enabled = true
        )
    }
}
