package com.kunk.singbox.ui.components

import com.kunk.singbox.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kunk.singbox.ui.theme.*

private val NodeSelectedPulseGreen = Color(0xFF22C55E)

@Composable
private fun Modifier.nodeOverflowMenuPanel(): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return if (isLiquidGlassTheme()) {
        width(100.dp) // 交给 LiquidGlassDropdownMenu 去绘制玻璃面板，这里只控制宽度
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = shape
            )
            .width(100.dp)
    }
}

@Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
@Composable
fun NodeCard(
    name: String,
    type: String,
    latency: Long? = null,
    isSelected: Boolean,
    isSwitching: Boolean = false,
    isTesting: Boolean = false,
    trafficUsed: Long = 0,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onLatency: () -> Unit,
    onDelete: () -> Unit,
    showLatency: Boolean = true,
    showActions: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val listInteractionSource = remember { MutableInteractionSource() }

    fun formatTraffic(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unitIndex])
    }

    val shape = RoundedCornerShape(16.dp)
    val useLiquidGlass = isLiquidGlassTheme()
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val borderWidth = 1.dp
    val cardModifier = if (useLiquidGlass) {
        modifier
            .fillMaxWidth()
            .liquidGlassPanel(shape = shape, selected = isSelected, shadowElevation = 0.dp)
    } else {
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(borderWidth, borderColor, shape)
    }
    Box(
        modifier = cardModifier
            .semantics { selected = isSelected }
            .liquidGlassPressFeedback(
                interactionSource = listInteractionSource,
                label = "liquid_glass_node_card_scale",
                onClick = onClick
            )
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                SelectedPulseIndicator(
                    selected = isSelected,
                    animationLabel = "node_list_selected",
                    isSwitching = isSwitching
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = type,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.width(if (showLatency) 8.dp else 0.dp))

                        if (isTesting && showLatency) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = liquidGlassProgressColor(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                ),
                                strokeWidth = 2.dp,
                                trackColor = liquidGlassProgressTrackColor(Color.Transparent)
                            )
                        } else {
                            val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            val latencyColor = remember(latency) {
                                when {
                                    latency == null -> placeholderColor
                                    latency < 0 -> Color.Red
                                    latency <= 100 -> Color(0xFF00BFA5) // Teal: <=100ms
                                    latency <= 200 -> Color(0xFF4CAF50) // Green: <=200ms
                                    latency <= 500 -> Color(0xFFFF9800) // Orange: <=500ms
                                    else -> Color.Red // Red: >500ms
                                }
                            }
                            val timeoutText = stringResource(R.string.common_timeout)
                            val ipv6OnlyText = stringResource(R.string.common_ipv6_only)
                            val latencyText = remember(showLatency, latency, timeoutText, ipv6OnlyText) {
                                when {
                                    !showLatency -> ""
                                    latency == null -> "---"
                                    latency == com.kunk.singbox.model.PingResultCode.IPV6_ONLY -> ipv6OnlyText
                                    latency < 0 -> timeoutText
                                    else -> "${latency}ms"
                                }
                            }
                            val latencyWeight = if (latency == null) FontWeight.Normal else FontWeight.Bold

                            Text(
                                text = latencyText,
                                style = MaterialTheme.typography.labelSmall,
                                color = latencyColor,
                                fontWeight = latencyWeight
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (trafficUsed > 0 || showActions) Modifier.padding(start = 8.dp) else Modifier
            ) {
                if (trafficUsed > 0) {
                    Text(
                        text = formatTraffic(trafficUsed),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = liquidGlassMutedContentColor(Color(0xFF9575CD))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Box(
                    modifier = Modifier
                        .wrapContentSize(Alignment.TopStart)
                        .then(if (showActions) Modifier else Modifier.size(0.dp))
                ) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(32.dp)
                            .liquidGlassIconButtonPanel(shadowElevation = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    MaterialTheme(
                        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(12.dp))
                    ) {
                        LiquidGlassDropdownMenu(
                            expanded = showActions && showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.nodeOverflowMenuPanel()
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            stringResource(R.string.common_edit),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                },
                                colors = liquidGlassDropdownMenuItemColors()
                            )
                            DropdownMenuItem(
                                text = {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            stringResource(R.string.common_export),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    onExport()
                                },
                                colors = liquidGlassDropdownMenuItemColors()
                            )
                            DropdownMenuItem(
                                text = {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            stringResource(R.string.common_latency),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    onLatency()
                                },
                                colors = liquidGlassDropdownMenuItemColors()
                            )
                            DropdownMenuItem(
                                text = {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            stringResource(R.string.common_delete),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    onDelete()
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

@Composable
private fun BreathingGreenDot(animationLabel: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "${animationLabel}_pulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "${animationLabel}_pulse_progress"
    )

    Box(
        modifier = Modifier.size(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .graphicsLayer {
                    val pulseScale = 0.75f + (pulseProgress * 0.5f)
                    scaleX = pulseScale
                    scaleY = pulseScale
                    alpha = 0.36f - (pulseProgress * 0.18f)
                }
                .background(NodeSelectedPulseGreen, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(NodeSelectedPulseGreen, CircleShape)
        )
    }
}

@Composable
internal fun SelectedPulseIndicator(
    selected: Boolean,
    animationLabel: String,
    isSwitching: Boolean = false,
    slotSize: Dp = 24.dp
) {
    Box(
        modifier = Modifier.size(slotSize),
        contentAlignment = Alignment.Center
    ) {
        if (isSwitching) {
            CircularProgressIndicator(
                modifier = Modifier.size(slotSize.coerceAtMost(16.dp)),
                color = NodeSelectedPulseGreen,
                strokeWidth = 2.dp
            )
        } else {
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                ) + scaleIn(
                    initialScale = 0.55f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                ),
                exit = fadeOut(
                    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
                ) + scaleOut(
                    targetScale = 0.55f,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                )
            ) {
                BreathingGreenDot(animationLabel = animationLabel)
            }
        }
    }
}

@Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodeGridCard(
    name: String,
    type: String,
    latency: Long? = null,
    isSelected: Boolean,
    isSwitching: Boolean = false,
    isTesting: Boolean = false,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onLatency: () -> Unit,
    onDelete: () -> Unit,
    showLatency: Boolean = true,
    showActions: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val gridInteractionSource = remember { MutableInteractionSource() }

    val shape = RoundedCornerShape(12.dp)
    val useLiquidGlass = isLiquidGlassTheme()
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    val borderWidth = 1.dp
    val cardModifier = if (useLiquidGlass) {
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .liquidGlassPanel(
                shape = shape,
                selected = isSelected,
                shadowElevation = 0.dp
            )
    } else {
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(borderWidth, borderColor, shape)
    }
    Box(
        modifier = cardModifier
            .semantics { selected = isSelected }
            .liquidGlassPressFeedback(
                interactionSource = gridInteractionSource,
                label = "liquid_glass_node_grid_card_scale",
                onLongClick = if (showActions) ({ showMenu = true }) else null,
                onClick = onClick
            )
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 名称与选中指示器独立占位，双列和三列下都不会互相覆盖。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                SelectedPulseIndicator(
                    selected = isSelected,
                    animationLabel = "node_grid_selected",
                    isSwitching = isSwitching,
                    slotSize = 16.dp
                )
            }

            // Protocol & Latency
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Protocol
                Text(
                    text = type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(if (showLatency) 4.dp else 0.dp))

                // Latency / Testing
                if (isTesting && showLatency) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        color = liquidGlassProgressColor(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        ),
                        strokeWidth = 1.5.dp,
                        trackColor = liquidGlassProgressTrackColor(Color.Transparent)
                    )
                } else {
                    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    val latencyColor = remember(latency) {
                        when {
                            latency == null -> placeholderColor
                            latency < 0 -> Color.Red
                            latency <= 100 -> Color(0xFF00BFA5)
                            latency <= 200 -> Color(0xFF4CAF50)
                            latency <= 500 -> Color(0xFFFF9800)
                            else -> Color.Red
                        }
                    }
                    val timeoutText = stringResource(R.string.common_timeout)
                    val ipv6OnlyText = stringResource(R.string.common_ipv6_only)
                    val latencyText = remember(showLatency, latency, timeoutText, ipv6OnlyText) {
                        when {
                            !showLatency -> ""
                            latency == null -> "---"
                            latency == com.kunk.singbox.model.PingResultCode.IPV6_ONLY -> ipv6OnlyText
                            latency < 0 -> timeoutText
                            else -> "${latency}ms"
                        }
                    }
                    Text(
                        text = latencyText,
                        style = MaterialTheme.typography.labelSmall,
                        color = latencyColor,
                        fontWeight = if (latency == null) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }

        if (showActions && showMenu) {
            MaterialTheme(
                shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(12.dp))
            ) {
                LiquidGlassDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.nodeOverflowMenuPanel()
                ) {
                    DropdownMenuItem(
                        text = {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.common_edit),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        colors = liquidGlassDropdownMenuItemColors()
                    )
                    DropdownMenuItem(
                        text = {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.common_export),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            showMenu = false
                            onExport()
                        },
                        colors = liquidGlassDropdownMenuItemColors()
                    )
                    DropdownMenuItem(
                        text = {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.common_latency),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            showMenu = false
                            onLatency()
                        },
                        colors = liquidGlassDropdownMenuItemColors()
                    )
                    DropdownMenuItem(
                        text = {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.common_delete),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        colors = liquidGlassDropdownMenuItemColors()
                    )
                }
            }
        }
    }
}
