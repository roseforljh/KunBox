package com.kunk.singbox.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.liquidGlassFloatingActionPanel(
    shape: Shape = CircleShape,
    shadowElevation: Dp = 12.dp
): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = true, shadowElevation = shadowElevation)
    } else {
        this
    }
}

@Composable
fun liquidGlassFloatingActionContainerColor(defaultColor: Color): Color {
    return liquidGlassTransparentContainerColor(defaultColor)
}

@Composable
fun liquidGlassFloatingActionContentColor(defaultColor: Color): Color {
    return liquidGlassPrimaryContentColor(defaultColor)
}

@Composable
fun Modifier.liquidGlassButtonPanel(
    shape: Shape = RoundedCornerShape(16.dp),
    shadowElevation: Dp = 8.dp
): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = true, shadowElevation = shadowElevation)
    } else {
        this
    }
}

@Composable
fun liquidGlassButtonContainerColor(defaultColor: Color): Color {
    return liquidGlassTransparentContainerColor(defaultColor)
}

@Composable
fun liquidGlassButtonContentColor(
    defaultColor: Color,
    liquidColor: Color = MaterialTheme.colorScheme.primary
): Color {
    return if (isLiquidGlassTheme()) {
        liquidColor
    } else {
        defaultColor
    }
}

@Composable
fun Modifier.liquidGlassTextButtonPanel(
    shape: Shape = RoundedCornerShape(20.dp),
    enabled: Boolean = true,
    shadowElevation: Dp = 4.dp
): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, enabled = enabled, shadowElevation = shadowElevation)
    } else {
        this
    }
}

@Composable
fun Modifier.liquidGlassIconButtonPanel(
    shape: Shape = CircleShape,
    selected: Boolean = false,
    enabled: Boolean = true,
    shadowElevation: Dp = 4.dp
): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(
            shape = shape,
            selected = selected,
            enabled = enabled,
            shadowElevation = shadowElevation
        )
    } else {
        this
    }
}

@Composable
fun liquidGlassOutlinedButtonBorder(defaultBorder: BorderStroke): BorderStroke {
    return if (isLiquidGlassTheme()) {
        BorderStroke(1.dp, Color.Transparent)
    } else {
        defaultBorder
    }
}

@Composable
fun liquidGlassTopAppBarContainerColor(defaultColor: Color): Color {
    return liquidGlassScreenContainerColor(defaultColor)
}

@Composable
fun liquidGlassScreenContainerColor(defaultColor: Color): Color {
    return liquidGlassTransparentContainerColor(defaultColor)
}

@Composable
private fun liquidGlassTransparentContainerColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        Color.Transparent
    } else {
        defaultColor
    }
}

@Composable
private fun liquidGlassPrimaryContentColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        MaterialTheme.colorScheme.primary
    } else {
        defaultColor
    }
}
