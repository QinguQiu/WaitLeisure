package com.waitleisure.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.waitleisure.R
import com.waitleisure.data.Repos
import com.waitleisure.ui.FramedWallpaper
import com.waitleisure.ui.AppIcon
import com.waitleisure.ui.rememberAppLabel
import com.waitleisure.util.DiagLog
import com.waitleisure.util.Pin
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 锁机页 —— 也就是「锁定期间的桌面」。
 *
 * 它做三件事：
 *  1. 用黑底全屏盖住一切，让你看不到原来的桌面和图标；
 *  2. 只列出白名单应用的图标，点一下就能正常使用；
 *  3. 提供 PIN 入口，作为唯一的合法逃生通道。
 */
class LockScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repos.ensure(this)
        applyLockScreenWindowFlags()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // 吞掉返回键，防止用返回手势溜出去。
                // 放在 Activity 层而不是 LockScreenRoute 里：悬浮窗（ComposeView）里没有
                // OnBackPressedDispatcherOwner，BackHandler 会直接崩。
                BackHandler(enabled = true) { /* 故意留空 */ }
                LockScreenRoute(onExit = { finish() })
            }
        }

        // 必须放在 setContent **之后** —— 见 applyStatusBarAppearance 的说明。
        applyStatusBarAppearance()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        Repos.ensure(this)
        Repos.lockState.refresh()
        // 定时到点了就会被弹走，别继续挡着用户
        if (!Repos.lockState.isLockedNow()) finish()
    }

    override fun onPause() {
        isResumed = false
        super.onPause()
    }

    /** 允许盖在系统锁屏之上，并在触发时点亮屏幕；状态栏透明，让壁纸透到底。 */
    @Suppress("DEPRECATION")
    private fun applyLockScreenWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // 状态栏透明 + 内容延伸到底。
        // 锁机页壁纸渲染在悬浮窗里，而状态栏底色由「最顶层的 Activity」决定 ——
        // 不把它设成 edge-to-edge，状态栏会显示 Activity 主题的底色，和壁纸不同色。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    /**
     * 状态栏/导航栏图标改成白色，并且**把系统状态栏藏掉**。
     *
     * 锁机页背景都是深色或暗图，深色图标看不清；而且「浅色外观」还会让系统
     * 在状态栏铺一层白底 —— 那正是用户看到的「状态栏有时完全变成白底」。
     * 所以这里显式清掉 LIGHT 标志位。
     *
     * 关于隐藏：锁机页顶部那条（时间/电量/信号）是**我们自己画的**
     * （Compose 里的 OverlayStatusBar），悬浮窗和锁机页 Activity 用的是同一个。
     * 正常情况下悬浮窗盖在系统状态栏之上，只看得见我们那条；但悬浮窗不在、
     * 锁机页 Activity 单独在前台时（比如收起下拉栏之后），系统那条也露出来了，
     * 于是**两条状态栏同时出现** —— 用户反馈的「有时候双状态栏」就是这个。
     * 在这里把系统的藏掉，两种情况下就都只剩我们那条了。
     *
     * **必须在 setContent 之后调用。** window.insetsController 内部要拿 DecorView，
     * 而 DecorView 是在 setContentView 时才创建的 —— 放在 onCreate 开头会直接
     * NPE：`DecorView.getWindowInsetsController() on a null object reference`。
     * （真机踩过：LockScreenActivity 每次都崩在 onCreate，只是悬浮窗还能盖住屏幕，
     *   表面上看不出来。）
     */
    private fun applyStatusBarAppearance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
                // 请求隐藏系统状态栏，避免和我们自己画的那条叠成两条。
                //
                // 实测（真机 / MIUI）：这条请求对「我们的 insets」是生效的 ——
                // 归零之后 OverlayStatusBar 回到 y=0，和系统条重合；
                // 但 MIUI 并不会真的把系统状态栏撤掉，顶上看到的仍是系统那条。
                // 所以最终效果是**只剩一条**（系统条压在我们的条上面），
                // 而不是「只剩我们那条」。够用，但别指望它能把系统条去掉。
                window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        /**
         * 锁机页此刻是否真的处于 resumed 状态。
         *
         * 为什么不能用「无障碍事件里最后一个包名是不是自己」来判断：
         * 连续快速按 Home 时系统会丢弃一部分窗口事件。真机实测（adb 连按 5 次
         * 无间隔）：只收到 2 次桌面的 TYPE_WINDOW_STATE_CHANGED，紧接着一条
         * 「com.waitleisure → 自身」——服务据此认为已经拦住了、收工，可剩下的按键
         * 仍在把桌面往上推，而且此后不再产生任何事件，最终停在桌面上。
         *
         * Activity 的 onResume/onPause 反映的是真实状态，不会被丢事件骗到。
         */
        @Volatile
        var isResumed: Boolean = false
            private set

        /**
         * 从无障碍服务/广播接收器里把锁机页拉到前台。
         *
         * 之所以能在后台启动 Activity：本应用启用了无障碍服务，
         * 而「拥有系统绑定的服务」是 Android 10+ 后台启动 Activity 限制的豁免项之一。
         * 仍然用 runCatching 兜底 —— 万一被拦，下一次窗口切换时无障碍服务会再拉一次。
         *
         * @return true 表示 startActivity 没抛异常。注意这只说明「调用成功」，
         *         不保证界面真的显示出来了 —— 所以还要把结果记进 DiagLog 才能定位问题。
         */
        fun show(context: Context): Boolean {
            val intent = Intent(context, LockScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            // 返回是否真的启动成功。以前这里静默吞异常，出问题时毫无线索；
            // 调用方（无障碍服务）会把结果写进 DiagLog。
            return runCatching { context.startActivity(intent) }.isSuccess
        }
    }
}

