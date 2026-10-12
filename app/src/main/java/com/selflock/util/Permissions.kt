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

    fun openBatteryOptimizationSettings(context: Context) {
        // 直接跳「本应用详情页」，而不是系统的 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS 列表页。
        //
        // 原因：小米/红米（HyperOS）把系统的电池优化列表页改得和原生 AOSP 不一致，层级也深，
        // 跳过去用户找不到自己的应用。而 MIUI/HyperOS 恰好把「省电策略」「自启动」「后台弹出界面」
        // 全都做在了「应用详情页」里 —— 跳这里反而一步到位。
        //
        // 原生 Android 上这个页面同样一定能打开，用户在详情页里再进「电池」即可，不会走错。
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

    private fun Context.startSafely(intent: Intent) {
        runCatching {
            if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
}
