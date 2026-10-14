package com.waitleisure.data

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import com.waitleisure.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 读取「有桌面启动图标」的应用列表，作为白名单候选。
 *
 * 依赖 AndroidManifest 里的 <queries> 声明（MAIN + LAUNCHER）。
 * 刻意不用 QUERY_ALL_PACKAGES：权限更小，且不会触发 Google Play 的敏感权限审核。
 */
class InstalledAppsRepository(private val context: Context) {

    suspend fun loadLaunchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, 0)

        resolved.asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map {
                AppInfo(
                    packageName = it.packageName,
                    label = runCatching { it.loadLabel(pm).toString() }.getOrDefault(it.packageName)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** 在 Compose 里同步取图标用；取不到就返回 null，UI 退化为首字母方块。 */
    fun iconOf(packageName: String): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    fun labelOf(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    /** 该包名目前是否仍装在设备上（用户可能在锁机期间卸载了白名单应用）。 */
    fun isInstalled(packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
