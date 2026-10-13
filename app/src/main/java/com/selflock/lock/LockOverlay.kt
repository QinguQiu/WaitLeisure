package com.selflock.lock

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.selflock.util.DiagLog

/**
 * 全屏遮罩（悬浮窗）。
 *
 * ## 为什么需要它 —— 这是整套锁机里唯一不依赖 Activity 的一层
 *
 * 真机实测（Redmi K70 / HyperOS OS3.0 / Android 16）：按 Home 把锁机页切到后台之后，
 * MIUI 会拒绝本应用再拉起**任何** Activity，logcat 里是
 * `MIUILOG- Permission Denied Activity`，startActivity 恒返回 code=3
 * （只把 intent 投递给已在顶部的 Activity，不做任务切换）。
 *
 * 试过都无效：
 *   - 补拉 / 每秒重试  → 检测得到，系统照拒
 *   - 换个 Activity 先回前台 → 无效，MIUI 是**应用级**拦截（这一条我一开始用
 *     `adb shell am start` 验证过，得出了错误结论 —— shell 有 BAL_ALLOW_PERMISSION
 *     特权，本来就不受这个限制）
 *
 * 而悬浮窗走的是 `WindowManager.addView`，**根本不经过 Activity 启动**，
 * 所以那道墙管不到它。一旦盖上，按多少次 Home 都盖不住它。
 *
 * ## 只用系统控件，不用 Compose
 *
 * ComposeView 放进 WindowManager 需要额外接管 ViewTreeLifecycleOwner /
 * SavedStateRegistryOwner，为一个纯色遮罩引入这层复杂度不值得。
 * 这里就用 LinearLayout + TextView + Button，零依赖、零生命周期风险。
 */
object LockOverlay {

    @Volatile
    private var overlayView: View? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    /** 用户是否授予了「显示悬浮窗」权限。没有这个权限，addView 会直接抛异常。 */
    fun canShow(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    fun show(context: Context, reason: String) {
        if (isShowing) return
        if (!canShow(context)) return
        runCatching {
            val wm = context.getSystemService(WindowManager::class.java)
                ?: error("拿不到 WindowManager")
            val root = buildView(context)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE：不抢输入焦点，免得干扰输入法；
                // 但**不加** NOT_TOUCHABLE —— 必须能吞掉触摸，否则会穿透到下面的桌面。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            )
            wm.addView(root, params)
            overlayView = root
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

    private fun buildView(context: Context): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF0B0B0F"))
            // 吞掉所有点击，不让它们穿透到下面的桌面
            isClickable = true
            isFocusable = true
        }

        root.addView(
            TextView(context).apply {
                text = "已锁定"
                setTextColor(Color.WHITE)
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
        )

        root.addView(
            TextView(context).apply {
                text = "SelfLock 正在阻止你使用这个界面\n点击下面的按钮回到锁机页"
                setTextColor(Color.parseColor("#FF9AA0A6"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(12), dp(24), dp(28))
            }
        )

        root.addView(
            Button(context).apply {
                text = "返回锁机页"
                setOnClickListener { LockScreenActivity.show(context) }
            }
        )

        return root
    }
}
