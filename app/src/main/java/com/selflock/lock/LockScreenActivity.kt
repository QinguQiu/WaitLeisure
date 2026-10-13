package com.selflock.lock

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

    /** 允许盖在系统锁屏之上，并在触发时点亮屏幕。 */
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

    // 锁定到期后自动退场
    LaunchedEffect(now) {
        if (!Repos.lockState.isLockedNow()) {
            Repos.lockState.refresh()
            onExit()
        }
    }

    LockScreen(
        now = now,
        whitelist = whitelist.sorted(),
        onLaunchApp = { pkg -> launchApp(context, pkg) },
        onUnlock = {
            LockController.unlock(context)
            onExit()
        }
    )
}

@Composable
private fun LockScreen(
    now: Long,
    whitelist: List<String>,
    onLaunchApp: (String) -> Unit,
    onUnlock: () -> Unit
) {
    var showPinDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(64.dp))

        Text(
            text = remember(now) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
            },
            color = Color.White,
            fontSize = 68.sp,
            fontWeight = FontWeight.Light
        )

        Text(
            text = remember(now) {
                SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(now))
            },
            color = Color(0xFF9E9E9E),
            fontSize = 14.sp
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "\uD83D\uDD12",
            fontSize = 24.sp
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.lock_title),
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = remainingText(now),
            color = Color(0xFF9E9E9E),
            fontSize = 13.sp
        )

        Spacer(Modifier.height(32.dp))

        WhitelistSection(
            packages = whitelist,
            onLaunch = onLaunchApp,
            modifier = Modifier.weight(1f)
        )

        OutlinedButton(
            onClick = { showPinDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 40.dp)
        ) {
            Text(stringResource(R.string.lock_unlock))
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
    // 所以改成内嵌遮罩 + 居中卡片，渲染在悬浮窗自己的窗口里。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1C1C1E)
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.lock_unlock),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(16.dp))

                if (hasPin) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            pin = it.filter(Char::isDigit).take(12)
                            wrong = false
                        },
                        singleLine = true,
                        isError = wrong,
                        label = { Text(stringResource(R.string.lock_pin_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                } else {
                    // 没设 PIN 就直接放行，绝不把自己锁在门外
                    Text(
                        text = stringResource(R.string.lock_no_pin_notice),
                        color = Color(0xFF9E9E9E),
                        fontSize = 13.sp
                    )
                }

                if (wrong) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.lock_pin_wrong),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(20.dp))
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
