package com.selflock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 极简路由器。
 *
 * 刻意不用 androidx.navigation：
 *  1. 本 App 只有 4 个页面，为它多引一个依赖不划算；
 *  2. 更重要的是依赖越少，跨 Compose 大版本的编译风险越小。
 *
 * 用 when 切换页面还有个正好合用的副作用：
 * 离开的页面会被 dispose，回来时重新进入组合，
 * 于是 HomeScreen 里的 LaunchedEffect(Unit) 会重跑 —— 权限状态自动刷新。
 *
 * 导航图标用 BMP 区字符（\uXXXX 转义），不依赖 material-icons 那个 artifact，
 * 同时保证源码本身保持纯 ASCII，避免任何编码问题。
 */
private enum class Screen { Home, Whitelist, Schedules, ScheduleEdit, Settings, PermissionEditor }

private data class Tab(val screen: Screen, val label: String, val glyph: String)

private val TABS = listOf(
    Tab(Screen.Home, "\u9996\u9875", "\u2302"),
    Tab(Screen.Whitelist, "\u767d\u540d\u5355", "\u2630"),
    Tab(Screen.Schedules, "\u5b9a\u65f6", "\u23F0"),
    Tab(Screen.Settings, "\u8bbe\u7f6e", "\u2699")
)

@Composable
fun SelfLockNavHost() {
    var screen by remember { mutableStateOf(Screen.Home) }
    var editingId by remember { mutableStateOf(-1L) }

    val showBottomBar = screen == Screen.Home ||
        screen == Screen.Whitelist ||
        screen == Screen.Schedules ||
        screen == Screen.Settings

    // 二级页需要拦返回键；其余页面交给系统默认行为。
    // 「加固权限」是从首页右上角进来的二级页，返回时回首页。
    BackHandler(enabled = screen == Screen.ScheduleEdit || screen == Screen.PermissionEditor) {
        screen = if (screen == Screen.PermissionEditor) Screen.Home else Screen.Schedules
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = screen == tab.screen,
                            onClick = { if (screen != tab.screen) screen = tab.screen },
                            icon = { Text(tab.glyph) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (screen) {
                Screen.Home -> HomeScreen(onOpenPermissions = { screen = Screen.PermissionEditor })

                Screen.PermissionEditor -> PermissionScreen(onBack = { screen = Screen.Home })

                Screen.Whitelist -> WhitelistScreen()

                Screen.Schedules -> ScheduleListScreen(
                    onEdit = { id ->
                        editingId = id
                        screen = Screen.ScheduleEdit
                    }
                )

                Screen.ScheduleEdit -> ScheduleEditScreen(
                    scheduleId = editingId,
                    onDone = { screen = Screen.Schedules }
                )

                Screen.Settings -> SettingsScreen()
            }
        }
    }
}
