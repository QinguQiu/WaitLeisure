package com.waitleisure.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * 带**零偏移光晕**的文字 —— 锁机页专用。
 *
 * ## 为什么不是"描边"
 *
 * 之前试过用两层 [Text] 叠描边(那个组件已删除)，四个版本都没洗干净重影：
 * 描边层的测量高度、宽度和填充层总有细微差别，两层叠起来就基错位。
 *
 * 现在改成**只画一次** + `Shadow(offset = Zero, blurRadius = ...)`：
 * 阴影不从字形上挪开，只在周围糊一圈暗边 —— 视觉上就是描边，
 * 但物理上只有一次绘制，不可能错位。
 *
 * ## 为什么不用渐变遮罩
 *
 * 那会把壁纸压暗，用户明确不要影响壁纸观感。
 */
@Composable
fun HaloText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    /** 光晕颜色。半透明黑比纯黑柔和。 */
    haloColor: Color = Color(0xB3000000),
    /** 光晕模糊半径（像素）。字号越大可以稍大。 */
    haloRadius: Float = 6f
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        lineHeight = lineHeight,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        style = TextStyle(
            shadow = Shadow(color = haloColor, offset = Offset.Zero, blurRadius = haloRadius)
        )
    )
}
