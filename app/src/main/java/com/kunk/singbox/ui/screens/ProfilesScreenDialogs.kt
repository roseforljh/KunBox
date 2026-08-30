@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.repository.*
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.FullScreenDialogPage
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassCheckboxColors
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassDialogPanel
import com.kunk.singbox.ui.theme.liquidGlassPressFeedback
import com.kunk.singbox.ui.theme.liquidGlassOutlinedTextFieldColors
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel

@Composable
internal fun Modifier.profileDialogPanel(shape: RoundedCornerShape): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassDialogPanel(shape = shape, shadowElevation = 24.dp)
    } else {
        background(MaterialTheme.colorScheme.surface, shape)
    }
}

@Composable
internal fun Modifier.profileGroupPanel(shape: RoundedCornerShape = RoundedCornerShape(12.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 6.dp)
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    }
}

@Composable
internal fun ProfileLiquidGlassTextFieldLabel(text: String, useLiquidGlass: Boolean) {
    if (useLiquidGlass) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
    }
}

internal fun liquidGlassAwareTextFieldLabel(
    useLiquidGlass: Boolean,
    text: String
): (@Composable () -> Unit)? {
    if (useLiquidGlass) {
        return null
    }
    return { Text(text) }
}

@Composable
internal fun Modifier.profileCustomNodePanel(isSelected: Boolean): Modifier {
    val shape = RoundedCornerShape(10.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = isSelected, shadowElevation = 4.dp)
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

@Composable
internal fun Modifier.profileGroupPressFeedback(
    enabled: Boolean,
    onClick: () -> Unit
): Modifier = liquidGlassPressFeedback(
    enabled = enabled,
    label = "liquid_glass_profile_group_scale",
    onClick = onClick
)

@Composable
internal fun Modifier.profileCustomNodePressFeedback(onClick: () -> Unit): Modifier =
    liquidGlassPressFeedback(
        label = "liquid_glass_profile_custom_node_scale",
        onClick = onClick
    )

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

@Suppress("LongMethod", "CognitiveComplexMethod", "LongParameterList")
@Composable
internal fun CustomConfigPage(
    nodes: List<NodeUi>,
    profileNames: Map<String, String>,
    addedNodes: List<Outbound>,
    name: String,
    selectedNodeIds: List<String>,
    onNameChange: (String) -> Unit,
    onSelectedNodeIdsChange: (List<String>) -> Unit,
    onPasteNodeLink: () -> Unit,
    onManualAddNode: () -> Unit,
    onRemoveAddedNode: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit
) {
    var expandedProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var addedNodeToRemove by remember { mutableStateOf<Pair<Int, Outbound>?>(null) }
    val nodesByProfile = remember(nodes) { nodes.groupBy { it.sourceProfileId } }
    val sortedProfileIds = remember(nodesByProfile, profileNames) {
        nodesByProfile.keys.sortedBy { profileNames[it] ?: it }
    }
    val canSave = name.isNotBlank() && (selectedNodeIds.isNotEmpty() || addedNodes.isNotEmpty())

    addedNodeToRemove?.let { (index, outbound) ->
        ConfirmDialog(
            title = stringResource(R.string.common_delete),
            message = stringResource(R.string.common_delete_confirm, outbound.tag),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                onRemoveAddedNode(index)
                addedNodeToRemove = null
            },
            onDismiss = { addedNodeToRemove = null }
        )
    }

    FullScreenDialogPage(
        title = stringResource(R.string.profiles_custom_config),
        onDismiss = onDismiss,
        actions = {
            IconButton(
                modifier = Modifier.fillMaxSize(),
                enabled = canSave,
                onClick = { onConfirm(name.trim(), selectedNodeIds.toList()) }
            ) {
                Text(
                    text = stringResource(R.string.common_save),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(
                        alpha = if (canSave) 1f else 0.38f
                    )
                )
            }
        }
    ) { contentTopPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = contentTopPadding + 16.dp,
                end = 16.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "custom_profile_name") {
                val nameFieldShape = RoundedCornerShape(16.dp)
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassTextFieldPanel(shape = nameFieldShape),
                    singleLine = true,
                    label = { Text(stringResource(R.string.node_detail_config_name)) },
                    placeholder = { Text(stringResource(R.string.custom_profile_name_hint)) },
                    shape = nameFieldShape,
                    colors = liquidGlassOutlinedTextFieldColors(
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

            item(key = "custom_profile_selection_count") {
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        text = stringResource(
                            R.string.custom_profile_selected_nodes,
                            selectedNodeIds.size + addedNodes.size
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.custom_profile_nodes_optional),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "custom_profile_paste_node") {
                ImportOptionCard(
                    icon = Icons.Rounded.ContentPaste,
                    title = stringResource(R.string.custom_profile_paste_link),
                    subtitle = stringResource(R.string.custom_profile_paste_link_subtitle),
                    onClick = onPasteNodeLink
                )
            }

            item(key = "custom_profile_manual_node") {
                ImportOptionCard(
                    icon = Icons.Rounded.DashboardCustomize,
                    title = stringResource(R.string.nodes_manual_create),
                    subtitle = stringResource(R.string.custom_profile_manual_add_subtitle),
                    onClick = onManualAddNode
                )
            }

            if (addedNodes.isNotEmpty()) {
                item(key = "custom_profile_added_nodes_title") {
                    Text(
                        text = stringResource(R.string.custom_profile_added_nodes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                itemsIndexed(
                    items = addedNodes,
                    key = { index, outbound -> "$index:${outbound.type}:${outbound.tag}" }
                ) { index, outbound ->
                    StandardCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = outbound.tag,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    text = outbound.type,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = { addedNodeToRemove = index to outbound }) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = stringResource(R.string.common_delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            if (nodes.isEmpty()) {
                item(key = "custom_profile_empty") {
                    Text(
                        text = stringResource(R.string.custom_profile_no_available_nodes),
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                sortedProfileIds.forEach { profileId ->
                    val profileNodes = nodesByProfile[profileId].orEmpty()
                    val isExpanded = expandedProfileId == profileId
                    item(key = "profile_$profileId") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .profileGroupPanel()
                                .profileGroupPressFeedback(enabled = true) {
                                    expandedProfileId = if (isExpanded) null else profileId
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profileNames[profileId]
                                        ?: stringResource(R.string.rulesets_unknown_profile, profileId),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.rulesets_nodes_count, profileNodes.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                    }

                    if (isExpanded) {
                        items(profileNodes, key = { it.id }) { node ->
                            val isSelected = node.id in selectedNodeIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .profileCustomNodePanel(isSelected)
                                    .profileCustomNodePressFeedback {
                                        onSelectedNodeIdsChange(
                                            selectedNodeIds.updatedCustomSelection(
                                                nodeId = node.id,
                                                checked = !isSelected
                                            )
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        onSelectedNodeIdsChange(
                                            selectedNodeIds.updatedCustomSelection(
                                                nodeId = node.id,
                                                checked = checked
                                            )
                                        )
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
    }
}

internal enum class ProfileImportType { Subscription, File, Clipboard, QRCode, Custom }
