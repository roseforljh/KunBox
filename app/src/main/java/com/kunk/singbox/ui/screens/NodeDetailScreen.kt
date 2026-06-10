package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.google.gson.Gson
import com.kunk.singbox.model.EchConfig
import com.kunk.singbox.model.MultiplexConfig
import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UdpOverTcpConfig
import com.kunk.singbox.model.WireGuardPeer
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.EditableMultilineTextItem
import com.kunk.singbox.ui.components.EditableSelectionItem
import com.kunk.singbox.ui.components.EditableTextItem
import com.kunk.singbox.ui.components.SelectProfileDialog
import com.kunk.singbox.ui.components.SelectProfileTarget
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.StandardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun resolveTransportHostTextForEditor(transport: TransportConfig): String {
    return transport.host
        ?.filter { it.isNotBlank() }
        ?.joinToString(", ")
        ?: transport.headers?.get("Host")
        ?: transport.headers?.get("host")
        ?: ""
}

internal fun resolveWebSocketHostTextForEditor(transport: TransportConfig): String {
    return transport.headers?.get("Host")
        ?: transport.headers?.get("host")
        ?: transport.host?.firstOrNull { it.isNotBlank() }
        ?: ""
}

internal fun updateTransportTypeForEditor(transport: TransportConfig, newType: String): TransportConfig {
    val updated = transport.copy(type = newType)
    return when (newType) {
        "ws" -> {
            val host = updated.headers?.get("Host")
                ?: updated.headers?.get("host")
                ?: updated.host?.firstOrNull()
            val headers = updated.headers.withoutHostHeader().orEmpty().toMutableMap()
            if (!host.isNullOrBlank()) headers["Host"] = host
            updated.copy(host = null, headers = headers.takeIf { it.isNotEmpty() })
        }
        "httpupgrade" -> updated.copy(
            host = updated.host?.takeIf { it.isNotEmpty() }
                ?: updated.headers?.get("Host")?.let { listOf(it) }
                ?: updated.headers?.get("host")?.let { listOf(it) },
            headers = updated.headers.withoutHostHeader(),
            maxEarlyData = null,
            earlyDataHeaderName = null
        )
        else -> updated
    }
}

internal fun updatePathBasedTransportHostForEditor(
    transport: TransportConfig,
    value: String
): TransportConfig {
    val hosts = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    return transport.copy(
        host = hosts.takeIf { it.isNotEmpty() },
        headers = transport.headers.withoutHostHeader()
    )
}

internal fun updateWebSocketTransportHostForEditor(
    transport: TransportConfig,
    value: String
): TransportConfig {
    val headers = transport.headers.withoutHostHeader().orEmpty().toMutableMap()
    val trimmedValue = value.trim()
    if (trimmedValue.isNotEmpty()) headers["Host"] = trimmedValue
    return transport.copy(host = null, headers = headers.takeIf { it.isNotEmpty() })
}

private fun Map<String, String>?.withoutHostHeader(): Map<String, String>? {
    return this
        ?.filterKeys { !it.equals("Host", ignoreCase = true) }
        ?.takeIf { it.isNotEmpty() }
}

private val outboundEditorGson = Gson()

