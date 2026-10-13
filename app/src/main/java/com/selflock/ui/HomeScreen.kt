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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selflock.alarm.AlarmScheduler
import com.selflock.data.Repos
import com.selflock.util.DiagLog
import com.selflock.lock.LockAccessibilityService
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
    var showLockNowDialog by remember { mutableStateOf(false) }
    // 记住上次选的时长，下次打开弹窗时沿用，不用每次重选
    var lockNowMinutes by remember { mutableIntStateOf(DEFAULT_LOCK_NOW_MINUTES) }

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
            onLockNow = { showLockNowDialog = true },
            onOpenLockScreen = { LockScreenActivity.show(context) }
        )

        // 无障碍服务没在跑时，锁机完全不生效，而且界面上不会有任何报错。
        // 这张卡片必须放在最上面 —— 放到页面底部等于没有：用户不滚动就看不到，
        // 实测用 uiautomator 抓屏幕内容时，屏幕外的节点根本抓不到。
        if (!Permissions.isAccessibilityEnabled(context)) {
            SectionCard(
                title = "⚠️ 无障碍服务未运行 —— 锁机当前不生效",
                subtitle = if (Permissions.isAccessibilityListedButDead(context)) {
                    "系统设置里显示它「已开启」，但系统并没有真正绑定它。" +
                        "这在安装新版本 APK 后很常见。" +
                        "请到 设置 → 更多设置 → 无障碍 → SelfLock 锁机服务，" +
                        "先关掉、再重新打开一次。"
                } else {
                    "请到 设置 → 更多设置 → 无障碍 → SelfLock 锁机服务 打开它。"
                }
            ) {
                Text(
                    text = "为什么单独提示：服务没在跑的时候，锁机不会拦截任何应用" +
                        "（包括桌面），但界面上不会弹任何错误 —— 看起来就像功能坏了。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

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
                description = "先弹系统授权框；若未弹出则落到应用信息页，手动把「省电策略」设为「无限制」",
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
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { refreshKey++ }) { Text("刷新") }
                TextButton(onClick = { DiagLog.clear(); refreshKey++ }) { Text("清空") }
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

    if (showLockNowDialog) {
        LockNowDialog(
            initialMinutes = lockNowMinutes,
            hasPin = !Repos.prefs.pinHash.isNullOrEmpty(),
            onDismiss = { showLockNowDialog = false },
            onConfirm = { minutes ->
                lockNowMinutes = minutes
                showLockNowDialog = false
                // 这里一律传具体的到期时间戳（而不是 0=无限期），
                // 所以「立即锁机」一定会到点自动解除，不会出现无限期锁死的情况。
                LockController.lock(context, System.currentTimeMillis() + minutes * 60_000L)
            }
        )
    }
}

// ---------------------------------------------------------------- 立即锁机

/**
 * 立即锁机的时长范围。
 * 下限特意放到 1 分钟 —— 调试锁机行为时不用每次干等 15 分钟。
 * 上限 24 小时，选到头也只能锁一天。
 */
private const val MIN_LOCK_NOW_MINUTES = 1
private const val MAX_LOCK_NOW_MINUTES = 24 * 60
private const val DEFAULT_LOCK_NOW_MINUTES = 60

/**
 * 「立即锁机」的时长选择。
 *
 * 上限写死 24 小时：拖到底也只能锁一天，避免误操作把自己锁死。
 * 同时把「没有 PIN」的提醒并进这个弹窗 —— 否则要先过一个警告弹窗、
 * 再过一个时长弹窗，两步太啰嗦。
 */
@Composable
private fun LockNowDialog(
    initialMinutes: Int,
    hasPin: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var minutes by remember {
        mutableIntStateOf(initialMinutes.coerceIn(MIN_LOCK_NOW_MINUTES, MAX_LOCK_NOW_MINUTES))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("立即锁机") },
        text = {
            Column {
                Text(
                    text = minutes.toDurationText(),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "将于 ${releaseTimeText(minutes)} 自动解除",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                DurationPicker(
                    totalMinutes = minutes,
                    maxMinutes = MAX_LOCK_NOW_MINUTES,
                    minMinutes = MIN_LOCK_NOW_MINUTES,
                    onSelect = { minutes = it }
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = if (hasPin) {
                        "锁定期间只有白名单应用可用，输入 PIN 可提前解除。最长 24 小时。"
                    } else {
                        "⚠️ 尚未设置 PIN，锁定期间可直接解除，建议先到首页设置。最长 24 小时。"
                    },
                    fontSize = 11.sp,
                    color = if (hasPin) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(minutes) }) { Text("开始锁定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 把「锁 N 分钟」换算成给人看的解除时刻；跨天时带上前缀，避免只看到 HH:mm 分不清哪天。 */
private fun releaseTimeText(minutes: Int): String {
    val now = LocalDateTime.now()
    val at = now.plusMinutes(minutes.toLong())
    val dayLabel = when (at.toLocalDate()) {
        now.toLocalDate() -> "今天"
        now.toLocalDate().plusDays(1) -> "明天"
        else -> "%d月%d日".format(at.monthValue, at.dayOfMonth)
    }
    return "%s %02d:%02d".format(dayLabel, at.hour, at.minute)
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
