package com.ltx


// 配置文件名
const val PREFS_NAME = "slide_settings"

// 配置键名
const val KEY_SPEED = "speed"
const val KEY_PAUSE_MODE = "pauseMode"
const val KEY_PAUSE_TIME = "pauseTime"
const val KEY_MIN_PAUSE_TIME = "minPauseTime"
const val KEY_MAX_PAUSE_TIME = "maxPauseTime"
const val KEY_FLOATING_TRANSPARENCY = "floatingTransparency"

// 默认值
const val DEFAULT_SPEED = 50
const val DEFAULT_PAUSE_TIME = 1
const val DEFAULT_MIN_PAUSE_TIME = 1
const val DEFAULT_MAX_PAUSE_TIME = 3
const val DEFAULT_FLOATING_TRANSPARENCY = 40

// 停顿模式
const val PAUSE_MODE_NONE = 0
const val PAUSE_MODE_FIXED = 1
const val PAUSE_MODE_RANDOM = 2

/* 滑动配置数据类 */
data class SlideConfig(
    val speed: Int = DEFAULT_SPEED,
    val pauseMode: Int = PAUSE_MODE_NONE,
    val pauseTime: Int = DEFAULT_PAUSE_TIME,
    val minPauseTime: Int = DEFAULT_MIN_PAUSE_TIME,
    val maxPauseTime: Int = DEFAULT_MAX_PAUSE_TIME
)

// 滑动方向
const val DIRECTION_UP = "up"
const val DIRECTION_DOWN = "down"
const val DIRECTION_LEFT = "left"
const val DIRECTION_RIGHT = "right"

// 自定义轨迹
const val KEY_CUSTOM_TRAJECTORY_UP = "customTrajectory_up"
const val KEY_CUSTOM_TRAJECTORY_DOWN = "customTrajectory_down"
const val KEY_CUSTOM_TRAJECTORY_LEFT = "customTrajectory_left"
const val KEY_CUSTOM_TRAJECTORY_RIGHT = "customTrajectory_right"

/**
 * 获取轨迹存储键名
 *
 * @param direction 方向字符串
 * @return 轨迹存储键名
 */
fun getTrajectoryKey(direction: String): String? = when (direction) {
    DIRECTION_UP -> KEY_CUSTOM_TRAJECTORY_UP
    DIRECTION_DOWN -> KEY_CUSTOM_TRAJECTORY_DOWN
    DIRECTION_LEFT -> KEY_CUSTOM_TRAJECTORY_LEFT
    DIRECTION_RIGHT -> KEY_CUSTOM_TRAJECTORY_RIGHT
    else -> null
}