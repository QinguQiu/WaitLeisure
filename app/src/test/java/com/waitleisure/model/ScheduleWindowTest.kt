package com.waitleisure.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「此刻是否正在生效窗口内」的单元测试。
 *
 * 对应实测 bug：14:00 才建一条「13:00 开始、持续 2 小时」的规则时，
 * nextTriggerMillis 只往后找开始时刻 → 直接排到明天 13:00，
 * 今天这段还在进行的窗口被整个跳过。
 */
class ScheduleWindowTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun endAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        at(year, month, day, hour, minute)

    private val everyDay = Schedule(id = 1, startMinutes = 13 * 60, durationMinutes = 120)

    // 2026-10-14 是星期三

    @Test
    fun `14点建一条13点开始持续2小时的规则 今天这段还在 要立刻生效`() {
        val end = everyDay.activeWindowEndMillis(at(2026, 10, 14, 14, 0), zone)
        assertEquals("应该锁到 15:00", endAt(2026, 10, 14, 15, 0), end)
    }

    @Test
    fun `窗口已经开始但还没到时 同样算在窗口内`() {
        // 13:00 整刚好是开始时刻，算在窗口内
        assertEquals(
            endAt(2026, 10, 14, 15, 0),
            everyDay.activeWindowEndMillis(at(2026, 10, 14, 13, 0), zone)
        )
        // 14:59 还在窗口里
        assertEquals(
            endAt(2026, 10, 14, 15, 0),
            everyDay.activeWindowEndMillis(at(2026, 10, 14, 14, 59), zone)
        )
    }

    @Test
    fun `窗口结束时刻 不再算在窗口内`() {
        assertNull(everyDay.activeWindowEndMillis(at(2026, 10, 14, 15, 0), zone))
    }

    @Test
    fun `还没到开始时刻 不算在窗口内`() {
        assertNull(everyDay.activeWindowEndMillis(at(2026, 10, 14, 12, 30), zone))
    }

    @Test
    fun `窗口早就结束了 不算在窗口内`() {
        assertNull(everyDay.activeWindowEndMillis(at(2026, 10, 14, 16, 0), zone))
    }

    @Test
    fun `跨午夜的长窗口 凌晨仍算在昨天那条规则里`() {
        // 23:00 开始、持续 8 小时 → 到次日 07:00
        val overnight = Schedule(id = 2, startMinutes = 23 * 60, durationMinutes = 8 * 60)
        // 次日凌晨 1 点：今天（10/15）23:00 还没到，但昨天（10/14）23:00 的窗口还在
        assertEquals(
            endAt(2026, 10, 15, 7, 0),
            overnight.activeWindowEndMillis(at(2026, 10, 15, 1, 0), zone)
        )
    }

    @Test
    fun `星期几不匹配的日子 即使时间对也不算`() {
        // 只在周一（1）生效；2026-10-14 是周三
        val mondayOnly = everyDay.copy(days = setOf(1))
        assertNull(mondayOnly.activeWindowEndMillis(at(2026, 10, 14, 14, 0), zone))
        // 2026-10-12 是周一
        assertEquals(
            endAt(2026, 10, 12, 15, 0),
            mondayOnly.activeWindowEndMillis(at(2026, 10, 12, 14, 0), zone)
        )
    }

    @Test
    fun `单次规则不挑日子 任何一天都算`() {
        val oneOff = everyDay.copy(days = emptySet())
        assertEquals(
            endAt(2026, 10, 14, 15, 0),
            oneOff.activeWindowEndMillis(at(2026, 10, 14, 14, 0), zone)
        )
    }

    @Test
    fun `跳过节假日时 国庆假期里不算在窗口内`() {
        // 2026-10-05 是星期一，但属于国庆假期
        val workdays = Schedule(
            id = 3,
            startMinutes = 13 * 60,
            durationMinutes = 120,
            days = setOf(1, 2, 3, 4, 5),
            holidayMode = HolidayMode.SKIP_HOLIDAY
        )
        assertNull(workdays.activeWindowEndMillis(at(2026, 10, 5, 14, 0), zone))
        // 假期结束后的正常周三仍然生效
        assertEquals(
            endAt(2026, 10, 14, 15, 0),
            workdays.activeWindowEndMillis(at(2026, 10, 14, 14, 0), zone)
        )
    }
}

// ---------------------------------------------------------------------------
// 用户实测场景（2026-10-21 反馈）
//
// 周一 10:28 建了一条「周一 / 周三、10:10 开始、持续 20 小时 50 分」的规则，
// 挂的是番茄钟任务，抱怨当天没生效。
//
// 先把「窗口判断」这一层钉死：10:10 + 1250 分 = 次日 07:00，
// 所以 10:28 时窗口必然还在 —— 如果下面的断言失败，说明问题在这一层。
// ---------------------------------------------------------------------------

class ScheduleUserCaseTest {

private fun monRule() = Schedule(
    id = 2,
    startMinutes = 10 * 60 + 10,
    durationMinutes = 20 * 60 + 50,
    days = setOf(1, 3)
)

/** 2026-10-19 是星期一。 */
@Test
fun `周一10点28分_周一10点10分开始持续20小时50分的规则_窗口仍在`() {
    val end = monRule().activeWindowEndMillis(at(2026, 10, 19, 10, 28), zone)
    assertEquals("应该锁到次日 07:00", endAt(2026, 10, 20, 7, 0), end)
}

/** 周二 10:00 不在窗口内（周一那条窗口已经在周二 07:00 结束）。 */
@Test
fun `周二10点不在周一那条规则的窗口内`() {
    val end = monRule().activeWindowEndMillis(at(2026, 10, 20, 10, 0), zone)
    assertNull("周二 10:00 已经超出周一那条窗口", end)
}

/** 周日 23:00 建的规则不匹配周一时，不该误判成进行中。 */
@Test
fun `不匹配的日子不算窗口内`() {
    val tuesdayOnly = Schedule(id = 3, startMinutes = 10 * 60 + 10, durationMinutes = 60, days = setOf(2))
    assertNull(tuesdayOnly.activeWindowEndMillis(at(2026, 10, 19, 10, 28), zone))
}
}
