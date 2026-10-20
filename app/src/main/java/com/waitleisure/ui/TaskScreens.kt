package com.waitleisure.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waitleisure.data.Repos
import com.waitleisure.lock.Pomodoro
import com.waitleisure.model.Task
import com.waitleisure.model.TaskKind
import com.waitleisure.model.toDurationText

/**
 * 任务列表。
 *
 * **两类任务分栏展示，不混在一起** —— 简单锁机和番茄钟是两种完全不同的东西
 * （一个锁固定时长，一个在专注/休息之间轮转），混在一张列表里既难找也容易点错。
 *
 * 任务 = 预设好的锁法，**不含任何时间**。首页的「简单锁机」「番茄钟」和
 * 定时规则里的「执行任务」共用这一套。
 */
@Composable
fun TaskListScreen(
    onBack: () -> Unit,
    onNew: (TaskKind) -> Unit,
    onEdit: (Long) -> Unit
) {
    var version by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<Task?>(null) }
    val tasks = remember(version) { Repos.tasks.current() }

    val simple = tasks.filter { it.kind == TaskKind.SIMPLE_LOCK }
    val pomodoro = tasks.filter { it.kind == TaskKind.POMODORO }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.width(12.dp))
            Text("任务", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Text(
            text = "任务 = 一套预设好的锁法，**不含任何时间**。\n" +
                "首页的「简单锁机」「番茄钟」和定时规则里的「执行任务」共用这一套 —— " +
                "常用锁法只配一次。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ------------------------------------------------ 简单锁机
        SectionCard(
            title = "简单锁机",
            subtitle = "锁一段固定时长，到点自动解除"
        ) {
            if (simple.isEmpty()) {
                Text(
                    text = "还没有。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                simple.forEach { t ->
                    TaskRow(t, onEdit = { onEdit(t.id) }, onCopy = { Repos.tasks.duplicate(t.id) }, onDelete = { deleting = t })
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onNew(TaskKind.SIMPLE_LOCK) }) { Text("＋ 新建简单锁机") }
        }

        // ------------------------------------------------ 番茄钟
        SectionCard(
            title = "番茄钟",
            subtitle = "专注与休息自动轮转"
        ) {
            if (pomodoro.isEmpty()) {
                Text(
                    text = "还没有。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                pomodoro.forEach { t ->
                    TaskRow(t, onEdit = { onEdit(t.id) }, onCopy = { Repos.tasks.duplicate(t.id) }, onDelete = { deleting = t })
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onNew(TaskKind.POMODORO) }) { Text("＋ 新建番茄钟") }
        }

        Spacer(Modifier.height(8.dp))
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除任务") },
            text = {
                Text(
                    "确定删除「${target.name}」吗？\n" +
                        "引用它的定时规则不会失效 —— 会退回用规则自己的时长。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Repos.tasks.delete(target.id)
                    deleting = null
                    version++
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun TaskRow(task: Task, onEdit: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(task.name, fontSize = 14.sp)
            Text(
                text = task.describe(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onCopy) { Text("复制") }
        TextButton(onClick = onEdit) { Text("编辑") }
        TextButton(onClick = onDelete) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * 单个任务的编辑页。
 *
 * **按类别分成两套界面**：简单锁机只给时长，番茄钟只给轮转参数。
 * kind 不在这里选 —— 由调用方（从哪一栏点进来的）决定，
 * 避免"建到一半发现选错类别"这种来回改的麻烦。
 *
 * @param taskId  >= 0 编辑已有任务；< 0 新建
 * @param newKind 新建时的类别；编辑已有任务时以任务自身的类别为准
 */
@Composable
fun TaskEditScreen(taskId: Long, newKind: TaskKind, onDone: () -> Unit) {
    val existing = remember(taskId) { if (taskId >= 0) Repos.tasks.byId(taskId) else null }
    val isNew = existing == null
    val kind = existing?.kind ?: newKind

    var name by remember { mutableStateOf(existing?.name ?: defaultName(kind)) }
    var duration by remember { mutableIntStateOf(existing?.durationMinutes ?: 25) }

    // 番茄钟五个参数
    val initP = remember { Pomodoro.parse(existing?.pomodoroConfig ?: "4,25,5,15,2") }
    var rounds by remember { mutableStateOf(initP.rounds.toString()) }
    var focus by remember { mutableStateOf(initP.focusMinutes.toString()) }
    var short by remember { mutableStateOf(initP.shortBreak.toString()) }
    var long by remember { mutableStateOf(initP.longBreak.toString()) }
    var perSet by remember { mutableStateOf(initP.roundsPerSet.toString()) }

    val pomodoroOk = listOf(rounds, focus, short, long, perSet)
        .all { it.toIntOrNull()?.let { n -> n > 0 } == true }
    val canSave = name.isNotBlank() && (kind == TaskKind.SIMPLE_LOCK || pomodoroOk)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onDone) { Text("返回") }
            Spacer(Modifier.width(12.dp))
            Text(
                text = (if (isNew) "新建" else "编辑") +
                    if (kind == TaskKind.SIMPLE_LOCK) "简单锁机" else "番茄钟",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        SectionCard(title = "名称", subtitle = null) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("任务名") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (kind == TaskKind.SIMPLE_LOCK) {
            // 简单锁机：只关心锁多久
            SectionCard(title = "锁多久", subtitle = duration.toDurationText()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(15, 25, 45, 60, 120).forEach { preset ->
                        FilterChip(
                            selected = duration == preset,
                            onClick = { duration = preset },
                            label = { Text("$preset 分") }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                DurationPicker(
                    totalMinutes = duration,
                    maxMinutes = 24 * 60,
                    onSelect = { duration = it }
                )
            }
        } else {
            // 番茄钟：只关心轮转参数
            SectionCard(title = "轮转参数", subtitle = null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rounds,
                        onValueChange = { rounds = it.filter(Char::isDigit).take(2) },
                        singleLine = true,
                        label = { Text("循环次数") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = perSet,
                        onValueChange = { perSet = it.filter(Char::isDigit).take(2) },
                        singleLine = true,
                        label = { Text("几个循环后长休息") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = focus,
                    onValueChange = { focus = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    label = { Text("专注时长（分钟）") },
                    modifier = Modifier.fillMaxWidth()
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
        }

        Button(
            enabled = canSave,
            onClick = {
                val cfg = "$rounds,$focus,$short,$long,$perSet"
                val t = existing?.copy(
                    name = name.trim(),
                    durationMinutes = duration,
                    pomodoroConfig = cfg
                ) ?: Task(
                    id = Repos.tasks.nextId(),
                    name = name.trim(),
                    kind = kind,
                    durationMinutes = duration,
                    pomodoroConfig = cfg
                )
                Repos.tasks.upsert(t)
                onDone()
            }
        ) { Text("保存") }

        Spacer(Modifier.height(8.dp))
    }
}

private fun defaultName(kind: TaskKind): String =
    if (kind == TaskKind.SIMPLE_LOCK) "新的简单锁机" else "新的番茄钟"

/**
 * 「选一个任务」弹窗。首页两张卡片点开的就是它。
 *
 * 刻意做成「先选再执行」而不是点一下直接锁 —— 用户按下去之前
 * 应该能看见自己选的是哪一个（锁多久 / 怎么轮转），而不是盲锁。
 */
@Composable
fun TaskPickerDialog(
    kind: TaskKind,
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onPick: (Task) -> Unit,
    footer: (@Composable () -> Unit)? = null
) {
    val tasks = remember { Repos.tasks.current().filter { it.kind == kind } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (tasks.isEmpty()) {
                    Text(
                        text = "还没有这类任务。去「设置 → 任务」里新建一个。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    tasks.forEach { t ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(t) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(t.name, fontSize = 14.sp)
                            Text(
                                text = t.describe(),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                footer?.let {
                    Spacer(Modifier.height(4.dp))
                    it()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
