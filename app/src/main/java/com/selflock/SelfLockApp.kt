package com.selflock

import android.app.Application
import com.selflock.data.Repos
import com.selflock.util.Notifications

class SelfLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 仓储是单例，越早初始化越好：无障碍服务和广播接收器可能在 Activity 之前被系统拉起。
        Repos.init(this)
        Notifications.createChannels(this)
    }
}
