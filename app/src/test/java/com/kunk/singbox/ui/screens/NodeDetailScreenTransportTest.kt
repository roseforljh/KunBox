package com.kunk.singbox.ui.screens

import com.kunk.singbox.model.TransportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeDetailScreenTransportTest {

    @Test
    fun resolveWebSocketHostTextForEditorFallsBackToLegacyHostList() {
        val transport = TransportConfig(
            type = "ws",
            path = "/ws",
            host = listOf("cdn.example.com")
        )

        assertEquals("cdn.example.com", resolveWebSocketHostTextForEditor(transport))
    }

    @Test
    fun updateTransportTypeForEditorMigratesWebSocketHostHeaderToHttpUpgradeHost() {
        val transport = TransportConfig(
            type = "ws",
            path = "/up",
            headers = mapOf(
                "Host" to "cdn.example.com",
                "User-Agent" to "KunBox"
            ),
            maxEarlyData = 2048L,
            earlyDataHeaderName = "Sec-WebSocket-Protocol"
        )

        val updated = updateTransportTypeForEditor(transport, "httpupgrade")

        assertEquals("httpupgrade", updated.type)
        assertEquals(listOf("cdn.example.com"), updated.host)
        assertEquals(mapOf("User-Agent" to "KunBox"), updated.headers)
        assertNull(updated.maxEarlyData)
        assertNull(updated.earlyDataHeaderName)
    }

    @Test
    fun updatePathBasedTransportHostForEditorWritesHostListAndRemovesHostHeader() {
        val transport = TransportConfig(
            type = "httpupgrade",
            headers = mapOf(
                "host" to "old.example.com",
                "User-Agent" to "KunBox"
            )
        )

        val updated = updatePathBasedTransportHostForEditor(transport, "cdn1.example.com, cdn2.example.com")

        assertEquals(listOf("cdn1.example.com", "cdn2.example.com"), updated.host)
        assertEquals(mapOf("User-Agent" to "KunBox"), updated.headers)
    }

    @Test
    fun updateTransportTypeForEditorMigratesHttpUpgradeHostToWebSocketHostHeader() {
        val transport = TransportConfig(
            type = "httpupgrade",
            path = "/ws",
            host = listOf("cdn.example.com"),
            headers = mapOf("User-Agent" to "KunBox")
        )

        val updated = updateTransportTypeForEditor(transport, "ws")

        assertEquals("ws", updated.type)
        assertNull(updated.host)
        assertEquals(
            mapOf(
                "User-Agent" to "KunBox",
                "Host" to "cdn.example.com"
            ),
            updated.headers
        )
    }

    @Test
    fun updateWebSocketTransportHostForEditorRemovesLegacyHostList() {
        val transport = TransportConfig(
            type = "ws",
            host = listOf("legacy.example.com"),
            headers = mapOf(
                "host" to "old.example.com",
                "User-Agent" to "KunBox"
            )
        )

        val updated = updateWebSocketTransportHostForEditor(transport, "cdn.example.com")

        assertNull(updated.host)
        assertEquals(
            mapOf(
                "User-Agent" to "KunBox",
                "Host" to "cdn.example.com"
            ),
            updated.headers
        )
    }
}
