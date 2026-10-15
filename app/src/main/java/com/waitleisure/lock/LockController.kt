package com.waitleisure.lock

import android.content.Context
import android.content.Intent
import com.waitleisure.MainActivity
import com.waitleisure.data.Repos
import com.waitleisure.util.DiagLog
import com.waitleisure.util.Permissions

/**
 * 锁机动作的统一入口。
 *
 * 所有「开始锁 / 解除锁 / 校准状态」都必须走这里，避免状态、前台服务、
 * 锁机页三者不同步 —— 这是这类 App 最容易出 bug 的地方。
 */
object LockController {

    /**
     * 开始锁定。untilMillis <= 0 表示无限期锁定（只能靠 PIN 解除）。
     *
     * @param whitelistGroupId >= 0 时本次锁机额外放行该分组；-1 表示只用全局白名单。
     */
    fun lock(
        context: Context,
        untilMillis: Long,
        scheduleId: Long = -1L,
        whitelistGroupId: Long = -1L
    ) {
        Repos.ensure(context)

        // 无障碍服务没在跑时不做任何锁机动作。
        //
        // 锁机界面（悬浮窗 + 锁机 Activity）都是「盖上去就撤不掉」的：
        // 唯一能撤掉它们的就是无障碍服务的窗口事件。服务不在还盖上去，
        // 白名单应用会被一起挡住，手机直接没法用 —— 真机实测踩到过。
        //
        // 首页有醒目提示「无障碍服务未运行 —— 锁机当前不生效」，
        // 这里宁可锁不住，也不能把手机锁死。
        if (!Permissions.isAccessibilityEnabled(context)) {
            DiagLog.add("锁机", "跳过（无障碍未运行，锁了也撤不掉）")
            return
        }

        // 分组本身不带生效时段了，「这次用哪一组」只能在这里记下来。
        // 必须写在启动服务之前 —— 服务一起来就会开始判白名单。
        Repos.prefs.activeWhitelistGroupId = whitelistGroupId

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
        // 解锁就回到「只用全局白名单」。下次锁机时再由调用方重新指定分组。
        Repos.prefs.activeWhitelistGroupId = -1L
        stopService(context)
        // 手动解锁也算「锁机结束」—— 用户刚在锁机页输完密码，
        // 这时候把他送到 App 首页最自然。
        bringAppToFront(context, "手动解锁")
    }

    /**
     * 锁机结束后把 App 首页拉到前台。
     *
     * 用户要的行为：锁机一结束就把首页弹出来，省得自己再点开。
     *
     * 后台启动 Activity 在 Android 10+ 默认是拦的，但本应用持有一层
     * 常驻悬浮窗（TYPE_APPLICATION_OVERLAY）—— 有悬浮窗权限就属于放行名单，
     * 加上 MIUI 那边用户已经开了「后台弹出界面」，所以这里能起来。
     * 万一被拦，只记一条诊断日志，不影响解锁本身。
     *
     * 只带 NEW_TASK 和 SINGLE_TOP：首页已经在任务栈里的话直接复用它，
     * 不会叠出第二个 MainActivity。
     */
    fun bringAppToFront(context: Context, from: String) {
        runCatching {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
            DiagLog.add("锁机", "已把首页拉到前台（$from）")
        }.onFailure {
            DiagLog.add("锁机", "拉起首页失败（$from）：" + it.javaClass.simpleName + " " + it.message)
        }
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
