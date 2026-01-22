package com.kunk.singbox.utils

import com.google.gson.GsonBuilder
import com.kunk.singbox.utils.parser.ClashYamlParser
import org.junit.Assert.*
import org.junit.Test

class ClashConfigParserTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    // 使用别名以保持与旧代码的兼容性
    private object ClashConfigParser {
        fun parse(yaml: String) = ClashYamlParser().parse(yaml)
    }

    @Test
    fun testParseSimpleClashConfig() {
        val yaml = """
            proxies:
              - name: "ss1"
                type: ss
                server: 1.2.3.4
                port: 443
                cipher: aes-256-gcm
                password: "pass"
            proxy-groups:
              - name: "PROXY"
                type: select
                proxies:
                  - ss1
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        assertNotNull(config)
        assertNotNull(config?.outbounds)

        val outbounds = config!!.outbounds!!
        assertEquals(2, outbounds.size) // ss1 + PROXY selector

        val ss1 = outbounds.find { it.tag == "ss1" }
        assertNotNull(ss1)
        assertEquals("shadowsocks", ss1?.type)
        assertEquals("1.2.3.4", ss1?.server)

        val proxyGroup = outbounds.find { it.tag == "PROXY" }
        assertNotNull(proxyGroup)
        assertEquals("selector", proxyGroup?.type)
        assertTrue(proxyGroup?.outbounds?.contains("ss1") == true)
    }

    @Test
    fun testParseVLessWithReality() {
        val yaml = """
            proxies:
              - name: "vless-reality"
                type: vless
                server: example.com
                port: 443
                uuid: uuid-123
                network: ws
                ws-opts:
                  path: /path?ed=2048
                  headers:
                    Host: example.com
                tls: true
                reality-opts:
                  public-key: "pbk"
                  short-id: "sid"
                client-fingerprint: chrome
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        assertNotNull(config)

        val vless = config?.outbounds?.find { it.tag == "vless-reality" }
        assertNotNull(vless)
        assertEquals("vless", vless?.type)
        assertNotNull(vless?.tls)
        assertEquals(true, vless?.tls?.enabled)
        assertNotNull(vless?.tls?.reality)
        assertEquals("pbk", vless?.tls?.reality?.publicKey)
        assertEquals("chrome", vless?.tls?.utls?.fingerprint)

        assertNotNull(vless?.transport)
        assertEquals("ws", vless?.transport?.type)
        assertEquals("/path?ed=2048", vless?.transport?.path)
    }

    @Test
    fun testParseHttpWithTls() {
        val yaml = """
            proxies:
              - name: "美国西雅图"
                port: 443
                server: proxy.example.com
                tls: true
                type: http
                username: user123
                password: pass456
                skip-cert-verify: true
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        assertNotNull(config)

        val http = config?.outbounds?.find { it.tag == "美国西雅图" }
        assertNotNull(http)
        assertEquals("http", http?.type)
        assertEquals("proxy.example.com", http?.server)
        assertEquals(443, http?.serverPort)
        assertEquals("user123", http?.username)
        assertEquals("pass456", http?.password)

        // TLS 配置验证
        assertNotNull(http?.tls)
        assertEquals(true, http?.tls?.enabled)
        assertEquals("proxy.example.com", http?.tls?.serverName)
        assertEquals(true, http?.tls?.insecure)

        // 打印生成的 JSON 以便调试
        println("HTTP+TLS Outbound JSON:")
        println(gson.toJson(http))
    }

    @Test
    fun testParseShadowsocksWithShadowTLS() {
        val yaml = """
            proxies:
              - name: "ss-shadowtls"
                type: ss
                server: 14.3.28.11
                port: 2245
                cipher: aes-256-gcm
                password: "vzx0"
                udp: true
                plugin: shadow-tls
                client-fingerprint: chrome
                plugin-opts:
                  password: "ENX"
                  version: 3
                  host: "sns-video-qn.xhscdn.com"
                smux:
                  enabled: true
                  padding: true
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        assertNotNull(config)
        assertNotNull(config?.outbounds)

        val outbounds = config!!.outbounds!!

        println("Parsed outbounds:")
        outbounds.forEach { println("  - ${it.tag}: ${it.type}") }

        assertEquals(2, outbounds.size)

        val ss = outbounds.find { it.tag == "ss-shadowtls" }
        assertNotNull("SS outbound not found", ss)
        assertEquals("shadowsocks", ss?.type)
        assertEquals("aes-256-gcm", ss?.method)
        assertEquals("vzx0", ss?.password)
        assertNotNull("SS should have detour", ss?.detour)
        assertEquals("ss-shadowtls_shadowtls", ss?.detour)
        assertNotNull("SS should have multiplex config", ss?.multiplex)
        assertEquals(true, ss?.multiplex?.enabled)
        assertEquals(true, ss?.multiplex?.padding)

        val stls = outbounds.find { it.tag == "ss-shadowtls_shadowtls" }
        assertNotNull("ShadowTLS outbound not found", stls)
        assertEquals("shadowtls", stls?.type)
        assertEquals("14.3.28.11", stls?.server)
        assertEquals(2245, stls?.serverPort)
        assertEquals(3, stls?.version)
        assertEquals("ENX", stls?.password)
        assertNotNull("ShadowTLS should have TLS config", stls?.tls)
        assertEquals("sns-video-qn.xhscdn.com", stls?.tls?.serverName)
        assertNotNull("ShadowTLS should have uTLS fingerprint", stls?.tls?.utls)
        assertEquals("chrome", stls?.tls?.utls?.fingerprint)

        println("\nSS Outbound JSON:")
        println(gson.toJson(ss))
        println("\nShadowTLS Outbound JSON:")
        println(gson.toJson(stls))
    }

    @Test
    fun testParseUserProvidedShadowTLSConfig() {
        // host 使用列表格式 [xxx] 测试
        val yaml = """
            proxies:
              - name: "BWH-ShadowTLS"
                type: ss
                cipher: aes-256-gcm
                password: vzx0fcb5MWN-aze1arp
                port: 20004
                server: 144.34.238.115
                udp: true
                plugin: shadow-tls
                client-fingerprint: chrome
                plugin-opts:
                  password: ENX5apd5upw*amj8gky
                  version: 3
                  host:
                    - sns-video-qn.xhscdn.com
                smux:
                  enabled: true
                  padding: true
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        assertNotNull(config)

        val outbounds = config!!.outbounds!!
        println("User config parsed outbounds:")
        outbounds.forEach { println("  - ${it.tag}: ${it.type}") }

        val ss = outbounds.find { it.type == "shadowsocks" }
        val stls = outbounds.find { it.type == "shadowtls" }

        assertNotNull("SS outbound missing", ss)
        assertNotNull("ShadowTLS outbound missing", stls)

        assertEquals("aes-256-gcm", ss?.method)
        assertEquals("vzx0fcb5MWN-aze1arp", ss?.password)
        assertEquals("BWH-ShadowTLS_shadowtls", ss?.detour)
        assertEquals(true, ss?.multiplex?.enabled)
        assertEquals(true, ss?.multiplex?.padding)

        assertEquals("144.34.238.115", stls?.server)
        assertEquals(20004, stls?.serverPort)
        assertEquals(3, stls?.version)
        assertEquals("ENX5apd5upw*amj8gky", stls?.password)
        assertEquals("sns-video-qn.xhscdn.com", stls?.tls?.serverName)
        assertEquals("chrome", stls?.tls?.utls?.fingerprint)

        println("\n=== Final sing-box config ===")
        println("SS Outbound:")
        println(gson.toJson(ss))
        println("\nShadowTLS Outbound:")
        println(gson.toJson(stls))
    }
}
