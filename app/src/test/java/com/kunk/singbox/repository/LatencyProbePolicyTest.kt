package com.kunk.singbox.repository

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TransportConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyProbePolicyTest {

    @Test
    fun shouldUseTcpFallback_returnsTrueForXhttpPacketUp() {
        val outbound = Outbound(
            type = "vless",
            tag = "xhttp-packet-up",
            transport = TransportConfig(type = "xhttp", mode = "packet-up")
        )

        assertTrue(LatencyProbePolicy.shouldUseTcpFallback(outbound))
    }

    @Test
    fun shouldUseTcpFallbackAfterProtocolFailure_returnsTrueForXhttpPacketUp() {
        val outbound = Outbound(
            type = "vless",
            tag = "xhttp-packet-up",
            transport = TransportConfig(type = "xhttp", mode = "packet-up")
        )

        assertTrue(LatencyProbePolicy.shouldUseTcpFallbackAfterProtocolFailure(outbound))
    }

    @Test
    fun shouldUseTcpFallback_returnsFalseForXhttpNonPacketUp() {
        val outbound = Outbound(
            type = "vless",
            tag = "xhttp-auto",
            transport = TransportConfig(type = "xhttp", mode = "auto")
        )

        assertFalse(LatencyProbePolicy.shouldUseTcpFallback(outbound))
    }

    @Test
    fun shouldUseTcpFallback_returnsFalseForNonXhttp() {
        val outbound = Outbound(
            type = "vless",
            tag = "ws-node",
            transport = TransportConfig(type = "ws", mode = "packet-up")
        )

        assertFalse(LatencyProbePolicy.shouldUseTcpFallback(outbound))
    }

    @Test
    fun shouldUseTcpFallbackAfterProtocolFailure_returnsFalseForAnyTls() {
        val outbound = Outbound(
            type = "anytls",
            tag = "anytls-open-port",
            server = "204.136.11.104",
            serverPort = 31424
        )

        assertFalse(LatencyProbePolicy.shouldUseTcpFallbackAfterProtocolFailure(outbound))
    }
}
