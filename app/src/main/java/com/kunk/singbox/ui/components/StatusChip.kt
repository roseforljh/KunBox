package com.kunk.singbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassPanel

@Composable
private fun Modifier.modeChipIndicatorPanel(indicatorColor: Color): Modifier {
    return if (isLiquidGlassTheme()) {
        size(14.dp)
            .liquidGlassPanel(shape = CircleShape, selected = true, shadowElevation = 3.dp)
    } else {
        size(8.dp)
            .background(indicatorColor, CircleShape)
    }
}

@Composable
fun StatusChip(
    label: String,
    icon: @Composable (() -> Unit)? = null,
    isActive: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val useLiquidGlass = isLiquidGlassTheme()
    val backgroundColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val borderColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val textColor = when {
        useLiquidGlass && isActive -> MaterialTheme.colorScheme.primary
        isActive -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val surfaceModifier = if (useLiquidGlass) {
        Modifier.liquidGlassPanel(
            shape = CircleShape,
            selected = isActive,
            shadowElevation = 6.dp
        )
    } else {
        Modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .border(1.dp, borderColor, CircleShape)
    }
    val modifier = surfaceModifier
        .let {
            if (onClick != null) it.clickable(onClick = onClick) else it
        }
        .padding(horizontal = 12.dp, vertical = 6.dp)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(modifier = Modifier.size(16.dp)) {
                icon()
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
fun ModeChip(
    mode: String,
    indicatorColor: Color = Neutral500,
    onClick: () -> Unit
) {
    val useLiquidGlass = isLiquidGlassTheme()
    StatusChip(
        label = mode,
        icon = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .modeChipIndicatorPanel(indicatorColor)
                )
                if (useLiquidGlass) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(indicatorColor, CircleShape)
                    )
                }
            }
        },
        onClick = onClick
    )
}
