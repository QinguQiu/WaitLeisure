package com.selflock.data

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

    private fun update(value: Set<String>) {
        prefs.whitelist = value
        _packages.value = value
    }
}
