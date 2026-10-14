package com.selflock.model

/**
 * 任务类型：预设好的两种锁法。
 *
 * 刻意只有两种 —— 对应首页那两个按钮（简单锁机 / 番茄钟）。
 * 加第三种之前先想清楚首页怎么摆，别让「任务」变成第二个设置页。
 */
enum class TaskKind {
    /** 简单锁机：锁一段时间。 */
    SIMPLE_LOCK,

    /** 番茄钟：按配置在专注/休息之间轮转。 */
    POMODORO
}

/**
 * 任务：一个**预设好的锁法**，不含任何时间设置。
 *
 * 为什么要把「锁多久」从规则里拆出来：常用的锁法就那么几种，
 * 每条规则都重配一遍既啰嗦又容易配歪。配一次、多条规则复用，
 * 以后改一处所有引用它的规则一起变。
 *
 * **时间属于规则，不属于任务** —— 所以任务里没有起始时间，
 * 也没有「几点到几点」。规则负责「什么时候执行」，任务负责「执行什么」。
 *
 * @param durationMinutes SIMPLE_LOCK 用：锁多久
 * @param pomodoroConfig  POMODORO 用：番茄钟配置，格式同 AppPrefs.pomodoroConfig
 */
data class Task(
    val id: Long,
    val name: String,
    val kind: TaskKind = TaskKind.SIMPLE_LOCK,
    val durationMinutes: Int = 60,
    val pomodoroConfig: String = "4,25,5,15,2"
) {

    /** 列表里显示的一行说明。 */
    fun describe(): String = when (kind) {
        TaskKind.SIMPLE_LOCK -> "简单锁机 · " + durationMinutes.toDurationText()
        TaskKind.POMODORO -> {
            val p = pomodoroConfig.split(',').mapNotNull { it.trim().toIntOrNull() }
            val rounds = p.getOrNull(0)?.takeIf { it > 0 } ?: 4
            val focus = p.getOrNull(1)?.takeIf { it > 0 } ?: 25
            "番茄钟 · $rounds 个循环 × $focus 分"
        }
    }
}
