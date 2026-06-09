package com.ltx.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.ltx.MainActivity
import com.ltx.R
import com.ltx.isAccessibilityServicePermissionEnabled

/**
 * 自动滑动磁贴服务
 *
 * @author tianxing
 */
class AutoSlideTileService : TileService() {

    /* 开始监听 */
    override fun onStartListening() {
        super.onStartListening()
        updateTileState(FloatingWindowService.isRunning())
    }

    /**
     * 更新磁贴状态
     *
     * @param isActive 是否激活
     */
    private fun updateTileState(isActive: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_slide_tile)
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        // 检查⌈无障碍服务权限⌋和⌈悬浮窗权限⌋是否已启用
        val hasAccessibility = isAccessibilityServicePermissionEnabled()
        val hasOverlay = Settings.canDrawOverlays(this)
        if (!hasAccessibility || !hasOverlay) {
            // 提示用户
            Toast.makeText(this, R.string.tile_permission_toast, Toast.LENGTH_SHORT).show()
            // 打开主界面
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(intent)
            }
            return
        }
        // 切换服务状态
        val isRunning = FloatingWindowService.isRunning()
        if (isRunning) {
            // 停止滑动
            AutoSlideService.getInstance()?.stopSlide()
            // 停止悬浮窗服务
            val stopIntent = Intent(this, FloatingWindowService::class.java)
            stopService(stopIntent)
        } else {
            // 启动悬浮窗服务
            val startIntent = Intent(this, FloatingWindowService::class.java)
            startService(startIntent)
        }
        // 更新磁贴状态
        updateTileState(!isRunning)
    }

    companion object {
        /**
         * 请求更新磁贴状态
         * 
         * @param context 上下文
         */
        @JvmStatic
        fun requestUpdate(context: Context) {
            val componentName = ComponentName(context, AutoSlideTileService::class.java)
            requestListeningState(context, componentName)
        }
    }
}
