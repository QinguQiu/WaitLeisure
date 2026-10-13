package com.selflock.lock

import android.content.Context
import com.selflock.data.Repos
import com.selflock.util.DiagLog

/** 番茄钟配置：共几组、每组多久、小休息、大休息、几组之后给大休息。 */
data class PomodoroConfig(
    val rounds: Int,
    val focusMinutes: Int,
    val shortBreak: Int,
    val longBreak: Int,
    val roundsPerSet: Int
)

/**
 * 番茄钟。
 *
 * 专注时段锁机，休息时段解锁，按配置自动轮转。
 * 例：间隔 2、共 4 组、每组 25、小休 5、大休 15 →
 *   25 专注 → 5 小休 → 25 专注 → 15 大休 → 25 专注 → 5 小休 → 25 专注 → 结束
 *
 * 状态存在 prefs 里（形如 "2,focus,<结束时刻>"），由 [LockForegroundService] 每秒
 * tick 驱动，所以杀进程或重启后仍能接上。
 *
 * 注意：休息时段锁是解开的，但前台服务必须继续活着才能推进相位 ——
 * 所以 LockForegroundService 的退出条件要额外看 [isActive]。
 */
object Pomodoro {

    fun parse(s: String): PomodoroConfig {
        val p = s.split(',').mapNotNull { it.trim().toIntOrNull() }
        fun at(i: Int, def: Int) = p.getOrNull(i)?.takeIf { it > 0 } ?: def
        return PomodoroConfig(
            rounds = at(0, 4),
            focusMinutes = at(1, 25),
            shortBreak = at(2, 5),
            longBreak = at(3, 15),
            roundsPerSet = at(4, 2)
        )
    }

    fun describe(s: String): String {
        val c = parse(s)
        return "${c.rounds} 组 × ${c.focusMinutes} 分 · 小休 ${c.shortBreak} / 大休 ${c.longBreak} 分（每 ${c.roundsPerSet} 组）"
    }

    fun isActive(): Boolean = Repos.prefs.pomodoroState.isNotEmpty()

    /** 当前相位：(组序号, 相位, 相位结束时刻)；未运行返回 null。 */
    fun current(): Triple<Int, String, Long>? {
        val st = Repos.prefs.pomodoroState
        if (st.isEmpty()) return null
        val p = st.split(',')
        if (p.size != 3) return null
        val round = p[0].toIntOrNull() ?: return null
        val end = p[2].toLongOrNull() ?: return null
        return Triple(round, p[1], end)
    }

    /** 开始：进入第 1 组专注并锁机到该组结束。 */
    fun start(context: Context) {
        val c = parse(Repos.prefs.pomodoroConfig)
        val end = System.currentTimeMillis() + c.focusMinutes * 60_000L
        Repos.prefs.pomodoroState = "1,focus,$end"
        DiagLog.add("番茄", "开始：1/${c.rounds} 组专注 ${c.focusMinutes} 分")
        LockController.lock(context, end)
    }

    /** 手动停止（用户主动中断）。 */
    fun stop(context: Context) {
        Repos.prefs.pomodoroState = ""
        DiagLog.add("番茄", "已手动停止")
        LockController.unlock(context)
    }

    /**
     * 每秒由前台服务调用。到点就推进相位。
     * @return true 表示状态变了（调用方可以据此刷新通知）。
     */
    fun tick(context: Context): Boolean {
        val cur = current() ?: return false
        val (round, phase, end) = cur
        val now = System.currentTimeMillis()
        if (now < end) return false

        val c = parse(Repos.prefs.pomodoroConfig)

        if (phase == "focus") {
            if (round >= c.rounds) {
                Repos.prefs.pomodoroState = ""
                DiagLog.add("番茄", "全部 $round 组完成，收工")
                LockController.unlock(context)
                return true
            }
            val isLong = round % c.roundsPerSet == 0
            val mins = if (isLong) c.longBreak else c.shortBreak
            Repos.prefs.pomodoroState = "$round,break," + (now + mins * 60_000L)
            // 休息期间解锁。注意这里只清锁定状态、不停前台服务 ——
            // 服务靠 isActive() 续命，休息结束才有力气把下一组锁起来。
            Repos.lockState.unlock()
            DiagLog.add("番茄", "第 $round 组结束，${if (isLong) "大" else "小"}休息 $mins 分")
        } else {
            val nextRound = round + 1
            val nextEnd = now + c.focusMinutes * 60_000L
            Repos.prefs.pomodoroState = "$nextRound,focus,$nextEnd"
            DiagLog.add("番茄", "休息结束，第 $nextRound/${c.rounds} 组开始")
            LockController.lock(context, nextEnd)
        }
        return true
    }
}
