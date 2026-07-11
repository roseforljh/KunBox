package com.kunk.singbox.utils.parser

import com.kunk.singbox.repository.config.OutboundFixer
import org.junit.Assert.*
import org.junit.Test

/**
 * NodeLinkParser 单元测试
 * 覆盖所有支持的协议链接解析
 */
@Suppress("LargeClass")
class NodeLinkParserTest : NodeLinkParserProxyAndEdgeTest() {
    // ==================== Shadowsocks ====================

    @Test
    fun testParseShadowsocksSIP002() {
        // SIP002 格式: ss://BASE64(method:password)@server:port#name
        val link = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@1.2.3.4:8388#MySSNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("shadowsocks", outbound?.type)
        assertEquals("MySSNode", outbound?.tag)
        assertEquals("1.2.3.4", outbound?.server)
        assertEquals(8388, outbound?.serverPort)
        assertEquals("aes-256-gcm", outbound?.method)
        assertEquals("password", outbound?.password)
    }

    @Test
    fun testParseShadowsocksLegacy() {
        // Legacy 格式: ss://BASE64(method:password@server:port)#name
        val link = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmRAMS4yLjMuNDo4Mzg4#LegacyNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("shadowsocks", outbound?.type)
        assertEquals("LegacyNode", outbound?.tag)
        assertEquals("1.2.3.4", outbound?.server)
        assertEquals(8388, outbound?.serverPort)
    }

    @Test
    fun testParseShadowsocksWithPlugin() {
        val link = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@1.2.3.4:8388?plugin=v2ray-plugin%3Bmode%3Dwebsocket#PluginNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("v2ray-plugin", outbound?.plugin)
        assertEquals("mode=websocket", outbound?.pluginOpts)
    }

    @Test
    fun testParseShadowsocksIPv6() {
        val link = "ss://YWVzLTI1Ni1nY206cGFzcw==@[2001:db8::1]:8388#IPv6Node"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("2001:db8::1", outbound?.server)
        assertEquals(8388, outbound?.serverPort)
    }

    @Test
    fun testParseShadowsocksUrlEncodedName() {
        val link = "ss://YWVzLTI1Ni1nY206cGFzcw==@1.2.3.4:8388#%E6%97%A5%E6%9C%AC%E8%8A%82%E7%82%B9"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("日本节点", outbound?.tag)
    }

    @Test
    fun testParseShadowsocksUrlEncodedPassword() {
        // 非 Base64 格式，密码中包含特殊字符 : 被 URL 编码为 %3A
        val link = "ss://aes-256-gcm:pass%3Aword@1.2.3.4:8388#UrlEncodedPwd"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("shadowsocks", outbound?.type)
        assertEquals("UrlEncodedPwd", outbound?.tag)
        assertEquals("aes-256-gcm", outbound?.method)
        assertEquals("pass:word", outbound?.password)
    }

    // ==================== VMess ====================

    @Test
    fun testParseVMessTcpHttpTypeBuildsHttpTransport() {
        val link = "vmess://" +
            "eyJhZGQiOiI5NC4xNzcuOS42NiIsImFpZCI6IjAiLCJhbHBuIjoiIiwiZnAiOiIiLCJob3N0Ijoi" +
            "ZGluZ3RhbGsuY29tIiwiaWQiOiIxOTMzYTQyNS0yODQyLTRkMmItODU4OS05Y2EyMjQyZDc0" +
            "MTUiLCJuZXQiOiJ0Y3AiLCJwYXRoIjoiLyIsInBvcnQiOiIyOTgyNSIsInBzIjoiQVQg" +
            "8J+HpvCfh7kgIC1cdTAwM2UgIOKtkCDigIvlpaXlnLDliKkt5Y6f55SfLeWkp+W4puWu" +
            "vS0yOTgyNSIsInNjeSI6ImF1dG8iLCJzbmkiOiIiLCJ0bHMiOiIiLCJ0eXBlIjoiaHR0" +
            "cCIsInYiOiIyIn0="

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vmess", outbound?.type)
        assertEquals("http", outbound?.transport?.type)
    }

