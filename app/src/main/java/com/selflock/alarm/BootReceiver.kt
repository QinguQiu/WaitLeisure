package com.selflock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.selflock.data.Repos
import com.selflock.lock.LockController

/**
 * 系统事件后重建状态。
 *
 * 闹钟是「一次性」的：设备重启后所有 AlarmManager 闹钟都会被清空，
 * 所以必须在开机、应用升级、系统时间/时区变更后重新排一遍。
 * 漏掉这个 Receiver 是这类 App 最常见的 bug —— 表现为「重启后就不锁了」。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Repos.ensure(context)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                AlarmScheduler.rescheduleAll(context)
                LockController.sync(context)
            }
        }
    }
}
