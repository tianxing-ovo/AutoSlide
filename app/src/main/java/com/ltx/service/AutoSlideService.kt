package com.ltx.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.ltx.DEFAULT_MAX_PAUSE_TIME
import com.ltx.DEFAULT_MIN_PAUSE_TIME
import com.ltx.DEFAULT_PAUSE_TIME
import com.ltx.DEFAULT_SPEED
import com.ltx.DIRECTION_DOWN
import com.ltx.DIRECTION_LEFT
import com.ltx.DIRECTION_RIGHT
import com.ltx.DIRECTION_UP
import com.ltx.KEY_MAX_PAUSE_TIME
import com.ltx.KEY_MIN_PAUSE_TIME
import com.ltx.KEY_PAUSE_MODE
import com.ltx.KEY_PAUSE_TIME
import com.ltx.KEY_SPEED
import com.ltx.PAUSE_MODE_FIXED
import com.ltx.PAUSE_MODE_NONE
import com.ltx.PAUSE_MODE_RANDOM
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * 自动滑动无障碍服务
 *
 * @author tianxing
 */
@SuppressLint("AccessibilityPolicy")
class AutoSlideService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isScreenOffReceiverRegistered = false
    private var speed = DEFAULT_SPEED
    private var pauseMode = PAUSE_MODE_NONE
    private var pauseTime = DEFAULT_PAUSE_TIME
    private var minPauseTime = DEFAULT_MIN_PAUSE_TIME
    private var maxPauseTime = DEFAULT_MAX_PAUSE_TIME
    private var currentDirection = DIRECTION_LEFT
    private var isRunning = false

    /* 自动滑动主循环 */
    private val slideRunnable = Runnable { runSlide() }

    /* 执行一次自动滑动 */
    private fun runSlide() {
        if (!isRunning) {
            return
        }
        // 计算手势持续时间
        val gestureDurationMillis = calculateGestureDurationMillis()
        // 执行滑动
        performSlideByDirection(gestureDurationMillis)
        // 计算并设置下一次滑动时间
        handler.postDelayed(slideRunnable, gestureDurationMillis + calculatePauseDelayMillis())
    }

    /* 息屏时强制停止滑动 */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF || !isRunning) {
                return
            }
            forceStop()
        }
    }

    companion object {
        private const val MIN_GESTURE_DURATION_MS = 100L
        private const val MAX_GESTURE_DURATION_MS = 900L
        private const val NO_PAUSE_GAP_MS = 80L
        private const val SPEED_CURVE_FACTOR = 0.7
        private var instance: AutoSlideService? = null

        /**
         * 获取服务单例实例
         *
         * @return 当前服务实例
         */
        @JvmStatic
        fun getInstance(): AutoSlideService? = instance

        /* 强制停止滑动回调 */
        var onForceStopListener: (() -> Unit)? = null
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
     * 更新滑动速度而不触发启动逻辑
     *
     * @param newSpeed 最新速度值
     */
    fun updateSpeed(newSpeed: Int) {
        speed = newSpeed.coerceIn(1, 100)
        if (!isRunning) {
            return
        }
        handler.removeCallbacks(slideRunnable)
        // 重新设置滑动时间
        handler.postDelayed(
            slideRunnable, calculateGestureDurationMillis() + calculatePauseDelayMillis()
        )
    }

    /**
     * 更新停顿配置参数
     *
     * @param mode 停顿模式
     * @param time 固定停顿时间
     * @param min 随机停顿下限
     * @param max 随机停顿上限
     */
    fun updatePauseConfig(mode: Int, time: Int, min: Int, max: Int) {
        pauseMode = mode
        pauseTime = time.coerceAtLeast(1)
        minPauseTime = min.coerceAtLeast(1)
        maxPauseTime = max.coerceAtLeast(1)
        if (!isRunning) {
            return
        }
        handler.removeCallbacks(slideRunnable)
        handler.postDelayed(
            slideRunnable, calculateGestureDurationMillis() + calculatePauseDelayMillis()
        )
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
        intent?.run {
            updateConfigFromIntent(this)
            startAutoSlide()
        }
        return START_STICKY
    }

    /* 服务连接完成后初始化屏幕参数并注册单例 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 请求按键过滤能力(用于音量键强制停止滑动)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        registerScreenOffReceiver()
    }

    /* 服务销毁时停止滑动并释放单例 */
    override fun onDestroy() {
        unregisterScreenOffReceiver()
        stopSlide()
        instance = null
        super.onDestroy()
    }

    /* 停止自动滑动循环 */
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
     * 监听音量键(在滑动运行中按音量键强制停止)
     *
     * @param event 物理按键事件
     * @return 是否已处理按键事件
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 只在按键按下且滑动正在运行时处理
        if (event.action != KeyEvent.ACTION_DOWN || !isRunning) {
            return super.onKeyEvent(event)
        }
        // 判断是否为音量键
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeKey) {
            return super.onKeyEvent(event)
        }
        // 强制停止滑动并恢复悬浮窗面板
        forceStop()
        return true
    }

    /* 强制停止滑动并恢复悬浮窗面板 */
    private fun forceStop() {
        stopSlide()
        onForceStopListener?.invoke()
    }

    /* 注册息屏广播 */
    private fun registerScreenOffReceiver() {
        if (isScreenOffReceiverRegistered) {
            return
        }
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isScreenOffReceiverRegistered = true
    }

    /* 注销息屏广播 */
    private fun unregisterScreenOffReceiver() {
        if (!isScreenOffReceiverRegistered) {
            return
        }
        runCatching { unregisterReceiver(screenOffReceiver) }
        isScreenOffReceiverRegistered = false
    }

    /* 滑动起止坐标数据类 */
    private data class SlideCoordinates(
        val startX: Float, val startY: Float, val endX: Float, val endY: Float
    )

    /**
     * 根据滑动方向计算滑动起止坐标
     *
     * @param direction 滑动方向
     * @return 起止坐标
     */
    private fun getSlideCoordinates(direction: String): SlideCoordinates {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val centerX = width / 2f
        val centerY = height / 2f
        return when (direction) {
            DIRECTION_UP -> SlideCoordinates(centerX, height * 0.2f, centerX, height * 0.8f)
            DIRECTION_DOWN -> SlideCoordinates(centerX, height * 0.8f, centerX, height * 0.2f)
            DIRECTION_LEFT -> SlideCoordinates(width * 0.1f, centerY, width * 0.9f, centerY)
            DIRECTION_RIGHT -> SlideCoordinates(width * 0.9f, centerY, width * 0.1f, centerY)
            else -> SlideCoordinates(width * 0.1f, centerY, width * 0.9f, centerY)
        }
    }

    /**
     * 从Intent中读取运行参数
     *
     * @param intent 启动参数
     */
    private fun updateConfigFromIntent(intent: Intent) {
        speed = intent.getIntExtra(KEY_SPEED, DEFAULT_SPEED)
        pauseMode = intent.getIntExtra(KEY_PAUSE_MODE, PAUSE_MODE_NONE)
        pauseTime = intent.getIntExtra(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME).coerceAtLeast(1)
        minPauseTime = intent.getIntExtra(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME).coerceAtLeast(1)
        maxPauseTime = intent.getIntExtra(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME).coerceAtLeast(1)
    }

    /* 启动自动滑动循环 */
    private fun startAutoSlide() {
        isRunning = true
        handler.removeCallbacks(slideRunnable)
        // 延迟300ms执行第一次滑动(等待悬浮窗完成最小化动画)(防止悬浮窗拦截手势)
        handler.postDelayed(slideRunnable, 300L)
    }

    /**
     * 按当前方向执行一次滑动
     *
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun performSlideByDirection(durationMillis: Long) {
        val (startX, startY, endX, endY) = getSlideCoordinates(currentDirection)
        dispatchLineGesture(startX, startY, endX, endY, durationMillis)
    }

    /**
     * 计算滑动手势持续时间
     *
     * @return 手势持续时间(毫秒)
     */
    private fun calculateGestureDurationMillis(): Long {
        val normalizedSpeed = speed.coerceIn(1, 100) / 100.0
        val curvedProgress = ln(1.0 + SPEED_CURVE_FACTOR * normalizedSpeed) /
                ln(1.0 + SPEED_CURVE_FACTOR)
        val durationRange = MAX_GESTURE_DURATION_MS - MIN_GESTURE_DURATION_MS
        return (MAX_GESTURE_DURATION_MS - durationRange * curvedProgress).roundToLong()
    }

    /**
     * 计算两次滑动之间的停顿时间
     *
     * @return 停顿时间(毫秒)
     */
    private fun calculatePauseDelayMillis(): Long = when (pauseMode) {
        PAUSE_MODE_FIXED -> pauseTime.coerceAtLeast(0) * 1000L
        PAUSE_MODE_RANDOM -> {
            val minMs = minPauseTime.coerceAtLeast(0) * 1000L
            val maxMs = maxPauseTime.coerceAtLeast(0) * 1000L
            val (lo, hi) = minOf(minMs, maxMs) to maxOf(minMs, maxMs)
            if (lo == hi) lo else (lo..hi).random()
        }

        else -> NO_PAUSE_GAP_MS
    }

    /**
     * 分发一条线性手势
     *
     * @param startX 起点X坐标
     * @param startY 起点Y坐标
     * @param endX 终点X坐标
     * @param endY 终点Y坐标
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun dispatchLineGesture(
        startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        // 构建并分发手势
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, durationMillis)
        ).build()
        // 分发手势
        dispatchGesture(gesture, null, null)
    }
}
