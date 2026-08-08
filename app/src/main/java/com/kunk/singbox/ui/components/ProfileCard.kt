package com.kunk.singbox.ui.components

import com.kunk.singbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.MoreVert
import com.kunk.singbox.model.SubscriptionUpdateStage
import com.kunk.singbox.model.UpdateStatus
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import com.kunk.singbox.ui.theme.LiquidGlassDropdownMenu
import com.kunk.singbox.ui.theme.hollowShadow
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassDropdownMenuItemColors
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassMaterial
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassPressFeedback
import com.kunk.singbox.ui.theme.liquidGlassProgressColor
import com.kunk.singbox.ui.theme.liquidGlassProgressTrackColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 仅配置主卡：比全局默认轻，参数全常量；更新态刷新时 remember 住阴影段，不重建 hollowShadow
private val ProfileCardShadowBlur = 4.dp
private val ProfileCardShadowOffsetY = 1.5.dp
private const val PROFILE_CARD_SHADOW_ALPHA_DARK = 0.10f
private const val PROFILE_CARD_SHADOW_ALPHA_LIGHT = 0.03f

@Composable
private fun Modifier.profileOverflowMenuPanel(): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return if (isLiquidGlassTheme()) {
        width(100.dp)
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

@Composable
private fun Modifier.profileListCardPanel(
    shape: RoundedCornerShape,
    selected: Boolean,
    enabled: Boolean
): Modifier {
    if (!isLiquidGlassTheme()) {
        return this
    }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 阴影段与选中/启用态解耦：更新文案、进度、选中切换都不重建 hollowShadow
    val shadowOnly = remember(shape, isDark) {
        Modifier.hollowShadow(
            shape = shape,
            color = Color.Black,
            alpha = if (isDark) PROFILE_CARD_SHADOW_ALPHA_DARK else PROFILE_CARD_SHADOW_ALPHA_LIGHT,
            blurRadius = ProfileCardShadowBlur,
            offsetY = ProfileCardShadowOffsetY
        )
    }
    val panel = this
        .then(shadowOnly)
        .liquidGlassMaterial(shape = shape, selected = selected)
    return if (!enabled) panel.alpha(0.56f) else panel
}

@Composable
private fun Modifier.profileBadgePanel(
    defaultColor: Color,
    shape: RoundedCornerShape = RoundedCornerShape(6.dp)
): Modifier {
    return if (isLiquidGlassTheme()) {
        // 角标不画阴影，避免更新状态切换时多处 hollowShadow 同步闪
        liquidGlassPanel(shape = shape, selected = true, shadowElevation = 0.dp)
    } else {
        background(defaultColor, shape)
    }
}

@Composable
private fun profileBadgeContentColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) MaterialTheme.colorScheme.primary else defaultColor
}

