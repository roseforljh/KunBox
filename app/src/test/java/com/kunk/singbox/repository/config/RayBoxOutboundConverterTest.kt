package com.kunk.singbox.repository.config

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RayBoxOutboundConverterTest {
    private val gson = Gson()

    private fun node(type: String = "vless") = Outbound(
        type = type, tag = "test", server = "example.com", serverPort = 443,
        uuid = if (type == "trojan") null else "00000000-0000-0000-0000-000000000001",
        password = if (type == "trojan") "secret" else null,
        transport = TransportConfig(type = "xhttp", path = "/test", host = listOf("edge.example.com")),
        tls = TlsConfig(enabled = true, insecure = true, alpn = listOf("h2")), detour = "front"
    )

    @Test
    fun convertsAllProtocolsAndPreservesOuterDialFields() {
        listOf("vless", "vmess", "trojan").forEach { type ->
            val result = RayBoxOutboundConverter.convert(node(type))
            val outer = gson.toJsonTree(result).asJsonObject
            val inner = outer.getAsJsonObject("outbound")
            assertEquals("xray", result.type)
            assertEquals("front", outer.get("detour").asString)
            assertEquals(type, inner.get("protocol").asString)
            assertFalse(outer.has("server") || outer.has("tls") || outer.has("transport"))
            assertFalse(inner.getAsJsonObject("streamSettings").has("sockopt"))
            assertEquals(true, inner.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")
                .get("allowInsecure").asBoolean)
        }
    }

    @Test
    fun encryptedVlessWebsocketAndOrdinaryNodes() {
        val ordinary = node().copy(transport = TransportConfig(type = "ws"))
        assertSame(ordinary, RayBoxOutboundConverter.convert(ordinary))
        val encrypted = ordinary.copy(encryption = "example", transport = TransportConfig(type = "ws", path = "/ws"))
        val result = RayBoxOutboundConverter.convert(encrypted).xrayOutbound
        assertEquals("ws", result?.getAsJsonObject("streamSettings")?.get("network")?.asString)
        assertEquals("example", result?.getAsJsonObject("settings")?.getAsJsonArray("vnext")?.get(0)
            ?.asJsonObject?.getAsJsonArray("users")?.get(0)?.asJsonObject?.get("encryption")?.asString)
    }

    @Test
    fun preservesExtraAndRejectsUnsafeOrUnsupportedOptions() {
        val extra = JsonParser.parseString("""{"uplinkHTTPMethod":"GET","scMaxEachPostBytes":4096}""").asJsonObject
        val converted = RayBoxOutboundConverter.convert(node().copy(transport = node().transport?.copy(extra = extra)))
        assertEquals(extra, converted.xrayOutbound?.getAsJsonObject("streamSettings")
            ?.getAsJsonObject("xhttpSettings")?.getAsJsonObject("extra"))
        val rx = JsonParser.parseString("""{"rx":{"enabled":true}}""").asJsonObject
        assertThrows(IllegalArgumentException::class.java) {
            RayBoxOutboundConverter.convert(node().copy(transport = node().transport?.copy(extra = rx)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RayBoxOutboundConverter.convert(node().copy(tls = TlsConfig(enabled = true, caPath = "/ca")))
        }
    }

    @Test
    fun rejectsTcpOptionsInsteadOfDroppingThem() {
        val encrypted = node().copy(encryption = "example", transport = TransportConfig(type = "tcp", path = "/lost"))
        assertThrows(IllegalArgumentException::class.java) { RayBoxOutboundConverter.convert(encrypted) }
    }

    @Test
    fun rejectsLegacyVmessAlterId() {
        assertThrows(IllegalArgumentException::class.java) {
            RayBoxOutboundConverter.convert(node("vmess").copy(alterId = 8))
        }
    }

    @Test
    fun cloudflareRxAndOobRequireExplicitContextAndUseFallback() {
        val extra = JsonParser.parseString("""{"rx":{"enabled":true},"oob":{"enabled":true}}""").asJsonObject
        val input = node().copy(
            detour = null, tls = TlsConfig(enabled = true, serverName = "example.com", alpn = listOf("h2")),
            transport = TransportConfig(type = "xhttp", path = "/", mode = "packet-up", extra = extra)
        )
        assertThrows(IllegalArgumentException::class.java) { RayBoxOutboundConverter.convert(input) }
        val stream = RayBoxOutboundConverter.convert(input, cloudflare = true).xrayOutbound
            ?.getAsJsonObject("streamSettings")
        val primary = stream?.getAsJsonObject("xhttpSettings")?.getAsJsonObject("extra")
        assertFalse(primary?.has("rx") == true)
        val fallback = primary?.getAsJsonObject("oob")?.getAsJsonObject("fallbackSettings")
        assertEquals(true, fallback?.getAsJsonObject("xhttpSettings")?.getAsJsonObject("extra")
            ?.getAsJsonObject("rx")?.get("enabled")?.asBoolean)
        assertThrows(IllegalArgumentException::class.java) {
            RayBoxOutboundConverter.convert(input.copy(detour = "front"), cloudflare = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RayBoxOutboundConverter.convert(input.copy(tcpFastOpen = true), cloudflare = true)
        }
    }

    @Test
    fun rejectsRealityAlpnAndPacketEncoding() {
        val reality = TlsConfig(
            enabled = true, alpn = listOf("h2"),
            reality = com.kunk.singbox.model.RealityConfig(enabled = true, publicKey = "key")
        )
        assertThrows(IllegalArgumentException::class.java) {
            RayBoxOutboundConverter.convert(node().copy(tls = reality))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RayBoxOutboundConverter.convert(node().copy(packetEncoding = "xudp"))
        }
    }

    @Test
    fun importedCloudflareNodeReachesRuntimeAndRoundTrips() {
        val extra = JsonParser.parseString("""{"rx":{"enabled":true}}""").asJsonObject
        val input = node().copy(
            rayboxCloudflare = true, detour = null,
            tls = TlsConfig(enabled = true, serverName = "example.com", alpn = listOf("h2")),
            transport = TransportConfig(type = "xhttp", path = "/", mode = "packet-up", extra = extra)
        )
        val link = requireNotNull(NodeLinkExporter.export(input, gson))
        val imported = requireNotNull(com.kunk.singbox.utils.parser.NodeLinkParser(gson).parse(link))
        assertEquals(true, imported.rayboxCloudflare)
        val runtime = requireNotNull(OutboundFixer.buildForRuntimeWithDialConfigForTest(imported))
        val preconverted = RayBoxOutboundConverter.convert(imported)
        assertThrows(IllegalArgumentException::class.java) {
            OutboundFixer.buildForRuntimeWithDialConfigForTest(preconverted)
        }
        assertEquals("xray", runtime.type)
        assertFalse(gson.toJsonTree(runtime).asJsonObject.has("raybox_cloudflare"))
        assertEquals(true, runtime.xrayOutbound?.getAsJsonObject("streamSettings")
            ?.getAsJsonObject("xhttpSettings")?.getAsJsonObject("extra")
            ?.getAsJsonObject("rx")?.get("enabled")?.asBoolean)
    }

    @Test
    fun writesCrossPlatformFixture() {
        val input = Outbound(
            type = "vless", tag = "contract-xhttp", server = "example.invalid", serverPort = 443,
            uuid = "00000000-0000-0000-0000-000000000001", encryption = "none", detour = "contract-hop",
            tls = TlsConfig(
                enabled = true, serverName = "edge.example.invalid", alpn = listOf("h2"),
                utls = com.kunk.singbox.model.UtlsConfig(enabled = true, fingerprint = "chrome")
            ),
            transport = TransportConfig(
                type = "xhttp", path = "/contract", host = listOf("edge.example.invalid"), mode = "packet-up"
            )
        )
        val output = gson.toJsonTree(RayBoxOutboundConverter.convert(input)).asJsonObject
        assertEquals("xray", output.get("type").asString)
        assertEquals("contract-hop", output.get("detour").asString)
        val inner = output.getAsJsonObject("outbound")
        assertFalse(inner.has("tag"))
        val host = inner.getAsJsonObject("streamSettings").getAsJsonObject("xhttpSettings").get("host")
        assertEquals(true, host.isJsonPrimitive && host.asJsonPrimitive.isString)
        assertEquals("edge.example.invalid", host.asString)
        assertThrows(IllegalArgumentException::class.java) {
            RayBoxOutboundConverter.convert(input.copy(transport = input.transport?.copy(host = listOf("a", "b"))))
        }
        val directory = java.io.File("../docs/raybox-unification/fixtures")
        check(directory.isDirectory || directory.mkdirs())
        java.io.File(directory, "generated-xhttp.json").writeText(
            com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(output) + "\n"
        )
    }

    @Test
    fun standardRuntimePreservesProtocolOptions() {
        val certificate = listOf("-----BEGIN CERTIFICATE-----", "test", "-----END CERTIFICATE-----")
        listOf("vless", "vmess", "trojan").forEach { type ->
            listOf("", "xudp").forEach { encoding ->
                val input = node(type).copy(
                    packetEncoding = if (type == "trojan") null else encoding,
                    encryption = if (type == "vless") "mlkem768x25519plus.native.0rtt.test" else null,
                    tls = TlsConfig(enabled = true, ca = certificate, alpn = listOf("h3")),
                    transport = TransportConfig(
                        type = "xhttp", path = "/x?test=1", host = listOf("edge.example.com"),
                        mode = "packet-up", scMaxEachPostBytes = 4096
                    )
                )
                val runtime = requireNotNull(OutboundFixer.buildForRuntimeWithDialConfigForTest(input))
                val json = gson.toJsonTree(runtime).asJsonObject
                assertEquals(type, runtime.type)
                assertEquals(
                    input.packetEncoding?.takeIf { it.isNotBlank() }.orEmpty(),
                    runtime.packetEncoding.orEmpty()
                )
                assertEquals(input.encryption, runtime.encryption)
                assertEquals(certificate, runtime.tls?.ca)
                assertEquals(listOf("h3"), runtime.tls?.alpn)
                assertEquals("/x?test=1", runtime.transport?.path)
                val transport = json.getAsJsonObject("transport")
                assertEquals("edge.example.com", transport.get("host").asString)
                assertEquals(4096, transport.getAsJsonObject("extra").get("scMaxEachPostBytes").asInt)
                assertFalse(transport.has("sc_max_each_post_bytes"))
            }
        }
        val encryptedWs = node().copy(
            encryption = "mlkem768x25519plus.native.0rtt.test",
            packetEncoding = "xudp",
            tls = TlsConfig(enabled = true, reality = com.kunk.singbox.model.RealityConfig(enabled = true)),
            transport = TransportConfig(type = "ws", path = "/ws?ed=2048")
        )
        val ws = requireNotNull(OutboundFixer.buildForRuntimeWithDialConfigForTest(encryptedWs))
        assertEquals("vless", ws.type)
        assertEquals(2048L, ws.transport?.maxEarlyData)
        assertEquals("Sec-WebSocket-Protocol", ws.transport?.earlyDataHeaderName)
        assertEquals(null, ws.tls?.alpn)
    }

    @Test
    fun packetDownAndXmuxSurviveImportExportAndRuntime() {
        val extra = JsonParser.parseString(
            """{"packetDown":{"keepAlive":true,"keepAlivePeriod":"10-20","keepAliveLength":"24-64"},
                "xmux":{"maxConcurrency":5,"maxConnections":0,"hMaxRequestTimes":"300-600"}}"""
        ).asJsonObject
        val input = node().copy(transport = node().transport?.copy(extra = extra))
        val saved = gson.fromJson(gson.toJson(input), Outbound::class.java)
        val link = requireNotNull(NodeLinkExporter.export(saved, gson))
        val imported = requireNotNull(com.kunk.singbox.utils.parser.NodeLinkParser(gson).parse(link))
        assertEquals(extra, imported.transport?.extra)
        val runtime = requireNotNull(OutboundFixer.buildForRuntimeWithDialConfigForTest(imported))
        assertEquals(extra, runtime.transport?.extra)
        val converted = RayBoxOutboundConverter.convert(imported)
        assertEquals(extra, converted.xrayOutbound?.getAsJsonObject("streamSettings")
            ?.getAsJsonObject("xhttpSettings")?.getAsJsonObject("extra"))
    }

    @Test
    fun validatesExistingXrayShell() {
        val existing = RayBoxOutboundConverter.convert(node())
        assertSame(existing, RayBoxOutboundConverter.convert(existing))
        val bad = existing.xrayOutbound?.deepCopy()?.apply {
            getAsJsonObject("streamSettings").add("sockopt", JsonParser.parseString("{}"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RayBoxOutboundConverter.convert(existing.copy(xrayOutbound = bad))
        }
    }
}
