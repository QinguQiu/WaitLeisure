package com.waitleisure.data

import android.content.Context
import com.waitleisure.util.DiagLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * 壁纸库里的一张壁纸。
 *
 * 每张壁纸**各存一份取景参数**（缩放/偏移）—— 不同图片的构图不一样，
 * 共用一套参数会让新加的图跑到奇怪的位置。
 *
 * @param path 文件绝对路径（放在 `filesDir/wallpapers/` 下）
 * @param name 显示名，默认按加入顺序叫「壁纸 N」
 */
data class WallpaperEntry(
    val id: Long,
    val path: String,
    val name: String,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val addedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("path", path)
        put("name", name)
        put("scale", scale.toDouble())
        put("offsetX", offsetX.toDouble())
        put("offsetY", offsetY.toDouble())
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = WallpaperEntry(
            id = o.getLong("id"),
            path = o.getString("path"),
            name = o.optString("name", "壁纸"),
            scale = o.optDouble("scale", 1.0).toFloat(),
            offsetX = o.optDouble("offsetX", 0.0).toFloat(),
            offsetY = o.optDouble("offsetY", 0.0).toFloat(),
            addedAt = o.optLong("addedAt", 0L)
        )
    }
}

/** 壁纸选取模式。 */
object WallpaperMode {
    /** 固定用「当前壁纸」（默认；升级后行为不变）。 */
    const val SINGLE = "single"

    /** 每次锁机随机挑一张（避免和上次相同）。 */
    const val RANDOM = "random"

    /** 按库里的顺序依次取，用完回到第一张。 */
    const val SEQUENTIAL = "sequential"

    fun label(mode: String): String = when (mode) {
        RANDOM -> "随机"
        SEQUENTIAL -> "顺序"
        else -> "固定一张"
    }
}

/**
 * 壁纸库：多张壁纸 + 历史记录 + 轮换。
 *
 * ## 和旧版的关系
 *
 * 旧版只有一张 `filesDir/wallpaper.jpg`，路径存在 `prefs.wallpaperImagePath`。
 * 现在改成：所有壁纸放 `filesDir/wallpapers/`，库里每张一条记录；
 * **渲染层没有变** —— 锁机页仍然只读 `wallpaperImagePath` + 三个取景参数，
 * 库只是在**锁机开始前**把这四项挑好（见 [applyForLock]）。
 *
 * 这样渲染、取景页、锁机页一行都不用改，风险最小。
 *
 * ## 首次迁移
 *
 * 第一次跑新版时，如果库是空的但旧 `wallpaper.jpg` 还在，
 * 就把它搬进库（沿用旧的 scale/offset），并把模式设为「固定一张」——
 * 用户升级后看不出任何变化。
 */
class WallpaperLibrary(private val prefs: AppPrefs) {

    private fun dir(context: Context): File =
        File(context.filesDir, "wallpapers").apply { if (!exists()) mkdirs() }

    fun list(): List<WallpaperEntry> = runCatching {
        val raw = prefs.wallpaperLibraryJson
        if (raw.isBlank()) return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { WallpaperEntry.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList()).sortedBy { it.addedAt }

    private fun persist(list: List<WallpaperEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.wallpaperLibraryJson = arr.toString()
    }

    fun byId(id: Long): WallpaperEntry? = list().firstOrNull { it.id == id }

    /** 当前锁机正在用的那张。 */
    fun active(): WallpaperEntry? {
        val path = prefs.wallpaperImagePath ?: return null
        return list().firstOrNull { it.path == path }
    }

    /**
     * 把一张图加入库。
     *
     * @param source 已经落在磁盘上的图片（通常是取景页确认后的临时文件）
     * @return 新条目；失败返回 null
     */
    fun add(context: Context, source: File, name: String? = null): WallpaperEntry? = runCatching {
        val list = list()
        val id = (list.maxOfOrNull { it.id } ?: 0L) + 1L
        val dst = File(dir(context), "wp_$id.jpg")
        source.copyTo(dst, overwrite = true)
        val entry = WallpaperEntry(
            id = id,
            path = dst.absolutePath,
            name = name ?: "壁纸 $id",
            scale = 1f,
            offsetX = 0f,
            offsetY = 0f,
            addedAt = System.currentTimeMillis()
        )
        persist(list + entry)
        DiagLog.add("壁纸", "加入库：${entry.name}（id=$id，共 ${list.size + 1} 张）")
        entry
    }.getOrNull()

    /** 更新某张壁纸的取景参数。 */
    fun updateFraming(id: Long, scale: Float, offsetX: Float, offsetY: Float) {
        val list = list().map {
            if (it.id == id) it.copy(scale = scale, offsetX = offsetX, offsetY = offsetY) else it
        }
        persist(list)
    }

    /** 删除一张：索引和文件一起删。 */
    fun delete(context: Context, id: Long) {
        val list = list()
        val target = list.firstOrNull { it.id == id } ?: return
        runCatching { File(target.path).delete() }
        persist(list.filterNot { it.id == id })
        DiagLog.add("壁纸", "从库中删除：${target.name}")
        // 删掉的正好是当前用的 → 清空，让锁机页回退到纯色，
        // 否则会拿着一份已经不存在的路径去解码（兜底见 FramedWallpaper）
        if (prefs.wallpaperImagePath == target.path) {
            prefs.wallpaperImagePath = null
        }
        if (list.size - 1 == 0) prefs.wallpaperSeqCursor = 0
    }

    /**
     * 锁机开始时按模式挑一张。
     *
     * **必须在 lock() 里调，不能在渲染时随机** —— 否则每次重组都换一张。
     */
    fun applyForLock() {
        val list = list()
        if (list.isEmpty()) {
            // 库里没东西：保持现状（可能是纯色，或者旧路径还在）
            return
        }
        val current = active()
        val next = when (prefs.wallpaperMode) {
            WallpaperMode.RANDOM -> {
                if (list.size == 1) {
                    list.first()
                } else {
                    // 避免连续两次同一张
                    val pool = list.filter { it.id != current?.id }
                    pool[Random.nextInt(pool.size)]
                }
            }
            WallpaperMode.SEQUENTIAL -> {
                val idx = prefs.wallpaperSeqCursor.mod(list.size)
                prefs.wallpaperSeqCursor = (idx + 1) % list.size
                list[idx]
            }
            else -> current ?: list.first()
        }
        prefs.wallpaperImagePath = next.path
        prefs.wallpaperScale = next.scale
        prefs.wallpaperOffsetX = next.offsetX
        prefs.wallpaperOffsetY = next.offsetY
        DiagLog.add("壁纸", "本次锁机使用：${next.name}（模式 ${WallpaperMode.label(prefs.wallpaperMode)}）")
    }

    /**
     * 首次升级迁移：库里空 + 旧 wallpaper.jpg 存在 → 搬进来。
     *
     * 迁移后模式固定为「固定一张」，保证升级用户观感不变。
     */
    fun migrateIfNeeded(context: Context) {
        if (prefs.wallpaperLibraryMigrated) return
        prefs.wallpaperLibraryMigrated = true

        if (list().isNotEmpty()) return
        val old = File(context.filesDir, "wallpaper.jpg")
        if (!old.exists()) return

        val entry = add(context, old, "原壁纸") ?: return
        // 沿用旧的取景参数
        updateFraming(entry.id, prefs.wallpaperScale, prefs.wallpaperOffsetX, prefs.wallpaperOffsetY)
        prefs.wallpaperImagePath = entry.path
        prefs.wallpaperMode = WallpaperMode.SINGLE
        DiagLog.add("壁纸", "已把旧的 wallpaper.jpg 迁入库")
    }
}
