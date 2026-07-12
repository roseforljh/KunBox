package com.kunk.singbox.repository

import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetConfig
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRepositoryRoutingP1Test {

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
    fun mixedInboundDnsProtocolRuleRunsAfterNonFinalSniffWhileTunPortStaysFirst() {
        val rules = ConfigRepository.buildRunRouteRulesForTest(
            settings = AppSettings(routingMode = RoutingMode.RULE),
            selectorTag = "PROXY",
            outbounds = listOf(Outbound(type = "selector", tag = "PROXY")),
            profiles = emptyList(),
            validRuleSets = emptyList()
        )

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
