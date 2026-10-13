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
import java.time.LocalDateTime
import java.time.ZoneId

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

    val allOk: Boolean get() = okCount == 7

    companion object {
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
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Repos.lockState.refresh()
    }

    val perms = remember(refreshKey) { PermissionSnapshot.of(context) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showLockNowDialog by remember { mutableStateOf(false) }
    // 记住上次选的时长，下次打开弹窗时沿用，不用每次重选
    var lockNowMinutes by remember { mutableIntStateOf(DEFAULT_LOCK_NOW_MINUTES) }
    var allowPinUnlock by remember { mutableStateOf(Repos.prefs.allowPinUnlock) }
    val groups by Repos.groups.groups.collectAsState()
    var showQuickEdit by remember { mutableStateOf(false) }
    var showGroupEdit by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<WhitelistGroup?>(null) }

    // 壁纸图片选择：选完复制到应用私有目录，锁机页直接读文件
    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val file = File(context.filesDir, "wallpaper.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
                Repos.prefs.wallpaperImagePath = file.absolutePath
                Repos.prefs.wallpaperColor = 0xFF000000.toInt()
            }
        }
    }

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
            subtitle = "已就绪 ${perms.okCount} / 7 · 缺任何一项都会让锁机漏风"
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
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { refreshKey++ }) {
                Text("重新检测")
            }
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
            title = "解锁 PIN",
            subtitle = if (Repos.prefs.pinHash.isNullOrEmpty()) {
                "尚未设置。不设置也能用，但锁定期间可直接解除。"
            } else {
                "已设置。锁定期间输入 PIN 可提前解除。"
            }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPinDialog = true }) {
                    Text(if (Repos.prefs.pinHash.isNullOrEmpty()) "设置 PIN" else "修改 PIN")
                }
                if (!Repos.prefs.pinHash.isNullOrEmpty()) {
                    OutlinedButton(onClick = { Repos.prefs.pinHash = null }) {
                        Text("清除 PIN")
                    }
                }
            }
        }

        SectionCard(
            title = "锁机选项",
            subtitle = "密码解锁与常用时长"
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text("允许密码解锁", fontSize = 14.sp)
                    Text(
                        text = "关闭后锁机页不显示解锁按钮，只能等时间到",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = allowPinUnlock,
                    onCheckedChange = {
                        allowPinUnlock = it
                        Repos.prefs.allowPinUnlock = it
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { showQuickEdit = true }) {
                Text("立即锁机常用时长：${Repos.prefs.quickDurations} 分钟")
            }
        }

        SectionCard(
            title = "锁机页壁纸",
            subtitle = "纯色或自定义图片"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WALLPAPER_COLORS.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c)
                            .clickable {
                                Repos.prefs.wallpaperColor = c.toArgb()
                                Repos.prefs.wallpaperImagePath = null
                            }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                TextButton(onClick = { wallpaperPicker.launch("image/*") }) { Text("选择图片…") }
                if (Repos.prefs.wallpaperImagePath != null) {
                    TextButton(onClick = { Repos.prefs.wallpaperImagePath = null }) {
                        Text("恢复纯色")
                    }
                }
            }
        }

        SectionCard(
            title = "白名单分组",
            subtitle = "不同时段可用不同应用（全局白名单始终生效）"
        ) {
            if (groups.isEmpty()) {
                Text(
                    text = "还没有分组。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                groups.forEach { g ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingGroup = g; showGroupEdit = true }
                            .padding(vertical = 6.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${g.name} · ${g.startMinute.toHhMm()}-${g.endMinute.toHhMm()} · ${g.packages.size} 个应用",
                                fontSize = 13.sp,
                                color = if (g.enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!g.enabled) {
                                Text("已停用", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(onClick = { Repos.groups.remove(g.id) }) { Text("删除") }
                    }
                }
            }
            TextButton(onClick = { editingGroup = null; showGroupEdit = true }) {
                Text("＋ 新建分组")
            }
        }

        SectionCard(
            title = "番茄锁机",
            subtitle = "专注一段时间，期间锁机"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                POMODORO_PRESETS.forEach { m ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            LockController.lock(
                                context,
                                System.currentTimeMillis() + m * 60_000L
                            )
                        },
                        label = { Text("$m 分") }
                    )
                }
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

    if (showQuickEdit) {
        QuickDurationsDialog(
            onDismiss = { showQuickEdit = false },
            onSave = { text ->
                Repos.prefs.quickDurations = text
                showQuickEdit = false
            }
        )
    }

    if (showGroupEdit) {
        GroupEditDialog(
            editing = editingGroup,
            onDismiss = { showGroupEdit = false },
            onSave = { group ->
                Repos.groups.save(group)
                showGroupEdit = false
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

// ---------------------------------------------------------------- 锁机选项

/** 解析「立即锁机常用时长」的逗号分隔列表。 */
private fun quickDurationsList(): List<Int> =
    Repos.prefs.quickDurations
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it > 0 }
        .ifEmpty { listOf(5, 15, 30, 60) }

/** 锁机页壁纸预设色。 */
private val WALLPAPER_COLORS = listOf(
    Color(0xFF000000),
    Color(0xFF101014),
    Color(0xFF1A1A2E),
    Color(0xFF0F2027),
    Color(0xFF1B2A1B),
    Color(0xFF2B1B1B)
)

/** 番茄锁机预设时长（分钟）。 */
private val POMODORO_PRESETS = listOf(15, 25, 45)

private fun parseHhMm(s: String): Int? {
    val parts = s.split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

@Composable
private fun QuickDurationsDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(Repos.prefs.quickDurations) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("立即锁机常用时长") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.filter { c -> c.isDigit() || c == ',' || c == ' ' }
                    },
                    singleLine = true,
                    label = { Text("分钟数，用逗号分隔，如 5,15,30,60") }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "这些会显示在「立即锁机」弹窗里作为快捷选项。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .isNotEmpty(),
                onClick = { onSave(text.trim()) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun GroupEditDialog(
    editing: WhitelistGroup?,
    onDismiss: () -> Unit,
    onSave: (WhitelistGroup) -> Unit
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var start by remember { mutableStateOf(editing?.startMinute?.toHhMm() ?: "19:00") }
    var end by remember { mutableStateOf(editing?.endMinute?.toHhMm() ?: "22:00") }
    var selected by remember { mutableStateOf(editing?.packages ?: emptySet()) }

    val apps by produceState(initialValue = emptyList<AppInfo>()) {
        value = Repos.installedApps.loadLaunchableApps()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "新建分组" else "编辑分组") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("分组名") }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it },
                        singleLine = true,
                        label = { Text("开始 HH:mm") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it },
                        singleLine = true,
                        label = { Text("结束 HH:mm") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "选择该时段可用的应用（已选 ${selected.size} 个）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (app.packageName in selected) {
                                        selected - app.packageName
                                    } else {
                                        selected + app.packageName
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = app.label,
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                                maxLines = 1
                            )
                            Checkbox(
                                checked = app.packageName in selected,
                                onCheckedChange = null
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() &&
                    parseHhMm(start) != null &&
                    parseHhMm(end) != null,
                onClick = {
                    val s = parseHhMm(start) ?: return@TextButton
                    val e = parseHhMm(end) ?: return@TextButton
                    onSave(
                        WhitelistGroup(
                            id = editing?.id ?: Repos.groups.nextId(),
                            name = name.trim(),
                            packages = selected,
                            startMinute = s,
                            endMinute = e,
                            enabled = editing?.enabled ?: true
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
