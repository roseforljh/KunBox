package com.kunk.singbox.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation

fun Modifier.liquidGlassPressBounceEffect(): Modifier = composed {
    if (!isLiquidGlassTheme()) return@composed this

    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    waitForUpOrCancellation()
                    isPressed = false
                }
            }
        }
}

@Composable
fun Modifier.liquidGlassFloatingActionPanel(
    shape: Shape = CircleShape,
    shadowElevation: Dp = 12.dp
): Modifier {
    if (!isLiquidGlassTheme()) {
        return this
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shadowAlpha = if (isDark) 0.4f else 0.18f

    // 高亮液态玻璃笔刷，增强边缘和高光的折射质感
    val fabBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = if (isDark) 0.20f else 0.70f),
            MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.40f else 0.55f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.18f else 0.30f)
        )
    )

    // 亮暗折射的边框渐变，呈现高级实体玻璃边缘
    val borderBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = if (isDark) 0.35f else 0.85f),
            Color.White.copy(alpha = if (isDark) 0.10f else 0.30f)
        )
    )

    return this
        .hollowShadow(
            shape = shape,
            alpha = shadowAlpha,
            blurRadius = shadowElevation,
            offsetY = shadowElevation / 2
        )
        .clip(shape)
        .background(brush = fabBrush)
        .border(
            border = BorderStroke(width = 1.dp, brush = borderBrush),
            shape = shape
        )
        .liquidGlassPressBounceEffect()
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
fun liquidGlassFloatingActionShape(): Shape {
    return if (isLiquidGlassTheme()) {
        CircleShape
    } else {
        FloatingActionButtonDefaults.shape
    }
}

@Composable
fun liquidGlassFloatingActionElevation(): FloatingActionButtonElevation {
    return if (isLiquidGlassTheme()) {
        FloatingActionButtonDefaults.elevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp
        )
    } else {
        FloatingActionButtonDefaults.elevation()
    }
}

@Composable
fun Modifier.liquidGlassButtonPanel(
    shape: Shape = RoundedCornerShape(16.dp),
    shadowElevation: Dp = 8.dp
): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = true, shadowElevation = shadowElevation)
            .liquidGlassPressBounceEffect()
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
            .liquidGlassPressBounceEffect()
    } else {
        this
    }
}

@Composable
fun liquidGlassTextButtonContentColor(
    defaultColor: Color,
    liquidColor: Color = defaultColor
): Color {
    return if (isLiquidGlassTheme()) {
        liquidColor
    } else {
        defaultColor
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
        ).liquidGlassPressBounceEffect()
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
fun Modifier.liquidGlassEmptyStatePanel(
    shape: Shape = RoundedCornerShape(20.dp),
    shadowElevation: Dp = 8.dp
): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = shadowElevation)
    } else {
        this
    }
}

@Composable
fun Modifier.liquidGlassLoadingStatePanel(
    shape: Shape = RoundedCornerShape(20.dp),
    shadowElevation: Dp = 8.dp,
    contentPadding: Dp = 24.dp
): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = shadowElevation)
            .padding(contentPadding)
    } else {
        this
    }
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