@Composable
internal fun LockScreenRoute(onExit: () -> Unit) {
    val context = LocalContext.current

    // 每秒 tick 一次：同时驱动大时钟和「剩余时间」
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val whitelist by Repos.whitelist.packages.collectAsState()

    // 当前生效的完整白名单（全局 = 永远放行；再加上本次锁定选中的分组）。
    // 分组已经不按时间段生效了，这里跟着 now 重算只是图省事 —— 开销是一次集合并集。
    val shownWhitelist = remember(now, whitelist) {
        Repos.effectiveWhitelist().sorted()
    }

    // 锁定到期后自动退场。
    //
    // 这里**只退场，不拉首页**：番茄钟的休息阶段就是这么进来的，
    // 拉首页会把正在用别的应用的人踹出来（休息时间应该允许玩别的软件）。
    LaunchedEffect(now) {
        if (!Repos.lockState.isLockedNow()) {
            Repos.lockState.refresh()
            LockController.unlock(context, pullAppToFront = false)
            onExit()
        }
    }

    LockScreen(
        now = now,
        whitelist = shownWhitelist,
        onLaunchApp = { pkg -> launchApp(context, pkg) },
        onUnlock = {
            LockController.unlock(context)
            onExit()
        }
    )
}

/** 锁机页应用页的背景模糊半径上限（dp）。 */
private const val MAX_BLUR_DP = 26f

