package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.utils.parser.NodeLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProfileOutboundsTest {
    private val parser = NodeLinkParser(Gson())
    private val vless = "vless://11111111-1111-4111-8111-111111111111@one.example.com:443#one"
    private val trojan = "trojan://password@two.example.com:443#two"

    @Test
    fun pastedLinksAreParsedInOrderAndAllIncludedInCustomProfile() {
        val content = "$vless\r\n\r\nvmess://not-base64\nhttps://example.com/subscription\n$trojan"
        val pasted = parser.parseLinks(content)
        val existing = Outbound(type = "socks", tag = "existing")
        val outbounds = combineCustomProfileOutbounds(emptyList(), listOf(existing) + pasted)

        assertEquals(listOf("existing", "one", "two", "direct"), outbounds.map(Outbound::tag))
        assertEquals(listOf("one.example.com", "two.example.com"), pasted.map(Outbound::server))
    }

    @Test
    fun sameLineLinksDoNotSplitUrlOrCommaInsideAParameter() {
        val http = "https://proxy.example.com:443?proxy_path=https://target.example.com/a,b#three"
        val pasted = parser.parseLinks("$vless $trojan，$http")

        assertEquals(listOf("one", "two", "three"), pasted.map(Outbound::tag))
        assertEquals("https://target.example.com/a,b", pasted.last().path)
    }

    @Test
    fun singleLinkStillWorksAndInvalidInputAddsNothing() {
        assertEquals(listOf(requireNotNull(parser.parse(vless))), parser.parseLinks("  `$vless`  "))
        assertTrue(parser.parseLinks("\nvmess://not-base64 https://example.com/subscription\n").isEmpty())
        assertTrue(parser.parseLinks("\r\n \t").isEmpty())
    }

    @Test
    fun copiedAndAddedNodesAreCombinedWithoutAllowingAnEmptyProfile() {
        val copied = Outbound(type = "vless", tag = "copied")
        val added = Outbound(type = "http", tag = "added")

        val outbounds = combineCustomProfileOutbounds(listOf(copied), listOf(added))

        assertEquals(listOf("copied", "added", "direct"), outbounds.map(Outbound::tag))
        assertTrue(combineCustomProfileOutbounds(emptyList(), emptyList()).isEmpty())
    }
}
