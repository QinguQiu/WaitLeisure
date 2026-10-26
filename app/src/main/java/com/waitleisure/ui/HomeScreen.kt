package com.waitleisure.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waitleisure.alarm.AlarmScheduler
import com.waitleisure.R
import com.waitleisure.data.Repos
import com.waitleisure.data.WhitelistGroup
import com.waitleisure.model.HolidayCalendar
import com.waitleisure.model.HolidayMode
import com.waitleisure.util.DiagLog
import com.waitleisure.lock.LockAccessibilityService
import com.waitleisure.lock.LockController
import com.waitleisure.lock.LockScreenActivity
import com.waitleisure.lock.Pomodoro
import com.waitleisure.model.Schedule
import com.waitleisure.model.TaskKind
import com.waitleisure.model.describeDays
import com.waitleisure.model.toDurationText
import com.waitleisure.model.toHhMm
import com.waitleisure.util.Permissions
import com.waitleisure.util.Pin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.waitleisure.model.AppInfo
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
    // 配合 AppNavHost 的 when 切换 —— 每次回到首页都会重新进入组合，效果等价。
    var refreshKey by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    // 番茄钟状态存在 prefs 里而不是 StateFlow，所以靠 refreshKey 驱动重算
    val pomodoroActive = Pomodoro.isActive()
    LaunchedEffect(Unit) {
        Repos.lockState.refresh()
    }

    // 从系统设置授权完回到 App 时必须立刻重算。
    //
    // 光靠「切 tab 重新进入组合」不够：退到后台再回来**不会**重建组合，
    // 首页会一直显示旧结果 —— 实测手动去设置里开完无障碍再回来，
    // 首页还写着「无障碍服务未运行」，看起来就像白开了。
    // 这里监听 ON_RESUME（进入前台）主动刷新。
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val perms = remember(refreshKey) { PermissionSnapshot.of(context) }
    var showSimplePicker by remember { mutableStateOf(false) }
    var showPomodoroPicker by remember { mutableStateOf(false) }
    // 简单锁机选择框里的「自定义时长…」兜底入口
    var showCustomDuration by remember { mutableStateOf(false) }

    // 首页两张卡片上显示的任务：优先「上次用的那个」，否则该类别的第一个。
    // 让用户按下去之前就知道会发生什么。
    val simpleTask = remember(refreshKey) {
        val all = Repos.tasks.current()
        all.firstOrNull { it.id == Repos.prefs.lastSimpleTaskId }
            ?: all.firstOrNull { it.kind == TaskKind.SIMPLE_LOCK }
    }
    val pomodoroTask = remember(refreshKey) {
        val all = Repos.tasks.current()
        all.firstOrNull { it.id == Repos.prefs.lastPomodoroTaskId }
            ?: all.firstOrNull { it.kind == TaskKind.POMODORO }
    }
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
                text = stringResource(R.string.app_name),
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
            onOpenLockScreen = { LockScreenActivity.show(context) }
        )

        // 两张独立的动作卡片。选中的任务存进 prefs，切页面回来还记得。
        // 番茄钟进行中时，第二张卡变成「停止番茄钟」。
        if (pomodoroActive) {
            // 休息阶段不给「点此停止」——休息本来就是放松时间，
            // 但「停止」意味着这次番茄彻底作废，只该在专注阶段由用户主动放弃。
            // 休息时点卡片还会开选择框，等于变相允许改设置，一起堵掉。
            val pomoNow = Pomodoro.current()
            val isBreak = pomoNow?.second == "break"
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionCard(
                    title = "简单锁机", glyph = "⏳",
                    taskName = simpleTask?.name, taskDetail = simpleTask?.describe(),
                    modifier = Modifier.weight(1f),
                    onClick = { showSimplePicker = true }
                )
                ActionCard(
                    title = if (isBreak) "休息中" else "番茄钟进行中",
                    glyph = "🍅",
                    taskName = Pomodoro.describe(Pomodoro.activeConfig()),
                    taskDetail = if (isBreak) "休息阶段不能停止" else "点此停止",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // 休息阶段完全不响应点击
                        if (!isBreak) {
                            Pomodoro.stop(context)
                            refreshKey++
                        }
                    }
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionCard(
                    title = "简单锁机", glyph = "⏳",
                    taskName = simpleTask?.name, taskDetail = simpleTask?.describe(),
                    modifier = Modifier.weight(1f),
                    onClick = { showSimplePicker = true }
                )
                ActionCard(
                    title = "番茄钟", glyph = "🍅",
                    taskName = pomodoroTask?.name, taskDetail = pomodoroTask?.describe(),
                    modifier = Modifier.weight(1f),
                    onClick = { showPomodoroPicker = true }
                )
            }
        }

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
                        "请到 设置 → 更多设置 → 无障碍 → 等闲 锁机服务，" +
                        "先关掉、再重新打开一次。"
                } else {
                    "请到 设置 → 更多设置 → 无障碍 → 等闲 锁机服务 打开它。"
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

        // 诊断日志已经搬到「加固权限」页 —— 那页本来就是"体检/排查"的聚集地，
        // 首页只留日常要用的东西。要点它从首页右上角的入口进去。
        SectionCard(
            title = "使用提示",
            subtitle = null
        ) {
            Text(
                text = "1. 一定要开启无障碍服务，否则锁机无效。\n" +
                    "2. 锁定期间只能使用白名单软件，无法回到桌面。\n" ,
                    
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(8.dp))
    }

    // 选中任务后才真正开始 —— 点卡片只是打开选择框，不会盲锁
    if (showSimplePicker) {
        TaskPickerDialog(
            kind = TaskKind.SIMPLE_LOCK,
            title = "选择简单锁机任务",
            subtitle = "选中后立刻开始锁机，到点自动解除。",
            onDismiss = { showSimplePicker = false },
            onPick = { t ->
                Repos.prefs.lastSimpleTaskId = t.id
                showSimplePicker = false
                if (Pomodoro.isActive()) Pomodoro.stop(context)
                LockController.lock(
                                    context,
                                    System.currentTimeMillis() + t.durationMinutes * 60_000L,
                                    taskName = t.name
                                )
                refreshKey++
            },
            footer = {
                Text(
                    text = "自定义时长…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showSimplePicker = false
                            showCustomDuration = true
                        }
                        .padding(vertical = 8.dp)
                )
            }
        )
    }

    if (showPomodoroPicker) {
        TaskPickerDialog(
            kind = TaskKind.POMODORO,
            title = "选择番茄钟任务",
            subtitle = "选中后立刻开始：专注时段锁机，休息时段自动解锁。",
            onDismiss = { showPomodoroPicker = false },
            onPick = { t ->
                Repos.prefs.lastPomodoroTaskId = t.id
                showPomodoroPicker = false
                Pomodoro.start(context, configOverride = t.pomodoroConfig, taskName = t.name)
                refreshKey++
            }
        )
    }

    if (showCustomDuration) {
        LockNowDialog(
            initialMinutes = lockNowMinutes,
            hasPin = !Repos.prefs.pinHash.isNullOrEmpty(),
            onDismiss = { showCustomDuration = false },
            onConfirm = { minutes ->
                lockNowMinutes = minutes
                showCustomDuration = false
                // 这里一律传具体的到期时间戳（而不是 0=无限期），
                // 所以「立即锁机」一定会到点自动解除，不会出现无限期锁死的情况。
                LockController.lock(context, System.currentTimeMillis() + minutes * 60_000L)
                refreshKey++
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
        if (locked) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenLockScreen, modifier = Modifier.fillMaxWidth()) {
                Text("回到锁机页")
            }
        }
    }
}

/**
 * 首页的两张动作卡片：简单锁机 / 番茄钟。
 *
 * 刻意做成**两张各自独立的卡片**而不是两个按钮：
 * 卡片上要显示"这次会用哪个任务"，按钮塞不下这些信息。
 *
 * 点卡片**不会直接锁** —— 先弹出任务选择框，选中哪个才执行哪个。
 */
@Composable
private fun ActionCard(
    title: String,
    glyph: String,
    taskName: String?,
    taskDetail: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        // weight 是 RowScope 的扩展，在独立 composable 里拿不到 —— 由调用方传进来
        modifier = modifier.clickable { onClick() }
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = "$glyph $title",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = taskName ?: "还没有任务",
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (taskDetail != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = taskDetail,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (taskName == null) "去「设置 → 任务」新建" else "点此选择任务",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
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
