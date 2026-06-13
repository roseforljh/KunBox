package com.kunk.singbox.utils.parser

import org.junit.Assert.*
import org.junit.Test

abstract class NodeLinkParserProxyAndEdgeTest : NodeLinkParserTestBase() {
// ==================== HTTP/HTTPS ====================

    @Test
    fun testParseHttpsProxy() {
        val link = "https://user:pass@proxy.example.com:8443#HTTPSProxy"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("http", outbound?.type)
        assertEquals("HTTPSProxy", outbound?.tag)
        assertEquals("proxy.example.com", outbound?.server)
        assertEquals(8443, outbound?.serverPort)
        assertEquals("user", outbound?.username)
        assertEquals("pass", outbound?.password)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
    }

    @Test
    fun testParseHttpsProxyIpKeepsServerNameNull() {
        val link = "https://user:pass@1.2.3.4:8443#HTTPSProxyIp"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("1.2.3.4", outbound?.server)
        assertNull(outbound?.tls?.serverName)
    }

    @Test
    fun testParseHttpProxy() {
        val link = "http://user:pass@proxy.example.com:8080#HTTPProxy"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("http", outbound?.type)
        assertEquals(8080, outbound?.serverPort)
        assertNull(outbound?.tls)
    }

    @Test
    fun testParseHttpProxyWithoutAuth() {
        val link = "http://proxy.example.com:3128#NoAuthProxy"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertNull(outbound?.username)
        assertNull(outbound?.password)
    }

    @Test
    fun testDoesNotParseSubscriptionUrlAsHttpsProxy() {
        val link = "https://sub.example.com/api/v1/client/subscribe?token=abc123"
        val outbound = parser.parse(link)

        assertNull(outbound)
    }

    @Test
    fun testDoesNotParsePortedSubscriptionUrlAsHttpsProxy() {
        val link = "https://sub.example.com:8443/api/v1/client/subscribe?token=abc123"
        val outbound = parser.parse(link)

        assertNull(outbound)
    }

    // ==================== SOCKS5 ====================

    @Test
    fun testParseSocks5Basic() {
        val link = "socks5://user:pass@socks.example.com:1080#SOCKS5Node"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("socks", outbound?.type)
        assertEquals("SOCKS5Node", outbound?.tag)
        assertEquals("socks.example.com", outbound?.server)
        assertEquals(1080, outbound?.serverPort)
        assertEquals("user", outbound?.username)
        assertEquals("pass", outbound?.password)
    }

    @Test
    fun testParseSocksShortScheme() {
        val link = "socks://socks.example.com:1080#SocksShort"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("socks", outbound?.type)
        assertEquals(1080, outbound?.serverPort)
    }

    // ==================== Edge Cases ====================

    @Test
    fun testParseUnknownScheme() {
        val link = "unknown://something"
        val outbound = parser.parse(link)

        assertNull(outbound)
    }

    @Test
    fun testParseEmptyLink() {
        val outbound = parser.parse("")
        assertNull(outbound)
    }

    @Test
    fun testParseMalformedLink() {
        val outbound = parser.parse("not-a-valid-link")
        assertNull(outbound)
    }

    @Test
    fun testParseSpecialCharactersInPassword() {
        val link = "trojan://p%40ss%23word%21@trojan.example.com:443#SpecialChars"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        // URL 编码的特殊字符: @ = %40, # = %23, ! = %21
        assertEquals("p@ss#word!", outbound?.password)
    }

    @Test
    fun testParseSpacesInQueryParams() {
        // 测试 sanitizeUri 对空格的处理
        val link = "vless://uuid@server:443?security = tls & type = ws#SpacesNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
    }

    @Test
    fun testRealSubscriptionNodeWithoutExplicitSecurity() {
        val link = "vless://68d55b3f-c4f1-481a-8bfb-e483004f2c15@198.41.223.11:443" +
            "?flow=&type=ws&path=%2F&host=cm.kuz7.com&sni=cm.kuz7.com" +
            "#SG%7C%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%89%7C90ms"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
        assertEquals("198.41.223.11", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("SG|官方优选|90ms", outbound?.tag)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
        assertEquals("cm.kuz7.com", outbound?.tls?.serverName)
        assertNull(outbound?.tls?.ech)
        assertNull(outbound?.flow)
        assertEquals("ws", outbound?.transport?.type)
        assertEquals("/", outbound?.transport?.path)
        assertEquals(mapOf("Host" to "cm.kuz7.com"), outbound?.transport?.headers)
    }
}
