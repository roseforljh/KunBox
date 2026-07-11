package com.kunk.singbox.utils

import com.google.gson.GsonBuilder
import com.kunk.singbox.utils.parser.ClashYamlParser
import org.junit.Assert.*
import org.junit.Test

class VMessClashTest {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun testParseVMessWithTlsNoNetwork() {
        val yaml = """
proxies:
  - {name: JP-Node-1, server: test.example.com, port: 21585, type: vmess, uuid: d9362c09-496b-3b0d-932d-bdb971e586d5, alterId: 1, cipher: auto, tls: true, skip-cert-verify: false, udp: true}
        """.trimIndent()

        val parser = ClashYamlParser()
        val config = parser.parse(yaml)

        assertNotNull("Config should not be null", config)
        assertNotNull("Outbounds should not be null", config?.outbounds)

        val vmess = config?.outbounds?.find { it.type == "vmess" }
        assertNotNull("VMess outbound should exist", vmess)

        println("=== VMess Outbound ===")
        println(gson.toJson(vmess))

        assertEquals("vmess", vmess?.type)
        assertEquals("JP-Node-1", vmess?.tag)
        assertEquals("test.example.com", vmess?.server)
        assertEquals(21585, vmess?.serverPort)
        assertEquals("d9362c09-496b-3b0d-932d-bdb971e586d5", vmess?.uuid)
        assertEquals("auto", vmess?.security)

        // TLS config
        assertNotNull("TLS config should exist", vmess?.tls)
        assertEquals(true, vmess?.tls?.enabled)
        assertEquals("test.example.com", vmess?.tls?.serverName)
        assertEquals(false, vmess?.tls?.insecure)

        // Transport should be null for raw TCP
        assertNull("Transport should be null for raw TCP", vmess?.transport)

        // 缺省配置不得注入非官方 packet_encoding
        assertNull(vmess?.packetEncoding)
    }

    @Test
    fun testParseVMessWithoutSkipCertVerifyKeepsVerificationEnabled() {
        val yaml = """
            proxies:
              - name: Secure-VMess
                type: vmess
                server: long-random-looking-edge-123456.example.com
                port: 443
                uuid: d9362c09-496b-3b0d-932d-bdb971e586d5
                cipher: auto
                tls: true
        """.trimIndent()

        val vmess = ClashYamlParser().parse(yaml)?.outbounds?.find { it.type == "vmess" }

        assertNotNull(vmess)
        assertEquals(false, vmess?.tls?.insecure)
    }
}
