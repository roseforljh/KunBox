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
    fun testParseProxyGroupNormalizesClashBuiltinRefs() {
        val yaml = """
            proxies:
              - name: "ss1"
                type: ss
                server: 1.2.3.4
                port: 443
                cipher: aes-256-gcm
                password: "pass"
            proxy-groups:
              - name: "AUTO"
                type: url-test
                proxies:
                  - DIRECT
                  - ss1
                  - REJECT
                  - GLOBAL
                url: http://www.gstatic.com/generate_204
              - name: "PROXY"
                type: select
                proxies:
                  - AUTO
                  - DIRECT
                  - REJECT-DROP
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)

        val autoGroup = config?.outbounds?.find { it.tag == "AUTO" }
        val proxyGroup = config?.outbounds?.find { it.tag == "PROXY" }
        assertEquals(listOf("direct", "ss1"), autoGroup?.outbounds)
        assertEquals(listOf("AUTO", "direct"), proxyGroup?.outbounds)
        assertEquals("AUTO", proxyGroup?.default)
    }

    @Test
    fun testUrlTestRejectsUnsafeProbeUrl() {
        val yaml = """
            proxies:
              - name: "ss1"
                type: ss
                server: 1.2.3.4
                port: 443
                cipher: aes-256-gcm
                password: "pass"
            proxy-groups:
              - name: "LOCAL"
                type: url-test
                proxies:
                  - ss1
                url: file:///data/local/tmp/secret
              - name: "LOOPBACK"
                type: url-test
                proxies:
                  - ss1
                url: http://127.0.0.1:8080/generate_204
              - name: "PRIVATE"
                type: url-test
                proxies:
                  - ss1
                url: http://192.168.1.1/generate_204
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)

        assertEquals("http://www.gstatic.com/generate_204", config?.outbounds?.find { it.tag == "LOCAL" }?.url)
        assertEquals("http://www.gstatic.com/generate_204", config?.outbounds?.find { it.tag == "LOOPBACK" }?.url)
        assertEquals("http://www.gstatic.com/generate_204", config?.outbounds?.find { it.tag == "PRIVATE" }?.url)
    }

    @Test
    fun testParseProxyGroupKeepsProxyNamedDirect() {
        val yaml = """
            proxies:
              - name: "DIRECT"
                type: ss
                server: 1.2.3.4
                port: 443
                cipher: aes-256-gcm
                password: "pass"
            proxy-groups:
              - name: "PROXY"
                type: select
                proxies:
                  - DIRECT
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        val proxyGroup = config?.outbounds?.find { it.tag == "PROXY" }

        assertEquals(listOf("DIRECT"), proxyGroup?.outbounds)
        assertEquals("DIRECT", proxyGroup?.default)
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
    fun testParseVLessWebSocketDoesNotEnableEarlyDataByDefault() {
        val yaml = """
            proxies:
              - name: "vless-ws"
                type: vless
                server: example.com
                port: 443
                uuid: uuid-123
                network: ws
                ws-opts:
                  path: /ws
                  headers:
                    Host: example.com
                tls: true
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        val vless = config?.outbounds?.find { it.tag == "vless-ws" }

        assertNotNull(vless?.transport)
        assertEquals("ws", vless?.transport?.type)
        assertEquals("/ws", vless?.transport?.path)
        assertNull(vless?.transport?.maxEarlyData)
        assertNull(vless?.transport?.earlyDataHeaderName)
    }

    @Test
    fun testParseVLessHttpUpgradeUsesTransportHost() {
        val yaml = """
            proxies:
              - name: "vless-httpupgrade"
                type: vless
                server: edge.example.com
                port: 443
                uuid: uuid-123
                tls: true
                network: ws
                ws-opts:
                  path: /up
                  v2ray-http-upgrade: true
                  headers:
                    Host: cdn.example.com
                    User-Agent: custom-agent
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        val vless = config?.outbounds?.find { it.tag == "vless-httpupgrade" }
        val transportJson = gson.toJson(vless?.transport)

        assertNotNull(vless)
        assertEquals("httpupgrade", vless?.transport?.type)
        assertEquals("/up", vless?.transport?.path)
        assertEquals(listOf("cdn.example.com"), vless?.transport?.host)
        assertFalse(vless?.transport?.headers?.containsKey("Host") == true)
        assertEquals("custom-agent", vless?.transport?.headers?.get("User-Agent"))
        assertTrue(Regex("\"host\"\\s*:\\s*\"cdn\\.example\\.com\"").containsMatchIn(transportJson))
    }

    @Test
    fun testParseVLessXhttpPreservesExtraEncryption() {
        val yaml = """
            proxies:
              - name: "xhttp-vless"
                type: vless
                server: 35.194.192.123
                port: 13324
                uuid: 2edd765b-a895-46ab-a01c-c4719947546b
                cipher: auto
                tls: true
                flow: xtls-rprx-vision
                network: xhttp
                servername: apple.com
                client-fingerprint: chrome
                reality-opts:
                  public-key: HBnrh72W2LW-zJygpN_H0Kw5fO7kIWhw5Bd-8ieVGj0
                  short-id: "94c5638d"
                xhttp-opts:
                  path: "/2edd765b-a895-46ab-a01c-c4719947546b-xh"
                  mode: auto
                  extra:
                    encryption: "mlkem768x25519plus.native.0rtt.test"
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        val vless = config?.outbounds?.find { it.tag == "xhttp-vless" }

        assertNotNull(vless)
        assertEquals("xhttp", vless?.transport?.type)
        assertEquals("/2edd765b-a895-46ab-a01c-c4719947546b-xh", vless?.transport?.path)
        assertEquals("auto", vless?.transport?.mode)
        assertEquals("mlkem768x25519plus.native.0rtt.test", vless?.encryption)
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
    fun testParseHttpWithTlsDefaultsToCertificateVerification() {
        val yaml = """
            proxies:
              - name: "http-default-secure"
                port: 443
                server: proxy.example.com
                tls: true
                type: http
                username: user123
                password: pass456
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        val http = config?.outbounds?.find { it.tag == "http-default-secure" }

        assertNotNull(http?.tls)
        assertEquals(true, http?.tls?.enabled)
        assertEquals("proxy.example.com", http?.tls?.serverName)
        assertEquals(false, http?.tls?.insecure)
    }

    @Test
    fun testParseHysteria2YamlWithExtendedFields() {
        val yaml = """
            proxies:
              - name: "hy2-node"
                type: hysteria2
                server: hy2.example.com
                port: 443
                password: secret
                sni: edge.example.com
                skip-cert-verify: true
                alpn:
                  - h3
                  - hysteria
                client-fingerprint: chrome
                obfs: salamander
                obfs-password: obfs-pass
                network: udp
                up: 100
                down: 200
                ports: 20000,20001
                hop-interval: 30s
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        val hy2 = config?.outbounds?.find { it.tag == "hy2-node" }

        assertNotNull(hy2)
        assertEquals("hysteria2", hy2?.type)
        assertEquals("hy2.example.com", hy2?.server)
        assertEquals(443, hy2?.serverPort)
        assertEquals("secret", hy2?.password)
        assertEquals("udp", hy2?.network)
        assertEquals(100, hy2?.upMbps)
        assertEquals(200, hy2?.downMbps)
        assertEquals(listOf("20000,20001"), hy2?.serverPorts)
        assertEquals("30s", hy2?.hopInterval)
        assertEquals(true, hy2?.tls?.enabled)
        assertEquals("edge.example.com", hy2?.tls?.serverName)
        assertEquals(true, hy2?.tls?.insecure)
        assertEquals(listOf("h3", "hysteria"), hy2?.tls?.alpn)
        assertEquals("chrome", hy2?.tls?.utls?.fingerprint)
        assertEquals("salamander", hy2?.obfs?.type)
        assertEquals("obfs-pass", hy2?.obfs?.password)
    }

    @Test
    fun testParseTuicKeepsDefaultCongestionUnset() {
        val yaml = """
            proxies:
              - name: "tuic-default"
                type: tuic
                server: tuic.example.com
                port: 443
                uuid: uuid-123
                password: pass
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        val tuic = config?.outbounds?.find { it.tag == "tuic-default" }

        assertNotNull(tuic)
        assertEquals("tuic", tuic?.type)
        assertNull(tuic?.congestionControl)
    }

    @Test
    fun testParseNaiveProxy() {
        val yaml = """
            proxies:
              - name: "🇹🇼 NA | 台湾 Native"
                type: naive
                server: native.5945946.xyz
                port: 443
                username: kziii
                password: d63bddb3-4fb6-47d1-9360-c4ff2e8fdc9d
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        assertNotNull(config)

        val naive = config?.outbounds?.find { it.tag == "🇹🇼 NA | 台湾 Native" }
        assertNotNull(naive)
        assertEquals("naive", naive?.type)
        assertEquals("native.5945946.xyz", naive?.server)
        assertEquals(443, naive?.serverPort)
        assertEquals("kziii", naive?.username)
        assertEquals("d63bddb3-4fb6-47d1-9360-c4ff2e8fdc9d", naive?.password)
        assertEquals("h2", naive?.network)
        assertEquals("/", naive?.path)
        assertEquals(true, naive?.tls?.enabled)
        assertEquals("native.5945946.xyz", naive?.tls?.serverName)
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
        assertEquals("14.3.28.11", ss?.server)
        assertEquals(2245, ss?.serverPort)
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

        assertEquals("144.34.238.115", ss?.server)
        assertEquals(20004, ss?.serverPort)
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

    @Test
    fun testParseAnyTlsWithCertificateFields() {
        val certificatePem = "-----BEGIN CERTIFICATE-----\nMIIBTESTCERTDATA\n-----END CERTIFICATE-----"
        val caPem = "-----BEGIN CERTIFICATE-----\nMIIBTESTCADATA\n-----END CERTIFICATE-----"
        val privateKeyPem = "-----BEGIN PRIVATE KEY-----\nMIIBTESTKEYDATA\n-----END PRIVATE KEY-----"
        val yaml = """
            proxies:
              - name: "anytls-cert"
                type: anytls
                server: anytls.example.com
                port: 443
                password: test-pass
                sni: edge.example.com
                cert: |
                  -----BEGIN CERTIFICATE-----
                  MIIBTESTCERTDATA
                  -----END CERTIFICATE-----
                ca-cert: |
                  -----BEGIN CERTIFICATE-----
                  MIIBTESTCADATA
                  -----END CERTIFICATE-----
                client-key: |
                  -----BEGIN PRIVATE KEY-----
                  MIIBTESTKEYDATA
                  -----END PRIVATE KEY-----
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        assertNotNull(config)

        val anytls = config?.outbounds?.find { it.tag == "anytls-cert" }
        assertNotNull(anytls)
        assertEquals("anytls", anytls?.type)
        assertEquals(certificatePem, anytls?.tls?.certificate?.trim())
        assertEquals(caPem, anytls?.tls?.ca?.trim())
        assertEquals(privateKeyPem, anytls?.tls?.key?.trim())
        assertTrue(anytls?.tls?.certificate?.endsWith("\n") == true)
        assertEquals("edge.example.com", anytls?.tls?.serverName)
    }

    @Test
    fun testParseHttpTlsCertificateAliasPaths() {
        val yaml = """
            proxies:
              - name: "http-cert-paths"
                type: http
                server: http.example.com
                port: 443
                tls: true
                certificate-path: /etc/ssl/client.pem
                key-path: /etc/ssl/client.key
                ca_path: /etc/ssl/ca.pem
        """.trimIndent()

        val config = ClashConfigParser.parse(yaml)
        assertNotNull(config)

        val http = config?.outbounds?.find { it.tag == "http-cert-paths" }
        assertNotNull(http)
        assertEquals("http", http?.type)
        assertEquals("/etc/ssl/client.pem", http?.tls?.certificatePath)
        assertEquals("/etc/ssl/client.key", http?.tls?.keyPath)
        assertEquals("/etc/ssl/ca.pem", http?.tls?.caPath)
    }
}
