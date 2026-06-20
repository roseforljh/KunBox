package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.ui.components.ClickableDropdownField
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kunk.singbox.viewmodel.DefaultRuleSetDownloadState
import com.kunk.singbox.model.RuleSetOutboundMode
import androidx.compose.ui.draw.scale
import com.kunk.singbox.ui.theme.LiquidGlassDropdownMenu
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassCheckboxColors
import com.kunk.singbox.ui.theme.liquidGlassDropdownMenuItemColors
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassDialogContainerColor
import com.kunk.singbox.ui.theme.liquidGlassDialogPanel
import com.kunk.singbox.ui.theme.liquidGlassProgressColor
import com.kunk.singbox.ui.theme.liquidGlassProgressTrackColor
import com.kunk.singbox.ui.theme.liquidGlassSwitchColors
import com.kunk.singbox.ui.theme.liquidGlassTextButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassTextButtonPanel

@Composable
private fun Modifier.ruleSetMenuPanel(shape: RoundedCornerShape = RoundedCornerShape(12.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 8.dp)
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant, shape)
    }
}

@Composable
private fun Modifier.ruleSetItemPressFeedback(onClick: () -> Unit): Modifier {
    val useLiquidGlass = isLiquidGlassTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (useLiquidGlass && isPressed) 0.98f else 1f,
        animationSpec = spring(stiffness = 520f, dampingRatio = 0.72f),
        label = "liquid_glass_rule_set_item_scale"
    )
    val clickModifier = if (useLiquidGlass) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }

    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.then(clickModifier)
}

