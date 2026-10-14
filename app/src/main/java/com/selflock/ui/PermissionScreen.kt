package com.selflock.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selflock.data.Repos
import com.selflock.lock.LockAccessibilityService
import com.selflock.util.DiagLog
import com.selflock.util.Permissions

/** 一次权限体检的快照。 */
data class PermissionSnapshot(
    val accessibility: Boolean,
    val exactAlarm: Boolean,
    val notification: Boolean,
    val battery: Boolean,
    val deviceAdmin: Boolean,
    val overlay: Boolean,
    val usageStats: Boolean
) {
    val okCount: Int
        get() = listOf(
            accessibility, exactAlarm, notification, battery, deviceAdmin, overlay, usageStats
        ).count { it }

    val allOk: Boolean get() = okCount == TOTAL

    companion object {
        const val TOTAL = 7

        fun of(context: Context) = PermissionSnapshot(
            accessibility = Permissions.isAccessibilityEnabled(context),
            exactAlarm = Permissions.canScheduleExactAlarms(context),
            notification = Permissions.areNotificationsEnabled(context),
            battery = Permissions.isIgnoringBatteryOptimizations(context),
            deviceAdmin = Permissions.isDeviceAdminActive(context),
            overlay = Permissions.canDrawOverlays(context),
            usageStats = Permissions.canReadUsageStats(context)
        )
    }
}

/**
 * 加固权限页。
 *
 * 从首页右上角进来。把这些「一次性配置好、平时不用管」的系统权限
 * 全部收在这里，首页只保留一个入口和状态徽标，避免首页越堆越长。
 *
 * 分成两组：
 *   · 必须给的 —— 缺任何一项锁机都会漏风
 *   · MIUI 专属 —— 系统自加权限，没有 AOSP 接口可以查询状态，只能引导用户去开关
 */
@Composable
fun PermissionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val perms = remember(refreshKey) { PermissionSnapshot.of(context) }
    val locked by Repos.lockState.locked.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部：返回 + 实时进度
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Spacer(Modifier.weight(1f))
            Text(
                text = "已就绪 ${perms.okCount} / ${PermissionSnapshot.TOTAL}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (perms.allOk) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }

        SectionCard(
            title = if (perms.allOk) "✅ 权限已加固" else "⚠️ 还有 ${PermissionSnapshot.TOTAL - perms.okCount} 项没配好",
            subtitle = "缺任何一项都会让锁机漏风"
        ) {
            Text(
                text = "这些权限是锁机能不能拦住应用的物理前提。\n" +
                    "建议一次配齐 —— 漏掉哪一项，症状都是「锁机看起来在跑，其实能被绕过去」，" +
                    "而且界面上不会报错，很难自查。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            CheckRow(
                ok = perms.accessibility,
                title = "无障碍服务",
                description = "拦截非白名单应用，锁机的地基。" +
                    "Android 14+ 还必须在系统里勾选「允许受限设置」",
                actionLabel = "去开启",
                onAction = { Permissions.openAccessibilitySettings(context) }
            )
            CheckRow(
                ok = perms.overlay,
                title = "显示悬浮窗",
                description = "全屏遮罩。没有它，连点几次 Home 就能逃到桌面 —— " +
                    "因为 MIUI 会拒绝被 Home 切到后台的锁机页再回到前台，而遮罩不经过 Activity 启动",
                actionLabel = "去授权",
                onAction = { Permissions.openOverlaySettings(context) }
            )
            CheckRow(
                ok = perms.usageStats,
                title = "使用情况访问",
                description = "前台应用的第二信号源。无障碍服务「绑定但没收事件」时用它兜底",
                actionLabel = "去授权",
                onAction = { Permissions.openUsageAccessSettings(context) }
            )
            CheckRow(
                ok = perms.exactAlarm,
                title = "闹钟和提醒",
                description = "Android 12+ 必需，否则定时锁机可能不准",
                actionLabel = "去开启",
                onAction = { Permissions.openExactAlarmSettings(context) }
            )
            CheckRow(
                ok = perms.battery,
                title = "省电策略 / 忽略电池优化",
                description = "先弹系统授权框；若未弹出则落到应用信息页，手动把「省电策略」设为「无限制」",
                actionLabel = "去设置",
                onAction = { Permissions.openBatteryOptimizationSettings(context) }
            )
            CheckRow(
                ok = perms.notification,
                title = "通知权限",
                description = "锁机期间显示常驻通知，前台服务才不会被系统回收",
                actionLabel = "去开启",
                onAction = { Permissions.openNotificationSettings(context) }
            )
            CheckRow(
                ok = perms.deviceAdmin,
                title = "设备管理员",
                description = "锁机期间阻止「一键卸载」",
                actionLabel = "去激活",
                onAction = { Permissions.requestDeviceAdmin(context) }
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { refreshKey++ }) { Text("重新检测") }
        }

        // MIUI 的「后台弹出界面」不在上面的体检里 —— 它是 MIUI 自加的权限，
        // 没有 AOSP 接口可以查询，只能引导用户去开关。
        SectionCard(
            title = "MIUI 专属：后台弹出界面",
            subtitle = "不在这张体检表里（MIUI 自加权限，查不到状态）"
        ) {
            Text(
                text = "它才是「允许应用从后台弹出界面」的正式开关。开了它，锁机页被 Home 推到后台后\n" +
                    "也能顺利回到前台，不再依赖遮罩兜底。\n\n" +
                    "如果连点几次 Home 还是能回到桌面，多半就是它没开。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { Permissions.openMiuiPermissionEditor(context) }) {
                Text("打开 MIUI 权限页")
            }
        }

        SectionCard(
            title = "诊断日志",
            subtitle = "服务" + (if (LockAccessibilityService.isConnected()) "已连接" else "未连接") +
                " · 窗口事件 " + LockAccessibilityService.receivedEventCount() + " 次" +
                " · 当前" + (if (locked) "已锁定" else "未锁定")
        ) {
            val lines = remember(refreshKey) { DiagLog.snapshot() }.asReversed()
            if (lines.isEmpty()) {
                Text(
                    text = "还没有记录。开启无障碍并锁机后，这里会显示每一个窗口切换事件。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = lines.joinToString("\n"),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
