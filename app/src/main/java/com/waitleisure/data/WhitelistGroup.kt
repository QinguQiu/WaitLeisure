package com.waitleisure.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 白名单分组：**一套应用，不带任何时间限制**。
 *
 * 为什么把「生效时段」去掉了：早期版本给分组配了时间段，
 * 但它和定时规则的时间是两套互相打架的语义 —— 用户既要在分组里设时段、
 * 又要在规则里设时段，同一件事说两遍，还经常对不上。
 *
 * 现在时间只由**定时规则 / 任务**决定：规则选中哪个分组，
 * 锁机期间就额外放行哪一组应用。全局白名单永远生效。
 *
 * 旧数据里的 start/end/enabled 字段会被直接忽略（[fromJson] 不读它们），
 * 迁移是无损的 —— 只丢掉时间信息，应用集合原样保留。
 */
data class WhitelistGroup(
    val id: Long,
    val name: String,
    val packages: Set<String>
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("packages", JSONArray(packages.toList()))
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
                packages = pkgs
            )
        }
    }
}

class WhitelistGroupRepository(private val prefs: AppPrefs) {

    private val _groups = MutableStateFlow(read(prefs.whitelistGroupsJson))
    val groups: StateFlow<List<WhitelistGroup>> = _groups.asStateFlow()

    fun current(): List<WhitelistGroup> = _groups.value

    fun byId(id: Long): WhitelistGroup? = _groups.value.firstOrNull { it.id == id }

    /** 某个分组的应用集合；分组不存在就返回空集（而不是抛异常）。 */
    fun packagesOf(id: Long): Set<String> = byId(id)?.packages ?: emptySet()

    fun save(group: WhitelistGroup) {
        update(_groups.value.filter { it.id != group.id } + group)
    }

    fun remove(id: Long) = update(_groups.value.filter { it.id != id })

    /**
     * 拖动排序：把第 [from] 个分组移动到 [to] 位置。
     *
     * 顺序就是 JSON 数组顺序，不用加新字段 —— 老数据读出来还是原来的顺序。
     */
    fun move(from: Int, to: Int) {
        val list = _groups.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        update(list)
    }

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
