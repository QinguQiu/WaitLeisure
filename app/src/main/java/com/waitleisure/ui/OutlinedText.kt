package com.waitleisure.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
 * 描边用**纯黑、1.5f**：半透明黑看起来像"投影"（用户明确不要那个观感），
 * 实心黑的细线才是轮廓。宽度再大就会有字幕感。
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
    strokeColor: Color = Color(0xFF000000),
    strokeWidth: Float = 1.5f
) {
    // 两层必须同宽同对齐：描边层因为 Stroke 会把测量宽度撑大一点，
    // 若都靠左排，两层中心就差一两像素 —— 视觉上就是重影。
    Box(modifier = modifier) {
        val layer = Modifier.fillMaxWidth()
        // 两层必须用**同一个行高**。
        //
        // Stroke 会让描边层的测量高度比填充层大一点点，行高不同 → 基线错开
        // → 看起来就是上下重影（真机上 11sp 的小字特别明显）。
        // 所以不指定行高时自己按字号定一个，两层都用它。
        val lh = if (lineHeight == TextUnit.Unspecified) fontSize * 1.2f else lineHeight
        // 底层：只描边，不填充
        Text(
            text = text,
            modifier = layer,
            color = strokeColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            lineHeight = lh,
            textAlign = textAlign ?: TextAlign.Center,
            maxLines = maxLines,
            overflow = overflow,
            style = TextStyle(drawStyle = Stroke(width = strokeWidth))
        )
        // 上层：正常填充
        Text(
            text = text,
            modifier = layer,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            lineHeight = lh,
            textAlign = textAlign ?: TextAlign.Center,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}
