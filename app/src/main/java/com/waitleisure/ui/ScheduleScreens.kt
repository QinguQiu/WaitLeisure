package com.waitleisure.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waitleisure.R
import com.waitleisure.alarm.AlarmScheduler
import com.waitleisure.alarm.ScheduleRunner
import com.waitleisure.data.Repos
import com.waitleisure.lock.Pomodoro
import com.waitleisure.model.DAY_LABELS
import com.waitleisure.model.HolidayCalendar
import com.waitleisure.model.HolidayMode
import com.waitleisure.model.Schedule
import com.waitleisure.model.durationBetween
import com.waitleisure.model.TaskKind
import com.waitleisure.model.activeWindowEndMillis
import com.waitleisure.model.describeDays
import com.waitleisure.model.describeHolidayMode
import com.waitleisure.model.toDurationText
import com.waitleisure.model.toHhMm

/** 定时规则的时长上限。24 小时 —— 规则本来就是每天重复的，再长没有意义。 */

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
            // 长按条目拖动排序。顺序持久化在 JSON 数组里，dragState 换位后直接存回。
            val listState = rememberLazyListState()
            val dragState = rememberDragDropState(listState) { from, to ->
                Repos.schedules.move(from, to)
            }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.dragContainer(dragState)
            ) {
                itemsIndexed(schedules, key = { _, s -> s.id }) { index, schedule ->
                    ScheduleCard(
                        modifier = Modifier.draggableItem(dragState, index),
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
                        onCopy = {
                // 复制一条规则：同样的时间/重复/任务/白名单，新 id。
                // 不自动启用 —— 原规则和新副本同时生效会重复上锁，
                // 让用户改完时间再保存（保存即启用）更安全。
                Repos.schedules.upsert(
                    schedule.copy(id = Repos.schedules.nextId(), enabled = false)
                )
            }
            , onDelete = { pendingDelete = schedule }
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
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
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
        // modifier 由调用方传入 —— 拖动排序时它带着 graphicsLayer 平移
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (dim) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(
            // 停用的卡片底色是半透明灰，再压阴影会显脏 —— 只有启用的才抬起来。
            // 卡片的**外框尺寸**两边一致，所以视觉上仍然对齐。
            defaultElevation = if (dim) 0.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                // 名字单独占一行。
                //
                // ⚠️ 曾经图省事，把名字用字符串拼进下面那个 24sp 的 Text 里 ——
                // 结果名字继承了时刻的粗体大字，把整行宽度吃光，
                // 右边「任务 · 番茄钟」被挤成一字一行（用户截图反馈）。
                if (schedule.name.isNotBlank()) {
                    Text(
                        text = schedule.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = titleColor
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = schedule.startMinutes.toHhMm(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color = titleColor
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // 执行任务时「结束时间」不参与 —— 时长由任务决定。
                        // 之前这里仍显示规则自己的时长，和任务同时"生效"，看着像两套规则打架。
                        text = if (schedule.usesTask) {
                            Repos.tasks.byId(schedule.taskId)?.let { "任务 · " + it.name } ?: "任务（已删除）"
                        } else {
                            "持续 " + schedule.durationMinutes.toDurationText()
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(schedule.days.describeDays())
                        schedule.describeHolidayMode()?.let {
                            append("（")
                            append(it)
                            append("）")
                        }
                        // 执行任务时没有「解除时刻」可言（番茄钟按相位走，简单锁机按任务时长）
                        if (!schedule.usesTask) {
                            if (schedule.crossesMidnight) {
                                append(" · 次日 ")
                            } else {
                                append(" · ")
                            }
                            append(schedule.endMinutes.toHhMm())
                            append(" 解除")
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = schedule.enabled, onCheckedChange = onToggle)
            TextButton(onClick = onCopy) {
                Text("复制")
            }
            TextButton(onClick = onDelete) {
                Text("\u5220\u9664")
            }
        }
    }
}

// ---------------------------------------------------------------- 编辑

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditScreen(scheduleId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val existing = remember(scheduleId) { Repos.schedules.byId(scheduleId) }
    val isNew = existing == null

    var startMinutes by remember {
        mutableIntStateOf(existing?.startMinutes ?: nowMinutes())
    }

    // 「结束时间」而不是「设时长」。
    //
    // 用户更习惯「23:00 锁到 07:00」这种说法 —— 让他去心算「8 小时」是负担，
    // 而且跨天的时候（23:00 开始、8 小时后）很容易设错。
    //
    // 模型里存的**仍然是 durationMinutes**，这里只是按结束时刻来编辑：
    // 保存时用 durationBetween() 反算回去。这样数据格式不变、不用迁移，
    // 定时触发和闹钟那套逻辑一行都不用动。
    var endMinutes by remember {
        mutableIntStateOf(
            existing?.let { (it.startMinutes + it.durationMinutes) % Schedule.MINUTES_PER_DAY }
                ?: ((startMinutes + DEFAULT_DURATION_MINUTES) % Schedule.MINUTES_PER_DAY)
        )
    }
    var days by remember { mutableStateOf(existing?.days ?: setOf(1, 2, 3, 4, 5, 6, 7)) }
    var holidayMode by remember { mutableStateOf(existing?.holidayMode ?: HolidayMode.IGNORE) }

    // 规则的名字。可以留空 —— 留空时列表就只显示时刻，和以前一样。
    var scheduleName by remember { mutableStateOf(existing?.name ?: "") }
    // 严格模式：-1 跟随全局 / 0 强制严格 / 1 强制允许
    var strictMode by remember {
        mutableIntStateOf(existing?.strictMode ?: Schedule.STRICT_FOLLOW_GLOBAL)
    }

    // 「执行任务」模式：锁什么由任务决定，本页不再设时长
    val tasks = remember { Repos.tasks.current() }
    var useTask by remember { mutableStateOf(existing?.usesTask ?: false) }
    var taskId by remember { mutableLongStateOf(existing?.taskId ?: (tasks.firstOrNull()?.id ?: -1L)) }

    // 本次锁机额外放行哪个白名单分组；-1 = 只用全局
    val groups = remember { Repos.groups.current() }
    var groupId by remember { mutableLongStateOf(existing?.whitelistGroupId ?: Schedule.NO_GROUP) }

    // 选中项实际会放行哪些应用 —— 下面要把图标全部铺出来
    val globalWhitelist = Repos.whitelist.current()
    val selectedPackages = if (groupId < 0) {
        globalWhitelist
    } else {
        groups.firstOrNull { it.id == groupId }?.packages ?: emptySet()
    }
    // 图标是异步加载的，先备好，铺出来时就不会一个个闪
    LaunchedEffect(groupId) {
        prefetchAppIcons(selectedPackages.toList())
    }

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

        // 名字放在最上面：先给这条规则起个名，后面看列表才认得出。
        SectionCard(
            title = "名称",
            subtitle = if (scheduleName.isBlank()) "可以留空" else scheduleName
        ) {
            OutlinedTextField(
                value = scheduleName,
                onValueChange = { scheduleName = it.take(20) },
                placeholder = { Text("比如：早起、睡前、午休") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        SectionCard(
            title = "严格模式",
            subtitle = when (strictMode) {
                Schedule.STRICT_ON -> "锁定期间无法解除"
                Schedule.STRICT_OFF -> "允许密码解锁"
                else -> "跟随全局设置"
            }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { strictMode = Schedule.STRICT_FOLLOW_GLOBAL }) {
                    Text(
                        text = "跟随全局",
                        color = if (strictMode == Schedule.STRICT_FOLLOW_GLOBAL) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                TextButton(onClick = { strictMode = Schedule.STRICT_ON }) {
                    Text(
                        text = "严格",
                        color = if (strictMode == Schedule.STRICT_ON) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                TextButton(onClick = { strictMode = Schedule.STRICT_OFF }) {
                    Text(
                        text = "允许解锁",
                        color = if (strictMode == Schedule.STRICT_OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
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

        // 「锁多久」有两种给法：本规则自己设，或者交给一个现成的任务。
        // 用任务的好处是常用锁法只配一次、多条规则复用。
        SectionCard(
            title = "锁什么",
            subtitle = if (useTask) "执行任务" else "本规则自己定起止时刻"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !useTask,
                    onClick = { useTask = false },
                    label = { Text("结束时间") }
                )
                FilterChip(
                    selected = useTask,
                    onClick = {
                        // 一个任务都没有时不要切过去 —— 否则会卡在一个没有选项的空页面
                        if (tasks.isNotEmpty()) useTask = true
                    },
                    label = { Text("执行任务") }
                )
            }

            if (useTask) {
                Spacer(Modifier.height(10.dp))
                if (tasks.isEmpty()) {
                    Text(
                        text = "还没有任务。去「设置 → 任务」里新建，或直接改用「设定时长」。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    tasks.forEach { t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { taskId = t.id }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = taskId == t.id,
                                onClick = { taskId = t.id }
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(t.name, fontSize = 13.sp)
                                Text(
                                    text = t.describe(),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "任务里只有锁法、不含时间 —— 什么时候执行由上面的「起始时间」决定。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(Modifier.height(10.dp))
                // 复用「时/分」两列选择器，但上限放到 1439 ——
                // 于是它就是一个 0:00～23:59 的**时刻**选择器，不用另写一个组件。
                DurationPicker(
                    totalMinutes = endMinutes,
                    maxMinutes = Schedule.MINUTES_PER_DAY - 1,
                    minMinutes = 0,
                    onSelect = { endMinutes = it }
                )

                // 结束时刻定了，时长是算出来的 —— 这里说清楚，免得用户以为还要再设一次
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "从 ${startMinutes.toHhMm()} 锁到 ${endMinutes.toHhMm()}，" +
                        "共 ${durationBetween(startMinutes, endMinutes).toDurationText()}" +
                        if (endMinutes <= startMinutes) "（跨到次日）" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 常用档位改成「点一下就跳到对应的结束时刻」。
                // 标签显示的是时刻本身（用户现在关心的就是这个），不是时长。
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    DURATION_PRESETS.forEach { preset ->
                        val target = (startMinutes + preset) % Schedule.MINUTES_PER_DAY
                        AssistChip(
                            onClick = { endMinutes = target },
                            label = { Text(target.toHhMm()) }
                        )
                    }
                }
            }
        }

        // 分组不再自带生效时段，「什么时候用哪一组」在这里说清楚。
        // 选项用 FlowRow —— 分组多了要能换行铺满，横向滚动会看不全。
        SectionCard(
            title = "白名单",
            subtitle = if (groupId < 0) "只用全局白名单" else "额外放行一个分组"
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = groupId < 0,
                    onClick = { groupId = Schedule.NO_GROUP },
                    label = { Text("全局（${globalWhitelist.size}）") }
                )
                groups.forEach { g ->
                    FilterChip(
                        selected = groupId == g.id,
                        onClick = { groupId = g.id },
                        label = { Text("${g.name}（${g.packages.size}）") }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = if (groupId < 0) "本次锁机放行的应用：" else "在全局白名单之外，额外放行：",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))

            // 把这一组应用**全部**铺出来，多行换行，不截断也不横滚 ——
            // 只看分组名根本想不起来里面到底放了哪几个应用，配错白名单就是这么来的。
            if (selectedPackages.isEmpty()) {
                Text(
                    text = if (groupId < 0) "全局白名单是空的。去「白名单」页里加几个。" else "这个分组还没有应用。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedPackages.sorted().forEach { pkg ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(52.dp)
                        ) {
                            AppIcon(packageName = pkg, size = 36.dp)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = rememberAppLabel(pkg),
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "全局白名单永远生效；选中某个分组时，本次锁机在全局之外**额外**放行那一组应用。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

        SectionCard(
            title = "节假日",
            subtitle = when (holidayMode) {
                HolidayMode.IGNORE -> "只看星期几，不看节假日"
                HolidayMode.SKIP_HOLIDAY -> "跳过法定节假日；调休上班日照锁"
                HolidayMode.ONLY_HOLIDAY -> "只在法定节假日锁，忽略星期几"
            }
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = holidayMode == HolidayMode.IGNORE,
                    onClick = { holidayMode = HolidayMode.IGNORE },
                    label = { Text("不看节假日") }
                )
                FilterChip(
                    selected = holidayMode == HolidayMode.SKIP_HOLIDAY,
                    onClick = { holidayMode = HolidayMode.SKIP_HOLIDAY },
                    label = { Text("跳过节假日") }
                )
                FilterChip(
                    selected = holidayMode == HolidayMode.ONLY_HOLIDAY,
                    onClick = { holidayMode = HolidayMode.ONLY_HOLIDAY },
                    label = { Text("只在节假日") }
                )
            }

            if (holidayMode != HolidayMode.IGNORE) {
                Spacer(Modifier.height(8.dp))
                val latest = HolidayCalendar.latestYear
                Text(
                    text = if (latest == null) {
                        "⚠️ 没有内置节假日数据，这个模式当前等同于只看星期几。"
                    } else {
                        "内置国务院公告数据，覆盖到 $latest 年。之后的年份会退化成只看星期几 —— " +
                            "放假安排每年 11 月才公布次年版本，届时更新 App 即可。"
                    },
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard(title = "生效结果", subtitle = null) {
            val selectedTask = if (useTask) tasks.firstOrNull { it.id == taskId } else null
            // 简单锁机任务才有确定时长；番茄钟按相位轮转，给不出单一结束时刻
            val effective = selectedTask?.takeIf { it.kind == TaskKind.SIMPLE_LOCK }?.durationMinutes
                ?: durationBetween(startMinutes, endMinutes)
            val end = (startMinutes + effective) % Schedule.MINUTES_PER_DAY

            Text(
                text = buildString {
                    append(startMinutes.toHhMm())
                    if (selectedTask?.kind == TaskKind.POMODORO) {
                        append(" 开始番茄钟：")
                        append(selectedTask.describe())
                        append("，按相位自动轮转。\n")
                    } else {
                        append(" 开始锁定，")
                        append(if (startMinutes + effective > Schedule.MINUTES_PER_DAY) "次日 " else "当天 ")
                        append(end.toHhMm())
                        append(" 自动解除。\n")
                    }
                    if (days.isEmpty()) {
                        append("单次：下一个 ")
                        append(startMinutes.toHhMm())
                        append(" 执行一次，之后自动停用。")
                    } else {
                        append("生效于：")
                        append(days.describeDays())
                        val hm = holidayMode
                        if (hm != HolidayMode.IGNORE) {
                            append("（")
                            append(
                                when (hm) {
                                    HolidayMode.SKIP_HOLIDAY -> "跳过法定节假日"
                                    else -> "仅法定节假日"
                                }
                            )
                            append("）")
                        }
                    }
                    if (groupId >= 0) {
                        append("\n额外放行分组：")
                        append(groups.firstOrNull { it.id == groupId }?.name ?: "（已删除）")
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
                        name = scheduleName.trim(),
                        strictMode = strictMode,
                        startMinutes = startMinutes,
                        durationMinutes = durationBetween(startMinutes, endMinutes),
                        days = days,
                        holidayMode = holidayMode,
                        enabled = true,
                        // 不用任务时写回 -1，避免上次的任务残留下来
                        taskId = if (useTask && taskId >= 0) taskId else Schedule.NO_TASK,
                        whitelistGroupId = groupId
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

    // 关键补漏：如果**此刻已经落在生效窗口里**，光排「下一次开始」是不够的 ——
    // 14:00 才建一条 13:00 开始、持续 2 小时的规则时，下一次开始是明天 13:00，
    // 今天这段还在进行的窗口会被整个跳过（实测踩到）。
    // 这时立刻执行，并只锁到窗口结束。
    //
    // 走 ScheduleRunner 而不是直接 LockController.lock —— 规则可能是「执行任务」，
    // 时长得由任务决定，两处各写一份迟早走歪。
    val task = if (schedule.usesTask) Repos.tasks.byId(schedule.taskId) else null

    // 窗口长度怎么算：
    //   执行任务 → 由**任务**决定，和规则自己的「结束时间」完全无关
    //     · 简单锁机任务：任务里的时长
    //     · 番茄钟任务  ：整轮跑完的总时长（焦点 + 休息）
    //   自带时长 → 用规则自己的
    //
    // 之前番茄钟任务会退回 schedule.durationMinutes —— 于是「结束时间」和
    // 「执行任务」两套同时在起作用，用户看到的窗口是错的。
    val windowMinutes = when {
        task == null -> schedule.durationMinutes
        task.kind == TaskKind.SIMPLE_LOCK -> task.durationMinutes
        else -> Pomodoro.totalMinutes(task.pomodoroConfig)
    }

    val windowEnd = schedule.activeWindowEndMillis(durationOverride = windowMinutes)
    if (windowEnd != null) {
        // 窗口起点 = 窗口终点 - 时长。补执行时番茄钟按这个起点算，
        // 已经过去的那段要扣掉，而不是重新从满时长开始。
        val startAt = windowEnd - windowMinutes * 60_000L
        val endAt = ScheduleRunner.execute(context, schedule, windowStartMillis = startAt)
        if (endAt != null) {
            AlarmScheduler.scheduleEnd(context, endAt, schedule.id)
        }
    }
}


private const val DEFAULT_DURATION_MINUTES = 8 * 60

/** 当前时刻的「分钟数」（0..1439）。新建定时规则时用它做起始时间。 */
private fun nowMinutes(): Int {
    val t = java.time.LocalTime.now()
    return t.hour * 60 + t.minute
}