package com.kunk.singbox.ui.components

import android.content.Intent
import androidx.compose.ui.res.stringResource
import com.kunk.singbox.R
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.kunk.singbox.ui.theme.Destructive
import com.kunk.singbox.model.FilterMode
import com.kunk.singbox.model.NodeFilter
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassButtonColors
import com.kunk.singbox.ui.theme.liquidGlassButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassEmptyStatePanel
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassPressFeedback
import com.kunk.singbox.ui.theme.liquidGlassProgressColor
import com.kunk.singbox.ui.theme.liquidGlassProgressTrackColor
import com.kunk.singbox.ui.theme.liquidGlassOutlinedTextFieldColors
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel
import com.kunk.singbox.ui.theme.liquidGlassTextButtonColors
import com.kunk.singbox.ui.theme.liquidGlassTextButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassTextButtonPanel
import com.kunk.singbox.ui.theme.LiquidGlassDialogEffect

@Composable
private fun Modifier.nodeSelectionDialogPanel(shape: RoundedCornerShape = RoundedCornerShape(28.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 24.dp)
    } else {
        background(MaterialTheme.colorScheme.surface, shape)
    }
}

@Composable
private fun Modifier.nodeSelectionGroupPanel(shape: RoundedCornerShape = RoundedCornerShape(12.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 6.dp)
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    }
}

@Composable
private fun Modifier.nodeSelectionItemPanel(isSelected: Boolean): Modifier {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }

    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = isSelected, shadowElevation = 6.dp)
    } else {
        background(backgroundColor, shape)
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = borderColor,
                shape = shape
            )
    }
}

@Composable
private fun Modifier.nodeSelectionListItemPanel(isSelected: Boolean): Modifier {
    val shape = RoundedCornerShape(10.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = isSelected, shadowElevation = 4.dp)
    } else {
        background(
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
            shape
        )
    }
}

@Composable
private fun Modifier.nodeFilterModePanel(isSelected: Boolean): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = isSelected, shadowElevation = 5.dp)
    } else {
        background(
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
            shape
        )
    }
}

@Composable
private fun Modifier.nodeSelectorCheckPanel(): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = CircleShape, selected = true, shadowElevation = 4.dp)
    } else {
        background(MaterialTheme.colorScheme.primary, CircleShape)
    }
}

@Composable
private fun Modifier.nodeSelectorItemPressFeedback(
    useLiquidGlass: Boolean,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (useLiquidGlass && isPressed) 0.98f else 1f,
        animationSpec = spring(stiffness = 520f, dampingRatio = 0.72f),
        label = "liquid_glass_node_selector_item_scale"
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
private fun Modifier.nodeSelectionGroupPressFeedback(
    useLiquidGlass: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (useLiquidGlass && enabled && isPressed) 0.98f else 1f,
        animationSpec = spring(stiffness = 520f, dampingRatio = 0.72f),
        label = "liquid_glass_node_selection_group_scale"
    )
    val clickModifier = if (useLiquidGlass) {
        Modifier.clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier.clickable(
            enabled = enabled,
            onClick = onClick
        )
    }

    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.then(clickModifier)
}

