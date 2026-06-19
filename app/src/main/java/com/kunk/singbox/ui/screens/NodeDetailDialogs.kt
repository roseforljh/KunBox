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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassPanel

@Composable
private fun Modifier.nodeDetailDialogPanel(shape: RoundedCornerShape = RoundedCornerShape(28.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 24.dp)
    } else {
        background(MaterialTheme.colorScheme.surface, shape)
    }
}

@Composable
private fun Modifier.nodeDetailSelectionPanel(isSelected: Boolean): Modifier {
    val shape = RoundedCornerShape(10.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = isSelected, shadowElevation = 5.dp)
    } else {
        background(
            if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            shape
        )
    }
}

@Composable
private fun Modifier.nodeDetailGroupPanel(): Modifier {
    val shape = RoundedCornerShape(10.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 6.dp)
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape)
    }
}

@Suppress("LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
internal fun DetourNodeSelectDialog(
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
                .nodeDetailDialogPanel()
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
                            .nodeDetailSelectionPanel(isNoneSelected)
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
                                .nodeDetailGroupPanel()
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
                                                .nodeDetailSelectionPanel(selected)
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

internal fun createEmptyOutbound(protocol: String): Outbound {
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

internal fun formatHeaderLines(headers: Map<String, String>?): String {
    return headers
        ?.entries
        ?.sortedBy { it.key.lowercase() }
        ?.joinToString("\n") { (key, value) -> "$key: $value" }
        .orEmpty()
}

internal fun parseHeaderLines(text: String): Map<String, String>? {
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
