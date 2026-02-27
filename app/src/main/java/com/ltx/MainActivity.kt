package com.ltx

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import com.ltx.databinding.ActivityMainBinding
import com.ltx.service.AutoSlideService
import com.ltx.service.FloatingWindowService
import java.util.Locale

/**
 * 应用主界面
 *
 * @author tianxing
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: SharedPreferences

    companion object {
        private const val PREFS_NAME = "slide_settings"
        private const val KEY_SPEED = "speed"
        private const val KEY_NEED_PAUSE = "needPause"
        private const val KEY_PAUSE_TIME = "pauseTime"
        private const val DEFAULT_SPEED = 50
        private const val DEFAULT_NEED_PAUSE = false
        private const val DEFAULT_PAUSE_TIME = 1
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
        // 设置状态栏图标颜色为深色
        window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        )
        // 初始化SharedPreferences用于本地配置存储
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        restoreSettings()
        setupPauseControls()
        setupSpeedControl()
        setupAccessibilityToggle()
        setupStartButton()
        setupUpdateButton()
    }

    /**
     * 活动恢复时检查无障碍权限并同步开关状态
     */
    override fun onResume() {
        super.onResume()
        if (hasSecureSettingsPermission() && !isAccessibilityEnabled()) {
            changeAccessibilityServiceState(enable = true)
        }
        binding.accessibilityPermissionSwitch.isChecked = isAccessibilityEnabled()
        UpdateChecker.onHostResumed(this)
    }

    override fun onPostResume() {
        super.onPostResume()
        UpdateChecker.onHostResumed(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            UpdateChecker.onHostResumed(this)
        }
    }

    /**
     * 恢复上次配置
     */
    private fun restoreSettings() {
        val speed = preferences.getInt(KEY_SPEED, DEFAULT_SPEED)
        val needPause = preferences.getBoolean(KEY_NEED_PAUSE, DEFAULT_NEED_PAUSE)
        val pauseTime = preferences.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME)
        binding.speedSeekBar.progress = speed
        binding.pauseSwitch.isChecked = needPause
        binding.pauseTimeSeekBar.progress = pauseTime
        binding.pauseTimeValueText.text = pauseTime.toString()
        updatePauseTimeVisibility(needPause)
    }

    /**
     * 绑定停顿相关控件事件并持久化用户设置
     */
    private fun setupPauseControls() {
        binding.pauseSwitch.setOnCheckedChangeListener { _, isChecked ->
            updatePauseTimeVisibility(isChecked)
            preferences.edit { putBoolean(KEY_NEED_PAUSE, isChecked) }
        }
        // 绑定停顿时长滑块事件
        binding.pauseTimeSeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            /**
             * 停顿时长变化时同步UI与本地配置
             *
             * @param seekBar 当前滑块
             * @param progress 当前进度值
             * @param fromUser 是否由用户手势触发
             */
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.pauseTimeValueText.text = progress.toString()
                preferences.edit { putInt(KEY_PAUSE_TIME, progress) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
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
                if (!isAccessibilityEnabled()) {
                    return
                }
                val intent = Intent(this@MainActivity, AutoSlideService::class.java)
                intent.putExtra(KEY_SPEED, seekBar.progress)
                startService(intent)
            }
        })
    }

    /**
     * 绑定无障碍权限开关点击事件
     */
    private fun setupAccessibilityToggle() {
        binding.accessibilityPermissionSwitch.setOnClickListener {
            if (binding.accessibilityPermissionSwitch.isChecked) {
                onAccessibilitySwitchEnabled()
            } else {
                onAccessibilitySwitchDisabled()
            }
        }
    }

    /**
     * 处理无障碍开关打开动作
     */
    private fun onAccessibilitySwitchEnabled() {
        if (hasSecureSettingsPermission()) {
            changeAccessibilityServiceState(enable = true)
            Toast.makeText(this, R.string.accessibility_service_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        showAccessibilityOptionDialog()
    }

    /**
     * 处理无障碍开关关闭动作
     */
    private fun onAccessibilitySwitchDisabled() {
        if (!isAccessibilityEnabled()) {
            return
        }
        // 有权限时直接关闭服务
        if (hasSecureSettingsPermission()) {
            changeAccessibilityServiceState(enable = false)
            return
        }
        // 无权限时提示用户前往设置
        AlertDialog.Builder(this).setTitle(R.string.permission_required)
            .setMessage(R.string.accessibility_disable_message)
            .setPositiveButton(R.string.go_to_close) { _, _ ->
                openAppAccessibilitySettings()
            }.setNegativeButton(R.string.cancel) { _, _ ->
                binding.accessibilityPermissionSwitch.isChecked = true
            }.show()
    }

    /**
     * 展示无障碍开启方式选择
     */
    private fun showAccessibilityOptionDialog() {
        val options =
            arrayOf(getString(R.string.manual_enable), getString(R.string.permanent_authorization))
        AlertDialog.Builder(this).setTitle(R.string.choose_enable_method)
            .setItems(options) { _, which ->
                if (which == 0) {
                    openAppAccessibilitySettings()
                } else {
                    showAdbCommandDialog()
                    binding.accessibilityPermissionSwitch.isChecked = false
                }
            }.setOnCancelListener {
                binding.accessibilityPermissionSwitch.isChecked = false
            }.show()
    }

    /**
     * 检查是否具备"WRITE_SECURE_SETTINGS"权限
     *
     * @return 是否具备"WRITE_SECURE_SETTINGS"权限
     */
    private fun hasSecureSettingsPermission(): Boolean {
        return checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 启用或禁用当前应用的无障碍服务
     *
     * @param enable 是否启用无障碍服务
     */
    private fun changeAccessibilityServiceState(enable: Boolean) {
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
        binding.accessibilityPermissionSwitch.isChecked = isAccessibilityEnabled()
    }

    /**
     * 显示永久授权所需的ADB命令弹窗
     */
    @SuppressLint("SetTextI18n")
    private fun showAdbCommandDialog() {
        val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val content = createAdbDialogContent(command)
        // 展示ADB命令弹窗
        AlertDialog.Builder(this).setTitle(R.string.get_permanent_authorization).setView(content)
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

    /**
     * 打开系统无障碍设置页并定位到当前服务
     */
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
     * 判断当前应用无障碍服务是否已启用
     *
     * @return 无障碍服务是否已启用
     */
    private fun isAccessibilityEnabled(): Boolean {
        // 获取无障碍服务是否已启用状态
        val accessibilityEnabled = runCatching {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        }.getOrElse {
            return false
        }
        // 检查无障碍服务是否已启用
        if (accessibilityEnabled != 1) {
            return false
        }
        // 获取已启用的无障碍服务列表
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // 检查无障碍服务列表是否包含当前应用
        val packageNameLower = packageName.lowercase(Locale.ROOT)
        return enabledServices.lowercase(Locale.ROOT).contains(packageNameLower)
    }

    /**
     * 绑定"开始"按钮点击事件并执行运行前权限校验
     */
    private fun setupStartButton() {
        // 绑定"开始"按钮点击事件
        binding.startButton.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                if (hasSecureSettingsPermission()) {
                    changeAccessibilityServiceState(enable = true)
                    Toast.makeText(
                        this, R.string.accessibility_service_auto_enabled, Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AlertDialog.Builder(this).setTitle(R.string.permission_required)
                        .setMessage(R.string.accessibility_permission_message)
                        .setPositiveButton(R.string.go_to_open) { _, _ ->
                            showAccessibilityOptionDialog()
                        }.setNegativeButton(R.string.cancel, null).show()
                    return@setOnClickListener
                }
            }
            // 校验悬浮窗权限
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this).setTitle(R.string.permission_required)
                    .setMessage(R.string.overlay_permission_required)
                    .setPositiveButton(R.string.go_to_open) { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        startActivity(intent)
                    }.setNegativeButton(R.string.cancel, null).show()
                return@setOnClickListener
            }
            // 校验通过后启动悬浮窗服务并把应用退到后台
            val floatingIntent = Intent(this, FloatingWindowService::class.java)
            startService(floatingIntent)
            moveTaskToBack(true)
        }
    }

    /**
     * 绑定"检查更新"按钮点击事件
     */
    private fun setupUpdateButton() {
        binding.checkUpdateButton.setOnClickListener {
            UpdateChecker.checkUpdate(this, showToastOnLatest = true)
        }
    }

    /**
     * 更新停顿时长区域的可见性
     *
     * @param visible 是否显示停顿时长区域
     */
    private fun updatePauseTimeVisibility(visible: Boolean) {
        binding.pauseTimeLayout.visibility = if (visible) View.VISIBLE else View.GONE
    }
}