@Composable
private fun LockScreen(
    now: Long,
    whitelist: List<String>,
    onLaunchApp: (String) -> Unit,
    onUnlock: () -> Unit
) {
    var showPinDialog by remember { mutableStateOf(false) }

    // 壁纸：有自定义图片就用图，否则用纯色
    val wallpaper = remember {
        val path = Repos.prefs.wallpaperImagePath
        if (path != null) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        } else {
            null
        }
    }

    // 壁纸取景：用户在设置页的裁切界面自己拖/缩放选出来的。
    // 默认 (1.0, 0, 0) = 就用 ContentScale.Crop 的基准画面（居中裁切填满），
    // 和这个功能出现之前的表现完全一致。
    //
    // 这里刻意**不用 remember** —— 用户改完取景回到锁机页要立刻看到效果。
    // 三个都是 SharedPreferences 读内存缓存，开销可以忽略。
    val wallpaperScale = Repos.prefs.wallpaperScale
    val wallpaperOffsetX = Repos.prefs.wallpaperOffsetX
    val wallpaperOffsetY = Repos.prefs.wallpaperOffsetY

    val pagerState = rememberPagerState(pageCount = { 3 })

    // 模糊度跟着滑动位置走：中间那页（应用页）最糊，时间页/退出页清晰，
    // 滑动过程中连续过渡。pos 是 0..2 的连续位置，|pos-1| 越小越靠近应用页。
    val pos = pagerState.currentPage + pagerState.currentPageOffsetFraction
    val blurFraction = (1f - kotlin.math.abs(pos - 1f)).coerceIn(0f, 1f)
    val blurDp = (blurFraction * MAX_BLUR_DP).dp

    // Modifier.blur 只在 Android 12+ 生效。更低版本退化成压一层暗色 ——
    // 宁可暗一点保证白字可读，也不能让文字糊在花哨的壁纸上看不清。
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(modifier = Modifier.fillMaxSize()) {
        if (wallpaper != null) {
            // 取景渲染直接复用取景页那个组件 —— 容器一样大就画得一模一样，
            // 不存在「预览好好的、锁机页露黑边」这种事。
            // （曾经两边各写一套：这边按 ContentScale.Crop 画、那边按实际尺寸画，
            //   结果滑动时锁机页会露黑边。Crop 超出的部分根本画不出来。）
            FramedWallpaper(
                bitmap = wallpaper,
                scale = wallpaperScale,
                offsetX = wallpaperOffsetX,
                offsetY = wallpaperOffsetY,
                modifier = Modifier.fillMaxSize(),
                imageModifier = if (canBlur) Modifier.blur(blurDp) else Modifier
            )
            if (!canBlur) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(Repos.prefs.wallpaperColor))
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> TimePage(now)
                1 -> AppsPage(whitelist = whitelist, onLaunchApp = onLaunchApp)
                else -> ExitPage(now = now, onUnlock = { showPinDialog = true })
            }
        }

        // 顶部这条**只负责识别下拉手势**，不再画任何东西。
        //
        // 它以前是个自绘状态栏（时间/网络/电量）。当初加它的理由是
        // 「悬浮窗铺满全屏后系统状态栏的图标不再绘制」—— 但这个判断只在
        // 某些情况下成立，实际表现是两条状态栏叠着：
        //
        //   · 悬浮窗模式：我们的条画在 y=0，和系统条重合，看着像一条；
        //   · 锁机页 Activity 模式：系统条在 y=0，我们的条被 WindowInsets
        //     推到它下面 —— 真·两条。
        //
        // 而且真机实测（用户反馈）：在锁机页下拉通知栏之后，**系统状态栏会
        // 出现并且不再消失**，和我们那条一直并排，很乱。
        //
        // 所以绘制部分全部去掉，统一用系统状态栏；这条退化成一个透明的
        // 手势热区 —— 下拉展开通知栏的能力必须留着，锁机页整屏都是我们
        // 的窗口，系统收不到这个手势。
        StatusBarGestureStrip(modifier = Modifier.align(Alignment.TopCenter))

        // 页码指示点
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(3) { i ->
                val active = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .size(if (active) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) Color.White else Color.White.copy(alpha = 0.35f)
                        )
                )
            }
        }
    }

    if (showPinDialog) {
        PinDialog(
            onDismiss = { showPinDialog = false },
            onSuccess = {
                showPinDialog = false
                onUnlock()
            }
        )
    }
}

/**
 * 主动展开通知栏（下拉栏）。
 *
 * `android.app.StatusBarManager` **不是公开 SDK API**（`android.os.StatusBarManager` 根本不存在），
 * 只能走反射拿系统服务再把 `expandNotificationsPanel()` 调出来。
 * 需要清单里的 `EXPAND_STATUS_BAR` 权限（normal 级，安装即授予）。
 *
 * 为什么非要这么做：悬浮窗整屏可触摸，会把系统自带的「从状态栏下拉」手势一起吃掉，
 * 而矩形窗口没法做局部触摸透传 —— 所以只能自己识别手势再主动展开。
 * 展开后下拉栏在悬浮窗之上，可以正常操作。
 */
