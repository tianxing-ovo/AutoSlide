package com.ltx.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 自动滑动无障碍服务
 *
 * @author tianxing
 */
@SuppressLint("AccessibilityPolicy")
class AutoSlideService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var screenWidth = 0
    private var centerY = 0
    private var speed = DEFAULT_SPEED
    private var needPause = false
    private var pauseTime = DEFAULT_PAUSE_SECONDS
    private var currentDirection = DIRECTION_LEFT
    private var isRunning = false

    /* 自动滑动主循环*/
    private val slideRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                return
            }
            performSlideByDirection()
            handler.postDelayed(this, calculateDelayMillis())
        }
    }

    companion object {
        private const val DEFAULT_SPEED = 50
        private const val DEFAULT_PAUSE_SECONDS = 1
        private const val DIRECTION_UP = "up"
        private const val DIRECTION_DOWN = "down"
        private const val DIRECTION_LEFT = "left"
        private const val DIRECTION_RIGHT = "right"
        private const val GESTURE_DURATION_MS = 500L
        private const val MIN_SLIDE_DELAY_MS = 120L
        private const val TOP_Y = 300f
        private const val BOTTOM_Y = 1500f
        private const val LEFT_X = 100f
        private const val RIGHT_X = 900f
        private var instance: AutoSlideService? = null

        /**
         * 获取服务单例实例
         *
         * @return 当前服务实例
         */
        @JvmStatic
        fun getInstance(): AutoSlideService? = instance
    }

    /**
     * 设置滑动方向
     *
     * @param direction 目标方向字符串(up/down/left/right)
     */
    fun setDirection(direction: String) {
        currentDirection = when (direction) {
            DIRECTION_UP, DIRECTION_DOWN, DIRECTION_LEFT, DIRECTION_RIGHT -> direction
            else -> DIRECTION_LEFT
        }
    }

    /**
     * 接收外部启动参数并开始自动滑动
     *
     * @param intent 启动参数(包含速度与停顿配置)
     * @param flags 系统启动标记
     * @param startId 启动请求ID
     * @return 固定返回START_STICKY
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            updateConfigFromIntent(it)
            startAutoSlide()
        }
        return START_STICKY
    }

    /**
     * 服务连接完成后初始化屏幕参数并注册单例
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        screenWidth = resources.displayMetrics.widthPixels * 2 / 3
        centerY = resources.displayMetrics.heightPixels / 2
    }

    /**
     * 服务销毁时停止滑动并释放单例
     */
    override fun onDestroy() {
        stopSlide()
        instance = null
        super.onDestroy()
    }

    /**
     * 停止自动滑动循环
     */
    fun stopSlide() {
        if (!isRunning) {
            return
        }
        isRunning = false
        handler.removeCallbacks(slideRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * 执行向上滑动
     */
    fun slideUp() {
        dispatchLineGesture(
            screenWidth.toFloat(), TOP_Y, screenWidth.toFloat(), BOTTOM_Y
        )
    }

    /**
     * 执行向下滑动
     */
    fun slideDown() {
        dispatchLineGesture(
            screenWidth.toFloat(), BOTTOM_Y, screenWidth.toFloat(), TOP_Y
        )
    }

    /**
     * 执行向左滑动
     */
    fun slideLeft() {
        dispatchLineGesture(
            LEFT_X, centerY.toFloat(), RIGHT_X, centerY.toFloat()
        )
    }

    /**
     * 执行向右滑动
     */
    fun slideRight() {
        dispatchLineGesture(
            RIGHT_X, centerY.toFloat(), LEFT_X, centerY.toFloat()
        )
    }

    /**
     * 从Intent中读取运行参数
     *
     * @param intent 启动参数
     */
    private fun updateConfigFromIntent(intent: Intent) {
        speed = intent.getIntExtra("speed", DEFAULT_SPEED)
        needPause = intent.getBooleanExtra("needPause", false)
        pauseTime = intent.getIntExtra("pauseTime", DEFAULT_PAUSE_SECONDS)
    }

    /**
     * 启动自动滑动循环
     */
    private fun startAutoSlide() {
        isRunning = true
        handler.removeCallbacks(slideRunnable)
        handler.post(slideRunnable)
    }

    /**
     * 按当前方向执行一次滑动
     */
    private fun performSlideByDirection() {
        when (currentDirection) {
            DIRECTION_UP -> slideUp()
            DIRECTION_DOWN -> slideDown()
            DIRECTION_RIGHT -> slideRight()
            else -> slideLeft()
        }
    }

    /**
     * 计算下一次滑动延迟
     *
     * @return 延迟毫秒值
     */
    private fun calculateDelayMillis(): Long {
        if (needPause) {
            return pauseTime.coerceAtLeast(0) * 1000L
        }
        return ((100 - speed.coerceIn(0, 100)) * 20L).coerceAtLeast(MIN_SLIDE_DELAY_MS)
    }

    /**
     * 分发一条线性手势
     *
     * @param startX 起点X
     * @param startY 起点Y
     * @param endX 终点X
     * @param endY 终点Y
     */
    private fun dispatchLineGesture(startX: Float, startY: Float, endX: Float, endY: Float) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        // 构建并分发手势
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS)
        ).build()
        // 分发手势
        dispatchGesture(gesture, null, null)
    }
}