package com.kunk.singbox.service.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteGroupSelectorTest {

    @Test
    fun testCandidateRpcUsesAutoGroupWhenAutoGroupPresent() {
        val target = RouteGroupSelector.Companion.RouteGroupTarget(
            groupTag = "P:cf",
            candidates = listOf("US|官方优选|218ms"),
            fallbackTag = "PROXY",
            autoGroupTag = "P:cf#AUTO"
        )

        assertEquals("P:cf#AUTO", RouteGroupSelector.selectorTagForCandidateRpc(target))
        assertEquals("P:cf", RouteGroupSelector.selectorTagForFallbackRpc(target))
    }

    @Test
    fun testResolveCurrentCandidateSelectionPrefersAutoGroupSelection() {
        val selected = RouteGroupSelector.resolveCurrentCandidateSelection(
            groupSelectedTag = "P:cf#AUTO",
            autoGroupSelectedTag = "US|官方优选|218ms",
            candidates = listOf("US|官方优选|218ms", "JP|官方优选|300ms")
        )

        assertEquals("US|官方优选|218ms", selected)
    }

    @Test
    fun testResolveCurrentCandidateSelectionIgnoresOuterSelectorAlias() {
        val selected = RouteGroupSelector.resolveCurrentCandidateSelection(
            groupSelectedTag = "P:cf#AUTO",
            autoGroupSelectedTag = null,
            candidates = listOf("US|官方优选|218ms", "JP|官方优选|300ms")
        )

        assertNull(selected)
    }
}