@Composable
private fun Modifier.profileCardPressFeedback(
    useLiquidGlass: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit
): Modifier = liquidGlassPressFeedback(
    enabled = isEnabled,
    useLiquidGlass = useLiquidGlass,
    label = "liquid_glass_profile_card_scale",
    onClick = onClick
)

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "LongParameterList")
@Composable
fun ProfileCard(
    name: String,
    type: String,
    isSelected: Boolean,
    isSwitching: Boolean = false,
    isEnabled: Boolean,
    isUpdating: Boolean,
    updateStatus: UpdateStatus = UpdateStatus.Idle,
    updateStage: SubscriptionUpdateStage? = null,
    expireDate: Long = 0,
    totalTraffic: Long = 0,
    usedTraffic: Long = 0,
    lastUpdated: Long = 0,
    onClick: () -> Unit,
    onUpdate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    fun formatTraffic(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        val formatted = String.format(Locale.US, "%.2f", value)

        val stripped = formatted.dropLastWhile { it == '0' }.dropLastWhile { it == '.' }
        return "$stripped ${units[unitIndex]}"
    }

    val noExpiryMsg = stringResource(R.string.profile_card_no_expiry)
    val neverUpdatedMsg = stringResource(R.string.profile_card_never_updated)
    val unlimitedTrafficMsg = stringResource(R.string.profile_card_traffic_unlimited)
    val stageContainerColor = MaterialTheme.colorScheme.primaryContainer
    val stageTextColor = profileBadgeContentColor(MaterialTheme.colorScheme.primary)
    // 形状只建一次，避免每次重组新 Shape 实例触发阴影节点 update
    val shape = remember { RoundedCornerShape(16.dp) }
    val useLiquidGlass = isLiquidGlassTheme()
    val cardModifier = if (useLiquidGlass) {
        modifier
            .fillMaxWidth()
            // 配置页专用轻阴影面板；参数常量固定，更新态文案/进度刷新不重建阴影
            .profileListCardPanel(
                shape = shape,
                selected = isSelected,
                enabled = isEnabled
            )
            .profileCardPressFeedback(
                useLiquidGlass = useLiquidGlass,
                isEnabled = isEnabled,
                onClick = onClick
            )
            .padding(16.dp)
    } else {
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = shape
            )
            .profileCardPressFeedback(
                useLiquidGlass = useLiquidGlass,
                isEnabled = isEnabled,
                onClick = onClick
            )
            .padding(16.dp)
            .alpha(if (isEnabled) 1f else 0.5f)
    }

    fun formatDate(timestamp: Long): String {
        if (timestamp <= 0) return noExpiryMsg
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date(timestamp * 1000)) // Subscription usually returns unix timestamp in seconds
    }

    fun formatLastUpdated(timestamp: Long): String {
        if (timestamp <= 0) return neverUpdatedMsg
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp)) // lastUpdated is in milliseconds
    }

    Row(
        modifier = cardModifier.semantics { selected = isSelected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            SelectedPulseIndicator(
                selected = isSelected,
                animationLabel = "profile_selected",
                isSwitching = isSwitching
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    when {
                        isUpdating -> {
                            Spacer(modifier = Modifier.width(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = liquidGlassProgressColor(MaterialTheme.colorScheme.onSurface),
                                strokeWidth = 2.dp,
                                trackColor = liquidGlassProgressTrackColor(Color.Transparent)
                            )
                        }
                        updateStatus == UpdateStatus.Success -> {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Update Success",
                                tint = Color(0xFF4CAF50), // Green
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        updateStatus == UpdateStatus.Failed -> {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.Error,
                                contentDescription = "Update Failed",
                                tint = Color(0xFFF44336), // Red
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.profile_card_updated_at) + " ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                        text = formatLastUpdated(lastUpdated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (!isEnabled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.common_disabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = profileBadgeContentColor(MaterialTheme.colorScheme.onSurfaceVariant),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .profileBadgePanel(
                                    defaultColor = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }

                    // 更新阶段放在同一行，禁止新增行导致卡片高度变化
                    if (updateStage != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(updateStage.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = stageTextColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .profileBadgePanel(stageContainerColor)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(16.dp)
                ) {
                    val showTraffic = totalTraffic > 0 || totalTraffic < 0
                    if (showTraffic) {
                        Icon(
                            imageVector = Icons.Rounded.ImportExport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val trafficText = when {
                            totalTraffic > 0 -> "${formatTraffic(usedTraffic)} / ${formatTraffic(totalTraffic)}"
                            totalTraffic == -2L -> stringResource(
                                R.string.profile_card_traffic_remaining,
                                formatTraffic(usedTraffic)
                            )
                            usedTraffic > 0 -> "${formatTraffic(usedTraffic)} / $unlimitedTrafficMsg"
                            else -> unlimitedTrafficMsg
                        }
                        Text(
                            text = trafficText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    if (showTraffic && expireDate != 0L) {
                        Spacer(modifier = Modifier.width(16.dp))
                    }

                    if (expireDate != 0L) {
                        Icon(
                            imageVector = Icons.Rounded.DateRange,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatDate(expireDate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
            IconButton(
                modifier = Modifier.liquidGlassIconButtonPanel(),
                onClick = { showMenu = true }
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            MaterialTheme(
                shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(12.dp))
            ) {
                LiquidGlassDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.profileOverflowMenuPanel()
                ) {
                    DropdownMenuItem(
                        text = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.common_update), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            showMenu = false
                            onUpdate()
                        },
                        colors = liquidGlassDropdownMenuItemColors()
                    )
                    DropdownMenuItem(
                        text = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(if (isEnabled) stringResource(R.string.common_disable) else stringResource(R.string.common_enable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            showMenu = false
                            onToggle()
                        },
                        colors = liquidGlassDropdownMenuItemColors()
                    )
                    DropdownMenuItem(
                        text = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.common_edit), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
