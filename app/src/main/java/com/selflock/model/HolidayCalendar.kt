package com.selflock.model

import java.time.LocalDate

/**
 * 中国法定节假日表。
 *
 * ## 为什么必须内置
 *
 * 放假安排（含调休）由国务院**每年单独发文**公布，没有公式可以推算 ——
 * 唯一可靠的办法就是查表。数据直接来自国务院办公厅的正式通知。
 *
 * 实测过「读手机日历」这条路：本机 `CalendarContract` 里只有 2 个本地日历、
 * 64 条个人事件，**一条节假日都没有**。MIUI 日历里显示的「休/班」是它自己
 * 内置的数据，不走公开 Provider，第三方读不到。所以只能自己带表。
 *
 * ## 表里必须有两类日子
 *
 * - [Year.off]  放假：法定节假日 + 调休拼出来的休息日
 * - [Year.work] 调休上班：本来是周末、被改成工作日的那几天
 *
 * 只记「放假」的话，「跳过节假日」模式在调休那几周会把该上班的周末也放过。
 *
 * ## 覆盖范围与更新方式
 *
 * 只覆盖到发版时能拿到的最新公告。超出范围的年份，节假日相关模式会退化成
 * 「只看星期几」——[hasData] 供界面提示用。更新方式：国务院一般每年 11 月
 * 公布次年安排，届时应发一版把新年份加进 [TABLE]。
 */
object HolidayCalendar {

    private data class Year(val off: Set<Int>, val work: Set<Int>)

    /** month * 100 + day，和 LocalDate 比对时用它，比字符串省事。 */
    private fun key(date: LocalDate): Int = date.monthValue * 100 + date.dayOfMonth

    /**
     * 国务院办公厅关于 2026 年部分节假日安排的通知
     * （国办发明电〔2025〕7号，2025-11-04）。
     *
     * 元旦 1/1-1/3（1/4 上班）
     * 春节 2/15-2/23（2/14、2/28 上班）
     * 清明 4/4-4/6
     * 劳动节 5/1-5/5（5/9 上班）
     * 端午 6/19-6/21
     * 中秋 9/25-9/27
     * 国庆 10/1-10/7（9/20、10/10 上班）
     */
    private val TABLE: Map<Int, Year> = mapOf(
        2026 to Year(
            off = setOf(
                101, 102, 103,                                  // 元旦
                215, 216, 217, 218, 219, 220, 221, 222, 223,    // 春节
                404, 405, 406,                                  // 清明
                501, 502, 503, 504, 505,                        // 劳动节
                619, 620, 621,                                  // 端午
                925, 926, 927,                                  // 中秋
                1001, 1002, 1003, 1004, 1005, 1006, 1007        // 国庆
            ),
            work = setOf(
                104,            // 元旦调休
                214, 228,       // 春节调休
                509,            // 劳动节调休
                920, 1010       // 国庆调休
            )
        )
    )

    /** 有内置数据的年份（升序）。界面用它提示「数据只到哪一年」。 */
    val coveredYears: List<Int> get() = TABLE.keys.sorted()

    /** 最新覆盖到哪一年；表为空时返回 null。 */
    val latestYear: Int? get() = TABLE.keys.maxOrNull()

    /** 该年份有没有内置数据。没有的话节假日模式只能退化成只看星期几。 */
    fun hasData(year: Int): Boolean = TABLE.containsKey(year)

    /** 是否是法定放假日（含调休拼出来的休息日）。 */
    fun isHoliday(date: LocalDate): Boolean =
        TABLE[date.year]?.off?.contains(key(date)) ?: false

    /** 是否是「调休上班日」：本来是周末，被改成工作日。 */
    fun isMakeupWorkday(date: LocalDate): Boolean =
        TABLE[date.year]?.work?.contains(key(date)) ?: false
}
