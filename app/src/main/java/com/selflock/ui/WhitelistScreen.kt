package com.selflock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selflock.data.Repos
import com.selflock.model.AppInfo
import com.selflock.model.toHhMm

/** 一个框里最多摆多少个图标，再多用 +N 收尾。 */
private const val BOX_MAX_ICONS = 32

/**
 * 白名单页。
 *
 * 整页只有「框」：最上面一个全局白名单框，下面若干个分时段分组框。
 * 每个分组框占一整行 —— 横着挤一排的话，名字和时段都得截断，看不出这组是干嘛的。
 *
 * 点任意一个框进应用选择页：选应用的地方统一在那一页，这里只负责总览。
 */
@Composable
fun WhitelistScreen(
    onOpenGlobal: () -> Unit,
    onOpenGroup: (Long) -> Unit,
    onNewGroup: () -> Unit
) {
    val whitelist by Repos.whitelist.packages.collectAsState()
    val groups by Repos.groups.groups.collectAsState()

    val apps by produceState(initialValue = emptyList<AppInfo>()) {
        value = Repos.installedApps.loadLaunchableApps()
    }
    // 提前把图标备好，进选择页时不再边滚边加载
    LaunchedEffect(apps) {
        if (apps.isNotEmpty()) prefetchAppIcons(apps.map { it.packageName })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ------------------------------------------------ 全局白名单（最上）
        WhitelistBox(
            title = "全局白名单",
            subtitle = "任何时段都可用 · 已选 ${whitelist.size} 个应用",
            packages = whitelist,
            onClick = onOpenGlobal
        )

        // ------------------------------------------------ 分时段分组
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "分时段分组",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "每组 = 一套应用 + 一个生效时段，到点自动切换",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onNewGroup) { Text("＋ 新建分组") }
        }

        if (groups.isEmpty()) {
            Text(
                text = "还没有分组。点右上角「＋ 新建分组」建一个。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        } else {
            groups.forEach { group ->
                WhitelistBox(
                    title = group.name,
                    subtitle = "${group.startMinute.toHhMm()} - ${group.endMinute.toHhMm()} · " +
                        "${group.packages.size} 个应用" + if (group.enabled) "" else " · 已停用",
                    packages = group.packages,
                    dimmed = !group.enabled,
                    onClick = { onOpenGroup(group.id) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 白名单「框」：标题 + 说明 + 这组应用的全部图标（自动换行，装不下的用 +N）。
 *
 * 全局白名单和每个分组共用这一个组件 —— 两处显示形式保持一致，
 * 一眼就能看出「哪些框在用哪些应用」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhitelistBox(
    title: String,
    subtitle: String,
    packages: Set<String>,
    onClick: () -> Unit,
    dimmed: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (dimmed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        if (packages.isEmpty()) {
            Text(
                text = "还没选应用，点这个框去挑",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val shown = packages.take(BOX_MAX_ICONS)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                shown.forEach { pkg ->
                    AppIcon(packageName = pkg, size = 34.dp)
                }
                val more = packages.size - shown.size
                if (more > 0) {
                    Box(
                        modifier = Modifier.size(34.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+$more",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
