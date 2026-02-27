package com.ltx.service;


import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/**
 * 自动滑动服务类
 *
 * @author tianxing
 */
@SuppressLint("AccessibilityPolicy")
public class AutoSlideService extends AccessibilityService {
	private static AutoSlideService instance;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private int screenWidth;
	private int centerY;
	private boolean isRunning = false;
	private int speed;
	private boolean needPause;
	private int pauseTime;
	private String currentDirection = "left";

	public static AutoSlideService getInstance() {
		return instance;
	}

	/**
	 * 设置滑动方向
	 *
	 * @param direction 滑动方向
	 */
	public void setDirection(String direction) {
		this.currentDirection = direction;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			// 获取传入的参数
			speed = intent.getIntExtra("speed", 50);
			needPause = intent.getBooleanExtra("needPause", false);
			pauseTime = intent.getIntExtra("pauseTime", 1);
			// 开始自动滑动
			startAutoSlide();
		}
		return START_STICKY;
	}

	@Override
	protected void onServiceConnected() {
		super.onServiceConnected();
		instance = this;
		// 获取屏幕宽度
		screenWidth = getResources().getDisplayMetrics().widthPixels * 2 / 3;
		// 获取屏幕中心点Y坐标
		centerY = getResources().getDisplayMetrics().heightPixels / 2;
	}

	private void startAutoSlide() {
		isRunning = true;
		handler.post(new Runnable() {
			@Override
			public void run() {
				if (!isRunning) {
					return;
				}
				// 执行滑动
				switch (currentDirection) {
					case "up":
						slideUp();
						break;
					case "down":
						slideDown();
						break;
					case "left":
						slideLeft();
						break;
					case "right":
						slideRight();
						break;
				}
				// 计算下次滑动延迟时间
				long delay = needPause ? pauseTime * 1000L : (100 - speed) * 20L;
				// 安排下次滑动
				handler.postDelayed(this, delay);
			}
		});
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		stopSlide();
	}

	public void stopSlide() {
		isRunning = false;
		handler.removeCallbacksAndMessages(null);
	}

	/**
	 * 服务创建时调用
	 *
	 * @param event 无障碍事件
	 */
	@Override
	public void onAccessibilityEvent(AccessibilityEvent event) {
	}

	@Override
	public void onInterrupt() {
	}

	/**
	 * 执行向上滑动
	 */
	public void slideUp() {
		Path path = new Path();
		path.moveTo(screenWidth, 300);
		path.lineTo(screenWidth, 1500);
		GestureDescription.Builder builder = new GestureDescription.Builder();
		builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 500));
		dispatchGesture(builder.build(), null, null);
	}

	/**
	 * 执行向下滑动
	 */
	public void slideDown() {
		Path path = new Path();
		path.moveTo(screenWidth, 1500);
		path.lineTo(screenWidth, 300);
		GestureDescription.Builder builder = new GestureDescription.Builder();
		builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 500));
		dispatchGesture(builder.build(), null, null);
	}

	/**
	 * 执行向左滑动
	 */
	public void slideLeft() {
		Path path = new Path();
		path.moveTo(100, centerY);
		path.lineTo(900, centerY);
		GestureDescription.Builder builder = new GestureDescription.Builder();
		builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 500));
		dispatchGesture(builder.build(), null, null);
	}

	/**
	 * 执行向右滑动
	 */
	public void slideRight() {
		Path path = new Path();
		path.moveTo(900, centerY);
		path.lineTo(100, centerY);
		GestureDescription.Builder builder = new GestureDescription.Builder();
		builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 500));
		dispatchGesture(builder.build(), null, null);
	}
}