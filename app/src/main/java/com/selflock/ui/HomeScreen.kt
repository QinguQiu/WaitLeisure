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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selflock.alarm.AlarmScheduler
import com.selflock.data.Repos
import com.selflock.lock.LockController
import com.selflock.lock.LockScreenActivity
import com.selflock.model.Schedule
import com.selflock.model.describeDays
import com.selflock.model.toDurationText
import com.selflock.model.toHhMm
import com.selflock.util.Permissions
import com.selflock.util.Pin
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 一次权限体检的快照。 */
data class PermissionSnapshot(
    val accessibility: Boolean,
    val exactAlarm: Boolean,
    val notification: Boolean,
    val battery: Boolean,
    val deviceAdmin: Boolean
) {
    val okCount: Int
        get() = listOf(accessibility, exactAlarm, notification, battery, deviceAdmin).count { it }

    val allOk: Boolean get() = okCount == 5

    companion object {
        fun of(context: Context) = PermissionSnapshot(
            accessibility = Permissions.isAccessibilityEnabled(context),
            exactAlarm = Permissions.canScheduleExactAlarms(context),
            notification = Permissions.areNotificationsEnabled(context),
            battery = Permissions.isIgnoringBatteryOptimizations(context),
            deviceAdmin = Permissions.isDeviceAdminActive(context)
        )
    }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val locked by Repos.lockState.locked.collectAsState()
    val whitelist by Repos.whitelist.packages.collectAsState()
    val schedules by Repos.schedules.schedules.collectAsState()

    // 每次进入首页都重新体检一次权限（用户可能刚在系统设置里授权完）。
    // 这里刻意不用 LifecycleEventEffect：它来自 lifecycle-runtime-compose，
    // 本工程不想为此多引一个依赖。改成 LaunchedEffect(Unit)，
    // 配合 SelfLockNavHost 的 when 切换 —— 每次回到首页都会重新进入组合，效果等价。
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        Repos.lockState.refresh()
    }

    val perms = remember(refreshKey) { PermissionSnapshot.of(context) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showLockConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusCard(
            locked = locked,
            scheduleCount = schedules.count { it.enabled },
            whitelistCount = whitelist.size,
            nextTrigger = nextTriggerText(schedules),
            onLockNow = {
                if (Repos.prefs.pinHash.isNullOrEmpty()) showLockConfirm = true
                else LockController.lock(context, 0L)
            },
            onOpenLockScreen = { LockScreenActivity.show(context) }
        )

        SectionCard(
            title = "权限体检",
            subtitle = "已就绪 ${perms.okCount} / 5 · 缺任何一项都会让锁机漏风"
        ) {
            CheckRow(
                ok = perms.accessibility,
                title = "无障碍服务",
                description = "拦截非白名单应用，锁机的地基",
                actionLabel = "去开启",
                onAction = { Permissions.openAccessibilitySettings(context) }
            )
            CheckRow(
                ok = perms.exactAlarm,
                title = "闹钟和提醒",
                description = "Android 12+ 必需，否则定时可能不准",
                actionLabel = "去开启",
                onAction = { Permissions.openExactAlarmSettings(context) }
            )
            CheckRow(
                ok = perms.notification,
                title = "通知权限",
                description = "锁机期间显示常驻通知",
                actionLabel = "去开启",
                onAction = { Permissions.openNotificationSettings(context) }
            )
            CheckRow(
                ok = perms.battery,
                title = "省电策略 / 忽略电池优化",
                description = "打开后把「省电策略」设为「无限制」。小米/红米还要顺手开「自启动」和「后台弹出界面」",
                actionLabel = "去设置",
                onAction = { Permissions.openBatteryOptimizationSettings(context) }
            )
            CheckRow(
                ok = perms.deviceAdmin,
                title = "设备管理员",
                description = "锁机期间阻止「一键卸载」",
                actionLabel = "去激活",
                onAction = { Permissions.requestDeviceAdmin(context) }
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { refreshKey++ }) {
                Text("重新检测")
            }
        }

        SectionCard(
            title = "解锁 PIN",
            subtitle = if (Repos.prefs.pinHash.isNullOrEmpty()) {
                "尚未设置。不设置也能用，但锁定期间可直接解除。"
            } else {
                "已设置。锁定期间输入 PIN 可提前解除。"
            }
        ) {
            OutlinedButton(onClick = { showPinDialog = true }) {
                Text(if (Repos.prefs.pinHash.isNullOrEmpty()) "设置 PIN" else "修改 PIN")
            }
        }

        SectionCard(
            title = "使用提示",
            subtitle = null
        ) {
            Text(
                text = "1. 先开启无障碍服务，否则锁机不会生效。\n" +
                    "2. 锁定期间按 Home 键会被弹回锁机页，这是预期行为。\n" +
                    "3. 输入法、系统界面、拨号盘永远放行，保证打得出紧急电话。\n" +
                    "4. 安全模式下无障碍服务不运行，可借此卸载 —— 非 root 无法根治。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showPinDialog) {
        SetPinDialog(
            onDismiss = { showPinDialog = false },
            onSave = { pin ->
                Repos.prefs.pinHash = Pin.hash(pin)
                showPinDialog = false
            }
        )
    }

    if (showLockConfirm) {
        AlertDialog(
            onDismissRequest = { showLockConfirm = false },
            title = { Text("还没设置 PIN") },
            text = { Text("现在锁定将无法用 PIN 解除，只能等到你手动去设置里关闭无障碍服务。确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showLockConfirm = false
                    LockController.lock(context, 0L)
                }) { Text("仍然锁定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLockConfirm = false
                    showPinDialog = true
                }) { Text("先去设置 PIN") }
            }
        )
    }
}

