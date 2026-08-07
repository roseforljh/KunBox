package com.kunk.singbox.ui.theme

import android.graphics.BlurMaskFilter
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kunk.singbox.model.AppThemeStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

val LocalAppThemeStyle = staticCompositionLocalOf { AppThemeStyle.DEFAULT }
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

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
@Suppress("LongMethod", "CognitiveComplexMethod")
fun Modifier.liquidGlassPanel(
    shape: Shape = RoundedCornerShape(16.dp),
    selected: Boolean = false,
    dialog: Boolean = false,
    enabled: Boolean = true,
    shadowElevation: Dp = 12.dp,
    hazeState: HazeState? = null,
    forceBackdropUpdates: Boolean = false
): Modifier {
    if (!isLiquidGlassTheme()) {
        return this
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val backdrop = LocalLiquidGlassBackdrop.current
    val controlSurfaceColor = if (isDark) Color.Black else Color.White
    val controlSurfaceTint = controlSurfaceColor.copy(alpha = if (isDark) 0.84f else 0.88f)
    val dialogSurfaceTint = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.08f)
    val activeSurfaceTint = if (dialog) dialogSurfaceTint else controlSurfaceTint
    val edgeShadowColor = if (isDark) Color.White else Color.Black
    val edgeShadowAlpha = if (isDark) 0.08f else 0.12f
    val edgeShadowBlurRadius = if (isDark) shadowElevation / 2 else shadowElevation
    val edgeShadowOffsetY = if (isDark) 0.dp - shadowElevation / 3 else shadowElevation / 2
    val backdropEffect = when {
        backdrop != null -> Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                val opticsScale = (size.minDimension / 64.dp.toPx()).coerceIn(0.25f, 1f)
                vibrancy()
                if (dialog) {
                    blur(0.75.dp.toPx() * opticsScale)
                    lens(
                        refractionHeight = 48.dp.toPx() * opticsScale,
                        refractionAmount = 28.dp.toPx() * opticsScale,
                        chromaticAberration = false
                    )
                } else {
                    blur(0.5.dp.toPx() * opticsScale)
                    lens(
                        refractionHeight = 40.dp.toPx() * opticsScale,
                        refractionAmount = 60.dp.toPx() * opticsScale,
                        chromaticAberration = false
                    )
                }
            },
            // 控件材料自行塑形，关闭 Backdrop 默认绘制的白色高光边。
            highlight = null,
            shadow = null,
            onDrawSurface = { drawRect(activeSurfaceTint) }
        )
        hazeState != null -> Modifier
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = liquidGlassHazeStyle(isDark = isDark)
            ) {
                // 弹窗独立窗口无法读取主窗口采样时，保留逐帧 Haze 回退。
                forceInvalidateOnPreDraw = forceBackdropUpdates
                expandLayerBounds = true
            }
        else -> Modifier.background(activeSurfaceTint, shape)
    }
    val baseModifier = this
        .hollowShadow(
            shape = shape,
            color = edgeShadowColor,
            alpha = edgeShadowAlpha,
            blurRadius = edgeShadowBlurRadius,
            offsetY = edgeShadowOffsetY
        )
        .then(backdropEffect)
        .liquidGlassMaterial(
            shape = shape,
            selected = selected,
            dialog = dialog,
            accented = true,
            backdropVisible = true
        )
    return if (!enabled) baseModifier.alpha(0.56f) else baseModifier
}

@Composable
private fun liquidGlassHazeStyle(isDark: Boolean): HazeStyle {
    val background = MaterialTheme.colorScheme.background
    val blurRadius = with(LocalDensity.current) { 20f.toDp() }
    return HazeStyle(
        backgroundColor = background.copy(alpha = if (isDark) 0.58f else 0.50f),
        tint = HazeTint(Color.White.copy(alpha = if (isDark) 0.012f else 0.02f)),
        blurRadius = blurRadius,
        noiseFactor = 0f,
        fallbackTint = HazeTint(background.copy(alpha = 0.72f))
    )
}

