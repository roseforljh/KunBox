package com.kunk.singbox.repository.config

import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RealityConfig
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UtlsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class OutboundFixerTest {

    @Test
    fun testFixNaivePreservesH2NetworkForRuntime() {
        val outbound = Outbound(
            type = "naive",
            tag = "naive-node",
            server = "naive.example.com",
            serverPort = 443,
            username = "u",
            password = "p",
            network = "h2"
        )

        val fixed = OutboundFixer.buildRuntimeNaiveOutbound(
            fixed = outbound,
            tcpKeepAliveEnabled = false,
            tcpKeepAliveInterval = null,
            connectTimeout = null
        )

        assertEquals(null, fixed.network)
        assertEquals(false, fixed.quic)
        assertEquals("h2", outbound.network)
    }

    @Test
    fun testBuildRuntimeHysteria2PreservesCriticalFields() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret",
            upMbps = 100,
            downMbps = 200,
            obfs = ObfsConfig(type = "salamander", password = "obfs-pass"),
            serverPorts = listOf("20000", "20001"),
            hopInterval = "30s",
            tls = TlsConfig(enabled = true, serverName = "edge.example.com")
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            null,
            null
        )

        assertEquals("hysteria2", runtime.type)
        assertEquals("secret", runtime.password)
        assertEquals(100, runtime.upMbps)
        assertEquals(200, runtime.downMbps)
        assertEquals("salamander", runtime.obfs?.type)
        assertEquals("obfs-pass", runtime.obfs?.password)
        assertEquals(listOf("20000", "20001"), runtime.serverPorts)
        assertEquals("30s", runtime.hopInterval)
        assertEquals("edge.example.com", runtime.tls?.serverName)
    }

    @Test
    fun testBuildForRuntimePreservesShadowsocksTransportAndTls() {
        val outbound = Outbound(
            type = "shadowsocks",
            tag = "ss-v2ray-plugin",
            server = "ss.example.com",
            serverPort = 8388,
            method = "2022-blake3-aes-128-gcm",
            password = "secret",
            transport = TransportConfig(
                type = "ws",
                path = "/ws",
                headers = mapOf("Host" to "edge.example.com")
            ),
            tls = TlsConfig(enabled = true, serverName = "edge.example.com")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("shadowsocks", runtime?.type)
        assertEquals("ws", runtime?.transport?.type)
        assertEquals("/ws", runtime?.transport?.path)
        assertEquals("edge.example.com", runtime?.transport?.headers?.get("Host"))
        assertEquals(true, runtime?.tls?.enabled)
        assertEquals("edge.example.com", runtime?.tls?.serverName)
    }

    @Test
    fun testBuildRuntimeHysteria2AddsBootstrapDomainResolverForHostnames() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret"
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            null,
            null
        )

        assertEquals("dns-bootstrap", runtime.domainResolver?.server)
    }

    @Test
    fun testBuildRuntimeHysteria2PreservesExistingDomainResolver() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret",
            domainResolver = DomainResolveConfig(server = "custom-bootstrap", strategy = "prefer_ipv4")
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            null,
            null
        )

        assertEquals("custom-bootstrap", runtime.domainResolver?.server)
        assertEquals("prefer_ipv4", runtime.domainResolver?.strategy)
    }

    @Test
    fun testBuildRuntimeHysteria2DoesNotAddBootstrapDomainResolverForIpLiteral() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "1.2.3.4",
            serverPort = 443,
            password = "secret"
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            null,
            null
        )

        assertNull(runtime.domainResolver)
    }

    @Test
    fun testBuildRuntimeHysteria2DefaultsBandwidthTo50WhenMissing() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret"
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            null,
            null
        )

        assertEquals(50, runtime.upMbps)
        assertEquals(50, runtime.downMbps)
    }

    @Test
    fun testBuildRuntimeHysteria2DoesNotInjectTcpFields() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret"
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            "15s",
            "5s"
        )

        assertNull(runtime.tcpKeepAlive)
        assertNull(runtime.tcpKeepAliveInterval)
        assertNull(runtime.connectTimeout)
    }

    @Test
    fun testBuildForRuntimeKeepsTuicServerNameWhenDisableSniEnabled() {
        val outbound = Outbound(
            type = "tuic",
            tag = "tuic-node",
            server = "tuic.example.com",
            serverPort = 443,
            uuid = "uuid",
            password = "secret",
            disableSni = true,
            tls = TlsConfig(
                enabled = true,
                serverName = "tuic.example.com"
            )
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertNull(runtime?.disableSni)
        assertEquals(true, runtime?.tls?.disableSni)
        assertEquals(true, runtime?.tls?.enabled)
        assertEquals("tuic.example.com", runtime?.tls?.serverName)
    }

    @Test
    fun testBuildForRuntimeKeepsTuicServerNameWhenDisableSniDisabled() {
        val outbound = Outbound(
            type = "tuic",
            tag = "tuic-node",
            server = "tuic.example.com",
            serverPort = 443,
            uuid = "uuid",
            password = "secret",
            disableSni = null,
            tls = TlsConfig(
                enabled = true,
                serverName = "edge.example.com"
            )
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertNull(runtime?.disableSni)
        assertEquals("edge.example.com", runtime?.tls?.serverName)
    }

    @Test
    fun testFixPreservesWhitelistedAutoUrlTestWithoutDefault() {
        val outbound = Outbound(
            type = "urltest",
            tag = "P:HK#AUTO",
            outbounds = listOf("node-a", "node-b"),
            default = "node-b",
            url = "https://www.gstatic.com/generate_204",
            interval = "10m",
            tolerance = 50
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("urltest", fixed.type)
        assertEquals(listOf("node-a", "node-b"), fixed.outbounds)
        assertNull(fixed.default)
        assertEquals("https://www.gstatic.com/generate_204", fixed.url)
        assertEquals("10m", fixed.interval)
        assertEquals(50, fixed.tolerance)
    }

    @Test
    fun testBuildForRuntimeKeepsWhitelistedUrlTestWithoutDefault() {
        val outbound = Outbound(
            type = "urltest",
            tag = "P:HK#AUTO",
            outbounds = listOf("node-a", "node-b"),
            default = "node-b",
            url = "https://www.gstatic.com/generate_204",
            interval = "10m",
            tolerance = 50
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("urltest", runtime?.type)
        assertEquals(listOf("node-a", "node-b"), runtime?.outbounds)
        assertNull(runtime?.default)
        assertEquals("https://www.gstatic.com/generate_204", runtime?.url)
        assertEquals("10m", runtime?.interval)
        assertEquals(50, runtime?.tolerance)
    }

    @Test
    fun testBuildForRuntimePreservesVlessCustomEncryption() {
        val outbound = Outbound(
            type = "vless",
            tag = "encrypted-xhttp",
            server = "xhttp.example.com",
            serverPort = 443,
            uuid = "uuid",
            flow = "xtls-rprx-vision",
            encryption = "mlkem768x25519plus.native.0rtt.sample"
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("vless", runtime?.type)
        assertEquals("mlkem768x25519plus.native.0rtt.sample", runtime?.encryption)
    }

    @Test
    fun testBuildForRuntimePreservesImplicitXudpForVlessXhttp() {
        val outbound = Outbound(
            type = "vless",
            tag = "xhttp-node",
            server = "xhttp.example.com",
            serverPort = 443,
            uuid = "uuid",
            transport = com.kunk.singbox.model.TransportConfig(type = "xhttp", path = "/x")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertNull(runtime?.packetEncoding)
    }

    @Test
    fun testBuildForRuntimePreservesExplicitXudpForVlessXhttp() {
        val outbound = Outbound(
            type = "vless",
            tag = "xhttp-node",
            server = "xhttp.example.com",
            serverPort = 443,
            uuid = "uuid",
            packetEncoding = "xudp",
            transport = TransportConfig(type = "xhttp", path = "/x")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("xudp", runtime?.packetEncoding)
    }

    @Test
    fun testBuildForRuntimePreservesImplicitXudpForEncryptedVlessWebSocket() {
        val outbound = Outbound(
            type = "vless",
            tag = "encrypted-ws-node",
            server = "34.150.59.170",
            serverPort = 39797,
            uuid = "b6fd6867-c239-4d95-8a98-cb036d34fc21",
            encryption = "mlkem768x25519plus.native.0rtt.sample",
            transport = TransportConfig(type = "ws", path = "/b6fd6867-c239-4d95-8a98-cb036d34fc21-vw")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertNull(runtime?.packetEncoding)
        assertEquals("mlkem768x25519plus.native.0rtt.sample", runtime?.encryption)
    }

    @Test
    fun testBuildForRuntimePreservesExplicitXudpForEncryptedVlessWebSocket() {
        val outbound = Outbound(
            type = "vless",
            tag = "encrypted-ws-node",
            server = "34.150.59.170",
            serverPort = 39797,
            uuid = "b6fd6867-c239-4d95-8a98-cb036d34fc21",
            packetEncoding = "xudp",
            encryption = "mlkem768x25519plus.native.0rtt.sample",
            transport = TransportConfig(type = "ws", path = "/b6fd6867-c239-4d95-8a98-cb036d34fc21-vw")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("xudp", runtime?.packetEncoding)
        assertEquals("ws", runtime?.transport?.type)
    }

    @Test
    fun testBuildForRuntimePreservesExplicitXudpWithRealityVisionXhttp() {
        val outbound = Outbound(
            type = "vless",
            tag = "reality-xhttp-node",
            server = "35.194.192.123",
            serverPort = 13324,
            uuid = "2edd765b-a895-46ab-a01c-c4719947546b",
            flow = "xtls-rprx-vision",
            packetEncoding = "xudp",
            encryption = "mlkem768x25519plus.native.0rtt.sample",
            tls = TlsConfig(
                enabled = true,
                serverName = "apple.com",
                reality = RealityConfig(
                    enabled = true,
                    publicKey = "HBnrh72W2LW-zJygpN_H0Kw5fO7kIWhw5Bd-8ieVGj0",
                    shortId = "94c5638d"
                ),
                utls = UtlsConfig(enabled = true, fingerprint = "chrome")
            ),
            transport = TransportConfig(
                type = "xhttp",
                path = "/2edd765b-a895-46ab-a01c-c4719947546b-xh",
                mode = "auto"
            )
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("xudp", runtime?.packetEncoding)
        assertEquals("xtls-rprx-vision", runtime?.flow)
        assertEquals("mlkem768x25519plus.native.0rtt.sample", runtime?.encryption)
        assertEquals("xhttp", runtime?.transport?.type)
    }

    @Test
    fun testBuildForRuntimePreservesExplicitXhttpAlpnWhenProvided() {
        val outbound = Outbound(
            type = "vless",
            tag = "reality-xhttp-node",
            server = "35.194.192.123",
            serverPort = 13324,
            uuid = "2edd765b-a895-46ab-a01c-c4719947546b",
            flow = "xtls-rprx-vision",
            packetEncoding = "xudp",
            tls = TlsConfig(
                enabled = true,
                serverName = "apple.com",
                alpn = listOf("http/1.1"),
                reality = RealityConfig(
                    enabled = true,
                    publicKey = "HBnrh72W2LW-zJygpN_H0Kw5fO7kIWhw5Bd-8ieVGj0",
                    shortId = "94c5638d"
                ),
                utls = UtlsConfig(enabled = true, fingerprint = "chrome")
            ),
            transport = TransportConfig(
                type = "xhttp",
                path = "/2edd765b-a895-46ab-a01c-c4719947546b-xh",
                mode = "auto"
            )
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals(listOf("http/1.1"), runtime?.tls?.alpn)
    }

    @Test
    fun testBuildForRuntimeKeepsVmessHttpTransport() {
        val outbound = Outbound(
            type = "vmess",
            tag = "vmess-http-node",
            server = "18.225.57.7",
            serverPort = 32721,
            uuid = "c31a559b-8285-4b11-db99-d1edfc2b2b70",
            transport = TransportConfig(type = "http")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("vmess", runtime?.type)
        assertEquals("http", runtime?.transport?.type)
    }

    @Test
    fun testBuildForRuntimeKeepsHttpOutboundTopLevelPathAndHeaders() {
        val outbound = Outbound(
            type = "http",
            tag = "http-node",
            server = "proxy.example.com",
            serverPort = 443,
            username = "user",
            password = "pass",
            path = "/proxy",
            headers = mapOf("User-Agent" to "KunBox")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("http", runtime?.type)
        assertEquals("/proxy", runtime?.path)
        assertEquals("KunBox", runtime?.headers?.get("User-Agent"))
        assertNull(runtime?.transport)
    }

    @Test
    fun testBuildForRuntimeMigratesHttpUpgradeHostHeader() {
        val outbound = Outbound(
            type = "vless",
            tag = "legacy-httpupgrade-node",
            server = "edge.example.com",
            serverPort = 443,
            uuid = "uuid",
            tls = TlsConfig(enabled = true, serverName = "edge.example.com"),
            transport = TransportConfig(
                type = "httpupgrade",
                path = "/up",
                headers = mapOf(
                    "Host" to "cdn.example.com",
                    "User-Agent" to "custom-agent"
                )
            )
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("httpupgrade", runtime?.transport?.type)
        assertEquals("/up", runtime?.transport?.path)
        assertEquals(listOf("cdn.example.com"), runtime?.transport?.host)
        assertFalse(runtime?.transport?.headers?.containsKey("Host") == true)
        assertEquals("custom-agent", runtime?.transport?.headers?.get("User-Agent"))
        assertEquals("cdn.example.com", runtime?.tls?.serverName)
    }

    @Test
    fun testFixPreservesOuterSelectorDefaultForRouteGroup() {
        val outbound = Outbound(
            type = "selector",
            tag = "P:HK",
            outbounds = listOf("P:HK#AUTO", "PROXY"),
            default = "P:HK#AUTO"
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("selector", fixed.type)
        assertEquals(listOf("P:HK#AUTO", "PROXY"), fixed.outbounds)
        assertEquals("P:HK#AUTO", fixed.default)
        assertFalse(OutboundFixer.shouldKeepUrlTestForRuntime(fixed.tag))
    }

    @Test
    fun testFixConvertsNonWhitelistedUrlTestToSelector() {
        val outbound = Outbound(
            type = "urltest",
            tag = "manual-auto",
            outbounds = listOf("node-a", "node-b"),
            url = "https://example.com/test",
            interval = "5m",
            tolerance = 10
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("selector", fixed.type)
        assertEquals(listOf("node-a", "node-b"), fixed.outbounds)
        assertEquals("node-a", fixed.default)
        assertNull(fixed.url)
        assertNull(fixed.interval)
        assertNull(fixed.tolerance)
    }
}
