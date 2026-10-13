package com.selflock.lock

import android.content.Context
import android.content.Intent
import com.selflock.data.Repos

/**
 * 锁机动作的统一入口。
 *
 * 所有「开始锁 / 解除锁 / 校准状态」都必须走这里，避免状态、前台服务、
 * 锁机页三者不同步 —— 这是这类 App 最容易出 bug 的地方。
 */
object LockController {

    /** 开始锁定。untilMillis <= 0 表示无限期锁定（只能靠 PIN 解除）。 */
    fun lock(context: Context, untilMillis: Long, scheduleId: Long = -1L) {
        Repos.ensure(context)
        Repos.lockState.lockUntil(untilMillis, scheduleId)
        startService(context)
        // 悬浮窗优先：锁机界面就是它，秒显、常驻、不经过 Activity 启动，MIUI 拦不住。
        LockOverlay.show(context, "锁机")
        // LockScreenActivity 保留为第二层：只有它能「点亮屏幕 / 盖在系统锁屏上」，
        // 处理定时锁机在息屏状态触发时要把屏幕唤醒的场景。
        LockScreenActivity.show(context)
    }

    fun unlock(context: Context) {
        Repos.ensure(context)
        Repos.lockState.unlock()
        stopService(context)
    }

    /**
     * 状态校准：与时间戳对齐，并保证前台服务的存活状态正确。
     * App 回到前台、开机完成、定时触发后都应调用。
     */
    fun sync(context: Context) {
        Repos.ensure(context)
        Repos.lockState.refresh()
        if (Repos.lockState.isLockedNow()) startService(context) else stopService(context)
    }

    private fun startService(context: Context) {
        runCatching {
            context.startForegroundService(Intent(context, LockForegroundService::class.java))
        }
        // 失败通常是因为 Android 12+ 的后台前台服务启动限制；
        // 此时锁机依旧有效（无障碍服务仍在拦截），只是少了常驻通知。
    }

    private fun stopService(context: Context) {
        runCatching { context.stopService(Intent(context, LockForegroundService::class.java)) }
    }
}
