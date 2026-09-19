package com.kunk.singbox.repository

import com.kunk.singbox.core.filterRuntimeOutbounds
import com.kunk.singbox.model.Outbound
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigStartupTest {
    @Test
    fun healthySubscriptionUsesOneNativeValidationRegardlessOfNodeCount() {
        val nodes = (1..200).map { Outbound(type = "trojan", tag = "node-$it") }
        val selector = Outbound(type = "selector", tag = "PROXY", outbounds = nodes.map { it.tag })
        val outbounds = nodes + selector
        var calls = 0

        val result = filterRuntimeOutbounds(outbounds) { candidates ->
            calls++
            assertEquals(nodes, candidates)
            true
        }

        assertSame(outbounds, result)
        assertEquals(1, calls)
    }

    @Test
    fun invalidNodeFallsBackToIndividualFilteringWithoutDroppingHealthyNodes() {
        val good = Outbound(type = "trojan", tag = "good")
        val bad = Outbound(type = "trojan", tag = "bad")
        val direct = Outbound(type = "direct", tag = "direct")
        val selector = Outbound(type = "selector", tag = "PROXY", outbounds = listOf("good", "bad"))
        val calls = mutableListOf<List<String>>()

        val result = filterRuntimeOutbounds(listOf(good, bad, direct, selector)) { candidates ->
            calls.add(candidates.map { it.tag })
            candidates.none { it.tag == "bad" }
        }

        assertEquals(listOf(good, direct, selector), result)
        assertEquals(listOf(listOf("good", "bad"), listOf("good"), listOf("bad")), calls)
    }

    @Test
    fun emptyOrBuiltinOnlyConfigurationDoesNotInvokeNativeValidation() {
        val direct = listOf(Outbound(type = "direct", tag = "direct"))
        assertTrue(filterRuntimeOutbounds(emptyList()) { error("Unexpected native validation") }.isEmpty())
        assertSame(direct, filterRuntimeOutbounds(direct) { error("Unexpected native validation") })
    }

    @Test
    fun batchValidationKeepsDependentNodesTogether() {
        val hop = Outbound(type = "socks", tag = "hop")
        val node = Outbound(type = "trojan", tag = "node", detour = "hop")
        val outbounds = listOf(node, hop)

        assertSame(outbounds, filterRuntimeOutbounds(outbounds) { candidates ->
            candidates.all { it.detour == null || candidates.any { target -> target.tag == it.detour } }
        })
    }

    @Test
    fun startupUsesSavedProfileAndValidatesCompleteConfigBeforeWriting() {
        val body = File("src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart5.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("internal suspend fun ConfigRepository.generateConfigFile(")
            .substringBefore("internal fun ConfigRepository.buildRuntimeNodeMappings(")

        assertTrue(body.contains("val config = loadConfig(activeId)"))
        assertFalse(body.contains("loadConfigWithLegacyEchRepair("))
        assertFalse(body.contains("fetchAndParseSubscription("))
        val validationIndex = body.indexOf("singBoxCore.validateConfig(stripInternalMetadata(runConfig))")
        val failureIndex = body.indexOf("validation.exceptionOrNull()")
        val writeIndex = body.indexOf("ConfigRepository.writeTextFileAtomically(configFile")
        assertTrue(validationIndex >= 0)
        assertTrue(failureIndex > validationIndex)
        assertTrue(writeIndex > failureIndex)
    }
}
