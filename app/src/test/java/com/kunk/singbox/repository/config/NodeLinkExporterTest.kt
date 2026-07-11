package com.kunk.singbox.repository.config

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UdpOverTcpConfig
import com.kunk.singbox.model.WireGuardPeer
import com.kunk.singbox.utils.parser.NodeLinkParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class NodeLinkExporterTest {

    private val gson = Gson()

    @Test
    fun exportNaivePreservesMultiValueExtraHeaders() {
        val outbound = gson.fromJson(
            """
                {
                  "type":"naive",
                  "tag":"Naive Multi Header",
                  "server":"naive.example.com",
                  "server_port":443,
                  "username":"user",
                  "password":"pass",
                  "extra_headers":{"X-Multi":["one","two"]}
                }
            """.trimIndent(),
            Outbound::class.java
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }
        val serialized = JsonParser.parseString(gson.toJson(parsed)).asJsonObject
        val values = serialized.getAsJsonObject("extra_headers").getAsJsonArray("X-Multi")

        assertEquals(2, values.size())
        assertEquals("one", values[0].asString)
        assertEquals("two", values[1].asString)
    }

    @Test
    fun exportNaiveShouldUseNewFields() {
        val outbound = Outbound(
            type = "naive",
            tag = "Naive Node",
            server = "naive.example.com",
            serverPort = 443,
            username = "user",
            password = "pass",
            network = listOf("h2"),
            insecureConcurrency = 3,
            extraHeaders = mapOf("User-Agent" to "naive", "X-Test" to "demo"),
            congestionControl = "bbr",
            udpOverTcp = UdpOverTcpConfig(enabled = true),
            tls = TlsConfig(
                enabled = true,
                serverName = "naive.example.com",
                insecure = true,
                alpn = listOf("h3"),
                utls = com.kunk.singbox.model.UtlsConfig(enabled = true, fingerprint = "chrome")
            )
        )

        val link = NodeLinkExporter.export(outbound, gson)

        assertNotNull(link)
        assertTrue(link!!.contains("network=h2"))
        assertTrue(link.contains("insecure_concurrency=3"))
        assertTrue(link.contains("extra_headers="))
        assertTrue(link.contains("congestion_control=bbr"))
        assertTrue(link.contains("uot=1"))
        assertFalse(link.contains("path="))
        assertFalse(link.contains("host="))
        assertFalse(link.contains("insecure="))
        assertFalse(link.contains("alpn="))
        assertFalse(link.contains("fp="))
    }

    @Test
    fun exportNaiveShouldPreferQuicCongestionControl() {
        val outbound = Outbound(
            type = "naive",
            tag = "Naive QUIC",
            server = "naive.example.com",
            serverPort = 443,
            username = "user",
            password = "pass",
            network = listOf("quic"),
            quic = true,
            congestionControl = "cubic",
            quicCongestionControl = "bbr",
            tls = TlsConfig(enabled = true)
        )

        val link = NodeLinkExporter.export(outbound, gson)

        assertNotNull(link)
        assertTrue(link!!.contains("network=quic"))
        assertTrue(link.contains("congestion_control=bbr"))
        assertFalse(link.contains("congestion_control=cubic"))
    }

    @Test
    fun exportVlessShouldAlwaysUseCompatibleNoneEncryption() {
        val outbound = Outbound(
            type = "vless",
            tag = "VLESS",
            server = "vless.example.com",
            serverPort = 443,
            uuid = "uuid",
            flow = "xtls-rprx-vision",
            encryption = "mlkem768x25519plus.native.0rtt.sample",
            tls = TlsConfig(enabled = true, serverName = "apple.com"),
            transport = TransportConfig(type = "ws", path = "/ws")
        )

        val link = NodeLinkExporter.export(outbound, gson)

        assertNotNull(link)
        assertTrue(link!!.contains("encryption=none"))
        assertFalse(link.contains("mlkem768x25519plus.native.0rtt.sample"))
        assertTrue(link.contains("type=ws"))
        assertTrue(link.contains("flow=xtls-rprx-vision"))
    }

    @Test
    fun exportVlessShouldPreserveXhttpTransport() {
        val outbound = Outbound(
            type = "vless",
            tag = "XHTTP",
            server = "vless.example.com",
            serverPort = 443,
            uuid = "uuid",
            transport = TransportConfig(
                type = "xhttp",
                path = "/xhttp",
                host = listOf("cdn.example.com"),
                mode = "auto",
                xPaddingBytes = "100-200",
                scMaxEachPostBytes = 1_048_576,
                scMinPostsIntervalMs = 30,
                scMaxBufferedPosts = 64,
                noGRPCHeader = true,
                noSSEHeader = true
            )
        )

        val link = NodeLinkExporter.export(outbound, gson)
        val parsed = link?.let { NodeLinkParser(gson).parse(it) }

        assertNotNull(link)
        assertEquals("xhttp", parsed?.transport?.type)
        assertEquals("/xhttp", parsed?.transport?.path)
        assertEquals(listOf("cdn.example.com"), parsed?.transport?.host)
        assertEquals("auto", parsed?.transport?.mode)
        assertEquals("100-200", parsed?.transport?.xPaddingBytes)
        assertEquals(1_048_576L, parsed?.transport?.scMaxEachPostBytes)
        assertEquals(true, parsed?.transport?.noGRPCHeader)
        assertEquals(true, parsed?.transport?.noSSEHeader)
    }

    @Test
    fun exportVlessShouldRejectPrivateXhttpEncryption() {
        val outbound = Outbound(
            type = "vless",
            tag = "Private Encryption",
            server = "vless.example.com",
            serverPort = 443,
            uuid = "uuid",
            encryption = "mlkem768x25519plus.native.0rtt.sample",
            transport = TransportConfig(type = "xhttp", path = "/private")
        )

        assertNull(NodeLinkExporter.export(outbound, gson))
    }

    @Test
    fun exportV2RayProtocolsShouldRejectUnknownTransport() {
        listOf("vmess", "vless", "trojan").forEach { protocol ->
            val outbound = Outbound(
                type = protocol,
                tag = "Unknown Transport",
                server = "$protocol.example.com",
                serverPort = 443,
                uuid = "uuid",
                password = "secret",
                transport = TransportConfig(type = "h3")
            )

            assertNull(NodeLinkExporter.export(outbound, gson))
        }
    }

    @Test
    fun exportWebSocketShouldValidateMaxEarlyDataUInt32Range() {
        fun export(maxEarlyData: Long): String? {
            return NodeLinkExporter.export(
                Outbound(
                    type = "vless",
                    tag = "VLESS WS",
                    server = "vless.example.com",
                    serverPort = 443,
                    uuid = "uuid",
                    transport = TransportConfig(type = "ws", path = "/ws", maxEarlyData = maxEarlyData)
                ),
                gson
            )
        }

        assertNotNull(export(4_294_967_295L))
        assertNull(export(4_294_967_296L))
        assertNull(export(-1L))
    }

    @Test
    fun exportVlessShouldPreserveHttpUpgradeTransport() {
        val outbound = Outbound(
            type = "vless",
            tag = "HTTPUpgrade",
            server = "edge.example.com",
            serverPort = 443,
            uuid = "uuid",
            tls = TlsConfig(enabled = true, serverName = "cdn.example.com"),
            transport = TransportConfig(type = "httpupgrade", path = "/up", host = listOf("cdn.example.com"))
        )

        val link = NodeLinkExporter.export(outbound, gson)
        val parsed = link?.let { NodeLinkParser(gson).parse(it) }

        assertNotNull(link)
        assertTrue(link!!.contains("type=httpupgrade"))
        assertTrue(link.contains("path=%2Fup"))
        assertTrue(link.contains("host=cdn.example.com"))
        assertEquals("httpupgrade", parsed?.transport?.type)
        assertEquals("/up", parsed?.transport?.path)
        assertEquals(listOf("cdn.example.com"), parsed?.transport?.host)
    }

    @Test
    fun exportVmessShouldPreserveAlterIdAsAid() {
        val outbound = Outbound(
            type = "vmess",
            tag = "Legacy VMess",
            server = "vmess.example.com",
            serverPort = 443,
            uuid = "uuid",
            alterId = 8
        )

        val link = NodeLinkExporter.export(outbound, gson)
        val payload = link!!.removePrefix("vmess://")
        val json = String(java.util.Base64.getDecoder().decode(payload))

        assertTrue(json, json.contains("\"aid\":\"8\""))
    }

    @Test
    fun exportTrojanShouldPreserveTransport() {
        val outbound = Outbound(
            type = "trojan",
            tag = "Trojan WS",
            server = "trojan.example.com",
            serverPort = 443,
            password = "secret",
            tls = TlsConfig(enabled = true, serverName = "edge.example.com"),
            transport = TransportConfig(
                type = "ws",
                path = "/ws",
                headers = mapOf("Host" to "edge.example.com")
            )
        )

        val link = NodeLinkExporter.export(outbound, gson)
        val parsed = link?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("ws", parsed?.transport?.type)
        assertEquals("/ws", parsed?.transport?.path)
        assertEquals("edge.example.com", parsed?.transport?.headers?.get("Host"))
    }

    @Test
    fun exportHysteria2ShouldPreserveRuntimeFields() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "HY2",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret",
            upMbps = 80,
            downMbps = 160,
            obfs = ObfsConfig(type = "salamander", password = "obfs-secret"),
            serverPorts = listOf("20000-21000"),
            hopInterval = "30s",
            network = listOf("udp"),
            disableMtuDiscovery = true,
            tls = TlsConfig(enabled = true, serverName = "edge.example.com", alpn = listOf("h3"))
        )

        val link = NodeLinkExporter.export(outbound, gson)
        val parsed = link?.let { NodeLinkParser(gson).parse(it) }

        assertEquals(80, parsed?.upMbps)
        assertEquals(160, parsed?.downMbps)
        assertEquals("salamander", parsed?.obfs?.type)
        assertEquals("obfs-secret", parsed?.obfs?.password)
        assertEquals(listOf("20000-21000"), parsed?.serverPorts)
        assertEquals("30s", parsed?.hopInterval)
        assertEquals(listOf("udp"), parsed?.network)
        assertFalse(link.orEmpty().contains("disable_mtu_discovery"))
        assertNull(parsed?.disableMtuDiscovery)
        assertEquals(listOf("h3"), parsed?.tls?.alpn)
    }

    @Test
    fun exportHysteriaShouldPreserveStringObfsAndBandwidth() {
        val outbound = Outbound(
            type = "hysteria",
            tag = "HY1",
            server = "hy.example.com",
            serverPort = 443,
            authStr = "auth",
            upMbps = 40,
            downMbps = 120,
            obfs = ObfsConfig(type = "obfs-secret", stringValue = true),
            serverPorts = listOf("30000,30001"),
            hopInterval = "20s",
            recvWindowConn = BigInteger.valueOf(1024),
            recvWindow = BigInteger.valueOf(2048),
            disableMtuDiscovery = true,
            tls = TlsConfig(enabled = true, serverName = "hy.example.com", insecure = true)
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("auth", parsed?.authStr)
        assertEquals(40, parsed?.upMbps)
        assertEquals(120, parsed?.downMbps)
        assertEquals("obfs-secret", parsed?.obfs?.type)
        assertEquals(true, parsed?.obfs?.stringValue)
        assertEquals(listOf("30000", "30001"), parsed?.serverPorts)
        assertEquals("20s", parsed?.hopInterval)
        assertEquals(BigInteger.valueOf(1024), parsed?.recvWindowConn)
        assertEquals(BigInteger.valueOf(2048), parsed?.recvWindow)
        assertEquals(true, parsed?.disableMtuDiscovery)
        assertEquals(true, parsed?.tls?.insecure)
    }

    @Test
    fun exportTuicShouldPreserveTransportOptions() {
        val outbound = Outbound(
            type = "tuic",
            tag = "TUIC",
            server = "tuic.example.com",
            serverPort = 443,
            uuid = "uuid",
            password = "secret",
            congestionControl = "bbr",
            udpRelayMode = "native",
            udpOverStream = true,
            zeroRttHandshake = true,
            heartbeat = "10s",
            network = listOf("udp"),
            tls = TlsConfig(enabled = true, serverName = "tuic.example.com")
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("bbr", parsed?.congestionControl)
        assertEquals("native", parsed?.udpRelayMode)
        assertEquals(true, parsed?.udpOverStream)
        assertEquals(true, parsed?.zeroRttHandshake)
        assertEquals("10s", parsed?.heartbeat)
        assertEquals(listOf("udp"), parsed?.network)
    }

    @Test
    fun exportShadowsocksShouldPreservePluginParams() {
        val outbound = Outbound(
            type = "shadowsocks",
            tag = "Plugin Node",
            server = "1.2.3.4",
            serverPort = 8388,
            method = "aes-256-gcm",
            password = "password",
            plugin = "v2ray-plugin",
            pluginOpts = "mode=websocket;host=cdn.example.com"
        )

        val link = NodeLinkExporter.export(outbound, gson)
        val parsed = link?.let { NodeLinkParser(gson).parse(it) }

        assertNotNull(link)
        assertTrue(link!!.contains("plugin="))
        assertEquals("v2ray-plugin", parsed?.plugin)
        assertEquals("mode=websocket;host=cdn.example.com", parsed?.pluginOpts)
    }

    @Test
    fun exportHttpShouldRoundTrip() {
        val outbound = Outbound(
            type = "http",
            tag = "HTTP Proxy",
            server = "proxy.example.com",
            serverPort = 8080,
            username = "user",
            password = "pass",
            path = "/proxy",
            headers = mapOf("User-Agent" to "KunBox")
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("http", parsed?.type)
        assertEquals("proxy.example.com", parsed?.server)
        assertEquals(8080, parsed?.serverPort)
        assertEquals("user", parsed?.username)
        assertEquals("pass", parsed?.password)
        assertEquals("/proxy", parsed?.path)
        assertEquals("KunBox", parsed?.headers?.get("User-Agent"))
    }

    @Test
    fun exportSocksShouldRoundTrip() {
        val outbound = Outbound(
            type = "socks",
            tag = "SOCKS Proxy",
            server = "socks.example.com",
            serverPort = 1080,
            version = JsonPrimitive("4a"),
            username = "user",
            password = "pass",
            network = listOf("tcp"),
            udpOverTcp = UdpOverTcpConfig(enabled = true)
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("socks", parsed?.type)
        assertEquals("socks.example.com", parsed?.server)
        assertEquals(1080, parsed?.serverPort)
        assertEquals("user", parsed?.username)
        assertEquals("pass", parsed?.password)
        assertEquals("4a", parsed?.version?.asString)
        assertEquals(listOf("tcp"), parsed?.network)
        assertEquals(true, parsed?.udpOverTcp?.enabled)
    }

    @Test
    fun exportSshShouldRoundTrip() {
        val outbound = Outbound(
            type = "ssh",
            tag = "SSH Proxy",
            server = "ssh.example.com",
            serverPort = 22,
            user = "root",
            password = "secret",
            privateKey = listOf("-----BEGIN OPENSSH PRIVATE KEY-----"),
            privateKeyPassphrase = "passphrase"
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("ssh", parsed?.type)
        assertEquals("ssh.example.com", parsed?.server)
        assertEquals(22, parsed?.serverPort)
        assertEquals("root", parsed?.user)
        assertEquals("secret", parsed?.password)
        assertEquals(listOf("-----BEGIN OPENSSH PRIVATE KEY-----"), parsed?.privateKey)
        assertEquals("passphrase", parsed?.privateKeyPassphrase)
    }

    @Test
    fun exportWireGuardShouldRoundTripEndpointFields() {
        val outbound = Outbound(
            type = "wireguard",
            tag = "WG Node",
            privateKey = listOf("private-key"),
            localAddress = listOf("172.16.0.2/32"),
            mtu = 1380,
            workers = 2,
            peers = listOf(
                WireGuardPeer(
                    server = "wg.example.com",
                    serverPort = 51820,
                    publicKey = "public-key",
                    preSharedKey = "psk",
                    allowedIps = listOf("0.0.0.0/0", "::/0"),
                    persistentKeepaliveInterval = 25,
                    reserved = listOf(1, 2, 3)
                )
            )
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("wireguard", parsed?.type)
        assertEquals(listOf("private-key"), parsed?.privateKey)
        assertEquals(listOf("172.16.0.2/32"), parsed?.localAddress)
        assertEquals(1380, parsed?.mtu)
        assertEquals(2, parsed?.workers)
        assertEquals("wg.example.com", parsed?.peers?.single()?.server)
        assertEquals(listOf("0.0.0.0/0", "::/0"), parsed?.peers?.single()?.allowedIps)
        assertEquals(25, parsed?.peers?.single()?.persistentKeepaliveInterval)
        assertEquals(listOf(1, 2, 3), parsed?.peers?.single()?.reserved)
    }
}
