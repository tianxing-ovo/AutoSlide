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
import com.ltx.DEFAULT_MAX_PAUSE_TIME
import com.ltx.DEFAULT_MIN_PAUSE_TIME
import com.ltx.DEFAULT_PAUSE_TIME
import com.ltx.DEFAULT_SPEED
import com.ltx.DIRECTION_DOWN
import com.ltx.DIRECTION_LEFT
import com.ltx.DIRECTION_RIGHT
import com.ltx.DIRECTION_UP
import com.ltx.PAUSE_MODE_FIXED
import com.ltx.PAUSE_MODE_NONE
import com.ltx.PAUSE_MODE_RANDOM
import com.ltx.SlideConfig
import com.ltx.SlideEvent
import com.ltx.SlideEventHub
import com.ltx.clearCustomTrajectory
import com.ltx.getCustomTrajectory
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
     * 应用滑动配置
     *
     * @param config 滑动配置数据对象
     */
    private fun applyConfig(config: SlideConfig) {
        speed = config.speed.coerceIn(1, 100)
        pauseMode = config.pauseMode
        pauseTime = config.pauseTime.coerceAtLeast(1)
        minPauseTime = config.minPauseTime.coerceAtLeast(1)
        maxPauseTime = config.maxPauseTime.coerceAtLeast(1)
    }

    /**
     * 更新停顿配置参数
     *
     * @param config 滑动配置数据对象
     */
    fun updatePauseConfig(config: SlideConfig) {
        applyConfig(config)
        if (!isRunning || isGestureActive) {
            return
        }
        // 移除当前滑动任务并重新调度新的停顿时间
        handler.removeCallbacks(slideRunnable)
        handler.postDelayed(slideRunnable, calculatePauseDelayMillis())
    }

   /**
    * 根据配置启动自动滑动
    *
    * @param config 滑动配置数据对象
    */
    fun startSlideWithConfig(config: SlideConfig) {
        applyConfig(config)
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
            DIRECTION_RIGHT -> SlideCoordinates(width * 0.9f, centerY, width * 0.1f, centerY)
            else -> SlideCoordinates(width * 0.1f, centerY, width * 0.9f, centerY)
        }
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
        // 按分号拆分轨迹字符串并过滤空项
        val pointsStr = trajectoryStr.split(";").filter { it.isNotBlank() }
        // 有效点不足两个时清除轨迹并安排下次滑动
        if (pointsStr.size < 2) {
            clearCustomTrajectory(currentDirection)
            scheduleNextSlide()
            return
        }
        // 解析坐标数据
        val rawPoints = pointsStr.mapNotNull { pointStr ->
            val xyValues = pointStr.split(",")
            if (xyValues.size == 2) {
                val x = xyValues[0].toFloatOrNull() ?: return@mapNotNull null
                val y = xyValues[1].toFloatOrNull() ?: return@mapNotNull null
                PointF(x, y)
            } else null
        }
        // 解析后有效点不足两个时清除轨迹并安排下次滑动
        if (rawPoints.size < 2) {
            clearCustomTrajectory(currentDirection)
            scheduleNextSlide()
            return
        }
        // 过滤间距过近的冗余噪点以防止曲线畸变
        val density = resources.displayMetrics.density
        val minDistSq = (3f * density) * (3f * density)
        val filteredPoints = mutableListOf<PointF>()
        filteredPoints.add(rawPoints.first())
        for (i in 1 until rawPoints.size) {
            val last = filteredPoints.last()
            val curr = rawPoints[i]
            val dx = curr.x - last.x
            val dy = curr.y - last.y
            if (i == rawPoints.size - 1 || dx * dx + dy * dy >= minDistSq) {
                filteredPoints.add(curr)
            }
        }
        // 过滤后不足两个点时保底保留首尾坐标
        if (filteredPoints.size < 2) {
            filteredPoints.clear()
            filteredPoints.add(rawPoints.first())
            filteredPoints.add(rawPoints.last())
        }
        // 计算整条轨迹统一的拟人化随机平移量
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val maxGlobalOffset = 3f * density
        val globalXOffset = ((secureRandom.nextDouble() * 2 - 1) * maxGlobalOffset).toFloat()
        val globalYOffset = ((secureRandom.nextDouble() * 2 - 1) * maxGlobalOffset).toFloat()
        // 应用平移量并确保坐标处于屏幕范围内
        val points = filteredPoints.map { pt ->
            val finalX = (pt.x + globalXOffset).coerceIn(0f, width.toFloat())
            val finalY = (pt.y + globalYOffset).coerceIn(0f, height.toFloat())
            PointF(finalX, finalY)
        }
        // 基于中点插值构建二次贝塞尔平滑路径
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 2) {
            path.lineTo(points[1].x, points[1].y)
        } else {
            for (i in 1 until points.size - 1) {
                val midX = (points[i].x + points[i + 1].x) / 2f
                val midY = (points[i].y + points[i + 1].y) / 2f
                path.quadTo(points[i].x, points[i].y, midX, midY)
            }
            path.lineTo(points.last().x, points.last().y)
        }
        // 构建并分发无障碍手势
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
