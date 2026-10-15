package com.waitleisure.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
                // ⚠️ 必须用 requiredSize 而不是 size。
                //
                // size() 会**把请求的尺寸夹进父容器的约束里**：图片比屏幕宽时，
                // 宽度被夹成屏幕宽、高度却不受影响 —— 宽高比就此被破坏，
                // 再配上 FillBounds 就成了「图片被拉伸变形」（用户反馈）。
                //
                // requiredSize 忽略父约束，量出来就是我们要的尺寸；
                // 超出的部分由外层的 clipToBounds 裁掉。
                .requiredSize(
                    width = with(density) { frame.widthPx.toDp() },
                    height = with(density) { frame.heightPx.toDp() }
                )
                .offset { IntOffset(frame.txPx.roundToInt(), frame.tyPx.roundToInt()) }
                .then(imageModifier)
        )

        // 手势挂在容器上而不是图片上：图片可能比容器大（超出的部分被裁掉），
        // 挂在图片上的话坐标要按图片尺寸换算，容易搞错；挂在容器上，
        // pan 直接除以容器尺寸就是归一化偏移。
        if (onTransform != null && containerW > 0f && containerH > 0f) {

            // ⚠️ 必须用 rememberUpdatedState 包一层。
            //
            // pointerInput(Unit) 的 key 是 Unit —— 这个块**组合一次后就再也不会重启**。
            // 直接引用外面那几个参数的话，lambda 里拿到的永远是**首次组合时**的值：
            // 第一次拖动看着正常，一松手再拖，它又从最初的偏移开始算 ——
            // 表现就是「往回弹、根本拖不动」（用户反馈的原话）。
            //
            // rememberUpdatedState 给的是同一个 State 对象，每次重组只更新 .value，
            // 所以 lambda 里读到的始终是当前值。
            val liveScale by rememberUpdatedState(scale)
            val liveOffsetX by rememberUpdatedState(offsetX)
            val liveOffsetY by rememberUpdatedState(offsetY)
            val liveTransform by rememberUpdatedState(onTransform)

            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // pan 是「相对上一次事件」的位移增量，所以这里是累加
                            liveTransform(
                                (liveScale * zoom).coerceIn(1f, maxScale),
                                liveOffsetX + pan.x / containerW,
                                liveOffsetY + pan.y / containerH
                            )
                        }
                    }
            )
        }
    }
}
