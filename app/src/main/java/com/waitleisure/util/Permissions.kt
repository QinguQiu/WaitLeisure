package com.waitleisure.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.waitleisure.lock.DeviceAdminReceiver
import com.waitleisure.lock.LockAccessibilityService

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

    /**
     * 无障碍服务是否**真的在运行**。
     *
     * 注意：**不能**用 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 来判断。
     * 那行字符串只说明「系统设置里列着这个服务」，跟「服务已被绑定、正在收事件」
     * 完全是两回事。实测（HyperOS + adb）：
     *
     *   往那个设置里写入服务名 → 设置里就列着它了，但系统根本没绑定；
     *   锁机完全不生效，而旧版这里却返回 ✅ —— 一个假阳性让整轮排查方向全错。
     *
     * AccessibilityManager.getEnabledAccessibilityServiceList 返回的是系统真正
     * 启用并绑定完成的服务列表，这才是可信信号。
     */
    fun isAccessibilityEnabled(context: Context): Boolean = runCatching {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        if (!am.isEnabled) return false
        val expected = ComponentName(context, LockAccessibilityService::class.java)
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val si = info.resolveInfo?.serviceInfo
                si != null &&
                    si.packageName == expected.packageName &&
                    si.name == expected.className
            }
    }.getOrDefault(false)

    /** 服务名写在系统设置里，但系统并没有真正绑定它 —— 也就是「看着开了，其实没开」。 */
    fun isAccessibilityListedButDead(context: Context): Boolean =
        isAccessibilityListed(context) && !isAccessibilityEnabled(context)

    /**
     * 能不能自己把无障碍重新绑上。
     *
     * `WRITE_SECURE_SETTINGS` 是 signature|privileged|**development** 级权限 ——
     * 普通 App 拿不到，但可以用 adb 一次性授予：
     *
     *     adb shell pm grant com.waitleisure android.permission.WRITE_SECURE_SETTINGS
     *
     * 授予之后就允许我们直接改系统设置里的无障碍列表，不用再让用户跑一趟设置页。
     */
    fun canRebindAccessibility(context: Context): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 把无障碍服务重新写回系统设置并启用；返回是否「尝试过且当前已生效」。
     *
     * 什么时候用：MIUI/HyperOS 上从最近任务划掉 App 会连进程带服务一起杀，
     * 无障碍绑定跟着消失，用户每次都要手动去设置里重开。
     * 有了 WRITE_SECURE_SETTINGS 就能在下次打开 App 时自动补上。
     *
     * **只在真的掉了的时候写** —— 否则会把用户自己配的其它无障碍服务覆盖掉。
     */
    fun rebindAccessibilityIfNeeded(context: Context): Boolean {
        if (isAccessibilityEnabled(context)) return true
        if (!canRebindAccessibility(context)) return false

        return runCatching {
            val self = ComponentName(context, LockAccessibilityService::class.java)
            val full = self.flattenToString()
            val short = self.flattenToShortString()

            // 读出现有列表，只**追加**自己，绝不覆盖别人 ——
            // 用户可能还开着别的无障碍服务（比如按键映射），一并冲掉就闯祸了。
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val existing = raw.split(':').filter { it.isNotBlank() }
            val alreadyThere = existing.any { it.equals(full, true) || it.equals(short, true) }

            if (!alreadyThere) {
                val merged = (existing + full).joinToString(":")
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    merged
                )
            }
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )

            // 绑定是异步的，这里立刻查多半还没生效；只记录「写过了」。
            true
        }.onFailure {
            DiagLog.add("权限", "自动重绑无障碍失败：" + it.javaClass.simpleName + " " + it.message)
        }.getOrDefault(false)
    }

    private fun isAccessibilityListed(context: Context): Boolean {
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

    /**
     * 「显示悬浮窗」是否已授权。
     *
     * 这一项现在也算关键项：全屏遮罩是唯一能挡住「连点 Home 逃到桌面」的手段，
     * 没授权时锁机在极端连点下会漏。
     */
    fun canDrawOverlays(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    /**
     * 打开 MIUI 的权限编辑页。
     *
     * 「后台弹出界面」是 MIUI 自己加的权限，AOSP 没有对应接口，既查不到也开不了开关，
     * 只能把用户送到那个页面。位置在各 MIUI 版本里不一致，所以给两层兜底：
     * 先试 MIUI 权限编辑页，失败再落到本应用详情页。
     */
    fun openMiuiPermissionEditor(context: Context) {
        val editor = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            )
            putExtra("extra_pkgname", context.packageName)
        }
        if (context.startSafely(editor)) return
        context.startSafely(appDetailsIntent(context))
    }

    fun openOverlaySettings(context: Context) {
        context.startSafely(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName)
            )
        )
    }

    /**
     * 「使用情况访问」是否已授权。
     *
     * 它是无障碍之外的第二条「看前台应用」的信号源。无障碍服务有「绑定但没收事件」的坑，
     * 使用情况访问走的是系统记账，独立一条路。双保险，跟「远离手机」的做法一致。
     */
    @Suppress("DEPRECATION")
    fun canReadUsageStats(context: Context): Boolean = runCatching {
        val am = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            am.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            am.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun openUsageAccessSettings(context: Context) {
        context.startSafely(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

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
