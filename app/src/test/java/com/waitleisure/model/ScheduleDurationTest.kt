package com.waitleisure.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「起始时刻 + 结束时刻」↔ 时长 的互换。
 *
 * 界面按**结束时刻**编（「23:00 锁到 07:00」），模型里存的是 durationMinutes，
 * 这两个函数就是那层换算。改错一个方向的表现是「定时时间明明设对了却不触发」，
 * 而且很难从界面上看出来 —— 所以这里逐个档位钉住。
 */
class ScheduleDurationTest {

    private val day = Schedule.MINUTES_PER_DAY

    // ---------------------------------------------------------------- 同一天

    @Test
    fun `同一天内的起止`() {
        // 09:00 → 12:30 = 3 小时 30 分
        assertEquals(210, durationBetween(9 * 60, 12 * 60 + 30))
    }

    @Test
    fun `起始等于结束视为锁满一整天`() {
        // 0 分钟等于没锁，而且定时窗口会立刻失效；按常识理解成 24 小时
        assertEquals(day, durationBetween(8 * 60, 8 * 60))
    }

    @Test
    fun `相差一分钟`() {
        assertEquals(1, durationBetween(8 * 60, 8 * 60 + 1))
    }

    // ---------------------------------------------------------------- 跨天

    @Test
    fun `跨天_晚上锁到第二天早上`() {
        // 23:00 → 07:00 = 8 小时
        assertEquals(8 * 60, durationBetween(23 * 60, 7 * 60))
    }

    @Test
    fun `跨天_午夜前后`() {
        // 23:30 → 00:30 = 1 小时
        assertEquals(60, durationBetween(23 * 60 + 30, 30))
    }

    @Test
    fun `跨天_几乎一整天`() {
        // 00:01 → 00:00 = 23 小时 59 分
        assertEquals(day - 1, durationBetween(1, 0))
    }

    // ------------------------------------------------- 和 Schedule.endMinutes 对得上

    @Test
    fun `和 endMinutes 互为反函数`() {
        val starts = listOf(0, 1, 7 * 60, 12 * 60, 23 * 60, 23 * 60 + 59)
        val durations = listOf(1, 30, 60, 8 * 60, 12 * 60, day - 1, day)

        for (s in starts) {
            for (d in durations) {
                val end = (s + d) % day
                // 注意：d == day 时 end == s，反算回来得到 day —— 正好自洽
                assertEquals(
                    "start=$s duration=$d 反算不一致",
                    d, durationBetween(s, end)
                )
            }
        }
    }

    @Test
    fun `反算结果的结束时刻和 Schedule endMinutes 一致`() {
        val schedule = Schedule(id = 1, startMinutes = 23 * 60, durationMinutes = 8 * 60)

        val recomputed = durationBetween(schedule.startMinutes, schedule.endMinutes)
        assertEquals(
            "由 endMinutes 反算出来的时长必须还是原来的",
            schedule.durationMinutes, recomputed
        )
    }

    // ---------------------------------------------------------------- 边界

    @Test
    fun `任何起止组合都落在一天之内`() {
        for (s in listOf(0, 1, 359, 720, 1438, 1439)) {
            for (e in listOf(0, 1, 359, 720, 1438, 1439)) {
                val d = durationBetween(s, e)
                assertEquals("start=$s end=$e 超出一天", true, d in 1..day)
            }
        }
    }
}
