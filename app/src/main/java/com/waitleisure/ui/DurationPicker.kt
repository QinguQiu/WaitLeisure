package com.waitleisure.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「时 / 分」两列数字选择器。
 *
 * 为什么不用 Slider：Slider 的步进做不到 1 分钟（要么很密拖不准，要么 5 分钟一跳），
 * 而「只想锁 1 分钟」这种需求恰恰需要精确到个位数。
 * 两列列表点一下就到位：想锁 1 分钟就选「0 时 / 01 分」。
 *
 * 下拉列表用 LazyColumn 而不是 NumberPicker：
 * 后者是 View 体系的东西，要通过 AndroidView 桥接，为这点交互不值得引入那层复杂度。
 *
 * @param totalMinutes 当前选中的总分钟数
 * @param maxMinutes   上限，超出的选择会被夹住
 * @param minMinutes   下限，默认 1 分钟（不允许选到 0，否则等于没锁）
 */
@Composable
fun DurationPicker(
    totalMinutes: Int,
    maxMinutes: Int,
    modifier: Modifier = Modifier,
    minMinutes: Int = 1,
    /**
     * 「小时」列是否首尾相接（滚过 23 接回 0）。
     *
     * 只在**选时刻**的场景开（定时页的起始/结束时间）—— 那时小时是钟点，
     * 23 之后本来就该是 0。选**时长**时（立即锁机、番茄钟）关掉，
     * 因为「锁 24 小时再接着锁 0 小时」没有意义。
     */
    wrapHours: Boolean = false,
    onSelect: (Int) -> Unit
) {
    val safeTotal = totalMinutes.coerceIn(minMinutes, maxMinutes)
    val hours = safeTotal / 60
    val minutes = safeTotal % 60
    val maxHours = maxMinutes / 60

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NumberColumn(
            label = "小时",
            values = (0..maxHours).toList(),
            selected = hours,
            modifier = Modifier.weight(1f),
            wrap = wrapHours,
            onSelect = { h -> onSelect((h * 60 + minutes).coerceIn(minMinutes, maxMinutes)) }
        )
        NumberColumn(
            label = "分钟",
            values = (0..59).toList(),
            selected = minutes,
            modifier = Modifier.weight(1f),
            onSelect = { m -> onSelect((hours * 60 + m).coerceIn(minMinutes, maxMinutes)) }
        )
    }
}

@Composable
private fun NumberColumn(
    label: String,
    values: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 可循环：列表首尾相接，滚过最后一个值会接着出现第一个。
     *
     * 定时页的「时」列要这个效果 —— 23 点之后接着 0 点，符合"时间是圆的"直觉；
     * 「立即锁机」那边是时长（锁多久），滚到头就该停住，所以默认 false。
     */
    wrap: Boolean = false
) {
    // 初始滚动位置对准当前值，免得每次打开都要手动翻
    val startIndex = values.indexOf(selected).let { if (it < 0) 0 else it }
    val span = values.size

    // 循环实现：把列表复制很多份，从中间那份开始。
    // 不追求真正的无穷 —— 1000 个来回（24×1000 ≈ 24000 行）足够一个人点一辈子了，
    // 而最近重排索引的动画/闪动代价不值得为它付。
    val copies = if (wrap) 1000 else 1
    val itemCount = span * copies
    val baseIndex = if (wrap) span * (copies / 2) + startIndex else startIndex

    val state = rememberLazyListState(initialFirstVisibleItemIndex = baseIndex)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(itemCount, key = { it }) { i ->
                val value = values[if (wrap) i % span else i]
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(value) }
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            }
                        )
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "%02d".format(value),
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}
