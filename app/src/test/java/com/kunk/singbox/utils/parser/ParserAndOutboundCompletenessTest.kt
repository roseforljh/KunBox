package com.kunk.singbox.utils.parser

import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.repository.config.OutboundFixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ParserAndOutboundCompletenessTest {

    private val clashParser = ClashYamlParser()
    private val linkParser = NodeLinkParser(Gson())

    @Test
    fun clashYamlPreservesDialFieldsAndHy2Options() {
        val yaml = """
            proxies:
              - name: relay
                type: ss
                server: 1.1.1.1
                port: 8388
                cipher: aes-256-gcm
                password: secret
                udp-over-tcp: true
              - name: hy2-landing
                type: hysteria2
                server: 2.2.2.2
                port: 443
                password: hy2-pass
                up: "100 Mbps"
                down: "1 Gbps"
                ports: "20000-30000,40000-50000"
                dialer-proxy: relay
                tfo: true
                mptcp: true
                ip-version: ipv4-prefer
        """.trimIndent()

        val config = clashParser.parse(yaml)
        assertNotNull(config)
        val outbounds = config?.outbounds.orEmpty().associateBy { it.tag }

        assertEquals(true, outbounds["relay"]?.udpOverTcp?.enabled)

        val hy2 = outbounds["hy2-landing"]
        assertEquals("relay", hy2?.detour)
        assertEquals(true, hy2?.tcpFastOpen)
        assertEquals(true, hy2?.tcpMultiPath)
        assertEquals("prefer_ipv4", hy2?.domainStrategy)
        assertEquals(100, hy2?.upMbps)
        assertEquals(1000, hy2?.downMbps)
        assertEquals(listOf("20000-30000", "40000-50000"), hy2?.serverPorts)
    }

    @Test
    fun clashYamlPreservesRealityAndTrojanWsHost() {
        val yaml = """
            proxies:
              - name: vless-reality
                type: vless
                server: 3.3.3.3
                port: 443
                uuid: 11111111-1111-1111-1111-111111111111
                servername: example.com
                reality-opts:
                  public-key: pubkey123
                  short-id: abcd
              - name: trojan-reality
                type: trojan
                server: 4.4.4.4
                port: 443
                password: tpass
                sni: example.com
                network: ws
                ws-opts:
                  path: /ws
                  headers:
                    host: custom.example.com
                reality-opts:
                  public-key: tpubkey
                  short-id: "01"
        """.trimIndent()

        val outbounds = clashParser.parse(yaml)?.outbounds.orEmpty().associateBy { it.tag }

        val vless = outbounds["vless-reality"]
        assertEquals(true, vless?.tls?.enabled)
        assertEquals("pubkey123", vless?.tls?.reality?.publicKey)

        val trojan = outbounds["trojan-reality"]
        assertEquals("tpubkey", trojan?.tls?.reality?.publicKey)
        val wsHeaders = trojan?.transport?.headers.orEmpty()
        assertEquals(1, wsHeaders.keys.count { it.equals("Host", ignoreCase = true) })
    }

    @Test
    fun nodeLinksPreserveVmessScyAlpnInsecureAnyTlsBooleanAndNaiveQuic() {
        val vmessJson = """
            {"v":"2","ps":"vm","add":"example.com","port":"443","id":"11111111-1111-1111-1111-111111111111",
             "aid":"0","scy":"chacha20-poly1305","net":"ws","host":"ws.example.com","path":"/v",
             "tls":"tls","sni":"sni.example.com","alpn":"h2,http/1.1","allowInsecure":"true"}
        """.trimIndent()
        val vmessLink = "vmess://" + Base64.getEncoder().encodeToString(vmessJson.toByteArray(Charsets.UTF_8))
        val vmess = linkParser.parse(vmessLink)
        assertEquals("chacha20-poly1305", vmess?.security)
        assertEquals(true, vmess?.tls?.insecure)
        assertEquals(listOf("h2", "http/1.1"), vmess?.tls?.alpn)

        val anyTls = linkParser.parse("anytls://pass@example.com:443?sni=example.com&insecure=true#any")
        assertEquals(true, anyTls?.tls?.insecure)

        val naiveQuic = linkParser.parse("naive+quic://user:pass@example.com:443?sni=example.com#nq")
        assertEquals("naive", naiveQuic?.type)
        assertEquals(true, naiveQuic?.quic)
        assertEquals(listOf("quic"), naiveQuic?.network)
    }

    @Test
    fun outboundFixerPreservesTuicDisableSniAnyTlsNetworkAndHysteriaConnectTimeout() {
        val tuic = OutboundFixer.buildForRuntimeWithDialConfigForTest(
            Outbound(
                type = "tuic",
                tag = "tuic-node",
                server = "example.com",
                serverPort = 443,
                uuid = "11111111-1111-1111-1111-111111111111",
                password = "pw",
                disableSni = true,
                tls = TlsConfig(enabled = true)
            )
        )
        assertEquals(true, tuic?.tls?.disableSni)

        val anyTls = OutboundFixer.buildForRuntimeWithDialConfigForTest(
            Outbound(
                type = "anytls",
                tag = "anytls-node",
                server = "example.com",
                serverPort = 443,
                password = "pw",
                network = listOf("tcp"),
                tls = TlsConfig(enabled = true, serverName = "example.com")
            )
        )
        assertEquals(listOf("tcp"), anyTls?.network)

        val hy2 = OutboundFixer.buildForRuntimeWithDialConfigForTest(
            outbound = Outbound(
                type = "hysteria2",
                tag = "hy2-node",
                server = "example.com",
                serverPort = 443,
                password = "pw",
                tls = TlsConfig(enabled = true, serverName = "example.com")
            ),
            connectTimeout = "8s"
        )
        assertTrue(hy2?.connectTimeout == "8s")
    }
}
