package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.gson.Gson
import com.kunk.singbox.model.EchConfig
import com.kunk.singbox.model.MultiplexConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.ui.components.AppNotificationManager
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
import java.util.Locale
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarColors

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

    val nodes by configRepository.nodes.collectAsStateWithLifecycle(initialValue = emptyList())
    val allNodes by configRepository.allNodes.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeProfileId by configRepository.activeProfileId.collectAsStateWithLifecycle(initialValue = null)
    val node = if (!isCreateMode) nodes.find { it.id == nodeId } else null
    val profiles by configRepository.profiles.collectAsStateWithLifecycle(initialValue = emptyList())

    val editingOutboundState = rememberSaveable(nodeId, createProtocol, saver = outboundEditorStateSaver) {
        mutableStateOf<Outbound?>(null)
    }
    var editingOutbound by editingOutboundState
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
    val importFailedFormat = stringResource(R.string.profiles_import_failed, "%s")
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
                                String.format(Locale.getDefault(), importFailedFormat, it.message ?: "")
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
        containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background),
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
                    IconButton(
                        modifier = Modifier.liquidGlassIconButtonPanel(),
                        onClick = { navController.popBackStack() }
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    val savedMsg = stringResource(R.string.node_detail_saved)
                    IconButton(
                        modifier = Modifier.liquidGlassIconButtonPanel(
                            enabled = editingOutbound != null
                        ),
                        onClick = {
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
                                                String.format(Locale.getDefault(), importFailedFormat, it.message ?: "")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.common_save), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = liquidGlassTopAppBarColors(defaultContainerColor = MaterialTheme.colorScheme.background)
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

                    NodeProtocolFields(
                        type = type,
                        outbound = outbound,
                        editingOutboundState = editingOutboundState
                    )
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
