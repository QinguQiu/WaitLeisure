package com.selflock.model

import java.time.LocalDate

/** 安装在设备上、且带桌面启动入口的应用。L1 只让用户从这些应用里挑白名单。 */
data class AppInfo(
    val packageName: String,
    val label: String
)

/**
 * 节假日处理方式。作用在「选中的星期几」之上。
 */
enum class HolidayMode {
    /** 不看节假日，只按星期几。默认，保持和以前一致。 */
    IGNORE,

    /** 跳过法定节假日（含调休拼出来的休息日）；调休上班日照锁。 */
    SKIP_HOLIDAY,

    /** 只在法定节假日锁，忽略星期几的选择。 */
    ONLY_HOLIDAY
}

/**
 * 一条每日锁机规则。
 *
 * 用「起始时间 + 持续时长」而不是「起始 + 结束」，是因为跨天场景（23:00 锁到次日 07:00）
 * 用时长表达更不容易出错，也和主流锁机 App 的心智一致。
 *
 * @param startMinutes    当天 0 点起的分钟数，0..1439
 * @param durationMinutes 锁定时长，1..1440
 * @param days            生效的星期，取值 1..7 对应 java.time.DayOfWeek（1=周一，7=周日）。
 *                        **空集合 = 单次**：只在下一个该时刻锁一次，锁完自动置为停用。
 */
data class Schedule(
    val id: Long,
    val enabled: Boolean = true,
    val startMinutes: Int = 23 * 60,
    val durationMinutes: Int = 8 * 60,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val holidayMode: HolidayMode = HolidayMode.IGNORE
) {
    /** 当天 0 点起的结束分钟数（仅用于展示，跨天时小于 startMinutes）。 */
    val endMinutes: Int get() = (startMinutes + durationMinutes) % MINUTES_PER_DAY

    /** 是否跨过午夜。 */
    val crossesMidnight: Boolean get() = startMinutes + durationMinutes > MINUTES_PER_DAY

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/** 把「0 点起的分钟数」格式化为 HH:mm。 */
fun Int.toHhMm(): String = "%02d:%02d".format(this / 60, this % 60)

/**
 * 这条规则在某一天是否应该生效（不看 [Schedule.enabled]）。
 *
 * 星期几的选择和节假日处理叠加在一起，三种模式：
 *  - [HolidayMode.IGNORE]       只认星期几
 *  - [HolidayMode.SKIP_HOLIDAY] 法定放假日一律不锁；
 *                               调休上班日**照锁**（哪怕它落在周末，
 *                               因为它名义上是工作日 —— 这正是「调休」的意义）
 *  - [HolidayMode.ONLY_HOLIDAY] 只看是不是法定放假日，忽略星期几
 */
fun Schedule.matchesDate(date: LocalDate): Boolean {
    val inDays = date.dayOfWeek.value in days
    return when (holidayMode) {
        HolidayMode.IGNORE -> inDays

        HolidayMode.SKIP_HOLIDAY ->
            !HolidayCalendar.isHoliday(date) &&
                (inDays || HolidayCalendar.isMakeupWorkday(date))

        HolidayMode.ONLY_HOLIDAY -> HolidayCalendar.isHoliday(date)
    }
}

/** 规则描述里那句「生效于」，把节假日模式一并说清楚。 */
fun Schedule.describeHolidayMode(): String? = when (holidayMode) {
    HolidayMode.IGNORE -> null
    HolidayMode.SKIP_HOLIDAY -> "跳过法定节假日"
    HolidayMode.ONLY_HOLIDAY -> "仅法定节假日"
}

/** 星期标签，索引 = DayOfWeek.value - 1。 */
val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 把 day 集合渲染成「每天 / 工作日 / 周一、周三」这样的短描述。
 *
 * 空集合不是「未选择」而是**单次**：只在下一个该时刻锁一次，锁完自动停用。
 */
fun Set<Int>.describeDays(): String = when {
    size == 7 -> "每天"
    this == setOf(1, 2, 3, 4, 5) -> "工作日"
    this == setOf(6, 7) -> "周末"
    isEmpty() -> "单次"
    else -> sorted().joinToString("、") { "周${DAY_LABELS[it - 1]}" }
}

/** 分钟数渲染成「8 小时」「1 小时 30 分」。 */
fun Int.toDurationText(): String {
    val h = this / 60
    val m = this % 60
    return when {
        h > 0 && m > 0 -> "${h} 小时 ${m} 分"
        h > 0 -> "${h} 小时"
        else -> "${m} 分钟"
    }
}
