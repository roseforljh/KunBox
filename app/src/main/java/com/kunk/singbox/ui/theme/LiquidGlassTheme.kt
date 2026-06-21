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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import android.graphics.BlurMaskFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kunk.singbox.model.AppThemeStyle

val LocalAppThemeStyle = staticCompositionLocalOf { AppThemeStyle.DEFAULT }

@Composable
fun isLiquidGlassTheme(): Boolean {
    return LocalAppThemeStyle.current == AppThemeStyle.LIQUID_GLASS
}

@Composable
fun liquidGlassMutedContentColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        MaterialTheme.colorScheme.onSurfaceVariant
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

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shadowAlpha = if (isDark) 0.35f else 0.12f

    return this
        .hollowShadow(
            shape = shape,
            alpha = shadowAlpha,
            blurRadius = shadowElevation,
            offsetY = shadowElevation / 2
        )
        .clip(shape)
        .background(liquidGlassPanelBrush(selected = selected))
        .border(
            border = BorderStroke(
                width = if (selected) 1.5.dp else 1.dp,
                brush = liquidGlassPanelBorderBrush(selected = selected)
            ),
            shape = shape
        )
        .alpha(if (enabled) 1f else 0.56f)
}

fun Modifier.hollowShadow(
    shape: Shape,
    color: Color = Color.Black,
    alpha: Float = 0.08f,
    blurRadius: Dp = 12.dp,
    offsetY: Dp = 6.dp
) = this.drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path()
    when (outline) {
        is androidx.compose.ui.graphics.Outline.Rectangle -> path.addRect(outline.rect)
        is androidx.compose.ui.graphics.Outline.Rounded -> path.addRoundRect(outline.roundRect)
        is androidx.compose.ui.graphics.Outline.Generic -> path.addPath(outline.path)
    }
    
    clipPath(path, clipOp = ClipOp.Difference) {
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                this.color = color.copy(alpha = alpha)
            }
            val frameworkPaint = paint.asFrameworkPaint()
            if (blurRadius.toPx() > 0) {
                frameworkPaint.maskFilter = BlurMaskFilter(
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
                window.setDimAmount(0.3f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = 60
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
): Modifier {
    return this.liquidGlassPanel(shape = shape, shadowElevation = shadowElevation)
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
    val primary = MaterialTheme.colorScheme.primary

    return Brush.linearGradient(
        colors = if (selected) {
            listOf(
                Color.White.copy(alpha = if (isDark) 0.15f else 0.85f),
                primary.copy(alpha = if (isDark) 0.45f else 0.15f),
                if (isDark) primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.55f)
            )
        } else {
            listOf(
                Color.White.copy(alpha = if (isDark) 0.14f else 0.65f),
                MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.48f else 0.45f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.24f else 0.25f)
            )
        }
    )
}

@Composable
fun liquidGlassPanelBorderBrush(selected: Boolean = false): Brush {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (selected) {
        androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.78f else 0.62f))
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = if (isDark) 0.35f else 0.85f),
                Color.White.copy(alpha = if (isDark) 0.05f else 0.15f)
            )
        )
    }
}
