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
fun SettingsScreen() {
    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var allowPinUnlock by remember { mutableStateOf(Repos.prefs.allowPinUnlock) }
    var showQuickEdit by remember { mutableStateOf(false) }
    val groups by Repos.groups.groups.collectAsState()
    var showGroupEdit by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<WhitelistGroup?>(null) }
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
                                fontSize = 13.sp
                            )
                            if (!g.enabled) {
                                Text(
                                    text = "已停用",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
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
            title = "番茄钟",
            subtitle = Pomodoro.describe(Repos.prefs.pomodoroConfig)
        ) {
            Text(
                text = "按「几组 × 每组多长」轮转：每组结束时休息，若干组后给一次大休息。\n" +
                    "专注时段自动锁机，休息时段自动解锁。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPomodoroEdit = true }) { Text("自定义…") }
                Button(onClick = {
                    Pomodoro.start(context)
                }) { Text("开始番茄钟") }
            }
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
                        label = { Text("大休息间隔（组）") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = rounds,
                        onValueChange = { rounds = it.filter(Char::isDigit).take(2) },
                        singleLine = true,
                        label = { Text("共几组") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = focus,
                    onValueChange = { focus = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    label = { Text("每组专注（分钟）") }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = short,
                        onValueChange = { short = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        label = { Text("小休息（分）") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = long,
                        onValueChange = { long = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        label = { Text("大休息（分）") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "例：间隔 2、共 4 组、每组 25、小休 5、大休 15 → " +
                        "25 专注 → 5 小休 → 25 专注 → 15 大休 → 25 专注 → 5 小休 → 25 专注",
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
