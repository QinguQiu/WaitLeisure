package com.waitleisure.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.waitleisure.data.computeWallpaperFrame
import kotlin.math.roundToInt

/** 取景缩放的默认上限。再大就只剩像素块了。 */
const val WALLPAPER_MAX_SCALE = 4f

/**
 * 按取景参数画壁纸。**取景页和锁机页共用这一个组件** ——
 * 只要两边容器尺寸一样，画出来就一模一样，不可能出现「预览好好的、
 * 锁机页露黑边」这种不一致。
 *
 * ## 为什么不用 ContentScale.Crop
 *
 * 曾经是 `Image(Crop).fillMaxSize()` 再套 `graphicsLayer` 做缩放平移，
 * 结果一滑动就露黑边：Crop 虽然按覆盖比例把图放大了，但超出的部分
 * **画不出来**（Image 只在自己的布局边界内绘制），于是图层内容实际
 * 只有容器那么大，一平移立刻露背景。
 *
 * 现在改成：用 [computeWallpaperFrame] 算出裁切后的实际尺寸，
 * 把这个尺寸**设成 Image 的布局尺寸**，再在容器里平移。
 * 外层 clipToBounds 保证永不越界显示。
 *
 * @param onTransform 传了才响应手势（锁机页只读，不传）。
 *                    参数是新的 (缩放, 归一化横向偏移, 归一化纵向偏移)。
 */
@Composable
fun FramedWallpaper(
    bitmap: ImageBitmap,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier,
    maxScale: Float = WALLPAPER_MAX_SCALE,
    onTransform: ((Float, Float, Float) -> Unit)? = null
) {
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()

        val frame = computeWallpaperFrame(
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            containerWidth = containerW,
            containerHeight = containerH,
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY
        )

        val density = LocalDensity.current
        Image(
            bitmap = bitmap,
            contentDescription = null,
            // 尺寸由我们精确算好，不要再让 Crop/Fit 去二次缩放
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(frame.txPx.roundToInt(), frame.tyPx.roundToInt()) }
                .size(
                    width = with(density) { frame.widthPx.toDp() },
                    height = with(density) { frame.heightPx.toDp() }
                )
                .then(imageModifier)
        )

        // 手势挂在容器上而不是图片上：图片可能比容器大（超出的部分被裁掉），
        // 挂在图片上的话坐标要按图片尺寸换算，容易搞错；挂在容器上，
        // pan 直接除以容器尺寸就是归一化偏移。
        if (onTransform != null && containerW > 0f && containerH > 0f) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            onTransform(
                                (scale * zoom).coerceIn(1f, maxScale),
                                offsetX + pan.x / containerW,
                                offsetY + pan.y / containerH
                            )
                        }
                    }
            )
        }
    }
}
