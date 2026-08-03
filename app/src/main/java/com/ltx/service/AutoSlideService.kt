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
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
import com.ltx.PREFS_NAME
import com.ltx.SlideEvent
import com.ltx.SlideEventHub
import com.ltx.getTrajectoryKey
import java.lang.ref.WeakReference
import java.security.SecureRandom
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.random.asKotlinRandom

/**
 * 自动滑动无障碍服务
 *
 * @author tianxing
 */
@SuppressLint("AccessibilityPolicy")
class AutoSlideService : AccessibilityService() {

    private val secureRandom = SecureRandom().asKotlinRandom()
    private val handler = Handler(Looper.getMainLooper())
    private var runGeneration = 0
    private var isScreenOffReceiverRegistered = false
    private var speed = DEFAULT_SPEED
    private var pauseMode = PAUSE_MODE_NONE
    private var pauseTime = DEFAULT_PAUSE_TIME
    private var minPauseTime = DEFAULT_MIN_PAUSE_TIME
    private var maxPauseTime = DEFAULT_MAX_PAUSE_TIME
    private var currentDirection = DIRECTION_LEFT
    private var isRunning = false
    private var isGestureActive = false

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
        private var instanceRef: WeakReference<AutoSlideService>? = null

        /**
         * 获取服务单例实例
         *
         * @return 当前服务实例
         */
        @JvmStatic
        fun getInstance(): AutoSlideService? = instanceRef?.get()
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
     * 读取自定义轨迹字符串
     *
     * @param direction 方向字符串
     * @return 自定义轨迹字符串
     */
    private fun getCustomTrajectory(direction: String): String? {
        val key = getTrajectoryKey(direction) ?: return null
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val value = prefs.getString(key, null)
        return if (value.isNullOrBlank()) null else value
    }

