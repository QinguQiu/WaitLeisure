package com.waitleisure.ui

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waitleisure.data.Repos
import com.waitleisure.data.WhitelistGroup
import com.waitleisure.model.AppInfo
import com.waitleisure.model.toHhMm

/** groupId < 0 表示在编辑「全局白名单」。 */
const val PICKER_GLOBAL: Long = -1L

/**
 * 应用选择页。
 *
 * 从白名单页点任意一个「框」进来：
 *   · 全局白名单框 → 编辑 [Repos.whitelist]
 *   · 分组框 → 编辑那个分组的应用集合
 *
 * 顶上左边返回、中间标题、**右上角是显示方式切换**（大图标网格 / 列表）。
 * 编辑分组时顶栏下面多一行，用来改名称或删除整个分组。
 */
@Composable
fun AppPickerScreen(groupId: Long, onBack: () -> Unit) {
    val global by Repos.whitelist.packages.collectAsState()
    val groups by Repos.groups.groups.collectAsState()
    val group: WhitelistGroup? = if (groupId < 0L) null else groups.firstOrNull { it.id == groupId }
    val selected: Set<String> = group?.packages ?: global

    var query by remember { mutableStateOf("") }
    // 显示方式存盘，切页回来不重置
    var gridMode by remember { mutableStateOf(Repos.prefs.whitelistGrid) }
    var showInfoEdit by remember { mutableStateOf(false) }

    val apps by produceState(initialValue = emptyList<AppInfo>()) {
        value = Repos.installedApps.loadLaunchableApps()
    }
    LaunchedEffect(apps) {
        if (apps.isNotEmpty()) prefetchAppIcons(apps.map { it.packageName })
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

    fun toggle(pkg: String) {
        val g = group
        if (g == null) {
            Repos.whitelist.toggle(pkg)
        } else {
            val next = if (pkg in g.packages) g.packages - pkg else g.packages + pkg
            Repos.groups.save(g.copy(packages = next))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(6.dp))

        // 顶栏：返回 · 标题 · 显示方式切换（右上角）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Column(Modifier.weight(1f)) {
                Text(
                    text = group?.name ?: "全局白名单",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (group == null) {
                        "全局白名单 · 任何时段都可用 · 已选 ${selected.size} 个"
                    } else {
                        "分组「${group.name}」· 已选 ${selected.size} 个"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                gridMode = !gridMode
                Repos.prefs.whitelistGrid = gridMode
            }) {
                Text(
                    text = if (gridMode) "\u2630 列表" else "\u25A6 大图标",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 分组才有：改名称、删除
        if (group != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showInfoEdit = true }) { Text("改名称") }
                TextButton(onClick = {
                    Repos.groups.remove(group.id)
                    onBack()
                }) {
                    Text("删除分组", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

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
        } else if (gridMode) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppGridItem(
                        app = app,
                        checked = app.packageName in selected,
                        onClick = { toggle(app.packageName) }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toggle(app.packageName) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app.packageName, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(text = app.label, fontSize = 15.sp, maxLines = 1)
                            Text(
                                text = app.packageName,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Checkbox(checked = checked, onCheckedChange = { toggle(app.packageName) })
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showInfoEdit && group != null) {
        GroupInfoDialog(
            group = group,
            onDismiss = { showInfoEdit = false },
            onSave = { updated ->
                Repos.groups.save(updated)
                showInfoEdit = false
            }
        )
    }
}

/**
 * 大图标网格里的一格：图标 + 名称。
 *
 * 选中态用图标右上角的对勾徽标表示 —— 网格里没有复选框的位置，
 * 而只靠「变灰/变色」区分选中与否在深浅色主题下都不够醒目。
 */
@Composable
fun AppGridItem(app: AppInfo, checked: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        Box {
            AppIcon(app.packageName, size = 56.dp)
            if (checked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✓", color = Color.White, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = app.label,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (checked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

/** 解析 "HH:mm" → 一天里的第几分钟；格式不对返回 null。 */
fun parseHhMm(s: String): Int? {
    val parts = s.split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/**
 * 只改分组的名称（应用集合在本页勾选）。
 *
 * 生效时段那一栏已经删掉：分组不再带时间限制，
 * 「什么时候用哪一组」由定时规则里的白名单选项决定。
 */
@Composable
private fun GroupInfoDialog(
    group: WhitelistGroup,
    onDismiss: () -> Unit,
    onSave: (WhitelistGroup) -> Unit
) {
    var name by remember { mutableStateOf(group.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分组信息") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("分组名") }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "分组只是一套应用，不带时间限制。\n" +
                        "想让它在某个时段生效，去「定时」里新建规则并选上这个分组。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(group.copy(name = name.trim())) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
