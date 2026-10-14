package com.selflock.data

import android.content.Context

/**
 * 唯一的落盘入口。所有需要「进程被杀后依然生效」的状态都放这里。
 *
 * 特别注意：[lockedUntil] 必须持久化 —— 锁机期间用户可能强杀 App，
 * 但系统会重新拉起无障碍服务，那时必须还能读到「现在仍在锁定中」。
 */
class AppPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 白名单包名集合。 */
    var whitelist: Set<String>
        get() = sp.getStringSet(KEY_WHITELIST, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_WHITELIST, value).apply()

    /** 定时规则，JSON 数组字符串（用系统自带的 org.json，避免引入额外依赖）。 */
    var schedulesJson: String
        get() = sp.getString(KEY_SCHEDULES, "[]") ?: "[]"
        set(value) = sp.edit().putString(KEY_SCHEDULES, value).apply()

    /** PIN 的 SHA-256。为 null 表示还没设置过 PIN。 */
    var pinHash: String?
        get() = sp.getString(KEY_PIN_HASH, null)
        set(value) = sp.edit().putString(KEY_PIN_HASH, value).apply()

    /**
     * 锁定截止时间（epoch millis）。
     * - 0            = 未锁定
     * - Long.MAX_VALUE = 无限期锁定（只能靠 PIN 解除）
     * - 其它         = 到点自动解除
     */
    var lockedUntil: Long
        get() = sp.getLong(KEY_LOCKED_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_LOCKED_UNTIL, value).apply()

    /** 触发当前锁定的规则 id，-1 表示手动锁定。 */
    var activeScheduleId: Long
        get() = sp.getLong(KEY_ACTIVE_SCHEDULE, -1L)
        set(value) = sp.edit().putLong(KEY_ACTIVE_SCHEDULE, value).apply()

    /** 是否允许锁机时通过 PIN 解锁。false = 严格锁，锁机页不显示解锁按钮，只能等时间到。 */
    var allowPinUnlock: Boolean
        get() = sp.getBoolean(KEY_ALLOW_PIN_UNLOCK, true)
        set(value) = sp.edit().putBoolean(KEY_ALLOW_PIN_UNLOCK, value).apply()

    /** 立即锁机的常用时长（分钟，逗号分隔）。 */
    var quickDurations: String
        get() = sp.getString(KEY_QUICK_DURATIONS, DEFAULT_QUICK_DURATIONS) ?: DEFAULT_QUICK_DURATIONS
        set(value) = sp.edit().putString(KEY_QUICK_DURATIONS, value).apply()

    /** 锁机页壁纸颜色（ARGB int）。 */
    var wallpaperColor: Int
        get() = sp.getInt(KEY_WALLPAPER_COLOR, DEFAULT_WALLPAPER_COLOR)
        set(value) = sp.edit().putInt(KEY_WALLPAPER_COLOR, value).apply()

    /** 锁机页壁纸图片路径；null 表示用纯色。 */
    var wallpaperImagePath: String?
        get() = sp.getString(KEY_WALLPAPER_IMAGE, null)
        set(value) = sp.edit().putString(KEY_WALLPAPER_IMAGE, value).apply()

    /** 白名单分组，JSON 数组字符串（用系统自带的 org.json）。 */
    var whitelistGroupsJson: String
        get() = sp.getString(KEY_GROUPS, "[]") ?: "[]"
        set(value) = sp.edit().putString(KEY_GROUPS, value).apply()

    /** 番茄钟配置：共几组,每组分钟,小休息,大休息,几组后大休息。 */
    var pomodoroConfig: String
        get() = sp.getString(KEY_POMODORO_CONFIG, DEFAULT_POMODORO) ?: DEFAULT_POMODORO
        set(value) = sp.edit().putString(KEY_POMODORO_CONFIG, value).apply()

    /**
     * 番茄钟运行状态。空串 = 未运行；否则为 "组序号,相位,相位结束时刻"，
     * 例如 "2,focus,1791900000000"。相位取 focus / break。
     */
    var pomodoroState: String
        get() = sp.getString(KEY_POMODORO_STATE, "") ?: ""
        set(value) = sp.edit().putString(KEY_POMODORO_STATE, value).apply()

    /**
     * 白名单选择区是否用「大图标网格」显示。
     * 存盘是为了切 tab 回来不重置 —— NavHost 是 when 切换，离开就 dispose。
     */
    var whitelistGrid: Boolean
        get() = sp.getBoolean(KEY_WHITELIST_GRID, false)
        set(value) = sp.edit().putBoolean(KEY_WHITELIST_GRID, value).apply()

    private companion object {
        const val NAME = "selflock_prefs"
        const val KEY_WHITELIST = "whitelist"
        const val KEY_SCHEDULES = "schedules"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val KEY_ACTIVE_SCHEDULE = "active_schedule"
        const val KEY_ALLOW_PIN_UNLOCK = "allow_pin_unlock"
        const val KEY_QUICK_DURATIONS = "quick_durations"
        const val KEY_WALLPAPER_COLOR = "wallpaper_color"
        const val KEY_WALLPAPER_IMAGE = "wallpaper_image"
        const val KEY_GROUPS = "whitelist_groups"
        const val KEY_POMODORO_CONFIG = "pomodoro_config"
        const val KEY_POMODORO_STATE = "pomodoro_state"
        const val KEY_WHITELIST_GRID = "whitelist_grid"
        const val DEFAULT_QUICK_DURATIONS = "5,15,30,60,120,480"
        const val DEFAULT_WALLPAPER_COLOR = 0xFF000000.toInt()
        const val DEFAULT_POMODORO = "4,25,5,15,2"
    }
}
