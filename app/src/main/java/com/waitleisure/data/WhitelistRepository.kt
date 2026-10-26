package com.waitleisure.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 白名单。内存里用 StateFlow 暴露，UI 可直接订阅；
 * 每次变更同步写盘，保证无障碍服务随时能读到最新值。
 */
class WhitelistRepository(private val prefs: AppPrefs) {

    private val _packages = MutableStateFlow(prefs.whitelist)
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    /** 无障碍服务在事件回调里用这个——同步取值，不走协程。 */
    fun current(): Set<String> = _packages.value

    fun contains(packageName: String): Boolean = _packages.value.contains(packageName)

    fun toggle(packageName: String) {
        val now = _packages.value
        update(if (now.contains(packageName)) now - packageName else now + packageName)
    }

    fun add(packageName: String) = update(_packages.value + packageName)

    fun remove(packageName: String) = update(_packages.value - packageName)

    fun replaceAll(packages: Set<String>) = update(packages)

    /**
     * 白名单的**有序**视图，给锁机页的网格用。
     *
     * 规则：
     *   1. 先按用户排过的顺序出（存在 prefs 里，换行分隔）
     *   2. 新加进来、还没排过的包名，按字母序追加在末尾
     *   3. 已经不在白名单里的包名，自动跳过
     *
     * 这样拖过之后的顺序不会因为增删而乱掉。
     */
    fun ordered(): List<String> {
        val all = _packages.value
        val saved = prefs.whitelistDisplayOrder
            .split("\\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in all }
        val rest = all.filterNot { it in saved }.sorted()
        return saved + rest
    }

    /**
     * 拖动排序：把第 [from] 个移动到 [to] 位置，并持久化。
     *
     * 只动"顺序"，不动"在不在"—— 白名单归属仍由 Set 决定。
     */
    /** 把任意白名单子集（全局 ∪ 本次分组）按保存过的顺序排好，没排过的追加在后面。 */
    fun sortBySavedOrder(packages: List<String>): List<String> {
        val saved = prefs.whitelistDisplayOrder
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in packages }
        val rest = packages.filterNot { it in saved }.sorted()
        return saved + rest
    }

    /** 拖动排序：按给定的完整列表顺序落盘（用于锁机页那种"全局 ∪ 分组"的合并列表）。 */
    fun moveInList(list: List<String>, from: Int, to: Int) {
        if (from !in list.indices || to !in list.indices || from == to) return
        val next = list.toMutableList()
        next.add(to, next.removeAt(from))
        prefs.whitelistDisplayOrder = next.joinToString("\n".toString())
    }

    fun move(from: Int, to: Int) {
        val list = ordered().toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        prefs.whitelistDisplayOrder = list.joinToString("\\n")
    }

    private fun update(value: Set<String>) {
        prefs.whitelist = value
        _packages.value = value
    }
}
