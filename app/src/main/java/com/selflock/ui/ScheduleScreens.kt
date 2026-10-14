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

/** 定时规则的时长上限。24 小时 —— 规则本来就是每天重复的，再长没有意义。 */
private const val MAX_DURATION_MINUTES = 24 * 60

/** 常用时长的快捷入口。第一个是 1 分钟，调试锁机时最省事。 */
private val DURATION_PRESETS = listOf(1, 5, 30, 60, 120, 480, 1440)

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
    // 停用的规则整体变灰。
    // 单次规则触发一次后会被自动置为停用，走到这个状态 ——
    // 变灰让「这条已经用过了」一眼可见，把开关重新打开即可再次激活触发。
    val dim = !schedule.enabled
    val titleColor = if (dim) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (dim) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dim) 0.dp else 1.dp)
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
                        fontWeight = FontWeight.Medium,
                        color = titleColor
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

        SectionCard(title = "起始时间", subtitle = startMinutes.toHhMm()) {
            // 用和「立即锁机」同一个 DurationPicker：两列数字，精确到 1 分钟。
            // 之前这里是 FilterChip 拼的小时 + 5 分钟一档的分钟，选不到 :57 这种时刻。
            DurationPicker(
                totalMinutes = startMinutes,
                maxMinutes = Schedule.MINUTES_PER_DAY - 1,
                minMinutes = 0,
                onSelect = { startMinutes = it }
            )
        }

        SectionCard(title = "持续时长", subtitle = durationMinutes.toDurationText()) {
            DurationPicker(
                totalMinutes = durationMinutes,
                maxMinutes = MAX_DURATION_MINUTES,
                onSelect = { durationMinutes = it }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                DURATION_PRESETS.forEach { preset ->
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
                // 「每天」是个开关：全选中时再点一次就全部取消 = 单次
                TextButton(onClick = {
                    days = if (days.size == 7) emptySet() else setOf(1, 2, 3, 4, 5, 6, 7)
                }) {
                    Text(if (days.size == 7) "每天 ✓" else "每天")
                }
                TextButton(onClick = { days = setOf(1, 2, 3, 4, 5) }) { Text("工作日") }
                TextButton(onClick = { days = setOf(6, 7) }) { Text("周末") }
                TextButton(onClick = { days = emptySet() }) { Text("只锁一次") }
            }
            if (days.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "单次：只在下一个 ${startMinutes.toHhMm()} 锁一次，" +
                        "锁完自动停用（列表里变灰，开关重新打开即可再次触发）",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    if (days.isEmpty()) {
                        append("单次：下一个 ")
                        append(startMinutes.toHhMm())
                        append(" 锁一次，之后自动停用。")
                    } else {
                        append("生效于：")
                        append(days.describeDays())
                    }
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
}

private fun persistSchedule(context: Context, schedule: Schedule) {
    Repos.schedules.upsert(schedule)
    // 先清掉旧闹钟，再按新配置重排，避免改了时间之后旧闹钟还在
    AlarmScheduler.cancel(context, schedule.id)
    AlarmScheduler.scheduleNextStart(context, schedule)
}


