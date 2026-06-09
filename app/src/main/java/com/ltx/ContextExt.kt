package com.ltx

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.ltx.service.AutoSlideService

/**
 * 判断当前应用⌈无障碍服务权限⌋是否已启用
 *
 * @return ⌈无障碍服务权限⌋是否已启用
 */
fun Context.isAccessibilityServicePermissionEnabled(): Boolean {
    val enabled = runCatching {
        Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
    }.getOrDefault(0)
    if (enabled != 1) {
        return false
    }
    val services = Settings.Secure.getString(
        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val targetComponent = ComponentName(this, AutoSlideService::class.java)
    return services.split(":").any {
        ComponentName.unflattenFromString(it.trim()) == targetComponent
    }
}
