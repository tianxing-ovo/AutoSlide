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
    private var screenWidth = 0
    private var screenHeight = 0
    private var speed = DEFAULT_SPEED
    private var pauseMode = PAUSE_MODE_NONE
    private var pauseTime = DEFAULT_PAUSE_SECONDS
    private var minPauseTime = 1
    private var maxPauseTime = 3
    private var currentDirection = DIRECTION_LEFT
    private var isRunning = false

    /* 自动滑动主循环 */
    private val slideRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                return
            }
            // 计算手势持续时间
            val gestureDurationMillis = calculateGestureDurationMillis()
            // 执行滑动
            performSlideByDirection(gestureDurationMillis)
            // 计算并设置下一次滑动时间
            handler.postDelayed(this, gestureDurationMillis + calculatePauseDelayMillis())
        }
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
        private const val DEFAULT_SPEED = 50
        private const val DEFAULT_PAUSE_SECONDS = 1
        const val PAUSE_MODE_NONE = 0
        const val PAUSE_MODE_FIXED = 1
        const val PAUSE_MODE_RANDOM = 2
        private const val DIRECTION_UP = "up"
        private const val DIRECTION_DOWN = "down"
        private const val DIRECTION_LEFT = "left"
        private const val DIRECTION_RIGHT = "right"
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
        speed = newSpeed.coerceIn(0, 100)
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
        screenWidth = resources.displayMetrics.widthPixels
        screenHeight = resources.displayMetrics.heightPixels
        // 请求按键过滤能力(用于音量键强制停止滑动)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        registerScreenOffReceiver()
    }

    /**
     * 服务销毁时停止滑动并释放单例
     */
    override fun onDestroy() {
        unregisterScreenOffReceiver()
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

    /**
     * 强制停止滑动并恢复悬浮窗面板
     */
    private fun forceStop() {
        stopSlide()
        sendBroadcast(
            Intent(FloatingWindowService.ACTION_EXPAND_FROM_FORCE_STOP).apply {
                `package` = packageName
            }
        )
    }

    /**
     * 注册息屏广播
     */
    private fun registerScreenOffReceiver() {
        if (isScreenOffReceiverRegistered) {
            return
        }
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        isScreenOffReceiverRegistered = true
    }

    /**
     * 注销息屏广播
     */
    private fun unregisterScreenOffReceiver() {
        if (!isScreenOffReceiverRegistered) {
            return
        }
        runCatching { unregisterReceiver(screenOffReceiver) }
        isScreenOffReceiverRegistered = false
    }

    /**
     * 执行向上滑动
     *
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun slideUp(durationMillis: Long) {
        // 滑动起点X坐标和终点X坐标(屏幕宽度的中心点X坐标)
        val centerX = screenWidth / 2f
        // 滑动起点Y坐标(屏幕顶部20%处)
        val startY = screenHeight * 0.2f
        // 滑动终点Y坐标(屏幕底部80%处)
        val endY = screenHeight * 0.8f
        // 执行滑动手势: 从屏幕顶部20%处滑动到屏幕底部80%处
        dispatchLineGesture(centerX, startY, centerX, endY, durationMillis)
    }

    /**
     * 执行向下滑动
     *
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun slideDown(durationMillis: Long) {
        // 滑动起点X坐标和终点X坐标(屏幕宽度的中心点X坐标)
        val centerX = screenWidth / 2f
        // 滑动起点Y坐标(屏幕底部80%处)
        val startY = screenHeight * 0.8f
        // 滑动终点Y坐标(屏幕顶部20%处)
        val endY = screenHeight * 0.2f
        // 执行滑动手势: 从屏幕底部80%处滑动到屏幕顶部20%处
        dispatchLineGesture(centerX, startY, centerX, endY, durationMillis)
    }

    /**
     * 执行向左滑动
     *
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun slideLeft(durationMillis: Long) {
        // 滑动起点Y坐标和终点Y坐标(屏幕高度的中心点Y坐标)
        val centerY = screenHeight / 2f
        // 滑动起点X坐标(屏幕左侧10%处)
        val startX = screenWidth * 0.1f
        // 滑动终点X坐标(屏幕右侧90%处)
        val endX = screenWidth * 0.9f
        // 执行滑动手势: 从屏幕左侧10%处滑动到屏幕右侧90%处
        dispatchLineGesture(startX, centerY, endX, centerY, durationMillis)
    }

    /**
     * 执行向右滑动
     *
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun slideRight(durationMillis: Long) {
        // 滑动起点Y坐标和终点Y坐标(屏幕高度的中心点Y坐标)
        val centerY = screenHeight / 2f
        // 滑动起点X坐标(屏幕右侧90%处)
        val startX = screenWidth * 0.9f
        // 滑动终点X坐标(屏幕左侧10%处)
        val endX = screenWidth * 0.1f
        // 执行滑动手势: 从屏幕右侧90%处滑动到屏幕左侧10%处
        dispatchLineGesture(startX, centerY, endX, centerY, durationMillis)
    }

    /**
     * 从Intent中读取运行参数
     *
     * @param intent 启动参数
     */
    private fun updateConfigFromIntent(intent: Intent) {
        intent.getStringExtra("direction")?.let { setDirection(it) }
        speed = intent.getIntExtra("speed", DEFAULT_SPEED)
        pauseMode = intent.getIntExtra("pauseMode", PAUSE_MODE_NONE)
        pauseTime = intent.getIntExtra("pauseTime", DEFAULT_PAUSE_SECONDS).coerceAtLeast(1)
        minPauseTime = intent.getIntExtra("minPauseTime", 1).coerceAtLeast(1)
        maxPauseTime = intent.getIntExtra("maxPauseTime", 3).coerceAtLeast(1)
    }

    /**
     * 启动自动滑动循环
     */
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
        when (currentDirection) {
            DIRECTION_UP -> slideUp(durationMillis)
            DIRECTION_DOWN -> slideDown(durationMillis)
            DIRECTION_RIGHT -> slideRight(durationMillis)
            else -> slideLeft(durationMillis)
        }
    }

    /**
     * 计算滑动手势持续时间
     *
     * @return 手势持续时间(毫秒)
     */
    private fun calculateGestureDurationMillis(): Long {
        val normalizedSpeed = speed.coerceIn(0, 100) / 100.0
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
    private fun calculatePauseDelayMillis(): Long {
        if (pauseMode == PAUSE_MODE_FIXED) {
            return pauseTime.coerceAtLeast(0) * 1000L
        } else if (pauseMode == PAUSE_MODE_RANDOM) {
            val minMs = minPauseTime.coerceAtLeast(0) * 1000L
            val maxMs = maxPauseTime.coerceAtLeast(0) * 1000L
            val actualMinMs = minOf(minMs, maxMs)
            val actualMaxMs = maxOf(minMs, maxMs)
            return if (actualMinMs == actualMaxMs) actualMinMs else (actualMinMs..actualMaxMs).random()
        }
        return NO_PAUSE_GAP_MS
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
