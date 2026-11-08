package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RouteConfig
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.SingBoxConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRepositorySubscriptionMergeTest {
    @Test
    fun updateKeepsManualNodesWhileRefreshingAndRemovingSubscriptionNodes() {
        val initial = mergeSubscriptionConfig(null, SingBoxConfig(outbounds = listOf(
            Outbound(type = "socks", tag = "sub", server = "old.example"),
            Outbound(type = "socks", tag = "removed")
        )))
        val manual = Outbound(type = "trojan", tag = "manual", server = "manual.example", password = "secret")
        val previous = ConfigRepository.buildConfigWithOutboundsPreservingProfileSettings(
            initial,
            initial.outbounds.orEmpty() + manual
        )
        val updated = SingBoxConfig(outbounds = listOf(
            Outbound(type = "socks", tag = "sub", server = "new.example"),
            Outbound(type = "socks", tag = "added")
        ))

        val merged = mergeSubscriptionConfig(previous, updated)

        assertEquals(listOf("sub", "added", "manual"), merged.outbounds.orEmpty().map(Outbound::tag))
        assertEquals("new.example", merged.outbounds.orEmpty().first().server)
        assertEquals(manual, merged.outbounds.orEmpty().last())
        assertEquals(listOf("sub", "added"), merged.subscriptionOutboundTags)
        val gson = Gson()
        val reloaded = gson.fromJson(gson.toJson(merged), SingBoxConfig::class.java)
        assertEquals(merged, mergeSubscriptionConfig(reloaded, updated))
    }

    @Test
    fun sameNameKeepsManualNodeIdentityAndRewritesSubscriptionReferences() {
        val manual = Outbound(type = "trojan", tag = "node", server = "manual.example")
        val previous = SingBoxConfig(
            outbounds = listOf(manual, manual.copy(tag = "node_1")),
            subscriptionOutboundTags = emptyList()
        )
        val updated = SingBoxConfig(
            outbounds = listOf(
                Outbound(type = "socks", tag = "node", server = "sub.example"),
                Outbound(type = "selector", tag = "PROXY", outbounds = listOf("node"), default = "node"),
                Outbound(type = "urltest", tag = "auto", outbounds = listOf("node")),
                Outbound(type = "socks", tag = "child", detour = "node")
            ),
            route = RouteConfig(
                rules = listOf(RouteRule(packageName = listOf("com.example.app"), outbound = "node")),
                finalOutbound = "PROXY"
            ),
            dns = DnsConfig(servers = listOf(DnsServer(tag = "dns", type = "local", detour = "node")))
        )

        val merged = mergeSubscriptionConfig(previous, updated)
        val outbounds = merged.outbounds.orEmpty().associateBy(Outbound::tag)

        assertEquals(manual, outbounds["node"])
        assertEquals("sub.example", outbounds["node_2"]?.server)
        assertEquals(listOf("node_2"), outbounds["PROXY"]?.outbounds)
        assertEquals("node_2", outbounds["PROXY"]?.default)
        assertEquals(listOf("node_2"), outbounds["auto"]?.outbounds)
        assertNull(outbounds["auto"]?.default)
        assertEquals("node_2", outbounds["child"]?.detour)
        assertEquals("node_2", merged.route?.rules?.single()?.outbound)
        assertEquals("node_2", merged.dns?.servers?.single()?.detour)
        assertEquals(outbounds.size, merged.outbounds?.size)
        assertEquals(merged, mergeSubscriptionConfig(merged, updated))
        ConfigRepository.requireValidApplicationRoutes(merged.route, outbounds.keys)
        outbounds.values.forEach { outbound ->
            assertTrue(outbound.outbounds.orEmpty().all(outbounds::containsKey))
            assertTrue(outbound.detour == null || outbound.detour in outbounds)
        }
    }

    @Test
    fun renamedManualNodeSurvivesAndDeletedManualNodeDoesNotReturn() {
        val previous = SingBoxConfig(
            outbounds = listOf(Outbound(type = "socks", tag = "sub"), Outbound(type = "socks", tag = "manual")),
            subscriptionOutboundTags = listOf("sub")
        )
        val (renamed, finalTag) = replaceOutboundInConfig(
            previous,
            "manual",
            Outbound(type = "socks", tag = "renamed", server = "edited.example")
        )
        val updated = SingBoxConfig(outbounds = listOf(Outbound(type = "socks", tag = "sub")))
        val merged = mergeSubscriptionConfig(renamed, updated)

        assertEquals("edited.example", merged.outbounds.orEmpty().first { it.tag == finalTag }.server)
        val afterDelete = mergeSubscriptionConfig(removeOutboundFromConfig(merged, finalTag), updated)
        assertEquals(listOf("sub"), afterDelete.outbounds.orEmpty().map(Outbound::tag))
    }

    @Test
    fun manuallyReusingDeletedSubscriptionNameDoesNotLoseNodeOwnership() {
        val initial = mergeSubscriptionConfig(null, SingBoxConfig(outbounds = listOf(
            Outbound(type = "socks", tag = "node", server = "old.example")
        )))
        val deleted = removeOutboundFromConfig(initial, "node")
        assertEquals(emptyList<String>(), deleted.subscriptionOutboundTags)
        val manual = Outbound(type = "trojan", tag = "node", server = "manual.example")
        val previous = ConfigRepository.buildConfigWithOutboundsPreservingProfileSettings(deleted, listOf(manual))
        val updated = SingBoxConfig(outbounds = listOf(
            Outbound(type = "socks", tag = "node", server = "new.example")
        ))

        val merged = mergeSubscriptionConfig(previous, updated)

        assertEquals(manual, merged.outbounds.orEmpty().first { it.tag == "node" })
        assertEquals("new.example", merged.outbounds.orEmpty().first { it.tag == "node_1" }.server)
        assertEquals(listOf("node_1"), merged.subscriptionOutboundTags)
    }

    @Test
    fun legacyConfigRetainsMissingNodesOnceAndRecordsNewSubscriptionBaseline() {
        val legacy = SingBoxConfig(outbounds = listOf(
            Outbound(type = "socks", tag = "sub"),
            Outbound(type = "trojan", tag = "manual"),
            Outbound(type = "selector", tag = "old-selector", outbounds = listOf("sub", "manual")),
            Outbound(type = "direct", tag = "direct")
        ))
        val updated = SingBoxConfig(outbounds = listOf(
            Outbound(type = "socks", tag = "sub"),
            Outbound(type = "socks", tag = "new-sub")
        ))

        val merged = mergeSubscriptionConfig(legacy, updated)
        assertEquals(listOf("sub", "new-sub", "manual"), merged.outbounds.orEmpty().map(Outbound::tag))
        val second = mergeSubscriptionConfig(
            merged,
            SingBoxConfig(outbounds = listOf(Outbound(type = "socks", tag = "new-sub")))
        )
        assertEquals(listOf("new-sub", "manual"), second.outbounds.orEmpty().map(Outbound::tag))
    }

    @Test
    fun initialImportRecordsActualTagsInsteadOfRemoteSourceMetadata() {
        val incoming = SingBoxConfig(
            outbounds = listOf(Outbound(type = "socks", tag = "sub")),
            subscriptionOutboundTags = listOf("untrusted")
        )

        assertEquals(listOf("sub"), mergeSubscriptionConfig(null, incoming).subscriptionOutboundTags)
    }

    @Test
    fun subscriptionEntryRecordsBaselineAndRuntimeSerializationRemovesIt() {
        val sourceDir = File("src/main/java/com/kunk/singbox/repository/configrepo")
        val importSource = File(sourceDir, "ConfigRepositoryPart2.kt").readText()
        val runtimeSource = File(sourceDir, "ConfigRepositoryPart6.kt").readText()
        val updateSource = File(sourceDir, "ConfigRepositoryPart5.kt").readText()

        assertTrue(importSource.contains("mergeSubscriptionConfig(null, it)"))
        assertTrue(updateSource.contains("mergeSubscriptionConfig(loadConfig(profile.id), deduplicateTags(config))"))
        val stripBody = runtimeSource.substringAfter("fun ConfigRepository.stripInternalMetadata(config:")
            .substringBefore("fun ConfigRepository.stripInternalMetadata(outbound:")
        assertTrue(stripBody.contains("subscriptionOutboundTags = null"))
        assertTrue(updateSource.contains("validateConfig(stripInternalMetadata(runConfig))"))
        assertTrue(updateSource.contains("gson.toJson(stripInternalMetadata(runConfig))"))
        assertFalse(Gson().toJson(SingBoxConfig()).contains("_kunbox_subscription_outbound_tags"))
    }
}
