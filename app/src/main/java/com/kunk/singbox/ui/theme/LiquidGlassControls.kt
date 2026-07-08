@file:Suppress("TooManyFunctions")

package com.kunk.singbox.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.semantics.Role

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

    return this
        .liquidGlassPanel(shape = shape, selected = true, shadowElevation = shadowElevation)
        .liquidGlassPressBounceEffect()
}

private data class LiquidGlassFloatingActionSurfaceSpec(
    val size: Dp,
    val shadowElevation: Dp
)

@Composable
private fun LiquidGlassFloatingActionSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    spec: LiquidGlassFloatingActionSurfaceSpec,
    contentColor: Color,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(spec.size)
            .liquidGlassFloatingActionPanel(shadowElevation = spec.shadowElevation)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

@Composable
fun LiquidGlassFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    content: @Composable () -> Unit
) {
    if (isLiquidGlassTheme()) {
        LiquidGlassFloatingActionSurface(
            onClick = onClick,
            modifier = modifier,
            spec = LiquidGlassFloatingActionSurfaceSpec(
                size = 56.dp,
                shadowElevation = 12.dp
            ),
            contentColor = liquidGlassFloatingActionContentColor(contentColor),
            content = content
        )
    } else {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier.liquidGlassFloatingActionPanel(),
            containerColor = liquidGlassFloatingActionContainerColor(containerColor),
            contentColor = liquidGlassFloatingActionContentColor(contentColor),
            shape = liquidGlassFloatingActionShape(),
            elevation = liquidGlassFloatingActionElevation(),
            content = content
        )
    }
}

@Composable
fun LiquidGlassSmallFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    content: @Composable () -> Unit
) {
    if (isLiquidGlassTheme()) {
        LiquidGlassFloatingActionSurface(
            onClick = onClick,
            modifier = modifier,
            spec = LiquidGlassFloatingActionSurfaceSpec(
                size = 40.dp,
                shadowElevation = 8.dp
            ),
            contentColor = liquidGlassFloatingActionContentColor(contentColor),
            content = content
        )
    } else {
        SmallFloatingActionButton(
            onClick = onClick,
            modifier = modifier.liquidGlassFloatingActionPanel(),
            containerColor = liquidGlassFloatingActionContainerColor(containerColor),
            contentColor = liquidGlassFloatingActionContentColor(contentColor),
            shape = liquidGlassFloatingActionShape(),
            elevation = liquidGlassFloatingActionElevation(),
            content = content
        )
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

@Suppress("CognitiveComplexMethod")
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
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val bgAlpha = if (selected) { if (isDark) 0.14f else 0.10f } else { if (isDark) 0.06f else 0.04f }
        val borderAlpha = if (selected) { if (isDark) 0.22f else 0.16f } else { if (isDark) 0.10f else 0.08f }
        val bgColor = if (isDark) Color.White.copy(alpha = bgAlpha) else Color.Black.copy(alpha = bgAlpha)
        val borderColor = if (isDark) Color.White.copy(alpha = borderAlpha) else Color.Black.copy(alpha = borderAlpha)

        this.clip(shape)
            .background(bgColor)
            .border(BorderStroke(0.5.dp, borderColor), shape)
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
