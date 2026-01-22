package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kunk.singbox.model.EchConfig
import com.kunk.singbox.model.MultiplexConfig
import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UdpOverTcpConfig
import com.kunk.singbox.model.WireGuardPeer
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.ui.components.EditableSelectionItem
import com.kunk.singbox.ui.components.EditableTextItem
import com.kunk.singbox.ui.components.SelectProfileDialog
import com.kunk.singbox.ui.components.SelectProfileTarget
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.StandardCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(
    navController: NavController,
    nodeId: String,
    createProtocol: String = ""
) {
    val context = LocalContext.current
    val configRepository = remember { ConfigRepository.getInstance(context) }

    val isCreateMode = nodeId.isEmpty() && createProtocol.isNotEmpty()

    val nodes by configRepository.nodes.collectAsState(initial = emptyList())
    val node = if (!isCreateMode) nodes.find { it.id == nodeId } else null
    val profiles by configRepository.profiles.collectAsState(initial = emptyList())

    var editingOutbound by remember { mutableStateOf<Outbound?>(null) }
    var showSelectProfileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(nodeId, createProtocol) {
        if (editingOutbound == null) {
            if (isCreateMode) {
                editingOutbound = createEmptyOutbound(createProtocol)
            } else {
                val original = configRepository.getOutboundByNodeId(nodeId)
                if (original != null) {
                    editingOutbound = original
                }
            }
        }
    }

    val createdMsg = stringResource(R.string.node_created)
    if (showSelectProfileDialog) {
        SelectProfileDialog(
            profiles = profiles,
            onConfirm = { target ->
                editingOutbound?.let { outbound ->
                    when (target) {
                        is SelectProfileTarget.ExistingProfile -> {
                            configRepository.createNode(outbound, targetProfileId = target.profileId)
                        }
                        is SelectProfileTarget.NewProfile -> {
                            configRepository.createNode(outbound, newProfileName = target.profileName)
                        }
                    }
                    Toast.makeText(context, createdMsg, Toast.LENGTH_SHORT).show()
                    navController.popBackStack()
                }
                showSelectProfileDialog = false
            },
            onDismiss = { showSelectProfileDialog = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isCreateMode) stringResource(R.string.node_create_title)
                        else stringResource(R.string.node_detail_title),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    val savedMsg = stringResource(R.string.node_detail_saved)
                    IconButton(onClick = {
                        if (editingOutbound != null) {
                            if (isCreateMode) {
                                showSelectProfileDialog = true
                            } else {
                                configRepository.updateNode(nodeId, editingOutbound!!)
                                Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                                navController.popBackStack()
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.common_save), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (editingOutbound == null) {
                StandardCard {
                    SettingItem(title = stringResource(R.string.common_loading), value = "")
                }
            } else {
                val outbound = editingOutbound!!
                val type = outbound.type

                // --- Common Header ---
                StandardCard {
                    EditableTextItem(
                        title = stringResource(R.string.node_detail_config_name),
                        value = outbound.tag,
                        icon = Icons.Rounded.Title,
                        onValueChange = { editingOutbound = outbound.copy(tag = it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(stringResource(R.string.node_detail_server_settings))

                // --- Server Info (Address/Port) ---
                StandardCard {
                    // Most protocols have server/port
                    if (type != "wireguard") {
                        EditableTextItem(
                            title = stringResource(R.string.node_detail_server_address),
                            value = outbound.server ?: "",
                            icon = Icons.Rounded.Router,
                            onValueChange = { editingOutbound = outbound.copy(server = it) }
                        )
                        EditableTextItem(
                            title = stringResource(R.string.node_detail_server_port),
                            value = outbound.serverPort?.toString() ?: "",
                            icon = Icons.Rounded.Numbers,
                            onValueChange = { editingOutbound = outbound.copy(serverPort = it.toIntOrNull() ?: 0) }
                        )
                    }

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
                            title = "Plugin (Optional)", // TODO: add to strings.xml
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
                            title = "UDP over TCP",
                            checked = uot.enabled == true,
                            icon = Icons.Rounded.SwapHoriz,
                            onCheckedChange = { editingOutbound = outbound.copy(udpOverTcp = uot.copy(enabled = it)) }
                        )
                    }

                    // 2. VMess / VLESS
                    if (type == "vmess" || type == "vless") {
                        EditableTextItem(
                            title = "UUID",
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
                            // 此字段已从模型中移除
                        }

                        if (type == "vless") {
                            EditableSelectionItem(
                                title = "Flow",
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
                            title = "Ports (Jumping)", // TODO: add to strings.xml
                            value = outbound.ports ?: "",
                            icon = Icons.Rounded.Numbers,
                            onValueChange = { editingOutbound = outbound.copy(ports = if (it.isEmpty()) null else it) }
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
                            title = "Upload Speed (Mbps)", // TODO: add to strings.xml
                            value = outbound.upMbps?.toString() ?: "",
                            icon = Icons.Rounded.Speed,
                            onValueChange = { editingOutbound = outbound.copy(upMbps = it.toIntOrNull()) }
                        )
                        EditableTextItem(
                            title = "Download Speed (Mbps)", // TODO: add to strings.xml
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
                            title = "UDP Relay Mode", // TODO: add to strings.xml
                            value = outbound.udpRelayMode ?: "native",
                            options = listOf("native", "quic"),
                            icon = Icons.Rounded.SwapHoriz,
                            onValueChange = { editingOutbound = outbound.copy(udpRelayMode = it) }
                        )
                        EditableTextItem(
                            title = "Heartbeat", // TODO: add to strings.xml
                            value = outbound.heartbeat ?: "3s",
                            icon = Icons.Rounded.Bolt,
                            onValueChange = { editingOutbound = outbound.copy(heartbeat = it) }
                        )
                        SettingSwitchItem(
                            title = "Zero RTT", // TODO: add to strings.xml
                            checked = outbound.zeroRttHandshake == true,
                            icon = Icons.Rounded.Bolt,
                            onCheckedChange = { editingOutbound = outbound.copy(zeroRttHandshake = it) }
                        )
                        SettingSwitchItem(
                            title = "Disable SNI", // TODO: add to strings.xml
                            checked = outbound.disableSni == true,
                            icon = Icons.Rounded.Fingerprint,
                            onCheckedChange = { editingOutbound = outbound.copy(disableSni = it) }
                        )
                    }

                    // 6. WireGuard
                    if (type == "wireguard") {
                        val peer = outbound.peers?.firstOrNull() ?: WireGuardPeer()

                        EditableTextItem(
                            title = stringResource(R.string.node_detail_server_address),
                            value = peer.server ?: "",
                            icon = Icons.Rounded.Router,
                            onValueChange = {
                                val newPeer = peer.copy(server = it)
                                editingOutbound = outbound.copy(peers = listOf(newPeer))
                            }
                        )
                        EditableTextItem(
                            title = stringResource(R.string.node_detail_server_port),
                            value = peer.serverPort?.toString() ?: "",
                            icon = Icons.Rounded.Numbers,
                            onValueChange = {
                                val newPeer = peer.copy(serverPort = it.toIntOrNull())
                                editingOutbound = outbound.copy(peers = listOf(newPeer))
                            }
                        )
                        EditableTextItem(
                            title = stringResource(R.string.node_detail_private_key),
                            value = outbound.privateKey ?: "",
                            icon = Icons.Rounded.Key,
                            onValueChange = { editingOutbound = outbound.copy(privateKey = it) }
                        )
                        EditableTextItem(
                            title = "Peer Public Key", // TODO: add to strings.xml
                            value = peer.publicKey ?: "",
                            icon = Icons.Rounded.Key,
                            onValueChange = {
                                val newPeer = peer.copy(publicKey = it)
                                editingOutbound = outbound.copy(peers = listOf(newPeer))
                            }
                        )
                        EditableTextItem(
                            title = "Pre-Shared Key", // TODO: add to strings.xml
                            value = peer.preSharedKey ?: "",
                            icon = Icons.Rounded.Key,
                            onValueChange = {
                                val newPeer = peer.copy(preSharedKey = if (it.isEmpty()) null else it)
                                editingOutbound = outbound.copy(peers = listOf(newPeer))
                            }
                        )
                        EditableTextItem(
                            title = "Local Address (CIDR)", // TODO: add to strings.xml
                            value = outbound.localAddress?.joinToString(", ") ?: "",
                            icon = Icons.Rounded.Dns,
                            onValueChange = {
                                val list = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                                editingOutbound = outbound.copy(localAddress = list)
                            }
                        )
                        EditableTextItem(
                            title = "MTU",
                            value = outbound.mtu?.toString() ?: "1420",
                            icon = Icons.Rounded.SettingsInputAntenna,
                            onValueChange = { editingOutbound = outbound.copy(mtu = it.toIntOrNull()) }
                        )
                        EditableTextItem(
                            title = "Reserved (WARP)",
                            value = outbound.reserved?.joinToString(", ") ?: "",
                            icon = Icons.Rounded.Tag,
                            onValueChange = {
                                val list = it.split(",").mapNotNull { s -> s.trim().toIntOrNull() }
                                editingOutbound = outbound.copy(reserved = if (list.isEmpty()) null else list)
                            }
                        )
                    }

                    // 7. SSH
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
                            value = outbound.privateKey ?: "",
                            icon = Icons.Rounded.Key,
                            onValueChange = { editingOutbound = outbound.copy(privateKey = if (it.isEmpty()) null else it) }
                        )
                        EditableTextItem(
                            title = "Passphrase", // TODO: add to strings.xml
                            value = outbound.privateKeyPassphrase ?: "",
                            icon = Icons.Rounded.Key,
                            onValueChange = { editingOutbound = outbound.copy(privateKeyPassphrase = if (it.isEmpty()) null else it) }
                        )
                        EditableTextItem(
                            title = "Host Key", // TODO: add to strings.xml
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
                            title = "SOCKS Version", // TODO: add to strings.xml
                            value = outbound.version?.toString() ?: "5",
                            options = listOf("4", "4a", "5"),
                            icon = Icons.Rounded.Tag,
                            onValueChange = { editingOutbound = outbound.copy(version = it.replace("a", "").toIntOrNull()) }
                        )
                        EditableTextItem(
                            title = "Username (Optional)", // TODO: add to strings.xml
                            value = outbound.username ?: "",
                            icon = Icons.Rounded.Person,
                            onValueChange = { editingOutbound = outbound.copy(username = if (it.isEmpty()) null else it) }
                        )
                        EditableTextItem(
                            title = "Password (Optional)", // TODO: add to strings.xml
                            value = outbound.password ?: "",
                            icon = Icons.Rounded.Password,
                            onValueChange = { editingOutbound = outbound.copy(password = if (it.isEmpty()) null else it) }
                        )
                    }

                    // 10. HTTP
                    if (type == "http") {
                        EditableTextItem(
                            title = "Username (Optional)", // TODO: add to strings.xml
                            value = outbound.username ?: "",
                            icon = Icons.Rounded.Person,
                            onValueChange = { editingOutbound = outbound.copy(username = if (it.isEmpty()) null else it) }
                        )
                        EditableTextItem(
                            title = "Password (Optional)", // TODO: add to strings.xml
                            value = outbound.password ?: "",
                            icon = Icons.Rounded.Password,
                            onValueChange = { editingOutbound = outbound.copy(password = if (it.isEmpty()) null else it) }
                        )
                    }

                    // 11. ShadowTLS
                    if (type == "shadowtls") {
                        EditableSelectionItem(
                            title = "ShadowTLS Version", // TODO: add to strings.xml
                            value = outbound.version?.toString() ?: "3",
                            options = listOf("1", "2", "3"),
                            icon = Icons.Rounded.Tag,
                            onValueChange = { editingOutbound = outbound.copy(version = it.toIntOrNull()) }
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
                            title = "Upload Speed (Mbps)", // TODO: add to strings.xml
                            value = outbound.upMbps?.toString() ?: "",
                            icon = Icons.Rounded.Speed,
                            onValueChange = { editingOutbound = outbound.copy(upMbps = it.toIntOrNull()) }
                        )
                        EditableTextItem(
                            title = "Download Speed (Mbps)", // TODO: add to strings.xml
                            value = outbound.downMbps?.toString() ?: "",
                            icon = Icons.Rounded.Speed,
                            onValueChange = { editingOutbound = outbound.copy(downMbps = it.toIntOrNull()) }
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
                        EditableTextItem(
                            title = "Hop Interval (s)", // TODO: add to strings.xml
                            value = outbound.hopInterval ?: "10",
                            icon = Icons.Rounded.SwapHoriz,
                            onValueChange = { editingOutbound = outbound.copy(hopInterval = it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Transport ---
                if (type in listOf("vmess", "vless", "trojan", "shadowsocks")) {
                    SectionHeader(stringResource(R.string.node_detail_transport_settings))
                    StandardCard {
                        val transport = outbound.transport ?: TransportConfig(type = "tcp")
                        val currentType = transport.type ?: "tcp"

                        EditableSelectionItem(
                            title = stringResource(R.string.node_detail_transport_protocol),
                            value = currentType,
                            options = listOf("tcp", "http", "ws", "grpc", "quic", "httpupgrade"),
                            icon = Icons.Rounded.SwapHoriz,
                            onValueChange = { newType ->
                                editingOutbound = outbound.copy(
                                    transport = transport.copy(type = newType)
                                )
                            }
                        )

                        if (currentType == "ws") {
                            Spacer(modifier = Modifier.height(8.dp))
                            EditableTextItem(
                                title = "WebSocket 主机",
                                value = transport.headers?.get("Host") ?: "",
                                icon = Icons.Rounded.Language,
                                onValueChange = {
                                    val newHeaders = (transport.headers ?: emptyMap()).toMutableMap()
                                    if (it.isBlank()) newHeaders.remove("Host") else newHeaders["Host"] = it
                                    editingOutbound = outbound.copy(transport = transport.copy(headers = newHeaders))
                                }
                            )
                            EditableTextItem(
                                title = "WebSocket Path", // TODO: add to strings.xml
                                value = transport.path ?: "/",
                                icon = Icons.Rounded.Route,
                                onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(path = it)) }
                            )
                            EditableTextItem(
                                title = "Max Early Data",
                                value = transport.maxEarlyData?.toString() ?: "",
                                icon = Icons.Rounded.CompareArrows,
                                onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(maxEarlyData = it.toIntOrNull())) }
                            )
                            EditableTextItem(
                                title = "Early Data Header",
                                value = transport.earlyDataHeaderName ?: "",
                                icon = Icons.Rounded.Title,
                                onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(earlyDataHeaderName = if (it.isEmpty()) null else it)) }
                            )
                        }

                        if (currentType == "grpc") {
                            Spacer(modifier = Modifier.height(8.dp))
                            EditableTextItem(
                                title = "Service Name",
                                value = transport.serviceName ?: "",
                                icon = Icons.Rounded.Tag,
                                onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(serviceName = it)) }
                            )
                        }

                        if (currentType == "http" || currentType == "h2" || currentType == "httpupgrade") {
                            Spacer(modifier = Modifier.height(8.dp))
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_transport_path),
                                value = transport.path ?: "/",
                                icon = Icons.Rounded.Route,
                                onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(path = it)) }
                            )
                            EditableTextItem(
                                title = "Host",
                                value = transport.host?.joinToString(", ") ?: "",
                                icon = Icons.Rounded.Language,
                                onValueChange = {
                                    val hosts = it.split(",").map { h -> h.trim() }.filter { h -> h.isNotEmpty() }
                                    editingOutbound = outbound.copy(transport = transport.copy(host = hosts))
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- TLS ---
                if (type !in listOf("wireguard", "ssh", "shadowsocks")) {
                    SectionHeader("TLS Settings") // TODO: add to strings.xml
                    StandardCard {
                        val tls = outbound.tls ?: TlsConfig(enabled = false)
                        val isTlsIntrinsic = type in listOf("hysteria2", "hysteria", "tuic", "anytls")

                        // Security type selector
                        val securityType = if (isTlsIntrinsic || tls.enabled == true) {
                            if (tls.reality?.enabled == true) "reality" else "tls"
                        } else "none"

                        if (!isTlsIntrinsic) {
                            EditableSelectionItem(
                                title = stringResource(R.string.node_detail_transport_security),
                                value = securityType,
                                options = listOf("none", "tls", "reality"),
                                icon = Icons.Rounded.Security,
                                onValueChange = { type ->
                                    val newTls = when (type) {
                                        "none" -> tls.copy(enabled = false)
                                        "tls" -> tls.copy(enabled = true, reality = null)
                                        "reality" -> tls.copy(enabled = true, reality = com.kunk.singbox.model.RealityConfig(enabled = true))
                                        else -> tls
                                    }
                                    editingOutbound = outbound.copy(tls = newTls)
                                }
                            )
                        }

                        if (securityType != "none") {
                            EditableTextItem(
                                title = "SNI (Server Name Indication)",
                                value = tls.serverName ?: "",
                                icon = Icons.Rounded.Dns,
                                onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(serverName = it)) }
                            )

                            EditableTextItem(
                                title = "ALPN",
                                value = tls.alpn?.joinToString(", ") ?: "",
                                icon = Icons.Rounded.Merge,
                                onValueChange = {
                                    val alpnList = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                                    editingOutbound = outbound.copy(tls = tls.copy(alpn = alpnList))
                                }
                            )

                            SettingSwitchItem(
                                title = "Allow Insecure", // TODO: add to strings.xml
                                subtitle = "Disable certificate verification", // TODO: add to strings.xml
                                checked = tls.insecure == true,
                                icon = Icons.Rounded.Lock,
                                onCheckedChange = { editingOutbound = outbound.copy(tls = tls.copy(insecure = it)) }
                            )
                            EditableTextItem(
                                title = "CA 证书 (PEM)",
                                value = tls.ca ?: "",
                                icon = Icons.Rounded.Security,
                                onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(ca = if (it.isEmpty()) null else it)) }
                            )

                            EditableTextItem(
                                title = "客户端证书 (PEM)",
                                value = tls.certificate ?: "",
                                icon = Icons.Rounded.Security,
                                onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(certificate = if (it.isEmpty()) null else it)) }
                            )

                            EditableTextItem(
                                title = "客户端私钥 (PEM)",
                                value = tls.key ?: "",
                                icon = Icons.Rounded.Key,
                                onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(key = if (it.isEmpty()) null else it)) }
                            )

                            // uTLS
                            Spacer(modifier = Modifier.height(8.dp))
                            EditableSelectionItem(
                                title = "uTLS Fingerprint", // TODO: add to strings.xml
                                value = tls.utls?.fingerprint ?: "",
                                options = listOf("") + listOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"),
                                icon = Icons.Rounded.Fingerprint,
                                onValueChange = { fp ->
                                    val newUtls = if (fp.isEmpty()) null else com.kunk.singbox.model.UtlsConfig(enabled = true, fingerprint = fp)
                                    editingOutbound = outbound.copy(tls = tls.copy(utls = newUtls))
                                }
                            )

                            // Reality Specific
                            if (securityType == "reality") {
                                val reality = tls.reality ?: com.kunk.singbox.model.RealityConfig(enabled = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                EditableTextItem(
                                    title = "Reality Public Key",
                                    value = reality.publicKey ?: "",
                                    icon = Icons.Rounded.Key,
                                    onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(reality = reality.copy(publicKey = it))) }
                                )
                                EditableTextItem(
                                    title = "Reality ShortId",
                                    value = reality.shortId ?: "",
                                    icon = Icons.Rounded.Tag,
                                    onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(reality = reality.copy(shortId = it))) }
                                )
                                EditableTextItem(
                                    title = "Reality SpiderX",
                                    value = reality.spiderX ?: "",
                                    icon = Icons.Rounded.Waves,
                                    onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(reality = reality.copy(spiderX = it))) }
                                )
                            }

                            // ECH
                            val ech = tls.ech ?: EchConfig(enabled = false)
                            Spacer(modifier = Modifier.height(8.dp))
                            SettingSwitchItem(
                                title = "Enable ECH", // TODO: add to strings.xml
                                checked = ech.enabled == true,
                                icon = Icons.Rounded.Security,
                                onCheckedChange = { enabled ->
                                    editingOutbound = outbound.copy(tls = tls.copy(ech = ech.copy(enabled = enabled)))
                                }
                            )
                            if (ech.enabled == true) {
                                EditableTextItem(
                                    title = "ECH 配置 (Base64)",
                                    value = ech.config?.joinToString("\n") ?: "",
                                    icon = Icons.Rounded.Tune,
                                    onValueChange = {
                                        val configs = it.split("\n").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                                        editingOutbound = outbound.copy(tls = tls.copy(ech = ech.copy(config = configs)))
                                    }
                                )
                                EditableTextItem(
                                    title = "ECH 私钥 (Base64)",
                                    value = ech.key?.joinToString("\n") ?: "",
                                    icon = Icons.Rounded.Key,
                                    onValueChange = {
                                        val keys = it.split("\n").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                                        editingOutbound = outbound.copy(tls = tls.copy(ech = ech.copy(key = keys)))
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Transport ---
                // ...
                // --- Multiplex ---
                if (type in listOf("vmess", "vless", "trojan", "shadowsocks")) {
                    SectionHeader(stringResource(R.string.node_detail_mux_settings))
                    StandardCard {
                        val mux = outbound.multiplex ?: MultiplexConfig(enabled = false)
                        SettingSwitchItem(
                            title = stringResource(R.string.node_detail_mux_enable),
                            subtitle = stringResource(R.string.node_detail_mux_subtitle),
                            checked = mux.enabled == true,
                            icon = Icons.Rounded.CallSplit,
                            onCheckedChange = { enabled ->
                                editingOutbound = outbound.copy(multiplex = mux.copy(enabled = enabled))
                            }
                        )

                        if (mux.enabled == true) {
                            EditableSelectionItem(
                                title = "Mux Protocol", // TODO: add to strings.xml
                                value = mux.protocol ?: "h2mux",
                                options = listOf("h2mux", "smux", "yamux"),
                                icon = Icons.Rounded.Merge,
                                onValueChange = { editingOutbound = outbound.copy(multiplex = mux.copy(protocol = it)) }
                            )
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_mux_max_connections),
                                value = mux.maxConnections?.toString() ?: "5",
                                icon = Icons.Rounded.Numbers,
                                onValueChange = { editingOutbound = outbound.copy(multiplex = mux.copy(maxConnections = it.toIntOrNull())) }
                            )
                            EditableTextItem(
                                title = "最小流数量 (Min Streams)",
                                value = mux.minStreams?.toString() ?: "",
                                icon = Icons.Rounded.Numbers,
                                onValueChange = { editingOutbound = outbound.copy(multiplex = mux.copy(minStreams = it.toIntOrNull())) }
                            )
                            EditableTextItem(
                                title = "最大流数量 (Max Streams)",
                                value = mux.maxStreams?.toString() ?: "",
                                icon = Icons.Rounded.Numbers,
                                onValueChange = { editingOutbound = outbound.copy(multiplex = mux.copy(maxStreams = it.toIntOrNull())) }
                            )
                            SettingSwitchItem(
                                title = "Padding",
                                checked = mux.padding == true,
                                icon = Icons.Rounded.Layers,
                                onCheckedChange = { padding ->
                                    editingOutbound = outbound.copy(multiplex = mux.copy(padding = padding))
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Common Settings for all protocols ---
                SectionHeader(stringResource(R.string.node_detail_common_settings))
                StandardCard {
                    EditableTextItem(
                        title = stringResource(R.string.common_outbound) + " (Detour)",
                        value = outbound.detour ?: "",
                        icon = Icons.Rounded.Route,
                        onValueChange = { editingOutbound = outbound.copy(detour = if (it.isEmpty()) null else it) }
                    )
                    SettingSwitchItem(
                        title = "TCP Fast Open",
                        checked = outbound.tcpFastOpen == true,
                        icon = Icons.Rounded.Bolt,
                        onCheckedChange = { editingOutbound = outbound.copy(tcpFastOpen = it) }
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
    )
}

private fun createEmptyOutbound(protocol: String): Outbound {
    val defaultPort = when (protocol) {
        "shadowsocks" -> 8388
        "vmess", "vless" -> 443
        "trojan" -> 443
        "hysteria2", "hysteria" -> 443
        "tuic" -> 443
        "anytls" -> 443
        "ssh" -> 22
        "socks" -> 1080
        "http" -> 8080
        "wireguard" -> 51820
        else -> 443
    }

    val needsTls = protocol in listOf("vless", "trojan", "hysteria2", "hysteria", "tuic", "anytls")

    return Outbound(
        type = protocol,
        tag = "New-${protocol.uppercase()}",
        server = "",
        serverPort = defaultPort,
        tls = if (needsTls) TlsConfig(enabled = true) else null
    )
}
