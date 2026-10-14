package com.selflock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 节假日逻辑的单元测试。
 *
 * 为什么要写：内置表只覆盖 2026 年，而跑测试这天（10 月中）2026 的节假日早就过完了 ——
 * 真机上根本没法把「跳过节假日」这个行为跑出来看。下一次能观察到要等到 2027 元旦，
 * 而 2027 还没有数据。所以这块逻辑只能靠单元测试锁住。
 */
class HolidayCalendarTest {

    private fun date(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    // ---------------------------------------------------------------- 表内容

    @Test
    fun `国庆 10月1日到7日 都是法定放假日`() {
        for (day in 1..7) {
            assertTrue("10/$day 应该是放假日", HolidayCalendar.isHoliday(date(2026, 10, day)))
        }
    }

    @Test
    fun `国庆调休的 9月20日 和 10月10日 是调休上班日`() {
        assertTrue(HolidayCalendar.isMakeupWorkday(date(2026, 9, 20)))
        assertTrue(HolidayCalendar.isMakeupWorkday(date(2026, 10, 10)))
        // 调休上班日不是放假日
        assertFalse(HolidayCalendar.isHoliday(date(2026, 10, 10)))
    }

    @Test
    fun `春节 2月15日到23日 九天都是放假日`() {
        for (day in 15..23) {
            assertTrue("2/$day 应该是放假日", HolidayCalendar.isHoliday(date(2026, 2, day)))
        }
        assertTrue(HolidayCalendar.isMakeupWorkday(date(2026, 2, 14)))
        assertTrue(HolidayCalendar.isMakeupWorkday(date(2026, 2, 28)))
    }

    @Test
    fun `普通工作日既不是放假日也不是调休上班日`() {
        val wed = date(2026, 10, 14)
        assertFalse(HolidayCalendar.isHoliday(wed))
        assertFalse(HolidayCalendar.isMakeupWorkday(wed))
    }

    @Test
    fun `没有数据的年份 hasData 为 false 且判定都返回 false`() {
        assertFalse(HolidayCalendar.hasData(2027))
        assertFalse(HolidayCalendar.isHoliday(date(2027, 1, 1)))
        assertFalse(HolidayCalendar.isMakeupWorkday(date(2027, 1, 1)))
    }

    @Test
    fun `覆盖年份包含 2026`() {
        assertTrue(HolidayCalendar.coveredYears.contains(2026))
        assertEquals(2026, HolidayCalendar.latestYear)
    }

    // ---------------------------------------------------------------- 与星期几叠加

    private val workdays = Schedule(id = 1, days = setOf(1, 2, 3, 4, 5))

    @Test
    fun `不看节假日时 选中的星期一照锁 哪怕它是国庆`() {
        val s = workdays.copy(holidayMode = HolidayMode.IGNORE)
        // 2026-10-05 是星期一，但属于国庆假期
        assertTrue(s.matchesDate(date(2026, 10, 5)))
    }

    @Test
    fun `跳过节假日时 国庆期间的星期一不锁`() {
        val s = workdays.copy(holidayMode = HolidayMode.SKIP_HOLIDAY)
        assertFalse("10/5 是国庆假期，不该锁", s.matchesDate(date(2026, 10, 5)))
    }

    @Test
    fun `跳过节假日时 调休上班的周六照锁`() {
        val s = workdays.copy(holidayMode = HolidayMode.SKIP_HOLIDAY)
        // 2026-10-10 是星期六，但被调休成工作日 —— 这正是「调休」的意义
        assertTrue("10/10 是调休上班日，应该锁", s.matchesDate(date(2026, 10, 10)))
    }

    @Test
    fun `跳过节假日时 平常的工作日不受影响`() {
        val s = workdays.copy(holidayMode = HolidayMode.SKIP_HOLIDAY)
        assertTrue(s.matchesDate(date(2026, 10, 14)))       // 周三
        assertFalse(s.matchesDate(date(2026, 10, 17)))      // 周六
    }

    @Test
    fun `只在节假日时 忽略星期几 只看是不是放假日`() {
        val s = workdays.copy(holidayMode = HolidayMode.ONLY_HOLIDAY)
        assertTrue("10/5 是放假日", s.matchesDate(date(2026, 10, 5)))
        assertFalse("10/14 不是放假日，哪怕它是周三", s.matchesDate(date(2026, 10, 14)))
        assertFalse("10/10 是调休上班日，不是放假日", s.matchesDate(date(2026, 10, 10)))
    }

    @Test
    fun `没有数据的年份 跳过节假日会退化成只看星期几`() {
        val s = workdays.copy(holidayMode = HolidayMode.SKIP_HOLIDAY)
        // 2027-01-01 是周五，表里没数据 → 当成普通工作日
        assertTrue(s.matchesDate(date(2027, 1, 1)))
    }

    // ---------------------------------------------------------------- 更新提醒

    @Test
    fun `数据没过期且没到公告季 不提醒`() {
        // 2026-10-14：表覆盖到 2026，公告季（11 月）还没到
        assertFalse(HolidayCalendar.needsUpdateReminder(date(2026, 10, 14)))
        assertNull(HolidayCalendar.updateReminderText(date(2026, 10, 14)))
    }

    @Test
    fun `公告季的边界正好卡在 11 月 1 日`() {
        assertFalse("10/31 还差一天", HolidayCalendar.needsUpdateReminder(date(2026, 10, 31)))
        assertTrue("11/1 到了", HolidayCalendar.needsUpdateReminder(date(2026, 11, 1)))
    }

    @Test
    fun `进入公告季后 提醒次年数据该更新了`() {
        val text = HolidayCalendar.updateReminderText(date(2026, 11, 1))
        assertNotNull(text)
        assertTrue("应该提到覆盖到哪一年：$text", text!!.contains("2026"))
        assertTrue("应该提到 11 月公布：$text", text.contains("11 月"))
    }

    @Test
    fun `跨年后数据已过期 换一套更重的文案`() {
        val text = HolidayCalendar.updateReminderText(date(2027, 3, 1))
        assertNotNull(text)
        assertTrue("过期文案要说清后果：$text", text!!.contains("退化成只看星期几"))
    }

    @Test
    fun `已过期之后每年都提醒`() {
        assertTrue(HolidayCalendar.needsUpdateReminder(date(2027, 5, 1)))
        assertTrue(HolidayCalendar.needsUpdateReminder(date(2028, 5, 1)))
    }
}
