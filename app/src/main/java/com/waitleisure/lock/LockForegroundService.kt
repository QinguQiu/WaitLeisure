package com.waitleisure.lock

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.waitleisure.data.Repos
import com.waitleisure.util.CoreAllowed
import com.waitleisure.util.DiagLog
import com.waitleisure.util.ForegroundDetector
import com.waitleisure.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 锁机状态宿主。
 *
 * 职责有三个：
 *  1. 显示常驻通知 —— 让用户在任何时候都能确认「现在是锁着的」；
 *  2. 自己盯着时间戳，到点自动收摊；
 *  3. 锁定刚生效时做一次「补刀」—— 如果用户此刻正开着非白名单应用，
 *     就在后台启动 Activity 被系统拦下的情况下再试一次。
 *
 * 第 2 点很关键：锁定的截止时间存在 SharedPreferences 里，
 * 只要轮询到这个时间已过，就主动停掉通知并退出，
 * 不依赖任何外部闹钟准时送达。
 */
class LockForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Repos.ensure(this)

        startForeground(Notifications.ID_LOCKING, Notifications.buildLockingNotification(this))
        watchExpiry()
        recheckForegroundOnce()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Repos.ensure(this)
        // 番茄钟的休息时段锁是开的，但服务必须继续活着才能推进到下一组专注
        if (!Repos.lockState.isLockedNow() && !Pomodoro.isActive()) {
            shutdown()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun watchExpiry() {
        scope.launch {
            var wasInGrace = false
            var escapeAttempts = 0
            // 之前是否真的处于锁机中。用来区分「锁机到期结束」和
            // 「服务被误启动时本来就没锁」—— 后者不该弹首页。
            var wasLocked = Repos.lockState.isLockedNow()
            while (isActive) {
                // 番茄钟：到点推进相位（专注 ↔ 休息）。放在最前面 ——
                // 休息时段锁是开的，靠这一句才能继续走到下一组专注。
                runCatching { Pomodoro.tick(this@LockForegroundService) }

                if (!Repos.lockState.isLockedNow() && !Pomodoro.isActive()) {
                    Repos.lockState.refresh()
                    shutdown()
                    // 锁机结束（时间到 / 番茄钟跑完）→ 把 App 首页拉到前台。
                    // 用户要的就是「锁完自动把首页弹出来」，省得自己再点开。
                    if (wasLocked) {
                        LockController.bringAppToFront(this@LockForegroundService, "锁机结束")
                    }
                    return@launch
                }
                if (Repos.lockState.isLockedNow()) wasLocked = true

                // 免打扰窗口刚结束的那一刻补一次检查。
                val inGrace = LockAccessibilityService.inLaunchGrace()
                if (wasInGrace && !inGrace && LockAccessibilityService.isForegroundBlocked()) {
                    LockOverlay.show(this@LockForegroundService, "免打扰结束")
                }
                wasInGrace = inGrace

                // 「逃逸」看门狗。
                //
                // 悬浮窗是常驻窗口，正常盖上一次就一直在；这里做的是兜底：
                // 万一它因为某种竞态被撤掉了，而前台又不是白名单应用，就重新盖上。
                //
                // 判据只认「确定前台是白名单应用」才不动手，其余一律盖上（宁可多盖，不可漏）：
                //   - 桌面 / 其他非白名单应用 → 盖上
                //   - 前台未知（无障碍假绑定时 lastPackage 一直是 null）→ 盖上，
                //     但先用使用情况访问兜底确认，避免误盖正在用的白名单应用
                if (!inGrace) {
                    // 用 lastRealPackage（最近一条「非自己」的事件），不用 lastSeenPackage ——
                    // 悬浮窗/锁机页 Activity 都是 com.waitleisure，遮罩每次盖上撤下都会产生
                    // 一条 pkg=com.waitleisure 的事件。用后者判前台会踩这个坑：
                    // 用户点开白名单应用 → 遮罩让开 → 最后一条事件恰好是「遮罩自己」
                    // → 判成「前台不是白名单」→ 遮罩被重新盖回去
                    // → 表现就是「允许打开的应用被挡住」。
                    var pkg = LockAccessibilityService.lastRealPackage()
                    if (pkg == null) {
                        // 无障碍「假绑定」时一条事件都收不到，用使用情况访问兜底
                        pkg = ForegroundDetector.currentForegroundPackage(this@LockForegroundService)
                    }

                    // 三种情况要分开处理，**不能把「核心放行」和「白名单应用」混为一谈**：
                    //
                    //   白名单应用  → 用户真的在用，遮罩必须让开
                    //   核心放行    → 输入法 / 下拉栏 / 系统弹窗只是「盖在上面」，
                    //                它不代表用户在用什么应用。既不该据此撤遮罩，
                    //                也不该据此补盖（那会把底下的白名单应用盖住）——
                    //                正确做法是**保持现状**。
                    //
                    // 踩过的坑：一开始把核心放行也当成「确定是白名单」，于是
                    //   下拉栏打开 → 最后事件是 com.android.systemui → 判成白名单
                    //   → 遮罩被撤掉后再也不补回来 → 锁机整个失效。
                    val isWhitelistedApp = pkg != null && Repos.isWhitelisted(pkg)
                    val isCoreOverlay = pkg != null &&
                        CoreAllowed.contains(this@LockForegroundService, pkg)

                    when {
                        isWhitelistedApp -> {
                            // 白名单应用在前台：悬浮窗让开，用户才能用它。
                            // 这是无障碍之外的第二条「让开」路径 —— 无障碍假绑定时也能生效。
                            if (LockOverlay.isShowing) {
                                LockOverlay.hide()
                            }
                            escapeAttempts = 0
                        }

                        isCoreOverlay -> {
                            // 系统窗口盖着，判不出用户在用什么应用 —— 保持现状，什么都不做
                            escapeAttempts = 0
                        }

                        !LockOverlay.isShowing -> {
                            escapeAttempts++
                            if (escapeAttempts <= 3 || escapeAttempts % 10 == 0) {
                                DiagLog.add(
                                    "看门狗",
                                    "悬浮窗不在，第 " + escapeAttempts + " 次盖上（pkg=" + pkg + "）"
                                )
                            }
                            LockOverlay.show(this@LockForegroundService, "看门狗")
                        }

                        else -> escapeAttempts = 0
                    }
                } else {
                    escapeAttempts = 0
                }

                delay(1000L)
            }
        }
    }

    /**
     * 锁定生效后约 0.8 秒复检一次。
     * 判据来自无障碍服务记录的「最近前台应用」，因此不会误伤白名单应用 ——
     * 白名单应用的包名不会满足 isForegroundBlocked()。
     */
    private fun recheckForegroundOnce() {
        scope.launch {
            delay(800L)
            if (Repos.lockState.isLockedNow() && LockAccessibilityService.isForegroundBlocked()) {
                LockOverlay.show(this@LockForegroundService, "启动复检")
            }
        }
    }

    private fun shutdown() {
        // 锁机结束，遮罩必须撤掉 —— 否则会一直盖在屏幕上
        LockOverlay.hide()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }
}
