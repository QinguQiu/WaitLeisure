package com.waitleisure.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.waitleisure.data.Repos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图标 / 应用名缓存。
 *
 * 为什么必须有这一层：LazyColumn 会把滚出屏幕的 item 整个销毁，
 * 里面的 remember 也跟着没了。之前 AppIcon 只靠 remember(packageName) 缓存，
 * 于是每次滚回来都要重新走一遍
 *   PackageManager.getApplicationIcon()（跨进程）→ Drawable.toBitmap()（光栅化）
 * 全在主线程上，滚动时肉眼可见地掉帧。
 *
 * 现在图标只取一次，之后进程内一直命中；首次加载也丢到 IO 线程，
 * 主线程只负责画已经准备好的位图。
 */
private val iconCache = LruCache<String, ImageBitmap>(160)
private val labelCache = HashMap<String, String>()

/**
 * 预热图标/名字缓存。
 *
 * 光有缓存还不够：第一次滚动时缓存是空的，用户滚到哪就现取到哪，
 * 取完再触发重组 + 位图上传 —— 这就是剩下的那部分掉帧。
 * 列表一加载完就在 IO 线程把整批图标取好，把「边滚边加载」变成「滚之前就好了」。
 */
suspend fun prefetchAppIcons(packages: List<String>) = withContext(Dispatchers.IO) {
    packages.forEach { pkg ->
        if (iconCache.get(pkg) == null) {
            runCatching {
                Repos.installedApps.iconOf(pkg)?.toBitmap(144, 144)?.asImageBitmap()
            }.getOrNull()?.also { iconCache.put(pkg, it) }
        }
        if (labelCache[pkg] == null) {
            runCatching { Repos.installedApps.labelOf(pkg) }
                .getOrNull()?.also { labelCache[pkg] = it }
        }
    }
}

/**
 * 应用名（带进程内缓存）。
 *
 * 单独抽出来是因为锁机页的应用格子也要显示名字 —— 那边原本又写了一遍
 * 无缓存的 labelOf()，等于缓存白做。统一走这里。
 */
@Composable
fun rememberAppLabel(packageName: String): String {
    val label by produceState(
        initialValue = labelCache[packageName] ?: "",
        packageName
    ) {
        value = labelCache[packageName] ?: withContext(Dispatchers.IO) {
            runCatching { Repos.installedApps.labelOf(packageName) }
                .getOrDefault(packageName)
                .also { labelCache[packageName] = it }
        }
    }
    return label
}

/** 应用图标。取不到图标时退化为首字母方块，避免列表出现空洞。 */
@Composable
fun AppIcon(
    packageName: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFFEDEDED)
) {
    // 命中缓存就同步拿到，第一帧就能画出来，不会闪一下占位符。
    // 没命中才去 IO 线程取，取完 produceState 自动触发重组。
    val bitmap by produceState<ImageBitmap?>(
        initialValue = iconCache.get(packageName),
        packageName
    ) {
        value = iconCache.get(packageName) ?: withContext(Dispatchers.IO) {
            runCatching {
                Repos.installedApps.iconOf(packageName)?.toBitmap(144, 144)?.asImageBitmap()
            }.getOrNull()?.also { iconCache.put(packageName, it) }
        }
    }
    val label = rememberAppLabel(packageName)
    // 委托属性不能智能转换，先落到局部变量
    val bmp = bitmap

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size - 6.dp)
            )
        } else {
            Text(
                text = label.take(1),
                fontSize = (size.value / 2.4f).sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 统一的卡片容器，带标题与可选的副标题。 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * 权限体检用的单行。左侧是对勾/叉号，右侧可点。
 */
@Composable
fun CheckRow(
    ok: Boolean,
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (ok) "✅" else "⚠️",
            fontSize = 16.sp
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!ok && actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 空列表时的占位提示。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 星期选择用的 FilterChip 行。 */
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