    @Test
    fun testParseVMessHttpTransportKeepsHttp() {
        val vmessJson = """
            {
              "v":"2",
              "ps":"http vmess",
              "add":"18.225.57.7",
              "port":"32721",
              "id":"c31a559b-8285-4b11-db99-d1edfc2b2b70",
              "aid":"0",
              "net":"http",
              "host":"",
              "path":"",
              "tls":""
            }
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val link = "vmess://$encoded"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vmess", outbound?.type)
        assertNotNull(outbound?.transport)
        assertEquals("http", outbound?.transport?.type)
    }

    @Test
    fun testParseVMessHttpTransportPreservesHostAndPath() {
        val vmessJson = """
            {
              "v":"2",
              "ps":"http with host",
              "add":"vmess.example.com",
              "port":"80",
              "id":"uuid-2000",
              "aid":"0",
              "net":"http",
              "host":"cdn.example.com",
              "path":"/health",
              "tls":""
            }
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val link = "vmess://$encoded"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("http", outbound?.transport?.type)
        assertEquals(listOf("cdn.example.com"), outbound?.transport?.host)
        assertEquals("/health", outbound?.transport?.path)
    }

    @Test
    fun testParseVMessUserHttpLinkKeepsHttpTransport() {
        val link = "vmess://" +
            "eyJhZGQiOiIxOC4yMjUuNTcuNyIsImFpZCI6IjAiLCJob3N0IjoiIiwiaWQiOiJjMzFhNTU5Yi04" +
            "Mjg1LTRiMTEtZGI5OS1kMWVkZmMyYjJiNzAiLCJuZXQiOiJodHRwIiwicGF0aCI6IiIsInBv" +
            "cnQiOiIzMjcyMSIsInBzIjoiVVMg8J+HuvCfh7ggIC1cdTAwM2UgIOe+juWbvS3ljp/nlJ8t" +
            "5Lqa6ams6YCKLeWkp+W4puWuvS0zMjcyMSIsInNjeSI6ImF1dG8iLCJzbmkiOiIiLCJ0" +
            "bHMiOiIiLCJ0eXBlIjoibm9uZSIsInYiOiIyIn0="

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vmess", outbound?.type)
        assertEquals("http", outbound?.transport?.type)
        assertNull(outbound?.transport?.host)
    }