private fun expandNotificationShade(context: Context): Boolean = runCatching {
    val service = context.getSystemService("statusbar")
    val clazz = Class.forName("android.app.StatusBarManager")
    clazz.getMethod("expandNotificationsPanel").invoke(service)
    true
}.getOrElse {
    DiagLog.add("下拉栏", "展开失败：" + it.javaClass.simpleName + " " + it.message)
    false
}

/** 锁机页顶部的**手势热区**：只识别下拉，不画任何东西。
 *
 * 这里原本是个自绘状态栏（时间 / 网络 / 电量）。为什么现在不画了：
 *
 *   · 它和系统状态栏会叠成两条 —— 悬浮窗模式下刚好重合看不出，
 *     锁机页 Activity 模式下系统条在 y=0、我们的条被 WindowInsets 推到
 *     它下面，就是清清楚楚的两条；
 *   · 用户实测：在锁机页下拉通知栏之后，系统状态栏会出现并且不再消失，
 *     和我们那条一直并排着。
 *
 * 统一交给系统状态栏之后，两种模式表现一致，也不会再有两份。
 *
 * 但**手势必须留着**：锁机页整屏都是我们的窗口（悬浮窗是整屏可触摸的），
 * 系统收不到「从顶部下拉」这个手势 —— 实测遮罩盖着时下拉栏根本拉不下来
 * （y=0 / y=50 / y=400 三处都试过，全部无效）。矩形窗口没法做「局部触摸
 * 透传」，所以只能在这一条上自己识别，再用 EXPAND_STATUS_BAR 权限主动展开。
 * 「远离手机」也带这个权限，正是同一个原因。
 *
 * 高度仍取系统状态栏的真实 inset，各家 ROM 的刘海/状态栏高度都能自适应。
 */
@Composable
private fun StatusBarGestureStrip(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // 一次手势只展开一次。
    // 之前没这个闸门，结果是每个 MOVE 事件累加过阈值就调一次 —— 实测一次下拉触发了 11 次
    // expandNotificationsPanel()，反复展开互相打断，最后停在收起状态，看起来就是「没反应」。
    var dragged by remember { mutableFloatStateOf(0f) }
    var firedThisGesture by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 36.dp.toPx() }

    val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val stripHeight = if (inset > 0.dp) inset else 28.dp

    // 空 Box：只有手势，没有内容
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(stripHeight)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragged = 0f
                        firedThisGesture = false
                    },
                    onDragEnd = { dragged = 0f },
                    onDragCancel = { dragged = 0f }
                ) { _, dragAmount ->
                    if (dragAmount > 0f && !firedThisGesture) {
                        dragged += dragAmount
                        if (dragged >= threshold) {
                            firedThisGesture = true
                            DiagLog.add("下拉栏", "识别到下拉手势，先撤遮罩再展开")

                            // 撤遮罩前**先把锁机页拉到前台当背景**。
                            //
                            // 为什么需要这一步：撤掉遮罩后，背后露出的是任务栈里的下一个
                            // Activity。正常情况下锁机页就在那儿（实测窗口层级：
                            // Launcher #14 < LockScreenActivity #15 < 悬浮窗 #1），
                            // 但锁机页被系统回收过、只剩看门狗补盖的悬浮窗时，
                            // 背后就是桌面 —— 用户看到的就是「锁机页下拉，背景闪成桌面」。
                            //
                            // 这里先把锁机页叫起来（有悬浮窗权限，后台启动 Activity 是放行的），
                            // 等它上来再撤遮罩，背后就一定是锁机页。
                            LockScreenActivity.show(context)

                            Handler(Looper.getMainLooper()).postDelayed({
                                // 实测：遮罩持有时 expandNotificationsPanel() 会返回成功但系统不理会 ——
                                // 撤掉遮罩它才真的展开。
                                LockOverlay.hide()
                                val ok = expandNotificationShade(context)
                                DiagLog.add("下拉栏", if (ok) "展开调用成功" else "展开调用失败")

                                // ⚠️ 这里**不能**立刻把遮罩盖回来。
                                // 试过 postDelayed(350) 补盖，结果遮罩把下拉栏整个挡住了 ——
                                // 我们的悬浮窗实际压在下拉栏之上，遮罩在 = 下拉栏不可见。
                                // 所以撤下后交给看门狗，等下拉栏收起、前台变回应用时再补盖。
                            }, 220)
                        }
                    }
                }
            }
    )
}

