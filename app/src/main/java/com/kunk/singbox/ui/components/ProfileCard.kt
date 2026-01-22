package com.kunk.singbox.ui.components

import com.kunk.singbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.MoreVert
import com.kunk.singbox.model.UpdateStatus
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfileCard(
    name: String,
    type: String,
    isSelected: Boolean,
    isEnabled: Boolean,
    isUpdating: Boolean,
    updateStatus: UpdateStatus = UpdateStatus.Idle,
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
        // 去除末尾的 0 和 . (例如: 100.00 -> 100, 25.50 -> 25.5)
        val stripped = formatted.dropLastWhile { it == '0' }.dropLastWhile { it == '.' }
        return "$stripped ${units[unitIndex]}"
    }

    val noExpiryMsg = stringResource(R.string.profile_card_no_expiry)
    val neverUpdatedMsg = stringResource(R.string.profile_card_never_updated)
    val unlimitedTrafficMsg = stringResource(R.string.profile_card_traffic_unlimited)

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
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(16.dp)
            .alpha(if (isEnabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Status Indicator
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(4.dp)
                )
            } else {
                Spacer(modifier = Modifier.size(24.dp))
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    when {
                        isUpdating -> {
                            Spacer(modifier = Modifier.width(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                strokeWidth = 2.dp
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

                // 显示最后更新时间（替换原来的 Type 显示）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.profile_card_updated_at) + " ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    val disabledSuffix = if (!isEnabled) " (" + stringResource(R.string.common_disabled) + ")" else ""
                    Text(
                        text = formatLastUpdated(lastUpdated) + disabledSuffix,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 流量信息块（保持高度一致，即使为空也占位）
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

        Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            MaterialTheme(
                shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(12.dp))
            ) {
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .width(100.dp)
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
                        }
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
                        }
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
                        }
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
                        }
                    )
                }
            }
        }
    }
}
