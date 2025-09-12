package com.ltx;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.SeekBar;

import androidx.appcompat.app.AppCompatActivity;

import com.ltx.databinding.ActivityMainBinding;
import com.ltx.service.AutoSlideService;
import com.ltx.service.FloatingWindowService;


/**
 * 主活动类
 *
 * @author tianxing
 */
public class MainActivity extends AppCompatActivity {

	private ActivityMainBinding binding;
	private SharedPreferences prefs;

	/**
	 * 活动创建时调用
	 *
	 * @param bundle 保存的状态
	 */
	@Override
	protected void onCreate(Bundle bundle) {
		super.onCreate(bundle);
		// 初始化数据绑定
		binding = ActivityMainBinding.inflate(getLayoutInflater());
		// 设置布局
		setContentView(binding.getRoot());
		// 初始化SharedPreferences
		prefs = getSharedPreferences("slide_settings", MODE_PRIVATE);
		// 恢复设置
		restoreSettings();
		// 设置停顿时间
		setPauseTime();
		// 设置滑动速度
		setSlideSpeed();
		// 设置无障碍权限
		setAccessibilityPermission();
		// 开始
		start();
	}

	/**
	 * 活动恢复时调用
	 */
	@Override
	protected void onResume() {
		super.onResume();
		// 检查无障碍服务状态并更新无障碍服务权限开关
		binding.accessibilityPermissionSwitch.setChecked(isAccessibilityEnabled());
	}

	/**
	 * 恢复用户设置到UI
	 */
	private void restoreSettings() {
		int speed = prefs.getInt("speed", 50);
		boolean needPause = prefs.getBoolean("needPause", false);
		int pauseTime = prefs.getInt("pauseTime", 1);
		binding.speedSeekBar.setProgress(speed);
		binding.pauseSwitch.setChecked(needPause);
		binding.pauseTimeSeekBar.setProgress(pauseTime);
		binding.pauseTimeValueText.setText(String.valueOf(pauseTime));
		binding.pauseTimeLayout.setVisibility(needPause ? View.VISIBLE : View.GONE);
	}

	/**
	 * 设置停顿时间
	 */
	private void setPauseTime() {
		binding.pauseSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			// 根据开关状态控制停顿时间布局的显示和隐藏
			binding.pauseTimeLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
			// 保存"是否需要停顿"设置
			prefs.edit().putBoolean("needPause", isChecked).apply();
		});
		// 设置停顿时间滑动条的监听
		binding.pauseTimeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			/**
			 * 当滑动条的进度变化时调用
			 *
			 * @param seekBar 滑动条
			 * @param progress 当前进度
			 * @param fromUser 是否是用户操作
			 */
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				// 更新显示的停顿时间值
				binding.pauseTimeValueText.setText(String.valueOf(progress));
				// 保存"停顿时间"设置
				prefs.edit().putInt("pauseTime", progress).apply();
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
		});
	}

	/**
	 * 设置滑动速度
	 */
	private void setSlideSpeed() {
		// 设置速度滑动条的监听
		binding.speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			/**
			 * 当滑动条的进度变化时调用
			 *
			 * @param seekBar 滑动条
			 * @param progress 当前进度
			 * @param fromUser 是否是用户操作
			 */
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				// 保存"滑动速度"设置
				prefs.edit().putInt("speed", progress).apply();
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			/**
			 * 停止拖动滑动条时更新服务中的滑动速度
			 *
			 * @param seekBar 滑动条
			 */
			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				if (isAccessibilityEnabled()) {
					// 获取当前速度值
					int speed = seekBar.getProgress();
					// 更新服务中的滑动速度
					Intent intent = new Intent(MainActivity.this, AutoSlideService.class);
					intent.putExtra("speed", speed);
					startService(intent);
				}
			}
		});
	}

	/**
	 * 设置无障碍权限
	 */
	private void setAccessibilityPermission() {
		binding.accessibilityPermissionSwitch.setOnClickListener(v -> {
			if (binding.accessibilityPermissionSwitch.isChecked()) {
				navigateToAppAccessibilitySettings();
			} else {
				if (isAccessibilityEnabled()) {
					// 关闭时提示用户前往系统设置禁用无障碍服务
					new AlertDialog.Builder(this).setTitle(R.string.permission_required).setMessage(R.string.accessibility_disable_message).setPositiveButton(R.string.go_to_close, (dialog, which) -> navigateToAppAccessibilitySettings()).setNegativeButton(R.string.cancel, (dialog, which) -> binding.accessibilityPermissionSwitch.setChecked(true)).show();
				}
			}
		});
	}

	/**
	 * 导航到当前应用的无障碍服务设置页面
	 */
	private void navigateToAppAccessibilitySettings() {
		Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		String serviceName = getPackageName() + "/" + AutoSlideService.class.getCanonicalName();
		intent.putExtra(":settings:fragment_args_key", serviceName);
		intent.putExtra(":settings:show_fragment_args", intent.getExtras());
		startActivity(intent);
	}

	/**
	 * 检查无障碍服务是否开启
	 *
	 * @return 开启-true 未开启-false
	 */
	private boolean isAccessibilityEnabled() {
		int accessibilityEnabled;
		try {
			accessibilityEnabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
		} catch (Settings.SettingNotFoundException e) {
			throw new RuntimeException(e);
		}
		if (accessibilityEnabled == 1) {
			String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
			if (services != null) {
				return services.toLowerCase().contains(getPackageName().toLowerCase());
			}
		}
		return false;
	}

	private void start() {
		binding.startButton.setOnClickListener(v -> {
			// 检查无障碍服务权限
			if (!isAccessibilityEnabled()) {
				new AlertDialog.Builder(this).setTitle(R.string.permission_required).setMessage(R.string.accessibility_permission_message).setPositiveButton(R.string.go_to_open, (dialog, which) -> navigateToAppAccessibilitySettings()).setNegativeButton(R.string.cancel, null).show();
				return;
			}
			// 检查悬浮窗权限
			if (!Settings.canDrawOverlays(this)) {
				new AlertDialog.Builder(this).setTitle(R.string.permission_required).setMessage("需要开启悬浮窗权限").setPositiveButton(R.string.go_to_open, (dialog, which) -> {
					Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
					startActivity(intent);
				}).setNegativeButton(R.string.cancel, null).show();
				return;
			}
			// 启动悬浮窗服务
			Intent floatingIntent = new Intent(this, FloatingWindowService.class);
			startService(floatingIntent);
			// 最小化应用
			moveTaskToBack(true);
		});
	}
}