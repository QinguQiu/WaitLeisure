package com.waitleisure.data

import android.content.Context

/**
 * 唯一的落盘入口。所有需要「进程被杀后依然生效」的状态都放这里。
 *
 * 特别注意：[lockedUntil] 必须持久化 —— 锁机期间用户可能强杀 App，
 * 但系统会重新拉起无障碍服务，那时必须还能读到「现在仍在锁定中」。
 */
class AppPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 白名单包名集合。 */
    var whitelist: Set<String>
        get() = sp.getStringSet(KEY_WHITELIST, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_WHITELIST, value).apply()

    /** 定时规则，JSON 数组字符串（用系统自带的 org.json，避免引入额外依赖）。 */
    var schedulesJson: String
        get() = sp.getString(KEY_SCHEDULES, "[]") ?: "[]"
        set(value) = sp.edit().putString(KEY_SCHEDULES, value).apply()

    /** PIN 的 SHA-256。为 null 表示还没设置过 PIN。 */
    var pinHash: String?
        get() = sp.getString(KEY_PIN_HASH, null)
        set(value) = sp.edit().putString(KEY_PIN_HASH, value).apply()

    /**
     * 锁定截止时间（epoch millis）。
     * - 0            = 未锁定
     * - Long.MAX_VALUE = 无限期锁定（只能靠 PIN 解除）
     * - 其它         = 到点自动解除
     */
    var lockedUntil: Long
        get() = sp.getLong(KEY_LOCKED_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_LOCKED_UNTIL, value).apply()

    /** 触发当前锁定的规则 id，-1 表示手动锁定。 */
    var activeScheduleId: Long
        get() = sp.getLong(KEY_ACTIVE_SCHEDULE, -1L)
        set(value) = sp.edit().putLong(KEY_ACTIVE_SCHEDULE, value).apply()

    /** 是否允许锁机时通过 PIN 解锁。false = 严格锁，锁机页不显示解锁按钮，只能等时间到。 */
    var allowPinUnlock: Boolean
        get() = sp.getBoolean(KEY_ALLOW_PIN_UNLOCK, true)
        set(value) = sp.edit().putBoolean(KEY_ALLOW_PIN_UNLOCK, value).apply()

    /** 立即锁机的常用时长（分钟，逗号分隔）。 */
    // 「立即锁机常用时长」（quick_durations）已删除：
    // 简单锁机现在从任务里选，时长由任务决定，第二份时长入口是冗余的。
    // 「自定义时长…」弹窗的预设改成代码里的固定值（SettingsScreen.quickDurationsList）。

    /** 锁机页壁纸颜色（ARGB int）。 */
    var wallpaperColor: Int
        get() = sp.getInt(KEY_WALLPAPER_COLOR, DEFAULT_WALLPAPER_COLOR)
        set(value) = sp.edit().putInt(KEY_WALLPAPER_COLOR, value).apply()

    /** 锁机页壁纸图片路径；null 表示用纯色。 */
    var wallpaperImagePath: String?
        get() = sp.getString(KEY_WALLPAPER_IMAGE, null)
        set(value) = sp.edit().putString(KEY_WALLPAPER_IMAGE, value).apply()

    // ------------------------------------------------------------------
    // 壁纸取景（用户在裁切页自己拖/缩放选出来的）
    //
    // 基准是 ContentScale.Crop —— 也就是「先按屏幕比例居中裁切填满」。
    // 用户的操作叠加在这个基准之上：
    //
    //   scale     缩放倍数，1.0 = 就用基准裁切，只能 ≥ 1
    //   offsetX/Y 平移量，单位是**屏幕宽/高的倍数**（不是像素）
    //
    // 存屏幕倍数而不是像素，是因为裁切预览框和真正的锁机页尺寸不同
    // （预览可能是 1080x2400，锁机页也是，但不能假设）；归一化之后
    // 两边用同一组数值算出来的画面就是同一个。
    //
    // 取值范围：|offset| ≤ (scale - 1) / 2 —— 再往外拖就露背景了。
    // 这个约束在裁切页和渲染处都用同一个函数算，见 wallpaperMaxOffset。
    // ------------------------------------------------------------------

    /** 壁纸缩放倍数，1.0 = 默认裁切。 */
    var wallpaperScale: Float
        get() = sp.getFloat(KEY_WALLPAPER_SCALE, 1f)
        set(value) = sp.edit().putFloat(KEY_WALLPAPER_SCALE, value).apply()

    /** 壁纸横向平移，单位是屏幕宽度的倍数。 */
    var wallpaperOffsetX: Float
        get() = sp.getFloat(KEY_WALLPAPER_OFFSET_X, 0f)
        set(value) = sp.edit().putFloat(KEY_WALLPAPER_OFFSET_X, value).apply()

    /** 壁纸纵向平移，单位是屏幕高度的倍数。 */
    var wallpaperOffsetY: Float
        get() = sp.getFloat(KEY_WALLPAPER_OFFSET_Y, 0f)
        set(value) = sp.edit().putFloat(KEY_WALLPAPER_OFFSET_Y, value).apply()

    /** 换壁纸时把取景一并重置，否则新图会套用旧图的缩放/偏移。 */
    fun resetWallpaperFraming() {
        wallpaperScale = 1f
        wallpaperOffsetX = 0f
        wallpaperOffsetY = 0f
    }

    /** 白名单分组，JSON 数组字符串（用系统自带的 org.json）。 */
    var whitelistGroupsJson: String
        get() = sp.getString(KEY_GROUPS, "[]") ?: "[]"
        set(value) = sp.edit().putString(KEY_GROUPS, value).apply()

    /**
     * 个性签名：锁机页上显示的一句话（比如「专注当下，别刷手机」）。
     *
     * 空字符串 = 不显示，锁机页布局保持原样 —— 不会留出一块空白。
     */
    var signature: String
        get() = sp.getString(KEY_SIGNATURE, "") ?: ""
        set(value) = sp.edit().putString(KEY_SIGNATURE, value.take(SIGNATURE_MAX_LENGTH)).apply()

    /** 任务（预设锁法）：JSON 数组。空字符串表示「还没播种过」。 */
    var tasksJson: String
        get() = sp.getString(KEY_TASKS, "") ?: ""
        set(value) = sp.edit().putString(KEY_TASKS, value).apply()

    /**
     * 当前锁定期间额外放行的白名单分组；-1 表示只用全局白名单。
     *
     * 分组本身不再带生效时段，所以「现在用哪一组」必须由锁机时记下来 ——
     * 定时规则触发时把它的 whitelistGroupId 写进来，解锁时清回 -1。
     */
    var activeWhitelistGroupId: Long
        get() = sp.getLong(KEY_ACTIVE_GROUP, -1L)
        set(value) = sp.edit().putLong(KEY_ACTIVE_GROUP, value).apply()

    /**
     * 首页两张卡片上「上次用的那个任务」。
     *
     * 存下来只是为了卡片上显示得出名字（否则只能写"点击选择"），
     * 让用户按下去之前就知道会发生什么。-1 表示还没选过，回退到该类别的第一个任务。
     */
    var lastSimpleTaskId: Long
        get() = sp.getLong(KEY_LAST_SIMPLE_TASK, -1L)
        set(value) = sp.edit().putLong(KEY_LAST_SIMPLE_TASK, value).apply()

    var lastPomodoroTaskId: Long
        get() = sp.getLong(KEY_LAST_POMODORO_TASK, -1L)
        set(value) = sp.edit().putLong(KEY_LAST_POMODORO_TASK, value).apply()

    /** 番茄钟配置：循环次数,专注分钟,短休息,长休息,几个循环后长休息。 */
    var pomodoroConfig: String
        get() = sp.getString(KEY_POMODORO_CONFIG, DEFAULT_POMODORO) ?: DEFAULT_POMODORO
        set(value) = sp.edit().putString(KEY_POMODORO_CONFIG, value).apply()

    /**
     * **本次运行**的番茄钟配置；空字符串表示「用用户设置页里那份」。
     *
     * 为什么单独存一份：「执行任务」时任务自带配置，如果直接写进 [pomodoroConfig]，
     * 用户跑一次定时的番茄钟任务，自己手配的参数就被悄悄改掉了。
     * 运行期用这一份，运行结束清空，用户那份始终不动。
     */
    var pomodoroRunConfig: String
        get() = sp.getString(KEY_POMODORO_RUN_CONFIG, "") ?: ""
        set(value) = sp.edit().putString(KEY_POMODORO_RUN_CONFIG, value).apply()

    /**
     * 番茄钟运行状态。空串 = 未运行；否则为 "组序号,相位,相位结束时刻"，
     * 例如 "2,focus,1791900000000"。相位取 focus / break。
     */
    var pomodoroState: String
        get() = sp.getString(KEY_POMODORO_STATE, "") ?: ""
        set(value) = sp.edit().putString(KEY_POMODORO_STATE, value).apply()

    /**
     * 白名单选择区是否用「大图标网格」显示。
     * 存盘是为了切 tab 回来不重置 —— NavHost 是 when 切换，离开就 dispose。
     */
    var whitelistGrid: Boolean
        get() = sp.getBoolean(KEY_WHITELIST_GRID, false)
        set(value) = sp.edit().putBoolean(KEY_WHITELIST_GRID, value).apply()

    /**
     * 「节假日数据该更新了」这条提醒，用户点了「知道了」的年份。
     * 0 = 还没关过。存年份是为了明年能再提醒一次，而不是永久闭嘴。
     */
    var holidayReminderDismissedYear: Int
        get() = sp.getInt(KEY_HOLIDAY_DISMISSED_YEAR, 0)
        set(value) = sp.edit().putInt(KEY_HOLIDAY_DISMISSED_YEAR, value).apply()

    private companion object {
        const val NAME = "waitleisure_prefs"
        const val KEY_WHITELIST = "whitelist"
        const val KEY_SCHEDULES = "schedules"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val KEY_ACTIVE_SCHEDULE = "active_schedule"
        const val KEY_ALLOW_PIN_UNLOCK = "allow_pin_unlock"
        // KEY_QUICK_DURATIONS 已随「立即锁机常用时长」一并删除
        const val KEY_SIGNATURE = "signature"
        const val KEY_WALLPAPER_COLOR = "wallpaper_color"
        const val KEY_WALLPAPER_IMAGE = "wallpaper_image"
        const val KEY_WALLPAPER_SCALE = "wallpaper_scale"
        const val KEY_WALLPAPER_OFFSET_X = "wallpaper_offset_x"
        const val KEY_WALLPAPER_OFFSET_Y = "wallpaper_offset_y"
        const val KEY_GROUPS = "whitelist_groups"
    const val KEY_TASKS = "tasks"
    const val KEY_ACTIVE_GROUP = "active_whitelist_group"
    const val KEY_LAST_SIMPLE_TASK = "last_simple_task"
    const val KEY_LAST_POMODORO_TASK = "last_pomodoro_task"
        const val KEY_POMODORO_CONFIG = "pomodoro_config"
    const val KEY_POMODORO_RUN_CONFIG = "pomodoro_run_config"
        const val KEY_POMODORO_STATE = "pomodoro_state"
        const val KEY_WHITELIST_GRID = "whitelist_grid"
        const val KEY_HOLIDAY_DISMISSED_YEAR = "holiday_dismissed_year"

        // DEFAULT_QUICK_DURATIONS 已随「立即锁机常用时长」一并删除
        const val DEFAULT_WALLPAPER_COLOR = 0xFF000000.toInt()
        const val DEFAULT_POMODORO = "4,25,5,15,2"
    }
}

