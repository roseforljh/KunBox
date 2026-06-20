package com.kunk.singbox.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun Modifier.liquidGlassPressFeedback(
    enabled: Boolean = true,
    pressedScale: Float = 0.98f,
    label: String = "liquid_glass_press_feedback_scale",
    onClick: () -> Unit
): Modifier {
    val useLiquidGlass = isLiquidGlassTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (useLiquidGlass && enabled && isPressed) pressedScale else 1f,
        animationSpec = spring(stiffness = 520f, dampingRatio = 0.72f),
        label = label
    )
    val clickModifier = if (useLiquidGlass) {
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
    }.then(clickModifier)
}
