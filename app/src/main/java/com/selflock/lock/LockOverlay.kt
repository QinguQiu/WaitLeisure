package com.selflock.lock

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.selflock.util.DiagLog

/**
 * 全屏锁机悬浮窗。
 *
 * ## 为什么锁机界面要做成悬浮窗，而不是 Activity
 *
 * 真机实测（Redmi K70 / HyperOS OS3.0 / Android 16）：按 Home 把锁机页切到后台之后，
 * MIUI 会拒绝本应用再拉起**任何** Activity，logcat 里是
 * `MIUILOG- Permission Denied Activity`，startActivity 恒返回 code=3
 * （只把 intent 投递给已在顶部的 Activity，不做任务切换）。这是应用级拦截，
 * 换 Activity、反复重试都无效。
 *
 * 而悬浮窗走的是 WindowManager.addView，**根本不经过 Activity 启动**，那道墙管不到。
 * 一旦盖上，按多少次 Home 都盖不住它 —— 这正是「远离手机」这类 App 的通用做法。
 *
 * ## 窗口必须是可聚焦的
 *
 * 锁机界面里有 PIN 输入框，需要接收键盘输入。所以**不能**加 FLAG_NOT_FOCUSABLE
 * （那会让输入框收不到焦点）。不加它，窗口默认就是可聚焦、可触摸的。
 *
 * ## 为什么 ComposeView + 两个 Owner
 *
 * 锁机界面是 Compose 写的，悬浮窗里要用 ComposeView 渲染。ComposeView 需要两个 Owner
 * 才能正常工作（少一个都会在 attach 时崩）：
 *   - LifecycleOwner：驱动 collectAsState / produceState / LaunchedEffect
 *   - SavedStateRegistryOwner：ComposeView 挂到窗口时强制要求，否则抛
 *     「doesn't propagateViewTreeSavedStateRegistryOwner」
 * 见文件底部的 OverlayComposeOwner。
 */
object LockOverlay {

    @Volatile
    private var overlayView: View? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    /** 用户是否授予了「显示悬浮窗」权限。没有它 addView 会直接抛异常。 */
    fun canShow(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    fun show(context: Context, reason: String) {
        if (isShowing) return
        if (!canShow(context)) return
        runCatching {
            val wm = context.getSystemService(WindowManager::class.java)
                ?: error("拿不到 WindowManager")

            val owner = OverlayComposeOwner()

            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        LockScreenRoute(
                            onExit = {
                                // PIN 解锁或锁定到期，撤掉悬浮窗
                                LockController.unlock(context)
                                hide()
                            }
                        )
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 可聚焦 + 可触摸（默认），不设 NOT_FOCUSABLE；
                // LAYOUT_IN_SCREEN 铺满到屏幕边缘；HARDWARE_ACCELERATED 给 Compose 用。
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE
            )

            wm.addView(view, params)
            overlayView = view
            isShowing = true
            DiagLog.add("悬浮窗", "已盖上遮罩（$reason）")
        }.onFailure {
            DiagLog.add("悬浮窗", "盖上失败：" + it.javaClass.simpleName + " " + it.message)
        }
    }

    fun hide() {
        if (!isShowing) return
        val v = overlayView
        overlayView = null
        isShowing = false
        runCatching {
            v?.context?.getSystemService(WindowManager::class.java)?.removeViewImmediate(v)
        }
        DiagLog.add("悬浮窗", "已撤下遮罩")
    }
}

/**
 * 给悬浮窗里的 ComposeView 提供 LifecycleOwner + SavedStateRegistryOwner。
 *
 * ComposeView 挂到窗口时强制要求 SavedStateRegistryOwner（否则抛
 * 「doesn't propagateViewTreeSavedStateRegistryOwner」），这里用
 * SavedStateRegistryController 造一个最小实现，和 ComponentActivity 的做法一致。
 */
private class OverlayComposeOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performRestore(Bundle())
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
}
