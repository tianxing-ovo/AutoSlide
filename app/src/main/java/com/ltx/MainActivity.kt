package com.ltx

import android.Manifest
import android.annotation.SuppressLint
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
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ltx.databinding.ActivityMainBinding
import com.ltx.service.AutoSlideService
import com.ltx.service.FloatingWindowService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import android.R as AndroidR
import com.google.android.material.R as MaterialR

/**
 * 应用主界面
 *
 * @author tianxing
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: SharedPreferences
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    /* 全局常量 */
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
     *
     * @param exitCode 命令执行退出码
     * @param stdout 命令执行标准输出
     * @param stderr 命令执行标准错误输出
     */
    private data class ShizukuCommandResult(
        val exitCode: Int, val stdout: String, val stderr: String
    )

    /* Shizuku授权待执行的回调 */
    private var pendingShizukuOnGranted: (() -> Unit)? = null
    private var pendingShizukuOnFailed: (() -> Unit)? = null

    /* Shizuku权限请求监听器 */
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            pendingShizukuOnGranted?.invoke()
        } else {
            Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
            pendingShizukuOnFailed?.invoke()
        }
        pendingShizukuOnGranted = null
        pendingShizukuOnFailed = null
    }

    /**
     * 初始化Activity界面布局和事件绑定
     *
     * @param savedInstanceState 系统恢复状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 初始化SharedPreferences用于本地配置存储
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        restoreSettings()
        setupPauseControls()
        setupSpeedControl()
        setupAccessibilityServicePermissionToggle()
        setupOverlayPermissionToggle()
        setupStartButton()
        setupUpdateButton()
        // 注册Shizuku监听器
        binding.root.post {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
    }

    /* 活动恢复时检查⌈无障碍服务权限⌋并同步开关状态 */
    override fun onResume() {
        super.onResume()
        // 使用协程异步检查权限并同步开关状态
        lifecycleScope.launch(ioDispatcher) {
            val hasWriteSecure = hasWriteSecureSettingsPermission()
            val isAccessibilityEnabled = isAccessibilityServicePermissionEnabled()
            // 有⌈写入安全设置权限⌋时直接开启⌈无障碍服务权限⌋
            if (hasWriteSecure && !isAccessibilityEnabled) {
                changeAccessibilityServicePermissionState(enable = true)
            }
            val canDrawOverlays = Settings.canDrawOverlays(this@MainActivity)
            val useShizuku = canUseShizuku()
            // Shizuku可用时自动授权悬浮窗权限
            if (!canDrawOverlays && useShizuku) {
                withContext(mainDispatcher) {
                    if (!isFinishing && !isDestroyed) {
                        grantOverlayPermissionViaShizuku()
                    }
                }
            }
            // 获取最终的权限状态
            val finalAccessibilityEnabled = isAccessibilityServicePermissionEnabled()
            val finalCanDrawOverlays = Settings.canDrawOverlays(this@MainActivity)
            // 回到主线程同步开关状态并应用无动画过渡
            withContext(mainDispatcher) {
                if (!isFinishing && !isDestroyed) {
                    binding.accessibilityServicePermissionSwitch.isChecked = finalAccessibilityEnabled
                    binding.accessibilityServicePermissionSwitch.jumpDrawablesToCurrentState()
                    binding.overlayPermissionSwitch.isChecked = finalCanDrawOverlays
                    binding.overlayPermissionSwitch.jumpDrawablesToCurrentState()
                }
            }
        }
        UpdateChecker.onHostResumed(this)
    }

    /* 活动销毁时移除Shizuku权限请求监听器 */
    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    /* 恢复上次配置 */
    private fun restoreSettings() {
        // 恢复滑动速度
        val speed = preferences.getInt(KEY_SPEED, DEFAULT_SPEED).coerceIn(1, 100)
        // 恢复停顿模式
        val pauseMode = preferences.getPauseMode()
        // 恢复停顿时间
        val pauseTime = preferences.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME).coerceAtLeast(1)
        // 恢复随机停顿时间范围
        val minPauseTime = preferences.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME).coerceAtLeast(1)
        val maxPauseTime = preferences.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME).coerceAtLeast(1)
        binding.speedSlider.value = speed.toFloat()
        binding.speedSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        when (pauseMode) {
            PAUSE_MODE_NONE -> binding.pauseModeToggleGroup.check(R.id.btnNoPause)
            PAUSE_MODE_FIXED -> binding.pauseModeToggleGroup.check(R.id.btnFixedPause)
            PAUSE_MODE_RANDOM -> binding.pauseModeToggleGroup.check(R.id.btnRandomPause)
        }
        // 动态调整固定停顿时间滑块的最大值
        binding.pauseTimeSlider.valueTo = maxOf(10, pauseTime).toFloat()
        binding.pauseTimeSlider.value = pauseTime.toFloat()
        // 动态调整随机停顿范围滑块的最大值并排序赋值
        binding.randomPauseTimeSlider.valueTo = maxOf(10, minPauseTime, maxPauseTime).toFloat()
        binding.randomPauseTimeSlider.values = listOf(minPauseTime.toFloat(), maxPauseTime.toFloat()).sorted()
        binding.pauseTimeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.randomPauseTimeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        updatePauseTimeVisibility(pauseMode)
    }

    /* 绑定停顿相关控件事件并持久化用户设置 */
    @SuppressLint("SetTextI18n")
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
                // 更新停顿配置
                AutoSlideService.getInstance()?.updatePauseConfig(
                    pauseMode,
                    preferences.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME),
                    preferences.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME),
                    preferences.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME)
                )
            }
        }
        // 停顿时间文本绑定点击事件
        binding.pauseTimeValueText.setOnClickListener {
            if (preferences.getInt(
                    KEY_PAUSE_MODE, PAUSE_MODE_NONE
                ) != PAUSE_MODE_FIXED
            ) return@setOnClickListener
            showCustomPauseTimeDialog()
        }
        // 设置滑块提示气泡显示为整数
        binding.pauseTimeSlider.setLabelFormatter { value -> value.toInt().toString() }
        binding.randomPauseTimeSlider.setLabelFormatter { value -> value.toInt().toString() }
        // 绑定停顿时长滑块事件
        binding.pauseTimeSlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            binding.pauseTimeValueText.text = progress.toString()
            if (fromUser) {
                preferences.edit { putInt(KEY_PAUSE_TIME, progress) }
            }
        }
        // 绑定随机停顿时长范围滑块事件
        binding.randomPauseTimeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            val min = values[0].toInt()
            val max = values[1].toInt()
            binding.pauseTimeValueText.text = getString(R.string.pause_time_range_format, min, max)
            if (fromUser) {
                preferences.edit {
                    putInt(KEY_MIN_PAUSE_TIME, min)
                    putInt(KEY_MAX_PAUSE_TIME, max)
                }
            }
        }
        // 绑定停顿时长滑块触摸松开事件
        binding.pauseTimeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                AutoSlideService.getInstance()?.updatePauseConfig(
                    preferences.getPauseMode(),
                    slider.value.toInt(),
                    preferences.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME),
                    preferences.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME)
                )
            }
        })
        // 绑定随机停顿时长范围滑块触摸松开事件
        binding.randomPauseTimeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = Unit
            override fun onStopTrackingTouch(slider: RangeSlider) {
                val values = slider.values
                val min = values[0].toInt()
                val max = values[1].toInt()
                AutoSlideService.getInstance()?.updatePauseConfig(
                    preferences.getPauseMode(), preferences.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME), min, max
                )
            }
        })
    }

    /* 显示自定义停顿时间输入对话框 */
    @SuppressLint("SetTextI18n")
    private fun showCustomPauseTimeDialog() {
        val textInputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.custom_pause_time)
        }
        val editText = TextInputEditText(textInputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(binding.pauseTimeValueText.text)
            setSelection(text?.length ?: 0)
        }
        textInputLayout.addView(editText)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, padding / 3)
            addView(textInputLayout)
        }
        // 显示自定义停顿时间对话框
        AlertDialog.Builder(this).setTitle(R.string.custom_pause_time).setView(container)
            .setPositiveButton(AndroidR.string.ok) { _, _ ->
                val value = editText.text.toString().toIntOrNull()
                if (value != null && value > 0) {
                    preferences.edit { putInt(KEY_PAUSE_TIME, value) }
                    binding.pauseTimeSlider.valueTo = maxOf(10, value).toFloat()
                    binding.pauseTimeSlider.value = value.toFloat()
                    binding.pauseTimeValueText.text = value.toString()
                    // 更新停顿配置
                    AutoSlideService.getInstance()?.updatePauseConfig(
                        preferences.getPauseMode(),
                        value,
                        preferences.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME),
                        preferences.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME)
                    )
                } else {
                    Toast.makeText(this, R.string.invalid_input_number, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /* 绑定速度滑块事件 */
    private fun setupSpeedControl() {
        // 设置滑块提示气泡显示为整数
        binding.speedSlider.setLabelFormatter { value -> value.toInt().toString() }
        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            if (fromUser) {
                preferences.edit { putInt(KEY_SPEED, progress) }
            }
        }
        binding.speedSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                if (!isAccessibilityServicePermissionEnabled()) {
                    return
                }
                AutoSlideService.getInstance()?.updateSpeed(slider.value.toInt())
            }
        })
    }

    /* 设置⌈无障碍服务权限⌋开关监听器 */
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

    /* 设置⌈悬浮窗权限⌋开关监听器 */
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

    /* 处理⌈悬浮窗权限⌋开关打开动作 */
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
            getString(R.string.manual_enable), getString(R.string.shizuku_authorization)
        )
        setTitle(R.string.choose_enable_method)
        setItems(options) { _, which ->
            when (which) {
                OPTION_MANUAL -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                OPTION_SHIZUKU -> handleShizukuAuthorization(
                    onGranted = { grantOverlayPermissionViaShizuku() },
                    onFailed = { binding.overlayPermissionSwitch.isChecked = false })
            }
        }
        setOnCancelListener { binding.overlayPermissionSwitch.isChecked = false }
        show()
    }

    /* 通过Shizuku授权悬浮窗权限 */
    private fun grantOverlayPermissionViaShizuku() {
        runShizukuCommand(command = arrayOf("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow"), onSuccess = {
            binding.overlayPermissionSwitch.post {
                if (Settings.canDrawOverlays(this)) {
                    binding.overlayPermissionSwitch.isChecked = true
                    Toast.makeText(
                        this, R.string.overlay_permission_enabled, Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.e(TAG, "Overlay permission still missing after Shizuku grant")
                    Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                    binding.overlayPermissionSwitch.isChecked = false
                }
            }
        }, onFailure = { binding.overlayPermissionSwitch.isChecked = false })
    }

    /* 通过Shizuku撤销悬浮窗权限 */
    private fun revokeOverlayPermissionViaShizuku() {
        runShizukuCommand(command = arrayOf("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "deny"), onSuccess = {
            binding.overlayPermissionSwitch.post {
                binding.overlayPermissionSwitch.isChecked = Settings.canDrawOverlays(this)
                Toast.makeText(this, R.string.overlay_permission_disabled, Toast.LENGTH_SHORT).show()
            }
        }, onFailure = { binding.overlayPermissionSwitch.isChecked = true })
    }

    /* 处理⌈无障碍服务权限⌋开关打开动作 */
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

    /* 处理⌈无障碍服务权限⌋开关关闭动作 */
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
                    onFailed = { binding.accessibilityServicePermissionSwitch.isChecked = false })

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
        runShizukuCommand(
            command = arrayOf(
            "/system/bin/pm", "grant", packageName, Manifest.permission.WRITE_SECURE_SETTINGS
        ), onSuccess = {
            binding.accessibilityServicePermissionSwitch.post {
                if (hasWriteSecureSettingsPermission()) {
                    changeAccessibilityServicePermissionState(enable = true)
                    Toast.makeText(
                        this, R.string.accessibility_service_enabled, Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.e(TAG, "WRITE_SECURE_SETTINGS still missing after Shizuku grant")
                    Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                    binding.accessibilityServicePermissionSwitch.isChecked = false
                }
            }
        }, onFailure = { binding.accessibilityServicePermissionSwitch.isChecked = false })
    }

    /**
     * 执行Shizuku命令
     *
     * @param command 要执行的命令数组
     * @param onSuccess 命令执行成功时的回调
     * @param onFailure 命令执行失败时的回调
     */
    private fun runShizukuCommand(command: Array<String>, onSuccess: () -> Unit, onFailure: () -> Unit) {
        lifecycleScope.launch(ioDispatcher) {
            val result = runCatching { executeShizukuCommand(*command) }
            withContext(mainDispatcher) {
                if (!isFinishing && !isDestroyed) {
                    result.onSuccess { res ->
                        if (res.exitCode != 0) {
                            Log.e(
                                TAG,
                                "Shizuku command failed: cmd=${command.joinToString(" ")}, exitCode=${res.exitCode}, stdout=${res.stdout}, stderr=${res.stderr}"
                            )
                            Toast.makeText(this@MainActivity, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                            onFailure()
                        } else {
                            onSuccess()
                        }
                    }.onFailure { exception ->
                        Log.e(
                            TAG, "Shizuku command execution failed: cmd=${command.joinToString(" ")}", exception
                        )
                        Toast.makeText(this@MainActivity, R.string.shizuku_exception, Toast.LENGTH_SHORT).show()
                        onFailure()
                    }
                }
            }
        }
    }

    /**
     * 执行Shizuku命令
     *
     * @param command 要执行的命令数组
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
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabledServices.joinToString(":")
        )
        // 更新无障碍服务权限开关状态
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                binding.accessibilityServicePermissionSwitch.isChecked = isAccessibilityServicePermissionEnabled()
                binding.accessibilityServicePermissionSwitch.jumpDrawablesToCurrentState()
            }
        }
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
     * 从主题中获取颜色
     *
     * @param attrId 属性ID
     * @return 颜色
     */
    private fun getColorFromAttr(attrId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
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
        val textColorPrimary = getColorFromAttr(AndroidR.attr.textColorPrimary)
        val textColorSecondary = getColorFromAttr(AndroidR.attr.textColorSecondary)
        val colorSurfaceVariant = getColorFromAttr(MaterialR.attr.colorSurfaceVariant)
        // 提示用户连接电脑并在终端运行以下ADB命令
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.adb_command_instruction)
                    textSize = 16f
                    setTextColor(textColorPrimary)
                })
            // 展示ADB命令
            addView(
                TextView(this@MainActivity).apply {
                    text = command
                    typeface = Typeface.MONOSPACE
                    setBackgroundColor(colorSurfaceVariant)
                    setPadding(codePadding, codePadding, codePadding, codePadding)
                    textSize = 13f
                    setTextColor(textColorSecondary)
                    setTextIsSelectable(true)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
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
        val bundle = Bundle().apply {
            putString(":settings:fragment_args_key", serviceName)
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(":settings:fragment_args_key", serviceName)
            putExtra(":settings:show_fragment_args", bundle)
        }
        startActivity(intent)
    }

    /**
     * 判断当前应用⌈无障碍服务权限⌋是否已启用
     *
     * @return ⌈无障碍服务权限⌋是否已启用
     */
    private fun isAccessibilityServicePermissionEnabled(): Boolean {
        val enabled = runCatching {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        }.getOrDefault(0)
        if (enabled != 1) {
            return false
        }
        val services = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val serviceName = "$packageName/${AutoSlideService::class.java.canonicalName}"
        return services.split(":").any { it.trim().equals(serviceName, ignoreCase = true) }
    }

    /**
     * 确保⌈无障碍服务权限⌋已启用
     *
     * @return 无障碍服务权限是否已启用
     */
    private fun ensureAccessibilityPermission(): Boolean {
        if (isAccessibilityServicePermissionEnabled()) {
            return true
        }
        if (hasWriteSecureSettingsPermission()) {
            changeAccessibilityServicePermissionState(enable = true)
            Toast.makeText(
                this, R.string.accessibility_service_auto_enabled, Toast.LENGTH_SHORT
            ).show()
            return true
        }
        AlertDialog.Builder(this).setTitle(R.string.permission_required)
            .setMessage(R.string.accessibility_service_description).setPositiveButton(R.string.go_to_open) { _, _ ->
                showAccessibilityServicePermissionOptionDialog()
            }.setNegativeButton(R.string.cancel, null).show()
        return false
    }

    /**
     * 确保⌈悬浮窗权限⌋已启用
     *
     * @return 悬浮窗权限是否已启用
     */
    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) {
            return true
        }
        if (canUseShizuku()) {
            grantOverlayPermissionViaShizuku()
            return Settings.canDrawOverlays(this)
        }
        AlertDialog.Builder(this).setTitle(R.string.permission_required)
            .setMessage(R.string.overlay_permission_required).setPositiveButton(R.string.go_to_open) { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }.setNegativeButton(R.string.cancel, null).show()
        return false
    }

    /* 绑定⌈开始⌋按钮点击事件并执行运行前权限校验 */
    private fun setupStartButton() {
        // 绑定⌈开始⌋按钮点击事件
        binding.startButton.setOnClickListener {
            if (!ensureAccessibilityPermission()) return@setOnClickListener
            if (!ensureOverlayPermission()) return@setOnClickListener
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
    @SuppressLint("SetTextI18n")
    private fun updatePauseTimeVisibility(pauseMode: Int) {
        binding.pauseTimeContainer.isVisible = pauseMode != PAUSE_MODE_NONE
        if (pauseMode == PAUSE_MODE_FIXED) {
            binding.pauseTimeLabel.setText(R.string.pause_time)
            binding.pauseTimeSlider.isVisible = true
            binding.randomPauseTimeSlider.isVisible = false
            // 启用下划线及点击事件
            binding.pauseTimeValueText.paintFlags = binding.pauseTimeValueText.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            binding.pauseTimeValueText.isClickable = true
            binding.pauseTimeValueText.text = binding.pauseTimeSlider.value.toInt().toString()
        } else if (pauseMode == PAUSE_MODE_RANDOM) {
            binding.pauseTimeLabel.setText(R.string.random_pause_time_range)
            binding.pauseTimeSlider.isVisible = false
            binding.randomPauseTimeSlider.isVisible = true
            // 禁用下划线及点击事件
            binding.pauseTimeValueText.paintFlags =
                binding.pauseTimeValueText.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            binding.pauseTimeValueText.isClickable = false
            val values = binding.randomPauseTimeSlider.values
            if (values.size >= 2) {
                val min = values[0].toInt()
                val max = values[1].toInt()
                binding.pauseTimeValueText.text = getString(R.string.pause_time_range_format, min, max)
            }
        }
    }
}
