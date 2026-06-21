package com.kunk.singbox.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.BlurMaskFilter
import com.kunk.singbox.model.AppThemeStyle

val LocalAppThemeStyle = staticCompositionLocalOf { AppThemeStyle.DEFAULT }

@Composable
fun isLiquidGlassTheme(): Boolean {
    return LocalAppThemeStyle.current == AppThemeStyle.LIQUID_GLASS
}

@Composable
fun liquidGlassMutedContentColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    } else {
        defaultColor
    }
}

@Composable
fun liquidGlassStrongContentColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        MaterialTheme.colorScheme.onSurface
    } else {
        defaultColor
    }
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

    val shadow = liquidGlassPanelShadowSpec(selected = selected)

    val baseModifier = this
        .hollowShadow(
            shape = shape,
            color = shadow.color,
            alpha = shadow.alpha,
            blurRadius = shadowElevation,
            offsetY = shadowElevation / 2
        )
        .clip(shape)
        .background(liquidGlassPanelBrush(selected = selected))
        .border(
            border = BorderStroke(
                width = if (selected) 1.2.dp else 0.8.dp,
                brush = liquidGlassPanelBorderBrush(selected = selected)
            ),
            shape = shape
        )
    return if (!enabled) baseModifier.alpha(0.56f) else baseModifier
}

private data class LiquidGlassShadowSpec(
    val color: Color,
    val alpha: Float
)

@Composable
private fun liquidGlassPanelShadowSpec(selected: Boolean): LiquidGlassShadowSpec {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val color = if (selected) MaterialTheme.colorScheme.primary else Color.Black
    val alpha = when {
        selected && isDark -> 0.40f
        selected -> 0.22f
        isDark -> 0.35f
        else -> 0.12f
    }
    return LiquidGlassShadowSpec(color = color, alpha = alpha)
}

fun Modifier.hollowShadow(
    shape: Shape,
    color: Color = Color.Black,
    alpha: Float = 0.08f,
    blurRadius: Dp = 12.dp,
    offsetY: Dp = 6.dp
): Modifier = this.drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path()
    when (outline) {
        is Outline.Rectangle -> path.addRect(outline.rect)
        is Outline.Rounded -> path.addRoundRect(outline.roundRect)
        is Outline.Generic -> path.addPath(outline.path)
    }

    clipPath(path, clipOp = ClipOp.Difference) {
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                this.color = color.copy(alpha = alpha)
            }
            if (blurRadius.toPx() > 0) {
                paint.asFrameworkPaint().maskFilter = BlurMaskFilter(
                    blurRadius.toPx(),
                    BlurMaskFilter.Blur.NORMAL
                )
            }

            canvas.save()
            canvas.translate(0f, offsetY.toPx())
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }
}

@Composable
fun LiquidGlassDialogEffect() {
    if (isLiquidGlassTheme()) {
        val view = LocalView.current

        LaunchedEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                window.setDimAmount(0.56f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = 120
                    }
                }
            }
        }
    }
}

@Composable
fun Modifier.liquidGlassDialogPanel(
    shape: Shape = RoundedCornerShape(28.dp),
    shadowElevation: Dp = 24.dp
): Modifier = composed {
    if (isLiquidGlassTheme()) {
        val scale = remember { Animatable(0.9f) }
        val alpha = remember { Animatable(0f) }

        LaunchedEffect(Unit) {
            launch { scale.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 400f)) }
            launch { alpha.animateTo(1f, tween(250)) }
        }

        this.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            this.alpha = alpha.value
        }.liquidGlassPanel(shape = shape, shadowElevation = shadowElevation)
    } else {
        this.liquidGlassPanel(shape = shape, shadowElevation = shadowElevation)
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
    shadowElevation: Dp = 0.dp
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
    val primary = MaterialTheme.colorScheme.primary

    return Brush.linearGradient(
        colors = if (selected) {
            if (isDark) {
                // 暗色模式：轻微的主题色高光叠加在毛玻璃上
                listOf(
                    Color.White.copy(alpha = 0.18f),
                    primary.copy(alpha = 0.18f),
                    primary.copy(alpha = 0.10f)
                )
            } else {
                listOf(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                    primary.copy(alpha = 0.12f),
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.18f)
                )
            }
        } else {
            if (isDark) {
                listOf(
                    Color.White.copy(alpha = 0.14f),
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.48f),
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
                )
            } else {
                // 亮色模式下：极具通透半透明感的彩色玻璃折射质感，避免死白块
                listOf(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
                    primary.copy(alpha = 0.08f),
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.14f)
                )
            }
        }
    )
}

@Composable
fun liquidGlassPanelBorderBrush(selected: Boolean = false): Brush {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val primary = MaterialTheme.colorScheme.primary
    return if (selected) {
        androidx.compose.ui.graphics.SolidColor(
            primary.copy(alpha = if (isDark) 0.45f else 0.62f)
        )
    } else {
        Brush.linearGradient(
            colors = if (isDark) {
                listOf(
                    Color.White.copy(alpha = 0.35f),
                    Color.White.copy(alpha = 0.05f)
                )
            } else {
                // 亮色模式下：边缘全反射勾勒，强白色高光渐变到淡淡的 primary 边缘反射
                listOf(
                    Color.White.copy(alpha = 0.90f),
                    primary.copy(alpha = 0.22f)
                )
            }
        )
    }
}
