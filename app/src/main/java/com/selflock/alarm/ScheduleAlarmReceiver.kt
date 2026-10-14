package com.selflock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.selflock.data.Repos
import com.selflock.lock.LockController

/**
 * 定时触发点。
 *
 * 用 goAsync 之外的同步处理即可：这里只做几次 SharedPreferences 读写和一次闹钟重排，
 * 远低于 BroadcastReceiver 的 10 秒上限。
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Repos.ensure(context)

        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)

        when (intent.action) {
            AlarmScheduler.ACTION_LOCK_START -> onLockStart(context, scheduleId)
            AlarmScheduler.ACTION_LOCK_END -> onLockEnd(context)
        }
    }

    private fun onLockStart(context: Context, scheduleId: Long) {
        val schedule = Repos.schedules.byId(scheduleId)

        if (schedule != null && schedule.enabled) {
            val endAt = System.currentTimeMillis() + schedule.durationMinutes * 60_000L
            LockController.lock(context, endAt, schedule.id)
            // 本次锁定的结束闹钟必须排上
            AlarmScheduler.scheduleEnd(context, endAt, schedule.id)

            if (schedule.days.isEmpty()) {
                // 单次：触发一次就消耗掉 —— 置为停用，列表里随之变灰。
                // 用户把开关重新打开即可再次触发（会重新排下一个该时刻）。
                Repos.schedules.upsert(schedule.copy(enabled = false))
            } else {
                // 每日 / 每周：接着排下一次
                AlarmScheduler.scheduleNextStart(context, schedule)
            }
        }

        // 即便规则已被删除，也校准一次状态，避免出现「该锁却没锁」的缝隙
        LockController.sync(context)
    }

    private fun onLockEnd(context: Context) {
        // 可能还有另一条规则把时间延长了，这种情况下不要解锁
        if (Repos.lockState.isLockedNow()) return
        LockController.unlock(context)
    }
}
