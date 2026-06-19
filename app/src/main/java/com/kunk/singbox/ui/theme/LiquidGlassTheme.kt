package com.kunk.singbox.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kunk.singbox.model.AppThemeStyle

val LocalAppThemeStyle = staticCompositionLocalOf { AppThemeStyle.DEFAULT }

@Composable
fun isLiquidGlassTheme(): Boolean {
    return LocalAppThemeStyle.current == AppThemeStyle.LIQUID_GLASS
}

@Composable
fun Modifier.liquidGlassPanel(
    shape: Shape = RoundedCornerShape(16.dp),
    selected: Boolean = false,
    enabled: Boolean = true,
    shadowElevation: Dp = 12.dp
): Modifier {
    if (!isLiquidGlassTheme()) {
        return this
    }

    return this
        .shadow(elevation = shadowElevation, shape = shape, clip = false)
        .clip(shape)
        .background(liquidGlassPanelBrush(selected = selected))
        .border(
            border = BorderStroke(
                width = if (selected) 1.5.dp else 1.dp,
                color = liquidGlassPanelBorderColor(selected = selected)
            ),
            shape = shape
        )
        .alpha(if (enabled) 1f else 0.56f)
}

@Composable
fun Modifier.liquidGlassDialogPanel(
    shape: Shape = RoundedCornerShape(28.dp),
    shadowElevation: Dp = 24.dp
): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = shadowElevation)
    } else {
        this
    }
}

@Composable
fun liquidGlassDialogContainerColor(): Color {
    return if (isLiquidGlassTheme()) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.surface
    }
}

@Composable
fun Modifier.liquidGlassTextFieldPanel(
    shape: Shape = RoundedCornerShape(16.dp),
    shadowElevation: Dp = 8.dp
): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = shadowElevation)
    } else {
        this
    }
}

@Composable
fun liquidGlassTextFieldContainerColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        Color.Transparent
    } else {
        defaultColor
    }
}

@Composable
fun liquidGlassTextFieldBorderColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        Color.Transparent
    } else {
        defaultColor
    }
}

@Composable
fun liquidGlassPanelBrush(selected: Boolean = false): Brush {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary

    return Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = if (isDark) 0.18f else 0.62f),
            surface.copy(alpha = if (isDark) 0.54f else 0.72f),
            if (selected) {
                primary.copy(alpha = if (isDark) 0.18f else 0.16f)
            } else {
                surfaceVariant.copy(alpha = if (isDark) 0.28f else 0.36f)
            }
        )
    )
}

@Composable
fun liquidGlassPanelBorderColor(selected: Boolean = false): Color {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.78f else 0.62f)
    } else {
        Color.White.copy(alpha = if (isDark) 0.18f else 0.68f)
    }
}
