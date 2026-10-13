package com.selflock.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 白名单分组：一套应用 + 一个生效时间段。
 *
 * 例如「学习」分组（词典/笔记）在 19:00-22:00 生效，
 * 「娱乐」分组（视频/游戏）在 22:00-23:00 生效。
 * 生效时段内的分组，其应用会被并入「当前可用白名单」。
 *
 * 时间段用「一天里的第几分钟」表示（0-1439），跨午夜时 startMinute > endMinute。
 */
data class WhitelistGroup(
    val id: Long,
    val name: String,
    val packages: Set<String>,
    val startMinute: Int,
    val endMinute: Int,
    val enabled: Boolean = true
) {

    fun isActive(minuteOfDay: Int): Boolean {
        if (!enabled) return false
        return if (startMinute < endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            // 跨午夜：22:00 到次日 06:00
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("packages", JSONArray(packages.toList()))
        put("start", startMinute)
        put("end", endMinute)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): WhitelistGroup {
            val arr = o.optJSONArray("packages") ?: JSONArray()
            val pkgs = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) pkgs += s
            }
            return WhitelistGroup(
                id = o.optLong("id"),
                name = o.optString("name", "未命名分组"),
                packages = pkgs,
                startMinute = o.optInt("start", 0),
                endMinute = o.optInt("end", 0),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }
}

class WhitelistGroupRepository(private val prefs: AppPrefs) {

    private val _groups = MutableStateFlow(read(prefs.whitelistGroupsJson))
    val groups: StateFlow<List<WhitelistGroup>> = _groups.asStateFlow()

    fun current(): List<WhitelistGroup> = _groups.value

    /** 某分钟时刻生效的分组应用合集（不含全局白名单）。 */
    fun activePackages(minuteOfDay: Int): Set<String> =
        _groups.value
            .filter { it.isActive(minuteOfDay) }
            .flatMapTo(mutableSetOf()) { it.packages }

    fun save(group: WhitelistGroup) {
        val next = (_groups.value.filter { it.id != group.id } + group)
            .sortedBy { it.startMinute }
        update(next)
    }

    fun remove(id: Long) = update(_groups.value.filter { it.id != id })

    fun nextId(): Long = (_groups.value.maxOfOrNull { it.id } ?: 0L) + 1

    private fun update(next: List<WhitelistGroup>) {
        val json = JSONArray().apply { next.forEach { put(it.toJson()) } }.toString()
        prefs.whitelistGroupsJson = json
        _groups.value = next
    }

    private fun read(json: String): List<WhitelistGroup> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { WhitelistGroup.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
