package com.kunk.singbox.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kunk.singbox.R
import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.EditableSelectionItem
import com.kunk.singbox.ui.components.EditableTextItem
import com.kunk.singbox.ui.components.InputDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import java.net.URI

internal fun parseHysteria2RealmForEditor(text: String): JsonObject {
    val element = try {
        JsonParser.parseString(text)
    } catch (_: com.google.gson.JsonParseException) {
        throw IllegalArgumentException("Realm: invalid JSON")
    }
    require(element.isJsonObject) { "Realm: expected a JSON object" }
    val realm = element.asJsonObject
    val url = realm.get("server_url")
    require(url?.isJsonPrimitive == true && url.asJsonPrimitive.isString) { "server_url: required string" }
    val uri = runCatching { URI(url.asString) }.getOrNull()
    require(uri?.scheme?.lowercase() in listOf("http", "https") && !uri?.host.isNullOrBlank()) {
        "server_url: expected an HTTP(S) URL"
    }
    val id = realm.get("realm_id")
    require(id?.isJsonPrimitive == true && id.asJsonPrimitive.isString && id.asString.isNotBlank()) {
        "realm_id: required string"
    }
    val stun = realm.get("stun_servers")
    val servers = when {
        stun?.isJsonArray == true -> stun.asJsonArray.toList()
        stun?.isJsonPrimitive == true -> listOf(stun)
        else -> emptyList()
    }
    require(servers.isNotEmpty() && servers.all {
        it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank()
    }) { "stun_servers: expected a server string or non-empty string array" }
    return realm
}

internal fun applyHysteria2RealmForEditor(outbound: Outbound, realm: JsonObject): Outbound = outbound.copy(
    realm = realm.deepCopy(), server = null, serverPort = null, serverPorts = null,
    hopInterval = null, hopIntervalMax = null
)

internal fun removeHysteria2RealmForEditor(outbound: Outbound): Outbound = outbound.copy(
    realm = null, serverPort = 443
)

@Composable
@Suppress("LongMethod")
internal fun NodeHysteria2Fields(outbound: Outbound, onChange: (Outbound) -> Unit) {
    EditableTextItem(
        title = stringResource(R.string.node_detail_password), value = outbound.password.orEmpty(),
        icon = Icons.Rounded.Password, onValueChange = { onChange(outbound.copy(password = it)) }
    )
    Hysteria2RealmField(outbound, onChange)
    if (outbound.realm == null) {
        EditableTextItem(
            title = stringResource(R.string.node_detail_ports_jumping),
            value = outbound.serverPorts?.joinToString(",").orEmpty(), icon = Icons.Rounded.Numbers,
            onValueChange = { text ->
                val ports = text.split(',').map(String::trim).filter(String::isNotEmpty)
                onChange(outbound.copy(serverPorts = ports.takeIf(List<String>::isNotEmpty)))
            }
        )
        EditableTextItem(
            title = stringResource(R.string.node_hy2_hop_min), value = outbound.hopInterval.orEmpty(),
            icon = Icons.Rounded.SwapHoriz,
            onValueChange = { onChange(outbound.copy(hopInterval = it.trim().ifEmpty { null })) }
        )
        EditableTextItem(
            title = stringResource(R.string.node_hy2_hop_max), value = outbound.hopIntervalMax.orEmpty(),
            icon = Icons.Rounded.SwapHoriz,
            onValueChange = { onChange(outbound.copy(hopIntervalMax = it.trim().ifEmpty { null })) }
        )
    }
    Hysteria2ObfsFields(outbound, onChange)
    EditableTextItem(
        title = stringResource(R.string.node_detail_upload_speed), value = outbound.upMbps?.toString().orEmpty(),
        icon = Icons.Rounded.Speed, onValueChange = { onChange(outbound.copy(upMbps = it.toIntOrNull())) }
    )
    EditableTextItem(
        title = stringResource(R.string.node_detail_download_speed), value = outbound.downMbps?.toString().orEmpty(),
        icon = Icons.Rounded.Speed, onValueChange = { onChange(outbound.copy(downMbps = it.toIntOrNull())) }
    )
    EditableSelectionItem(
        title = stringResource(R.string.node_hy2_bbr_profile), value = outbound.bbrProfile ?: "standard",
        options = listOf("standard", "conservative", "aggressive"), icon = Icons.Rounded.Speed,
        onValueChange = { onChange(outbound.copy(bbrProfile = it)) }
    )
    SettingSwitchItem(
        title = stringResource(R.string.node_hy2_chrome_parrot), checked = outbound.disableChromeParrot != true,
        onCheckedChange = { onChange(outbound.copy(disableChromeParrot = !it)) }
    )
}

