package com.selflock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.selflock.data.Repos
import com.selflock.model.Schedule
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

    /** 为一条规则安排「下一次触发」。规则被停用或没选任何星期时，直接取消。 */
    fun scheduleNextStart(context: Context, schedule: Schedule) {
        if (!schedule.enabled || schedule.days.isEmpty()) {
            cancel(context, schedule.id)
            return
        }
        val am = alarmManager(context) ?: return
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
     * 从今天起最多往后找 8 天 —— 足以覆盖「每周只选一天」这种配置。
     */
    fun nextTriggerMillis(schedule: Schedule, now: LocalDateTime = LocalDateTime.now()): Long? {
        if (schedule.days.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val time = LocalTime.of(schedule.startMinutes / 60, schedule.startMinutes % 60)

        for (offset in 0..7L) {
            val date = now.toLocalDate().plusDays(offset)
            if (date.dayOfWeek.value !in schedule.days) continue
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