    @Test
    fun testParseVMessHttpUpgradeUsesTransportHost() {
        val vmessJson = """
            {
              "v":"2",
              "ps":"httpupgrade vmess",
              "add":"edge.example.com",
              "port":"443",
              "id":"c31a559b-8285-4b11-db99-d1edfc2b2b70",
              "aid":"0",
              "net":"httpupgrade",
              "host":"cdn.example.com",
              "path":"/up",
              "tls":"tls"
            }
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val link = "vmess://$encoded"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vmess", outbound?.type)
        assertEquals("httpupgrade", outbound?.transport?.type)
        assertEquals("/up", outbound?.transport?.path)
        assertEquals(listOf("cdn.example.com"), outbound?.transport?.host)
        assertNull(outbound?.transport?.headers?.get("Host"))
        assertTrue(gson.toJson(outbound?.transport).contains("\"host\":\"cdn.example.com\""))
    }

    @Test
    fun testParseVMessBasic() {
        val vmessJson = """{"v":"2","ps":"VMess Node","add":"vmess.example.com","port":"443","id":"uuid-1234","aid":"0","net":"ws","type":"none","host":"","path":"/path","tls":"tls"}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val link = "vmess://$encoded"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vmess", outbound?.type)
        assertEquals("VMess Node", outbound?.tag)
        assertEquals("vmess.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("uuid-1234", outbound?.uuid)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
        assertNotNull(outbound?.transport)
        assertEquals("ws", outbound?.transport?.type)
        assertEquals("/path", outbound?.transport?.path)
    }

    @Test
    fun testParseVMessWithGrpc() {
        val vmessJson = """{"v":"2","ps":"gRPC Node","add":"grpc.example.com","port":"443","id":"uuid-5678","aid":"0","net":"grpc","path":"grpc-service","tls":"tls"}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val link = "vmess://$encoded"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("grpc", outbound?.transport?.type)
        assertEquals("grpc-service", outbound?.transport?.serviceName)
    }

    @Test
    fun testParseV2RayQuicTransports() {
        val vmessJson = """
            {
              "v":"2",
              "ps":"VMess QUIC",
              "add":"vmess.example.com",
              "port":"443",
              "id":"uuid-vmess",
              "aid":"0",
              "net":"quic",
              "tls":"tls"
            }
        """.trimIndent()
        val vmessLink = "vmess://${java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())}"
        val vlessLink =
            "vless://uuid-vless@vless.example.com:443?security=tls&type=quic#VLESS%20QUIC"
        val trojanLink =
            "trojan://secret@trojan.example.com:443?type=quic&sni=trojan.example.com#Trojan%20QUIC"

        assertEquals("quic", parser.parse(vmessLink)?.transport?.type)
        assertEquals("quic", parser.parse(vlessLink)?.transport?.type)
        assertEquals("quic", parser.parse(trojanLink)?.transport?.type)
    }

    @Test
    fun testParseV2RayLinksRejectUnknownTransport() {
        val vmessJson = """
            {
              "v":"2",
              "ps":"VMess KCP",
              "add":"vmess.example.com",
              "port":"443",
              "id":"uuid-vmess",
              "aid":"0",
              "net":"kcp",
              "tls":"tls"
            }
        """.trimIndent()
        val vmessLink = "vmess://${java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())}"

        assertNull(parser.parse(vmessLink))
        assertNull(parser.parse("vless://uuid@vless.example.com:443?security=tls&type=kcp#VLESS"))
        assertNull(parser.parse("trojan://secret@trojan.example.com:443?type=kcp#Trojan"))
    }

    @Test
    fun testParseVMessSupportsXhttpTransport() {
        val vmessJson = """
            {
              "v":"2",
              "ps":"xhttp vmess",
              "add":"vmess.example.com",
              "port":"443",
              "id":"uuid-1000",
              "aid":"0",
              "net":"xhttp",
              "host":"h1.example.com,h2.example.com",
              "path":"/xhttp",
              "mode":"auto",
              "xPaddingBytes":"100-200",
              "scMaxEachPostBytes":"1048576",
              "scMinPostsIntervalMs":"30",
              "scMaxBufferedPosts":"64",
              "noGRPCHeader":"1",
              "noSSEHeader":"true",
              "tls":"tls",
              "sni":"vmess.example.com"
            }
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val link = "vmess://$encoded"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("xhttp", outbound?.transport?.type)
        assertEquals("/xhttp", outbound?.transport?.path)
        assertEquals(listOf("h1.example.com", "h2.example.com"), outbound?.transport?.host)
        assertEquals("auto", outbound?.transport?.mode)
        assertEquals("100-200", outbound?.transport?.xPaddingBytes)
        assertEquals(1_048_576L, outbound?.transport?.scMaxEachPostBytes)
        assertEquals(30L, outbound?.transport?.scMinPostsIntervalMs)
        assertEquals(64L, outbound?.transport?.scMaxBufferedPosts)
        assertEquals(true, outbound?.transport?.noGRPCHeader)
        assertEquals(true, outbound?.transport?.noSSEHeader)
    }

    // ==================== VLESS ====================

    @Test
    fun testParseVLessWebSocketEarlyDataFromPathQuery() {
        val link =
            "vless://uuid@125.140.145.188:21272?type=ws&encryption=none&security=tls" +
                "&sni=lp3.0528.linkpc.net&fp=random&allowInsecure=1&host=lp3.0528.linkpc.net" +
                "&path=%2F%3Fed%3D2560#KR_1"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
        assertEquals("ws", outbound?.transport?.type)
        assertEquals("/", outbound?.transport?.path)
        assertEquals(2560L, outbound?.transport?.maxEarlyData)
        assertEquals("Sec-WebSocket-Protocol", outbound?.transport?.earlyDataHeaderName)
        assertEquals("random", outbound?.tls?.utls?.fingerprint)
        assertEquals(true, outbound?.tls?.insecure)
        assertEquals("lp3.0528.linkpc.net", outbound?.tls?.serverName)
    }

    @Test
    fun testParseWebSocketEarlyDataHonorsUInt32Bounds() {
        val maxValueLink =
            "vless://uuid@example.com:443?security=tls&type=ws&path=%2F%3Fed%3D4294967295#Max"
        val overflowLink =
            "vless://uuid@example.com:443?security=tls&type=ws&path=%2F%3Fed%3D4294967296#Overflow"
        val negativeLink =
            "vless://uuid@example.com:443?security=tls&type=ws&path=%2F%3Fed%3D-1#Negative"

        assertEquals(4_294_967_295L, parser.parse(maxValueLink)?.transport?.maxEarlyData)
        assertNull(parser.parse(overflowLink))
        assertNull(parser.parse(negativeLink))
    }

    @Test
    fun testParseVLessHttpAndH2Transports() {
        listOf("http", "h2").forEach { type ->
            val outbound = parser.parse(
                "vless://uuid@example.com:443?security=tls&type=$type" +
                    "&host=h1.example.com,h2.example.com&path=%2Fhealth#$type"
            )

            assertEquals("http", outbound?.transport?.type)
            assertEquals(listOf("h1.example.com", "h2.example.com"), outbound?.transport?.host)
            assertEquals("/health", outbound?.transport?.path)
        }
    }

    @Test
    fun testParseVLessBasic() {
        val link = "vless://uuid-1234@vless.example.com:443?security=tls&sni=vless.example.com&type=ws&path=%2Fpath#VLESSNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
        assertEquals("VLESSNode", outbound?.tag)
        assertEquals("vless.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("uuid-1234", outbound?.uuid)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
        assertEquals("vless.example.com", outbound?.tls?.serverName)
        assertNotNull(outbound?.transport)
        assertEquals("ws", outbound?.transport?.type)
        assertEquals("/path", outbound?.transport?.path)
    }

    @Test
    fun testParseVLessRejectsMissingRequiredFields() {
        assertNull(parser.parse("vless://@vless.example.com:443?security=tls#NoUuid"))
        assertNull(parser.parse("vless://uuid@:443?security=tls#NoServer"))
    }

    @Test
    fun testParseVLessKeepsUnencodedChineseFragmentName() {
        val link = "vless://uuid@example.com:443?security=tls&type=ws#香港节点"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("香港节点", outbound?.tag)
    }

    @Test
    fun testParseVLessInfersTlsWhenSniPresentButSecurityMissing() {
        // 很多机场生成的链接省略 security=tls，但有 sni 参数，应推断为 TLS
        val link = "vless://68d55b3f-c4f1-481a-8bfb-e483004f2c15@198.41.223.11:443" +
            "?flow=&type=ws&path=%2F&host=cm.kuz7.com&sni=cm.kuz7.com#TestNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
        assertEquals("198.41.223.11", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        // 核心断言：必须有 TLS
        assertNotNull("TLS should be inferred when sni is present", outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
        assertEquals("cm.kuz7.com", outbound?.tls?.serverName)
        // WebSocket transport
        assertNotNull(outbound?.transport)
        assertEquals("ws", outbound?.transport?.type)
    }

    @Test
    fun testParseVLessInfersTlsWhenHostPresentOnPort443ButSecurityMissing() {
        // 443 端口上的 WebSocket 且带 Host，通常是 WSS，应推断为 TLS
        val link = "vless://uuid@example.com:443?type=ws&path=%2F&host=example.com#Node443"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertNotNull("TLS should be inferred for WSS-like port 443 link", outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
    }

    @Test
    fun testParseVLessNoTlsWhenPort443WithoutHostOrSni() {
        // 只有 443 端口不足以推断 TLS，避免误伤明文自定义端口场景
        val link = "vless://uuid@example.com:443?type=ws&path=%2F#Node443Plain"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertNull("TLS should NOT be inferred for port 443 without host or sni", outbound?.tls)
    }

    @Test
    fun testParseVLessNoTlsWhenPort80AndNoSni() {
        // 端口 80 且无 sni，不应启用 TLS
        val link = "vless://uuid@example.com:80?type=ws&path=%2F&host=example.com#Node80"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertNull("TLS should NOT be inferred for port 80 without sni", outbound?.tls)
    }

    @Test
    fun testParseVLessRejectsUnsupportedPrivateEncryption() {
        val link = "vless://b6fd6867-c239-4d95-8a98-cb036d34fc21@34.150.59.170:39797" +
            "?encryption=mlkem768x25519plus.native.0rtt.sample&flow=xtls-rprx-vision" +
            "&type=ws&path=b6fd6867-c239-4d95-8a98-cb036d34fc21-vw#vl-ws-enc"

        val outbound = parser.parse(link)
        assertNull(outbound)
    }

    @Test
    fun testParseVLessWithReality() {
        val link = "vless://uuid@reality.example.com:443?security=reality&sni=www.microsoft.com&pbk=public-key-123&sid=short-id&fp=chrome&type=tcp#RealityNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
        assertEquals("RealityNode", outbound?.tag)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
        assertNotNull(outbound?.tls?.reality)
        assertEquals(true, outbound?.tls?.reality?.enabled)
        assertEquals("public-key-123", outbound?.tls?.reality?.publicKey)
        assertEquals("short-id", outbound?.tls?.reality?.shortId)
        assertEquals("chrome", outbound?.tls?.utls?.fingerprint)
    }

    @Test
    fun testParseVLessWithFlow() {
        val link = "vless://uuid@xtls.example.com:443?security=tls&flow=xtls-rprx-vision&type=tcp#XTLSNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("xtls-rprx-vision", outbound?.flow)
    }

    @Test
    fun testParseVLessWithGrpc() {
        val link = "vless://uuid@grpc.example.com:443?security=tls&type=grpc&serviceName=my-service#gRPCNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertNotNull(outbound?.transport)
        assertEquals("grpc", outbound?.transport?.type)
        assertEquals("my-service", outbound?.transport?.serviceName)
    }

    @Test
    fun testParseVLessSupportsXhttpTransport() {
        val link =
            "vless://uuid@xhttp.example.com:443?security=tls&type=xhttp&host=h1.example.com,h2.example.com" +
                "&path=%2Fxhttp&mode=auto&xPaddingBytes=100-200&scMaxEachPostBytes=1048576" +
                "&scMinPostsIntervalMs=30&scMaxBufferedPosts=64&noGRPCHeader=1&noSSEHeader=true#XHTTPNode"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("xhttp", outbound?.transport?.type)
        assertEquals(listOf("h1.example.com", "h2.example.com"), outbound?.transport?.host)
        assertEquals("auto", outbound?.transport?.mode)
        assertEquals(1_048_576L, outbound?.transport?.scMaxEachPostBytes)
    }

    @Test
    fun testParseVLessRejectsPrivateEncryptionWithXhttp() {
        val link =
            "vless://uuid@xhttp.example.com:443?security=reality&sni=apple.com&pbk=public-key-123" +
                "&sid=short-id-123&fp=chrome&flow=xtls-rprx-vision&type=xhttp" +
                "&path=node-xh&mode=auto&encryption=mlkem768x25519plus.native.0rtt.sample#EncryptedXHTTPNode"

        val outbound = parser.parse(link)

        assertNull(outbound)
    }

    @Test
    fun testParseVLessHttpUpgradeInfersTls() {
        val link = "vless://uuid@edge.example.com:443?type=httpupgrade&host=cdn.example.com&path=%2Fup#HttpUpgrade"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
        assertEquals("httpupgrade", outbound?.transport?.type)
        assertEquals("/up", outbound?.transport?.path)
        assertEquals(listOf("cdn.example.com"), outbound?.transport?.host)
        assertNull(outbound?.transport?.headers?.get("Host"))
        assertTrue(gson.toJson(outbound?.transport).contains("\"host\":\"cdn.example.com\""))
        assertEquals(true, outbound?.tls?.enabled)
        assertEquals("cdn.example.com", outbound?.tls?.serverName)
    }

    @Test
    fun testRealVLessXhttpRealityNodeIsRejected() {
        val link = "vless://2edd765b-a895-46ab-a01c-c4719947546b@35.194.192.123:13324" +
            "?type=xhttp&encryption=mlkem768x25519plus.native.0rtt.sample&flow=xtls-rprx-vision" +
            "&security=reality&pbk=public-key-123&sid=94c5638d&sni=apple.com&fp=chrome" +
            "&packetEncoding=xudp&path=%2F2edd765b-a895-46ab-a01c-c4719947546b-xh&mode=auto" +
            "#TW-GCP-xhttp"
        val outbound = parser.parse(link)
        assertNull(outbound)
    }

    @Test
    fun testParseVLessWithEch() {
        val link = "vless://68d55b3f-c4f1-481a-8bfb-e483004f2c15@198.41.223.11:443" +
            "?security=tls&type=ws&ech=cloudflare-ech.com%2Bhttps%3A%2F%2Fdns.alidns.com%2Fdns-query" +
            "&host=cm.kuz7.com&fp=chrome&sni=cm.kuz7.com&path=%2F&encryption=none" +
            "#SG%7C%E5%AE%98%E6%96%B9%E4%BC%98%E9%80%89%7C90ms"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
        assertEquals("198.41.223.11", outbound?.server)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
        assertEquals("cm.kuz7.com", outbound?.tls?.serverName)
        assertNotNull("ECH should be enabled when ech param is present", outbound?.tls?.ech)
        assertEquals(true, outbound?.tls?.ech?.enabled)
        assertEquals("cloudflare-ech.com", outbound?.tls?.ech?.queryServerName)
        assertEquals("https://dns.alidns.com/dns-query", outbound?.tls?.ech?.dnsServer)
    }

    @Test
    fun testParseVLessWithoutEch() {
        val link = "vless://uuid@example.com:443?security=tls&sni=example.com&type=ws&path=%2F#NoECH"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertNull("ECH should be null when ech param is absent", outbound?.tls?.ech)
    }

    // ==================== Trojan ====================

    @Test
    fun testParseTrojanBasic() {
        val link = "trojan://password123@trojan.example.com:443?sni=trojan.example.com#TrojanNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("trojan", outbound?.type)
        assertEquals("TrojanNode", outbound?.tag)
        assertEquals("trojan.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("password123", outbound?.password)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
    }

    @Test
    fun testParseTrojanRejectsMissingRequiredFields() {
        assertNull(parser.parse("trojan://@trojan.example.com:443#NoPassword"))
        assertNull(parser.parse("trojan://password@:443#NoServer"))
    }

    @Test
    fun testParseTrojanWithDefaultPort() {
        val link = "trojan://password@trojan.example.com#DefaultPort"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(443, outbound?.serverPort)
    }

    @Test
    fun testParseTrojanSupportsXhttpTransport() {
        val link =
            "trojan://password@trojan.example.com:443?security=tls&type=xhttp&host=h1.example.com,h2.example.com" +
                "&path=%2Fxhttp&mode=auto&xPaddingBytes=100-200&scMaxEachPostBytes=1048576" +
                "&scMinPostsIntervalMs=30&scMaxBufferedPosts=64&noGRPCHeader=1&noSSEHeader=true" +
                "&sni=sni.example.com&fp=chrome&alpn=h2,http%2F1.1&allowInsecure=1#TrojanXHTTP"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("xhttp", outbound?.transport?.type)
        assertEquals("/xhttp", outbound?.transport?.path)
        assertEquals(listOf("h1.example.com", "h2.example.com"), outbound?.transport?.host)
        assertEquals(true, outbound?.transport?.noGRPCHeader)
        assertEquals(true, outbound?.transport?.noSSEHeader)
    }

    @Test
    fun testParseTrojanHttpUpgrade() {
        val link =
            "trojan://password@trojan.example.com:443?type=httpupgrade&host=cdn.example.com&path=%2Fup" +
                "&sni=sni.example.com#TrojanHttpUpgrade"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("trojan", outbound?.type)
        assertEquals("httpupgrade", outbound?.transport?.type)
        assertEquals("/up", outbound?.transport?.path)
        assertEquals(listOf("cdn.example.com"), outbound?.transport?.host)
        assertNull(outbound?.transport?.headers?.get("Host"))
        assertTrue(gson.toJson(outbound?.transport).contains("\"host\":\"cdn.example.com\""))
        assertEquals(true, outbound?.tls?.enabled)
        assertEquals("sni.example.com", outbound?.tls?.serverName)
    }

    @Test
    fun testParseTrojanWebSocketEarlyData() {
        val outbound = parser.parse(
            "trojan://secret@trojan.example.com:443?type=ws&path=%2Fws%3Fed%3D2048" +
                "&host=cdn.example.com#TrojanWS"
        )

        assertEquals("ws", outbound?.transport?.type)
        assertEquals("/ws", outbound?.transport?.path)
        assertEquals(2048L, outbound?.transport?.maxEarlyData)
        assertEquals("Sec-WebSocket-Protocol", outbound?.transport?.earlyDataHeaderName)
    }

    // ==================== Hysteria2 ====================

    @Test
    fun testParseHysteria2Basic() {
        val link = "hysteria2://password@hy2.example.com:443?sni=hy2.example.com&pinSHA256=abcdef123456#Hy2Node"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("hysteria2", outbound?.type)
        assertEquals("Hy2Node", outbound?.tag)
        assertEquals("hy2.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("password", outbound?.password)
        assertEquals(listOf("abcdef123456"), outbound?.tls?.certificatePublicKeySha256)
    }

    @Test
    fun testParseHysteria2WithBandwidth() {
        val link = "hysteria2://password@hy2.example.com:443?up=100&down=200#BandwidthNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(100, outbound?.upMbps)
        assertEquals(200, outbound?.downMbps)
    }

    @Test
    fun testParseHysteria2WithoutBandwidthKeepsNull() {
        val link = "hysteria2://password@hy2.example.com:443?sni=hy2.example.com#NoBandwidthNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(null, outbound?.upMbps)
        assertEquals(null, outbound?.downMbps)
    }

    @Test
    fun testParseHysteria2WithExportedBandwidthParamNames() {
        val link = "hysteria2://password@hy2.example.com:443?upmbps=80&downmbps=160#ExportedBandwidthNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(80, outbound?.upMbps)
        assertEquals(160, outbound?.downMbps)
    }

    @Test
    fun testParseHy2ShortScheme() {
        val link = "hy2://password@hy2.example.com:443#ShortScheme"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("hysteria2", outbound?.type)
        assertEquals("ShortScheme", outbound?.tag)
    }

    @Test
    fun testParseHysteria2WithObfs() {
        val link = "hysteria2://password@hy2.example.com:443?obfs=salamander&obfs-password=obfs-pass#ObfsNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertNotNull(outbound?.obfs)
        assertEquals("salamander", outbound?.obfs?.type)
        assertEquals("obfs-pass", outbound?.obfs?.password)
    }

    @Test
    fun testParseHysteria2WithTlsFlagsAndMport() {
        val link = "hysteria2://password@hy2.example.com:443" +
            "?sni=edge.example.com&insecure=1&alpn=h3,hysteria&mport=20000,20001#TlsFlagsNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("edge.example.com", outbound?.tls?.serverName)
        assertEquals(true, outbound?.tls?.insecure)
        assertEquals(listOf("h3", "hysteria"), outbound?.tls?.alpn)
        assertEquals(listOf("20000", "20001"), outbound?.serverPorts)
    }

    @Test
    fun testParseHysteria2IpWithoutSniKeepsServerNameNull() {
        val link = "hysteria2://password@34.150.59.170:38313#Hy2IpNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("34.150.59.170", outbound?.server)
        assertNull(outbound?.tls?.serverName)
    }

    // ==================== Naive ====================

    @Test
    fun testParseNaiveBasic() {
        val link = "naive://user:pass@naive.example.com:443" +
            "?network=h2&insecure_concurrency=2" +
            "&extra_headers=User-Agent%3A%20naive%0AX-Test%3A%20demo" +
            "&sni=naive.example.com#NaiveNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("naive", outbound?.type)
        assertEquals("NaiveNode", outbound?.tag)
        assertEquals("naive.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("user", outbound?.username)
        assertEquals("pass", outbound?.password)
        assertEquals(listOf("h2"), outbound?.network)
        assertEquals(2, outbound?.insecureConcurrency)
        assertEquals("naive", outbound?.extraHeaders?.get("User-Agent"))
        assertEquals("demo", outbound?.extraHeaders?.get("X-Test"))
        assertNull(outbound?.path)
        assertNull(outbound?.headers)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
        assertEquals("naive.example.com", outbound?.tls?.serverName)
    }

    @Test
    fun testParseNaivePlusHttpsScheme() {
        val link = "naive+https://u:p@naive.example.com:443?extra_headers=User-Agent%3A%20naive#NaiveHttps"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("naive", outbound?.type)
        assertEquals("NaiveHttps", outbound?.tag)
        assertEquals("u", outbound?.username)
        assertEquals("p", outbound?.password)
        assertEquals(listOf("h2"), outbound?.network)
        assertEquals("naive", outbound?.extraHeaders?.get("User-Agent"))
    }

    @Test
    fun testParseNaivePlusHttpsWithTrailingComma() {
        val link = "naive+https://u:p@naive.example.com:443?insecure_concurrency=4#NaiveHttps,"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("naive", outbound?.type)
        assertEquals("NaiveHttps", outbound?.tag)
        assertEquals("u", outbound?.username)
        assertEquals("p", outbound?.password)
        assertEquals(4, outbound?.insecureConcurrency)
    }

    @Test
    fun testParseNaiveIpWithoutSniKeepsServerNameNull() {
        val link = "naive://u:p@34.150.59.170:443?network=h2#NaiveIp"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("34.150.59.170", outbound?.server)
        assertNull(outbound?.tls?.serverName)
    }

    // ==================== TUIC ====================

    @Test
    fun testParseTuicBasic() {
        val link = "tuic://uuid:password@tuic.example.com:443?congestion_control=bbr&udp_relay_mode=native#TUICNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("tuic", outbound?.type)
        assertEquals("TUICNode", outbound?.tag)
        assertEquals("tuic.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("uuid", outbound?.uuid)
        assertEquals("password", outbound?.password)
        assertEquals("bbr", outbound?.congestionControl)
        assertEquals("native", outbound?.udpRelayMode)
        assertEquals("tuic.example.com", outbound?.tls?.serverName)
        assertNull(outbound?.tls?.disableSni)
    }

    @Test
    fun testParseTuicWithDisableSniKeepsServerName() {
        val link = "tuic://uuid:password@tuic.example.com:443?sni=edge.example.com&disable_sni=1#TUICDisableSni"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("tuic", outbound?.type)
        assertNull(outbound?.disableSni)
        assertEquals(true, outbound?.tls?.disableSni)
        assertEquals(true, outbound?.tls?.enabled)
        assertEquals("edge.example.com", outbound?.tls?.serverName)
    }

    @Test
    fun testParseTuicKeepsDefaultCongestionUnset() {
        val link = "tuic://uuid:password@tuic.example.com:443#TUICDefaultCongestion"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("tuic", outbound?.type)
        assertNull(outbound?.congestionControl)
    }

    @Test
    fun testParseTuicWithZeroRtt() {
        val link = "tuic://uuid:password@tuic.example.com:443?reduce_rtt=1#ZeroRttNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(true, outbound?.zeroRttHandshake)
    }

    @Test
    fun testRealTuicLinkDisablesSniInRuntimeTlsOptions() {
        val link = "tuic://a06ded63-e9b9-41ac-b721-c1372fe3d700:YhUro1WIFk@cdn4.eu.org:27017" +
            "?security=tls&sni=cdn4.eu.org&alpn=h3&congestion_control=bbr#%F0%9F%87%B0%F0%9F%87%B7%20" +
            "%E9%A6%96%E5%B0%944-T"
        val outbound = parser.parse(link)?.copy(disableSni = true)

        val runtime = outbound?.let { OutboundFixer.buildForRuntimeWithDialConfigForTest(it) }
        val json = gson.toJson(runtime)

        assertNotNull(runtime)
        assertNull(runtime?.disableSni)
        assertEquals(true, runtime?.tls?.disableSni)
        assertEquals("cdn4.eu.org", runtime?.tls?.serverName)
        assertTrue(json.contains("\"server_name\":\"cdn4.eu.org\""))
    }

    @Test
    fun testRealHysteria2LinkDisablesSniInRuntimeTlsOptions() {
        val link = "hysteria2://YhUro1WIFk@cdn4.eu.org:52011?security=tls&sni=cdn4.eu.org&alpn=&fastopen=0" +
            "#%F0%9F%87%B0%F0%9F%87%B7%20%E9%A6%96%E5%B0%944-H"
        val outbound = parser.parse(link)?.copy(disableSni = true)

        val runtime = outbound?.let { OutboundFixer.buildForRuntimeWithDialConfigForTest(it) }
        val json = gson.toJson(runtime)

        assertNotNull(runtime)
        assertNull(runtime?.disableSni)
        assertEquals(true, runtime?.tls?.disableSni)
        assertEquals("cdn4.eu.org", runtime?.tls?.serverName)
        assertTrue(json.contains("\"server_name\":\"cdn4.eu.org\""))
    }

    // ==================== AnyTLS ====================

    @Test
    fun testParseAnyTLSBasic() {
        val link = "anytls://password@anytls.example.com:443?sni=anytls.example.com#AnyTLSNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("anytls", outbound?.type)
        assertEquals("AnyTLSNode", outbound?.tag)
        assertEquals("anytls.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("password", outbound?.password)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
    }

    @Test
    fun testParseAnyTLSWithSessionParams() {
        val link = "anytls://password@anytls.example.com:443?idle_session_check_interval=30s&idle_session_timeout=60s&min_idle_session=2#SessionNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("30s", outbound?.idleSessionCheckInterval)
        assertEquals("60s", outbound?.idleSessionTimeout)
        assertEquals(2, outbound?.minIdleSession)
    }

    // ==================== WireGuard ====================

    @Test
    fun testParseWireGuardUsesRequiredLocalAddress() {
        val link = "wireguard://private-key@example.com:51820?" +
            "public_key=peer-key&address=10.7.0.2%2F32,fd00%3A%3A2%2F128" +
            "&allowed_ips=0.0.0.0%2F0,%3A%3A%2F0&persistent_keepalive_interval=25" +
            "&reserved=1,2,3&mtu=1380&workers=2#WireGuardNode"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("wireguard", outbound?.type)
        assertEquals("WireGuardNode", outbound?.tag)
        assertEquals(listOf("private-key"), outbound?.privateKey)
        assertEquals(listOf("10.7.0.2/32", "fd00::2/128"), outbound?.localAddress)
        assertEquals("example.com", outbound?.peers?.firstOrNull()?.server)
        assertEquals(51820, outbound?.peers?.firstOrNull()?.serverPort)
        assertEquals("peer-key", outbound?.peers?.firstOrNull()?.publicKey)
        assertEquals(listOf("0.0.0.0/0", "::/0"), outbound?.peers?.firstOrNull()?.allowedIps)
        assertEquals(25, outbound?.peers?.firstOrNull()?.persistentKeepaliveInterval)
        assertEquals(listOf(1, 2, 3), outbound?.peers?.firstOrNull()?.reserved)
        assertEquals(1380, outbound?.mtu)
        assertEquals(2, outbound?.workers)
    }

    @Test
    fun testParseWireGuardRejectsMissingLocalAddress() {
        val link = "wireguard://private-key@example.com:51820?public_key=peer-key#WireGuardNode"

        val outbound = parser.parse(link)

        assertNull(outbound)
    }

    @Test
    fun supportedLinkPrefixesMatchImplementedProtocols() {
        assertTrue(NodeLinkParser.isSupportedLink("https://user:pass@example.com:443#HTTP"))
        assertFalse(NodeLinkParser.isSupportedLink("https://subscription.example.com/config"))
        assertTrue(NodeLinkParser.isSupportedLink("socks4a://example.com:1080#SOCKS"))
        assertTrue(NodeLinkParser.isSupportedLink("wg://private@example.com:51820?address=10.0.0.2%2F32"))
        assertFalse(NodeLinkParser.isSupportedLink("ssr://unsupported"))
    }
}
