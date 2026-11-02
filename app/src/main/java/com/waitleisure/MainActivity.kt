package com.waitleisure

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.waitleisure.alarm.AlarmScheduler
import com.waitleisure.data.Repos
import com.waitleisure.lock.Pomodoro
import com.waitleisure.lock.LockScreenActivity
import com.waitleisure.util.DiagLog
import com.waitleisure.util.Permissions
import com.waitleisure.ui.AppNavHost

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Repos.ensure(this)

        // MIUI 从最近任务划掉 App 会把进程连无障碍绑定一起带走。
        // 如果用户用 adb 授过 WRITE_SECURE_SETTINGS，这里自动补回来 ——
        // 没授权时这一步什么都不做（返回 false），用户照旧去设置里开。
        val rebound = Permissions.rebindAccessibilityIfNeeded(this)
        if (rebound) DiagLog.add("权限", "启动时检测到无障碍已掉，已自动重绑")

        // 把旧版单张壁纸迁进壁纸库（只跑一次）
        runCatching { Repos.wallpapers.migrateIfNeeded(this) }

        // 每次冷启动都把闹钟重排一遍：这是最省事也最不容易漏的状态修复点
        AlarmScheduler.rescheduleAll(this)

        // 番茄钟的节奏靠前台服务每秒 tick 推进。进程被划掉之后没人推进，
        // 状态就会卡在一个**已经过期**的相位上。
        //
        // 早期版本在这里"补推"过期相位，结果很糟：用户休息时清掉后台，
        // 再打开就凭空多出一个"番茄钟进行中"并重新锁机（用户实测）。
        // 正确语义是 —— **进程被杀 = 这一段番茄作废**，直接清掉，
        // 让用户自己决定要不要重开。
        runCatching { Pomodoro.discardIfStale(this) }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1B5E9C),
                    secondary = Color(0xFF4A7BB5)
                )
            ) {
                AppNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Repos.ensure(this)
        Repos.lockState.refresh()

        // 锁定期间从桌面点进来，直接弹回锁机页
        if (Repos.lockState.isLockedNow()) {
            LockScreenActivity.show(this)
        }
    }
}
