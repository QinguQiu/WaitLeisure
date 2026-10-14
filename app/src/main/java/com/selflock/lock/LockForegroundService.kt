package com.selflock.lock

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.selflock.data.Repos
import com.selflock.util.CoreAllowed
import com.selflock.util.DiagLog
import com.selflock.util.ForegroundDetector
import com.selflock.util.Notifications
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
            while (isActive) {
                // 番茄钟：到点推进相位（专注 ↔ 休息）。放在最前面 ——
                // 休息时段锁是开的，靠这一句才能继续走到下一组专注。
                runCatching { Pomodoro.tick(this@LockForegroundService) }

                if (!Repos.lockState.isLockedNow() && !Pomodoro.isActive()) {
                    Repos.lockState.refresh()
                    shutdown()
                    return@launch
                }

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
                    // 悬浮窗/锁机页 Activity 都是 com.selflock，遮罩每次盖上撤下都会产生
                    // 一条 pkg=com.selflock 的事件。用后者判前台会踩这个坑：
                    // 用户点开白名单应用 → 遮罩让开 → 最后一条事件恰好是「遮罩自己」
                    // → 判成「前台不是白名单」→ 遮罩被重新盖回去
                    // → 表现就是「允许打开的应用被挡住」。
                    var pkg = LockAccessibilityService.lastRealPackage()
                    if (pkg == null) {
                        // 无障碍「假绑定」时一条事件都收不到，用使用情况访问兜底
                        pkg = ForegroundDetector.currentForegroundPackage(this@LockForegroundService)
                    }
                    // **核心放行也要算数**：输入法、SystemUI、拨号盘不属于用户白名单，
                    // 但它们是锁机能不能用的前提。之前这里只查 isWhitelisted，
                    // 于是白名单应用里一弹键盘，看门狗就把遮罩盖回来 —— 应用直接没法用。
                    val definitelyWhitelisted = pkg != null &&
                        (Repos.isWhitelisted(pkg) || CoreAllowed.contains(this@LockForegroundService, pkg))

                    if (definitelyWhitelisted) {
                        // 白名单应用在前台：悬浮窗让开，用户才能用它。
                        // 这是无障碍之外的第二条「让开」路径 —— 无障碍假绑定时也能生效。
                        if (LockOverlay.isShowing) {
                            LockOverlay.hide()
                        }
                        escapeAttempts = 0
                    } else if (!LockOverlay.isShowing) {
                        escapeAttempts++
                        if (escapeAttempts <= 3 || escapeAttempts % 10 == 0) {
                            DiagLog.add(
                                "看门狗",
                                "悬浮窗不在，第 " + escapeAttempts + " 次盖上（pkg=" + pkg + "）"
                            )
                        }
                        LockOverlay.show(this@LockForegroundService, "看门狗")
                    } else {
                        escapeAttempts = 0
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