private val outboundEditorStateSaver = Saver<MutableState<Outbound?>, String>(
    save = { state -> state.value?.let { outboundEditorGson.toJson(it) }.orEmpty() },
    restore = { json ->
        mutableStateOf(
            json.takeIf { it.isNotBlank() }
                ?.let { outboundEditorGson.fromJson(it, Outbound::class.java) }
        )
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(
    navController: NavController,
    nodeId: String,
    createProtocol: String = ""
) {
    val context = LocalContext.current
    val configRepository = remember { ConfigRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    val isCreateMode = nodeId.isEmpty() && createProtocol.isNotEmpty()

    DisposableEffect(configRepository) {
        configRepository.setAllNodesUiActive(true)
        onDispose {
            configRepository.setAllNodesUiActive(false)
        }
    }

    val nodes by configRepository.nodes.collectAsState(initial = emptyList())
    val allNodes by configRepository.allNodes.collectAsState(initial = emptyList())
    val activeProfileId by configRepository.activeProfileId.collectAsState(initial = null)
    val node = if (!isCreateMode) nodes.find { it.id == nodeId } else null
    val profiles by configRepository.profiles.collectAsState(initial = emptyList())

    var editingOutbound by rememberSaveable(nodeId, createProtocol, saver = outboundEditorStateSaver) {
        mutableStateOf<Outbound?>(null)
    }
    var showSelectProfileDialog by remember { mutableStateOf(false) }
    var showDetourNodeDialog by remember { mutableStateOf(false) }
    var pendingDetourRef by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(nodeId, createProtocol, nodes, allNodes) {
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

    fun resolveNodeByStoredValue(value: String?) = run {
        if (value.isNullOrBlank()) return@run null
        val parts = value.split("::", limit = 2)
        if (parts.size == 2) {
            val profileId = parts[0]
            val name = parts[1]
            return@run allNodes.find { it.sourceProfileId == profileId && it.name == name }
        }
        allNodes.find { it.id == value } ?: allNodes.find { it.name == value }
    }

    fun toNodeRef(sourceProfileId: String, name: String): String = "$sourceProfileId::$name"

    val createdMsg = stringResource(R.string.node_created)
    if (showSelectProfileDialog) {
        SelectProfileDialog(
            profiles = profiles,
            onConfirm = { target ->
                val outbound = editingOutbound
                if (outbound != null) {
                    showSelectProfileDialog = false
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                when (target) {
                                    is SelectProfileTarget.ExistingProfile -> {
                                        configRepository.createNode(outbound, targetProfileId = target.profileId)
                                    }
                                    is SelectProfileTarget.NewProfile -> {
                                        configRepository.createNode(outbound, newProfileName = target.profileName)
                                    }
                                }
                            }
                        }.onSuccess {
                            AppNotificationManager.showMessage(context, createdMsg)
                            navController.popBackStack()
                        }.onFailure {
                            AppNotificationManager.showMessage(
                                context,
                                context.getString(R.string.profiles_import_failed, it.message ?: "")
                            )
                        }
                    }
                } else {
                    showSelectProfileDialog = false
                }
            },
            onDismiss = { showSelectProfileDialog = false }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                        val currentOutbound = editingOutbound
                        if (currentOutbound != null) {
                            if (isCreateMode) {
                                showSelectProfileDialog = true
                            } else {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            configRepository.updateNode(nodeId, currentOutbound)
                                        }
                                    }.onSuccess {
                                        AppNotificationManager.showMessage(context, savedMsg)
                                        navController.popBackStack()
                                    }.onFailure {
                                        AppNotificationManager.showMessage(
                                            context,
                                            context.getString(R.string.profiles_import_failed, it.message ?: "")
                                        )
                                    }
                                }
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            val outbound = editingOutbound
            if (outbound == null) {
                StandardCard {
                    SettingItem(title = stringResource(R.string.common_loading), value = "")
                }
            } else {
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
                            checked = outbound.disableSni == true,
                            icon = Icons.Rounded.Fingerprint,
                            onCheckedChange = { checked ->
                                val currentTls = outbound.tls ?: TlsConfig(enabled = true)
                                editingOutbound = outbound.copy(
                                    disableSni = if (checked) true else null,
                                    tls = currentTls.copy(
                                        enabled = true
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
                            value = if (outbound.quic == true || outbound.network == "quic") "quic" else "h2",
                            options = listOf("h2", "quic"),
                            icon = Icons.Rounded.SwapHoriz,
                            onValueChange = {
                                val useQuic = it == "quic"
                                editingOutbound = outbound.copy(
                                    network = if (useQuic) "quic" else "h2",
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
                            title = stringResource(R.string.node_detail_peer_public_key),
                            value = peer.publicKey ?: "",
                            icon = Icons.Rounded.Key,
                            onValueChange = {
                                val newPeer = peer.copy(publicKey = it)
                                editingOutbound = outbound.copy(peers = listOf(newPeer))
                            }
                        )
                        EditableTextItem(
                            title = stringResource(R.string.node_detail_pre_shared_key),
                            value = peer.preSharedKey ?: "",
                            icon = Icons.Rounded.Key,
                            onValueChange = {
                                val newPeer = peer.copy(preSharedKey = if (it.isEmpty()) null else it)
                                editingOutbound = outbound.copy(peers = listOf(newPeer))
                            }
                        )
                        EditableTextItem(
                            title = stringResource(R.string.node_detail_local_address),
                            value = outbound.localAddress?.joinToString(", ") ?: "",
                            icon = Icons.Rounded.Dns,
                            onValueChange = {
                                val list = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                                editingOutbound = outbound.copy(localAddress = list)
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
                            value = outbound.version?.toString() ?: "5",
                            options = listOf("4", "4a", "5"),
                            icon = Icons.Rounded.Tag,
                            onValueChange = { editingOutbound = outbound.copy(version = it.replace("a", "").toIntOrNull()) }
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
                                val newObfs = if (it.isEmpty()) null else (outbound.obfs?.copy(type = it) ?: ObfsConfig(type = it))
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
                            options = listOf("tcp", "http", "ws", "grpc", "quic", "httpupgrade", "xhttp"),
                            icon = Icons.Rounded.SwapHoriz,
                            onValueChange = { newType ->
                                editingOutbound = outbound.copy(
                                    transport = updateTransportTypeForEditor(transport, newType)
                                )
                            }
                        )

                        if (currentType == "ws") {
                            Spacer(modifier = Modifier.height(8.dp))
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_ws_host),
                                value = resolveWebSocketHostTextForEditor(transport),
                                icon = Icons.Rounded.Language,
                                onValueChange = {
                                    editingOutbound = outbound.copy(
                                        transport = updateWebSocketTransportHostForEditor(transport, it)
                                    )
                                }
                            )
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_ws_path),
                                value = transport.path ?: "/",
                                icon = Icons.Rounded.Route,
                                onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(path = it)) }
                            )
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_max_early_data),
                                value = transport.maxEarlyData?.toString() ?: "",
                                icon = Icons.Rounded.CompareArrows,
                                onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(maxEarlyData = it.toIntOrNull())) }
                            )
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_early_data_header),
                                value = transport.earlyDataHeaderName ?: "",
                                icon = Icons.Rounded.Title,
                                onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(earlyDataHeaderName = if (it.isEmpty()) null else it)) }
                            )
                        }

                        if (currentType == "grpc") {
                            Spacer(modifier = Modifier.height(8.dp))
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_service_name),
                                value = transport.serviceName ?: "",
                                icon = Icons.Rounded.Tag,
                                onValueChange = { editingOutbound = outbound.copy(transport = transport.copy(serviceName = it)) }
                            )
                        }

                        val pathBasedTypes = setOf("http", "h2", "httpupgrade", "xhttp")
                        if (currentType in pathBasedTypes) {
                            Spacer(modifier = Modifier.height(8.dp))
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_transport_path),
                                value = transport.path ?: "/",
                                icon = Icons.Rounded.Route,
                                onValueChange = {
                                    editingOutbound = outbound.copy(
                                        transport = transport.copy(path = it)
                                    )
                                }
                            )
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_host),
                                value = resolveTransportHostTextForEditor(transport),
                                icon = Icons.Rounded.Language,
                                onValueChange = {
                                    editingOutbound = outbound.copy(
                                        transport = updatePathBasedTransportHostForEditor(transport, it)
                                    )
                                }
                            )
                        }

                        if (currentType == "xhttp") {
                            Spacer(modifier = Modifier.height(8.dp))
                            EditableSelectionItem(
                                title = stringResource(R.string.node_detail_xhttp_mode),
                                value = transport.mode ?: "auto",
                                options = listOf("auto", "packet-up", "stream-up"),
                                icon = Icons.Rounded.Tune,
                                onValueChange = {
                                    editingOutbound = outbound.copy(
                                        transport = transport.copy(mode = it)
                                    )
                                }
                            )
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_xpadding_bytes),
                                value = transport.xPaddingBytes ?: "",
                                icon = Icons.Rounded.CompareArrows,
                                onValueChange = {
                                    editingOutbound = outbound.copy(
                                        transport = transport.copy(xPaddingBytes = if (it.isEmpty()) null else it)
                                    )
                                }
                            )
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_sc_max_each_post_bytes),
                                value = transport.scMaxEachPostBytes?.toString() ?: "",
                                icon = Icons.Rounded.Numbers,
                                onValueChange = {
                                    editingOutbound = outbound.copy(
                                        transport = transport.copy(scMaxEachPostBytes = it.toLongOrNull())
                                    )
                                }
                            )
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_sc_min_posts_interval_ms),
                                value = transport.scMinPostsIntervalMs?.toString() ?: "",
                                icon = Icons.Rounded.Numbers,
                                onValueChange = {
                                    editingOutbound = outbound.copy(
                                        transport = transport.copy(scMinPostsIntervalMs = it.toLongOrNull())
                                    )
                                }
                            )
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_sc_max_buffered_posts),
                                value = transport.scMaxBufferedPosts?.toString() ?: "",
                                icon = Icons.Rounded.Numbers,
                                onValueChange = {
                                    editingOutbound = outbound.copy(
                                        transport = transport.copy(scMaxBufferedPosts = it.toLongOrNull())
                                    )
                                }
                            )
                            SettingSwitchItem(
                                title = stringResource(R.string.node_detail_no_grpc_header),
                                checked = transport.noGRPCHeader == true,
                                icon = Icons.Rounded.Merge,
                                onCheckedChange = {
                                    editingOutbound = outbound.copy(transport = transport.copy(noGRPCHeader = it))
                                }
                            )
                            SettingSwitchItem(
                                title = stringResource(R.string.node_detail_no_sse_header),
                                checked = transport.noSSEHeader == true,
                                icon = Icons.Rounded.Merge,
                                onCheckedChange = {
                                    editingOutbound = outbound.copy(transport = transport.copy(noSSEHeader = it))
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- TLS ---
                if (type !in listOf("wireguard", "ssh", "shadowsocks")) {
                    SectionHeader(stringResource(R.string.node_detail_tls_settings))
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
                                title = stringResource(R.string.node_detail_sni),
                                value = tls.serverName ?: "",
                                icon = Icons.Rounded.Dns,
                                onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(serverName = it)) }
                            )

                            EditableTextItem(
                                title = stringResource(R.string.node_detail_alpn),
                                value = tls.alpn?.joinToString(", ") ?: "",
                                icon = Icons.Rounded.Merge,
                                onValueChange = {
                                    val alpnList = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                                    editingOutbound = outbound.copy(tls = tls.copy(alpn = alpnList))
                                }
                            )

                            SettingSwitchItem(
                                title = stringResource(R.string.node_detail_allow_insecure),
                                subtitle = stringResource(R.string.node_detail_allow_insecure_subtitle),
                                checked = tls.insecure == true,
                                icon = Icons.Rounded.Lock,
                                onCheckedChange = { editingOutbound = outbound.copy(tls = tls.copy(insecure = it)) }
                            )
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_ca_cert),
                                value = tls.ca ?: "",
                                icon = Icons.Rounded.Security,
                                onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(ca = if (it.isEmpty()) null else it)) }
                            )

                            EditableTextItem(
                                title = stringResource(R.string.node_detail_client_cert),
                                value = tls.certificate ?: "",
                                icon = Icons.Rounded.Security,
                                onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(certificate = if (it.isEmpty()) null else it)) }
                            )

                            EditableTextItem(
                                title = stringResource(R.string.node_detail_client_key),
                                value = tls.key ?: "",
                                icon = Icons.Rounded.Key,
                                onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(key = if (it.isEmpty()) null else it)) }
                            )

                            // uTLS
                            Spacer(modifier = Modifier.height(8.dp))
                            EditableSelectionItem(
                                title = stringResource(R.string.node_detail_utls_fingerprint),
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
                                    title = stringResource(R.string.node_detail_reality_public_key),
                                    value = reality.publicKey ?: "",
                                    icon = Icons.Rounded.Key,
                                    onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(reality = reality.copy(publicKey = it))) }
                                )
                                EditableTextItem(
                                    title = stringResource(R.string.node_detail_reality_short_id),
                                    value = reality.shortId ?: "",
                                    icon = Icons.Rounded.Tag,
                                    onValueChange = { editingOutbound = outbound.copy(tls = tls.copy(reality = reality.copy(shortId = it))) }
                                )
                                // Note: spiderX is Xray-core specific, not supported by sing-box
                            }

                            // ECH
                            val ech = tls.ech ?: EchConfig(enabled = false)
                            Spacer(modifier = Modifier.height(8.dp))
                            SettingSwitchItem(
                                title = stringResource(R.string.node_detail_enable_ech),
                                checked = ech.enabled == true,
                                icon = Icons.Rounded.Security,
                                onCheckedChange = { enabled ->
                                    editingOutbound = outbound.copy(tls = tls.copy(ech = ech.copy(enabled = enabled)))
                                }
                            )
                            if (ech.enabled == true) {
                                EditableTextItem(
                                    title = stringResource(R.string.node_detail_ech_config),
                                    value = ech.config?.joinToString("\n") ?: "",
                                    icon = Icons.Rounded.Tune,
                                    onValueChange = {
                                        val configs = it.split("\n").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                                        editingOutbound = outbound.copy(tls = tls.copy(ech = ech.copy(config = configs)))
                                    }
                                )
                                EditableTextItem(
                                    title = stringResource(R.string.node_detail_ech_key),
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
                                title = stringResource(R.string.node_detail_mux_protocol),
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
                                title = stringResource(R.string.node_detail_min_streams),
                                value = mux.minStreams?.toString() ?: "",
                                icon = Icons.Rounded.Numbers,
                                onValueChange = { editingOutbound = outbound.copy(multiplex = mux.copy(minStreams = it.toIntOrNull())) }
                            )
                            EditableTextItem(
                                title = stringResource(R.string.node_detail_max_streams),
                                value = mux.maxStreams?.toString() ?: "",
                                icon = Icons.Rounded.Numbers,
                                onValueChange = { editingOutbound = outbound.copy(multiplex = mux.copy(maxStreams = it.toIntOrNull())) }
                            )
                            SettingSwitchItem(
                                title = stringResource(R.string.node_detail_padding),
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
                    val noneText = stringResource(R.string.common_none)
                    val selectedNode = resolveNodeByStoredValue(outbound.detour)
                    val detourSelectionText = when {
                        outbound.detour.isNullOrBlank() -> noneText
                        selectedNode != null -> {
                            val profileName = profiles.firstOrNull { it.id == selectedNode.sourceProfileId }?.name
                            if (profileName.isNullOrBlank()) {
                                selectedNode.name
                            } else {
                                "${selectedNode.name} ($profileName)"
                            }
                        }
                        else -> outbound.detour ?: noneText
                    }
                    val detourNodesForSelection = (allNodes.takeIf { it.isNotEmpty() } ?: nodes)
                        .filterNot {
                            it.name == outbound.tag &&
                                it.sourceProfileId == (node?.sourceProfileId ?: activeProfileId)
                        }

                    val selectedRef = selectedNode?.let { toNodeRef(it.sourceProfileId, it.name) }

                    SettingItem(
                        title = stringResource(R.string.node_detail_detour_proxy),
                        value = detourSelectionText,
                        subtitle = stringResource(R.string.node_detail_detour_proxy_subtitle),
                        icon = Icons.Rounded.Route,
                        onClick = {
                            pendingDetourRef = selectedRef
                            showDetourNodeDialog = true
                        }
                    )

                    if (showDetourNodeDialog) {
                        DetourNodeSelectDialog(
                            profiles = profiles,
                            nodesForSelection = detourNodesForSelection,
                            selectedNodeRef = pendingDetourRef,
                            onSelect = { ref -> pendingDetourRef = ref },
                            onConfirm = {
                                editingOutbound = outbound.copy(detour = pendingDetourRef)
                                showDetourNodeDialog = false
                            },
                            onDismiss = { showDetourNodeDialog = false }
                        )
                    }

                    EditableTextItem(
                        title = stringResource(R.string.node_detail_detour_tag),
                        value = outbound.detour ?: "",
                        icon = Icons.Rounded.Route,
                        subtitle = stringResource(R.string.node_detail_detour_tag_subtitle),
                        onValueChange = { editingOutbound = outbound.copy(detour = if (it.isEmpty()) null else it) }
                    )
                    SettingSwitchItem(
                        title = stringResource(R.string.node_detail_tcp_fast_open),
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

@Suppress("LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
private fun DetourNodeSelectDialog(
    profiles: List<com.kunk.singbox.model.ProfileUi>,
    nodesForSelection: List<com.kunk.singbox.model.NodeUi>,
    selectedNodeRef: String?,
    onSelect: (String?) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    fun toNodeRef(node: com.kunk.singbox.model.NodeUi): String = "${node.sourceProfileId}::${node.name}"

    val groupedNodes = remember(nodesForSelection, profiles) {
        val profileNameMap = profiles.associate { it.id to it.name }
        nodesForSelection
            .groupBy { it.sourceProfileId }
            .toList()
            .sortedBy { (profileId, _) -> profileNameMap[profileId] ?: profileId }
    }
    var expandedProfileId by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.node_detail_select_detour_node),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.height(460.dp)) {
                item {
                    val isNoneSelected = selectedNodeRef == null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(null) }
                            .background(
                                if (isNoneSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isNoneSelected) {
                                Icons.Rounded.RadioButtonChecked
                            } else {
                                Icons.Rounded.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            tint = if (isNoneSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = stringResource(R.string.common_none), color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                groupedNodes.forEach { (profileId, profileNodes) ->
                    val profileName = profiles.firstOrNull { it.id == profileId }?.name
                    val isExpanded = expandedProfileId == profileId

                    item(key = "group_$profileId") {
                        val profileTitle = profileName
                            ?: stringResource(R.string.node_detail_unknown_profile, profileId)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    RoundedCornerShape(10.dp)
                                )
                                .animateContentSize(animationSpec = tween(durationMillis = 220))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedProfileId = if (isExpanded) null else profileId
                                    }
                                    .padding(vertical = 10.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$profileTitle (${profileNodes.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = if (isExpanded) {
                                        Icons.Rounded.ExpandLess
                                    } else {
                                        Icons.Rounded.ExpandMore
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = fadeIn(animationSpec = tween(180)),
                                exit = fadeOut(animationSpec = tween(120))
                            ) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp)
                                ) {
                                    items(profileNodes, key = { it.id }) { detourNode ->
                                        val ref = toNodeRef(detourNode)
                                        val selected = selectedNodeRef == ref
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onSelect(ref) }
                                                .background(
                                                    if (selected) {
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                                    } else {
                                                        MaterialTheme.colorScheme.surface
                                                    },
                                                    RoundedCornerShape(10.dp)
                                                )
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (selected) {
                                                    Icons.Rounded.RadioButtonChecked
                                                } else {
                                                    Icons.Rounded.RadioButtonUnchecked
                                                },
                                                contentDescription = null,
                                                tint = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(text = detourNode.name, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Text(stringResource(R.string.common_cancel))
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text(
                        text = stringResource(R.string.common_ok),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun createEmptyOutbound(protocol: String): Outbound {
    val defaultPort = when (protocol) {
        "shadowsocks" -> 8388
        "vmess", "vless" -> 443
        "trojan" -> 443
        "hysteria2", "hysteria" -> 443
        "tuic" -> 443
        "naive" -> 443
        "anytls" -> 443
        "ssh" -> 22
        "socks" -> 1080
        "http" -> 8080
        "wireguard" -> 51820
        else -> 443
    }

    val needsTls = protocol in listOf("vless", "trojan", "hysteria2", "hysteria", "tuic", "naive", "anytls")

    return Outbound(
        type = protocol,
        tag = "New-${protocol.uppercase()}",
        server = "",
        serverPort = defaultPort,
        network = if (protocol == "naive") "h2" else null,
        quic = if (protocol == "naive") false else null,
        tls = if (needsTls) TlsConfig(enabled = true) else null
    )
}

private fun formatHeaderLines(headers: Map<String, String>?): String {
    return headers
        ?.entries
        ?.sortedBy { it.key.lowercase() }
        ?.joinToString("\n") { (key, value) -> "$key: $value" }
        .orEmpty()
}

private fun parseHeaderLines(text: String): Map<String, String>? {
    val parsed = linkedMapOf<String, String>()
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { line ->
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) return@forEach

            val key = line.substring(0, separatorIndex).trim()
            val value = line.substring(separatorIndex + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                parsed[key] = value
            }
        }

    return parsed.ifEmpty { null }
}
