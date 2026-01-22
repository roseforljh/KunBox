package com.kunk.singbox.utils.parser

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * NodeLinkParser 单元测试
 * 覆盖所有支持的协议链接解析
 */
class NodeLinkParserTest {

    private lateinit var parser: NodeLinkParser
    private val gson = Gson()

    @Before
    fun setUp() {
        parser = NodeLinkParser(gson)
    }

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

    // ==================== VMess ====================

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

    // ==================== VLESS ====================

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
    fun testParseTrojanWithDefaultPort() {
        val link = "trojan://password@trojan.example.com#DefaultPort"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(443, outbound?.serverPort)
    }

    // ==================== Hysteria2 ====================

    @Test
    fun testParseHysteria2Basic() {
        val link = "hysteria2://password@hy2.example.com:443?sni=hy2.example.com#Hy2Node"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("hysteria2", outbound?.type)
        assertEquals("Hy2Node", outbound?.tag)
        assertEquals("hy2.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("password", outbound?.password)
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
    }

    @Test
    fun testParseTuicWithZeroRtt() {
        val link = "tuic://uuid:password@tuic.example.com:443?reduce_rtt=1#ZeroRttNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(true, outbound?.zeroRttHandshake)
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
}
