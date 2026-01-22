package com.kunk.singbox.ui.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ViewfinderView
import com.kunk.singbox.R
import kotlin.math.min

/**
 * 自定义正方形扫描框 ViewFinder
 * 确保扫描框始终为正方形，适合二维码扫描
 */
class SquareViewFinderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewfinderView(context, attrs) {

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.zxing_viewfinder_corner)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.zxing_viewfinder_border)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.zxing_laser)
        style = Paint.Style.FILL
    }

    private val maskPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.zxing_mask)
    }

    private val cornerLength = 50f

    private var laserY = 0f
    private var laserDirection = 1 // 1: 向下, -1: 向上
    private var squareFrameRect: Rect? = null

    override fun onDraw(canvas: Canvas) {
        val frame = calculateSquareFrame()
        if (frame == null) {
            return
        }

        val width = canvas.width
        val height = canvas.height

        // 绘制遮罩层（扫描框外的半透明区域）
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top.toFloat(), maskPaint)
        canvas.drawRect(0f, frame.top.toFloat(), frame.left.toFloat(), (frame.bottom + 1).toFloat(), maskPaint)
        canvas.drawRect((frame.right + 1).toFloat(), frame.top.toFloat(), width.toFloat(), (frame.bottom + 1).toFloat(), maskPaint)
        canvas.drawRect(0f, (frame.bottom + 1).toFloat(), width.toFloat(), height.toFloat(), maskPaint)

        // 绘制边框
        canvas.drawRect(
            frame.left.toFloat(),
            frame.top.toFloat(),
            frame.right.toFloat(),
            frame.bottom.toFloat(),
            borderPaint
        )

        // 绘制四个角
        drawCorners(canvas, frame)

        // 绘制激光线（扫描动画）
        drawLaser(canvas, frame)

        // 请求重绘以实现动画效果
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

        // 如果已经计算过，直接返回
        squareFrameRect?.let { return it }

        // 计算正方形扫描框的大小
        // 取屏幕宽度和高度中较小的一个，再乘以一个比例
        val minDimension = min(width, height)
        val frameSize = (minDimension * 0.7f).toInt()

        // 确保扫描框在屏幕中央
        val leftOffset = (width - frameSize) / 2
        val topOffset = (height - frameSize) / 2

        // 设置正方形的扫描框
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

        // 左上角
        canvas.drawLine(left, top + cornerLength, left, top, cornerPaint)
        canvas.drawLine(left, top, left + cornerLength, top, cornerPaint)

        // 右上角
        canvas.drawLine(right, top + cornerLength, right, top, cornerPaint)
        canvas.drawLine(right, top, right - cornerLength, top, cornerPaint)

        // 左下角
        canvas.drawLine(left, bottom - cornerLength, left, bottom, cornerPaint)
        canvas.drawLine(left, bottom, left + cornerLength, bottom, cornerPaint)

        // 右下角
        canvas.drawLine(right, bottom - cornerLength, right, bottom, cornerPaint)
        canvas.drawLine(right, bottom, right - cornerLength, bottom, cornerPaint)
    }

    private fun drawLaser(canvas: Canvas, frame: Rect) {
        val laserHeight = 4f
        val frameHeight = frame.height().toFloat()

        // 更新激光线位置
        laserY += laserDirection * 6f
        if (laserY > frameHeight - laserHeight) {
            laserY = frameHeight - laserHeight
            laserDirection = -1
        } else if (laserY < 0) {
            laserY = 0f
            laserDirection = 1
        }

        // 绘制渐变激光线效果
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
        // 尺寸变化时重新计算扫描框
        squareFrameRect = null
    }

    companion object {
        private const val ANIMATION_DELAY = 30L
    }
}
