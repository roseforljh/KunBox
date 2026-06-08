package com.kunk.singbox.repository.config

import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UdpOverTcpConfig
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
}