/**
 * 归一化的可平移上限（单位是容器宽/高的倍数），永远 ≥ 0。
 *
 * 取景页的**手势**和**渲染**共用这一个函数，两处必须一致 ——
 * 否则就会出现「划到边缘还能继续划」：渲染层收敛了，但手势累加的值没有，
 * 于是反方向划时得先把超量的部分抵消掉，图片才肯动（用户反馈）。
 */
fun wallpaperMaxOffsetFraction(
    scale: Float,
    imageWidth: Int,
    imageHeight: Int,
    containerWidth: Float,
    containerHeight: Float
): Pair<Float, Float> {
    if (imageWidth <= 0 || imageHeight <= 0 || containerWidth <= 0f || containerHeight <= 0f) {
        return 0f to 0f
    }
    val iw = imageWidth.toFloat()
    val ih = imageHeight.toFloat()

    // 覆盖比例：取较大的那个，保证两个方向都盖住容器
    val crop = maxOf(containerWidth / iw, containerHeight / ih)
    val safeScale = scale.coerceAtLeast(1f)

    val width = iw * crop * safeScale
    val height = ih * crop * safeScale

    val maxTxPx = ((width - containerWidth) / 2f).coerceAtLeast(0f)
    val maxTyPx = ((height - containerHeight) / 2f).coerceAtLeast(0f)

    return (maxTxPx / containerWidth) to (maxTyPx / containerHeight)
}

