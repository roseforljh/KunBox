package com.kunk.singbox.repository

import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.Endpoint
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RouteConfig
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.RuleSetConfig
import com.kunk.singbox.model.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRepositoryNodeMutationTest {

    @Test
    fun renameUsesFinalUniqueTagAndRewritesEveryOutboundReference() {
        val config = fullReferenceConfig()

        val (updated, finalTag) = replaceOutboundInConfig(
            config = config,
            oldTag = "Old",
            newOutbound = Outbound(type = "socks", tag = "New", server = "new.example")
        )

        assertEquals("New_1", finalTag)
        assertTrue(updated.outbounds.orEmpty().any { it.tag == finalTag && it.server == "new.example" })
        val selector = updated.outbounds.orEmpty().first { it.tag == "selector" }
        assertEquals(listOf(finalTag), selector.outbounds)
        assertEquals(finalTag, selector.default)
        assertEquals(finalTag, updated.outbounds.orEmpty().first { it.tag == "child" }.detour)
        assertEquals(finalTag, updated.route?.finalOutbound)
        assertEquals(finalTag, updated.route?.rules?.single()?.outbound)
        assertEquals(finalTag, updated.route?.ruleSet?.single()?.downloadDetour)
        assertEquals(finalTag, updated.dns?.servers?.single()?.detour)
        assertEquals(listOf(finalTag, "direct"), updated.dns?.rules?.single()?.outboundRaw)
        assertEquals(finalTag, updated.endpoints?.single()?.detour)
    }

    @Test
    fun deleteRemovesEveryOutboundReferenceWithoutLeavingInvalidGroups() {
        val config = fullReferenceConfig().copy(
            route = fullReferenceConfig().route?.copy(
                rules = listOf(
                    RouteRule(domain = listOf("deleted.example"), outbound = "Old"),
                    RouteRule(domain = listOf("keep.example"), outbound = "direct")
                )
            ),
            dns = fullReferenceConfig().dns?.copy(
                rules = listOf(
                    DnsRule(domain = listOf("deleted.example"), outboundRaw = "Old"),
                    DnsRule(domain = listOf("keep.example"), outboundRaw = listOf("Old", "direct"))
                )
            )
        )

        val updated = removeOutboundFromConfig(config, "Old")

        assertFalse(updated.outbounds.orEmpty().any { it.tag == "Old" })
        val selector = updated.outbounds.orEmpty().first { it.tag == "selector" }
        assertEquals(listOf("direct"), selector.outbounds)
        assertEquals("direct", selector.default)
        assertNull(updated.outbounds.orEmpty().first { it.tag == "child" }.detour)
        assertNull(updated.route?.finalOutbound)
        assertEquals(listOf("direct"), updated.route?.rules?.mapNotNull(RouteRule::outbound))
        assertNull(updated.route?.ruleSet?.single()?.downloadDetour)
        assertNull(updated.dns?.servers?.single()?.detour)
        assertEquals(1, updated.dns?.rules?.size)
        assertEquals(listOf("direct"), updated.dns?.rules?.single()?.outboundRaw)
        assertNull(updated.endpoints?.single()?.detour)
    }

    private fun fullReferenceConfig(): SingBoxConfig = SingBoxConfig(
        outbounds = listOf(
            Outbound(type = "socks", tag = "Old", server = "old.example"),
            Outbound(type = "socks", tag = "New", server = "existing.example"),
            Outbound(type = "selector", tag = "selector", outbounds = listOf("Old"), default = "Old"),
            Outbound(type = "socks", tag = "child", server = "child.example", detour = "Old"),
            Outbound(type = "direct", tag = "direct")
        ),
        endpoints = listOf(Endpoint(type = "wireguard", tag = "endpoint", detour = "Old")),
        dns = DnsConfig(
            servers = listOf(DnsServer(tag = "dns", type = "local", detour = "Old")),
            rules = listOf(DnsRule(domain = listOf("example.com"), outboundRaw = listOf("Old", "direct")))
        ),
        route = RouteConfig(
            rules = listOf(RouteRule(domain = listOf("example.com"), outbound = "Old")),
            ruleSet = listOf(RuleSetConfig(tag = "remote", downloadDetour = "Old")),
            finalOutbound = "Old"
        )
    )
}
