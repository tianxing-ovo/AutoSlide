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
	 * 设置停顿时间
	 */
	private void setPauseTime() {
		binding.pauseSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			// 根据开关状态控制停顿时间布局的显示和隐藏
			binding.pauseTimeLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
			// 设置停顿时间滑动条的监听
			binding.pauseTimeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					// 更新显示的停顿时间值
					binding.pauseTimeValueText.setText(String.valueOf(progress));
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {
				}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {
				}
			});
		});
	}

	/**
	 * 设置滑动速度
	 */
	private void setSlideSpeed() {
		// 设置速度滑动条的监听
		binding.speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				// 停止拖动时的处理
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
		binding.accessibilityPermissionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (isChecked) {
				// 跳转到无障碍设置页面
				Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
				startActivity(intent);
			}
		});
	}

	/**
	 * 检查无障碍服务是否开启
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
				new AlertDialog.Builder(this).setTitle(R.string.permission_required).setMessage(R.string.accessibility_permission_message).setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
					Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
					startActivity(intent);
				}).setNegativeButton(R.string.cancel, null).show();
				return;
			}
			// 检查悬浮窗权限
			if (!Settings.canDrawOverlays(this)) {
				new AlertDialog.Builder(this).setTitle(R.string.permission_required).setMessage("需要开启悬浮窗权限").setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
					Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
					startActivity(intent);
				}).setNegativeButton(R.string.cancel, null).show();
				return;
			}
			// 保存设置
			saveSettings();
			// 启动悬浮窗服务
			Intent floatingIntent = new Intent(this, FloatingWindowService.class);
			startService(floatingIntent);
			// 最小化应用
			moveTaskToBack(true);
		});
	}

	/**
	 * 保存设置
	 */
	private void saveSettings() {
		SharedPreferences.Editor editor = getSharedPreferences("slide_settings", MODE_PRIVATE).edit();
		editor.putInt("speed", binding.speedSeekBar.getProgress());
		editor.putBoolean("needPause", binding.pauseSwitch.isChecked());
		editor.putInt("pauseTime", binding.pauseTimeSeekBar.getProgress());
		editor.apply();
	}
}

