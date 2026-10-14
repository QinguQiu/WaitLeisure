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
    modifier: Modifier = Modifier
) {
    // 初始滚动位置对准当前值，免得每次打开都要手动翻
    val startIndex = values.indexOf(selected).let { if (it < 0) 0 else it }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)

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
            items(values, key = { it }) { value ->
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
