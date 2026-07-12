package com.kunk.singbox.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.liquidGlassPressFeedback(
    enabled: Boolean = true,
    useLiquidGlass: Boolean = isLiquidGlassTheme(),
    pressedScale: Float = 0.98f,
    label: String = "liquid_glass_press_feedback_scale",
    animationSpec: FiniteAnimationSpec<Float> = spring(stiffness = 520f, dampingRatio = 0.72f),
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)?
): Modifier {
    if (onClick == null) return this

    val indication = if (useLiquidGlass) null else LocalIndication.current
    if (!useLiquidGlass) {
        return if (onLongClick == null) {
            clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick
            )
        } else {
            combinedClickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = indication,
                onLongClick = onLongClick,
                onClick = onClick
            )
        }
    }

    val rememberedInteractionSource = remember { MutableInteractionSource() }
    val source = interactionSource ?: rememberedInteractionSource
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) pressedScale else 1f,
        animationSpec = animationSpec,
        label = label
    )
    val clickModifier = if (onLongClick == null) {
        Modifier.clickable(
            enabled = enabled,
            interactionSource = source,
            indication = indication,
            onClick = onClick
        )
    } else {
        Modifier.combinedClickable(
            enabled = enabled,
            interactionSource = source,
            indication = indication,
            onLongClick = onLongClick,
            onClick = onClick
        )
    }

    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.then(clickModifier)
}
