package com.kunk.singbox.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSerializationTest {
    private val gson = Gson()

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
            rcode = "NOERROR",
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
            type = "xhttp",
            path = "/x",
            host = listOf("h1.example.com", "h2.example.com")
        )

        val json = gson.toJson(transport)
        val decoded = gson.fromJson(json, TransportConfig::class.java)

        assertTrue(json.contains("\"host\":[\"h1.example.com\",\"h2.example.com\"]"))
        assertEquals(listOf("h1.example.com", "h2.example.com"), decoded.host)
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
