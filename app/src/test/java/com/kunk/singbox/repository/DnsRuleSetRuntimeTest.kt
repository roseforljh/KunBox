package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsRuleSetRuntimeTest {
    @Test
    fun subscriptionDnsRulesUseOnlyRuntimeRuleSets() {
        val dns = Gson().fromJson(
            """
            {
              "rules": [
                {"rule_set":["geosite-cn"],"server":"local"},
                {"rule_set":["category-ads-all"],"server":"block"},
                {"domain_suffix":["example.org"],"server":"remote"}
              ]
            }
            """.trimIndent(),
            DnsConfig::class.java
        )
        val rules = dns.rules.orEmpty()
        val serverTags = setOf("local", "block", "remote")
        val normalizedRules = rules.map { it.copy(action = "route") }

        assertEquals(
            normalizedRules.takeLast(1),
            ConfigRepository.sanitizeDnsRulesForRuntime(rules, serverTags, availableRuleSetTags = emptySet())
        )
        assertEquals(
            listOf(normalizedRules.first(), normalizedRules.last()),
            ConfigRepository.sanitizeDnsRulesForRuntime(
                rules, serverTags, availableRuleSetTags = setOf("geosite-cn")
            )
        )
        assertEquals(
            normalizedRules,
            ConfigRepository.sanitizeDnsRulesForRuntime(
                rules, serverTags, availableRuleSetTags = setOf("geosite-cn", "category-ads-all")
            )
        )
    }

    @Test
    fun missingRuleSetDropsEntireLogicalOrInvertedRuleWithoutBroadeningMatchers() {
        val validMatcher = DnsRule(domainSuffix = listOf("example.org"))
        val missingMatcher = DnsRule(ruleSet = listOf("geosite-cn"))
        val logical = DnsRule(
            type = "logical", mode = "and", rules = listOf(validMatcher, missingMatcher), server = "local"
        )
        val rules = listOf(
            logical,
            logical.copy(mode = "or", invert = true),
            missingMatcher.copy(invert = true, server = "local"),
            missingMatcher.copy(ruleSet = listOf("known", "geosite-cn"), server = "local")
        )

        assertTrue(
            ConfigRepository.sanitizeDnsRulesForRuntime(
                rules, setOf("local"), availableRuleSetTags = setOf("known")
            ).isEmpty()
        )
        assertEquals(
            rules.map { it.copy(action = "route") },
            ConfigRepository.sanitizeDnsRulesForRuntime(
                rules, setOf("local"), availableRuleSetTags = setOf("known", "geosite-cn")
            )
        )
    }

    @Test
    fun latencyDnsWithoutRuleSetsKeepsDomainRulesAndFallback() {
        val domainRule = DnsRule(domain = listOf("example.org"), server = "local")
        val runtime = SingBoxCore.buildLatencyTestDnsConfig(
            settings = AppSettings(),
            outbounds = emptyList(),
            dnsOverride = DnsConfig(
                rules = listOf(DnsRule(ruleSet = listOf("geosite-cn"), server = "local"), domainRule)
            )
        )

        assertEquals(domainRule.copy(action = "route"), runtime.rules?.first())
        assertTrue(runtime.rules.orEmpty().all { it.ruleSet.isNullOrEmpty() })
        assertEquals(listOf("A", "AAAA"), runtime.rules?.last()?.queryType)
    }
}
