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
