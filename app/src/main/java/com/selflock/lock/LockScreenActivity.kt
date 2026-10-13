package com.selflock.lock

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.selflock.R
import com.selflock.data.Repos
import com.selflock.util.DiagLog
import com.selflock.util.Pin
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
        // 设透明后，悬浮窗（FLAG_LAYOUT_NO_LIMITS）的壁纸就能透到状态栏后面。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
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
         * 「com.selflock → 自身」——服务据此认为已经拦住了、收工，可剩下的按键
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

    // 当前生效的完整白名单（全局 + 生效分组），随 now 每秒重算一次。
    // 分组是按时间段生效的，所以必须跟着时钟走，不能只在进入时算一次。
    val shownWhitelist = remember(now, whitelist) {
        Repos.effectiveWhitelist().sorted()
    }

    // 锁定到期后自动退场
    LaunchedEffect(now) {
        if (!Repos.lockState.isLockedNow()) {
            Repos.lockState.refresh()
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
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (canBlur) Modifier.blur(blurDp) else Modifier)
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

        Text(
            text = remainingText(now),
            color = Color(0xFFB0B0B0),
            fontSize = 13.sp
        )

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
        if (Repos.prefs.allowPinUnlock) {
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
    val label = remember(packageName) { Repos.installedApps.labelOf(packageName) }
    val bitmap = remember(packageName) {
        runCatching {
            Repos.installedApps.iconOf(packageName)?.toBitmap(144, 144)?.asImageBitmap()
        }.getOrNull()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1B1B1B)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(46.dp)
                )
            } else {
                Text(label.take(1), color = Color.White, fontSize = 20.sp)
            }
        }
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
