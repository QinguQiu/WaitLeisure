package com.selflock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.selflock.alarm.AlarmScheduler
import com.selflock.data.Repos
import com.selflock.lock.LockScreenActivity
import com.selflock.ui.SelfLockNavHost

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Repos.ensure(this)
        // 每次冷启动都把闹钟重排一遍：这是最省事也最不容易漏的状态修复点
        AlarmScheduler.rescheduleAll(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1B5E9C),
                    secondary = Color(0xFF4A7BB5)
                )
            ) {
                SelfLockNavHost()
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
