package com.waitleisure.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 壁纸取景页：让用户自己决定图片的哪一块显示在锁机页上。
 *
 * ## 交互
 *   · 双指捏合 → 缩放（1.0×～[WALLPAPER_MAX_SCALE]×）
 *   · 单指拖动 → 平移（1.0× 就能拖 —— 溢出的那个方向本来就有富余）
 *   · 重置     → 回到默认裁切
 *
 * ## 画面为什么和锁机页一致
 * 渲染直接复用 [FramedWallpaper] —— 锁机页用的是**同一个组件**，
 * 容器一样大就画得一模一样，不存在「预览好好的、锁机页露黑边」这种事。
 * （曾经就是两边各写一套，结果一边按 Crop 画、一边按实际尺寸画，对不上。）
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
    // 存一份当前值，手势和「确定」都读它。
    // 越界不在这里收敛 —— FramedWallpaper 内部按实际容器尺寸收敛，
    // 这里只管累加，缩小之后画面自然会收回来。
    var scale by remember { mutableFloatStateOf(initialScale.coerceIn(1f, WALLPAPER_MAX_SCALE)) }
    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }

    Dialog(
        onDismissRequest = onCancel,
        // 默认 Dialog 会留边距，这里要的是整屏 —— 否则预览比例和锁机页对不上
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {

            FramedWallpaper(
                bitmap = bitmap,
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY,
                modifier = Modifier.fillMaxSize(),
                onTransform = { s, ox, oy ->
                    scale = s
                    offsetX = ox
                    offsetY = oy
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
                    Button(onClick = { onConfirm(scale, offsetX, offsetY) }) {
                        Text("确定", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