@Composable
fun Modifier.liquidGlassMaterial(
    shape: Shape,
    selected: Boolean = false,
    dialog: Boolean = false,
    accented: Boolean = true,
    backdropVisible: Boolean = true
): Modifier {
    if (!isLiquidGlassTheme()) {
        return this
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val selectedSurfaceModifier = if (selected && !accented) {
        Modifier.background(
            color = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f),
            shape = shape
        )
    } else {
        Modifier
    }
    return clip(shape)
        .then(selectedSurfaceModifier)
        .liquidGlassCrystalSurface(
            shape = shape,
            selected = selected,
            isDark = isDark,
            accented = accented,
            panelBrush = liquidGlassPanelBrush(
                selected = selected,
                dialog = dialog,
                accented = accented,
                backdropVisible = backdropVisible
            )
        )
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
private fun Modifier.liquidGlassCrystalSurface(
    shape: Shape,
    selected: Boolean,
    isDark: Boolean,
    accented: Boolean,
    panelBrush: Brush
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val surfacePath = Path().apply {
        when (outline) {
            is Outline.Rectangle -> addRect(outline.rect)
            is Outline.Rounded -> addRoundRect(outline.roundRect)
            is Outline.Generic -> addPath(outline.path)
        }
    }
    val maxDimension = maxOf(size.width, size.height).coerceAtLeast(1f)
    val opticsScale = (size.minDimension / 64.dp.toPx()).coerceIn(0.35f, 1f)
    val rimAlphaScale = 0.72f + (0.28f * opticsScale)
    val outerRimWidth = 0.9.dp.toPx() * opticsScale
    val innerRimWidth = 3.2.dp.toPx() * opticsScale
    val embossedRimWidth = (if (isDark) 2.6.dp else 2.dp).toPx() * opticsScale
    val highlightColor = Color.White
    // 常规透明合成避免滚动或页面过渡时离屏图层改变中性色。
    val highlightBlendMode = if (accented) BlendMode.SrcOver else BlendMode.Screen
    val causticAlpha = when {
        accented && selected -> if (isDark) 0.22f else 0.16f
        accented -> if (isDark) 0.16f else 0.10f
        selected -> if (isDark) 0.24f else 0.38f
        else -> if (isDark) 0.17f else 0.29f
    } * opticsScale
    val causticBrush = Brush.radialGradient(
        colors = listOf(
            highlightColor.copy(alpha = causticAlpha),
            highlightColor.copy(
                alpha = if (accented) {
                    (if (isDark) 0.055f else 0.035f) * opticsScale
                } else {
                    (if (isDark) 0.045f else 0.075f) * opticsScale
                }
            ),
            Color.Transparent
        ),
        center = Offset(size.width * 0.16f, -size.height * 0.08f),
        radius = maxDimension * 0.74f
    )
    val depthBrush = Brush.radialGradient(
        colors = listOf(
            Color.Black.copy(alpha = if (isDark) 0.12f else 0.045f),
            Color.Transparent
        ),
        center = Offset(size.width * 1.03f, size.height * 1.08f),
        radius = maxDimension * 0.72f
    )
    val outerRimBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color.White.copy(alpha = (if (isDark) 0.62f else 0.96f) * rimAlphaScale),
            0.28f to Color.White.copy(alpha = (if (isDark) 0.28f else 0.56f) * rimAlphaScale),
            0.56f to Color.White.copy(alpha = (if (isDark) 0.10f else 0.24f) * rimAlphaScale),
            0.78f to Color.White.copy(alpha = (if (isDark) 0.16f else 0.34f) * rimAlphaScale),
            1f to Color.White.copy(alpha = (if (isDark) 0.42f else 0.72f) * rimAlphaScale)
        ),
        start = Offset(-size.width * 0.08f, -size.height * 0.16f),
        end = Offset(size.width * 1.06f, size.height * 1.12f)
    )
    val innerRimBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color.White.copy(alpha = (if (isDark) 0.26f else 0.48f) * opticsScale),
            0.38f to Color.Transparent,
            0.72f to Color.White.copy(alpha = (if (isDark) 0.10f else 0.20f) * opticsScale),
            1f to Color.White.copy(alpha = (if (isDark) 0.12f else 0.28f) * opticsScale)
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )
    val embossedRimBrush = Brush.linearGradient(
        colorStops = if (isDark) {
            arrayOf(
                0f to Color.White.copy(alpha = 0.24f * opticsScale),
                0.32f to Color.White.copy(alpha = 0.05f * opticsScale),
                0.66f to Color.Black.copy(alpha = 0.24f),
                1f to Color.Black.copy(alpha = 0.72f)
            )
        } else {
            arrayOf(
                0f to Color.White.copy(alpha = 0.92f * opticsScale),
                0.34f to Color.White.copy(alpha = 0.28f * opticsScale),
                0.66f to Color.Black.copy(alpha = 0.035f),
                1f to Color.Black.copy(alpha = 0.20f)
            )
        },
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )
    val specularBrush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            0.18f to Color.White.copy(alpha = (if (isDark) 0.20f else 0.42f) * opticsScale),
            0.52f to Color.White.copy(alpha = (if (isDark) 0.52f else 0.90f) * opticsScale),
            1f to Color.Transparent
        ),
        startX = size.width * 0.06f,
        endX = size.width * 0.70f
    )

    onDrawWithContent {
        clipPath(surfacePath) {
            drawPath(path = surfacePath, brush = panelBrush)
            drawRect(brush = causticBrush, blendMode = highlightBlendMode)
            drawRect(brush = depthBrush)

            if (accented) {
                // 同色表面通过斜向明暗边缘形成浮雕，暗色模式使用内高光保持轮廓。
                drawPath(
                    path = surfacePath,
                    brush = embossedRimBrush,
                    style = Stroke(width = embossedRimWidth)
                )
            } else {
                drawPath(
                    path = surfacePath,
                    brush = innerRimBrush,
                    style = Stroke(width = innerRimWidth)
                )
            }
        }

        drawContent()

        if (!accented) {
            clipPath(surfacePath) {
                drawLine(
                    brush = specularBrush,
                    start = Offset(size.width * 0.08f, 1.3.dp.toPx()),
                    end = Offset(size.width * 0.68f, 1.3.dp.toPx()),
                    strokeWidth = 1.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    blendMode = highlightBlendMode
                )
            }
            drawPath(
                path = surfacePath,
                brush = outerRimBrush,
                style = Stroke(width = outerRimWidth)
            )
        }
    }
}

