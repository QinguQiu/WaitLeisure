package com.selflock.lock

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.selflock.data.Repos
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
            while (isActive) {
                if (!Repos.lockState.isLockedNow()) {
                    Repos.lockState.refresh()
                    shutdown()
                    return@launch
                }

                // 免打扰窗口刚结束的那一刻补一次检查。
                // 场景：用户在窗口内把锁机页丢到后台（比如按了 Home、或点了「拒绝」后
                // 停在别处），此后再没有窗口切换事件，无障碍服务不会主动发现。
                // 这里每秒轮询一次，正好用来把锁机页拉回来，不留空子。
                val inGrace = LockAccessibilityService.inLaunchGrace()
                if (wasInGrace && !inGrace && LockAccessibilityService.isForegroundBlocked()) {
                    LockScreenActivity.show(this@LockForegroundService)
                }
                wasInGrace = inGrace

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
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }
}
