package com.selflock.data

import com.selflock.model.Schedule
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
            days = days
        )
    }
}
