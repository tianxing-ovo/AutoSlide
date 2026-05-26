package com.ltx

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import com.ltx.databinding.ActivityMainBinding
import com.ltx.service.AutoSlideService
import com.ltx.service.FloatingWindowService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.Locale

/**
 * 应用主界面
 *
 * @author tianxing
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: SharedPreferences

    /**
     * 全局常量
     */
    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 100

        // 无障碍授权方式选项常量
        private const val OPTION_MANUAL = 0
        private const val OPTION_SHIZUKU = 1
        private const val OPTION_ADB = 2
    }

    /**
     * Shizuku命令执行结果数据类
     */
    private data class ShizukuCommandResult(
        val exitCode: Int, val stdout: String, val stderr: String
    )

    /* Shizuku授权待执行的回调 */
    private var pendingShizukuOnGranted: (() -> Unit)? = null
    private var pendingShizukuOnFailed: (() -> Unit)? = null

    /* Shizuku权限请求监听器 */
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                pendingShizukuOnGranted?.invoke()
            } else {
                Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                pendingShizukuOnFailed?.invoke()
            }
        }

    /**
     * 初始化Activity界面布局和事件绑定
     *
     * @param savedInstanceState 系统恢复状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 设置状态栏图标颜色为深色
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        )
        // 初始化SharedPreferences用于本地配置存储
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        restoreSettings()
        setupPauseControls()
        setupSpeedControl()
        setupAccessibilityServicePermissionToggle()
        setupOverlayPermissionToggle()
        setupStartButton()
        setupUpdateButton()
    }

    /**
     * 活动恢复时检查⌈无障碍服务权限⌋并同步开关状态
     */
    override fun onResume() {
        super.onResume()
        // 检查是否具备⌈写入安全设置权限⌋并且⌈无障碍服务权限⌋未启用
        if (hasWriteSecureSettingsPermission() && !isAccessibilityServicePermissionEnabled()) {
            changeAccessibilityServicePermissionState(enable = true)
        }
        // 检查Shizuku是否可用并且⌈悬浮窗权限⌋未启用，自动授权
        if (!Settings.canDrawOverlays(this) && canUseShizuku()) {
            grantOverlayPermissionViaShizuku()
        }
        binding.accessibilityServicePermissionSwitch.isChecked =
            isAccessibilityServicePermissionEnabled()
        binding.overlayPermissionSwitch.isChecked = Settings.canDrawOverlays(this)
        UpdateChecker.onHostResumed(this)
    }

    /**
     * 活动恢复时检查更新
     */
    override fun onPostResume() {
        super.onPostResume()
        UpdateChecker.onHostResumed(this)
    }

    /**
     * 窗口焦点变化时检查更新
     *
     * @param hasFocus 是否有窗口焦点
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            UpdateChecker.onHostResumed(this)
        }
    }

    /**
     * 活动销毁时移除Shizuku权限请求监听器
     */
    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    /**
     * 恢复上次配置
     */
    private fun restoreSettings() {
        // 恢复滑动速度
        val speed = preferences.getInt(KEY_SPEED, DEFAULT_SPEED)
        // 恢复停顿模式
        var pauseMode = preferences.getInt(KEY_PAUSE_MODE, -1)
        if (pauseMode == -1) {
            val needPause = preferences.getBoolean("needPause", false)
            pauseMode = if (needPause) PAUSE_MODE_FIXED else PAUSE_MODE_NONE
            preferences.edit { putInt(KEY_PAUSE_MODE, pauseMode) }
        }
        // 恢复停顿时间
        val pauseTime = preferences.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME).coerceAtLeast(1)
        // 恢复随机停顿时间范围
        val minPauseTime =
            preferences.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME).coerceAtLeast(1)
        val maxPauseTime =
            preferences.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME).coerceAtLeast(1)
        binding.speedSeekBar.progress = speed
        when (pauseMode) {
            PAUSE_MODE_NONE -> binding.pauseModeToggleGroup.check(R.id.btnNoPause)
            PAUSE_MODE_FIXED -> binding.pauseModeToggleGroup.check(R.id.btnFixedPause)
            PAUSE_MODE_RANDOM -> binding.pauseModeToggleGroup.check(R.id.btnRandomPause)
        }
        // 动态调整滑块最大值
        binding.pauseTimeSeekBar.max = maxOf(10, pauseTime)
        binding.pauseTimeSeekBar.progress = pauseTime
        binding.pauseTimeValueText.text = pauseTime.toString()
        binding.randomPauseTimeSlider.values =
            listOf(minPauseTime.toFloat(), maxPauseTime.toFloat())
        binding.randomPauseTimeValueText.text =
            getString(R.string.pause_time_range_format, minPauseTime, maxPauseTime)
        binding.randomPauseTimeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        updatePauseTimeVisibility(pauseMode)
    }

    /**
     * 绑定停顿相关控件事件并持久化用户设置
     */
    private fun setupPauseControls() {
        binding.pauseModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val pauseMode = when (checkedId) {
                    R.id.btnNoPause -> PAUSE_MODE_NONE
                    R.id.btnFixedPause -> PAUSE_MODE_FIXED
                    R.id.btnRandomPause -> PAUSE_MODE_RANDOM
                    else -> PAUSE_MODE_NONE
                }
                updatePauseTimeVisibility(pauseMode)
                preferences.edit { putInt(KEY_PAUSE_MODE, pauseMode) }
            }
        }
        // 停顿时间文本添加下划线效果
        binding.pauseTimeValueText.paintFlags =
            binding.pauseTimeValueText.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        // 停顿时间文本绑定点击事件
        binding.pauseTimeValueText.setOnClickListener {
            val editText = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(binding.pauseTimeValueText.text)
                setSelection(text.length)
            }
            val padding = (24 * resources.displayMetrics.density).toInt()
            val container = FrameLayout(this).apply {
                setPadding(padding, padding / 3, padding, padding / 3)
                addView(editText)
            }
            // 显示自定义停顿时间对话框
            AlertDialog.Builder(this).setTitle(R.string.custom_pause_time).setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val value = editText.text.toString().toIntOrNull()
                    if (value != null && value > 0) {
                        preferences.edit { putInt(KEY_PAUSE_TIME, value) }
                        binding.pauseTimeSeekBar.max = maxOf(10, value)
                        binding.pauseTimeSeekBar.progress = value
                        binding.pauseTimeValueText.text = value.toString()
                    } else {
                        Toast.makeText(this, R.string.invalid_input_number, Toast.LENGTH_SHORT)
                            .show()
                    }
                }.setNegativeButton(R.string.cancel, null).show()
        }
        // 绑定停顿时长滑块事件
        binding.pauseTimeSeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.pauseTimeValueText.text = progress.toString()
                preferences.edit { putInt(KEY_PAUSE_TIME, progress) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        // 绑定随机停顿时长范围滑块事件
        binding.randomPauseTimeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            val min = values[0].toInt()
            val max = values[1].toInt()
            binding.randomPauseTimeValueText.text =
                getString(R.string.pause_time_range_format, min, max)
            if (fromUser) {
                preferences.edit {
                    putInt(KEY_MIN_PAUSE_TIME, min)
                    putInt(KEY_MAX_PAUSE_TIME, max)
                }
            }
        }
    }

    /**
     * 绑定速度滑块事件
     */
    private fun setupSpeedControl() {
        binding.speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            /**
             * 速度变化时仅保存配置
             *
             * @param seekBar 当前滑块
             * @param progress 当前进度值
             * @param fromUser 是否由用户手势触发
             */
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                preferences.edit { putInt(KEY_SPEED, progress) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            /**
             * 结束拖动时把速度参数发送给服务
             *
             * @param seekBar 当前滑块
             */
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (!isAccessibilityServicePermissionEnabled()) {
                    return
                }
                AutoSlideService.getInstance()?.updateSpeed(seekBar.progress)
            }
        })
    }

    /**
     * 设置⌈无障碍服务权限⌋开关监听器
     */
    private fun setupAccessibilityServicePermissionToggle() {
        binding.accessibilityServicePermissionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == isAccessibilityServicePermissionEnabled()) return@setOnCheckedChangeListener
            if (isChecked) {
                onAccessibilityServicePermissionSwitchEnabled()
            } else {
                onAccessibilityServicePermissionSwitchDisabled()
            }
        }
    }

    /**
     * 设置⌈悬浮窗权限⌋开关监听器
     */
    private fun setupOverlayPermissionToggle() {
        binding.overlayPermissionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == Settings.canDrawOverlays(this)) return@setOnCheckedChangeListener
            if (isChecked) {
                onOverlayPermissionSwitchEnabled()
            } else {
                // Shizuku可用时直接关闭悬浮窗权限
                if (canUseShizuku()) {
                    revokeOverlayPermissionViaShizuku()
                } else {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    binding.overlayPermissionSwitch.isChecked = true
                }
            }
        }
    }

    /**
     * 处理⌈悬浮窗权限⌋开关打开动作
     */
    private fun onOverlayPermissionSwitchEnabled() {
        // Shizuku可用时直接通过Shizuku开启悬浮窗权限
        if (canUseShizuku()) {
            grantOverlayPermissionViaShizuku()
            return
        }
        // 展示⌈悬浮窗权限⌋选项弹窗
        showOverlayPermissionOptionDialog()
    }

    /* 展示⌈悬浮窗权限⌋选项弹窗 */
    private fun showOverlayPermissionOptionDialog() = with(AlertDialog.Builder(this)) {
        val options = arrayOf(
            getString(R.string.manual_enable),
            getString(R.string.shizuku_authorization)
        )
        setTitle(R.string.choose_enable_method)
        setItems(options) { _, which ->
            when (which) {
                OPTION_MANUAL -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                OPTION_SHIZUKU -> handleShizukuAuthorization(
                    onGranted = { grantOverlayPermissionViaShizuku() },
                    onFailed = { binding.overlayPermissionSwitch.isChecked = false }
                )
            }
        }
        setOnCancelListener { binding.overlayPermissionSwitch.isChecked = false }
        show()
    }

    /* 通过Shizuku授权悬浮窗权限 */
    private fun grantOverlayPermissionViaShizuku() {
        try {
            val result = executeShizukuCommand(
                "appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow"
            )
            if (result.exitCode != 0) {
                Log.e(
                    TAG,
                    "Shizuku overlay grant failed with exitCode=${result.exitCode}, stdout=${result.stdout}, stderr=${result.stderr}"
                )
                Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                binding.overlayPermissionSwitch.isChecked = false
                return
            }
            binding.overlayPermissionSwitch.post {
                if (Settings.canDrawOverlays(this)) {
                    binding.overlayPermissionSwitch.isChecked = true
                    Toast.makeText(this, R.string.overlay_permission_enabled, Toast.LENGTH_SHORT).show()
                } else {
                    Log.e(TAG, "Overlay permission still missing after Shizuku grant")
                    Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                    binding.overlayPermissionSwitch.isChecked = false
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Shizuku overlay grant execution failed", exception)
            Toast.makeText(this, R.string.shizuku_exception, Toast.LENGTH_SHORT).show()
            binding.overlayPermissionSwitch.isChecked = false
        }
    }

    /* 通过Shizuku撤销悬浮窗权限 */
    private fun revokeOverlayPermissionViaShizuku() {
        try {
            val result = executeShizukuCommand(
                "appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "deny"
            )
            if (result.exitCode != 0) {
                Log.e(
                    TAG,
                    "Shizuku overlay revoke failed with exitCode=${result.exitCode}, stdout=${result.stdout}, stderr=${result.stderr}"
                )
                Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                binding.overlayPermissionSwitch.isChecked = true
                return
            }
            binding.overlayPermissionSwitch.post {
                binding.overlayPermissionSwitch.isChecked = Settings.canDrawOverlays(this)
                Toast.makeText(this, R.string.overlay_permission_disabled, Toast.LENGTH_SHORT).show()
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Shizuku overlay revoke execution failed", exception)
            Toast.makeText(this, R.string.shizuku_exception, Toast.LENGTH_SHORT).show()
            binding.overlayPermissionSwitch.isChecked = true
        }
    }

    /**
     * 处理⌈无障碍服务权限⌋开关打开动作
     */
    private fun onAccessibilityServicePermissionSwitchEnabled() {
        // 有⌈写入安全设置权限⌋时直接开启⌈无障碍服务权限⌋
        if (hasWriteSecureSettingsPermission()) {
            changeAccessibilityServicePermissionState(enable = true)
            Toast.makeText(this, R.string.accessibility_service_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        // 展示⌈无障碍服务权限⌋选项弹窗
        showAccessibilityServicePermissionOptionDialog()
    }

    /**
     * 处理⌈无障碍服务权限⌋开关关闭动作
     */
    private fun onAccessibilityServicePermissionSwitchDisabled() {
        if (!isAccessibilityServicePermissionEnabled()) {
            return
        }
        // 有⌈写入安全设置权限⌋时直接关闭⌈无障碍服务权限⌋
        if (hasWriteSecureSettingsPermission()) {
            changeAccessibilityServicePermissionState(enable = false)
            Toast.makeText(this, R.string.accessibility_service_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        // 打开⌈无障碍⌋设置页
        openAppAccessibilitySettings()
    }

    /* 展示⌈无障碍服务权限⌋选项弹窗 */
    private fun showAccessibilityServicePermissionOptionDialog() = with(AlertDialog.Builder(this)) {
        // 选项数组
        val options = arrayOf(
            getString(R.string.manual_enable),
            getString(R.string.shizuku_authorization),
            getString(R.string.adb_authorization)
        )
        // 设置标题
        setTitle(R.string.choose_enable_method)
        // 设置选项监听器
        setItems(options) { _, which ->
                when (which) {
                    OPTION_MANUAL -> openAppAccessibilitySettings()
                    OPTION_SHIZUKU -> handleShizukuAuthorization(
                        onGranted = { grantPermissionViaShizuku() },
                        onFailed = { binding.accessibilityServicePermissionSwitch.isChecked = false }
                    )
                    OPTION_ADB -> {
                        showAdbCommandDialog()
                        binding.accessibilityServicePermissionSwitch.isChecked = false
                    }
                }
        }
        // 设置取消监听器
        setOnCancelListener { binding.accessibilityServicePermissionSwitch.isChecked = false }
        // 展示对话框
        show()
    }

    /* 处理Shizuku授权(通用) */
    private fun handleShizukuAuthorization(onGranted: () -> Unit, onFailed: () -> Unit) {
        // 检查Shizuku是否运行
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_SHORT).show()
            onFailed()
            return
        }
        // 检查Shizuku权限是否已授权
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingShizukuOnGranted = onGranted
            pendingShizukuOnFailed = onFailed
            try {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "Failed to request Shizuku permission", exception)
                Toast.makeText(this, R.string.shizuku_exception, Toast.LENGTH_SHORT).show()
                onFailed()
            }
        }
    }

    /* 检查Shizuku是否可用(运行中且已授权) */
    private fun canUseShizuku(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /* 通过Shizuku授权⌈写入安全设置⌋权限 */
    private fun grantPermissionViaShizuku() {
        try {
            val result = executeShizukuCommand(
                "/system/bin/pm", "grant", packageName, Manifest.permission.WRITE_SECURE_SETTINGS
            )
            if (result.exitCode != 0) {
                Log.e(
                    TAG,
                    "Shizuku grant failed with exitCode=${result.exitCode}, stdout=${result.stdout}, stderr=${result.stderr}"
                )
                Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                binding.accessibilityServicePermissionSwitch.isChecked = false
                return
            }
            binding.accessibilityServicePermissionSwitch.post {
                if (hasWriteSecureSettingsPermission()) {
                    changeAccessibilityServicePermissionState(enable = true)
                    Toast.makeText(this, R.string.accessibility_service_enabled, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Log.e(TAG, "WRITE_SECURE_SETTINGS still missing after Shizuku grant")
                    Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                    binding.accessibilityServicePermissionSwitch.isChecked = false
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Shizuku grant execution failed", exception)
            Toast.makeText(this, R.string.shizuku_exception, Toast.LENGTH_SHORT).show()
            binding.accessibilityServicePermissionSwitch.isChecked = false
        }
    }

    /**
     * 执行Shizuku命令
     *
     * @param command 要执行的命令
     * @return 命令执行结果
     */
    private fun executeShizukuCommand(vararg command: String): ShizukuCommandResult {
        val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply {
            isAccessible = true
        }
        val process = newProcessMethod.invoke(null, command, null, null) as ShizukuRemoteProcess
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().use { it.readText().trim() }
        val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
        process.destroy()
        return ShizukuCommandResult(exitCode, stdout, stderr)
    }

    /* 检查是否具备⌈写入安全设置⌋权限 */
    private fun hasWriteSecureSettingsPermission() =
        checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED


    /**
     * 更新⌈无障碍服务权限⌋状态
     *
     * @param enable 是否启用⌈无障碍服务权限⌋
     */
    private fun changeAccessibilityServicePermissionState(enable: Boolean) {
        val serviceName = "$packageName/${AutoSlideService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.split(":")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
        // 确保服务列表不包含重复项
        if (enable) {
            enabledServices.add(serviceName)
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } else {
            enabledServices.remove(serviceName)
        }
        // 更新无障碍服务列表
        Settings.Secure.putString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            enabledServices.joinToString(":")
        )
        // 更新无障碍开关状态
        binding.accessibilityServicePermissionSwitch.isChecked =
            isAccessibilityServicePermissionEnabled()
    }

    /* 显示ADB授权所需的命令弹窗 */
    private fun showAdbCommandDialog() {
        val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val content = createAdbDialogContent(command)
        // 展示ADB命令弹窗
        AlertDialog.Builder(this).setTitle(R.string.authorize_via_adb).setView(content)
            .setPositiveButton(R.string.copy_command) { _, _ ->
                copyToClipboard(command)
                Toast.makeText(this, R.string.command_copied, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /**
     * 构造ADB指令展示视图
     *
     * @param command 需要展示的ADB命令
     * @return 包含ADB命令展示的线性布局视图
     */
    private fun createAdbDialogContent(command: String): LinearLayout {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val codePadding = (12 * resources.displayMetrics.density).toInt()
        // 提示用户连接电脑并在终端运行以下ADB命令
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.adb_command_instruction)
                    textSize = 16f
                    setTextColor("#1f1f1f".toColorInt())
                })
            // 展示ADB命令
            addView(
                TextView(this@MainActivity).apply {
                    text = command
                    typeface = Typeface.MONOSPACE
                    setBackgroundColor("#F5F5F5".toColorInt())
                    setPadding(codePadding, codePadding, codePadding, codePadding)
                    textSize = 13f
                    setTextColor("#333333".toColorInt())
                    setTextIsSelectable(true)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
                    }
                })
        }
    }

    /**
     * 把文本写入系统剪贴板
     *
     * @param text 需要复制的文本
     */
    private fun copyToClipboard(text: String) {
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = ClipData.newPlainText("ADB Command", text)
        clipboardManager.setPrimaryClip(clipData)
    }

    /* 打开系统⌈无障碍⌋设置页并定位到当前服务 */
    private fun openAppAccessibilitySettings() {
        val serviceName = "$packageName/${AutoSlideService::class.java.canonicalName}"
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(":settings:fragment_args_key", serviceName)
            putExtra(":settings:show_fragment_args", extras)
        }
        startActivity(intent)
    }

    /**
     * 判断当前应用⌈无障碍服务权限⌋是否已启用
     *
     * @return ⌈无障碍服务权限⌋是否已启用
     */
    private fun isAccessibilityServicePermissionEnabled(): Boolean {
        // 获取⌈无障碍服务权限⌋是否已启用状态
        val accessibilityEnabled = runCatching {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        }.getOrElse {
            return false
        }
        // 检查⌈无障碍服务权限⌋是否已启用
        if (accessibilityEnabled != 1) {
            return false
        }
        // 获取已启用的⌈无障碍服务权限⌋列表
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // 检查⌈无障碍服务权限⌋列表是否包含当前应用
        val packageNameLower = packageName.lowercase(Locale.ROOT)
        return enabledServices.lowercase(Locale.ROOT).contains(packageNameLower)
    }

    /* 绑定⌈开始⌋按钮点击事件并执行运行前权限校验 */
    private fun setupStartButton() {
        // 绑定⌈开始⌋按钮点击事件
        binding.startButton.setOnClickListener {
            if (!isAccessibilityServicePermissionEnabled()) {
                if (hasWriteSecureSettingsPermission()) {
                    changeAccessibilityServicePermissionState(enable = true)
                    Toast.makeText(
                        this, R.string.accessibility_service_auto_enabled, Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AlertDialog.Builder(this).setTitle(R.string.permission_required)
                        .setMessage(R.string.accessibility_service_description)
                        .setPositiveButton(R.string.go_to_open) { _, _ ->
                            showAccessibilityServicePermissionOptionDialog()
                        }.setNegativeButton(R.string.cancel, null).show()
                    return@setOnClickListener
                }
            }
            // 校验悬浮窗权限
            if (!Settings.canDrawOverlays(this)) {
                // Shizuku可用时自动授权悬浮窗权限
                if (canUseShizuku()) {
                    grantOverlayPermissionViaShizuku()
                    if (!Settings.canDrawOverlays(this)) {
                        return@setOnClickListener
                    }
                } else {
                    AlertDialog.Builder(this).setTitle(R.string.permission_required)
                        .setMessage(R.string.overlay_permission_required)
                        .setPositiveButton(R.string.go_to_open) { _, _ ->
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                        }.setNegativeButton(R.string.cancel, null).show()
                    return@setOnClickListener
                }
            }
            // 校验通过后启动悬浮窗服务并把应用退到后台
            val floatingIntent = Intent(this, FloatingWindowService::class.java)
            startService(floatingIntent)
            moveTaskToBack(true)
        }
    }

    /* 绑定⌈检查更新⌋按钮点击事件 */
    private fun setupUpdateButton() {
        binding.checkUpdateButton.setOnClickListener {
            UpdateChecker.checkUpdate(this, showToastOnLatest = true)
        }
    }

    /**
     * 更新⌈停顿时间⌋面板的可见性
     *
     * @param pauseMode 停顿模式
     */
    private fun updatePauseTimeVisibility(pauseMode: Int) {
        binding.pauseTimeLayout.isVisible = pauseMode == PAUSE_MODE_FIXED
        binding.randomPauseTimeLayout.isVisible = pauseMode == PAUSE_MODE_RANDOM
    }
}
