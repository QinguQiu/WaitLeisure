package com.waitleisure.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waitleisure.data.wallpaperMaxOffset

/**
 * 壁纸取景页：让用户自己决定图片的哪一块显示在锁机页上。
 *
 * ## 交互
 *   · 双指捏合 → 缩放（1.0×～[MAX_SCALE]×）
 *   · 单指拖动 → 平移
 *   · 重置     → 回到默认裁切
 *
 * ## 画面为什么和锁机页一致
 * 这里渲染用的 Modifier 和 LockScreenRoute 里完全一样：
 * `ContentScale.Crop` 打底，再叠 `graphicsLayer` 的 scale/translation。
 * 预览铺满全屏，锁机页也铺满全屏，两者比例一致，所以所见即所得。
 *
 * 存的是**屏幕倍数**而不是像素（见 AppPrefs 里 wallpaperScale 的说明），
 * 换台设备、换个分辨率都能还原成同一个取景。
 */
@Composable
fun WallpaperCropDialog(
    bitmap: ImageBitmap,
    initialScale: Float,
    initialOffsetX: Float,
    initialOffsetY: Float,
    onCancel: () -> Unit,
    onConfirm: (scale: Float, offsetX: Float, offsetY: Float) -> Unit
) {
    var scale by remember { mutableFloatStateOf(initialScale.coerceIn(1f, MAX_SCALE)) }
    var offsetX by remember {
        mutableFloatStateOf(initialOffsetX.coerceIn(-wallpaperMaxOffset(scale), wallpaperMaxOffset(scale)))
    }
    var offsetY by remember {
        mutableFloatStateOf(initialOffsetY.coerceIn(-wallpaperMaxOffset(scale), wallpaperMaxOffset(scale)))
    }

    Dialog(
        onDismissRequest = onCancel,
        // 默认 Dialog 会留边距，这里要的是整屏 —— 否则预览比例和锁机页对不上
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        // 平移量按屏幕尺寸换算 —— 存的是倍数，这里再乘回去
                        translationX = offsetX * size.width
                        translationY = offsetY * size.height
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // 先算新缩放，再按新缩放收敛平移：缩回 1× 时偏移必须归零，
                            // 否则图片会停在屏幕外，看起来像「壁纸没了」。
                            val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            val max = wallpaperMaxOffset(newScale)
                            scale = newScale
                            offsetX = (offsetX + pan.x / size.width).coerceIn(-max, max)
                            offsetY = (offsetY + pan.y / size.height).coerceIn(-max, max)
                        }
                    }
            )

            // 提示条
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "双指放大后，单指拖动选位置",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "调到锁机时想看到的位置",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            // 底部操作条
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    scale = 1f
                    offsetX = 0f
                    offsetY = 0f
                }) {
                    Text("重置", color = Color.White.copy(alpha = 0.85f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) {
                        Text("取消", color = Color.White.copy(alpha = 0.85f))
                    }
                    Button(onClick = { onConfirm(scale, offsetX, offsetY) }) {
                        Text("确定", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** 放大上限。再大就只剩像素块了，意义不大。 */
private const val MAX_SCALE = 4f