    /**
     * 清除自定义轨迹
     *
     * @param direction 方向字符串
     */
    private fun clearCustomTrajectory(direction: String) {
        val key = getTrajectoryKey(direction) ?: return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            remove(key)
        }
        SlideEventHub.sendEvent(SlideEvent.CustomTrajectoryCleared)
    }

    /**
     * 安排下一次滑动
     *
     * @param currentGen 当前运行代数
     */
    private fun scheduleNextSlide(currentGen: Int = runGeneration) {
        if (isRunning && currentGen == runGeneration) {
            handler.postDelayed(slideRunnable, calculatePauseDelayMillis())
        }
    }

    /**
     * 更新滑动速度而不触发启动逻辑
     *
     * @param newSpeed 最新速度值
     */
    fun updateSpeed(newSpeed: Int) {
        speed = newSpeed.coerceIn(1, 100)
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
        if (!isRunning || isGestureActive) {
            return
        }
        // 移除当前滑动任务并重新调度新的停顿时间
        handler.removeCallbacks(slideRunnable)
        handler.postDelayed(slideRunnable, calculatePauseDelayMillis())
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

   /**
    * 根据配置启动自动滑动
    *
    * @param speedVal 速度值
    * @param pauseModeVal 停顿模式
    * @param pauseTimeVal 固定停顿时间
    * @param minPauseVal 随机停顿下限
    * @param maxPauseVal 随机停顿上限
    */
    fun startSlideWithConfig(
        speedVal: Int, pauseModeVal: Int, pauseTimeVal: Int, minPauseVal: Int, maxPauseVal: Int
    ) {
        speed = speedVal.coerceIn(1, 100)
        pauseMode = pauseModeVal
        pauseTime = pauseTimeVal.coerceAtLeast(1)
        minPauseTime = minPauseVal.coerceAtLeast(1)
        maxPauseTime = maxPauseVal.coerceAtLeast(1)
        startAutoSlide()
    }

    /* 服务连接完成后初始化屏幕参数并注册单例 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
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
        instanceRef = null
        super.onDestroy()
    }

    /* 停止自动滑动循环 */
    fun stopSlide() {
        if (!isRunning) {
            return
        }
        isRunning = false
        isGestureActive = false
        runGeneration++
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
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
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
        SlideEventHub.sendEvent(SlideEvent.ForceStop)
    }

    /* 注册息屏广播 */
    private fun registerScreenOffReceiver() {
        if (isScreenOffReceiverRegistered) {
            return
        }
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
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
        isGestureActive = false
        runGeneration++
        val currentGen = runGeneration
        handler.removeCallbacks(slideRunnable)
        // 延迟300ms执行第一次滑动(等待悬浮窗完成最小化动画)(防止悬浮窗拦截手势)
        handler.postDelayed({
            if (currentGen == runGeneration && isRunning) {
                runSlide()
            }
        }, 300L)
    }

    /**
     * 按当前方向执行一次滑动
     *
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun performSlideByDirection(durationMillis: Long) {
        // 读取自定义轨迹字符串
        val trajectoryStr = getCustomTrajectory(currentDirection)
        if (trajectoryStr != null) {
            // 分发自定义手势
            dispatchCustomGesture(trajectoryStr, durationMillis)
        } else {
            // 分发默认手势
            val (startX, startY, endX, endY) = getSlideCoordinates(currentDirection)
            dispatchDefaultGesture(startX, startY, endX, endY, durationMillis)
        }
    }

    /**
     * 分发自定义手势
     *
     * @param trajectoryStr 轨迹字符串
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun dispatchCustomGesture(trajectoryStr: String, durationMillis: Long) {
        // 按分号拆分轨迹字符串并去掉空项
        val pointsStr = trajectoryStr.split(";").filter { it.isNotBlank() }
        // 不足两个点视为无效数据清除后跳过本次滑动
        if (pointsStr.size < 2) {
            clearCustomTrajectory(currentDirection)
            scheduleNextSlide()
            return
        }
        // 解析轨迹点
        val parsedPoints = pointsStr.mapNotNull { pointStr ->
            val xyValues = pointStr.split(",")
            if (xyValues.size == 2) {
                val x = xyValues[0].toFloatOrNull() ?: return@mapNotNull null
                val y = xyValues[1].toFloatOrNull() ?: return@mapNotNull null
                PointF(x, y)
            } else null
        }
        // 解析后有效点不足两个视为无效数据清除后跳过本次滑动
        if (parsedPoints.size < 2) {
            clearCustomTrajectory(currentDirection)
            scheduleNextSlide()
            return
        }
        // 根据轨迹点构建手势路径
        val path = Path()
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val maxOffset = 5f * density
        // 为每个点添加轻微随机偏移并限制在屏幕范围内
        parsedPoints.forEachIndexed { index, point ->
            val xOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
            val yOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
            val finalX = (point.x + xOffset).coerceIn(0f, width.toFloat())
            val finalY = (point.y + yOffset).coerceIn(0f, height.toFloat())
            if (index == 0) {
                path.moveTo(finalX, finalY)
            } else {
                path.lineTo(finalX, finalY)
            }
        }
        // 构建自定义轨迹手势
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, durationMillis)
        ).build()
        dispatchGestureAndContinue(gesture)
    }

    /**
     * 计算滑动手势持续时间
     *
     * @return 手势持续时间(毫秒)
     */
    private fun calculateGestureDurationMillis(): Long {
        val normalizedSpeed = speed.coerceIn(1, 100) / 100.0
        val curvedProgress = ln(1.0 + SPEED_CURVE_FACTOR * normalizedSpeed) / ln(1.0 + SPEED_CURVE_FACTOR)
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
            if (lo == hi) lo else (lo..hi).random(secureRandom)
        }

        else -> NO_PAUSE_GAP_MS
    }

    /**
     * 分发默认手势
     *
     * @param startX 起点X坐标
     * @param startY 起点Y坐标
     * @param endX 终点X坐标
     * @param endY 终点Y坐标
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun dispatchDefaultGesture(
        startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long
    ) {
        // 获取当前设备的屏幕密度缩放比例
        val density = resources.displayMetrics.density
        val maxOffset = 10f * density
        // 计算起止坐标偏移量
        val startXOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        val startYOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        val endXOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        val endYOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        // 计算实际起止坐标
        val actualStartX = startX + startXOffset
        val actualStartY = startY + startYOffset
        val actualEndX = endX + endXOffset
        val actualEndY = endY + endYOffset
        // 计算中点坐标
        val midX = (actualStartX + actualEndX) / 2
        val midY = (actualStartY + actualEndY) / 2
        // 计算控制点坐标偏移量
        val controlOffset = 15f * density
        // 计算控制点坐标
        val controlX = midX + ((secureRandom.nextDouble() * 2 - 1) * controlOffset).toFloat()
        val controlY = midY + ((secureRandom.nextDouble() * 2 - 1) * controlOffset).toFloat()
        // 构造贝塞尔曲线路径模拟真人滑动的自然微弯轨迹
        val path = Path().apply {
            moveTo(actualStartX, actualStartY)
            quadTo(controlX, controlY, actualEndX, actualEndY)
        }
        // 构建并分发手势
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, durationMillis)
        ).build()
        dispatchGestureAndContinue(gesture)
    }

    /**
     * 分发手势并在结束后安排下一次滑动
     *
     * @param gesture 待分发的手势
     */
    private fun dispatchGestureAndContinue(gesture: GestureDescription) {
        isGestureActive = true
        val currentGen = runGeneration
        val success = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                isGestureActive = false
                scheduleNextSlide(currentGen)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onCompleted(gestureDescription)
            }
        }, handler)
        // 分发失败时手动复位并继续下一轮滑动
        if (!success) {
            isGestureActive = false
            scheduleNextSlide(currentGen)
        }
    }
}
