package com.kunk.singbox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kunk.singbox.R
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassProgressColor
import com.kunk.singbox.ui.theme.liquidGlassProgressTrackColor

@Composable
private fun Modifier.infoCardPingPressFeedback(
    enabled: Boolean,
    onClick: () -> Unit
): Modifier {
    val useLiquidGlass = isLiquidGlassTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (useLiquidGlass && enabled && isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 520f, dampingRatio = 0.72f),
        label = "liquid_glass_info_card_ping_scale"
    )
    val clickableModifier = if (useLiquidGlass) {
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
    }.then(clickableModifier)
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    uploadSpeed: String = "0 KB/s",
    downloadSpeed: String = "0 KB/s",
    ping: String = "0 ms",
    isPingLoading: Boolean = false,
    onPingClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(16.dp)
    val containerModifier = if (isLiquidGlassTheme()) {
        modifier
            .fillMaxWidth()
            .liquidGlassPanel(shape = shape)
    } else {
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
    }

    Row(
        modifier = containerModifier
            .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        InfoItem(
            icon = Icons.Rounded.ArrowUpward,
            label = stringResource(R.string.info_card_upload),
            value = uploadSpeed
        )
        InfoItem(
            icon = Icons.Rounded.ArrowDownward,
            label = stringResource(R.string.info_card_download),
            value = downloadSpeed
        )
        InfoItem(
            modifier = if (onPingClick != null) {
                Modifier.infoCardPingPressFeedback(
                    enabled = !isPingLoading,
                    onClick = onPingClick
                )
            } else {
                Modifier
            },
            icon = Icons.Rounded.Speed,
            label = stringResource(R.string.info_card_ping),
            value = ping,
            isLoading = isPingLoading
        )
    }
}

@Composable
private fun InfoItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    isLoading: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier.height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = liquidGlassProgressColor(MaterialTheme.colorScheme.onSurface),
                    strokeWidth = 2.dp,
                    trackColor = liquidGlassProgressTrackColor(Color.Transparent)
                )
            } else {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