@Composable
private fun Hysteria2ObfsFields(outbound: Outbound, onChange: (Outbound) -> Unit) {
    val obfs = outbound.obfs
    EditableSelectionItem(
        title = stringResource(R.string.node_detail_obfs_type), value = obfs?.type.orEmpty(),
        options = listOf("", "salamander", "gecko"), icon = Icons.Rounded.Lock,
        onValueChange = {
            onChange(outbound.copy(obfs = it.takeIf(String::isNotEmpty)?.let { type ->
                (obfs ?: ObfsConfig()).copy(
                    type = type, stringValue = false,
                    minPacketSize = obfs?.minPacketSize.takeIf { type == "gecko" },
                    maxPacketSize = obfs?.maxPacketSize.takeIf { type == "gecko" }
                )
            }))
        }
    )
    if (obfs != null && obfs.type in listOf("salamander", "gecko")) {
        EditableTextItem(
            title = stringResource(R.string.node_detail_obfs_password), value = obfs.password.orEmpty(),
            icon = Icons.Rounded.Key, onValueChange = { onChange(outbound.copy(obfs = obfs.copy(password = it))) }
        )
        if (obfs.type == "gecko") {
            EditableTextItem(
                title = stringResource(R.string.node_hy2_packet_min), value = obfs.minPacketSize?.toString().orEmpty(),
                onValueChange = { onChange(outbound.copy(obfs = obfs.copy(minPacketSize = it.toIntOrNull()))) }
            )
            EditableTextItem(
                title = stringResource(R.string.node_hy2_packet_max), value = obfs.maxPacketSize?.toString().orEmpty(),
                onValueChange = { onChange(outbound.copy(obfs = obfs.copy(maxPacketSize = it.toIntOrNull()))) }
            )
        }
    }
}

@Composable
private fun Hysteria2RealmField(outbound: Outbound, onChange: (Outbound) -> Unit) {
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var removeRealm by rememberSaveable { mutableStateOf(false) }
    SettingSwitchItem(
        title = "Realm", checked = outbound.realm != null,
        onCheckedChange = { if (it) showEditor = true else removeRealm = true }
    )
    if (outbound.realm != null) {
        SettingItem(
            title = stringResource(R.string.node_hy2_realm_json), value = stringResource(R.string.common_edit),
            onClick = { showEditor = true }
        )
    }
    if (showEditor) {
        Hysteria2RealmEditor(outbound, onChange, onDismiss = { showEditor = false })
    }
    if (removeRealm) {
        ConfirmDialog(
            title = "Realm", message = stringResource(R.string.node_hy2_realm_remove),
            onConfirm = { onChange(removeHysteria2RealmForEditor(outbound)); removeRealm = false },
            onDismiss = { removeRealm = false }
        )
    }
}

@Composable
private fun Hysteria2RealmEditor(outbound: Outbound, onChange: (Outbound) -> Unit, onDismiss: () -> Unit) {
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    InputDialog(
        title = stringResource(R.string.node_hy2_realm_json), initialValue = outbound.realm?.toString() ?: "{}",
        singleLine = false, minLines = 4, maxLines = 8, errorMessage = error,
        onConfirm = { text ->
            runCatching { parseHysteria2RealmForEditor(text) }.onSuccess { realm ->
                error = null
                val clearsEndpoint = outbound.server != null || outbound.serverPort != null ||
                    !outbound.serverPorts.isNullOrEmpty() || outbound.hopInterval != null ||
                    outbound.hopIntervalMax != null
                if (clearsEndpoint) {
                    pending = realm.toString()
                } else {
                    onChange(applyHysteria2RealmForEditor(outbound, realm))
                    onDismiss()
                }
            }.onFailure { error = it.message }
        },
        onDismiss = onDismiss
    )
    val pendingJson = pending
    if (pendingJson != null) {
        ConfirmDialog(
            title = "Realm", message = stringResource(R.string.node_hy2_realm_replace),
            onConfirm = {
                onChange(applyHysteria2RealmForEditor(outbound, parseHysteria2RealmForEditor(pendingJson)))
                pending = null
                onDismiss()
            },
            onDismiss = { pending = null }
        )
    }
}
