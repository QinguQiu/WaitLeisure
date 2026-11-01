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

        // 每次冷启动都把闹钟重排一遍：这是最省事也最不容易漏的状态修复点
        AlarmScheduler.rescheduleAll(this)

        // 番茄钟的相位靠前台服务每秒 tick 推进；App 被划掉过、或者服务被 MIUI 收掉，
        // 状态就会卡在某个**已经过期**的相位上 —— 表现是首页一直显示
        // 「休息中 · 0:00」或者「专注中 · 0:00」（用户实测）。
        // 冷启动时把过期的相位一次性补推完（tick 返回 true 表示推进了一格）。
        runCatching {
            var guard = 0
            while (Pomodoro.tick(this) && guard < 200) guard++
            if (guard > 0) DiagLog.add("番茄", "启动时补推了 $guard 个过期相位")
        }

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
