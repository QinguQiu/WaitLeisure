package com.selflock.util

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager

/**
 * 锁定期间「永远放行」的系统组件。
 *
 * 这些不是用户能挑的白名单应用，而是**锁机能不能正常用**的前提：
 *  - **输入法**：不放行的话，白名单应用里一打字就弹键盘、键盘一弹遮罩就盖回来，
 *    应用等于没法用。这是实测踩到的 bug。
 *  - SystemUI / permissioncontroller：下拉通知、系统权限弹窗等系统交互
 *  - 默认拨号盘：保证紧急电话能打出去
 *
 * 输入法和拨号盘都是**动态查询**的，用户换了输入法/拨号盘也不会被锁死。
 *
 * ## 为什么要单独抽成 object
 * 原来这套逻辑只长在 LockAccessibilityService 里（`alwaysAllowedPackages()`），
 * 无障碍服务的窗口事件分支确实查了它 —— 但 **LockForegroundService 的看门狗
 * 只用 `Repos.isWhitelisted()` 判断**，压根没查这个集合。于是：
 *   键盘弹出 → 事件包名是输入法 → 看门狗发现它「不在白名单」→ 把遮罩盖回去。
 * 抽到这里，两个入口共用同一份判定。
 *
 * 带 60 秒缓存：看门狗每秒跑一次，`TelecomManager.defaultDialerPackage` 是跨进程调用，
 * 没必要每秒问一遍。
 */
object CoreAllowed {

    private const val CACHE_MILLIS = 60_000L

    private var cached: Set<String> = emptySet()
    private var cachedAt: Long = 0L

    @Synchronized
    fun packages(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (cached.isNotEmpty() && now - cachedAt < CACHE_MILLIS) return cached

        val set = mutableSetOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller"
        )
        currentImePackage(context)?.let(set::add)
        defaultDialerPackage(context)?.let(set::add)

        cached = set
        cachedAt = now
        return set
    }

    fun contains(context: Context, packageName: String): Boolean =
        packageName in packages(context)

    /** 当前默认输入法。 */
    private fun currentImePackage(context: Context): String? = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
            ?.substringBefore('/')
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 默认拨号盘。 */
    private fun defaultDialerPackage(context: Context): String? = runCatching {
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    }.getOrNull()
}
