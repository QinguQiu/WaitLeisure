package com.selflock.lock

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.selflock.data.Repos
import com.selflock.util.DiagLog
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
        if (!Repos.lockState.isLockedNow()) {
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
                if (!Repos.lockState.isLockedNow()) {
                    Repos.lockState.refresh()
                    shutdown()
                    return@launch
                }

                // 免打扰窗口刚结束的那一刻补一次检查。
                // 场景：用户在窗口内把锁机页丢到后台（比如按了 Home、或点了「拒绝」后
                // 停在别处），此后再没有窗口切换事件，无障碍服务不会主动发现。
                val inGrace = LockAccessibilityService.inLaunchGrace()
                if (wasInGrace && !inGrace && LockAccessibilityService.isForegroundBlocked()) {
                    LockScreenActivity.show(this@LockForegroundService)
                }
                wasInGrace = inGrace

                // ----------------------------------------------------------
                // 「逃逸」看门狗
                //
                // 为什么需要：连续快速按 Home 时系统会丢弃一部分窗口事件。
                // 真机实测（adb 无间隔连按 5 次）：只收到 2 次桌面事件、紧接着
                // 一条「回到自己」，服务据此认为已经拦住了；可剩下的按键仍在把
                // 桌面往上推，而且此后不再产生任何事件 —— 结果停在桌面上。
                //
                // 无障碍服务里那套 1.4 秒的补拉不够用：MIUI 对短时间内反复拉起
                // Activity 有类似限流的处理，实测连续 4 次全部被拒。所以改成锁机
                // 期间持续复查，每秒一次，直到真的回到锁机页。
                //
                // 判据刻意收得很窄，只在「确定用户没有正当理由停留在别处」时才动手：
                //   - 锁机页确实不在前台（Activity 的真实状态，不会被丢事件骗到）
                //   - 不在免打扰窗口内（否则会打断 ROM 的「启动应用」确认框）
                //   - 最近前台包名要么是自己（事件被丢弃后留下的旧值）、要么为空、
                //     要么是桌面 —— 绝不打断正在使用白名单应用的用户
                // ----------------------------------------------------------
                if (!LockScreenActivity.isResumed && !inGrace) {
                    val pkg = LockAccessibilityService.lastSeenPackage()
                    val lost = pkg == null ||
                        pkg == packageName ||
                        LockAccessibilityService.isHomePackage(pkg)
                    if (lost) {
                        escapeAttempts++
                        if (escapeAttempts <= 3 || escapeAttempts % 10 == 0) {
                            DiagLog.add(
                                "看门狗",
                                "锁机页不在前台，第 " + escapeAttempts + " 次换手拉起（lastPkg=" + pkg + "）"
                            )
                        }
                        // 遮罩是这里的主力：它不经过 Activity 启动，MIUI 拦不住。
                        LockOverlay.show(this@LockForegroundService, "看门狗")
                        // 同时继续尝试把锁机页拉回来（成功的话用户能操作白名单图标）
                        LockScreenActivity.show(this@LockForegroundService)
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
                LockScreenActivity.show(this@LockForegroundService)
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
