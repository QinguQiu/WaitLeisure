package com.selflock.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selflock.R
import com.selflock.alarm.AlarmScheduler
import com.selflock.data.Repos
import com.selflock.model.DAY_LABELS
import com.selflock.model.Schedule
import com.selflock.model.describeDays
import com.selflock.model.toDurationText
import com.selflock.model.toHhMm
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 列表

@Composable
fun ScheduleListScreen(onEdit: (Long) -> Unit) {
    val context = LocalContext.current
    val schedules by Repos.schedules.schedules.collectAsState()
    var pendingDelete by remember { mutableStateOf<Schedule?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("定时锁机", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "到点自动锁定，到期自动解除",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = { onEdit(-1L) }) { Text("新建") }
        }

        Spacer(Modifier.height(12.dp))

        if (schedules.isEmpty()) {
            EmptyHint("还没有定时规则，点右上角「新建」加一条。")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(schedules, key = { it.id }) { schedule ->
                    ScheduleCard(
                        schedule = schedule,
                        onToggle = { enabled ->
                            val updated = schedule.copy(enabled = enabled)
                            Repos.schedules.upsert(updated)
                            if (enabled) {
                                AlarmScheduler.scheduleNextStart(context, updated)
                            } else {
                                AlarmScheduler.cancel(context, updated.id)
                            }
                        },
                        onClick = { onEdit(schedule.id) },
                        onDelete = { pendingDelete = schedule }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条规则？") },
            text = { Text("${target.startMinutes.toHhMm()} · ${target.days.describeDays()}") },
            confirmButton = {
                TextButton(onClick = {
                    AlarmScheduler.cancel(context, target.id)
                    Repos.schedules.delete(target.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ScheduleCard(
    schedule: Schedule,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = schedule.startMinutes.toHhMm(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "持续 ${schedule.durationMinutes.toDurationText()}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(schedule.days.describeDays())
                        if (schedule.crossesMidnight) {
                            append(" · 次日 ")
                            append(schedule.endMinutes.toHhMm())
                            append(" 解除")
                        } else {
                            append(" · ")
                            append(schedule.endMinutes.toHhMm())
                            append(" 解除")
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = schedule.enabled, onCheckedChange = onToggle)
            TextButton(onClick = onDelete) {
                Text("\u5220\u9664")
            }
        }
    }
}

// ---------------------------------------------------------------- 编辑

@Composable
fun ScheduleEditScreen(scheduleId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val existing = remember(scheduleId) { Repos.schedules.byId(scheduleId) }
    val isNew = existing == null

    var startMinutes by remember { mutableIntStateOf(existing?.startMinutes ?: (23 * 60)) }
    var durationMinutes by remember { mutableIntStateOf(existing?.durationMinutes ?: (8 * 60)) }
    var days by remember { mutableStateOf(existing?.days ?: setOf(1, 2, 3, 4, 5, 6, 7)) }
    var showTimePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (isNew) "新建定时规则" else "编辑定时规则",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        SectionCard(title = "起始时间", subtitle = "每天到这个点自动锁定") {
            OutlinedButton(onClick = { showTimePicker = true }) {
                Text(startMinutes.toHhMm(), fontSize = 22.sp)
            }
        }

        SectionCard(title = "持续时长", subtitle = durationMinutes.toDurationText()) {
            Slider(
                value = durationMinutes.toFloat(),
                onValueChange = { durationMinutes = (it / 15f).roundToInt() * 15 },
                valueRange = 15f..720f
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf(30, 60, 120, 240, 480).forEach { preset ->
                    AssistChip(
                        onClick = { durationMinutes = preset },
                        label = { Text(preset.toDurationText()) }
                    )
                }
            }
        }

        SectionCard(title = "重复", subtitle = days.describeDays()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                (1..7).forEach { day ->
                    FilterChip(
                        selected = days.contains(day),
                        onClick = { days = if (days.contains(day)) days - day else days + day },
                        label = { Text(DAY_LABELS[day - 1]) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { days = setOf(1, 2, 3, 4, 5, 6, 7) }) { Text("每天") }
                TextButton(onClick = { days = setOf(1, 2, 3, 4, 5) }) { Text("工作日") }
                TextButton(onClick = { days = setOf(6, 7) }) { Text("周末") }
            }
        }

        SectionCard(title = "生效结果", subtitle = null) {
            val end = (startMinutes + durationMinutes) % Schedule.MINUTES_PER_DAY
            Text(
                text = buildString {
                    append(startMinutes.toHhMm())
                    append(" 开始锁定，")
                    append(if (startMinutes + durationMinutes > Schedule.MINUTES_PER_DAY) "次日 " else "当天 ")
                    append(end.toHhMm())
                    append(" 自动解除。\n")
                    append("生效于：")
                    append(days.describeDays())
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val base = existing ?: Schedule(id = Repos.schedules.nextId())
                    val saved = base.copy(
                        startMinutes = startMinutes,
                        durationMinutes = durationMinutes,
                        days = days,
                        enabled = true
                    )
                    persistSchedule(context, saved)
                    onDone()
                },
                enabled = days.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(androidx.compose.ui.res.stringResource(R.string.action_save))
            }

            if (!isNew) {
                OutlinedButton(onClick = {
                    AlarmScheduler.cancel(context, scheduleId)
                    Repos.schedules.delete(scheduleId)
                    onDone()
                }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_delete))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialMinutes = startMinutes,
            onDismiss = { showTimePicker = false },
            onConfirm = { minutes ->
                startMinutes = minutes
                showTimePicker = false
            }
        )
    }
}

private fun persistSchedule(context: Context, schedule: Schedule) {
    Repos.schedules.upsert(schedule)
    // 先清掉旧闹钟，再按新配置重排，避免改了时间之后旧闹钟还在
    AlarmScheduler.cancel(context, schedule.id)
    AlarmScheduler.scheduleNextStart(context, schedule)
}

@Composable
private fun TimePickerDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    // 故意不用 Material3 的 TimePicker + rememberTimePickerState：
    // 那是实验性 API，签名在 1.2 / 1.3 / 1.4 之间变过，是编译期最不确定的一处。
    // 这里用 FilterChip 拼一个选择器，全部是稳定 API，而且锁机场景本来就是整点/半点居多。
    var hour by remember { mutableIntStateOf(initialMinutes / 60) }
    var minute by remember { mutableIntStateOf((initialMinutes % 60) / 5 * 5) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择起始时间") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%02d:%02d".format(hour, minute),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "小时",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    (0..23).forEach { h ->
                        FilterChip(
                            selected = h == hour,
                            onClick = { hour = h },
                            label = { Text("%02d".format(h)) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "分钟",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    (0..55 step 5).forEach { m ->
                        FilterChip(
                            selected = m == minute,
                            onClick = { minute = m },
                            label = { Text("%02d".format(m)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour * 60 + minute) }) {
                Text(androidx.compose.ui.res.stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
            }
        }
    )
}
