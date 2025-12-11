package com.ltx;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

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
		// 如果有永久授权且无障碍服务未开启
		if (hasSecureSettingsPermission() && !isAccessibilityEnabled()) {
			// 自动开启无障碍服务
			changeAccessibilityServiceState(true);
		}
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
			// 开关被打开
			if (binding.accessibilityPermissionSwitch.isChecked()) {
				if (hasSecureSettingsPermission()) {
					// 有永久授权则直接开启无障碍服务
					changeAccessibilityServiceState(true);
					Toast.makeText(this, "无障碍服务已开启", Toast.LENGTH_SHORT).show();
				} else {
					// 显示开启方式选择对话框
					showAccessibilityOptionDialog();
				}
			} else {
				if (isAccessibilityEnabled()) {
					if (hasSecureSettingsPermission()) {
						// 有永久授权则直接关闭无障碍服务
						changeAccessibilityServiceState(false);
					} else {
						// 无永久授权则提示用户手动关闭无障碍服务
						new AlertDialog.Builder(this).setTitle(R.string.permission_required).setMessage(R.string.accessibility_disable_message).setPositiveButton(R.string.go_to_close, (dialog, which) -> navigateToAppAccessibilitySettings()).setNegativeButton(R.string.cancel, (dialog, which) -> binding.accessibilityPermissionSwitch.setChecked(true)).show();
					}
				}
			}
		});
	}

	/**
	 * 显示开启方式选择对话框
	 */
	private void showAccessibilityOptionDialog() {
		String[] options = {"手动开启", "永久授权"};
		new AlertDialog.Builder(this).setTitle("选择开启方式").setItems(options, (dialog, which) -> {
			if (which == 0) {
				// 手动开启无障碍服务
				navigateToAppAccessibilitySettings();
			} else {
				// 提示用户执行ADB命令
				showAdbCommandDialog();
				// 恢复开关状态
				binding.accessibilityPermissionSwitch.setChecked(false);
			}
		}).setOnCancelListener(dialog -> binding.accessibilityPermissionSwitch.setChecked(false)).show();
	}

	/**
	 * 检查是否有写入安全设置权限
	 *
	 * @return true-有权限 false-无权限
	 */
	private boolean hasSecureSettingsPermission() {
		return checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
	}

	/**
	 * 改变无障碍服务状态
	 *
	 * @param enable true-开启 false-关闭
	 */
	private void changeAccessibilityServiceState(boolean enable) {
		String serviceName = getPackageName() + "/" + AutoSlideService.class.getCanonicalName();
		String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
		if (enable) {
			if (TextUtils.isEmpty(enabledServices)) {
				enabledServices = serviceName;
			} else if (!enabledServices.contains(serviceName)) {
				enabledServices += ":" + serviceName;
			}
			Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabledServices);
			Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
		} else {
			if (!TextUtils.isEmpty(enabledServices)) {
				StringBuilder newServices = new StringBuilder();
				for (String service : enabledServices.split(":")) {
					if (!service.equals(serviceName)) {
						if (newServices.length() > 0) {
							newServices.append(":");
						}
						newServices.append(service);
					}
				}
				Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices.toString());
			}
		}
		// 更新UI状态
		binding.accessibilityPermissionSwitch.setChecked(isAccessibilityEnabled());
	}

	/**
	 * 显示ADB命令说明
	 */
	@SuppressLint("SetTextI18n")
	private void showAdbCommandDialog() {
		String command = "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
		// 创建自定义布局
		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		int padding = (int) (24 * getResources().getDisplayMetrics().density);
		layout.setPadding(padding, padding / 2, padding, padding / 2);
		TextView message = new TextView(this);
		message.setText("请连接电脑并在终端运行以下ADB命令");
		message.setTextSize(16);
		message.setTextColor(Color.parseColor("#1f1f1f"));
		layout.addView(message);
		// 添加代码块
		TextView codeBlock = new TextView(this);
		codeBlock.setText(command);
		codeBlock.setTypeface(Typeface.MONOSPACE);
		codeBlock.setBackgroundColor(Color.parseColor("#F5F5F5"));
		int codePadding = (int) (12 * getResources().getDisplayMetrics().density);
		codeBlock.setPadding(codePadding, codePadding, codePadding, codePadding);
		codeBlock.setTextSize(13);
		codeBlock.setTextColor(Color.parseColor("#333333"));
		codeBlock.setTextIsSelectable(true);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		params.setMargins(0, (int) (16 * getResources().getDisplayMetrics().density), 0, 0);
		codeBlock.setLayoutParams(params);
		layout.addView(codeBlock);
		new AlertDialog.Builder(this).setTitle("获取永久授权").setView(layout).setPositiveButton("复制命令", (dialog, which) -> {
			copyToClipboard(command);
			Toast.makeText(this, "命令已复制到剪贴板", Toast.LENGTH_SHORT).show();
		}).setNegativeButton("取消", null).show();
	}

	/**
	 * 复制内容到剪贴板
	 *
	 * @param text 要复制的内容
	 */
	private void copyToClipboard(String text) {
		ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		ClipData clipData = ClipData.newPlainText("ADB Command", text);
		clipboardManager.setPrimaryClip(clipData);
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
			// 如果无障碍服务未开启
			if (!isAccessibilityEnabled()) {
				if (hasSecureSettingsPermission()) {
					// 有永久授权则自动开启无障碍服务
					changeAccessibilityServiceState(true);
					Toast.makeText(this, "已自动开启无障碍服务", Toast.LENGTH_SHORT).show();
				} else {
					// 提示用户开启无障碍服务
					new AlertDialog.Builder(this).setTitle(R.string.permission_required).setMessage(R.string.accessibility_permission_message).setPositiveButton(R.string.go_to_open, (dialog, which) -> showAccessibilityOptionDialog()).setNegativeButton(R.string.cancel, null).show();
					return;
				}
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