/**
 * 壁纸取景的实际几何结果（都是像素）。
 *
 *   widthPx / heightPx  图片该被画成多大
 *   txPx / tyPx         相对容器中心的偏移
 */
data class WallpaperFrame(
    val widthPx: Float,
    val heightPx: Float,
    val txPx: Float,
    val tyPx: Float
)

/**
 * 给定图片、容器、缩放和归一化偏移，算出图片该画多大、偏多少。
 *
 * ## 为什么不能沿用 ContentScale.Crop
 *
 * 一开始是「Image 用 Crop 铺满，再套一层 graphicsLayer 做 scale/translation」。
 * 结果是**一左右滑动就露黑边**（用户反馈），原因在绘制边界：
 *
 *   Crop 虽然按「覆盖」比例把图放大到超出容器，但那部分**画不出来** ——
 *   Image 只在自己的布局边界内绘制。所以图层内容实际就只有容器那么大，
 *   一平移立刻露背景。
 *
 * 正确做法是把 Image 的**布局尺寸本身**设成裁切后的尺寸，再在容器里平移。
 * 这个函数负责算出那个尺寸和偏移：
 *
 *   crop = max(容器宽 / 图宽, 容器高 / 图高)      // 覆盖比例，保证不漏
 *   画出来的尺寸 = 图尺寸 × crop × scale
 *   可平移上限   = (画出来的尺寸 - 容器尺寸) / 2   // 再多就露背景
 *
 * 取景页和锁机页渲染共用这一个函数 —— 预览里能拖到哪，锁机页就显示到哪，
 * 两边都不会露黑边。
 * （放成顶层函数而不是 AppPrefs 的伴生对象里：那个 companion 是 private 的，
 * 外面 ui / lock 包访问不到，编译会报 Cannot access 'companion object'。）
 */
