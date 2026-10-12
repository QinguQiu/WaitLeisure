package com.selflock.data

import android.content.Context

/**
 * 极简服务定位器。
 *
 * 之所以不用 Hilt/Koin：L1 只有 5 个依赖，引入 DI 框架的收益抵不过它带来的构建复杂度。
 * 无障碍服务和广播接收器可能在 Application 之后才被拉起，所以统一走 ensure() 兜底。
 */
object Repos {

    @Volatile
    private var initialized = false

    lateinit var prefs: AppPrefs
        private set

    lateinit var whitelist: WhitelistRepository
        private set

    lateinit var schedules: ScheduleRepository
        private set

    lateinit var lockState: LockStateRepository
        private set

    lateinit var installedApps: InstalledAppsRepository
        private set

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        prefs = AppPrefs(app)
        whitelist = WhitelistRepository(prefs)
        schedules = ScheduleRepository(prefs)
        lockState = LockStateRepository(prefs)
        installedApps = InstalledAppsRepository(app)
        initialized = true
    }

    /** 在服务/接收器里访问仓储前先调一次，避免 Application 尚未走完 onCreate。 */
    fun ensure(context: Context) {
        if (!initialized) init(context)
    }
}
