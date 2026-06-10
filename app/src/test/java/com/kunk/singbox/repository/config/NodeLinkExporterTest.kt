package com.kunk.singbox.repository.config

import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UdpOverTcpConfig
import com.kunk.singbox.model.WireGuardPeer
import com.kunk.singbox.utils.parser.NodeLinkParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeLinkExporterTest {

    private val gson = Gson()

    @Test
    fun exportNaiveShouldUseNewFields() {
        val outbound = Outbound(
            type = "naive",
            tag = "Naive Node",
            server = "naive.example.com",
            serverPort = 443,
            username = "user",
            password = "pass",
            network = "h2",
            insecureConcurrency = 3,
            extraHeaders = mapOf("User-Agent" to "naive", "X-Test" to "demo"),
            congestionControl = "bbr",
            udpOverTcp = UdpOverTcpConfig(enabled = true),
            tls = TlsConfig(enabled = true, serverName = "naive.example.com", insecure = true)
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
            network = "quic",
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
    fun exportVlessShouldPreserveCustomEncryption() {
        val outbound = Outbound(
            type = "vless",
            tag = "Encrypted XHTTP",
            server = "xhttp.example.com",
            serverPort = 443,
            uuid = "uuid",
            flow = "xtls-rprx-vision",
            encryption = "mlkem768x25519plus.native.0rtt.sample",
            tls = TlsConfig(enabled = true, serverName = "apple.com"),
            transport = TransportConfig(type = "xhttp", path = "/node-xh", mode = "auto")
        )

        val link = NodeLinkExporter.export(outbound, gson)

        assertNotNull(link)
        assertTrue(link!!.contains("encryption=mlkem768x25519plus.native.0rtt.sample"))
        assertTrue(link.contains("type=xhttp"))
        assertTrue(link.contains("flow=xtls-rprx-vision"))
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
            password = "pass"
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("http", parsed?.type)
        assertEquals("proxy.example.com", parsed?.server)
        assertEquals(8080, parsed?.serverPort)
        assertEquals("user", parsed?.username)
        assertEquals("pass", parsed?.password)
    }

    @Test
    fun exportSocksShouldRoundTrip() {
        val outbound = Outbound(
            type = "socks",
            tag = "SOCKS Proxy",
            server = "socks.example.com",
            serverPort = 1080,
            username = "user",
            password = "pass"
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("socks", parsed?.type)
        assertEquals("socks.example.com", parsed?.server)
        assertEquals(1080, parsed?.serverPort)
        assertEquals("user", parsed?.username)
        assertEquals("pass", parsed?.password)
    }

    @Test
    fun exportSshShouldRoundTrip() {
        val outbound = Outbound(
            type = "ssh",
            tag = "SSH Proxy",
            server = "ssh.example.com",
            serverPort = 22,
            user = "root",
            password = "secret"
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("ssh", parsed?.type)
        assertEquals("ssh.example.com", parsed?.server)
        assertEquals(22, parsed?.serverPort)
        assertEquals("root", parsed?.user)
        assertEquals("secret", parsed?.password)
    }

    @Test
    fun exportWireGuardShouldRoundTrip() {
        val outbound = Outbound(
            type = "wireguard",
            tag = "WG Node",
            privateKey = "private-key",
            localAddress = listOf("172.16.0.2/32"),
            peers = listOf(
                WireGuardPeer(
                    server = "wg.example.com",
                    serverPort = 51820,
                    publicKey = "public-key",
                    preSharedKey = "psk"
                )
            )
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("wireguard", parsed?.type)
        assertEquals("private-key", parsed?.privateKey)
        assertEquals(listOf("172.16.0.2/32"), parsed?.localAddress)
        assertEquals("wg.example.com", parsed?.peers?.firstOrNull()?.server)
        assertEquals("public-key", parsed?.peers?.firstOrNull()?.publicKey)
        assertEquals("psk", parsed?.peers?.firstOrNull()?.preSharedKey)
    }
}
