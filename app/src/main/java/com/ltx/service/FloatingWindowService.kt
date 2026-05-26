package com.ltx.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
import com.ltx.MainActivity
import com.ltx.PREFS_NAME
import com.ltx.R
import com.ltx.getPauseMode
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


    companion object {
        private const val TOUCH_SLOP = 10f
    }

    /* 绑定服务 */
    override fun onBind(intent: Intent?): IBinder? = null
    
    /* 创建服务根视图并添加到窗口管理器 */
    override fun onCreate() {
        super.onCreate()
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
            stopSelf()
            return
        }
        // 注册自动滑动服务停止回调
        AutoSlideService.onForceStopListener = {
            if (::rootView.isInitialized) {
                rootView.post { expand(stopSlide = false) }
            }
        }
    }

    /* 服务销毁时移除悬浮窗 */
    override fun onDestroy() {
        // 移除自动滑动服务停止回调
        AutoSlideService.onForceStopListener = null
        super.onDestroy()
        runCatching { windowManager.removeView(rootView) }
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

    /* 注册拖拽事件处理 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragging() {
        val listener = View.OnTouchListener { view, event ->
            event ?: return@OnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    onTouchDown(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    onTouchMove(event)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    onTouchUp(view, event)
                    true
                }
                else -> false
            }
        }
        // 为根视图和展开按钮注册触摸事件
        rootView.setOnTouchListener(listener)
        expandButton.setOnTouchListener(listener)
    }

    /**
     * 触摸按下时记录初始坐标
     *
     * @param event 触摸事件
     */
    private fun onTouchDown(event: MotionEvent) {
        initialX = layoutParams.x.toFloat()
        initialY = layoutParams.y.toFloat()
        initialTouchX = event.rawX
        initialTouchY = event.rawY
    }

    /**
     * 触摸移动时更新悬浮窗位置
     *
     * @param event 触摸事件
     */
    private fun onTouchMove(event: MotionEvent) {
        val deltaX = event.rawX - initialTouchX
        val deltaY = event.rawY - initialTouchY
        if (abs(deltaX) < TOUCH_SLOP && abs(deltaY) < TOUCH_SLOP) {
            return
        }
        // 更新悬浮窗位置
        layoutParams.x = (initialX + deltaX).toInt()
        layoutParams.y = (initialY + deltaY).toInt()
        windowManager.updateViewLayout(rootView, layoutParams)
    }

    /**
     * 触摸抬起时按位移阈值判断是否触发点击事件
     *
     * @param view 当前触发视图
     * @param event 触摸事件
     */
    private fun onTouchUp(view: View, event: MotionEvent) {
        if (
            abs(event.rawX - initialTouchX) < TOUCH_SLOP &&
            abs(event.rawY - initialTouchY) < TOUCH_SLOP
        ) {
            view.performClick()
        }
    }

    /* 绑定所有控制按钮事件 */
    private fun setupControlButtons() {
        expandButton.setOnClickListener { expand() }
        // 方向按钮点击事件绑定
        bindDirectionButton(R.id.floating_up_button, DIRECTION_UP)
        bindDirectionButton(R.id.floating_down_button, DIRECTION_DOWN)
        bindDirectionButton(R.id.floating_left_button, DIRECTION_LEFT)
        bindDirectionButton(R.id.floating_right_button, DIRECTION_RIGHT)
        // 设置按钮点击事件
        rootView.findViewById<View>(R.id.floating_setting_button).setOnClickListener {
            returnToMainActivity()
            AutoSlideService.getInstance()?.stopSlide()
            stopSelf()
        }
        // 关闭按钮点击事件
        rootView.findViewById<View>(R.id.floating_close_button).setOnClickListener {
            AutoSlideService.getInstance()?.stopSlide()
            stopSelf()
        }
    }

    /**
     * 为方向按钮绑定启动逻辑
     *
     * @param viewId 按钮视图ID
     * @param direction 方向字符串(up/down/left/right)
     */
    private fun bindDirectionButton(viewId: Int, direction: String) {
        rootView.findViewById<View>(viewId).setOnClickListener {
            val service = AutoSlideService.getInstance() ?: return@setOnClickListener
            service.setDirection(direction)
            startSlide()
        }
    }

    /* 最小化悬浮窗 */
    private fun minimize() {
        controlPanel.visibility = View.GONE
        expandButton.visibility = View.VISIBLE
        windowManager.updateViewLayout(rootView, layoutParams)
    }

    /**
     * 展开悬浮窗并停止当前自动滑动
     *
     * @param stopSlide 是否停止当前自动滑动
     */
    private fun expand(stopSlide: Boolean = true) {
        controlPanel.visibility = View.VISIBLE
        expandButton.visibility = View.GONE
        windowManager.updateViewLayout(rootView, layoutParams)
        if (stopSlide) {
            AutoSlideService.getInstance()?.stopSlide()
        }
    }

    /* 启动自动滑动服务 */
    private fun startSlide() {
        minimize()
        // 从本地配置文件读取当前设置
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val intent = Intent(this, AutoSlideService::class.java).apply {
            putExtra(KEY_SPEED, prefs.getInt(KEY_SPEED, DEFAULT_SPEED))
            putExtra(KEY_PAUSE_MODE, prefs.getPauseMode())
            putExtra(KEY_PAUSE_TIME, prefs.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME))
            putExtra(KEY_MIN_PAUSE_TIME, prefs.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME))
            putExtra(KEY_MAX_PAUSE_TIME, prefs.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME))
        }
        startService(intent)
    }

    /* 返回主界面 */
    private fun returnToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
}
