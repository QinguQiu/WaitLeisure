package com.selflock.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * 用「使用情况访问」反查当前前台应用。
 *
 * 无障碍服务有「系统说绑定了、实际没在收事件」的坑（真机 + 模拟器都复现过），
 * 这时 lastPackage 会一直是 null。使用情况访问走的是系统自己的记账，独立一条路，
 * 适合当第二信号 —— 这正是「远离手机」同时申请无障碍 + 使用情况访问的原因。
 *
 * 注意：queryEvents 有约 1 分钟的延迟，所以它适合做「兜底确认」，不适合做实时主信号。
 */
object ForegroundDetector {

    /** 最近 [windowMillis] 毫秒内，最后一个 MOVE_TO_FOREGROUND / ACTIVITY_RESUMED 的包名。 */
    @Suppress("DEPRECATION")
    fun currentForegroundPackage(context: Context, windowMillis: Long = 60_000L): String? =
        runCatching {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
            val end = System.currentTimeMillis()
            val start = end - windowMillis
            val events = usm.queryEvents(start, end)
            val e = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    // ACTIVITY_RESUMED 是 API 29+ 的常量，但它是编译期内联的 static int，
                    // 在低版本运行时引用不会抛异常，只会永远匹配不到而已。
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    UsageEvents.Event.ACTIVITY_RESUMED -> last = e.packageName
                }
            }
            last
        }.getOrNull()
}
