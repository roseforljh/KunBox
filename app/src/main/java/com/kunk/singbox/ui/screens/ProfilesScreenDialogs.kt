package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassButtonContainerColor
import com.kunk.singbox.ui.theme.liquidGlassButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassCheckboxColors
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassProgressColor
import com.kunk.singbox.ui.theme.liquidGlassProgressTrackColor
import com.kunk.singbox.ui.theme.liquidGlassSwitchColors
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel
import com.kunk.singbox.ui.theme.liquidGlassTextButtonPanel

@Composable
private fun Modifier.profileDialogPanel(shape: RoundedCornerShape): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 24.dp)
    } else {
        background(MaterialTheme.colorScheme.surface, shape)
    }
}

@Composable
private fun Modifier.profileGroupPanel(shape: RoundedCornerShape = RoundedCornerShape(12.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 6.dp)
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    }
}

@Composable
private fun Modifier.profileDnsMenuPanel(): Modifier {
    val shape = RoundedCornerShape(24.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 24.dp)
    } else {
        background(MaterialTheme.colorScheme.surface, shape)
    }
}

@Composable
private fun Modifier.profileDnsOptionPanel(isSelected: Boolean): Modifier {
    return if (isLiquidGlassTheme() && isSelected) {
        liquidGlassPanel(shape = RoundedCornerShape(12.dp), selected = true, shadowElevation = 4.dp)
    } else {
        background(
            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent
        )
    }
}

@Composable
private fun Modifier.profileCustomNodePanel(isSelected: Boolean): Modifier {
    val shape = RoundedCornerShape(10.dp)
    return if (isLiquidGlassTheme() && isSelected) {
        liquidGlassPanel(shape = shape, selected = true, shadowElevation = 4.dp)
    } else {
        background(
            if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            } else {
                Color.Transparent
            },
            shape
        )
    }
}

internal fun List<String>.updatedCustomSelection(nodeId: String, checked: Boolean): List<String> {
    return if (checked) {
        if (contains(nodeId)) {
            this
        } else {
            this + nodeId
        }
    } else {
        filterNot { it == nodeId }
    }
}

