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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
 *   · 单指拖动 → 平移（1.0× 就能拖 —— 见 wallpaperMaxOffset 的说明）
 *   · 重置     → 回到默认裁切
 *
 * ## 画面为什么和锁机页一致
 * 这里渲染用的写法和 LockScreenRoute 里**完全一样**：
 * `ContentScale.Crop` 打底，再叠 `graphicsLayer` 的 scale/translation，
 * 越界收敛也用同一个 wallpaperMaxOffset()。预览铺满全屏、锁机页也铺满全屏，
 * 所以所见即所得。
 *
 * 存的是**容器倍数**而不是像素（见 AppPrefs 里 wallpaperScale 的说明），
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
    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }

    // 容器实际尺寸。初始状态和收敛都要用它，所以单独存一份而不是只在
    // pointerInput 里用（那里的 size 只在那一个作用域内可见）。
    var container by remember { mutableStateOf(Size.Zero) }

    // 按当前容器算出的可平移上限。容器还没量出来时是 (0, 0)。
    val (maxOffsetX, maxOffsetY) = wallpaperMaxOffset(
        scale = scale,
        imageWidth = bitmap.width,
        imageHeight = bitmap.height,
        containerWidth = container.width,
        containerHeight = container.height
    )

    Dialog(
        onDismissRequest = onCancel,
        // 默认 Dialog 会留边距，这里要的是整屏 —— 否则预览比例和锁机页对不上
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { container = Size(it.width.toFloat(), it.height.toFloat()) }
        ) {

            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 越界在这里收敛（而不是在手势里 clamp）。
                        // 手势里 clamp 的话，缩小之后偏移会卡在原来的边界上不动，
                        // 表现成「缩小了但画面偏着回不来」。
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX.coerceIn(-maxOffsetX, maxOffsetX) * size.width
                        translationY = offsetY.coerceIn(-maxOffsetY, maxOffsetY) * size.height
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            // 平移量记成容器倍数；越界由上面那层收敛
                            offsetX += pan.x / size.width
                            offsetY += pan.y / size.height
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
                    text = "双指缩放，单指拖动",
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
                    Button(onClick = {
                        // 存之前先按当前容器收敛一次 —— 手势里没 clamp，
                        // 不收敛的话会存进一个越界的值（虽然渲染层也会收，
                        // 但存干净的更省事，而且换设备时不会莫名其妙被裁更多）
                        onConfirm(
                            scale,
                            offsetX.coerceIn(-maxOffsetX, maxOffsetX),
                            offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                        )
                    }) {
                        Text("确定", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** 放大上限。再大就只剩像素块了，意义不大。 */
private const val MAX_SCALE = 4f