// ---------------------------------------------------------------------------
// 下面两个是退役的自绘状态栏用的辅助函数。
// 自绘状态栏已经去掉（改用系统状态栏，见 StatusBarGestureStrip 的说明），
// 所以它们暂时没有调用方 —— 留着方便以后要回来，用 @Suppress 压掉「未使用」告警。
// ---------------------------------------------------------------------------

/** 电量百分比。ACTION_BATTERY_CHANGED 是粘性广播，注册时就能拿到当前值。 */
@Suppress("unused")
@Composable
private fun rememberBatteryPercent(): Int {
    val context = LocalContext.current
    var percent by remember { mutableIntStateOf(-1) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) percent = level * 100 / scale
            }
        }
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.getOrNull()
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) percent = level * 100 / scale

        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return percent
}

/** 网络类型。跟着 now 每秒重算，连断网也能及时反映。 */
@Suppress("unused")
@Composable
private fun rememberNetworkLabel(now: Long): String {
    val context = LocalContext.current
    return remember(now) {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.let { it.getNetworkCapabilities(it.activeNetwork) }
            when {
                caps == null -> "无网络"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
                else -> ""
            }
        }.getOrDefault("")
    }
}

/** 第一页：时间 + 倒计时。背景保持清晰。 */
@Composable
private fun TimePage(now: Long) {
        Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = remember(now) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
            },
            color = Color.White,
            fontSize = 72.sp,
            fontWeight = FontWeight.Light
        )

        Text(
            text = remember(now) {
                SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(now))
            },
            color = Color(0xFFB0B0B0),
            fontSize = 14.sp
        )

        Spacer(Modifier.height(26.dp))

        Text(text = "\uD83D\uDD12", fontSize = 24.sp)

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.lock_title),
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(6.dp))

        // 本次锁机是哪个任务触发的。
        // 配了几套任务之后，光看「已锁定」分不清现在是「专注 25 分钟」
        // 还是「深度锁机 2 小时」—— 这里直接写出来。
        // 非任务触发的（手动锁 / 自带时长）就没有这一行。
        val activeTask = Repos.prefs.activeTaskName
        if (activeTask.isNotBlank()) {
            Text(
                text = "任务 · $activeTask",
                color = Color(0xFFFFB74D),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
        }

        Text(
            text = remainingText(now),
            color = Color(0xFFB0B0B0),
            fontSize = 13.sp
        )

        // 个性签名：设置页里写的一句话。
        // 留空就整块不显示 —— 不能只是画个空 Text，那样会白占一段高度。
        val signature = Repos.prefs.signature
        if (signature.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = signature,
                color = Color(0xFFE6E6E6),
                fontSize = 15.sp,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        // 番茄钟进行中就顺带显示当前相位，省得用户猜现在能不能玩手机
        val pomo = Pomodoro.current()
        if (pomo != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "\uD83C\uDF45 " + pomo.first + " 组 · " +
                    if (pomo.second == "focus") "专注中" else "休息中",
                color = Color(0xFFFFB74D),
                fontSize = 13.sp
            )
        }
    }
}