@Composable
fun Modifier.liquidGlassBackdrop(): Modifier {
    if (!isLiquidGlassTheme()) {
        return this
    }

    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    return drawWithCache {
        val baseBrush = Brush.verticalGradient(
            colors = listOf(surface, background, background)
        )

        onDrawBehind {
            drawRect(brush = baseBrush)
        }
    }
}

fun Modifier.hollowShadow(
    shape: Shape,
    color: Color = Color.Black,
    alpha: Float = 0.08f,
    blurRadius: Dp = 12.dp,
    offsetY: Dp = 6.dp
): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path()
    when (outline) {
        is Outline.Rectangle -> path.addRect(outline.rect)
        is Outline.Rounded -> path.addRoundRect(outline.roundRect)
        is Outline.Generic -> path.addPath(outline.path)
    }

    val blurRadiusPx = blurRadius.toPx()
    val offsetYPx = offsetY.toPx()
    val paint = Paint().apply {
        this.color = color.copy(alpha = alpha)
        if (blurRadiusPx > 0) {
            asFrameworkPaint().maskFilter = BlurMaskFilter(
                blurRadiusPx,
                BlurMaskFilter.Blur.NORMAL
            )
        }
    }

    onDrawBehind {
        clipPath(path, clipOp = ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.translate(0f, offsetYPx)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
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
        }.liquidGlassPanel(shape = shape, dialog = true, shadowElevation = shadowElevation)
    } else {
        this.liquidGlassPanel(shape = shape, dialog = true, shadowElevation = shadowElevation)
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
@Suppress("LongMethod")
fun liquidGlassPanelBrush(
    selected: Boolean = false,
    dialog: Boolean = false,
    accented: Boolean = true,
    backdropVisible: Boolean = false
): Brush {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    return Brush.linearGradient(
        colors = when {
            dialog && isDark -> listOf(
                Color.White.copy(alpha = 0.018f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.024f),
                Color.Transparent,
                Color.White.copy(alpha = 0.008f)
            )
            dialog -> listOf(
                Color.White.copy(alpha = 0.055f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.025f),
                Color.Transparent,
                Color.White.copy(alpha = 0.015f)
            )
            accented && selected && isDark -> listOf(
                Color.Black.copy(alpha = 0.96f),
                Color.Black.copy(alpha = 0.90f),
                Color.Black.copy(alpha = 0.84f),
                Color.Black.copy(alpha = 0.92f)
            )
            accented && selected -> listOf(
                Color.White.copy(alpha = 0.96f),
                Color.White.copy(alpha = 0.90f),
                Color.White.copy(alpha = 0.84f),
                Color.White.copy(alpha = 0.92f)
            )
            accented && isDark -> listOf(
                Color.Black.copy(alpha = 0.90f),
                Color.Black.copy(alpha = 0.84f),
                Color.Black.copy(alpha = 0.76f),
                Color.Black.copy(alpha = 0.86f)
            )
            accented -> listOf(
                Color.White.copy(alpha = 0.90f),
                Color.White.copy(alpha = 0.84f),
                Color.White.copy(alpha = 0.76f),
                Color.White.copy(alpha = 0.86f)
            )
            backdropVisible && isDark -> listOf(
                Color.White.copy(alpha = 0.025f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.035f),
                Color.Transparent,
                Color.White.copy(alpha = 0.012f)
            )
            backdropVisible -> listOf(
                Color.White.copy(alpha = 0.08f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.035f),
                Color.Transparent,
                Color.White.copy(alpha = 0.025f)
            )
            selected && isDark -> listOf(
                Color.White.copy(alpha = 0.11f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.20f),
                Color.White.copy(alpha = 0.055f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f)
            )
            selected -> listOf(
                Color.White.copy(alpha = 0.31f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
                Color.White.copy(alpha = 0.15f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f)
            )
            isDark -> listOf(
                Color.White.copy(alpha = 0.065f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                Color.White.copy(alpha = 0.03f)
            )
            else -> listOf(
                Color.White.copy(alpha = 0.24f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.16f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.10f)
            )
        },
        start = Offset.Zero,
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )
}