@Composable
private fun Modifier.nodeSelectionRouteItemPressFeedback(
    useLiquidGlass: Boolean,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (useLiquidGlass && isPressed) 0.98f else 1f,
        animationSpec = spring(stiffness = 520f, dampingRatio = 0.72f),
        label = "liquid_glass_node_selection_route_item_scale"
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
fun ProfileNodeSelectDialog(
    title: String,
    profiles: List<ProfileUi>,
    nodesForSelection: List<NodeUi>,
    selectedNodeRef: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    fun toNodeRef(node: NodeUi): String = "${node.sourceProfileId}::${node.name}"

    val nodesByProfile = remember(nodesForSelection) {
        nodesForSelection.groupBy { it.sourceProfileId }
    }
    val profileOrder = remember(profiles) { profiles.sortedBy { it.name } }
    val knownProfileIds = remember(profiles) { profiles.map { it.id }.toSet() }

    var expandedProfileId by remember { mutableStateOf<String?>(null) }
    val useLiquidGlass = isLiquidGlassTheme()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .nodeSelectionDialogPanel()
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
            ) {
                profileOrder.forEach { profile ->
                    val itemsForProfile = nodesByProfile[profile.id].orEmpty()
                    val isExpanded = expandedProfileId == profile.id
                    val enabled = itemsForProfile.isNotEmpty()

                    item(key = "profile_${profile.id}") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .nodeSelectionGroupPanel()
                                .animateContentSize(animationSpec = tween(durationMillis = 220))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .nodeSelectionGroupPressFeedback(
                                        useLiquidGlass = useLiquidGlass,
                                        enabled = enabled
                                    ) {
                                        expandedProfileId = if (isExpanded) null else profile.id
                                    }
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(R.string.rulesets_nodes_count, itemsForProfile.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                                    items(itemsForProfile, key = { it.id }) { node ->
                                        val ref = toNodeRef(node)
                                        val selected = ref == selectedNodeRef
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .nodeSelectionListItemPanel(selected)
                                                .nodeSelectionRouteItemPressFeedback(
                                                    useLiquidGlass = useLiquidGlass
                                                ) {
                                                    onSelect(ref)
                                                    onDismiss()
                                                }
                                                .padding(vertical = 10.dp, horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
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

                val unknownProfiles = nodesByProfile.keys
                    .filter { it !in knownProfileIds }
                    .sorted()

                unknownProfiles.forEach { profileId ->
                    val itemsForProfile = nodesByProfile[profileId].orEmpty()
                    val isExpanded = expandedProfileId == profileId
                    val enabled = itemsForProfile.isNotEmpty()

                    item(key = "unknown_$profileId") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .nodeSelectionGroupPanel()
                                .animateContentSize(animationSpec = tween(durationMillis = 220))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .nodeSelectionGroupPressFeedback(
                                        useLiquidGlass = useLiquidGlass,
                                        enabled = enabled
                                    ) {
                                        expandedProfileId = if (isExpanded) null else profileId
                                    }
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.rulesets_unknown_profile, profileId),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(R.string.rulesets_nodes_count, itemsForProfile.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                                    items(itemsForProfile, key = { it.id }) { node ->
                                        val ref = toNodeRef(node)
                                        val selected = ref == selectedNodeRef
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .nodeSelectionListItemPanel(selected)
                                                .nodeSelectionRouteItemPressFeedback(
                                                    useLiquidGlass = useLiquidGlass
                                                ) {
                                                    onSelect(ref)
                                                    onDismiss()
                                                }
                                                .padding(vertical = 10.dp, horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
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

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassTextButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = liquidGlassTextButtonColors(
                    contentColor = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val githubUrl = "https://github.com/roseforljh/singboxforandriod.git"
    val linkColor = MaterialTheme.colorScheme.primary

    val appVersion = remember { com.kunk.singbox.utils.VersionInfo.getAppVersionName(context) }
    val appVersionCode = remember { com.kunk.singbox.utils.VersionInfo.getAppVersionCode(context) }

    val kernelLoadingMsg = stringResource(R.string.about_kernel_loading)
    val kernelBuiltinMsg = stringResource(R.string.about_kernel_builtin)
    var singBoxVersion by remember { mutableStateOf(kernelLoadingMsg) }
    LaunchedEffect(Unit) {
        singBoxVersion = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                com.kunk.singbox.core.SingBoxCore.ensureLibboxSetup(context)
                val version = io.nekohasekai.libbox.Libbox.version()

                when {
                    version.isNullOrBlank() -> kernelBuiltinMsg
                    version.equals("unknown", ignoreCase = true) -> kernelBuiltinMsg
                    else -> version
                }
            } catch (t: Throwable) {
                "sing-box (·告劕鎳愰悿?"
            }
        }
    }

    val aboutAddressMsg = stringResource(R.string.about_address)
    val aboutBasedOnMsg = stringResource(R.string.about_based_on)
    val aboutDesignedByMsg = stringResource(R.string.about_designed_by)

    val annotatedString = buildAnnotatedString {
        append("KunBox for Android\n\n")
        append(stringResource(R.string.about_version, appVersion, appVersionCode))
        append("\n")
        append(stringResource(R.string.about_kernel_version, singBoxVersion))
        append("\n\n")
        append("$aboutAddressMsg ")
        pushStringAnnotation(tag = "URL", annotation = githubUrl)
        withStyle(
            style = SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline
            )
        ) {
            append("KunBoxForAndroid")
        }
        pop()
        append("\n\n$aboutBasedOnMsg\n\n$aboutDesignedByMsg")
    }

    Dialog(onDismissRequest = onDismiss) {
        LiquidGlassDialogEffect()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .nodeSelectionDialogPanel()
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_about_kunbox),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            ClickableText(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                onClick = { offset ->
                    annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                            context.startActivity(intent)
                        }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = liquidGlassButtonColors(
                    defaultContainerColor = MaterialTheme.colorScheme.primary,
                    defaultContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text(
                    text = stringResource(R.string.common_ok),
                    fontWeight = FontWeight.Bold,
                    color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                )
            }
        }
    }
}

@Composable
fun NodeFilterDialog(
    currentFilter: NodeFilter,
    onConfirm: (NodeFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var filterMode by remember { mutableStateOf(currentFilter.filterMode) }

    var includeKeywordsText by remember {
        mutableStateOf(currentFilter.effectiveIncludeKeywords.joinToString(", "))
    }
    var excludeKeywordsText by remember {
        mutableStateOf(currentFilter.effectiveExcludeKeywords.joinToString(", "))
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .nodeSelectionDialogPanel()
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.node_filter_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.node_filter_mode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .nodeFilterModePanel(filterMode == FilterMode.NONE)
                    .liquidGlassPressFeedback(
                        label = "liquid_glass_node_filter_none_scale"
                    ) {
                        filterMode = FilterMode.NONE
                    }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (filterMode == FilterMode.NONE) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (filterMode == FilterMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.node_filter_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (filterMode == FilterMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .nodeFilterModePanel(filterMode == FilterMode.INCLUDE)
                    .liquidGlassPressFeedback(
                        label = "liquid_glass_node_filter_include_scale"
                    ) {
                        filterMode = FilterMode.INCLUDE
                    }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (filterMode == FilterMode.INCLUDE) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (filterMode == FilterMode.INCLUDE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.node_filter_include),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (filterMode == FilterMode.INCLUDE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .nodeFilterModePanel(filterMode == FilterMode.EXCLUDE)
                    .liquidGlassPressFeedback(
                        label = "liquid_glass_node_filter_exclude_scale"
                    ) {
                        filterMode = FilterMode.EXCLUDE
                    }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (filterMode == FilterMode.EXCLUDE) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (filterMode == FilterMode.EXCLUDE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.node_filter_exclude),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (filterMode == FilterMode.EXCLUDE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            if (filterMode != FilterMode.NONE) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = if (filterMode == FilterMode.INCLUDE) {
                        stringResource(R.string.node_filter_include)
                    } else {
                        stringResource(R.string.node_filter_exclude)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                val activeKeywords = if (filterMode == FilterMode.INCLUDE) {
                    includeKeywordsText
                } else {
                    excludeKeywordsText
                }

                val keywordFieldShape = RoundedCornerShape(16.dp)
                OutlinedTextField(
                    value = activeKeywords,
                    onValueChange = { newValue ->
                        if (filterMode == FilterMode.INCLUDE) {
                            includeKeywordsText = newValue
                        } else {
                            excludeKeywordsText = newValue
                        }
                    },
                    placeholder = { Text(stringResource(R.string.node_filter_keywords_hint), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassTextFieldPanel(shape = keywordFieldShape),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 3,
                    colors = liquidGlassOutlinedTextFieldColors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                        unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                        focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                        unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = keywordFieldShape
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.node_filter_keywords_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                TextButton(
                    onClick = {
                        filterMode = FilterMode.NONE
                        includeKeywordsText = ""
                        excludeKeywordsText = ""
                    },

                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .liquidGlassTextButtonPanel(shape = RoundedCornerShape(25.dp)),
                    colors = liquidGlassTextButtonColors(
                        contentColor = liquidGlassTextButtonContentColor(
                            defaultColor = Destructive,
                            liquidColor = Destructive
                        )
                    )
                ) {
                    Text(stringResource(R.string.common_clear))
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .liquidGlassTextButtonPanel(shape = RoundedCornerShape(25.dp)),
                    colors = liquidGlassTextButtonColors(
                        contentColor = liquidGlassTextButtonContentColor(
                            defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                ) {
                    Text(stringResource(R.string.common_cancel))
                }

                Button(
                    onClick = {

                        val includeKeywords = includeKeywordsText
                            .split(",", "，")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val excludeKeywords = excludeKeywordsText
                            .split(",", "，")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }

                        onConfirm(NodeFilter(
                            filterMode = filterMode,
                            includeKeywords = includeKeywords,
                            excludeKeywords = excludeKeywords
                        ))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .liquidGlassButtonPanel(shape = RoundedCornerShape(25.dp)),
                    colors = liquidGlassButtonColors(
                        defaultContainerColor = MaterialTheme.colorScheme.primary,
                        defaultContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text(
                        text = stringResource(R.string.common_ok),
                        fontWeight = FontWeight.Bold,
                        color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                    )
                }
            }
        }
    }
}

@Composable
fun NodeSelectorDialog(
    title: String,
    nodes: List<NodeUi>,
    selectedNodeId: String?,
    testingNodeIds: Set<String> = emptySet(),
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .nodeSelectionDialogPanel()
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (nodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .liquidGlassEmptyStatePanel(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_no_nodes_available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(nodes, key = { it.id }) { node ->
                        val isSelected = node.id == selectedNodeId
                        val isTesting = testingNodeIds.contains(node.id)

                        NodeSelectorItem(
                            node = node,
                            isSelected = isSelected,
                            isTesting = isTesting,
                            onClick = {
                                onSelect(node.id)
                                onDismiss()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassTextButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = liquidGlassTextButtonColors(
                    contentColor = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

@Composable
internal fun NodeSelectorItem(
    node: NodeUi,
    isSelected: Boolean,
    isTesting: Boolean,
    onClick: () -> Unit
) {
    val useLiquidGlass = isLiquidGlassTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .nodeSelectionItemPanel(isSelected)
            .nodeSelectorItemPressFeedback(useLiquidGlass = useLiquidGlass, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .nodeSelectorCheckPanel(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Rounded.Check,
                        contentDescription = null,
                        tint = if (useLiquidGlass) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.protocolDisplay,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        color = liquidGlassProgressColor(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        ),
                        strokeWidth = 1.5.dp,
                        trackColor = liquidGlassProgressTrackColor(Color.Transparent)
                    )
                } else {
                    val latency = node.latencyMs
                    val latencyColor = when {
                        latency == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        latency < 0 -> Color.Red
                        latency < 1000 -> Color(0xFF4CAF50)
                        latency < 2000 -> Color(0xFFFFC107)
                        else -> Color.Red
                    }
                    val latencyText = when {
                        latency == null -> "---"
                        latency == com.kunk.singbox.model.PingResultCode.IPV6_ONLY -> {
                            stringResource(R.string.common_ipv6_only)
                        }
                        latency < 0 -> stringResource(R.string.common_timeout)
                        else -> "${latency}ms"
                    }
                    Text(
                        text = latencyText,
                        style = MaterialTheme.typography.labelSmall,
                        color = latencyColor,
                        fontWeight = if (latency != null && latency > 0) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
