package com.ltx.service;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.ltx.MainActivity;
import com.ltx.R;

/**
 * 悬浮窗服务类
 *
 * @author tianxing
 */
public class FloatingWindowService extends Service {
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private View view;
    private View controlPanel;
    private View expandButton;
    private float initialX, initialY;
    private int initialTouchX, initialTouchY;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("InflateParams")
    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // 使用ContextThemeWrapper包装上下文以应用AppCompat主题
        Context context = new ContextThemeWrapper(this, R.style.Theme_AutoSlide);
        LayoutInflater inflater = LayoutInflater.from(context);
        view = inflater.inflate(R.layout.floating_window, null);
        controlPanel = view.findViewById(R.id.control_panel);
        expandButton = view.findViewById(R.id.floating_expand_button);
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        setupDragging();
        setupControlButton();
        windowManager.addView(view, params);
    }

    /**
     * 设置拖动事件
     */
    private void setupDragging() {
        View.OnTouchListener listener = (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = (int) event.getRawX();
                    initialTouchY = (int) event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // 计算移动距离
                    float deltaX = event.getRawX() - initialTouchX;
                    float deltaY = event.getRawY() - initialTouchY;
                    // 如果移动距离很小则可能是点击
                    if (Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) {
                        return true;
                    }
                    params.x = (int) (initialX + deltaX);
                    params.y = (int) (initialY + deltaY);
                    windowManager.updateViewLayout(view, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    // 如果移动距离很小则认为是点击事件
                    if (Math.abs(event.getRawX() - initialTouchX) < 10 && Math.abs(event.getRawY() - initialTouchY) < 10) {
                        v.performClick();
                    }
                    return true;
            }
            return false;
        };
        view.setOnTouchListener(listener);
        if (expandButton != null) {
            expandButton.setOnTouchListener(listener);
        }
    }

    /**
     * 设置控制按钮的点击事件
     */
    private void setupControlButton() {
        expandButton.setOnClickListener(v -> expand());
        view.findViewById(R.id.floating_up_button).setOnClickListener(v -> {
            AutoSlideService service = AutoSlideService.getInstance();
            if (service != null) {
                service.setDirection("up");
                startSlide();
            }
        });
        view.findViewById(R.id.floating_down_button).setOnClickListener(v -> {
            AutoSlideService service = AutoSlideService.getInstance();
            if (service != null) {
                service.setDirection("down");
                startSlide();
            }
        });
        view.findViewById(R.id.floating_left_button).setOnClickListener(v -> {
            AutoSlideService service = AutoSlideService.getInstance();
            if (service != null) {
                service.setDirection("left");
                startSlide();
            }
        });
        view.findViewById(R.id.floating_right_button).setOnClickListener(v -> {
            AutoSlideService service = AutoSlideService.getInstance();
            if (service != null) {
                service.setDirection("right");
                startSlide();
            }
        });
        view.findViewById(R.id.floating_setting_button).setOnClickListener(v -> {
            // 返回主界面
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            // 停止滑动服务并关闭悬浮窗
            AutoSlideService service = AutoSlideService.getInstance();
            if (service != null) {
                service.stopSlide();
            }
            stopSelf();
        });
        view.findViewById(R.id.floating_close_button).setOnClickListener(v -> {
            AutoSlideService service = AutoSlideService.getInstance();
            if (service != null) {
                service.stopSlide();
            }
            stopSelf();
        });
    }

    /**
     * 最小化悬浮窗
     */
    private void minimize() {
        if (controlPanel != null && expandButton != null) {
            controlPanel.setVisibility(View.GONE);
            expandButton.setVisibility(View.VISIBLE);
            // 更新悬浮窗布局以适应新的大小
            windowManager.updateViewLayout(view, params);
        }
    }

    /**
     * 展开悬浮窗
     */
    private void expand() {
        if (controlPanel != null && expandButton != null) {
            controlPanel.setVisibility(View.VISIBLE);
            expandButton.setVisibility(View.GONE);
            windowManager.updateViewLayout(view, params);
            // 展开时停止滑动
            AutoSlideService service = AutoSlideService.getInstance();
            if (service != null) {
                service.stopSlide();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (view != null) {
            windowManager.removeView(view);
        }
    }

    /**
     * 开始滑动
     */
    private void startSlide() {
        // 最小化悬浮窗
        minimize();
        // 启动滑动服务
        Intent intent = new Intent(this, AutoSlideService.class);
        // 从SharedPreferences获取保存的设置
        SharedPreferences prefs = getSharedPreferences("slide_settings", MODE_PRIVATE);
        intent.putExtra("speed", prefs.getInt("speed", 50));
        intent.putExtra("needPause", prefs.getBoolean("needPause", false));
        intent.putExtra("pauseTime", prefs.getInt("pauseTime", 1));
        startService(intent);
    }
}