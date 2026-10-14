package com.selflock.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selflock.alarm.AlarmScheduler
import com.selflock.data.Repos
import com.selflock.data.WhitelistGroup
import com.selflock.model.HolidayCalendar
import com.selflock.model.HolidayMode
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
import com.selflock.model.AppInfo
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
fun HomeScreen(onOpenPermissions: () -> Unit) {
    val context = LocalContext.current
    val locked by Repos.lockState.locked.collectAsState()
    val whitelist by Repos.whitelist.packages.collectAsState()
    val schedules by Repos.schedules.schedules.collectAsState()

    // 每次进入首页都重新体检一次权限（用户可能刚在系统设置里授权完）。
    // 这里刻意不用 LifecycleEventEffect：它来自 lifecycle-runtime-compose，
    // 本工程不想为此多引一个依赖。改成 LaunchedEffect(Unit)，
    // 配合 SelfLockNavHost 的 when 切换 —— 每次回到首页都会重新进入组合，效果等价。
    var refreshKey by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Repos.lockState.refresh()
    }

    val perms = remember(refreshKey) { PermissionSnapshot.of(context) }
    var showLockNowDialog by remember { mutableStateOf(false) }
    // 记住上次选的时长，下次打开弹窗时沿用，不用每次重选
    var lockNowMinutes by remember { mutableIntStateOf(DEFAULT_LOCK_NOW_MINUTES) }
    var holidayDismissed by remember {
        mutableIntStateOf(Repos.prefs.holidayReminderDismissedYear)
    }

    // 节假日数据过期提醒。
    // 只有真的用到了节假日模式才提醒 —— 没用到的话数据过期也毫无影响，提醒纯属噪音。
    // 关掉只关「今年」，明年会再提醒一次（存的是年份而不是布尔）。
    val currentYear = remember { LocalDate.now().year }
    val holidayReminder = remember(holidayDismissed, currentYear) {
        val inUse = Repos.schedules.current().any { it.holidayMode != HolidayMode.IGNORE }
        if (inUse && holidayDismissed != currentYear) HolidayCalendar.updateReminderText() else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 右上角「加固权限」入口。徽标直接把体检结果顶到脸上 ——
        // 权限没配齐是「锁机看起来在跑、其实能被绕过」的唯一原因，
        // 藏进二级页之后更需要在首页有个显眼的状态位。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SelfLock",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenPermissions) {
                Text(
                    text = if (perms.allOk) {
                        "\uD83D\uDEE1 加固权限 · 已就绪"
                    } else {
                        "\u26A0\uFE0F 加固权限 ${perms.okCount}/${PermissionSnapshot.TOTAL}"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (perms.allOk) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }

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
        // 详细体检表已经搬到「加固权限」页，这里只留最要命的一条 + 入口。
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
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onOpenPermissions) { Text("打开加固权限") }
            }
        }

        // 节假日数据过期提醒。放假安排每年 11 月才公布次年版本，
        // 表过期后「跳过节假日 / 只在节假日」会悄悄退化成只看星期几 —— 用户不会自己发现。
        if (holidayReminder != null) {
            SectionCard(
                title = "📅 节假日数据该更新了",
                subtitle = "放假安排每年更新一次"
            ) {
                Text(
                    text = holidayReminder,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    Repos.prefs.holidayReminderDismissedYear = currentYear
                    holidayDismissed = currentYear
                }) { Text("知道了，今年不再提醒") }
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
                TextButton(
                    enabled = lines.isNotEmpty(),
                    onClick = {
                        val dump = DiagLog.snapshot().joinToString("\n")
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("SelfLock 诊断日志", dump))
                        copied = true
                    }
                ) { Text(if (copied) "已复制 ✓" else "复制全部") }
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    quickDurationsList().forEach { preset ->
                        FilterChip(
                            selected = preset == minutes,
                            onClick = { minutes = preset },
                            label = { Text(preset.toDurationText()) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

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

/** 首页显示「下一次什么时候锁」。 */
private fun nextTriggerText(schedules: List<Schedule>): String {
    val now = LocalDateTime.now()
    val next = schedules
        // 不再要求 days 非空 —— 空集合现在是「单次」，同样是一条会触发的规则
        .filter { it.enabled }
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
