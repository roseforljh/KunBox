package com.kunk.singbox.model

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class ModelSerializationTest {
    private val gson = Gson()

    @Test
    fun nullableJsonPrimitiveFieldsAcceptHistoricalExplicitNulls() {
        val config = gson.fromJson(
            """
                {
                  "dns":{"rules":[{"action":"predefined","rcode":null}]},
                  "outbounds":[{"type":"socks","tag":"proxy","version":null}]
                }
            """.trimIndent(),
            SingBoxConfig::class.java
        )

        assertEquals(null, config.dns?.rules?.single()?.rcode)
        assertEquals(null, config.outbounds?.single()?.version)
    }

    @Test
    fun nullableJsonPrimitiveFieldsRejectContainers() {
        assertThrows(JsonParseException::class.java) {
            gson.fromJson("{\"type\":\"socks\",\"version\":{}}", Outbound::class.java)
        }
    }

    @Test
    fun testNodeUiSerialization() {
        val node = NodeUi(
            id = "test-id",
            name = "Test Node",
            protocol = "vmess",
            group = "Default",
            latencyMs = 120,
            sourceProfileId = "profile-1"
        )

        val json = gson.toJson(node)
        val decoded = gson.fromJson(json, NodeUi::class.java)

        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.latencyMs, decoded.latencyMs)
    }

    @Test
    fun testSingBoxConfigSerialization() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(type = "direct", tag = "direct"),
                Outbound(type = "vmess", tag = "proxy", server = "1.1.1.1", serverPort = 443)
            )
        )

        val json = gson.toJson(config)
        val decoded = gson.fromJson(json, SingBoxConfig::class.java)

        assertNotNull(decoded.outbounds)
        assertEquals(2, decoded.outbounds?.size)
        assertEquals("proxy", decoded.outbounds?.get(1)?.tag)
    }

    @Test
    fun dnsPredefinedRuleSerializesRcode() {
        val rule = DnsRule(
            action = "predefined",
            rcode = JsonPrimitive("NOERROR"),
            domain = listOf("www.googleadservices.com")
        )

        val json = gson.toJson(rule)

        assertTrue(json.contains("\"action\":\"predefined\""))
        assertTrue(json.contains("\"rcode\":\"NOERROR\""))
        assertTrue(json.contains("\"domain\":[\"www.googleadservices.com\"]"))
    }

    @Test
    fun transportHostSerializesSingleItemAsString() {
        val transport = TransportConfig(
            type = "httpupgrade",
            path = "/up",
            host = listOf("cdn.example.com")
        )

        val json = gson.toJson(transport)
        val decoded = gson.fromJson(json, TransportConfig::class.java)

        assertTrue(json.contains("\"host\":\"cdn.example.com\""))
        assertEquals(listOf("cdn.example.com"), decoded.host)
    }

    @Test
    fun transportHostSerializesMultipleItemsAsArray() {
        val transport = TransportConfig(
            type = "http",
            path = "/x",
            host = listOf("h1.example.com", "h2.example.com")
        )

        val json = gson.toJson(transport)
        val decoded = gson.fromJson(json, TransportConfig::class.java)

        assertTrue(json.contains("\"host\":[\"h1.example.com\",\"h2.example.com\"]"))
        assertEquals(listOf("h1.example.com", "h2.example.com"), decoded.host)
    }

    @Test
    fun hysteriaObfsSerializesAsStringAndHysteria2AsObject() {
        val hysteria = Outbound(
            type = "hysteria",
            obfs = ObfsConfig(type = "secret-obfs", stringValue = true)
        )
        val hysteria2 = Outbound(
            type = "hysteria2",
            obfs = ObfsConfig(type = "salamander", password = "secret")
        )

        val hysteriaJson = JsonParser.parseString(gson.toJson(hysteria)).asJsonObject
        val hysteria2Json = JsonParser.parseString(gson.toJson(hysteria2)).asJsonObject

        assertEquals("secret-obfs", hysteriaJson.get("obfs").asString)
        assertEquals("salamander", hysteria2Json.getAsJsonObject("obfs").get("type").asString)
        assertEquals("secret", hysteria2Json.getAsJsonObject("obfs").get("password").asString)
    }

    @Test
    fun protocolVersionKeepsSocksStringAndShadowTlsNumber() {
        val socksJson = gson.toJson(Outbound(type = "socks", version = JsonPrimitive("4a")))
        val shadowTlsJson = gson.toJson(Outbound(type = "shadowtls", version = JsonPrimitive(3)))

        assertTrue(socksJson.contains("\"version\":\"4a\""))
        assertTrue(shadowTlsJson.contains("\"version\":3"))
        assertEquals("4a", gson.fromJson(socksJson, Outbound::class.java).version?.asString)
        assertEquals(3, gson.fromJson(shadowTlsJson, Outbound::class.java).version?.asInt)
    }

    @Test
    fun outboundTlsUsesOfficialTrustAndClientCertificateFieldNames() {
        val tls = TlsConfig(
            ca = listOf("trusted-ca", "backup-ca"),
            caPath = "/trusted-ca.pem",
            certificate = listOf("client-cert"),
            certificatePath = "/client-cert.pem",
            key = listOf("client-key"),
            keyPath = "/client-key.pem"
        )

        val json = gson.toJson(tls)
        val parsed = gson.fromJson(json, TlsConfig::class.java)

        assertTrue(json.contains("\"certificate\":[\"trusted-ca\",\"backup-ca\"]"))
        assertTrue(json.contains("\"certificate_path\":\"/trusted-ca.pem\""))
        assertTrue(json.contains("\"client_certificate\":\"client-cert\""))
        assertTrue(json.contains("\"client_certificate_path\":\"/client-cert.pem\""))
        assertTrue(json.contains("\"client_key\":\"client-key\""))
        assertTrue(json.contains("\"client_key_path\":\"/client-key.pem\""))
        assertFalse(json.contains("\"ca\":"))
        assertFalse(json.contains("\"key\":"))
        assertEquals(listOf("trusted-ca", "backup-ca"), parsed.ca)
        assertEquals(listOf("client-cert"), parsed.certificate)
        assertEquals(listOf("client-key"), parsed.key)
    }

    @Test
    fun singBoxConfigPreservesEndpointAndServiceSections() {
        val json = """
            {
              "${'$'}schema":"https://sing-box.sagernet.org/schema.json",
              "ntp":{"enabled":true},
              "certificate":{"store":"system"},
              "endpoints":[{
                "type":"wireguard",
                "tag":"wg-endpoint",
                "address":["10.0.0.2/32"],
                "private_key":"private",
                "peers":[{"address":"wg.example.com","port":51820,"public_key":"public"}]
              }],
              "services":[{"type":"resolved","tag":"resolved"}]
            }
        """.trimIndent()

        val config = gson.fromJson(json, SingBoxConfig::class.java)
        val serialized = gson.toJson(config)

        assertEquals("wg-endpoint", config.endpoints?.single()?.tag)
        assertEquals("wg.example.com", config.endpoints?.single()?.peers?.single()?.server)
        assertTrue(serialized.contains("\"endpoints\""))
        assertTrue(serialized.contains("\"services\""))
        assertTrue(serialized.contains("\"ntp\""))
        assertTrue(serialized.contains("\"certificate\""))
    }

    @Test
    fun officialListableFieldsAcceptSingleValuesAndSerializeValidJson() {
        val json = """
            {
              "dns":{
                "servers":[{
                  "type":"https",
                  "tag":"dns-headers",
                  "server":"dns.example.com",
                  "headers":{"X-Multi":["one","two"]}
                }]
              },
              "outbounds":[{
                "type":"ssh",
                "tag":"single-listable",
                "server":"ssh.example.com",
                "server_port":22,
                "network":"tcp",
                "private_key":"private-key",
                "host_key":"ssh-ed25519 AAAA",
                "tls":{"enabled":true,"alpn":"h2"}
              },{
                "type":"hysteria2",
                "tag":"single-server-port",
                "server":"hy2.example.com",
                "server_ports":"20000-21000",
                "password":"secret"
              }]
            }
        """.trimIndent()

        val config = gson.fromJson(json, SingBoxConfig::class.java)
        val ssh = config.outbounds.orEmpty().first()
        val hysteria2 = config.outbounds.orEmpty().last()
        val serialized = JsonParser.parseString(gson.toJson(config)).asJsonObject
        val serializedOutbounds = serialized.getAsJsonArray("outbounds")
        val dnsHeaders = serialized
            .getAsJsonObject("dns")
            .getAsJsonArray("servers")[0]
            .asJsonObject
            .getAsJsonObject("headers")

        assertEquals(listOf("tcp"), ssh.network)
        assertEquals(listOf("private-key"), ssh.privateKey)
        assertEquals(listOf("ssh-ed25519 AAAA"), ssh.hostKey)
        assertEquals(listOf("h2"), ssh.tls?.alpn)
        assertEquals(listOf("20000-21000"), hysteria2.serverPorts)
        assertTrue(serializedOutbounds[0].asJsonObject.get("network").isJsonPrimitive)
        assertTrue(serializedOutbounds[0].asJsonObject.get("private_key").isJsonPrimitive)
        assertTrue(serializedOutbounds[0].asJsonObject.getAsJsonObject("tls").get("alpn").isJsonPrimitive)
        assertTrue(serializedOutbounds[1].asJsonObject.get("server_ports").isJsonPrimitive)
        assertTrue(dnsHeaders.get("X-Multi").isJsonArray)
    }

    @Test
    @Suppress("LongMethod")
    fun officialOutboundFixturePreservesListableDialerTlsAndProtocolFields() {
        val json = """
            {
              "outbounds":[{
                "type":"vmess",
                "tag":"vmess-official",
                "server":"vmess.example.com",
                "server_port":443,
                "uuid":"00000000-0000-0000-0000-000000000001",
                "security":"auto",
                "global_padding":true,
                "authenticated_length":true,
                "network":["tcp","udp"],
                "bind_interface":"wlan0",
                "inet4_bind_address":"192.0.2.10",
                "inet6_bind_address":"2001:db8::10",
                "bind_address_no_port":true,
                "protect_path":"/tmp/protect.sock",
                "routing_mark":"0xff",
                "reuse_addr":true,
                "netns":"test-ns",
                "tcp_multi_path":true,
                "disable_tcp_keep_alive":true,
                "udp_fragment":false,
                "network_strategy":"hybrid",
                "network_type":["wifi","cellular"],
                "fallback_network_type":"ethernet",
                "fallback_delay":"250ms",
                "domain_strategy":"prefer_ipv6",
                "tls":{
                  "enabled":true,
                  "alpn":["h2","http/1.1"],
                  "cipher_suites":["TLS_AES_128_GCM_SHA256"],
                  "curve_preferences":"X25519",
                  "fragment":true,
                  "fragment_fallback_delay":"300ms",
                  "record_fragment":true,
                  "kernel_tx":true,
                  "kernel_rx":true
                }
              },{
                "type":"urltest",
                "tag":"urltest-official",
                "outbounds":["vmess-official"],
                "idle_timeout":"30m"
              },{
                "type":"hysteria",
                "tag":"hysteria-official",
                "server":"hy.example.com",
                "server_ports":["20000","20001-20100"],
                "auth":"AQID",
                "up":"100 Mbps",
                "down":200,
                "recv_window_conn":4294967296,
                "recv_window":18446744073709551615
              },{
                "type":"naive",
                "tag":"naive-official",
                "server":"naive.example.com",
                "server_port":443,
                "username":"user",
                "password":"pass",
                "stream_receive_window":"8 MiB",
                "quic_session_receive_window":16777216
              },{
                "type":"hysteria2",
                "tag":"hysteria2-official",
                "server":"hy2.example.com",
                "server_port":443,
                "password":"secret",
                "brutal_debug":true
              },{
                "type":"ssh",
                "tag":"ssh-official",
                "server":"ssh.example.com",
                "server_port":22,
                "private_key":["key-one","key-two"],
                "host_key":["host-one","host-two"],
                "host_key_algorithms":["ssh-ed25519","rsa-sha2-512"]
              }]
            }
        """.trimIndent()

        val config = gson.fromJson(json, SingBoxConfig::class.java)
        val byTag = config.outbounds.orEmpty().associateBy(Outbound::tag)
        val vmess = byTag.getValue("vmess-official")
        val hysteria = byTag.getValue("hysteria-official")
        val naive = byTag.getValue("naive-official")
        val urltest = byTag.getValue("urltest-official")
        val hysteria2 = byTag.getValue("hysteria2-official")
        val ssh = byTag.getValue("ssh-official")

        assertEquals(listOf("tcp", "udp"), vmess.network)
        assertEquals(true, vmess.globalPadding)
        assertEquals(true, vmess.authenticatedLength)
        assertEquals("wlan0", vmess.bindInterface)
        assertEquals(listOf("wifi", "cellular"), vmess.networkType)
        assertEquals(listOf("ethernet"), vmess.fallbackNetworkType)
        assertEquals(listOf("h2", "http/1.1"), vmess.tls?.alpn)
        assertEquals(listOf("TLS_AES_128_GCM_SHA256"), vmess.tls?.cipherSuites)
        assertEquals(listOf("X25519"), vmess.tls?.curvePreferences)
        assertEquals(listOf("20000", "20001-20100"), hysteria.serverPorts)
        assertEquals("AQID", hysteria.auth)
        assertEquals(BigInteger("4294967296"), hysteria.recvWindowConn)
        assertEquals(BigInteger("18446744073709551615"), hysteria.recvWindow)
        assertEquals("8 MiB", naive.streamReceiveWindow?.asString)
        assertEquals(16_777_216L, naive.quicSessionReceiveWindow?.asLong)
        assertEquals("30m", urltest.idleTimeout)
        assertEquals(true, hysteria2.brutalDebug)
        assertEquals(listOf("key-one", "key-two"), ssh.privateKey)

        val serialized = gson.toJson(config)
        val roundTrip = gson.fromJson(serialized, SingBoxConfig::class.java)
        val roundTripByTag = roundTrip.outbounds.orEmpty().associateBy(Outbound::tag)

        assertEquals(vmess, roundTripByTag["vmess-official"])
        assertEquals(hysteria, roundTripByTag["hysteria-official"])
        assertEquals(naive, roundTripByTag["naive-official"])
        assertEquals(ssh, roundTripByTag["ssh-official"])
    }

    @Test
    fun officialDomainResolverAcceptsStringAndObjectForms() {
        val stringForm = gson.fromJson("\"dns-bootstrap\"", DomainResolveConfig::class.java)
        val objectForm = gson.fromJson(
            """{"server":"dns-remote","strategy":"prefer_ipv6","disable_cache":true}""",
            DomainResolveConfig::class.java
        )

        assertEquals("dns-bootstrap", stringForm.server)
        assertEquals("\"dns-bootstrap\"", gson.toJson(stringForm))
        assertEquals("dns-remote", objectForm.server)
        assertEquals("prefer_ipv6", objectForm.strategy)
        assertEquals(true, objectForm.disableCache)
        assertTrue(gson.toJson(objectForm).startsWith("{"))
    }

    @Test
    fun officialRewriteTtlUsesFullUint32Range() {
        val maxValue = 4_294_967_295L
        val dnsRule = gson.fromJson("""{"rewrite_ttl":$maxValue}""", DnsRule::class.java)
        val domainResolver = gson.fromJson(
            """{"server":"dns-remote","rewrite_ttl":$maxValue}""",
            DomainResolveConfig::class.java
        )

        assertEquals(maxValue, dnsRule.rewriteTtl)
        assertEquals(maxValue, domainResolver.rewriteTtl)
        assertEquals(maxValue, gson.fromJson(gson.toJson(dnsRule), DnsRule::class.java).rewriteTtl)
        assertEquals(
            maxValue,
            gson.fromJson(gson.toJson(domainResolver), DomainResolveConfig::class.java).rewriteTtl
        )

        listOf("-1", "4294967296", "1.5").forEach { invalid ->
            assertThrows(JsonParseException::class.java) {
                gson.fromJson("""{"rewrite_ttl":$invalid}""", DnsRule::class.java)
            }
            assertThrows(JsonParseException::class.java) {
                gson.fromJson("""{"rewrite_ttl":$invalid}""", DomainResolveConfig::class.java)
            }
        }
        assertThrows(JsonParseException::class.java) {
            gson.toJson(DnsRule(rewriteTtl = maxValue + 1))
        }
        assertThrows(JsonParseException::class.java) {
            gson.toJson(DomainResolveConfig(rewriteTtl = maxValue + 1))
        }
    }

    @Test
    @Suppress("LongMethod")
    fun officialDnsSchemaRoundTripsWithoutLosingMatcherSemantics() {
        val json = """
            {
              "dns": {
                "reverse_mapping": true,
                "cache_capacity": 4294967295,
                "client_subnet": "192.0.2.0/24",
                "servers": [{
                  "tag": "hosts",
                  "type": "hosts",
                  "path": ["/etc/hosts", "/data/local/hosts"],
                  "predefined": {
                    "example.com": ["192.0.2.1", "2001:db8::1"],
                    "single.example": "192.0.2.2"
                  }
                }, {
                  "tag": "remote",
                  "type": "https",
                  "server": "dns.example.com",
                  "method": "POST",
                  "network_type": "wifi",
                  "fallback_network_type": ["cellular"],
                  "bind_interface": "wlan0",
                  "inet4_bind_address": "192.0.2.10",
                  "protect_path": "/proc/self/fd/42"
                }],
                "rules": [{
                  "type": "logical",
                  "mode": "and",
                  "invert": true,
                  "rules": [{"domain_suffix": "example.com"}, {"query_type": [1, "AAAA"]}],
                  "action": "route",
                  "server": "remote"
                }, {
                  "ip_version": 4,
                  "network": "udp",
                  "auth_user": "user",
                  "protocol": ["dns"],
                  "client": "chromium",
                  "source_geoip": "cn",
                  "geoip": "us",
                  "ip_cidr": "192.0.2.0/24",
                  "source_ip_cidr": ["198.51.100.0/24"],
                  "source_port": 65535,
                  "port": [53, 853],
                  "user_id": 2147483647,
                  "interface_address": {"wlan0": "192.0.2.0/24"},
                  "network_interface_address": {"wifi": ["192.0.2.0/24"]},
                  "rule_set_ip_cidr_match_source": true,
                  "rule_set_ip_cidr_accept_empty": true,
                  "action": "predefined",
                  "rcode": 3,
                  "answer": "example.com. 60 IN A 192.0.2.1",
                  "ns": ["example.com. 60 IN NS ns.example.com."],
                  "extra": "ns.example.com. 60 IN A 192.0.2.53"
                }]
              }
            }
        """.trimIndent()

        val config = gson.fromJson(json, SingBoxConfig::class.java)
        val dns = config.dns
        val hosts = dns?.servers?.first()
        val logical = dns?.rules?.first()
        val matcher = dns?.rules?.get(1)
        val serialized = JsonParser.parseString(gson.toJson(config)).asJsonObject
            .getAsJsonObject("dns")

        assertEquals(4_294_967_295L, dns?.cacheCapacity)
        assertEquals(listOf("/etc/hosts", "/data/local/hosts"), hosts?.paths)
        assertEquals(listOf("192.0.2.1", "2001:db8::1"), hosts?.predefined?.get("example.com"))
        assertEquals("logical", logical?.type)
        assertEquals("and", logical?.mode)
        assertEquals(true, logical?.invert)
        assertEquals(listOf("1", "AAAA"), logical?.rules?.get(1)?.queryType)
        assertEquals(listOf(65_535), matcher?.sourcePort)
        assertEquals(listOf(53, 853), matcher?.port)
        assertEquals(listOf(Int.MAX_VALUE), matcher?.userId)
        assertEquals(3, matcher?.rcode?.asInt)
        assertTrue(serialized.getAsJsonArray("servers")[0].asJsonObject.get("path").isJsonArray)
        val serializedQueryTypes = serialized.getAsJsonArray("rules")[0].asJsonObject
            .getAsJsonArray("rules")[1].asJsonObject
            .get("query_type")
            .asJsonArray
        assertTrue(serializedQueryTypes[0].asJsonPrimitive.isNumber)
        assertTrue(serialized.getAsJsonArray("rules")[1].asJsonObject.get("rcode").asJsonPrimitive.isNumber)

        assertThrows(JsonParseException::class.java) {
            gson.fromJson("""{"cache_capacity":4294967296}""", DnsConfig::class.java)
        }
        assertThrows(JsonParseException::class.java) {
            gson.fromJson("""{"source_port":65536}""", DnsRule::class.java)
        }
    }

    @Test
    @Suppress("LongMethod")
    fun officialTransportMultiplexAndHeaderListablesRoundTrip() {
        val json = """
            {
              "outbounds":[{
                "type":"vmess",
                "tag":"transport",
                "server":"vmess.example.com",
                "server_port":443,
                "uuid":"00000000-0000-0000-0000-000000000001",
                "security":"auto",
                "transport":{
                  "type":"http",
                  "host":["one.example.com","two.example.com"],
                  "path":"/",
                  "method":"GET",
                  "headers":{"X-Multi":["one","two"],"Host":"one.example.com"},
                  "idle_timeout":"15s",
                  "ping_timeout":"5s",
                  "permit_without_stream":true
                },
                "multiplex":{
                  "enabled":true,
                  "brutal":{"enabled":true,"up_mbps":100,"down_mbps":200}
                }
              },{
                "type":"http",
                "tag":"http-headers",
                "server":"proxy.example.com",
                "server_port":8080,
                "headers":{"X-Multi":["one","two"]}
              },{
                "type":"naive",
                "tag":"naive-headers",
                "server":"naive.example.com",
                "server_port":443,
                "extra_headers":{"X-Multi":["one","two"]}
              }]
            }
        """.trimIndent()

        val config = gson.fromJson(json, SingBoxConfig::class.java)
        val transport = config.outbounds.orEmpty().first().transport
        val multiplex = config.outbounds.orEmpty().first().multiplex
        val serializedConfig = JsonParser.parseString(gson.toJson(config)).asJsonObject
        val serialized = serializedConfig.getAsJsonArray("outbounds")
        val transportHeaders = serialized[0].asJsonObject
            .getAsJsonObject("transport")
            .getAsJsonObject("headers")
        val httpHeaders = serialized[1].asJsonObject.getAsJsonObject("headers")
        val naiveHeaders = serialized[2].asJsonObject.getAsJsonObject("extra_headers")

        assertEquals("GET", transport?.method)
        assertEquals("15s", transport?.idleTimeout)
        assertEquals("5s", transport?.pingTimeout)
        assertEquals(true, transport?.permitWithoutStream)
        assertEquals(
            4_294_967_295L,
            gson.fromJson("{\"max_early_data\":4294967295}", TransportConfig::class.java).maxEarlyData
        )
        listOf("-1", "4294967296", "1.5").forEach { invalid ->
            assertThrows(JsonParseException::class.java) {
                gson.fromJson("{\"max_early_data\":$invalid}", TransportConfig::class.java)
            }
        }
        assertEquals(100, multiplex?.brutal?.upMbps)
        assertTrue(transportHeaders.get("X-Multi").isJsonArray)
        assertTrue(httpHeaders.get("X-Multi").isJsonArray)
        assertTrue(naiveHeaders.get("X-Multi").isJsonArray)
    }

    @Test
    fun appSettingsSerializesLiquidGlassThemeStyle() {
        val settings = AppSettings(appThemeStyle = AppThemeStyle.LIQUID_GLASS)

        val json = gson.toJson(settings)
        val decoded = gson.fromJson(json, AppSettings::class.java)

        assertTrue(json.contains("\"appThemeStyle\":\"LIQUID_GLASS\""))
        assertEquals(AppThemeStyle.LIQUID_GLASS, decoded.appThemeStyle)
    }
}
