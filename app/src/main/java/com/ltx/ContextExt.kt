package com.ltx

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import com.ltx.service.AutoSlideService

/**
 * 判断当前应用⌈无障碍服务权限⌋是否已启用
 *
 * @return ⌈无障碍服务权限⌋是否已启用
 */
fun Context.isAccessibilityServicePermissionEnabled(): Boolean {
    // 检查全局无障碍总开关是否开启
    val enabled = runCatching {
        Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
    }.getOrDefault(0)
    if (enabled != 1) {
        return false
    }
    // 获取当前已启用的无障碍服务列表
    val services = Settings.Secure.getString(
        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val targetComponent = ComponentName(this, AutoSlideService::class.java)
    return services.split(":").any {
        ComponentName.unflattenFromString(it.trim()) == targetComponent
    }
}

/**
 * 清除指定方向的自定义手势轨迹并广播事件
 *
 * @param direction 方向字符串
 */
fun Context.clearCustomTrajectory(direction: String) {
    val key = getTrajectoryKey(direction) ?: return
    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
        remove(key)
    }
    SlideEventHub.sendEvent(SlideEvent.CustomTrajectoryCleared)
}

/**
 * 获取指定方向的自定义轨迹字符串
 *
 * @param direction 方向字符串
 * @return 自定义轨迹字符串
 */
fun Context.getCustomTrajectory(direction: String): String? {
    val key = getTrajectoryKey(direction) ?: return null
    val value = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null)
    return if (value.isNullOrBlank()) {
        null
    } else {
        value
    }
}

/**
 * 判断指定方向是否存在自定义轨迹
 *
 * @param direction 方向字符串
 * @return 是否存在有效自定义轨迹
 */
fun Context.hasCustomTrajectory(direction: String): Boolean {
    return getCustomTrajectory(direction) != null
}

/**
 * 从本地配置文件读取滑动配置
 *
 * @return 滑动配置数据对象
 */
fun Context.getSlideConfig(): SlideConfig {
    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return SlideConfig(
        speed = prefs.getInt(KEY_SPEED, DEFAULT_SPEED).coerceIn(1, 100),
        pauseMode = prefs.getInt(KEY_PAUSE_MODE, PAUSE_MODE_NONE),
        pauseTime = prefs.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME).coerceAtLeast(1),
        minPauseTime = prefs.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME).coerceAtLeast(1),
        maxPauseTime = prefs.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME).coerceAtLeast(1)
    )
}

