package com.selflock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.selflock.data.Repos
import com.selflock.model.Schedule
import com.selflock.model.matchesDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 定时引擎。
 *
 * 每条规则同时维护两个闹钟：
 *  - START：下一次该锁的时候触发，触发后会重新排下一天（实现「每日执行」）；
 *  - END  ：锁定期满时触发，负责收尾。
 *
 * 全部使用 RTC_WAKEUP + setExactAndAllowWhileIdle，保证在 Doze 休眠下也能准时唤醒。
 */
object AlarmScheduler {

    const val ACTION_LOCK_START = "com.selflock.action.LOCK_START"
    const val ACTION_LOCK_END = "com.selflock.action.LOCK_END"
    const val EXTRA_SCHEDULE_ID = "extra_schedule_id"

    /** 全量重排。开机、时区变更、应用升级、以及进入 App 时调用。 */
    fun rescheduleAll(context: Context) {
        Repos.ensure(context)
        Repos.schedules.current().forEach { scheduleNextStart(context, it) }

        // 顺带校准：如果此刻仍在锁定中，把结束闹钟也补上
        if (Repos.lockState.isLockedNow()) {
            scheduleEnd(context, Repos.lockState.lockedUntil(), Repos.lockState.activeScheduleId())
        }
    }

    /** 为一条规则安排「下一次触发」。规则被停用时撤掉开始闹钟。 */
    fun scheduleNextStart(context: Context, schedule: Schedule) {
        val am = alarmManager(context) ?: return
        if (!schedule.enabled) {
            // 只撤开始闹钟，**不要**顺手撤结束闹钟 ——
            // 单次规则触发后会被置为停用，那时本次锁定还在进行中，
            // 把结束闹钟一起撤了就再没人来解锁了。
            am.cancel(startPendingIntent(context, schedule.id))
            return
        }
        val triggerAt = nextTriggerMillis(schedule) ?: return
        setExact(am, triggerAt, startPendingIntent(context, schedule.id))
    }

    fun scheduleEnd(context: Context, untilMillis: Long, scheduleId: Long) {
        if (untilMillis <= 0L || untilMillis == Long.MAX_VALUE) return
        val am = alarmManager(context) ?: return
        setExact(am, untilMillis, endPendingIntent(context, scheduleId))
    }

    fun cancel(context: Context, scheduleId: Long) {
        val am = alarmManager(context) ?: return
        am.cancel(startPendingIntent(context, scheduleId))
        am.cancel(endPendingIntent(context, scheduleId))
    }

    /**
     * 计算下一次触发时刻。
     *
     * - 没选星期（**单次**）：只在下一个该时刻触发一次 ——
     *   今天还没到就今天，已经过了就明天。单次刻意不看节假日模式：
     *   用户说「就这个点锁一次」，那就锁一次。
     * - 选了星期：从今天起最多往后找 8 天，每一天都交给
     *   [Schedule.matchesDate] 判断（它会叠加节假日模式）。
     */
    fun nextTriggerMillis(schedule: Schedule, now: LocalDateTime = LocalDateTime.now()): Long? {
        val zone = ZoneId.systemDefault()
        val time = LocalTime.of(schedule.startMinutes / 60, schedule.startMinutes % 60)

        if (schedule.days.isEmpty()) {
            for (offset in 0..1L) {
                val candidate = LocalDateTime.of(now.toLocalDate().plusDays(offset), time)
                if (candidate.isAfter(now)) {
                    return candidate.atZone(zone).toInstant().toEpochMilli()
                }
            }
            return null
        }

        // 往后找 15 天而不是 8 天：节假日模式下可能出现「连续 9 天春节 + 调休」
        // 这种长空档，窗口太短会算不出下一次。
        for (offset in 0..14L) {
            val date = now.toLocalDate().plusDays(offset)
            if (!schedule.matchesDate(date)) continue
            val candidate = LocalDateTime.of(date, time)
            if (candidate.isAfter(now)) {
                return candidate.atZone(zone).toInstant().toEpochMilli()
            }
        }
        return null
    }

    // ---------- 内部实现 ----------

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    private fun setExact(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        runCatching {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                // 没拿到「闹钟和提醒」权限时降级：仍会触发，只是可能有几分钟偏差。
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }.onFailure {
            runCatching { am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
        }
    }

    private fun startPendingIntent(context: Context, scheduleId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(scheduleId, 0),
            Intent(context, ScheduleAlarmReceiver::class.java)
                .setAction(ACTION_LOCK_START)
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun endPendingIntent(context: Context, scheduleId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(scheduleId, 1),
            Intent(context, ScheduleAlarmReceiver::class.java)
                .setAction(ACTION_LOCK_END)
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 用奇偶区分 START/END，保证两个 PendingIntent 的 requestCode 永不相等，
     * 否则后设置的会覆盖前一个。
     */
    private fun requestCode(scheduleId: Long, slot: Int): Int =
        ((scheduleId and 0x3FFF_FFFFL) * 2L + slot).toInt()
}
