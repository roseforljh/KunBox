package com.kunk.singbox.repository

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

internal data class MascotCrop(val left: Float, val top: Float, val side: Float) {
    fun transform(image: IntSize, anchor: Offset, pan: Offset, zoom: Float): MascotCrop {
        val maxSide = minOf(image.width, image.height).toFloat()
        val newSide = (side / zoom).coerceIn((maxSide / 4f).coerceAtLeast(1f), maxSide)
        return MascotCrop(
            left = (left + anchor.x * side - (anchor.x + pan.x) * newSide).coerceIn(0f, image.width - newSide),
            top = (top + anchor.y * side - (anchor.y + pan.y) * newSide).coerceIn(0f, image.height - newSide),
            side = newSide
        )
    }

    companion object {
        fun centered(image: IntSize): MascotCrop {
            val side = minOf(image.width, image.height).toFloat()
            return MascotCrop((image.width - side) / 2, (image.height - side) / 2, side)
        }
    }
}

internal fun Canvas.drawMascotCrop(bitmap: Bitmap, crop: MascotCrop, edge: Float) {
    val scale = edge / crop.side
    val matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate(-crop.left * scale, -crop.top * scale)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
    }
    drawCircle(edge / 2, edge / 2, edge / 2, paint)
}

internal fun Bitmap.cropMascot(crop: MascotCrop): Bitmap {
    val edge = crop.side.roundToInt().coerceIn(128, 512)
    return Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawMascotCrop(this, crop, edge.toFloat())
    }
}

internal fun Bitmap.hasTransparentMascotBackground(): Boolean {
    if (!hasAlpha()) return false
    val border = IntArray(2 * (width + height))
    getPixels(border, 0, width, 0, 0, width, 1)
    getPixels(border, width, width, 0, height - 1, width, 1)
    getPixels(border, 2 * width, 1, 0, 0, 1, height)
    getPixels(border, 2 * width + height, 1, width - 1, 0, 1, height)
    return hasTransparentMascotBorder(border)
}

// ponytail: use border alpha as the default; add a shape override if ambiguous images need one.
internal fun hasTransparentMascotBorder(border: IntArray): Boolean =
    border.count { it ushr 24 < 32 } >= (border.size / 20).coerceAtLeast(2)
