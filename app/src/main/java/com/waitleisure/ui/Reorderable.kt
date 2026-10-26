package com.waitleisure.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * LazyColumn 的「长按拖动排序」。
 *
 * 为什么自己写：Compose 没有内置可重排列表，而引第三方库要动依赖树。
 * 这份实现只干一件事 —— 长按把条目拎起来、跟着手指走、路过别人就跟它换位。
 *
 * 用法：
 * ```
 * val dragState = rememberDragDropState(listState) { from, to -> vm.move(from, to) }
 * LazyColumn(state = listState, modifier = Modifier.dragContainer(dragState)) {
 *     itemsIndexed(items) { index, item ->
 *         Row(Modifier.draggableItem(dragState, index)) { ... }
 *     }
 * }
 * ```
 *
 * **必须用 itemsIndexed** —— 换位时靠 index 才知道拎的是谁。
 */
class DragDropState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit
) {
    /** 正在被拖的条目下标；null = 没在拖。 */
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    /** 拖动中的竖直位移（像素），渲染时用 graphicsLayer 平移。 */
    var draggingOffset by mutableFloatStateOf(0f)
        private set

    /** 拖到边缘时的自动滚动：累计的越界量。 */
    private var scrollAccumulator = 0f
    private val scrollThreshold = 48.dp

    fun onDragStart(offset: Offset) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            offset.y in info.offset.toFloat()..(info.offset + info.size).toFloat()
        }
        draggingOffset = 0f
        scrollAccumulator = 0f
        draggingIndex = item?.index
    }

    fun onDrag(delta: Offset, density: Float) {
        val current = draggingIndex ?: return
        draggingOffset += delta.y

        val item = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == current } ?: return

        // 被拖条目的当前视觉中心
        val centerY = item.offset + draggingOffset + item.size / 2f
        // 中心越过某个邻居的区间就换位
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            other.index != current &&
                centerY in other.offset.toFloat()..(other.offset + other.size).toFloat()
        }
        if (target != null) {
            onMove(current, target.index)
            // 换位后坐标基准变了，清掉累计位移，避免条目"跳一下"
            draggingOffset = 0f
            draggingIndex = target.index
            return
        }

        // 拖到可视区边缘时自动滚动（长列表必需，否则拖不出屏幕）
        val threshold = scrollThreshold.value * density
        val top = listState.layoutInfo.viewportStartOffset + threshold
        val bottom = listState.layoutInfo.viewportEndOffset - threshold
        val viewportY = item.offset + draggingOffset

        if (viewportY < top || viewportY + item.size > bottom) {
            scrollAccumulator += delta.y
        } else {
            scrollAccumulator = 0f
        }
        if (abs(scrollAccumulator) > threshold) {
            scope.launch { listState.scrollBy(scrollAccumulator) }
            scrollAccumulator = 0f
        }
    }

    fun onDragInterrupted() {
        draggingIndex = null
        draggingOffset = 0f
        scrollAccumulator = 0f
    }
}

@Composable
fun rememberDragDropState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit
): DragDropState {
    val scope = rememberCoroutineScope()
    return remember(listState) { DragDropState(listState, scope, onMove) }
}

/** 挂在 LazyColumn 上，负责识别长按拖动。 */
fun Modifier.dragContainer(dragDrop: DragDropState): Modifier {
    return pointerInput(dragDrop) {
        val density = this.density
        detectDragGesturesAfterLongPress(
            onDrag = { change, offset ->
                change.consume()
                dragDrop.onDrag(offset, density)
            },
            onDragStart = { offset -> dragDrop.onDragStart(offset) },
            onDragEnd = { dragDrop.onDragInterrupted() },
            onDragCancel = { dragDrop.onDragInterrupted() }
        )
    }
}

/** 挂在每个条目上：被拖的那一条要抬高（zIndex）并跟着手指平移。 */
fun Modifier.draggableItem(dragDrop: DragDropState, index: Int): Modifier =
    this
        .zIndex(if (dragDrop.draggingIndex == index) 1f else 0f)
        .graphicsLayer {
            translationY = if (dragDrop.draggingIndex == index) dragDrop.draggingOffset else 0f
        }
