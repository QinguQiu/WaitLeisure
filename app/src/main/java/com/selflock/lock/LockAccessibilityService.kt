package com.selflock.lock

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import com.selflock.data.Repos

/**
 * 软锁的核心。
 *
 * 原理：监听 TYPE_WINDOW_STATE_CHANGED，拿到当前来到前台的应用包名。
 * 若处于锁定中、且该包名不在白名单里，立刻把用户带回 [LockScreenActivity]。
 *
 * 这也是「锁机页替代桌面」这个效果的全部秘密：
 * 桌面（Launcher）本身不在白名单里，所以按 Home 键会被立刻弹回锁机页；
 * 而锁机页上只列出白名单应用，于是用户就只能用那几个应用。
 *
 * 性能注意：事件回调可能每秒触发多次，所以「未锁定」的判断放在最前面，
 * 并且只有在锁定期间才去查默认输入法 / 拨号盘（那是两次跨进程调用）。
 *
 * 已知边界（非 root 无法根治，README 里有说明）：
 *  - 安全模式下无障碍服务不运行，可在此卸载；
 *  - 用户手动去设置里关闭无障碍服务即可解开。
 *  这两种情况靠 DeviceAdmin 只能拦「卸载」这一项。
 */
class LockAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Repos.ensure(this)
        selfPackage = packageName
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return

        // 记下最近的前台应用，供 LockForegroundService 做一次性复检
        lastPackage = pkg

        // 快速路径：绝大多数时间都没在锁机，这里直接返回，零额外开销
        if (!Repos.lockState.isLockedNow()) return

        // 用户刚在锁机页点了某个白名单应用 —— 这段时间不拦截。
        // 否则 ROM（尤其 MIUI/HyperOS）弹出的「启动应用 / 是否允许」确认框
        // 会被我们当成非白名单窗口秒弹回锁机页，用户根本来不及点「始终允许」。
        if (inLaunchGrace()) return

        val core = alwaysAllowedPackages()
        lastCoreAllowed = core

        if (shouldBlock(pkg, core)) {
            LockScreenActivity.show(this)
        }
    }

    override fun onInterrupt() = Unit

    private fun shouldBlock(pkg: String, core: Set<String>): Boolean {
        if (pkg == packageName) return false
        if (pkg in core) return false
        if (Repos.whitelist.contains(pkg)) return false
        return true
    }

    /**
     * 无论白名单怎么配都必须放行的包。
     * 少了输入法，用户连 PIN 都输不进去；少了系统 UI，通知和状态栏会出问题；
     * 少了拨号盘，就打不出紧急电话。
     */
    private fun alwaysAllowedPackages(): Set<String> {
        val set = mutableSetOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller"
        )
        currentImePackage()?.let(set::add)
        defaultDialerPackage()?.let(set::add)
        return set
    }

    /** 当前默认输入法。动态获取，这样用户换输入法也不会把键盘锁死。 */
    private fun currentImePackage(): String? = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 默认拨号盘：保证锁定期间仍然能打紧急电话。 */
    private fun defaultDialerPackage(): String? = runCatching {
        getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    }.getOrNull()

    companion object {
        /** 最近一次切到前台的应用包名。 */
        @Volatile
        private var lastPackage: String? = null

        /** 最近一次计算出的「必放行」包集合。 */
        @Volatile
        private var lastCoreAllowed: Set<String> = emptySet()

        @Volatile
        private var selfPackage: String = ""

        /**
         * 「免打扰」窗口的截止时间戳。
         *
         * 为什么需要它：用户从锁机页点某个白名单应用时，MIUI/HyperOS 会先弹一个
         * 「SelfLock 想要打开 XXX，是否允许？」的确认框。这个框属于系统/安全中心，
         * 不在白名单里，于是被本服务当成「非白名单应用」立刻弹回锁机页 ——
         * 表现就是确认框一闪而过，用户永远点不到「始终允许」。
         *
         * 这也正是这个修法刻意不判包名的原因：那个确认框到底属于哪个包，
         * 各家 ROM、各版本都不一样，猜错了就白改。用一个时间窗口绕过包名识别，最稳。
         *
         * 窗口结束后由 LockForegroundService 补一次前台检查，把可能的空子堵上。
         */
        @Volatile
        private var launchGraceUntil: Long = 0L

        fun componentName(context: Context): ComponentName =
            ComponentName(context, LockAccessibilityService::class.java)

        /** 当前是否处于「刚点了白名单应用」的免打扰窗口内。 */
        fun inLaunchGrace(): Boolean = System.currentTimeMillis() < launchGraceUntil

        /**
         * 开一段免打扰窗口。
         * @param millis 窗口时长。要够用户读完并点掉确认框，又不能太长留下空子。
         */
        fun startLaunchGrace(millis: Long) {
            launchGraceUntil = System.currentTimeMillis() + millis
        }

        /**
         * 最近一次的前台应用是否应当被拦。
         *
         * 给 [LockForegroundService] 用：锁定刚生效时主动复检一次，
         * 避免出现「用户正开着非白名单应用、锁机悄悄生效但没被弹回锁机页」的缝隙。
         */
        fun isForegroundBlocked(): Boolean {
            val pkg = lastPackage ?: return false
            if (!Repos.lockState.isLockedNow()) return false
            if (pkg == selfPackage) return false
            if (pkg in lastCoreAllowed) return false
            return !Repos.whitelist.contains(pkg)
        }
    }
}
