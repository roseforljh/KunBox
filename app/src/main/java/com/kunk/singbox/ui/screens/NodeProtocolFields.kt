package com.kunk.singbox.ui.screens

import com.google.gson.JsonPrimitive
import com.kunk.singbox.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.UdpOverTcpConfig
import com.kunk.singbox.model.WireGuardPeer
import com.kunk.singbox.ui.components.EditableMultilineTextItem
import com.kunk.singbox.ui.components.EditableSelectionItem
import com.kunk.singbox.ui.components.EditableTextItem
import com.kunk.singbox.ui.components.SettingSwitchItem

@Composable
internal fun NodeProtocolFields(
    type: String,
    outbound: Outbound,
    editingOutboundState: MutableState<Outbound?>
) {
    var editingOutbound by editingOutboundState
    // --- Protocol Specific Fields ---

    // 1. Shadowsocks
    if (type == "shadowsocks") {
        EditableSelectionItem(
            title = stringResource(R.string.node_detail_encryption),
            value = outbound.method ?: "aes-256-gcm",
            options = listOf(
                "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
                "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
                "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
                "aes-128-ctr", "aes-192-ctr", "aes-256-ctr",
                "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
                "rc4-md5", "chacha20-ietf", "xchacha20", "none"
            ),
            icon = Icons.Rounded.Lock,
            onValueChange = { editingOutbound = outbound.copy(method = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_password),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_plugin),
            value = outbound.plugin ?: "",
            icon = Icons.Rounded.Settings,
            onValueChange = { editingOutbound = outbound.copy(plugin = if (it.isEmpty()) null else it) }
        )
        if (!outbound.plugin.isNullOrBlank()) {
            EditableTextItem(
                title = stringResource(R.string.node_detail_plugin_options),
                value = outbound.pluginOpts ?: "",
                icon = Icons.Rounded.Settings,
                onValueChange = { editingOutbound = outbound.copy(pluginOpts = if (it.isEmpty()) null else it) }
            )
        }
        // UDP over TCP
        val uot = outbound.udpOverTcp ?: UdpOverTcpConfig(enabled = false)
        SettingSwitchItem(
            title = stringResource(R.string.node_detail_udp_over_tcp),
            checked = uot.enabled == true,
            icon = Icons.Rounded.SwapHoriz,
            onCheckedChange = { editingOutbound = outbound.copy(udpOverTcp = uot.copy(enabled = it)) }
        )
    }

    // 2. VMess / VLESS
    if (type == "vmess" || type == "vless") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_uuid),
            value = outbound.uuid ?: "",
            icon = Icons.Rounded.Person,
            onValueChange = { editingOutbound = outbound.copy(uuid = it) }
        )

        if (type == "vmess") {
            EditableSelectionItem(
                title = stringResource(R.string.node_detail_encryption),
                value = outbound.security ?: "auto",
                options = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"),
                icon = Icons.Rounded.Security,
                onValueChange = { editingOutbound = outbound.copy(security = it) }
            )
        }

        if (type == "vless") {
            EditableSelectionItem(
                title = stringResource(R.string.node_detail_flow),
                value = outbound.flow ?: "",
                options = listOf("", "xtls-rprx-vision"),
                icon = Icons.Rounded.Waves,
                onValueChange = { editingOutbound = outbound.copy(flow = it) }
            )
        }

        EditableSelectionItem(
            title = stringResource(R.string.node_detail_packet_encoding),
            value = outbound.packetEncoding ?: "",
            options = listOf("", "xudp", "packet"),
            icon = Icons.Rounded.Layers,
            onValueChange = { editingOutbound = outbound.copy(packetEncoding = if (it.isEmpty()) null else it) }
        )
    }

    // 3. Trojan
    if (type == "trojan") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_password),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = it) }
        )
    }

    // 4. Hysteria 2
    if (type == "hysteria2") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_password),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_ports_jumping),
            value = outbound.serverPorts?.firstOrNull() ?: "",
            icon = Icons.Rounded.Numbers,
            onValueChange = {
                editingOutbound = outbound.copy(
                    serverPorts = if (it.isEmpty()) null else listOf(it)
                )
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_obfs_type),
            value = outbound.obfs?.type ?: "",
            icon = Icons.Rounded.Lock,
            onValueChange = {
                val newObfs = if (it.isEmpty()) null else (outbound.obfs?.copy(type = it) ?: ObfsConfig(type = it))
                editingOutbound = outbound.copy(obfs = newObfs)
            }
        )
        if (outbound.obfs?.type == "salamander") {
            EditableTextItem(
                title = stringResource(R.string.node_detail_obfs_password),
                value = outbound.obfs.password ?: "",
                icon = Icons.Rounded.Key,
                onValueChange = { editingOutbound = outbound.copy(obfs = outbound.obfs.copy(password = it)) }
            )
        }
        EditableTextItem(
            title = stringResource(R.string.node_detail_upload_speed),
            value = outbound.upMbps?.toString() ?: "",
            icon = Icons.Rounded.Speed,
            onValueChange = { editingOutbound = outbound.copy(upMbps = it.toIntOrNull()) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_download_speed),
            value = outbound.downMbps?.toString() ?: "",
            icon = Icons.Rounded.Speed,
            onValueChange = { editingOutbound = outbound.copy(downMbps = it.toIntOrNull()) }
        )
    }

    // 5. TUIC
    if (type == "tuic") {
        EditableTextItem(
            title = "UUID",
            value = outbound.uuid ?: "",
            icon = Icons.Rounded.Person,
            onValueChange = { editingOutbound = outbound.copy(uuid = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_password),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = it) }
        )
        EditableSelectionItem(
            title = stringResource(R.string.node_detail_congestion_control),
            value = outbound.congestionControl ?: "bbr",
            options = listOf("bbr", "cubic", "new_reno"),
            icon = Icons.Rounded.Speed,
            onValueChange = { editingOutbound = outbound.copy(congestionControl = it) }
        )
        EditableSelectionItem(
            title = stringResource(R.string.node_detail_udp_relay_mode),
            value = outbound.udpRelayMode ?: "native",
            options = listOf("native", "quic"),
            icon = Icons.Rounded.SwapHoriz,
            onValueChange = { editingOutbound = outbound.copy(udpRelayMode = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_heartbeat),
            value = outbound.heartbeat ?: "3s",
            icon = Icons.Rounded.Bolt,
            onValueChange = { editingOutbound = outbound.copy(heartbeat = it) }
        )
        SettingSwitchItem(
            title = stringResource(R.string.node_detail_zero_rtt),
            checked = outbound.zeroRttHandshake == true,
            icon = Icons.Rounded.Bolt,
            onCheckedChange = { editingOutbound = outbound.copy(zeroRttHandshake = it) }
        )
    }
    if (type == "tuic" || type == "hysteria2") {
        SettingSwitchItem(
            title = stringResource(R.string.node_detail_disable_sni),
            checked = outbound.tls?.disableSni == true,
            icon = Icons.Rounded.Fingerprint,
            onCheckedChange = { checked ->
                val currentTls = outbound.tls ?: TlsConfig(enabled = true)
                editingOutbound = outbound.copy(
                    disableSni = null,
                    tls = currentTls.copy(
                        enabled = true,
                        disableSni = if (checked) true else null
                    )
                )
            }
        )
    }

    // 6. Naive
    if (type == "naive") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_username),
            value = outbound.username ?: "",
            icon = Icons.Rounded.Person,
            onValueChange = { editingOutbound = outbound.copy(username = if (it.isEmpty()) null else it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_password),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = if (it.isEmpty()) null else it) }
        )
        EditableSelectionItem(
            title = stringResource(R.string.node_detail_transport_protocol),
            value = if (outbound.quic == true || outbound.network?.firstOrNull() == "quic") "quic" else "h2",
            options = listOf("h2", "quic"),
            icon = Icons.Rounded.SwapHoriz,
            onValueChange = {
                val useQuic = it == "quic"
                editingOutbound = outbound.copy(
                    network = listOf(if (useQuic) "quic" else "h2"),
                    quic = useQuic
                )
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_insecure_concurrency),
            value = outbound.insecureConcurrency?.toString() ?: "",
            icon = Icons.Rounded.Numbers,
            onValueChange = {
                editingOutbound = outbound.copy(insecureConcurrency = it.toIntOrNull())
            }
        )
        EditableMultilineTextItem(
            title = stringResource(R.string.node_detail_extra_headers),
            value = formatHeaderLines(outbound.extraHeaders),
            subtitle = stringResource(R.string.node_detail_extra_headers_hint),
            placeholder = "User-Agent: naive\nX-Trace: demo",
            icon = Icons.Rounded.Language,
            onValueChange = {
                editingOutbound = outbound.copy(extraHeaders = parseHeaderLines(it))
            }
        )
        EditableSelectionItem(
            title = stringResource(R.string.node_detail_congestion_control),
            value = outbound.congestionControl ?: "",
            options = listOf("", "bbr", "cubic", "new_reno"),
            icon = Icons.Rounded.Speed,
            onValueChange = { editingOutbound = outbound.copy(congestionControl = it.ifEmpty { null }) }
        )
        val uot = outbound.udpOverTcp ?: UdpOverTcpConfig(enabled = false)
        SettingSwitchItem(
            title = stringResource(R.string.node_detail_udp_over_tcp),
            checked = uot.enabled == true,
            icon = Icons.Rounded.SwapHoriz,
            onCheckedChange = { enabled ->
                editingOutbound = outbound.copy(
                    udpOverTcp = if (enabled) {
                        uot.copy(enabled = true)
                    } else {
                        null
                    }
                )
            }
        )
    }

    // 7. WireGuard
    if (type == "wireguard") {
        val peer = outbound.peers?.firstOrNull() ?: WireGuardPeer()

        EditableTextItem(
            title = stringResource(R.string.node_detail_server_address),
            value = peer.server ?: "",
            icon = Icons.Rounded.Router,
            onValueChange = {
                editingOutbound = outbound.copy(peers = listOf(peer.copy(server = it)))
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_server_port),
            value = peer.serverPort?.toString() ?: "",
            icon = Icons.Rounded.Numbers,
            onValueChange = {
                editingOutbound = outbound.copy(peers = listOf(peer.copy(serverPort = it.toIntOrNull())))
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_private_key),
            value = outbound.privateKey?.firstOrNull().orEmpty(),
            icon = Icons.Rounded.Key,
            onValueChange = { editingOutbound = outbound.copy(privateKey = listOf(it)) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_peer_public_key),
            value = peer.publicKey ?: "",
            icon = Icons.Rounded.Key,
            onValueChange = {
                editingOutbound = outbound.copy(peers = listOf(peer.copy(publicKey = it)))
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_pre_shared_key),
            value = peer.preSharedKey ?: "",
            icon = Icons.Rounded.Key,
            onValueChange = {
                editingOutbound = outbound.copy(
                    peers = listOf(peer.copy(preSharedKey = it.takeIf { value -> value.isNotEmpty() }))
                )
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_local_address),
            value = outbound.localAddress?.joinToString(", ") ?: "",
            icon = Icons.Rounded.Dns,
            onValueChange = {
                val addresses = it.split(",").map { value -> value.trim() }.filter { value -> value.isNotEmpty() }
                editingOutbound = outbound.copy(localAddress = addresses.takeIf { values -> values.isNotEmpty() })
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_allowed_ips),
            value = peer.allowedIps?.joinToString(", ") ?: "",
            icon = Icons.Rounded.Route,
            onValueChange = {
                val allowedIps = it.split(",").map { value -> value.trim() }.filter { value -> value.isNotEmpty() }
                editingOutbound = outbound.copy(
                    peers = listOf(peer.copy(allowedIps = allowedIps.takeIf { values -> values.isNotEmpty() }))
                )
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_persistent_keepalive),
            value = peer.persistentKeepaliveInterval?.toString() ?: "",
            icon = Icons.Rounded.Timer,
            onValueChange = {
                editingOutbound = outbound.copy(
                    peers = listOf(
                        peer.copy(persistentKeepaliveInterval = it.trim().takeIf(String::isNotEmpty)?.toIntOrNull())
                    )
                )
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_mtu),
            value = outbound.mtu?.toString() ?: "1420",
            icon = Icons.Rounded.SettingsInputAntenna,
            onValueChange = { editingOutbound = outbound.copy(mtu = it.toIntOrNull()) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_reserved),
            value = peer.reserved?.joinToString(", ") ?: "",
            icon = Icons.Rounded.Tag,
            onValueChange = {
                val reserved = it.split(",").mapNotNull { value -> value.trim().toIntOrNull() }
                editingOutbound = outbound.copy(
                    peers = listOf(peer.copy(reserved = reserved.takeIf { values -> values.isNotEmpty() }))
                )
            }
        )
    }

    // 8. SSH
    if (type == "ssh") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_username),
            value = outbound.user ?: "",
            icon = Icons.Rounded.Person,
            onValueChange = { editingOutbound = outbound.copy(user = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_password),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = if (it.isEmpty()) null else it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_private_key),
            value = outbound.privateKey?.firstOrNull().orEmpty(),
            icon = Icons.Rounded.Key,
            onValueChange = {
                editingOutbound = outbound.copy(privateKey = it.takeIf(String::isNotEmpty)?.let(::listOf))
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_passphrase),
            value = outbound.privateKeyPassphrase ?: "",
            icon = Icons.Rounded.Key,
            onValueChange = { editingOutbound = outbound.copy(privateKeyPassphrase = if (it.isEmpty()) null else it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_host_key),
            value = outbound.hostKey?.joinToString("\n") ?: "",
            icon = Icons.Rounded.Fingerprint,
            onValueChange = {
                val list = it.split("\n").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                editingOutbound = outbound.copy(hostKey = list)
            }
        )
    }

    // 8. AnyTLS
    if (type == "anytls") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_password),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_idle_session_check),
            value = outbound.idleSessionCheckInterval ?: "30s",
            icon = Icons.Rounded.SwapHoriz,
            onValueChange = { editingOutbound = outbound.copy(idleSessionCheckInterval = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_idle_session_timeout),
            value = outbound.idleSessionTimeout ?: "30s",
            icon = Icons.Rounded.SwapHoriz,
            onValueChange = { editingOutbound = outbound.copy(idleSessionTimeout = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_min_idle_sessions),
            value = outbound.minIdleSession?.toString() ?: "0",
            icon = Icons.Rounded.Numbers,
            onValueChange = { editingOutbound = outbound.copy(minIdleSession = it.toIntOrNull()) }
        )
    }

    // 9. SOCKS
    if (type == "socks") {
        EditableSelectionItem(
            title = stringResource(R.string.node_detail_socks_version),
            value = outbound.version?.asString ?: "5",
            options = listOf("4", "4a", "5"),
            icon = Icons.Rounded.Tag,
            onValueChange = { editingOutbound = outbound.copy(version = JsonPrimitive(it)) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_username_optional),
            value = outbound.username ?: "",
            icon = Icons.Rounded.Person,
            onValueChange = { editingOutbound = outbound.copy(username = if (it.isEmpty()) null else it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_password_optional),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = if (it.isEmpty()) null else it) }
        )
    }

    // 10. HTTP
    if (type == "http") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_username_optional),
            value = outbound.username ?: "",
            icon = Icons.Rounded.Person,
            onValueChange = { editingOutbound = outbound.copy(username = if (it.isEmpty()) null else it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_password_optional),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = if (it.isEmpty()) null else it) }
        )
    }

    // 11. ShadowTLS
    if (type == "shadowtls") {
        EditableSelectionItem(
            title = stringResource(R.string.node_detail_shadowtls_version),
            value = outbound.version?.asString ?: "3",
            options = listOf("1", "2", "3"),
            icon = Icons.Rounded.Tag,
            onValueChange = { value ->
                editingOutbound = outbound.copy(version = value.toIntOrNull()?.let(::JsonPrimitive))
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_password),
            value = outbound.password ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(password = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_common_settings),
            value = outbound.detour ?: "",
            icon = Icons.Rounded.Route,
            onValueChange = { editingOutbound = outbound.copy(detour = if (it.isEmpty()) null else it) }
        )
    }

    // 12. Hysteria (v1)
    if (type == "hysteria") {
        EditableTextItem(
            title = stringResource(R.string.node_detail_auth_string),
            value = outbound.authStr ?: "",
            icon = Icons.Rounded.Password,
            onValueChange = { editingOutbound = outbound.copy(authStr = it) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_upload_speed),
            value = outbound.upMbps?.toString() ?: "",
            icon = Icons.Rounded.Speed,
            onValueChange = { editingOutbound = outbound.copy(upMbps = it.toIntOrNull()) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_download_speed),
            value = outbound.downMbps?.toString() ?: "",
            icon = Icons.Rounded.Speed,
            onValueChange = { editingOutbound = outbound.copy(downMbps = it.toIntOrNull()) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_obfs_type),
            value = outbound.obfs?.type ?: "",
            icon = Icons.Rounded.Lock,
            onValueChange = {
                val newObfs = if (it.isEmpty()) {
                    null
                } else {
                    outbound.obfs?.copy(type = it, stringValue = true)
                        ?: ObfsConfig(type = it, stringValue = true)
                }
                editingOutbound = outbound.copy(obfs = newObfs)
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_detail_hop_interval),
            value = outbound.hopInterval ?: "10",
            icon = Icons.Rounded.SwapHoriz,
            onValueChange = { editingOutbound = outbound.copy(hopInterval = it) }
        )
    }
}