@Composable
private fun StatusCard(
    locked: Boolean,
    scheduleCount: Int,
    whitelistCount: Int,
    nextTrigger: String,
    onLockNow: () -> Unit,
    onOpenLockScreen: () -> Unit
) {
    SectionCard(
        title = if (locked) "🔒 当前已锁定" else "🔓 当前未锁定",
        subtitle = if (locked) "仅白名单内的 $whitelistCount 个应用可用" else nextTrigger
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("白名单 $whitelistCount 个", fontSize = 13.sp)
            Spacer(Modifier.width(16.dp))
            Text("定时规则 $scheduleCount 条", fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (locked) {
            Button(onClick = onOpenLockScreen, modifier = Modifier.fillMaxWidth()) {
                Text("回到锁机页")
            }
        } else {
            Button(onClick = onLockNow, modifier = Modifier.fillMaxWidth()) {
                Text("立即锁机")
            }
        }
    }
}

@Composable
private fun SetPinDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = Pin.isValid(pin) && pin == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置解锁 PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    singleLine = true,
                    label = { Text("PIN（至少 4 位数字）") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit).take(12) },
                    singleLine = true,
                    isError = confirm.isNotEmpty() && confirm != pin,
                    label = { Text("再输一次") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "请务必记住这个 PIN。忘了就只能去系统设置里关闭无障碍服务来解除锁定。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(pin) }) {
                Text(androidx.compose.ui.res.stringResource(com.selflock.R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(com.selflock.R.string.action_cancel))
            }
        }
    )
}

/** 首页显示「下一次什么时候锁」。 */
private fun nextTriggerText(schedules: List<Schedule>): String {
    val now = LocalDateTime.now()
    val next = schedules
        .filter { it.enabled && it.days.isNotEmpty() }
        .mapNotNull { s ->
            AlarmScheduler.nextTriggerMillis(s, now)?.let { millis -> millis to s }
        }
        .minByOrNull { it.first }

    if (next == null) return "还没有启用的定时规则"

    val at = Instant.ofEpochMilli(next.first).atZone(ZoneId.systemDefault())
    val time = next.second
    return "下次：%d月%d日 %s · 持续 %s（%s）".format(
        at.monthValue,
        at.dayOfMonth,
        time.startMinutes.toHhMm(),
        time.durationMinutes.toDurationText(),
        time.days.describeDays()
    )
}