fun computeWallpaperFrame(
    imageWidth: Int,
    imageHeight: Int,
    containerWidth: Float,
    containerHeight: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float
): WallpaperFrame {
    if (imageWidth <= 0 || imageHeight <= 0 || containerWidth <= 0f || containerHeight <= 0f) {
        return WallpaperFrame(containerWidth, containerHeight, 0f, 0f)
    }
    val iw = imageWidth.toFloat()
    val ih = imageHeight.toFloat()

    // 覆盖比例：取较大的那个，保证两个方向都盖住容器
    val crop = maxOf(containerWidth / iw, containerHeight / ih)
    val safeScale = scale.coerceAtLeast(1f)

    val width = iw * crop * safeScale
    val height = ih * crop * safeScale

    // 平移上限和手势用的是同一个函数，两处必须一致
    val (maxX, maxY) = wallpaperMaxOffsetFraction(
        scale = safeScale,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        containerWidth = containerWidth,
        containerHeight = containerHeight
    )

    return WallpaperFrame(
        widthPx = width,
        heightPx = height,
        txPx = offsetX.coerceIn(-maxX, maxX) * containerWidth,
        tyPx = offsetY.coerceIn(-maxY, maxY) * containerHeight
    )
}

/**
 * 个性签名的长度上限。
 *
 * 允许换行（设置页里是多行输入框），所以给到 80 字 ——
 * 40 字在单行时代够用，多行就不够了。
 *
 * 放成**顶层常量**而不是 AppPrefs 的伴生对象里：那个 companion 是 private 的，
 * ui 包访问不到 —— 编译会报 Cannot access 'companion object'。
 * 这个坑踩过两次了（另一次是 wallpaperMaxOffset）。
 */
const val SIGNATURE_MAX_LENGTH = 80