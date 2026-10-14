package com.selflock.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selflock.data.Repos
import com.selflock.data.WhitelistGroup
import com.selflock.lock.Pomodoro
import com.selflock.model.AppInfo
import com.selflock.model.toHhMm
import com.selflock.util.Pin
import java.io.File
import java.io.FileOutputStream

/**
 * 设置页。
 *
 * 把「跟锁机行为有关、平时不常改」的东西都收到这里：
 * PIN、允许密码解锁、立即锁机常用时长、锁机页壁纸、白名单分组、番茄钟配置。
 * 首页只留状态、权限体检和诊断，保持清爽。
 */
@Composable
fun SettingsScreen(onOpenTasks: () -> Unit = {}) {
    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var allowPinUnlock by remember { mutableStateOf(Repos.prefs.allowPinUnlock) }
    var showQuickEdit by remember { mutableStateOf(false) }
    var showPomodoroEdit by remember { mutableStateOf(false) }
    var pinSet by remember { mutableStateOf(!Repos.prefs.pinHash.isNullOrEmpty()) }

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
        GroupHeader(
            title = "解锁与安全",
            hint = "锁定期间能不能提前退出"
        )

        SectionCard(
            title = "解锁 PIN",
            subtitle = if (!pinSet) {
                "尚未设置。不设置也能用，但锁定期间可直接解除。"
            } else {
                "已设置。锁定期间输入 PIN 可提前解除。"
            }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPinDialog = true }) {
                    Text(if (!pinSet) "设置 PIN" else "修改 PIN")
                }
                if (pinSet) {
                    OutlinedButton(onClick = {
                        Repos.prefs.pinHash = null
                        pinSet = false
                    }) {
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
                        text = if (allowPinUnlock) {
                            "锁机页会显示「解除锁定」按钮"
                        } else {
                            "⚠️ 关闭后锁机页没有解锁入口，当前锁定也只能等时间到"
                        },
                        fontSize = 11.sp,
                        color = if (allowPinUnlock) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
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

        GroupHeader(
            title = "锁机外观",
            hint = "锁机页长什么样"
        )

        SectionCard(
            title = "锁机页壁纸",
            subtitle = "纯色或自定义图片"
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                WALLPAPER_COLORS.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(34.dp)
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
                TextButton(onClick = { wallpaperPicker.launch("image/*") }) {
                    Text(if (Repos.prefs.wallpaperImagePath == null) "选择图片…" else "换一张图片…")
                }
                if (Repos.prefs.wallpaperImagePath != null) {
                    TextButton(onClick = { Repos.prefs.wallpaperImagePath = null }) {
                        Text("恢复纯色")
                    }
                }
            }
        }

        GroupHeader(
            title = "专注模式",
            hint = "番茄钟轮转"
        )

        SectionCard(
            title = "番茄钟",
            subtitle = Pomodoro.describe(Repos.prefs.pomodoroConfig)
        ) {
            Text(
                text = "按「循环次数 × 专注时长」轮转：每个循环结束时休息，若干循环后给一次长休息。\n" +
                    "专注时段自动锁机，休息时段自动解锁。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPomodoroEdit = true }) { Text("自定义…") }
            }
        }

        GroupHeader(
            title = "任务",
            hint = "预设好的锁法，供定时规则引用"
        )

        SectionCard(
            title = "任务",
            subtitle = Repos.tasks.current().size.let { if (it == 0) "还没有任务" else "共 $it 个" }
        ) {
            Text(
                text = "任务是一套预设好的锁法（锁多久 / 番茄钟怎么轮转），**不含时间**。\n" +
                    "定时规则里选一个任务，就等于「到点执行它」—— 常用锁法只配一次。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenTasks) { Text("管理任务…") }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showPinDialog) {
        SetPinDialog(
            onDismiss = { showPinDialog = false },
            onSave = { pin ->
                Repos.prefs.pinHash = Pin.hash(pin)
                pinSet = true
                showPinDialog = false
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

    if (showPomodoroEdit) {
        PomodoroConfigDialog(
            onDismiss = { showPomodoroEdit = false },
            onSave = { text ->
                Repos.prefs.pomodoroConfig = text
                showPomodoroEdit = false
            }
        )
    }
}

// ---------------------------------------------------------------- 常量 / 工具

/**
 * 设置页的分组标题。
 *
 * 设置项一多就容易变成一长串平铺的卡片，找不到想改的那条。
 * 按用途分组、每组一句话说明「这组管什么」，扫一眼就知道该往哪找。
 */
@Composable
private fun GroupHeader(title: String, hint: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 10.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        if (hint != null) {
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 锁机页壁纸预设色。
 * 之前那一版全挑的深色（#000000/#101014/…），肉眼看几乎都是黑的，所以这里换成
 * 明度、色相都拉开的一组，选哪个一眼能看出来。
 */
val WALLPAPER_COLORS = listOf(
    Color(0xFF121212), // 近黑
    Color(0xFF1E3A8A), // 深蓝
    Color(0xFF0E7490), // 青
    Color(0xFF166534), // 深绿
    Color(0xFF6B21A8), // 紫
    Color(0xFF7F1D1D), // 深红
    Color(0xFF9A3412), // 橙棕
    Color(0xFF3F3F46)  // 灰
)

/** 解析「立即锁机常用时长」的逗号分隔列表。 */
fun quickDurationsList(): List<Int> =
    Repos.prefs.quickDurations
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it > 0 }
        .ifEmpty { listOf(5, 15, 30, 60) }

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
private fun PomodoroConfigDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val cur = remember { Pomodoro.parse(Repos.prefs.pomodoroConfig) }
    var rounds by remember { mutableStateOf(cur.rounds.toString()) }
    var focus by remember { mutableStateOf(cur.focusMinutes.toString()) }
    var short by remember { mutableStateOf(cur.shortBreak.toString()) }
    var long by remember { mutableStateOf(cur.longBreak.toString()) }
    var perSet by remember { mutableStateOf(cur.roundsPerSet.toString()) }

    fun num(s: String, def: Int) = s.trim().toIntOrNull()?.takeIf { it > 0 } ?: def

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("番茄钟设置") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = perSet,
                        onValueChange = { perSet = it.filter(Char::isDigit).take(2) },
                        singleLine = true,
                        label = { Text("几个循环后长休息") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = rounds,
                        onValueChange = { rounds = it.filter(Char::isDigit).take(2) },
                        singleLine = true,
                        label = { Text("循环次数") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = focus,
                    onValueChange = { focus = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    label = { Text("专注时长（分钟）") }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = short,
                        onValueChange = { short = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        label = { Text("短休息（分钟）") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = long,
                        onValueChange = { long = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        label = { Text("长休息（分钟）") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "例：循环 4 次、专注 25 分钟、短休 5 分钟、长休 15 分钟、每 2 个循环长休一次 →\n" +
                        "专注 25 → 短休 5 → 专注 25 → 长休 15 → 专注 25 → 短休 5 → 专注 25 → 结束",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    listOf(
                        num(rounds, 4),
                        num(focus, 25),
                        num(short, 5),
                        num(long, 15),
                        num(perSet, 2)
                    ).joinToString(",")
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
            TextButton(enabled = valid, onClick = { onSave(pin) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
