@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunk.singbox.R
import com.kunk.singbox.model.ClashConnection
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import java.util.Locale

@Composable
@Suppress("LongMethod", "CognitiveComplexMethod")
@OptIn(ExperimentalLayoutApi::class)
internal fun ConnectionItemCard(
    connection: ClashConnection,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val borderColor = if (isDark) {
        Color.White.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    val shape = RoundedCornerShape(16.dp)
    val useLiquidGlass = isLiquidGlassTheme()

    if (useLiquidGlass) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .connectionItemPanel()
                .padding(12.dp)
        ) {
            ConnectionItemCardContent(
                connection = connection,
                onClose = onClose
            )
        }
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                borderColor,
                shape
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = connectionItemContainerColor(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            ConnectionItemCardContent(
                connection = connection,
                onClose = onClose
            )
        }
    }
}

@Suppress("LongMethod", "CognitiveComplexMethod")
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionItemCardContent(
    connection: ClashConnection,
    onClose: () -> Unit
) {
    val isUdp = connection.metadata.network.lowercase() == "udp"
    val badgeBg = if (isUdp) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val badgeTextColor = if (isUdp) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val protocolBadgeTextColor = connectionProtocolBadgeTextColor(badgeTextColor)
    val hostStr = connection.metadata.host.ifBlank { connection.metadata.destinationIP }
    val portStr = connection.metadata.destinationPort

    // 目标及删除按钮
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 协议药丸
        Box(
            modifier = Modifier
                .connectionProtocolBadgePanel(badgeBg)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = connection.metadata.network.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = protocolBadgeTextColor
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // 地址
        Text(
            text = "$hostStr:$portStr",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        ConnectionCloseControl(onClose = onClose)
    }

    // 额外的目的IP显示（如果host不为空）
    if (connection.metadata.host.isNotBlank() && connection.metadata.destinationIP.isNotBlank()) {
        Text(
            text = "IP: ${connection.metadata.destinationIP}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 2.dp)
        )
    }

    Spacer(modifier = Modifier.height(6.dp))

    // 代理链与规则
    if (connection.chains.isNotEmpty() || connection.rule.isNotBlank()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalArrangement = Arrangement.Center
        ) {
            // 规则
            if (connection.rule.isNotBlank()) {
                val payload = if (connection.rulePayload.isNotBlank()) "(${connection.rulePayload})" else ""
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp, bottom = 4.dp)
                        .connectionMetaBadgePanel(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Rule: ${connection.rule}$payload",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 节点链路
            if (connection.chains.isNotEmpty()) {
                val chainText = connection.chains.joinToString(" → ")
                Box(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .connectionMetaBadgePanel(
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = chainText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = connectionMetaBadgeTextColor(
                            MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    // 流量统计及已连接时长
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "↑ ${formatTraffic(connection.upload)}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "↓ ${formatTraffic(connection.download)}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        // 持续时间
        val duration = formatDuration(connection.start)
        if (duration.isNotBlank()) {
            Text(
                text = duration,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
internal fun ConnectionEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .connectionEmptyStatePanel(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .connectionEmptyIconPanel(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp),
                lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

internal object ConnectionInfoUiPolicy {
    private val rfc3339Regex = Regex(
        """^(.+?)(?:\.(\d+))?([Zz]|[+-]\d{2}:\d{2}|[+-]\d{4}|[+-]\d{2})$"""
    )

    fun filterConnections(
        connections: List<ClashConnection>,
        searchQuery: String
    ): List<ClashConnection> {
        if (searchQuery.isBlank()) return connections

        val lower = searchQuery.lowercase()
        return connections.filter { conn ->
            conn.metadata.host.lowercase().contains(lower) ||
                conn.metadata.destinationIP.lowercase().contains(lower) ||
                conn.rule.lowercase().contains(lower) ||
                conn.rulePayload.lowercase().contains(lower) ||
                conn.chains.any { it.lowercase().contains(lower) }
        }
    }

    fun canCloseAll(
        vpnActive: Boolean,
        allConnections: List<ClashConnection>
    ): Boolean {
        return vpnActive && allConnections.isNotEmpty()
    }

    fun emptySubtitleRes(
        searchQuery: String,
        allConnections: List<ClashConnection>,
        filteredConnections: List<ClashConnection>
    ): Int {
        return if (isSearchNoMatch(searchQuery, allConnections, filteredConnections)) {
            R.string.connection_info_no_match
        } else {
            R.string.connection_info_no_active
        }
    }

    fun emptyTitleRes(
        searchQuery: String,
        allConnections: List<ClashConnection>,
        filteredConnections: List<ClashConnection>
    ): Int {
        return if (isSearchNoMatch(searchQuery, allConnections, filteredConnections)) {
            R.string.connection_info_no_match_title
        } else {
            R.string.connection_info_no_connections
        }
    }

    private fun isSearchNoMatch(
        searchQuery: String,
        allConnections: List<ClashConnection>,
        filteredConnections: List<ClashConnection>
    ): Boolean {
        return searchQuery.isNotBlank() &&
            allConnections.isNotEmpty() &&
            filteredConnections.isEmpty()
    }

    fun formatTraffic(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        if (digitGroups >= units.size) return "$bytes B"
        return String.format(
            Locale.US,
            "%.2f %s",
            bytes / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups]
        )
    }

    fun formatDuration(
        startTime: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val startMillis = parseRfc3339Millis(startTime) ?: return ""
        val diff = nowMillis - startMillis
        return if (diff <= 0) {
            "0s"
        } else {
            formatPositiveDuration(diff)
        }
    }

    private fun formatPositiveDuration(diff: Long): String {
        val sec = diff / 1000
        val min = sec / 60
        val hr = min / 60
        return when {
            sec < 60 -> "${sec}s"
            min < 60 -> "${min}m ${sec % 60}s"
            else -> "${hr}h ${min % 60}m"
        }
    }

    private fun parseRfc3339Millis(value: String): Long? {
        if (value.isBlank()) return null
        val match = rfc3339Regex.matchEntire(value) ?: return null
        val base = match.groupValues[1]
        val fraction = match.groups[2]?.value.orEmpty()
        val zone = normalizeZone(match.groupValues[3])
        val millis = fraction.take(3).padEnd(3, '0')
        val normalized = "$base.$millis$zone"

        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                .parse(normalized)
                ?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeZone(zone: String): String {
        return when {
            zone.equals("Z", ignoreCase = true) -> "Z"
            zone.matches(Regex("""[+-]\d{2}$""")) -> "$zone:00"
            zone.matches(Regex("""[+-]\d{4}$""")) -> {
                "${zone.substring(0, 3)}:${zone.substring(3)}"
            }
            else -> zone
        }
    }
}

internal fun formatTraffic(bytes: Long): String {
    return ConnectionInfoUiPolicy.formatTraffic(bytes)
}

internal fun formatDuration(startTime: String): String {
    return ConnectionInfoUiPolicy.formatDuration(startTime)
}
