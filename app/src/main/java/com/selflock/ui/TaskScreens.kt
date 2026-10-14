package com.selflock.ui

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
import androidx.compose.material3.RadioButton
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
import com.selflock.data.Repos
import com.selflock.lock.Pomodoro
import com.selflock.model.Task
import com.selflock.model.TaskKind
import com.selflock.model.toDurationText
import com.selflock.model.toHhMm

/**
 * 任务列表。
 *
 * 任务是「预设好的锁法」，不含时间。定时规则里选一个任务，
 * 就等价于「到点执行这套锁法」。
 */
@Composable
fun TaskListScreen(onBack: () -> Unit, onEdit: (Long) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<Task?>(null) }
    val tasks = remember(version) { Repos.tasks.current() }

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
            text = "任务 = 一套预设好的锁法（锁多久 / 番茄钟怎么轮转），**不含任何时间**。\n" +
                "定时规则里选一个任务，就等于「到点执行它」—— 常用锁法只配一次，多条规则复用。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (tasks.isEmpty()) {
            SectionCard(title = "还没有任务", subtitle = null) {
                Text(
                    text = "新建一个，然后在「定时」里选它。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            tasks.forEach { t ->
                SectionCard(title = t.name, subtitle = t.describe()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onEdit(t.id) }) { Text("编辑") }
                        TextButton(onClick = { deleting = t }) { Text("删除") }
                    }
                }
            }
        }

        Button(
            onClick = { onEdit(-1L) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("＋ 新建任务") }

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

/** 单个任务的编辑页。taskId < 0 表示新建。 */
@Composable
fun TaskEditScreen(taskId: Long, onDone: () -> Unit) {
    val existing = remember(taskId) { if (taskId >= 0) Repos.tasks.byId(taskId) else null }
    val isNew = existing == null

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: TaskKind.SIMPLE_LOCK) }
    var duration by remember { mutableIntStateOf(existing?.durationMinutes ?: 25) }

    // 番茄钟五个参数，逗号分隔存
    val initP = remember { Pomodoro.parse(existing?.pomodoroConfig ?: "4,25,5,15,2") }
    var rounds by remember { mutableStateOf(initP.rounds.toString()) }
    var focus by remember { mutableStateOf(initP.focusMinutes.toString()) }
    var short by remember { mutableStateOf(initP.shortBreak.toString()) }
    var long by remember { mutableStateOf(initP.longBreak.toString()) }
    var perSet by remember { mutableStateOf(initP.roundsPerSet.toString()) }

    val canSave = name.isNotBlank() && (
        kind == TaskKind.SIMPLE_LOCK ||
            listOf(rounds, focus, short, long, perSet).all { it.toIntOrNull()?.let { n -> n > 0 } == true }
        )

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
            Text(if (isNew) "新建任务" else "编辑任务", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        SectionCard(title = "名称", subtitle = null) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("例如：专注 25 分钟") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        SectionCard(
            title = "锁法",
            subtitle = if (kind == TaskKind.SIMPLE_LOCK) "简单锁机" else "番茄钟"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == TaskKind.SIMPLE_LOCK,
                    onClick = { kind = TaskKind.SIMPLE_LOCK },
                    label = { Text("简单锁机") }
                )
                FilterChip(
                    selected = kind == TaskKind.POMODORO,
                    onClick = { kind = TaskKind.POMODORO },
                    label = { Text("番茄钟") }
                )
            }
        }

        if (kind == TaskKind.SIMPLE_LOCK) {
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
            SectionCard(title = "番茄钟参数", subtitle = null) {
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
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = canSave,
                onClick = {
                    val cfg = "$rounds,$focus,$short,$long,$perSet"
                    val t = existing?.copy(
                        name = name.trim(),
                        kind = kind,
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
        }

        Spacer(Modifier.height(8.dp))
    }
}
