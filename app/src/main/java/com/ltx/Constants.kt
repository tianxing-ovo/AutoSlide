package com.ltx

import android.content.SharedPreferences
import androidx.core.content.edit


// 配置文件名
const val PREFS_NAME = "slide_settings"

// 配置键名
const val KEY_SPEED = "speed"
const val KEY_PAUSE_MODE = "pauseMode"
const val KEY_PAUSE_TIME = "pauseTime"
const val KEY_MIN_PAUSE_TIME = "minPauseTime"
const val KEY_MAX_PAUSE_TIME = "maxPauseTime"

// 默认值
const val DEFAULT_SPEED = 50
const val DEFAULT_PAUSE_TIME = 1
const val DEFAULT_MIN_PAUSE_TIME = 1
const val DEFAULT_MAX_PAUSE_TIME = 3

// 停顿模式
const val PAUSE_MODE_NONE = 0
const val PAUSE_MODE_FIXED = 1
const val PAUSE_MODE_RANDOM = 2

// 滑动方向
const val DIRECTION_UP = "up"
const val DIRECTION_DOWN = "down"
const val DIRECTION_LEFT = "left"
const val DIRECTION_RIGHT = "right"


/**
 * 获取停顿模式
 *
 * @return 停顿模式
 */
fun SharedPreferences.getPauseMode(): Int {
    var mode = getInt(KEY_PAUSE_MODE, -1)
    if (mode == -1) {
        val needPause = getBoolean("needPause", false)
        mode = if (needPause) PAUSE_MODE_FIXED else PAUSE_MODE_NONE
        edit { putInt(KEY_PAUSE_MODE, mode) }
    }
    return mode
}