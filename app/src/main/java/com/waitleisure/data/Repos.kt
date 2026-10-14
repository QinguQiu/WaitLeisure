package com.waitleisure.data

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

    lateinit var groups: WhitelistGroupRepository
        private set

    lateinit var tasks: TaskRepository
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
        groups = WhitelistGroupRepository(prefs)
        tasks = TaskRepository(prefs)
        initialized = true
    }

    /** 在服务/接收器里访问仓储前先调一次，避免 Application 尚未走完 onCreate。 */
    fun ensure(context: Context) {
        if (!initialized) init(context)
    }

    /**
     * 包名是否在「当前生效的白名单」里：全局白名单 ∪ **本次锁定选中的分组**。
     *
     * 注意这里不再看时间：分组已经没有生效时段了。
     * 「现在用哪一组」由锁机时写进 prefs 的 activeWhitelistGroupId 决定 ——
     * 定时规则触发时带上它的 whitelistGroupId，解锁时清回 -1。
     */
    fun isWhitelisted(packageName: String): Boolean {
        if (whitelist.contains(packageName)) return true
        val gid = prefs.activeWhitelistGroupId
        if (gid < 0) return false
        return groups.packagesOf(gid).contains(packageName)
    }

    /** 当前生效的完整白名单（全局 + 本次锁定选中的分组），给锁机页显示图标用。 */
    fun effectiveWhitelist(): Set<String> {
        val gid = prefs.activeWhitelistGroupId
        if (gid < 0) return whitelist.current()
        return whitelist.current() + groups.packagesOf(gid)
    }
}