@Composable
internal fun CustomConfigNodeList(
    nodes: List<NodeUi>,
    profileNames: Map<String, String>,
    selectedNodeIds: List<String>,
    onSelectionChange: (String, Boolean) -> Unit
) {
    val nodesByProfile = remember(nodes) { nodes.groupBy { it.sourceProfileId } }
    val sortedProfileIds = remember(nodesByProfile, profileNames) {
        nodesByProfile.keys.sortedBy { profileNames[it] ?: it }
    }
    var expandedProfileId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.72f)
    ) {
        sortedProfileIds.forEach { profileId ->
            val nodesForProfile = nodesByProfile[profileId].orEmpty()
            val isExpanded = expandedProfileId == profileId
            val profileName = profileNames[profileId] ?: "未知订阅"

            item(key = "profile_$profileId") {
                ExpandableProfileGroup(
                    profileName = profileName,
                    nodeCount = nodesForProfile.size,
                    isExpanded = isExpanded,
                    onToggle = { expandedProfileId = if (isExpanded) null else profileId },
                    nodes = nodesForProfile,
                    selectedNodeIds = selectedNodeIds,
                    onSelectionChange = onSelectionChange
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Suppress("LongMethod", "LongParameterList", "CognitiveComplexMethod")
@Composable
internal fun ExpandableProfileGroup(
    profileName: String,
    nodeCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    nodes: List<NodeUi>,
    selectedNodeIds: List<String>,
    onSelectionChange: (String, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .profileGroupPanel()
            .animateContentSize(animationSpec = tween(durationMillis = 220))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = nodes.isNotEmpty(), onClick = onToggle)
                .padding(vertical = 12.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profileName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (nodes.isNotEmpty()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.rulesets_nodes_count, nodeCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = if (nodes.isNotEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
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
                    .heightIn(max = 260.dp)
            ) {
                items(nodes, key = { it.id }) { node ->
                    val isSelected = selectedNodeIds.contains(node.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .profileCustomNodePanel(isSelected)
                            .clickable {
                                onSelectionChange(node.id, !isSelected)
                            }
                            .padding(vertical = 10.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                onSelectionChange(node.id, checked)
                            },
                            colors = liquidGlassCheckboxColors()
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = node.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = node.group,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
internal fun CustomConfigDialog(
    nodes: List<NodeUi>,
    profileNames: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var selectedNodeIds by rememberSaveable { mutableStateOf(emptyList<String>()) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .profileDialogPanel(RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            Text(
                text = "自定义配置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            val nameFieldShape = RoundedCornerShape(16.dp)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassTextFieldPanel(shape = nameFieldShape),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "请输入配置名称",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                shape = nameFieldShape,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                    unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                    focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (nodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无可用订阅节点",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                CustomConfigNodeList(
                    nodes = nodes,
                    profileNames = profileNames,
                    selectedNodeIds = selectedNodeIds,
                    onSelectionChange = { nodeId, checked ->
                        selectedNodeIds = selectedNodeIds.updatedCustomSelection(nodeId, checked)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { onConfirm(name.trim(), selectedNodeIds.toList()) },
                enabled = name.isNotBlank() && selectedNodeIds.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = liquidGlassButtonContainerColor(MaterialTheme.colorScheme.primary),
                    contentColor = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                ),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text(
                    text = stringResource(R.string.common_ok),
                    fontWeight = FontWeight.Bold,
                    color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassTextButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

internal enum class ProfileImportType { Subscription, File, Clipboard, QRCode, Custom }

@Composable
internal fun ImportSelectionDialog(
    onDismiss: () -> Unit,
    onTypeSelected: (ProfileImportType) -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ImportOptionCard(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.profiles_subscription_link),
                subtitle = stringResource(R.string.common_import),
                onClick = { onTypeSelected(ProfileImportType.Subscription) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.Description,
                title = stringResource(R.string.profiles_local_file),
                subtitle = stringResource(R.string.profiles_local_file_subtitle),
                onClick = { onTypeSelected(ProfileImportType.File) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.ContentPaste,
                title = stringResource(R.string.profiles_clipboard),
                subtitle = stringResource(R.string.profiles_clipboard_subtitle),
                onClick = { onTypeSelected(ProfileImportType.Clipboard) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.QrCodeScanner,
                title = stringResource(R.string.profiles_scan_qrcode),
                subtitle = stringResource(R.string.profiles_scan_qrcode_subtitle),
                onClick = { onTypeSelected(ProfileImportType.QRCode) }
            )
            ImportOptionCard(
                icon = Icons.Rounded.DashboardCustomize,
                title = "自定义配置",
                subtitle = "从现有订阅选择节点组合",
                onClick = { onTypeSelected(ProfileImportType.Custom) }
            )
        }
    }
}

@Composable
internal fun ImportLoadingDialog(message: String, onCancel: () -> Unit = {}) {
    var displayMessage = message
    val progress = remember(message) {
        val regex = Regex(".*?\\((\\d+)/(\\d+)\\).*")
        val match = regex.find(message)
        if (match != null) {
            val (current, total) = match.destructured
            val totalFloat = total.toFloat()
            if (totalFloat > 0) {
                current.toFloat() / totalFloat
            } else {
                null
            }
        } else {
            null
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .profileDialogPanel(RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (progress != null) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = liquidGlassProgressColor(MaterialTheme.colorScheme.primary),
                    trackColor = liquidGlassProgressTrackColor(MaterialTheme.colorScheme.outline),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            } else {
                androidx.compose.material3.CircularProgressIndicator(
                    color = liquidGlassProgressColor(MaterialTheme.colorScheme.primary)
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.End)
                    .liquidGlassTextButtonPanel()
            ) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
internal fun ImportOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    StandardCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
@Composable
internal fun SubscriptionInputDialog(
    initialName: String = "",
    initialUrl: String = "",
    initialAutoUpdateInterval: Int = 0,
    initialDnsPreResolve: Boolean = false,
    initialDnsServer: String? = null,
    initialDnsOverride: String? = null,
    title: String = stringResource(R.string.profiles_add_subscription),
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        url: String,
        autoUpdateInterval: Int,
        dnsPreResolve: Boolean,
        dnsServer: String?,
        dnsOverride: String?
    ) -> Unit
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var autoUpdateEnabled by rememberSaveable(initialAutoUpdateInterval) {
        mutableStateOf(initialAutoUpdateInterval > 0)
    }
    var autoUpdateMinutes by rememberSaveable(initialAutoUpdateInterval) {
        mutableStateOf(
            if (initialAutoUpdateInterval > 0) {
                initialAutoUpdateInterval.toString()
            } else {
                "60"
            }
        )
    }
    var dnsPreResolveEnabled by rememberSaveable(initialDnsPreResolve) {
        mutableStateOf(initialDnsPreResolve)
    }
    var selectedDnsServer by rememberSaveable(initialDnsServer) {
        mutableStateOf(initialDnsServer ?: "https://cloudflare-dns.com/dns-query")
    }
    var dnsDropdownExpanded by remember { mutableStateOf(false) }
    var dnsOverrideText by rememberSaveable(initialDnsOverride) {
        mutableStateOf(initialDnsOverride ?: "")
    }
    var showDnsOverride by rememberSaveable(initialDnsOverride) {
        mutableStateOf(!initialDnsOverride.isNullOrBlank())
    }

    val dnsServerOptions = listOf(
        "https://cloudflare-dns.com/dns-query" to stringResource(R.string.profiles_dns_server_cloudflare),
        "https://dns.google/dns-query" to stringResource(R.string.profiles_dns_server_google),
        "https://dns.alidns.com/dns-query" to stringResource(R.string.profiles_dns_server_alidns)
    )

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .profileDialogPanel(RoundedCornerShape(28.dp))
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            val nameFieldShape = RoundedCornerShape(16.dp)
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.profiles_name_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassTextFieldPanel(shape = nameFieldShape),
                singleLine = true,
                shape = nameFieldShape,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                    unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                    focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            val urlFieldShape = RoundedCornerShape(16.dp)
            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.profiles_url_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassTextFieldPanel(shape = urlFieldShape),
                singleLine = true,
                shape = urlFieldShape,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                    unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                    focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.profiles_auto_update),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                androidx.compose.material3.Switch(
                    checked = autoUpdateEnabled,
                    onCheckedChange = { autoUpdateEnabled = it },
                    colors = liquidGlassSwitchColors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            AnimatedVisibility(
                visible = autoUpdateEnabled,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 300)
                ),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 300)
                )
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    val autoUpdateFieldShape = RoundedCornerShape(16.dp)
                    androidx.compose.material3.OutlinedTextField(
                        value = autoUpdateMinutes,
                        onValueChange = { newValue ->

                            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                autoUpdateMinutes = newValue
                            }
                        },
                        label = { Text(stringResource(R.string.profiles_auto_update_interval)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlassTextFieldPanel(shape = autoUpdateFieldShape),
                        shape = autoUpdateFieldShape,
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        supportingText = {
                            Text(
                                text = stringResource(R.string.profiles_auto_update_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                            unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                            focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.profiles_dns_preresolve),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.profiles_dns_preresolve_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = dnsPreResolveEnabled,
                    onCheckedChange = { dnsPreResolveEnabled = it },
                    colors = liquidGlassSwitchColors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            AnimatedVisibility(
                visible = dnsPreResolveEnabled,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 300)
                ),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 300)
                )
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    val dnsFieldShape = RoundedCornerShape(16.dp)
                    androidx.compose.material3.OutlinedTextField(
                        value = dnsServerOptions.find { it.first == selectedDnsServer }?.second ?: selectedDnsServer,
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text(stringResource(R.string.profiles_dns_server)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlassTextFieldPanel(shape = dnsFieldShape)
                            .clickable { dnsDropdownExpanded = true },
                        shape = dnsFieldShape,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                            disabledContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            if (dnsDropdownExpanded) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { dnsDropdownExpanded = false }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .profileDnsMenuPanel()
                            .padding(vertical = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.profiles_dns_server),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        dnsServerOptions.forEach { (url, label) ->
                            val isSelected = selectedDnsServer == url
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedDnsServer = url
                                        dnsDropdownExpanded = false
                                    }
                                    .profileDnsOptionPanel(isSelected)
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.profiles_dns_override),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                androidx.compose.material3.Switch(
                    checked = showDnsOverride,
                    onCheckedChange = { showDnsOverride = it },
                    colors = liquidGlassSwitchColors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            androidx.compose.animation.AnimatedVisibility(visible = showDnsOverride) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.profiles_dns_override_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val dnsOverrideFieldShape = RoundedCornerShape(16.dp)
                    androidx.compose.material3.OutlinedTextField(
                        value = dnsOverrideText,
                        onValueChange = { dnsOverrideText = it },
                        label = { Text("DNS JSON") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .liquidGlassTextFieldPanel(shape = dnsOverrideFieldShape),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        shape = dnsOverrideFieldShape,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                            unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                            focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            val context = LocalContext.current

            Button(
                onClick = {

                    val isNodeLink = url.trim().let {
                        it.startsWith("vmess://") || it.startsWith("vless://") ||
                            it.startsWith("ss://") || it.startsWith("ssr://") ||
                            it.startsWith("trojan://") || it.startsWith("hysteria://") ||
                            it.startsWith("hysteria2://") || it.startsWith("hy2://") ||
                            it.startsWith("tuic://") || it.startsWith("bean://") ||
                            it.startsWith("wireguard://") || it.startsWith("ssh://") ||
                            it.startsWith("naive://") || it.startsWith("naive+https://")
                    }

                    if (isNodeLink) {
                        AppNotificationManager.showMessage(
                            context = context,
                            message = context.getString(R.string.profiles_subscription_node_warning),
                            duration = androidx.compose.material3.SnackbarDuration.Long
                        )
                        return@Button
                    }

                    if (name.contains("://")) {
                        AppNotificationManager.showMessage(context, context.getString(R.string.profiles_name_invalid))
                        return@Button
                    }

                    val finalInterval = if (autoUpdateEnabled) {
                        val minutes = autoUpdateMinutes.toIntOrNull() ?: 0
                        if (minutes < 15) {
                            AppNotificationManager.showMessage(
                                context,
                                context.getString(R.string.settings_update_interval_min)
                            )
                            return@Button
                        }
                        minutes
                    } else {
                        0
                    }

                    val finalDnsOverride = if (showDnsOverride && dnsOverrideText.isNotBlank()) {
                        dnsOverrideText
                    } else {
                        null
                    }
                    ConfigRepository.buildDnsOverrideCompatibilityWarning(finalDnsOverride)?.let { warning ->
                        AppNotificationManager.showMessage(
                            context = context,
                            message = warning,
                            duration = androidx.compose.material3.SnackbarDuration.Long
                        )
                    }

                    onConfirm(
                        name,
                        url,
                        finalInterval,
                        dnsPreResolveEnabled,
                        if (dnsPreResolveEnabled) selectedDnsServer else null,
                        finalDnsOverride
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = liquidGlassButtonContainerColor(MaterialTheme.colorScheme.primary),
                    contentColor = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                ),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text(
                    text = stringResource(R.string.common_ok),
                    fontWeight = FontWeight.Bold,
                    color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassTextButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}
