package com.ltx.service

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ltx.DIRECTION_DOWN
import com.ltx.DIRECTION_LEFT
import com.ltx.DIRECTION_RIGHT
import com.ltx.DIRECTION_UP
import com.ltx.MainActivity
import com.ltx.PREFS_NAME
import com.ltx.R
import com.ltx.SlideEvent
import com.ltx.SlideEventHub
import com.ltx.clearCustomTrajectory
import com.ltx.getSlideConfig
import com.ltx.getTrajectoryKey
import com.ltx.hasCustomTrajectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮窗服务
 *
 * @author tianxing
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var rootView: View
    private lateinit var controlPanel: View
    private lateinit var expandButton: View
    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var recordOverlayView: View? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /* 绑定服务 */
    override fun onBind(intent: Intent?): IBinder? = null
    
    /* 创建服务根视图并添加到窗口管理器 */
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        AutoSlideTileService.requestUpdate(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 创建悬浮窗根视图
        rootView = createRootView()
        controlPanel = rootView.findViewById(R.id.control_panel)
        expandButton = rootView.findViewById(R.id.floating_expand_button)
        layoutParams = createLayoutParams()
        // 注册拖拽事件处理
        setupDragging()
        setupControlButtons()
        // 添加悬浮窗到窗口管理器
        try {
            windowManager.addView(rootView, layoutParams)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(
                "FloatingWindowService",
                "Failed to add floating window: overlay permission missing",
                e
            )
            isServiceRunning = false
            AutoSlideTileService.requestUpdate(this)
            stopSelf()
            return
        }
        // 监听自动滑动服务事件
        serviceScope.launch {
            SlideEventHub.eventFlow.collect { event ->
                if (!::rootView.isInitialized) return@collect
                when (event) {
                    is SlideEvent.ForceStop -> expand(stopSlide = false)
                    is SlideEvent.CustomTrajectoryCleared -> updateDirectionButtonIndicators()
                }
            }
        }
    }

    /* 服务销毁时移除悬浮窗 */
    override fun onDestroy() {
        isServiceRunning = false
        AutoSlideTileService.requestUpdate(this)
        serviceScope.cancel()
        removeRecordView()
        super.onDestroy()
        runCatching { windowManager.removeView(rootView) }
    }

    /**
     * 配置改变时更新悬浮窗位置
     * 
     * @param newConfig 配置
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::rootView.isInitialized && ::layoutParams.isInitialized) {
            rootView.post {
                if (::rootView.isInitialized && ::layoutParams.isInitialized) {
                    updateClampedPosition()
                }
            }
        }
    }

    /**
     * 创建悬浮窗根视图
     *
     * @return 悬浮窗根视图实例
     */
    @SuppressLint("InflateParams")
    private fun createRootView(): View {
        val themedContext: Context = ContextThemeWrapper(this, R.style.Theme_AutoSlide)
        return LayoutInflater
            .from(themedContext)
            .inflate(R.layout.floating_window, null)
    }

    /**
     * 构造悬浮窗布局参数
     *
     * @return 视图窗口参数
     */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    /* 设置拖拽事件处理 */
    private fun setupDragging() {
        val draggableRoot = rootView as? DraggableLinearLayout ?: return
        draggableRoot.setOnDragListener(object : DraggableLinearLayout.OnDragListener {
            override fun onDragDown(rawX: Float, rawY: Float) {
                initialX = layoutParams.x.toFloat()
                initialY = layoutParams.y.toFloat()
                initialTouchX = rawX
                initialTouchY = rawY
            }

            override fun onDragMove(rawX: Float, rawY: Float) {
                val deltaX = rawX - initialTouchX
                val deltaY = rawY - initialTouchY
                val targetX = (initialX + deltaX).toInt()
                val targetY = (initialY + deltaY).toInt()
                updateClampedPosition(targetX, targetY)
            }
        })
    }

    /**
     * 更新悬浮窗位置并限制在屏幕可视范围内
     *
     * @param targetX 目标X坐标
     * @param targetY 目标Y坐标
     */
    private fun updateClampedPosition(
        targetX: Int = layoutParams.x, targetY: Int = layoutParams.y
    ) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val viewWidth = rootView.width
        val viewHeight = rootView.height
        layoutParams.x = targetX.coerceIn(0, (screenWidth - viewWidth).coerceAtLeast(0))
        layoutParams.y = targetY.coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
        windowManager.updateViewLayout(rootView, layoutParams)
    }

    /* 绑定所有控制按钮事件 */
    private fun setupControlButtons() {
        expandButton.setOnClickListener { expand() }
        // 遍历方向按钮并绑定事件
        DIRECTION_BUTTON_MAP.forEach { (viewId, direction) ->
            bindDirectionButton(viewId, direction)
        }
        // 设置按钮点击事件
        rootView.findViewById<View>(R.id.floating_setting_button).setOnClickListener {
            returnToMainActivity()
            AutoSlideService.getInstance()?.stopSlide()
            stopSelf()
        }
        // 关闭按钮⌈点击⌋事件绑定
        rootView.findViewById<View>(R.id.floating_close_button).setOnClickListener {
            AutoSlideService.getInstance()?.stopSlide()
            stopSelf()
        }
        // 根据是否已有自定义轨迹更新方向按钮高亮
        updateDirectionButtonIndicators()
    }

    /**
     * 显示轨迹管理对话框
     *
     * @param direction 方向字符串
     */
    private fun showTrajectoryManageDialog(direction: String) {
        val items = arrayOf(getString(R.string.record), getString(R.string.reset))
        val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_AutoSlide))
            .setTitle(getTrajectoryManageTitle(direction)).setItems(items) { _, which ->
                when (items[which]) {
                    getString(R.string.record) -> startRecordingTrajectory(direction)
                    getString(R.string.reset) -> clearCustomTrajectory(direction)
                }
            }.setNegativeButton(R.string.cancel, null)
        showSystemAlertDialog(builder)
    }

    /**
     * 开始录制轨迹
     *
     * @param direction 方向字符串
     */
    private fun startRecordingTrajectory(direction: String) {
        AutoSlideService.getInstance()?.stopSlide()
        minimize()
        // 在全屏覆盖层显示录制方向提示
        val instructionText = getRecordDirectionInstruction(direction)
        // 创建录制视图
        val recordView =
            TrajectoryRecordView(this, instructionText = instructionText, onTrajectoryRecorded = { points ->
                removeRecordView()
                expand()
                val detected = detectTrajectoryDirection(points)
                if (detected != direction && detected.isNotEmpty()) {
                    showDirectionMismatchDialog(points, direction, detected)
                } else {
                    saveTrajectory(points, direction)
                    updateDirectionButtonIndicators()
                    Toast.makeText(this, R.string.trajectory_saved, Toast.LENGTH_SHORT).show()
                }
            }, onCancel = {
                removeRecordView()
                expand()
            })
        // 创建录制视图布局参数
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        // 添加录制视图到窗口管理器
        try {
            windowManager.addView(recordView, params)
            recordOverlayView = recordView
        } catch (e: Exception) {
            Log.e("FloatingWindowService", "Failed to add record view", e)
        }
    }

    /* 移除录制视图 */
    private fun removeRecordView() {
        val recordView = recordOverlayView ?: return
        runCatching { windowManager.removeView(recordView) }
        recordOverlayView = null
    }

    /**
     * 保存轨迹
     *
     * @param points 轨迹点列表
     * @param direction 方向字符串
     */
    private fun saveTrajectory(points: List<PointF>, direction: String) {
        if (points.isEmpty()) return
        val sb = StringBuilder()
        points.forEach { point ->
            sb.append("${point.x},${point.y};")
        }
        val key = getTrajectoryKey(direction) ?: return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(key, sb.toString())
        }
    }

    /**
     * 获取方向显示名称
     *
     * @param direction 方向字符串
     * @return 显示名称
     */
    private fun getDirectionDisplayName(direction: String): String = when (direction) {
        DIRECTION_UP -> getString(R.string.desc_slide_up)
        DIRECTION_DOWN -> getString(R.string.desc_slide_down)
        DIRECTION_LEFT -> getString(R.string.desc_slide_left)
        DIRECTION_RIGHT -> getString(R.string.desc_slide_right)
        else -> direction
    }

    /**
     * 获取轨迹管理标题
     *
     * @param direction 方向字符串
     * @return 标题
     */
    private fun getTrajectoryManageTitle(direction: String): String = when (direction) {
        DIRECTION_UP -> getString(R.string.trajectory_title_up)
        DIRECTION_DOWN -> getString(R.string.trajectory_title_down)
        DIRECTION_LEFT -> getString(R.string.trajectory_title_left)
        DIRECTION_RIGHT -> getString(R.string.trajectory_title_right)
        else -> direction
    }

    /**
     * 获取录制时的方向提示文本
     *
     * @param direction 方向字符串
     * @return 方向提示文本
     */
    private fun getRecordDirectionInstruction(direction: String): String = when (direction) {
        DIRECTION_UP -> getString(R.string.record_direction_up_explicit)
        DIRECTION_DOWN -> getString(R.string.record_direction_down_explicit)
        DIRECTION_LEFT -> getString(R.string.record_direction_left_explicit)
        DIRECTION_RIGHT -> getString(R.string.record_direction_right_explicit)
        else -> direction
    }

    /**
     * 根据轨迹首尾点位移检测主导方向
     *
     * @param points 轨迹点列表
     * @return 主导方向
     */
    private fun detectTrajectoryDirection(points: List<PointF>): String {
        if (points.size < 2) return ""
        val start = points.first()
        val end = points.last()
        val dx = end.x - start.x
        val dy = end.y - start.y
        return when {
            abs(dx) > abs(dy) -> if (dx > 0) DIRECTION_LEFT else DIRECTION_RIGHT
            else -> if (dy > 0) DIRECTION_UP else DIRECTION_DOWN
        }
    }

    /**
     * 当录制轨迹方向与所选方向不一致时弹出确认对话框
     *
     * @param points 已录制的轨迹点
     * @param selectedDirection 用户选择的方向
     * @param detectedDirection 检测到的实际方向
     */
    private fun showDirectionMismatchDialog(
        points: List<PointF>, selectedDirection: String, detectedDirection: String
    ) {
        val selectedName = getDirectionDisplayName(selectedDirection)
        val detectedName = getDirectionDisplayName(detectedDirection)
        val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_AutoSlide))
            .setTitle(R.string.trajectory_mismatch_title)
            .setMessage(getString(R.string.trajectory_mismatch_message, detectedName, selectedName))
            .setPositiveButton(R.string.save_anyway) { _, _ ->
                saveTrajectory(points, selectedDirection)
                updateDirectionButtonIndicators()
                Toast.makeText(this, R.string.trajectory_saved, Toast.LENGTH_SHORT).show()
            }.setNeutralButton(R.string.record_again) { _, _ ->
                startRecordingTrajectory(selectedDirection)
            }.setNegativeButton(R.string.cancel, null)
        showSystemAlertDialog(builder)
    }

    /* 更新方向按钮视觉标记 */
    private fun updateDirectionButtonIndicators() {
        val defaultColor = ContextCompat.getColor(this, R.color.floating_btn_bg)
        val activeColor = ContextCompat.getColor(this, R.color.floating_btn_active)
        val defaultIconColor = ContextCompat.getColor(this, R.color.floating_btn_icon)
        val activeIconColor = ContextCompat.getColor(this, R.color.floating_btn_active_icon)
        // 遍历方向按钮并更新视觉标记
        DIRECTION_BUTTON_MAP.forEach { (viewId, direction) ->
            val button = rootView.findViewById<FloatingActionButton>(viewId)
            val hasTrajectory = hasCustomTrajectory(direction)
            button?.let {
                it.backgroundTintList = ColorStateList.valueOf(
                    if (hasTrajectory) activeColor else defaultColor
                )
                it.imageTintList = ColorStateList.valueOf(
                    if (hasTrajectory) activeIconColor else defaultIconColor
                )
            }
        }
    }

    /**
     * 为方向按钮绑定启动与录制逻辑
     *
     * @param viewId 按钮视图ID
     * @param direction 方向字符串(up/down/left/right)
     */
    private fun bindDirectionButton(viewId: Int, direction: String) {
        val button = rootView.findViewById<View>(viewId)
        // 方向按钮⌈点击⌋事件绑定
        button.setOnClickListener {
            val service = AutoSlideService.getInstance() ?: return@setOnClickListener
            service.setDirection(direction)
            startSlide()
        }
        // 方向按钮⌈长按⌋事件绑定
        button.setOnLongClickListener {
            if (hasCustomTrajectory(direction)) {
                showTrajectoryManageDialog(direction)
            } else {
                startRecordingTrajectory(direction)
            }
            true
        }
    }

    /**
     * 设置悬浮窗展开与最小化状态
     *
     * @param isExpanded 是否展开
     * @param stopSlide 是否停止当前自动滑动
     */
    private fun setExpanded(isExpanded: Boolean, stopSlide: Boolean = true) {
        if (isExpanded) {
            controlPanel.visibility = View.VISIBLE
            expandButton.visibility = View.GONE
        } else {
            controlPanel.visibility = View.GONE
            expandButton.visibility = View.VISIBLE
        }
        windowManager.updateViewLayout(rootView, layoutParams)
        if (isExpanded && stopSlide) {
            AutoSlideService.getInstance()?.stopSlide()
        }
    }

    /* 最小化悬浮窗 */
    private fun minimize() {
        setExpanded(false)
    }

    /**
     * 展开悬浮窗并停止当前自动滑动
     *
     * @param stopSlide 是否停止当前自动滑动
     */
    private fun expand(stopSlide: Boolean = true) {
        setExpanded(true, stopSlide)
    }

    /* 启动自动滑动服务 */
    private fun startSlide() {
        minimize()
        AutoSlideService.getInstance()?.startSlideWithConfig(getSlideConfig())
    }

    /* 返回主界面 */
    private fun returnToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    /**
     * 显示系统级对话框
     *
     * @param builder 对话框构建器
     */
    private fun showSystemAlertDialog(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    companion object {
        private var isServiceRunning = false

        // 方向按钮ID与方向的映射
        private val DIRECTION_BUTTON_MAP = mapOf(
            R.id.floating_up_button to DIRECTION_UP,
            R.id.floating_down_button to DIRECTION_DOWN,
            R.id.floating_left_button to DIRECTION_LEFT,
            R.id.floating_right_button to DIRECTION_RIGHT
        )

        /**
         * 获取悬浮窗服务运行状态
         * 
         * @return 悬浮窗服务是否正在运行
         */
        @JvmStatic
        fun isRunning(): Boolean = isServiceRunning
    }
}