@Composable
private fun RuleSetBadge(
    text: String,
    backgroundColor: Color,
    contentColor: Color
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .then(
                if (isLiquidGlassTheme()) {
                    Modifier.liquidGlassPanel(shape = shape, selected = true, shadowElevation = 4.dp)
                } else {
                    Modifier.background(backgroundColor, shape)
                }
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RuleSetItem(
    ruleSet: RuleSet,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isDownloading: Boolean = false,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit = {},
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onOutboundClick: () -> Unit = {},
    onInboundClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.rulesets_delete_title),
            message = stringResource(R.string.rulesets_delete_confirm, ruleSet.tag),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                onDeleteClick()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    StandardCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ruleSetItemPressFeedback(onClick = { if (isSelectionMode) onClick() })
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp),
                    colors = liquidGlassCheckboxColors()
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = ruleSet.tag,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = liquidGlassProgressColor(MaterialTheme.colorScheme.primary),
                            trackColor = liquidGlassProgressTrackColor(Color.Transparent)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.settings_updating),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        RuleSetBadge(
                            text = stringResource(R.string.common_ready),
                            backgroundColor = Color(0xFF2E7D32).copy(alpha = 0.8f),
                            contentColor = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    val outboundMode = ruleSet.outboundMode ?: RuleSetOutboundMode.DIRECT
                    val outboundText = stringResource(outboundMode.displayNameRes)
                    val outboundColor = when (outboundMode) {
                        RuleSetOutboundMode.DIRECT -> Color(0xFF1565C0)
                        RuleSetOutboundMode.BLOCK -> Color(0xFFC62828)
                        RuleSetOutboundMode.PROXY -> Color(0xFF7B1FA2)
                        RuleSetOutboundMode.NODE -> Color(0xFFE65100)
                        RuleSetOutboundMode.PROFILE -> Color(0xFF00838F)
                    }
                    RuleSetBadge(
                        text = outboundText,
                        backgroundColor = outboundColor.copy(alpha = 0.8f),
                        contentColor = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val inbounds = ruleSet.inbounds ?: emptyList()
                    val inboundText = if (inbounds.isEmpty()) stringResource(R.string.common_all) else inbounds.joinToString(",")
                    RuleSetBadge(
                        text = inboundText,
                        backgroundColor = Color(0xFFFF8F00).copy(alpha = 0.8f),
                        contentColor = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${stringResource(ruleSet.type.displayNameRes)} - ${ruleSet.format}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ruleSet.type == RuleSetType.REMOTE) {
                    Text(
                        text = ruleSet.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = ruleSet.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (!isSelectionMode) {
                if (defaultRuleSetTags.contains(ruleSet.tag)) {
                    Switch(
                        checked = ruleSet.enabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier
                            .scale(0.8f)
                            .padding(end = 8.dp),
                        colors = liquidGlassSwitchColors()
                    )
                } else {
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                        IconButton(
                            modifier = Modifier.liquidGlassIconButtonPanel(),
                            onClick = { showMenu = true }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More actions",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        MaterialTheme(
                            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(12.dp))
                        ) {
                            LiquidGlassDropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier
                                    .width(100.dp)
                                    .ruleSetMenuPanel()
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(stringResource(R.string.common_edit), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        onEditClick()
                                    },
                                    colors = liquidGlassDropdownMenuItemColors()
                                )
                                DropdownMenuItem(
                                    text = {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        showDeleteConfirm = true
                                    },
                                    colors = liquidGlassDropdownMenuItemColors()
                                )
                                DropdownMenuItem(
                                    text = {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(stringResource(R.string.common_outbound), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        onOutboundClick()
                                    },
                                    colors = liquidGlassDropdownMenuItemColors()
                                )
                                DropdownMenuItem(
                                    text = {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(stringResource(R.string.common_inbound), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        onInboundClick()
                                    },
                                    colors = liquidGlassDropdownMenuItemColors()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RuleSetEditorDialog(
    initialRuleSet: RuleSet? = null,
    onDismiss: () -> Unit,
    onConfirm: (RuleSet) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var tag by rememberSaveable(initialRuleSet?.tag) {
        mutableStateOf(initialRuleSet?.tag ?: "")
    }
    var type by rememberSaveable(initialRuleSet?.type) {
        mutableStateOf(initialRuleSet?.type ?: RuleSetType.REMOTE)
    }
    var format by rememberSaveable(initialRuleSet?.format) {
        mutableStateOf(initialRuleSet?.format ?: "binary")
    }
    var url by rememberSaveable(initialRuleSet?.url) {
        mutableStateOf(initialRuleSet?.url ?: "")
    }
    var path by rememberSaveable(initialRuleSet?.path) {
        mutableStateOf(initialRuleSet?.path ?: "")
    }

    var showTypeDialog by remember { mutableStateOf(false) }
    var showFormatDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showTypeDialog) {
        val options = RuleSetType.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.rulesets_type),
            options = options,
            selectedIndex = RuleSetType.entries.indexOf(type),
            onSelect = { index ->
                type = RuleSetType.entries[index]
                showTypeDialog = false
            },
            onDismiss = { showTypeDialog = false }
        )
    }

    if (showFormatDialog) {
        val options = listOf("binary", "source")
        SingleSelectDialog(
            title = stringResource(R.string.rulesets_format),
            options = options,
            selectedIndex = options.indexOf(format).coerceAtLeast(0),
            onSelect = { index ->
                format = options[index]
                showFormatDialog = false
            },
            onDismiss = { showFormatDialog = false }
        )
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.rulesets_delete_title),
            message = stringResource(R.string.rulesets_delete_confirm, tag),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                onDelete?.invoke()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    AlertDialog(
        modifier = Modifier.liquidGlassDialogPanel(RoundedCornerShape(24.dp)),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = liquidGlassDialogContainerColor(),
        title = {
            Text(
                text = if (initialRuleSet == null) stringResource(R.string.rulesets_add) else stringResource(R.string.rulesets_edit),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                StyledTextField(
                    label = stringResource(R.string.rulesets_rule_set_tag),
                    value = tag,
                    onValueChange = { tag = it },
                    placeholder = "geoip-cn"
                )

                ClickableDropdownField(
                    label = stringResource(R.string.rulesets_type),
                    value = stringResource(type.displayNameRes),
                    onClick = { showTypeDialog = true }
                )

                ClickableDropdownField(
                    label = stringResource(R.string.rulesets_format),
                    value = format,
                    onClick = { showFormatDialog = true }
                )

                if (type == RuleSetType.REMOTE) {
                    StyledTextField(
                        label = stringResource(R.string.rulesets_url),
                        value = url,
                        onValueChange = { url = it },
                        placeholder = "https://example.com/rules.srs"
                    )
                } else {
                    StyledTextField(
                        label = stringResource(R.string.rulesets_local_path),
                        value = path,
                        onValueChange = { path = it },
                        placeholder = "/path/to/rules.srs"
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.liquidGlassTextButtonPanel(enabled = tag.isNotBlank()),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = liquidGlassTextButtonContentColor(MaterialTheme.colorScheme.primary)
                ),
                onClick = {
                    val newRuleSet = initialRuleSet?.copy(
                        tag = tag.trim(),
                        type = type,
                        format = format,
                        url = url.trim(),
                        path = path.trim()
                    ) ?: RuleSet(
                        tag = tag.trim(),
                        type = type,
                        format = format,
                        url = url.trim(),
                        path = path.trim()
                    )
                    onConfirm(newRuleSet)
                },
                enabled = tag.isNotBlank() && (if (type == RuleSetType.REMOTE) url.isNotBlank() else path.isNotBlank())
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            Row {
                if (initialRuleSet != null && onDelete != null) {
                    TextButton(
                        modifier = Modifier.liquidGlassTextButtonPanel(),
                        onClick = { showDeleteConfirm = true }
                    ) {
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(
                    modifier = Modifier.liquidGlassTextButtonPanel(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = liquidGlassTextButtonContentColor(
                            defaultColor = MaterialTheme.colorScheme.primary,
                            liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ),
                    onClick = onDismiss
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    )
}

@Composable
fun DefaultRuleSetProgressDialog(
    state: DefaultRuleSetDownloadState,
    onCancel: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.liquidGlassDialogPanel(),
        onDismissRequest = {},
        containerColor = liquidGlassDialogContainerColor(),
        title = {
            Text(
                text = stringResource(R.string.rulesets_add_default),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = state.currentTag ?: stringResource(R.string.common_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${state.completed}/${state.total}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.completed.toFloat() / state.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = liquidGlassProgressColor(MaterialTheme.colorScheme.primary),
                    trackColor = liquidGlassProgressTrackColor(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                modifier = Modifier.liquidGlassTextButtonPanel(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.primary,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ),
                onClick = onCancel
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
