package com.kunk.singbox.utils.parser

import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SubscriptionManagerTest {
    private val nodeLinkParser = NodeLinkParser(Gson())
    private val base64Parser = Base64Parser { nodeLinkParser.parse(it) }

    @Test
    fun deduplicateOutboundsKeepsSameEndpointWithDifferentTransport() {
        val first = Outbound(
            type = "vless",
            tag = "JP 01",
            server = "127.0.0.1",
            serverPort = 443,
            uuid = "7bcc4323-5318-4a7e-b806-be38a09e6d4e",
            tls = TlsConfig(enabled = true, serverName = "jp.example.com"),
            transport = TransportConfig(type = "ws", path = "/liangxin/jp1", host = listOf("jp.example.com"))
        )
        val second = first.copy(
            tag = "US 01",
            tls = first.tls?.copy(serverName = "us.example.com"),
            transport = first.transport?.copy(path = "/liangxin/us1", host = listOf("us.example.com"))
        )

        val result = SubscriptionManager.deduplicateOutbounds(listOf(first, second))

        assertEquals(listOf(first, second), result)
    }

    @Test
    fun deduplicateOutboundsDropsExactDuplicate() {
        val node = Outbound(
            type = "hysteria2",
            tag = "HK 01",
            server = "hy.example.com",
            serverPort = 60000,
            password = "password"
        )

        val result = SubscriptionManager.deduplicateOutbounds(listOf(node, node))

        assertEquals(listOf(node), result)
    }

    @Test
    fun singBoxParserPreservesDnsFromFullJsonConfig() {
        val json = """
            {
              "dns": {
                "servers": [
                  {
                    "tag": "remote",
                    "type": "https",
                    "server": "1.1.1.1"
                  }
                ],
                "rules": [
                  {
                    "domain_suffix": ["example.com"],
                    "server": "remote"
                  }
                ],
                "final": "remote"
              },
              "outbounds": [
                {
                  "type": "direct",
                  "tag": "direct"
                }
              ]
            }
        """.trimIndent()

        val config = SingBoxParser(com.google.gson.Gson()).parse(json)

        assertNotNull(config)
        assertEquals("remote", config?.dns?.servers?.firstOrNull()?.tag)
        assertEquals("remote", config?.dns?.rules?.firstOrNull()?.server)
        assertEquals("remote", config?.dns?.finalServer)
        assertEquals("direct", config?.outbounds?.firstOrNull()?.tag)
    }

    @Test
    fun base64ParserDecodesUnpaddedBase64Subscription() {
        val rawLink = "vless://uuid@example.com:443?type=ws&path=%2F&host=cdn.example.com#B64"
        val encoded = java.util.Base64.getEncoder()
            .withoutPadding()
            .encodeToString(rawLink.toByteArray(Charsets.UTF_8))

        val config = base64Parser.parse(encoded)

        assertNotNull(config)
        assertEquals("B64", config?.outbounds?.singleOrNull()?.tag)
        assertEquals("cdn.example.com", config?.outbounds?.singleOrNull()?.transport?.headers?.get("Host"))
    }

    @Test
    fun base64ParserTrimsTrailingMarkdownPunctuationFromExtractedLinks() {
        val content = "节点：(vless://uuid@example.com:443?type=ws&path=%2F&host=cdn.example.com)"

        val config = base64Parser.parse(content)

        assertNotNull(config)
        assertEquals("cdn.example.com", config?.outbounds?.singleOrNull()?.transport?.headers?.get("Host"))
        assertEquals("cdn.example.com", config?.outbounds?.singleOrNull()?.tls?.serverName)
    }

    @Test
    fun parseSubscriptionNameFromHeaderPrefersProfileTitle() {
        val title = "My Best Subscription"
        val disposition = "attachment; filename=\"ignore_me.yaml\""
        val name = SubscriptionManager.parseSubscriptionNameFromHeader(title, disposition)
        assertEquals("My Best Subscription", name)
    }

    @Test
    fun parseSubscriptionNameFromHeaderDecodesUrlEncodedProfileTitle() {
        val title = "%E6%88%91%E7%9A%84%E8%AE%A2%E9%98%85"
        val name = SubscriptionManager.parseSubscriptionNameFromHeader(title, null)
        assertEquals("我的订阅", name)
    }

    @Test
    fun parseSubscriptionNameFromHeaderParsesContentDispositionFilename() {
        val disposition = "attachment; filename=\"Config.yaml\""
        val name = SubscriptionManager.parseSubscriptionNameFromHeader(null, disposition)
        assertEquals("Config", name)
    }

    @Test
    fun parseSubscriptionNameFromHeaderParsesContentDispositionFilenameUtf8() {
        val disposition = "attachment; filename*=UTF-8''%E6%88%91%E7%9A%84%E9%85%8D%E7%BD%AE.yml"
        val name = SubscriptionManager.parseSubscriptionNameFromHeader(null, disposition)
        assertEquals("我的配置", name)
    }
}
