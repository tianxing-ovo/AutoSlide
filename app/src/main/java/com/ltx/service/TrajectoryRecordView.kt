package com.ltx.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColorInt

/**
 * 轨迹录制视图
 *
 * @author tianxing
 */
@SuppressLint("ViewConstructor")
class TrajectoryRecordView(
    context: Context,
    private val instructionText: String,
    private val onTrajectoryRecorded: (List<PointF>) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val points = mutableListOf<PointF>()
    private val path = Path()
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 60f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    init {
        setBackgroundColor("#44000000".toColorInt())
    }

    /**
     * 处理触摸事件
     *
     * @param event 触摸事件
     * @return 是否消费事件
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                path.reset()
                recordPoint(event)
                invalidate()
            }
            // 移动事件
            MotionEvent.ACTION_MOVE -> {
                val lastPoint = points.lastOrNull() ?: return true
                val dx = event.rawX - lastPoint.x
                val dy = event.rawY - lastPoint.y
                if (dx * dx + dy * dy > MIN_POINT_DISTANCE_SQ) {
                    recordPoint(event)
                    invalidate()
                }
            }
            // 抬起事件
            MotionEvent.ACTION_UP -> {
                if (points.size > 1) {
                    onTrajectoryRecorded(points.toList())
                } else {
                    onCancel()
                }
            }
            // 取消事件
            MotionEvent.ACTION_CANCEL -> onCancel()
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    /**
     * 记录触摸点
     *
     * @param event 触摸事件
     */
    private fun recordPoint(event: MotionEvent) {
        points.add(PointF(event.rawX, event.rawY))
        if (points.size == 1) {
            path.moveTo(event.x, event.y)
        } else {
            path.lineTo(event.x, event.y)
        }
    }

    /**
     * 绘制轨迹和指示文字
     *
     * @param canvas 画布
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
        if (points.isEmpty()) {
            canvas.drawText(instructionText, width / 2f, height / 2f, textPaint)
        }
    }

    companion object {
        private const val MIN_POINT_DISTANCE_PX = 5f
        private const val MIN_POINT_DISTANCE_SQ = MIN_POINT_DISTANCE_PX * MIN_POINT_DISTANCE_PX
    }
}
