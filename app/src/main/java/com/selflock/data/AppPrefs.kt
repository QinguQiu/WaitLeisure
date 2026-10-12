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

    private companion object {
        const val NAME = "selflock_prefs"
        const val KEY_WHITELIST = "whitelist"
        const val KEY_SCHEDULES = "schedules"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val KEY_ACTIVE_SCHEDULE = "active_schedule"
    }
}
