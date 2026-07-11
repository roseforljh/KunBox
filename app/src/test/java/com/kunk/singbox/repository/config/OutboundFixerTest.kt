package com.kunk.singbox.repository.config

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RealityConfig
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UtlsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("LargeClass")
class OutboundFixerTest {

    private val gson = Gson()

    @Test
    fun runtimeNormalizationPreservesMultiValueHeaders() {
        val websocket = gson.fromJson(
            """
                {
                  "type":"vmess",
                  "tag":"ws",
                  "server":"ws.example.com",
                  "server_port":443,
                  "uuid":"00000000-0000-0000-0000-000000000001",
                  "security":"auto",
                  "transport":{"type":"ws","headers":{"X-Multi":["one","two"]}}
                }
            """.trimIndent(),
            Outbound::class.java
        )
        val httpUpgrade = gson.fromJson(
            """
                {
                  "type":"vless",
                  "tag":"httpupgrade",
                  "server":"up.example.com",
                  "server_port":443,
                  "uuid":"00000000-0000-0000-0000-000000000002",
                  "transport":{
                    "type":"httpupgrade",
                    "headers":{"Host":"cdn.example.com","X-Multi":["one","two"]}
                  }
                }
            """.trimIndent(),
            Outbound::class.java
        )
        val naive = gson.fromJson(
            """
                {
                  "type":"naive",
                  "tag":"naive",
                  "server":"naive.example.com",
                  "server_port":443,
                  "username":"user",
                  "password":"pass",
                  "extra_headers":{"X-Multi":["one","two"]}
                }
            """.trimIndent(),
            Outbound::class.java
        )

        listOf(websocket, httpUpgrade, naive).forEach { outbound ->
            val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)
            assertNotNull(runtime)
            val json = JsonParser.parseString(gson.toJson(runtime)).asJsonObject
            val headers = when (outbound.type) {
                "naive" -> json.getAsJsonObject("extra_headers")
                else -> json.getAsJsonObject("transport").getAsJsonObject("headers")
            }

            assertTrue(headers.get("X-Multi").isJsonArray)
            assertEquals(2, headers.getAsJsonArray("X-Multi").size())
        }
    }

    @Test
    fun testFixNaivePreservesH2NetworkForRuntime() {
        val outbound = Outbound(
            type = "naive",
            tag = "naive-node",
            server = "naive.example.com",
            serverPort = 443,
            username = "u",
            password = "p",
            network = listOf("h2")
        )

        val fixed = OutboundFixer.buildRuntimeNaiveOutbound(
            fixed = outbound,
            tcpKeepAliveEnabled = false,
            tcpKeepAliveInterval = null,
            connectTimeout = null
        )

        assertEquals(null, fixed.network)
        assertEquals(false, fixed.quic)
        assertEquals(listOf("h2"), outbound.network)
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
            disableMtuDiscovery = true,
            brutalDebug = true,
            tls = TlsConfig(enabled = true, serverName = "edge.example.com")
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(outbound)

        assertEquals("hysteria2", runtime.type)
        assertEquals("secret", runtime.password)
        assertEquals(100, runtime.upMbps)
        assertEquals(200, runtime.downMbps)
        assertEquals("salamander", runtime.obfs?.type)
        assertEquals("obfs-pass", runtime.obfs?.password)
        assertEquals(listOf("20000", "20001"), runtime.serverPorts)
        assertEquals("30s", runtime.hopInterval)
        assertNull(runtime.disableMtuDiscovery)
        assertEquals(true, runtime.brutalDebug)
        assertEquals("edge.example.com", runtime.tls?.serverName)
    }

    @Test
    fun testBuildRuntimeHysteria2OmitsServerPortWhenServerPortsConfigured() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 60000,
            password = "secret",
            serverPorts = listOf("60000-65530"),
            tls = TlsConfig(enabled = true, serverName = "edge.example.com")
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(outbound)

        assertNull(runtime.serverPort)
        assertEquals(listOf("60000-65530"), runtime.serverPorts)
    }

    @Test
    fun testBuildRuntimeHysteriaPreservesDisableMtuDiscovery() {
        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            Outbound(
                type = "hysteria",
                tag = "hy-node",
                server = "hy.example.com",
                serverPort = 443,
                authStr = "secret",
                disableMtuDiscovery = true,
                tls = TlsConfig(enabled = true, serverName = "hy.example.com")
            )
        )

        assertEquals(true, runtime.disableMtuDiscovery)
    }

    @Test
    fun testBuildForRuntimeKeepsOnlyOfficialShadowsocksPluginFields() {
        val outbound = Outbound(
            type = "shadowsocks",
            tag = "ss-v2ray-plugin",
            server = "ss.example.com",
            serverPort = 8388,
            method = "2022-blake3-aes-128-gcm",
            password = "secret",
            plugin = "v2ray-plugin",
            pluginOpts = "mode=websocket;host=edge.example.com",
            transport = TransportConfig(
                type = "ws",
                path = "/ws",
                headers = mapOf("Host" to "edge.example.com")
            ),
            tls = TlsConfig(enabled = true, serverName = "edge.example.com")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("shadowsocks", runtime?.type)
        assertEquals("v2ray-plugin", runtime?.plugin)
        assertEquals("mode=websocket;host=edge.example.com", runtime?.pluginOpts)
        assertNull(runtime?.transport)
        assertNull(runtime?.tls)
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

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(outbound)

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

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(outbound)

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

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(outbound)

        assertNull(runtime.domainResolver)
    }

    @Test
    fun testBuildRuntimeHysteria2KeepsBandwidthUnsetWhenMissing() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret"
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(outbound)

        assertNull(runtime.upMbps)
        assertNull(runtime.downMbps)
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

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(outbound)

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
    fun testFixPreservesOfficialUrlTestWithoutDefault() {
        val outbound = Outbound(
            type = "urltest",
            tag = "P:HK#AUTO",
            outbounds = listOf("node-a", "node-b"),
            default = "node-b",
            url = "https://www.gstatic.com/generate_204",
            interval = "10m",
            tolerance = 50,
            idleTimeout = "30m"
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("urltest", fixed.type)
        assertEquals(listOf("node-a", "node-b"), fixed.outbounds)
        assertNull(fixed.default)
        assertEquals("https://www.gstatic.com/generate_204", fixed.url)
        assertEquals("10m", fixed.interval)
        assertEquals(50, fixed.tolerance)
        assertEquals("30m", fixed.idleTimeout)
    }

    @Test
    fun testBuildForRuntimeKeepsOfficialUrlTestWithoutDefault() {
        val outbound = Outbound(
            type = "urltest",
            tag = "manual-auto",
            outbounds = listOf("node-a", "node-b"),
            default = "node-b",
            url = "https://www.gstatic.com/generate_204",
            interval = "10m",
            tolerance = 50,
            idleTimeout = "30m"
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("urltest", runtime?.type)
        assertEquals(listOf("node-a", "node-b"), runtime?.outbounds)
        assertNull(runtime?.default)
        assertEquals("https://www.gstatic.com/generate_204", runtime?.url)
        assertEquals("10m", runtime?.interval)
        assertEquals(50, runtime?.tolerance)
        assertEquals("30m", runtime?.idleTimeout)
    }

    @Test
    fun runtimeRejectsEmptySelectorAndUrlTestInsteadOfFallingBackToDirect() {
        listOf("selector", "urltest", "url-test").forEach { type ->
            val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(
                Outbound(type = type, tag = "empty-$type", outbounds = emptyList())
            )

            assertNull(runtime)
        }
    }

    @Test
    fun testBuildForRuntimeRejectsUnsupportedVlessEncryption() {
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

        assertNull(runtime)
    }

    @Test
    fun testBuildForRuntimeRemovesCompatibleVlessNoneEncryption() {
        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(
            Outbound(
                type = "vless",
                tag = "vless-none-encryption",
                server = "vless.example.com",
                serverPort = 443,
                uuid = "uuid",
                encryption = "none"
            )
        )

        assertNotNull(runtime)
        assertNull(runtime?.encryption)
    }

    @Test
    fun testBuildForRuntimeKeepsVmessPacketEncodingUnset() {
        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(
            Outbound(
                type = "vmess",
                tag = "vmess-default-packet-encoding",
                server = "vmess.example.com",
                serverPort = 443,
                uuid = "uuid",
                security = "auto"
            )
        )

        assertNull(runtime?.packetEncoding)
    }

    @Test
    fun testBuildForRuntimePreservesExplicitlyDisabledVlessPacketEncoding() {
        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(
            Outbound(
                type = "vless",
                tag = "vless-disabled-packet-encoding",
                server = "vless.example.com",
                serverPort = 443,
                uuid = "uuid",
                packetEncoding = ""
            )
        )

        assertEquals("", runtime?.packetEncoding)
    }

    @Test
    fun testBuildForRuntimeSupportsVlessXhttpTransport() {
        val outbound = Outbound(
            type = "vless",
            tag = "xhttp-node",
            server = "xhttp.example.com",
            serverPort = 443,
            uuid = "uuid",
            transport = com.kunk.singbox.model.TransportConfig(type = "xhttp", path = "/x")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertNotNull(runtime)
        assertEquals("xhttp", runtime?.transport?.type)
        assertEquals("/x", runtime?.transport?.path)
    }

    @Test
    fun testBuildForRuntimeCanonicalizesVlessSplitHttpTransport() {
        val outbound = Outbound(
            type = "vless",
            tag = "xhttp-node",
            server = "xhttp.example.com",
            serverPort = 443,
            uuid = "uuid",
            packetEncoding = "xudp",
            transport = TransportConfig(type = "splithttp", path = "/x")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertNotNull(runtime)
        assertEquals("xhttp", runtime?.transport?.type)
        assertEquals("/x", runtime?.transport?.path)
    }

    @Test
    fun testBuildForRuntimeRejectsUnsupportedEncryptedVlessWebSocket() {
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

        assertNull(runtime)
    }

    @Test
    fun testBuildForRuntimeKeepsExplicitPacketEncodingWithVlessNoneEncryption() {
        val outbound = Outbound(
            type = "vless",
            tag = "encrypted-ws-node",
            server = "34.150.59.170",
            serverPort = 39797,
            uuid = "b6fd6867-c239-4d95-8a98-cb036d34fc21",
            packetEncoding = "xudp",
            encryption = "none",
            transport = TransportConfig(type = "ws", path = "/b6fd6867-c239-4d95-8a98-cb036d34fc21-vw")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("xudp", runtime?.packetEncoding)
        assertEquals("ws", runtime?.transport?.type)
        assertNull(runtime?.encryption)
    }

    @Test
    fun testBuildForRuntimeSupportsRealityVisionXhttp() {
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
                reality = RealityConfig(
                    enabled = true,
                    publicKey = "HBnrh72W2LW-zJygpN_H0Kw5fO7kIWhw5Bd-8ieVGj0",
                    shortId = "94c5638d"
                ),
                utls = UtlsConfig(enabled = true, fingerprint = "chrome")
            ),
            transport = TransportConfig(
                type = "xhttp",
                path = "/2edd765b-a895-46ab-a01c-c4719947546b-xh"
            )
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertNotNull(runtime)
        assertEquals("xhttp", runtime?.transport?.type)
        assertEquals("h2", runtime?.tls?.alpn?.single())
    }

    @Test
    fun testBuildForRuntimePreservesExplicitXhttpAlpn() {
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
                path = "/2edd765b-a895-46ab-a01c-c4719947546b-xh"
            )
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertNotNull(runtime)
        assertEquals("xhttp", runtime?.transport?.type)
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
    fun testBuildForRuntimeDoesNotConvertOfficialNetworkToTransport() {
        val outbound = Outbound(
            type = "vmess",
            tag = "udp-only",
            server = "vmess.example.com",
            serverPort = 443,
            uuid = "uuid",
            security = "auto",
            network = listOf("udp")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals(listOf("udp"), runtime?.network)
        assertNull(runtime?.transport)
    }

    @Test
    fun testBuildForRuntimeRejectsUnknownV2RayTransportType() {
        val outbound = Outbound(
            type = "trojan",
            tag = "invalid-transport",
            server = "trojan.example.com",
            serverPort = 443,
            password = "secret",
            transport = TransportConfig(type = "h3")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertNull(runtime)
    }

    @Test
    fun testBuildForRuntimeValidatesWebSocketEarlyDataUInt32Range() {
        fun runtime(maxEarlyData: Long): Outbound? {
            return OutboundFixer.buildForRuntimeWithDialConfigForTest(
                Outbound(
                    type = "vless",
                    tag = "ws-$maxEarlyData",
                    server = "vless.example.com",
                    serverPort = 443,
                    uuid = "uuid",
                    transport = TransportConfig(type = "ws", maxEarlyData = maxEarlyData)
                )
            )
        }

        assertEquals(4_294_967_295L, runtime(4_294_967_295L)?.transport?.maxEarlyData)
        assertNull(runtime(4_294_967_296L))
        assertNull(runtime(-1L))
    }

    @Test
    fun testBuildForRuntimeMigratesWebSocketEarlyDataFromPath() {
        fun runtime(path: String): Outbound? {
            return OutboundFixer.buildForRuntimeWithDialConfigForTest(
                Outbound(
                    type = "trojan",
                    tag = "ws-path-early-data",
                    server = "trojan.example.com",
                    serverPort = 443,
                    password = "secret",
                    tls = TlsConfig(enabled = true),
                    transport = TransportConfig(type = "ws", path = path)
                )
            )
        }

        val valid = runtime("/ws?foo=bar&ed=2048")
        assertEquals("/ws?foo=bar", valid?.transport?.path)
        assertEquals(2048L, valid?.transport?.maxEarlyData)
        assertEquals("Sec-WebSocket-Protocol", valid?.transport?.earlyDataHeaderName)
        assertNull(runtime("/ws?ed=4294967296"))
        assertNull(runtime("/ws?ed=-1"))
    }

    @Test
    fun testBuildForRuntimeRejectsQuicWithoutTls() {
        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(
            Outbound(
                type = "vmess",
                tag = "quic-without-tls",
                server = "vmess.example.com",
                serverPort = 443,
                uuid = "uuid",
                security = "auto",
                transport = TransportConfig(type = "quic")
            )
        )

        assertNull(runtime)
    }

    @Test
    fun testBuildForRuntimeCleansFieldsOutsideTransportSchema() {
        fun runtime(type: String): TransportConfig? {
            return OutboundFixer.buildForRuntimeWithDialConfigForTest(
                Outbound(
                    type = "vless",
                    tag = "transport-$type",
                    server = "vless.example.com",
                    serverPort = 443,
                    uuid = "uuid",
                    tls = TlsConfig(enabled = true),
                    transport = TransportConfig(
                        type = type,
                        path = "/path",
                        method = "GET",
                        headers = mapOf("Host" to "header.example.com", "X-Test" to "value"),
                        serviceName = "service",
                        host = listOf("h1.example.com", "h2.example.com"),
                        idleTimeout = "10s",
                        pingTimeout = "5s",
                        permitWithoutStream = true,
                        earlyDataHeaderName = "X-Early-Data",
                        maxEarlyData = 2048L
                    )
                )
            )?.transport
        }

        val http = runtime("h2")
        assertEquals("http", http?.type)
        assertEquals("GET", http?.method)
        assertEquals(listOf("h1.example.com", "h2.example.com"), http?.host)
        assertNull(http?.serviceName)
        assertNull(http?.permitWithoutStream)
        assertNull(http?.earlyDataHeaderName)
        assertNull(http?.maxEarlyData)

        val ws = runtime("ws")
        assertEquals("ws", ws?.type)
        assertEquals("/path", ws?.path)
        assertEquals(2048L, ws?.maxEarlyData)
        assertNull(ws?.method)
        assertNull(ws?.serviceName)
        assertNull(ws?.host)
        assertNull(ws?.idleTimeout)
        assertNull(ws?.pingTimeout)
        assertNull(ws?.permitWithoutStream)

        assertEquals(TransportConfig(type = "quic"), runtime("quic"))

        val grpc = runtime("grpc")
        assertEquals("service", grpc?.serviceName)
        assertEquals("10s", grpc?.idleTimeout)
        assertEquals("5s", grpc?.pingTimeout)
        assertEquals(true, grpc?.permitWithoutStream)
        assertNull(grpc?.path)
        assertNull(grpc?.method)
        assertNull(grpc?.headers)
        assertNull(grpc?.host)
        assertNull(grpc?.earlyDataHeaderName)
        assertNull(grpc?.maxEarlyData)

        val httpUpgrade = runtime("httpupgrade")
        assertEquals(listOf("h1.example.com"), httpUpgrade?.host)
        assertEquals("value", httpUpgrade?.headers?.get("X-Test"))
        assertFalse(httpUpgrade?.headers?.containsKey("Host") == true)
        assertNull(httpUpgrade?.method)
        assertNull(httpUpgrade?.serviceName)
        assertNull(httpUpgrade?.idleTimeout)
        assertNull(httpUpgrade?.pingTimeout)
        assertNull(httpUpgrade?.permitWithoutStream)
        assertNull(httpUpgrade?.earlyDataHeaderName)
        assertNull(httpUpgrade?.maxEarlyData)
    }

    @Test
    fun testBuildForRuntimeMapsNaiveQuicWithoutTransport() {
        val outbound = Outbound(
            type = "naive",
            tag = "naive-quic",
            server = "naive.example.com",
            serverPort = 443,
            username = "user",
            password = "pass",
            network = listOf("quic"),
            quic = true,
            quicCongestionControl = "bbr",
            tls = TlsConfig(
                enabled = true,
                serverName = "naive.example.com",
                insecure = true,
                alpn = listOf("h3"),
                utls = UtlsConfig(enabled = true, fingerprint = "chrome"),
                reality = RealityConfig(enabled = true, publicKey = "key")
            )
        )

        val runtime = OutboundFixer.buildRuntimeNaiveOutbound(
            fixed = OutboundFixer.fix(outbound),
            tcpKeepAliveEnabled = false,
            tcpKeepAliveInterval = null,
            connectTimeout = null
        )

        assertEquals(true, runtime.quic)
        assertEquals("bbr", runtime.quicCongestionControl)
        assertNull(runtime.transport)
        assertNull(runtime.network)
        assertEquals(true, runtime.tls?.insecure)
        assertEquals(listOf("h3"), runtime.tls?.alpn)
        assertEquals("chrome", runtime.tls?.utls?.fingerprint)
        assertEquals("key", runtime.tls?.reality?.publicKey)
    }

    @Test
    fun testBuildForRuntimeRejectsRemovedWireGuardOutbound() {
        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(
            Outbound(type = "wireguard", tag = "legacy-wireguard")
        )

        assertNull(runtime)
    }

    @Test
    fun testBuildForRuntimePreservesSshInlinePrivateKey() {
        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(
            Outbound(
                type = "ssh",
                tag = "ssh-node",
                server = "ssh.example.com",
                serverPort = 22,
                user = "root",
                privateKey = listOf("-----BEGIN OPENSSH PRIVATE KEY-----")
            )
        )

        assertEquals(listOf("-----BEGIN OPENSSH PRIVATE KEY-----"), runtime?.privateKey)
    }

    @Test
    fun testBuildForRuntimeDoesNotOverwriteExplicitWebSocketSni() {
        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(
            Outbound(
                type = "vless",
                tag = "ws-explicit-sni",
                server = "edge.example.com",
                serverPort = 443,
                uuid = "uuid",
                tls = TlsConfig(enabled = true, serverName = "edge.example.com"),
                transport = TransportConfig(
                    type = "ws",
                    path = "/ws",
                    headers = mapOf("Host" to "cdn.example.com")
                )
            )
        )

        assertEquals("edge.example.com", runtime?.tls?.serverName)
    }

    @Test
    fun testBuildForRuntimeMigratesHttpUpgradeHostHeaderWithoutOverwritingSni() {
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
        assertEquals("edge.example.com", runtime?.tls?.serverName)
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
    }

    @Test
    fun testFixPreservesNonRouteGroupUrlTest() {
        val outbound = Outbound(
            type = "urltest",
            tag = "manual-auto",
            outbounds = listOf("node-a", "node-b"),
            url = "https://example.com/test",
            interval = "5m",
            tolerance = 10
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("urltest", fixed.type)
        assertEquals(listOf("node-a", "node-b"), fixed.outbounds)
        assertNull(fixed.default)
        assertEquals("https://example.com/test", fixed.url)
        assertEquals("5m", fixed.interval)
        assertEquals(10, fixed.tolerance)
    }
}