/** 第二页：白名单应用。背景模糊。 */
@Composable
private fun AppsPage(whitelist: List<String>, onLaunchApp: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(72.dp))
        WhitelistSection(
            packages = whitelist,
            onLaunch = onLaunchApp,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 第三页：退出。背景保持清晰。 */
@Composable
private fun ExitPage(now: Long, onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (Repos.prefs.effectiveAllowPinUnlock) {
            Text(text = "\uD83D\uDD13", fontSize = 28.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "输入 PIN 可提前解除锁定",
                color = Color(0xFFB0B0B0),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = onUnlock,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.lock_unlock))
            }
        } else {
            // 严格模式：解不开，只能等
            Text(text = "\u23F3", fontSize = 28.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "严格模式已开启 —— 本次锁定期间无法解除",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = remainingText(now),
                color = Color(0xFFB0B0B0),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "「允许密码解锁」在设置页可以重新打开",
                color = Color(0xFF808080),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WhitelistSection(
    packages: List<String>,
    onLaunch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (packages.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = stringResource(R.string.lock_no_whitelist),
                color = Color(0xFF616161),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.lock_subtitle),
            color = Color(0xFF757575),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(14.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(packages, key = { it }) { pkg ->
                AppEntry(packageName = pkg, onClick = { onLaunch(pkg) })
            }
        }
    }
}

@Composable
private fun AppEntry(packageName: String, onClick: () -> Unit) {
    // 走统一的「缓存 + IO 线程加载」，别再自己实现一遍 ——
    // 之前这里和 AppIcon 各写了一份无缓存版本，是滚动卡顿的主要来源。
    val label = rememberAppLabel(packageName)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        AppIcon(
            packageName = packageName,
            size = 52.dp,
            backgroundColor = Color(0xFF1B1B1B)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = Color(0xFFBDBDBD),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val hasPin = remember { !Repos.prefs.pinHash.isNullOrEmpty() }
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    // 悬浮窗里不能弹 Dialog：没有 Activity 的 window token，Dialog.show() 会抛
    // BadTokenException（Unable to add window -- token null is not valid）。
    // 同理，悬浮窗窗口也收不到系统软键盘（IME），所以 PIN 用自绘数字键盘输入。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1C1C1E)
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.lock_unlock),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(14.dp))

                if (hasPin) {
                    Text(
                        text = if (pin.isEmpty()) "输入 PIN" else "●".repeat(pin.length),
                        color = Color.White,
                        fontSize = 20.sp,
                        letterSpacing = 6.sp
                    )
                    if (wrong) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.lock_pin_wrong),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    NumberKeypad(
                        onDigit = { if (pin.length < 12) { pin += it; wrong = false } },
                        onBackspace = { pin = pin.dropLast(1); wrong = false }
                    )
                } else {
                    // 没设 PIN 就直接放行，绝不把自己锁在门外
                    Text(
                        text = stringResource(R.string.lock_no_pin_notice),
                        color = Color(0xFF9E9E9E),
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        if (!hasPin || Pin.verify(pin, Repos.prefs.pinHash)) onSuccess() else wrong = true
                    }) { Text(stringResource(R.string.action_confirm)) }
                }
            }
        }
    }
}

/** 自绘数字键盘：悬浮窗收不到系统 IME，PIN 只能靠它输入。 */
@Composable
private fun NumberKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9')
        ).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { d ->
                    OutlinedButton(
                        onClick = { onDigit(d) },
                        modifier = Modifier.weight(1f)
                    ) { Text(d.toString(), fontSize = 20.sp) }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = onBackspace,
                modifier = Modifier.weight(1f)
            ) { Text("⌫", fontSize = 20.sp) }
            OutlinedButton(
                onClick = { onDigit('0') },
                modifier = Modifier.weight(1f)
            ) { Text("0", fontSize = 20.sp) }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun remainingText(now: Long): String {
    if (Repos.lockState.isForever()) return "无限期锁定 · 输入 PIN 可解除"
    val remaining = (Repos.lockState.lockedUntil() - now).coerceAtLeast(0L)
    val hours = remaining / 3_600_000L
    val minutes = (remaining % 3_600_000L) / 60_000L
    return if (hours > 0) "剩余 %d 小时 %d 分".format(hours, minutes) else "剩余 %d 分钟".format(minutes)
}

/**
 * 点击白名单应用后，给 ROM 的「启动应用」确认框留多长的可交互时间。
 * 10 秒足够读完并点掉；同时窗口结束后前台服务会补检查，不会留长期空子。
 */
private const val LAUNCH_GRACE_MILLIS = 10_000L

private fun launchApp(context: Context, packageName: String) {
    // 顺序很重要：必须赶在确认框弹出之前把免打扰窗口打开，
    // 否则第一次点击照样会被自己的拦截打掉。
    LockAccessibilityService.startLaunchGrace(packageName, LAUNCH_GRACE_MILLIS)

    runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
