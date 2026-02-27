package com.ltx.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.ltx.MainActivity
import com.ltx.R
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
    private var initialTouchX = 0
    private var initialTouchY = 0

    companion object {
        private const val TOUCH_SLOP = 10f
        private const val PREFS_NAME = "slide_settings"
        private const val KEY_SPEED = "speed"
        private const val KEY_NEED_PAUSE = "needPause"
        private const val KEY_PAUSE_TIME = "pauseTime"
        private const val DEFAULT_SPEED = 50
        private const val DEFAULT_NEED_PAUSE = false
        private const val DEFAULT_PAUSE_TIME = 1
        private const val DIRECTION_UP = "up"
        private const val DIRECTION_DOWN = "down"
        private const val DIRECTION_LEFT = "left"
        private const val DIRECTION_RIGHT = "right"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 服务创建入口
     */
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
        windowManager.addView(rootView, layoutParams)
    }

    /**
     * 服务销毁时移除悬浮窗
     */
    override fun onDestroy() {
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

    /**
     * 注册拖拽事件处理
     */
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
        initialTouchX = event.rawX.toInt()
        initialTouchY = event.rawY.toInt()
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

    /**
     * 绑定所有控制按钮事件
     */
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

    /**
     * 最小化悬浮窗
     */
    private fun minimize() {
        controlPanel.visibility = View.GONE
        expandButton.visibility = View.VISIBLE
        windowManager.updateViewLayout(rootView, layoutParams)
    }

    /**
     * 展开悬浮窗并停止当前自动滑动
     */
    private fun expand() {
        controlPanel.visibility = View.VISIBLE
        expandButton.visibility = View.GONE
        windowManager.updateViewLayout(rootView, layoutParams)
        AutoSlideService.getInstance()?.stopSlide()
    }

    /**
     * 启动自动滑动服务
     *
     */
    private fun startSlide() {
        minimize()
        // 从本地配置文件读取当前设置
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val intent = Intent(this, AutoSlideService::class.java).apply {
            putExtra(KEY_SPEED, prefs.getInt(KEY_SPEED, DEFAULT_SPEED))
            putExtra(KEY_NEED_PAUSE, prefs.getBoolean(KEY_NEED_PAUSE, DEFAULT_NEED_PAUSE))
            putExtra(KEY_PAUSE_TIME, prefs.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME))
        }
        startService(intent)
    }

    /**
     * 返回主界面
     */
    private fun returnToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
}
