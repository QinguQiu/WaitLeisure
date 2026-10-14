package com.selflock.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selflock.data.Repos
import com.selflock.data.WhitelistGroup
import com.selflock.model.AppInfo
import com.selflock.model.toHhMm

/** 分组框的尺寸。高度固定，「＋」空框才能和已有分组的框对齐。 */
private val GROUP_CARD_WIDTH = 104.dp
private val GROUP_CARD_HEIGHT = 96.dp

/** 框里最多摆几个图标（再多就挤了）。 */
private const val GROUP_CARD_ICONS = 4

/**
 * 白名单编辑。
 *
 * 分两块：
 *   · 上：分时段分组 —— 一个框代表一个分组。空框显示「＋」用来新建；
 *     已建好的框里直接摆这个分组的应用图标，一眼看出这组装了啥。
 *   · 下：全局白名单 —— 任何时段都放行的应用。
 *
 * 只列出「有桌面图标」的应用 —— 这也正是用户能点开、因此需要被拦的那些。
 * 输入法、系统桌面之类没有桌面图标的东西不需要出现在这里。
 */
@Composable
fun WhitelistScreen() {
    val whitelist by Repos.whitelist.packages.collectAsState()
    val groups by Repos.groups.groups.collectAsState()
    var query by remember { mutableStateOf("") }
    var showGroupEdit by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<WhitelistGroup?>(null) }

    val apps by produceState(initialValue = emptyList<AppInfo>()) {
        value = Repos.installedApps.loadLaunchableApps()
    }

    // 列表一出来就后台把图标/名字全部预热，避免第一次滚动时边滚边加载
    LaunchedEffect(apps) {
        if (apps.isNotEmpty()) {
            prefetchAppIcons(apps.map { it.packageName })
        }
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(10.dp))

        // ------------------------------------------------ 分时段分组
        Text(
            text = "分时段分组",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "一个框 = 一套应用 + 一个生效时段，到点自动切换。全局白名单始终生效。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(groups, key = { it.id }) { group ->
                GroupCard(
                    group = group,
                    onClick = {
                        editingGroup = group
                        showGroupEdit = true
                    }
                )
            }
            item {
                AddGroupCard(
                    onClick = {
                        editingGroup = null
                        showGroupEdit = true
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ------------------------------------------------ 全局白名单
        Text(
            text = "全局白名单 · 已选 ${whitelist.size} 个",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "锁定期间，只有这里勾选的应用可以正常使用。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("搜索应用") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        if (apps.isEmpty()) {
            EmptyHint("正在读取应用列表……")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = whitelist.contains(app.packageName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { Repos.whitelist.toggle(app.packageName) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app.packageName, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = app.label,
                                fontSize = 15.sp,
                                maxLines = 1
                            )
                            Text(
                                text = app.packageName,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { Repos.whitelist.toggle(app.packageName) }
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showGroupEdit) {
        GroupEditDialog(
            editing = editingGroup,
            onDismiss = { showGroupEdit = false },
            onSave = { group ->
                Repos.groups.save(group)
                showGroupEdit = false
            },
            onDelete = { id ->
                Repos.groups.remove(id)
                showGroupEdit = false
            }
        )
    }
}

/**
 * 一个分组框。
 *
 * 名字 + 生效时段 + 这个分组里的应用图标（最多 4 个，装不下的用 +N 表示）。
 * 点了就进编辑弹窗 —— 删除也放在那个弹窗里，避免框上再挂一堆小按钮。
 */
@Composable
private fun GroupCard(group: WhitelistGroup, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(GROUP_CARD_WIDTH)
            .height(GROUP_CARD_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(9.dp)
    ) {
        Text(
            text = group.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (group.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Text(
            text = "${group.startMinute.toHhMm()} - ${group.endMinute.toHhMm()}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.weight(1f))

        if (group.packages.isEmpty()) {
            Text(
                text = "还没选应用",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                group.packages.take(GROUP_CARD_ICONS).forEach { pkg ->
                    AppIcon(packageName = pkg, size = 22.dp)
                }
                val more = group.packages.size - GROUP_CARD_ICONS
                if (more > 0) {
                    Box(
                        modifier = Modifier.size(22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+$more",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 空的「＋」框：点一下新建一个分组。 */
@Composable
private fun AddGroupCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(GROUP_CARD_WIDTH)
            .height(GROUP_CARD_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "＋",
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "新建分组",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 把 "HH:mm" 解析成一天里的第几分钟；格式不对返回 null。 */
private fun parseHhMm(s: String): Int? {
    val parts = s.split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

@Composable
private fun GroupEditDialog(
    editing: WhitelistGroup?,
    onDismiss: () -> Unit,
    onSave: (WhitelistGroup) -> Unit,
    onDelete: (Long) -> Unit
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
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "跨午夜直接写，例如 22:00 - 06:00。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "选择该时段可用的应用（已选 ${selected.size} 个）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.height(260.dp)) {
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

                if (editing != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onDelete(editing.id) }) {
                        Text("删除这个分组", color = MaterialTheme.colorScheme.error)
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
