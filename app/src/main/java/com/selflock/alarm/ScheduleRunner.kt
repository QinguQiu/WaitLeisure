package com.selflock.alarm

import android.content.Context
import com.selflock.data.Repos
import com.selflock.lock.LockController
import com.selflock.lock.Pomodoro
import com.selflock.model.Schedule
import com.selflock.model.TaskKind
import com.selflock.util.DiagLog

/**
 * 「这条规则到底做什么」的唯一入口。
 *
 * 抽出来是因为有两个地方要执行规则：定时闹钟触发（[ScheduleAlarmReceiver]）
 * 和保存规则时的「此刻已在窗口内就立刻生效」（ScheduleScreens.persistSchedule）。
 * 两边各写一份迟早会走歪 —— 加「执行任务」的时候就正好踩在这个坎上。
 */
object ScheduleRunner {

    /**
     * 执行一条规则。
     *
     * 两种模式：
     *  - 执行任务（[Schedule.taskId] >= 0）：锁什么、锁多久由任务决定
     *  - 自带时长：按规则自己的 durationMinutes 锁
     *
     * 任务被删掉时**退回自带时长**，而不是整条规则失效 ——
     * 用户删了一个任务，不该连带几条规则一起静默失灵。
     *
     * @return 锁定结束时刻；返回 null 表示没有实际锁上（例如无障碍没开）
     */
    fun execute(context: Context, schedule: Schedule, now: Long = System.currentTimeMillis()): Long? {
        val groupId = schedule.whitelistGroupId
        val task = if (schedule.usesTask) Repos.tasks.byId(schedule.taskId) else null

        if (schedule.usesTask && task == null) {
            DiagLog.add("规则", "任务 ${schedule.taskId} 不存在，退回自带时长")
        }

        if (task != null) {
            return when (task.kind) {
                TaskKind.SIMPLE_LOCK -> {
                    val end = now + task.durationMinutes * 60_000L
                    DiagLog.add("规则", "执行任务「${task.name}」：锁到 " + fmt(end))
                    LockController.lock(context, end, schedule.id, groupId)
                    end
                }

                TaskKind.POMODORO -> {
                    DiagLog.add("规则", "执行任务「${task.name}」：番茄钟 " + task.pomodoroConfig)
                    Pomodoro.start(
                        context,
                        configOverride = task.pomodoroConfig,
                        whitelistGroupId = groupId
                    )
                    // 番茄钟的结束时刻由它自己的相位推进决定，这里给不出单一时刻
                    null
                }
            }
        }

        // 自带时长（也包含「任务被删了」的兜底）
        val end = now + schedule.durationMinutes * 60_000L
        LockController.lock(context, end, schedule.id, groupId)
        return end
    }

    private fun fmt(millis: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
}
