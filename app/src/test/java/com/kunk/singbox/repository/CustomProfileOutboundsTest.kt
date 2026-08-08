package com.kunk.singbox.repository

import com.kunk.singbox.model.Outbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProfileOutboundsTest {

    @Test
    fun copiedAndAddedNodesAreCombinedWithoutAllowingAnEmptyProfile() {
        val copied = Outbound(type = "vless", tag = "copied")
        val added = Outbound(type = "http", tag = "added")

        val outbounds = combineCustomProfileOutbounds(listOf(copied), listOf(added))

        assertEquals(listOf("copied", "added", "direct"), outbounds.map(Outbound::tag))
        assertTrue(combineCustomProfileOutbounds(emptyList(), emptyList()).isEmpty())
    }
}
