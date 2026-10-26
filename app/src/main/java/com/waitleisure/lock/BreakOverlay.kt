package com.waitleisure.lock

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.waitleisure.util.DiagLog
import com.waitleisure.util.Permissions
import kotlinx.coroutines.delay

/**
 * 番茄钟**休息期间**的迷你悬浮窗：右上角一个小胶囊，显示还剩多久，带一个 ×。
 *
 * 为什么需要它：休息阶段是解锁的，用户会切到别的应用去玩 —— 这时候
 * 锁机页看不见，完全不知道休息还剩多久。有个角标就不用反复切回来。
 *
 * 为什么和 [LockOverlay] 分开放：那个是整屏遮罩、可聚焦（要收 PIN 输入），
 * 这个是角落小窗、**不可聚焦**（不能抢走别人应用的输入焦点）。
 */
object BreakOverlay {

    @Volatile
    private var overlayView: View? = null

    /** 加/撤窗口用的同一个 WindowManager。不能从 view.parent 反推 —— 那是内部的根 View。 */
    @Volatile
    private var overlayWindowManager: WindowManager? = null

    @Volatile
    private var isShowing: Boolean = false

    /**
     * 用户点了 × —— 这一轮休息不要再弹。
     *
     * 只对**当前这一轮**生效：下一轮休息时调用方会 [resetDismissed]。
     * 做成永久的就变成"关一次以后永远看不到"了。
     */
    @Volatile
    var dismissed: Boolean = false
        private set

    fun resetDismissed() {
        dismissed = false
    }

    private fun canShow(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context) &&
            Permissions.isAccessibilityEnabled(context)
    }.getOrDefault(false)

    /**
     * 显示休息倒计时。
     *
     * @param endAt 休息结束的时刻（毫秒）
     */
    fun show(context: Context, endAt: Long) {
        if (isShowing || dismissed) return
        if (!canShow(context)) return

        runCatching {
            val wm = context.getSystemService(WindowManager::class.java)
                ?: error("拿不到 WindowManager")

            val owner = BreakOverlayOwner()
            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        BreakPill(
                            endAt = endAt,
                            onClose = {
                                dismissed = true
                                hide()
                            }
                        )
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 不可聚焦（不抢输入焦点）+ 不独占触摸（外面的应用照常点）
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                // 让开状态栏：用系统高度而不是固定 dp，各家 ROM 高度不一样
                val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
                y = if (resId > 0) context.resources.getDimensionPixelSize(resId) + 8 else 24
                x = 12
            }

            wm.addView(view, params)
            overlayView = view
            overlayWindowManager = wm
            isShowing = true
            DiagLog.add("休息窗", "已显示（还剩 ${(endAt - System.currentTimeMillis()) / 1000} 秒）")
        }.onFailure {
            DiagLog.add("休息窗", "显示失败：" + it.javaClass.simpleName + " " + it.message)
        }
    }

    fun hide() {
        if (!isShowing) return
        val v = overlayView
        val wm = overlayWindowManager
        overlayView = null
        overlayWindowManager = null
        isShowing = false
        runCatching { if (v != null && wm != null) wm.removeView(v) }
    }

    /** 番茄钟停了 / 全部跑完 —— 彻底清掉状态，下次重新开始。 */
    fun reset(context: Context) {
        hide()
        dismissed = false
    }
}

/**
 * 那个小胶囊本体。
 *
 * 每秒自己重算一次剩余时间 —— 不用外面推，窗口存活期间自洽。
 */
@androidx.compose.runtime.Composable
private fun BreakPill(endAt: Long, onClose: () -> Unit) {
    // 每秒 tick 一次，驱动倒计时
    val remaining by produceState(initialValue = (endAt - System.currentTimeMillis()).coerceAtLeast(0L), endAt) {
        while (true) {
            value = (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(500L)
        }
    }

    val totalSeconds = (remaining / 1000L).toInt()
    val mm = totalSeconds / 60
    val ss = totalSeconds % 60

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xE6202124))
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "休息中",
            color = Color(0xFFFFB74D),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = String.format("%d:%02d", mm, ss),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        // × 的点击区域做小一点，但留足 32dp 方便点
        Text(
            text = "✕",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClose)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * 给 ComposeView 用的最小 Owner 组合。
 *
 * ComposeView 挂到窗口时**强制**要求这两个 Owner，缺一个就抛
 * 「doesn't propagateViewTreeSavedStateRegistryOwner」。
 * 和 [LockOverlay] 里那个是同一套思路，只是那边是复用的私有类。
 */
private class BreakOverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val registry: SavedStateRegistry = savedStateController.savedStateRegistry

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = registry
}
