package com.kunk.singbox.ui.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ViewfinderView
import com.kunk.singbox.R
import com.kunk.singbox.model.AppThemeStyle
import com.kunk.singbox.repository.SettingsRepository
import kotlin.math.min

/**
 */
class SquareViewFinderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewfinderView(context, attrs) {

    private val useLiquidGlass = SettingsRepository.getInstance(context).settings.value.appThemeStyle ==
        AppThemeStyle.LIQUID_GLASS

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = scannerFrameColor(
            defaultColorRes = R.color.zxing_viewfinder_corner,
            liquidColor = Color.argb(218, 255, 255, 255)
        )
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = scannerFrameColor(
            defaultColorRes = R.color.zxing_viewfinder_border,
            liquidColor = Color.argb(118, 255, 255, 255)
        )
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = scannerFrameColor(
            defaultColorRes = R.color.zxing_laser,
            liquidColor = Color.argb(188, 255, 255, 255)
        )
        style = Paint.Style.FILL
    }

    private val maskPaint = Paint().apply {
        color = scannerMaskColor()
    }

    private val cornerLength = 50f

    private var laserY = 0f
    private var laserDirection = 1
    private var squareFrameRect: Rect? = null

    private fun scannerFrameColor(defaultColorRes: Int, liquidColor: Int): Int {
        return if (useLiquidGlass) {
            liquidColor
        } else {
            ContextCompat.getColor(context, defaultColorRes)
        }
    }

    private fun scannerMaskColor(): Int {
        return if (useLiquidGlass) {
            Color.argb(124, 0, 0, 0)
        } else {
            ContextCompat.getColor(context, R.color.zxing_mask)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val frame = calculateSquareFrame()
        if (frame == null) {
            return
        }

        val width = canvas.width
        val height = canvas.height

        canvas.drawRect(0f, 0f, width.toFloat(), frame.top.toFloat(), maskPaint)
        canvas.drawRect(0f, frame.top.toFloat(), frame.left.toFloat(), (frame.bottom + 1).toFloat(), maskPaint)
        canvas.drawRect((frame.right + 1).toFloat(), frame.top.toFloat(), width.toFloat(), (frame.bottom + 1).toFloat(), maskPaint)
        canvas.drawRect(0f, (frame.bottom + 1).toFloat(), width.toFloat(), height.toFloat(), maskPaint)

        canvas.drawRect(
            frame.left.toFloat(),
            frame.top.toFloat(),
            frame.right.toFloat(),
            frame.bottom.toFloat(),
            borderPaint
        )

        drawCorners(canvas, frame)

        drawLaser(canvas, frame)

        postInvalidateDelayed(
            ANIMATION_DELAY,
            frame.left,
            frame.top,
            frame.right,
            frame.bottom
        )
    }

    private fun calculateSquareFrame(): Rect? {
        if (width == 0 || height == 0) {
            return null
        }

        squareFrameRect?.let { return it }

        val minDimension = min(width, height)
        val frameSize = (minDimension * 0.7f).toInt()

        val leftOffset = (width - frameSize) / 2
        val topOffset = (height - frameSize) / 2

        val rect = Rect(
            leftOffset,
            topOffset,
            leftOffset + frameSize,
            topOffset + frameSize
        )
        squareFrameRect = rect
        return rect
    }

    private fun drawCorners(canvas: Canvas, frame: Rect) {
        val left = frame.left.toFloat()
        val top = frame.top.toFloat()
        val right = frame.right.toFloat()
        val bottom = frame.bottom.toFloat()

        canvas.drawLine(left, top + cornerLength, left, top, cornerPaint)
        canvas.drawLine(left, top, left + cornerLength, top, cornerPaint)

        canvas.drawLine(right, top + cornerLength, right, top, cornerPaint)
        canvas.drawLine(right, top, right - cornerLength, top, cornerPaint)

        canvas.drawLine(left, bottom - cornerLength, left, bottom, cornerPaint)
        canvas.drawLine(left, bottom, left + cornerLength, bottom, cornerPaint)

        canvas.drawLine(right, bottom - cornerLength, right, bottom, cornerPaint)
        canvas.drawLine(right, bottom, right - cornerLength, bottom, cornerPaint)
    }

    private fun drawLaser(canvas: Canvas, frame: Rect) {
        val laserHeight = 4f
        val frameHeight = frame.height().toFloat()
        laserY += laserDirection * 6f
        if (laserY > frameHeight - laserHeight) {
            laserY = frameHeight - laserHeight
            laserDirection = -1
        } else if (laserY < 0) {
            laserY = 0f
            laserDirection = 1
        }

        laserPaint.alpha = 200
        canvas.drawRect(
            (frame.left + 16).toFloat(),
            frame.top + laserY,
            (frame.right - 16).toFloat(),
            frame.top + laserY + laserHeight,
            laserPaint
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        squareFrameRect = null
    }

    companion object {
        private const val ANIMATION_DELAY = 30L
    }
}
