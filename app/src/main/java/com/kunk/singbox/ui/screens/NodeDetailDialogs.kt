package com.kunk.singbox.ui.screens

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.WireGuardPeer
import com.kunk.singbox.model.allHeaderValues
import com.kunk.singbox.model.asHttpHeaderMap

internal fun createEmptyOutbound(protocol: String): Outbound {
    val defaultPort = when (protocol) {
        "shadowsocks" -> 8388
        "ssh" -> 22
        "socks" -> 1080
        "http" -> 8080
        "wireguard" -> 51820
        else -> 443
    }

    val needsTls = protocol in listOf("vless", "trojan", "hysteria2", "hysteria", "tuic", "naive", "anytls")
    val isWireGuard = protocol == "wireguard"

    return Outbound(
        type = protocol,
        tag = "New-${protocol.uppercase()}",
        server = if (isWireGuard) null else "",
        serverPort = if (isWireGuard) null else defaultPort,
        network = if (protocol == "naive") listOf("h2") else null,
        quic = if (protocol == "naive") false else null,
        tls = if (needsTls) TlsConfig(enabled = true) else null,
        mtu = if (isWireGuard) 1420 else null,
        peers = createDefaultWireGuardPeers(isWireGuard, defaultPort)
    )
}

/** 手填 WireGuard 默认全隧道 + keepalive，避免 allowed_ips 为空导致无路由。 */
private fun createDefaultWireGuardPeers(isWireGuard: Boolean, defaultPort: Int): List<WireGuardPeer>? {
    if (!isWireGuard) return null
    return listOf(
        WireGuardPeer(
            serverPort = defaultPort,
            allowedIps = listOf("0.0.0.0/0", "::/0"),
            persistentKeepaliveInterval = 25
        )
    )
}

internal fun formatHeaderLines(headers: Map<String, String>?): String {
    return headers
        ?.allHeaderValues()
        ?.entries
        ?.sortedBy { it.key.lowercase() }
        ?.flatMap { (key, values) -> values.map { value -> "$key: $value" } }
        ?.joinToString("\n")
        .orEmpty()
}

internal fun parseHeaderLines(text: String): Map<String, String>? {
    val parsed = linkedMapOf<String, MutableList<String>>()
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { line ->
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) return@forEach

            val key = line.substring(0, separatorIndex).trim()
            val value = line.substring(separatorIndex + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                parsed.getOrPut(key) { mutableListOf() }.add(value)
            }
        }

    return parsed
        .mapValues { (_, values) -> values.toList() }
        .takeIf { it.isNotEmpty() }
        ?.asHttpHeaderMap()
}
