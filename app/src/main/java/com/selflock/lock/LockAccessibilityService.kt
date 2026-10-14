package com.selflock.lock

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import com.selflock.data.Repos
import com.selflock.util.CoreAllowed
import com.selflock.util.DiagLog

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
        homePackages = resolveHomePackages()
        connected = true
        DiagLog.add(
            "服务",
            "已连接，桌面 = " + homePackages.joinToString("/").ifEmpty { "未识别" }
        )
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        DiagLog.add("服务", "已断开")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return

        eventCount++

        // 记下最近的前台应用，供 LockForegroundService 做一次性复检
        lastPackage = pkg
        // 单独记一条「非自己」的：遮罩自己产生的事件不能代表用户在用什么应用
        if (pkg != packageName) lastNonSelfPackage = pkg

        // 目标应用真的到前台了 —— 免打扰窗口立刻结束。
        endGraceIfTargetArrived(pkg)

        // 快速路径：绝大多数时间都没在锁机，这里直接返回，零额外开销。
        // 注意这里不写诊断日志 —— 不锁机时事件量很大，会把有用的记录冲掉。
        if (!Repos.lockState.isLockedNow()) {
            // 锁机结束后遮罩必须撤掉，否则会一直盖着屏幕
            LockOverlay.hide()
            return
        }

        // 用户刚在锁机页点了某个白名单应用 —— 这段时间不拦截，
        // 目的是放行 ROM 的「启动应用」确认框。
        //
        // 但**桌面永远不放行**。实测（诊断日志）显示：窗口期内放行桌面时，
        // 用户恰好按 Home 就会停在解锁的桌面上，之后不再有任何窗口事件，
        // 锁机看起来就整个失效了。
        if (homePackages.isEmpty()) homePackages = resolveHomePackages()

        if (inLaunchGrace()) {
            if (homePackages.isEmpty()) {
                // 认不出桌面是谁时，绝不能放行 —— 否则窗口期又会把桌面放过。
                // 宁可让 ROM 的确认框被拦掉：那时用户会明确反馈「应用打不开」，
                // 而放行桌面的症状是「偶尔能回桌面」，很难定位。
                DiagLog.add("窗口", "$pkg → 免打扰窗口内，但桌面未识别，仍按拦截处理")
            } else if (pkg !in homePackages && pkg != packageName) {
                // ROM 的「启动应用」确认框或目标应用本身在前台，遮罩要让开。
                // 但**自己**不能算：悬浮窗自己的窗口也会产生 com.selflock 事件，
                // 若据此撤遮罩，会形成「盖上→撤下→盖上」的闪烁 ——
                // 实测「确认框弹出时按 Home」就是这个循环，闪几次等窗口期过了才停。
                LockOverlay.hide()
                DiagLog.add("窗口", "$pkg → 免打扰窗口内，放行")
                return
            } else {
                DiagLog.add("窗口", "$pkg → 免打扰窗口内，但它是桌面，照拦")
            }
        }

        val core = alwaysAllowedPackages()
        lastCoreAllowed = core

        val reason = when {
            pkg == packageName -> "自身"
            pkg in core -> "核心放行"
            Repos.isWhitelisted(pkg) -> "白名单"
            else -> "拦截"
        }

        if (reason != "拦截") {
            // 什么时候可以撤遮罩，判据要很小心 —— 这里踩过一个坑：
            //
            // **遮罩窗口本身也是 com.selflock 的窗口**。它一盖上就产生一条
            // pkg=com.selflock 的窗口事件；如果只看包名就撤遮罩，就会变成死循环：
            //   看门狗盖上遮罩 → 事件说「回到自己」→ 撤下 → 看门狗发现锁机页不在前台 → 再盖上……
            // 真机实测就是这个循环，日志里每秒一次「已盖上/已撤下」，看门狗累计到 80 次。
            //
            // 所以撤遮罩只有两种情况：
            //   1. 前台是白名单应用 —— 用户本来就该用它；
            //   2. 前台是自己、**并且锁机页 Activity 真的 resumed** —— 这才说明真回到了锁机页。
            // 「核心放行」（systemui 等）刻意不撤：它们只是盖在上面，
            // 撤了反而会露出下面的桌面。
            //
            // 另：这里也不能因为「回到锁机页」就取消补拉 —— 事件会被系统丢掉，
            // 真正的判据同样是 isResumed，在补拉逻辑里检查。
            val whitelisted = Repos.isWhitelisted(pkg)

            // 「回到锁机页」要同时满足三条，缺一条就会出问题：
            //
            //   1. 包名是自己；
            //   2. 这个窗口事件**确实来自锁机页 Activity**（className 对得上）；
            //   3. 锁机页 Activity 真的 resumed。
            //
            // 第 2 条是补上的 —— 只靠第 3 条不够：isResumed 有延迟。
            // 实测的闪烁序列是：
            //   按 Home → 桌面事件 → 盖上遮罩 → 遮罩产生一条 pkg=com.selflock 的事件
            //   → 此刻锁机页的 isResumed 还没来得及变 false → 判定「回到锁机页」→ 撤下
            //   → 桌面露出来 → 看门狗再盖上 → 每秒循环，屏幕黑底一闪一闪。
            //
            // 遮罩窗口的 className 不是 Activity，第 2 条能把它排除掉。
            val fromLockScreenActivity =
                event.className?.toString() == LockScreenActivity::class.java.name
            val reallyBackOnLockScreen = pkg == packageName &&
                fromLockScreenActivity &&
                LockScreenActivity.isResumed
            if (whitelisted || reallyBackOnLockScreen) {
                LockOverlay.hide()
            }
            DiagLog.add("窗口", "$pkg → $reason")
            return
        }

        // 拦截：只盖悬浮窗，不再启动 Activity。
        // 悬浮窗是常驻窗口，盖上一次就一直在；而且它不经过 Activity 启动，
        // MIUI 的防劫持墙（拒绝被 Home 切到后台的 Activity 再回来）完全管不到它。
        LockOverlay.show(this, pkg)
        DiagLog.add("窗口", "$pkg → 拦截，盖悬浮窗")
        scheduleReassert()
    }

    // ------------------------------------------------------------------
    // 补拉：处理「按 Home 时和桌面切换抢跑」的竞态
    //
    // 按 Home 时系统是**异步**把桌面任务移到前台的，而我们的无障碍事件到得很早。
    // 此刻系统仍认为锁机页在前台，于是 startActivity 只把 intent 投递给它
    // （logcat 里 result code=3 = START_DELIVERED_TO_TOP），**不做任务切换**；
    // 紧接着桌面的切换完成，用户就停在桌面上了。
    // 成功时那条日志是 result code=2 = START_TASK_TO_FRONT。
    //
    // 单次按 Home 时这个竞态通常还能靠后续事件扳回来，连按两次几乎必输，
    // 表现就是「在锁机界面连点两次 Home 就回到桌面」。
    //
    // 所以拉完之后隔一小段时间复查：前台如果仍然是该拦的应用，就再拉一次。
    // 那时桌面切换早已完成，第二次 startActivity 会正常走 TASK_TO_FRONT。
    // ------------------------------------------------------------------
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reassertAttempts = 0

    private val reassertRunnable = object : Runnable {
        override fun run() {
            if (!Repos.lockState.isLockedNow()) {
                // 锁机结束后遮罩必须撤掉，否则会一直盖着屏幕
                LockOverlay.hide()
                return
            }

            // 判据是「悬浮窗还在不在」，而不是「最后一个事件是不是自己」。
            // 后者会被丢弃的事件骗到：连按 Home 时服务先看到几条第桌面事件，
            // 再看到一条「回到自己」就以为拦住了，可剩下的按键还在把桌面往上推。
            if (LockOverlay.isShowing) {
                cancelReassert()
                return
            }

            // 前台明确是别的包、且属于白名单/核心放行时，不该补拉。
            // lastPackage 可能是旧的（因此等于自己），那种情况要往下走继续补拉 ——
            // 但补拉前必须先用「最近非自己」的包名复核一次，
            // 否则会把用户正在使用的白名单应用重新盖住。
            val pkg = lastPackage
            if (pkg != null && pkg != packageName) {
                if (pkg in lastCoreAllowed) return
                if (Repos.isWhitelisted(pkg)) return
            } else {
                val real = lastNonSelfPackage
                if (real != null && (real in lastCoreAllowed || Repos.isWhitelisted(real))) return
            }

            if (reassertAttempts >= MAX_REASSERT_ATTEMPTS) {
                DiagLog.add("补拉", "连续 " + reassertAttempts + " 次仍未回锁机页，放弃（lastPkg=" + pkg + "）")
                return
            }
            reassertAttempts++
            DiagLog.add("补拉", "第 " + reassertAttempts + " 次：悬浮窗不在（lastPkg=" + pkg + "）")
            // 悬浮窗是窗口不是 Activity，重试盖上即可，MIUI 拦不住。
            LockOverlay.show(this@LockAccessibilityService, "补拉")
            mainHandler.postDelayed(this, REASSERT_DELAY_MILLIS)
        }
    }

    private fun scheduleReassert() {
        reassertAttempts = 0
        mainHandler.removeCallbacks(reassertRunnable)
        mainHandler.postDelayed(reassertRunnable, REASSERT_DELAY_MILLIS)
    }

    private fun cancelReassert() {
        reassertAttempts = 0
        mainHandler.removeCallbacks(reassertRunnable)
    }

    override fun onInterrupt() = Unit



    /**
     * 无论白名单怎么配都必须放行的包。
     * 少了输入法，用户连 PIN 都输不进去；少了系统 UI，通知和状态栏会出问题；
     * 少了拨号盘，就打不出紧急电话。
     */
    /**
     * 解析默认桌面的包名。
     *
     * 必须运行时解析而不是写死：MIUI 是 com.miui.home，别家 ROM 各不相同。
     * 依赖 AndroidManifest 里声明的 HOME intent 查询权限，否则 Android 11+ 会返回 null。
     *
     * 两条策略取并集，因为单独任何一条都可能漏：
     *   ① resolveActivity + MATCH_DEFAULT_ONLY —— 最常见写法，但它要求 HOME 的
     *      intent-filter 里带 CATEGORY_DEFAULT；有些 ROM 就是不写，于是返回 null。
     *   ② queryIntentActivities 不带 flag —— 不看 CATEGORY_DEFAULT，把 ① 漏掉的捞回来。
     *
     * 只用 ① 正是 v1.0.13 失效的原因：MIUI 上拿到空集合，而空集合会让
     * 「不在桌面包里」恒为真，等于免打扰窗口又变回「放行一切」。
     */
    private fun resolveHomePackages(): Set<String> {
        val found = mutableSetOf<String>()

        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { found += it }
        }

        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
                .forEach { info -> info.activityInfo?.packageName?.let { found += it } }
        }

        return found
    }

    private fun alwaysAllowedPackages(): Set<String> = CoreAllowed.packages(this)

    companion object {
        /** 最近一次切到前台的应用包名。 */
        @Volatile
        private var lastPackage: String? = null

        /**
         * 最近一次「不是自己」的前台包名。
         *
         * 为什么需要单独记：悬浮窗和锁机页 Activity 都是 com.selflock，
         * 遮罩每次盖上/撤下都会产生一条 pkg=com.selflock 的窗口事件。
         * 于是用户点开白名单应用、遮罩刚让开的那一刻，[lastPackage] 往往正是「自己」，
         * 拿它判前台就会把白名单应用误判成非白名单，把遮罩又盖回去 ——
         * 真机表现就是「允许打开的应用被挡住」。
         */
        @Volatile
        private var lastNonSelfPackage: String? = null

        /** 最近一次计算出的「必放行」包集合。 */
        @Volatile
        private var lastCoreAllowed: Set<String> = emptySet()

        @Volatile
        private var selfPackage: String = ""

        /** 服务是否真的被系统绑定了。设置里显示「已开启」不等于这个为 true。 */
        @Volatile
        private var connected: Boolean = false

        /** 收到的窗口事件总数。用来判断服务是不是根本没在收事件。 */
        @Volatile
        private var eventCount: Long = 0L

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

        /** 供界面显示。 */
        fun isConnected(): Boolean = connected

        /** 供逃逸看门狗判断用：最近一次无障碍事件里的前台包名。 */
        fun lastSeenPackage(): String? = lastPackage

        /**
         * 供逃逸看门狗判断用：最近一次**不是自己**的前台包名。
         * 判「用户到底在用什么应用」时必须用这个，不能用 [lastSeenPackage] ——
         * 后者可能是遮罩自己产生的事件。
         */
        fun lastRealPackage(): String? = lastNonSelfPackage

        /** pkg 是否是默认桌面。 */
        fun isHomePackage(pkg: String?): Boolean = pkg != null && pkg in homePackages

        /** 供界面显示。 */
        fun receivedEventCount(): Long = eventCount

        /** 本次免打扰窗口正在等待的目标应用。 */
        @Volatile
        private var graceTargetPackage: String? = null

        /** 默认桌面的包名集合。免打扰窗口期间也照拦不误。 */
        @Volatile
        private var homePackages: Set<String> = emptySet()

        /** 拉完锁机页后隔多久复查一次。太短会和桌面切换本身抢，太长用户会看到桌面闪一下。 */
        private const val REASSERT_DELAY_MILLIS = 350L

        /** 最多补拉几次，避免和 ROM 死磕。 */
        private const val MAX_REASSERT_ATTEMPTS = 4

        /** 当前是否处于「刚点了白名单应用」的免打扰窗口内。 */
        fun inLaunchGrace(): Boolean = System.currentTimeMillis() < launchGraceUntil

        /**
         * 开一段免打扰窗口。
         *
         * @param targetPackage 期望被打开的白名单应用。**它一到前台，窗口就立刻结束** ——
         *        这样窗口只在「等待 ROM 确认框」这段时间存在，不会蔓延到用户真正开始用应用之后。
         * @param millis 兜底时长。用户点了「拒绝」或 ROM 行为异常时，靠它收场。
         */
        fun startLaunchGrace(targetPackage: String, millis: Long) {
            graceTargetPackage = targetPackage
            launchGraceUntil = System.currentTimeMillis() + millis
        }

        /**
         * 目标应用已到前台 —— 立刻结束免打扰窗口。
         *
         * 没有这一步的后果（实测踩到）：窗口会一直挂到兜底时长结束，
         * 用户在窗口期内按 Home 回桌面时不会被拦，看起来就像锁机失效了。
         */
        private fun endGraceIfTargetArrived(pkg: String) {
            if (pkg.isNotEmpty() && pkg == graceTargetPackage) {
                graceTargetPackage = null
                launchGraceUntil = 0L
            }
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
            return !Repos.isWhitelisted(pkg)
        }
    }
}
