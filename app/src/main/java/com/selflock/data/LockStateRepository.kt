package com.selflock.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「现在是否处于锁定中」的唯一真相来源。
 *
 * 设计要点：判定完全基于时间戳，不做额外的开关位。
 * 好处是即使 App 进程被杀、闹钟被系统延迟，只要重新读一次时间戳就能得到正确结论，
 * 不会出现「锁不上了」或「解锁后还锁着」的状态错乱。
 */
class LockStateRepository(private val prefs: AppPrefs) {

    private val _locked = MutableStateFlow(isLockedNow())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** 无期限锁定用的哨兵值。 */
    private val forever: Long get() = Long.MAX_VALUE

    /** 同步判定，供无障碍服务的事件回调直接调用。 */
    fun isLockedNow(): Boolean = prefs.lockedUntil > System.currentTimeMillis()

    /** 是否是无期限锁定（只能靠 PIN 解除）。 */
    fun isForever(): Boolean = prefs.lockedUntil == forever

    fun lockedUntil(): Long = prefs.lockedUntil

    fun activeScheduleId(): Long = prefs.activeScheduleId

    /** 距离自动解锁还剩多少毫秒；无期限锁定或未锁定时返回 -1。 */
    fun remainingMillis(): Long {
        val until = prefs.lockedUntil
        if (until == 0L || until == forever) return -1L
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * 开始锁定。
     * @param untilMillis 截止时间；传 0 或负数表示无限期锁定。
     * @param scheduleId  触发来源；手动锁定传 -1。
     *
     * 若当前已经处于锁定中，取二者中更晚的截止时间 —— 这样两条规则重叠时不会被提前解锁。
     */
    fun lockUntil(untilMillis: Long, scheduleId: Long = -1L) {
        val now = System.currentTimeMillis()
        val target = if (untilMillis <= 0L) forever else untilMillis
        val existing = prefs.lockedUntil
        val merged = if (existing > now) maxOf(existing, target) else target

        prefs.lockedUntil = merged
        prefs.activeScheduleId = scheduleId
        refresh()
    }

    fun unlock() {
        prefs.lockedUntil = 0L
        prefs.activeScheduleId = -1L
        refresh()
    }

    /** 外部（例如恢复前台时）主动重算一次。 */
    fun refresh() {
        _locked.value = isLockedNow()
    }
}
