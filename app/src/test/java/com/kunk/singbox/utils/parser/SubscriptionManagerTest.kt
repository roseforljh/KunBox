package com.kunk.singbox.utils.parser

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionManagerTest {

    @Test
    fun deduplicateOutboundsKeepsSameEndpointWithDifferentTransport() {
        val first = Outbound(
            type = "vless",
            tag = "JP 01",
            server = "127.0.0.1",
            serverPort = 443,
            uuid = "7bcc4323-5318-4a7e-b806-be38a09e6d4e",
            tls = TlsConfig(enabled = true, serverName = "jp.example.com"),
            transport = TransportConfig(type = "ws", path = "/liangxin/jp1", host = listOf("jp.example.com"))
        )
        val second = first.copy(
            tag = "US 01",
            tls = first.tls?.copy(serverName = "us.example.com"),
            transport = first.transport?.copy(path = "/liangxin/us1", host = listOf("us.example.com"))
        )

        val result = SubscriptionManager.deduplicateOutbounds(listOf(first, second))

        assertEquals(listOf(first, second), result)
    }

    @Test
    fun deduplicateOutboundsDropsExactDuplicate() {
        val node = Outbound(
            type = "hysteria2",
            tag = "HK 01",
            server = "hy.example.com",
            serverPort = 60000,
            password = "password"
        )

        val result = SubscriptionManager.deduplicateOutbounds(listOf(node, node))

        assertEquals(listOf(node), result)
    }
}
