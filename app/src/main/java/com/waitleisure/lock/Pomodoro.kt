package com.waitleisure.lock

import android.content.Context
import com.waitleisure.data.Repos
import com.waitleisure.util.DiagLog

/** 番茄钟配置：循环次数、专注时长、短休息、长休息、几个循环之后给一次长休息。 */
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
 * 例：循环 4 次、专注 25、短休 5、长休 15、每 2 个循环长休一次 →
 *   专注 25 → 短休 5 → 专注 25 → 长休 15 → 专注 25 → 短休 5 → 专注 25 → 结束
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

    /**
     * 一轮完整番茄钟跑完的总时长（分钟）：所有焦点 + 中间那些休息。
     *
     * 最后一次专注之后没有休息，所以休息次数 = rounds - 1；
     * 第 N 个循环之后，N 能被 roundsPerSet 整除时是长休，否则短休
     * （和 [tick] 里的判断一致）。
     *
     * 用途：定时规则挂番茄钟任务时，用它算「这条规则会锁多久」——
     * 否则只能退回规则自己的结束时间，两套逻辑同时生效。
     */
    fun totalMinutes(config: String): Int {
        val c = parse(config)
        var total = c.rounds * c.focusMinutes
        for (round in 1 until c.rounds) {
            total += if (round % c.roundsPerSet == 0) c.longBreak else c.shortBreak
        }
        return total
    }

    fun describe(s: String): String {
        val c = parse(s)
        return "${c.rounds} 个番茄 × ${c.focusMinutes} 分 · 短休 ${c.shortBreak} / 长休 ${c.longBreak} 分（每 ${c.roundsPerSet} 个番茄）"    }

    /**
     * 当前**运行**用的配置字符串：优先本次运行覆盖的那份，否则用用户设置里那份。
     * 「执行任务」靠这个机制，不会污染用户手配的参数。
     */
    fun activeConfig(): String =
        Repos.prefs.pomodoroRunConfig.ifEmpty { Repos.prefs.pomodoroConfig }

    fun isActive(): Boolean =
        Repos.prefs.pomodoroState.isNotEmpty() || Repos.prefs.pomodoroPausedRemaining > 0L

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

    /**
     * 开始：进入第 1 组专注并锁机到该组结束。
     *
     * @param configOverride  「执行任务」时用任务自带的番茄钟配置，不动用户设置页里那份 ——
     *                        否则跑一次定时就把用户手配的参数改掉了。
     * @param whitelistGroupId 本次要额外放行的分组；-1 = 只用全局白名单。
     */
    fun start(
        context: Context,
        configOverride: String? = null,
        whitelistGroupId: Long = -1L,
        /**
         * 本该开始的时刻（毫秒）。默认 null = 现在开始。
         *
         * 定时规则补执行时会传窗口起点 —— 20:55 设的 20:30 规则，
         * 番茄钟要按 20:30 起算，剩下 5 分钟就该结束第一个专注，
         * 而不是重新从满 25 分钟开始（用户实测反馈过）。
         */
        startAtMillis: Long? = null,
        taskName: String = "",
        /**
         * 严格模式覆盖：-1 跟全局 / 0 强制严格 / 1 强制允许。
         *
         * 定时规则挂番茄钟任务时由规则传下来 —— 否则「睡前」要严格、
         * 「午休」允许解锁这种配置对番茄钟就不生效了。
         */
        strictMode: Int = -1
    ) {
        // 任务自带的配置只作用于本次运行，不改用户设置里那份
        Repos.prefs.pomodoroRunConfig = configOverride ?: ""
        val c = parse(activeConfig())
        val now = System.currentTimeMillis()
        // 起算点：有窗口起点就用它，但不能比"现在"还晚（未来时间没意义）
        // 补算要「按起点算」，但不能算到第一个专注阶段已经整个过去。
        // 否则 state 一写下去 end 就是过去时刻，第一次 tick 立刻判到期，
        // 直接跳进休息 —— 表现为「定时开的番茄钟一上来就在休息」（用户实测）。
        // 这里把补算量限制在「专注时长 - 1 分钟」，保证至少还剩 1 分钟专注。
        val maxCatchUp = (c.focusMinutes * 60_000L) - 60_000L
        val base = startAtMillis?.let { raw ->
            val floor = now - maxCatchUp.coerceAtLeast(0L)
            raw.coerceAtMost(now).coerceAtLeast(floor)
        } ?: now
        val end = base + c.focusMinutes * 60_000L
        Repos.prefs.pomodoroState = "1,focus,$end"
        DiagLog.add(
            "番茄",
            "开始：1/${c.rounds} 个番茄专注 ${c.focusMinutes} 分" +
                if (base < now) "（按窗口起点补算，已过 ${(now - base) / 60_000} 分）" else ""
        )
        // 给锁机一个"至少还能锁一会儿"的终点：如果补算下来这个相位已经结束，
        // 交给 tick 去推进到下一个相位，这里不要用过去的时间点去 lock。
        LockController.lock(
            context,
            maxOf(end, now + 60_000L),
            scheduleId = -1L,
            whitelistGroupId = whitelistGroupId,
            taskName = taskName,
            strictMode = strictMode
        )
    }

    /** 现在是不是暂停中。 */
    fun isPaused(): Boolean = Repos.prefs.pomodoroPausedRemaining > 0L

    /**
     * 暂停：记住还剩多久，把状态串清掉（这样 tick 不会推进相位）。
     *
     * 只允许在**休息阶段**暂停 —— 专注阶段是锁着机的，
     * 能点到暂停说明已经解锁了，那本来就是休息。
     */
    fun pause(context: Context): Boolean {
        val cur = current() ?: return false
        if (isPaused()) return true
        val remaining = (cur.third - System.currentTimeMillis()).coerceAtLeast(0L)
        Repos.prefs.pomodoroPausedRemaining = remaining
        pausedRound = cur.first
        Repos.prefs.pomodoroState = ""
        DiagLog.add("番茄", "已暂停，剩余 ${remaining / 1000} 秒")
        BreakOverlay.hide()
        return true
    }

    /** 继续：按剩余时间重新写回状态串。 */
    fun resume(context: Context) {
        val remaining = Repos.prefs.pomodoroPausedRemaining
        if (remaining <= 0L) return
        val end = System.currentTimeMillis() + remaining
        // 暂停只发生在休息阶段，所以恢复成 break；round 取当时那轮
        Repos.prefs.pomodoroState = "${pausedRound},break,$end"
        Repos.prefs.pomodoroPausedRemaining = 0L
        DiagLog.add("番茄", "已继续，休息还剩 ${remaining / 1000} 秒")
        BreakOverlay.resetDismissed()
        BreakOverlay.show(context, end)
    }

    /** 暂停时的轮次（存进 prefs，恢复时用）。 */
    private var pausedRound: Int
        get() = Repos.prefs.pomodoroPausedRound
        set(value) { Repos.prefs.pomodoroPausedRound = value }

    /** 手动停止（用户主动中断）。 */
    /** 手动停止（用户主动中断）。 */
    fun stop(context: Context) {
        Repos.prefs.pomodoroState = ""
        Repos.prefs.pomodoroRunConfig = ""
        DiagLog.add("番茄", "已手动停止")
        BreakOverlay.reset(context)
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

        val c = parse(activeConfig())

        if (phase == "focus") {
            if (round >= c.rounds) {
                Repos.prefs.pomodoroState = ""
                Repos.prefs.pomodoroRunConfig = ""
                DiagLog.add("番茄", "全部 $round 个番茄完成，收工")
                BreakOverlay.reset(context)
                LockController.unlock(context)
                return true
            }
            val isLong = round % c.roundsPerSet == 0
            val mins = if (isLong) c.longBreak else c.shortBreak
            Repos.prefs.pomodoroState = "$round,break," + (now + mins * 60_000L)
            // 休息期间解锁。注意这里只清锁定状态、不停前台服务 ——
            // 服务靠 isActive() 续命，休息结束才有力气把下一个循环锁起来。
            Repos.lockState.unlock()
            DiagLog.add("番茄", "第 $round 个番茄结束，${if (isLong) "长" else "短"}休息 $mins 分")
            // 休息是解锁的，用户会切到别的应用 —— 右上角挂个小胶囊显示还剩多久
            BreakOverlay.resetDismissed()
            BreakOverlay.show(context, now + mins * 60_000L)
        } else {
            val nextRound = round + 1
            val nextEnd = now + c.focusMinutes * 60_000L
            Repos.prefs.pomodoroState = "$nextRound,focus,$nextEnd"
            DiagLog.add("番茄", "休息结束，第 $nextRound/${c.rounds} 个番茄开始")
            BreakOverlay.hide()
            // 休息期间只调了 lockState.unlock()（不是 LockController.unlock），
            // 所以 activeWhitelistGroupId 还留着本次的分组 —— 原样传回去即可。
            LockController.lock(
                context,
                nextEnd,
                scheduleId = -1L,
                whitelistGroupId = Repos.prefs.activeWhitelistGroupId,
                strictMode = Repos.prefs.activeAllowPinUnlockOverride
            )
        }
        return true
    }
}
