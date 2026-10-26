package com.waitleisure.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * 带**描边**的文字：同一段文字画两层 —— 底层是深色描边，上层是填充色。
 *
 * ## 为什么不用渐变遮罩
 *
 * 锁机页原来直接在白壁纸上压白字，浅色/花哨的壁纸就糊成一片。
 * 常规做法是给整屏加一层黑色渐变，但那会把壁纸压暗 —— 用户明确要求
 * **不要影响壁纸观感**，所以改从文字入手：加一圈细描边，
 * 无论底下是白墙还是繁花，字的轮廓都立得住。
 *
 * 描边宽度默认 2.5f，视觉上刚好是一条发丝线 —— 远看几乎察觉不到，
 * 近看能把白色填充和浅色背景隔开。
 *
 * ## 为什么用 Box 叠两层而不是 drawStyle 单独画
 *
 * Compose 的 `TextStyle(drawStyle = Stroke(...))` 只画描边、**不填充**。
 * 想要"描边 + 填充"就得画两次，且两次的排版必须完全一致 ——
 * 所以两个 [Text] 放在同一个 [Box] 里，参数一模一样，只是颜色不同。
 *
 * @param strokeColor 描边颜色。默认半透明黑 —— 比纯黑柔和，不会像贴纸。
 * @param strokeWidth 描边宽度（像素）。字号越大可以稍宽。
 */
@Composable
fun OutlinedText(
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
    strokeColor: Color = Color(0xB3000000),
    strokeWidth: Float = 2.5f
) {
    Box(modifier = modifier) {
        // 底层：只描边，不填充
        Text(
            text = text,
            color = strokeColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            lineHeight = lineHeight,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            style = TextStyle(drawStyle = Stroke(width = strokeWidth))
        )
        // 上层：正常填充
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            lineHeight = lineHeight,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}
