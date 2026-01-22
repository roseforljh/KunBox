package com.kunk.singbox.model

import com.google.gson.Gson
import org.junit.Assert.*
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
}
