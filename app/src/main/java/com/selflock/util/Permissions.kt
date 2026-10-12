package com.selflock.util

import android.app.Activity
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.selflock.lock.DeviceAdminReceiver
import com.selflock.lock.LockAccessibilityService

/**
 * 权限体检 + 引导跳转。
 *
 * L1 一共需要 5 项授权，缺任何一项都会让锁机「看起来能用、实际漏风」：
 *  1. 无障碍服务   —— 拦截非白名单应用（核心）
 *  2. 精确闹钟     —— Android 12+ 定时锁机能准时触发
 *  3. 通知         —— 常驻通知可见（Android 13+ 需运行时授权）
 *  4. 电池优化白名单 —— 防止后台被系统冻结导致时灵时不灵
 *  5. 设备管理员   —— 防止一键卸载
 */
object Permissions {

    fun isAccessibilityEnabled(context: Context): Boolean {
        val component = ComponentName(context, LockAccessibilityService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return runCatching {
            dpm.isAdminActive(ComponentName(context, DeviceAdminReceiver::class.java))
        }.getOrDefault(false)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    // ---------- 引导跳转 ----------

    fun openAccessibilitySettings(context: Context) {
        context.startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openExactAlarmSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.fromParts("package", context.packageName, null))
        } else {
            appDetailsIntent(context)
        }
        context.startSafely(intent)
    }

    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetailsIntent(context)
        }
        context.startSafely(intent)
    }

    /**
     * 「省电策略」引导 —— 按「效果」而不是「页面」来排序，逐个尝试直到有一个能打开。
     *
     * 背景：MIUI/HyperOS 把「省电策略」做成了应用信息页里的一个条目，**没有独立的 Activity**，
     * 所以不存在「直达省电策略页」的官方入口。网上流传的 MIUI 私有深链
     * （miui.intent.action.APP_PERM_EDITOR / OP_AUTO_START）指向的是权限编辑页和自启动管理，
     * 里面也没有省电策略这一项。
     *
     * 于是这里改成三级降级：
     *   1. 系统的「忽略电池优化」授权框 —— 一次点击。MIUI 上通常等价于把省电策略设为「无限制」
     *   2. MIUI/HyperOS 私有页 APP_PERM_EDITOR —— 自启动、后台弹出界面集中在这一页
     *   3. 本应用详情页 —— 兜底，用户在这里手动点「省电策略」
     *
     * 关键在于：每一步都用 tryStart 真的去 startActivity，抛异常才试下一个。
     * 因此**在任何 ROM 上都不会跳错页面** —— 最差也是落到应用详情页，也就是现在的行为。
     * 这比「猜一个 intent 然后赌它能开」稳得多。
     */
    fun openBatteryOptimizationSettings(context: Context) {
        // ① 已经拿到豁免就不用再弹框了，直接往下走去处理 MIUI 那两个开关
        if (!isIgnoringBatteryOptimizations(context)) {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", context.packageName, null))
            if (context.startSafely(request)) return
        }

        // ② MIUI / HyperOS 私有深链：该应用的「权限编辑」页
        //    两个 extra 键名都写上 —— 不同 HyperOS 版本用的键名不一样，多写一个没坏处
        val miuiEditor = Intent("miui.intent.action.APP_PERM_EDITOR")
            .putExtra("extra_pkgname", context.packageName)
            .putExtra("extra_package_name", context.packageName)
        if (context.startSafely(miuiEditor)) return

        // ③ 兜底：本应用详情页（「省电策略」在这里）
        context.startSafely(appDetailsIntent(context))
    }

    fun requestDeviceAdmin(context: Context) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(context, DeviceAdminReceiver::class.java)
            )
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "激活后可在锁机期间阻止「一键卸载」，避免锁定被中途解除。"
            )
        context.startSafely(intent)
    }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))

    /**
     * 尝试启动一个界面。
     * @return true 表示启动成功；false 表示这个 Intent 在本机没有对应的 Activity，
     *         调用方应该继续尝试下一个候选（而不是就此失败）。
     *
     * 之所以不用 resolveActivity() 预先探测：Android 11+ 有软件包可见性限制，
     * 探测系统应用可能返回 null（假阴性）。而直接 startActivity 是不受可见性限制的，
     * 用 try/catch 反而更准。
     */
    private fun Context.startSafely(intent: Intent): Boolean = runCatching {
        if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }.isSuccess
}
