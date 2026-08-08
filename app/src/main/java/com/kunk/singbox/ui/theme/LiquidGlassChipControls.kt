package com.kunk.singbox.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val FilterSelectedGreen = Color(0xFF22C55E)

@Composable
private fun FilterSelectionIndicator(selected: Boolean) {
    val color by animateColorAsState(
        targetValue = if (selected) {
            FilterSelectedGreen
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "filter_selection_indicator_color"
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .background(color, CircleShape)
    )
}

@Composable
fun LiquidGlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    shadowElevation: Dp = 6.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    selectedContentColor: Color = MaterialTheme.colorScheme.primary,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    showSelectionIndicator: Boolean = true
) {
    val selectionIndicator: (@Composable () -> Unit)? = if (showSelectionIndicator) {
        { FilterSelectionIndicator(selected) }
    } else {
        null
    }
    if (isLiquidGlassTheme()) {
        Box(
            modifier = modifier
                .liquidGlassPressFeedback(
                    pressedScale = 0.96f,
                    label = "liquid_glass_filter_chip_scale",
                    onClick = onClick
                )
                .liquidGlassPanel(
                    shape = shape,
                    selected = selected,
                    shadowElevation = shadowElevation
                )
                .padding(contentPadding),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(
                LocalContentColor provides if (selected) selectedContentColor else unselectedContentColor
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    selectionIndicator?.invoke()
                    label()
                }
            }
        }
    } else {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            modifier = modifier,
            shape = shape,
            colors = colors,
            leadingIcon = selectionIndicator
        )
    }
}
