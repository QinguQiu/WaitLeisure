package com.waitleisure.data

import com.waitleisure.model.HolidayMode
import com.waitleisure.model.Schedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时规则的增删改查。
 * 序列化手写在 toJson/fromJson 里 —— 规则结构很简单，不值得为它引入 Room 或 DataStore。
 */
class ScheduleRepository(private val prefs: AppPrefs) {

    private val _schedules = MutableStateFlow(load())
    val schedules: StateFlow<List<Schedule>> = _schedules.asStateFlow()

    fun current(): List<Schedule> = _schedules.value

    fun enabled(): List<Schedule> = _schedules.value.filter { it.enabled }

    fun byId(id: Long): Schedule? = _schedules.value.firstOrNull { it.id == id }

    /** 新建时用一个不与现有 id 冲突的 id。 */
    fun nextId(): Long = (_schedules.value.maxOfOrNull { it.id } ?: 0L) + 1L

    fun upsert(schedule: Schedule) {
        val list = _schedules.value.toMutableList()
        val index = list.indexOfFirst { it.id == schedule.id }
        if (index >= 0) list[index] = schedule else list.add(schedule)
        persist(list.sortedBy { it.startMinutes })
    }

    fun delete(id: Long) = persist(_schedules.value.filterNot { it.id == id })

    private fun persist(list: List<Schedule>) {
        prefs.schedulesJson = toJson(list)
        _schedules.value = list
    }

    private fun load(): List<Schedule> = runCatching {
        val array = JSONArray(prefs.schedulesJson)
        (0 until array.length()).map { i -> fromJson(array.getJSONObject(i)) }
    }.getOrElse { emptyList() }

    private fun toJson(list: List<Schedule>): String {
        val array = JSONArray()
        list.forEach { s ->
            array.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("enabled", s.enabled)
                    put("startMinutes", s.startMinutes)
                    put("durationMinutes", s.durationMinutes)
                    put("days", JSONArray(s.days.sorted()))
                    put("holidayMode", s.holidayMode.name)
                    put("taskId", s.taskId)
                    put("whitelistGroupId", s.whitelistGroupId)
                }
            )
        }
        return array.toString()
    }

    private fun fromJson(json: JSONObject): Schedule {
        val daysArray = json.optJSONArray("days")
        val days = if (daysArray == null) {
            setOf(1, 2, 3, 4, 5, 6, 7)
        } else {
            (0 until daysArray.length()).map { daysArray.getInt(it) }.toSet()
        }
        return Schedule(
            id = json.getLong("id"),
            enabled = json.optBoolean("enabled", true),
            startMinutes = json.optInt("startMinutes", 23 * 60),
            durationMinutes = json.optInt("durationMinutes", 8 * 60),
            days = days,
            // 老数据没有这个字段，默认 IGNORE —— 保持和升级前完全一致的行为
            holidayMode = runCatching {
                HolidayMode.valueOf(json.optString("holidayMode", HolidayMode.IGNORE.name))
            }.getOrDefault(HolidayMode.IGNORE),
            // 老数据同样没有这两个字段 —— 默认「自带时长、只用全局白名单」，
            // 也就是升级前后行为完全一致
            taskId = json.optLong("taskId", Schedule.NO_TASK),
            whitelistGroupId = json.optLong("whitelistGroupId", Schedule.NO_GROUP)
        )
    }
}
