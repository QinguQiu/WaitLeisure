package com.waitleisure.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope

/**
 * `LazyVerticalGrid` 的长按拖动排序 —— [DragDropState] 的网格版。
 *
 * ## 和竖列表版的区别
 *
 * 竖列表只需要比 `y`；网格是二维的，目标位置要同时看 `x` 和 `y`。
 * 做法不是"落进哪个格子就算哪个"（拖到间隙里会判不出来），
 * 而是**用被拖条目的中心点**去撞其它条目 —— 中心进了谁的范围就跟谁换。
 * 这样手指在格子缝隙里移动时也不会卡住。
 *
 * ## 用法
 * ```
 * val dragState = rememberGridDragDropState(gridState) { from, to -> repo.move(from, to) }
 * LazyVerticalGrid(
 *     state = gridState,
 *     modifier = Modifier.gridDragContainer(dragState)
 * ) {
 *     itemsIndexed(items, key = { _, it -> it }) { index, item ->
 *         Box(Modifier.gridDraggableItem(dragState, index)) { ... }
 *     }
 * }
 * ```
 *
 * 和竖列表版一样：**必须用 itemsIndexed**，且前面不能有非数据 item
 * （有的话要自己在下标上减掉，参照 [DragDropState] 的 headerCount）。
 */
class GridDragDropState(
    private val gridState: LazyGridState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit
) {
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    /** 拖动中的二维位移，渲染时用 graphicsLayer 平移。 */
    var draggingOffset by mutableStateOf(Offset.Zero)
        private set

    /** 按下点相对条目左上角的偏移 —— 绝对定位的基准。 */
    private var grabOffset = Offset.Zero

    private fun itemAt(pos: Offset) = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        pos.x in info.offset.x.toFloat()..(info.offset.x + info.size.width).toFloat() &&
            pos.y in info.offset.y.toFloat()..(info.offset.y + info.size.height).toFloat()
    }

    private fun topLeftOf(index: Int): Offset? =
        gridState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }
            ?.let { Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) }

    fun onDragStart(pos: Offset) {
        val item = itemAt(pos) ?: return
        val topLeft = Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
        grabOffset = pos - topLeft
        draggingOffset = Offset.Zero
        draggingIndex = item.index
    }

    fun onDrag(pos: Offset) {
        val current = draggingIndex ?: return
        val topLeft = topLeftOf(current) ?: return

        // 条目左上角本该在的位置 = 指针 - 抓取偏移（绝对定位，不累加）
        val targetTopLeft = pos - grabOffset
        draggingOffset = targetTopLeft - topLeft

        val second = gridState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == current } ?: return
        // 用中心点去撞，避免落到格子缝隙里判不出来
        val center = targetTopLeft + Offset(
            second.size.width / 2f,
            second.size.height / 2f
        )

        val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            info.index != current &&
                center.x in info.offset.x.toFloat()..(info.offset.x + info.size.width).toFloat() &&
                center.y in info.offset.y.toFloat()..(info.offset.y + info.size.height).toFloat()
        }

        if (target != null) {
            val targetIndex = target.index
            val targetTopLeftOffset = Offset(target.offset.x.toFloat(), target.offset.y.toFloat())
            onMove(current, targetIndex)
            draggingIndex = targetIndex
            // 换位后立即按目标槽位重算，避免"弹跳"
            draggingOffset = targetTopLeft - targetTopLeftOffset
        }
    }

    fun onDragEnd() {
        draggingIndex = null
        draggingOffset = Offset.Zero
        grabOffset = Offset.Zero
    }
}

@Composable
fun rememberGridDragDropState(
    gridState: LazyGridState,
    onMove: (from: Int, to: Int) -> Unit
): GridDragDropState {
    val scope = rememberCoroutineScope()
    return remember(gridState) { GridDragDropState(gridState, scope, onMove) }
}

/** 挂在 LazyVerticalGrid 上，识别长按拖动。 */
fun Modifier.gridDragContainer(gridDrop: GridDragDropState): Modifier =
    pointerInput(gridDrop) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset -> gridDrop.onDragStart(offset) },
            onDrag = { change, _ ->
                change.consume()
                gridDrop.onDrag(change.position)
            },
            onDragEnd = { gridDrop.onDragEnd() },
            onDragCancel = { gridDrop.onDragEnd() }
        )
    }

/** 挂在每个格子上：被拖的那一格抬高并跟着手指走。 */
fun Modifier.gridDraggableItem(gridDrop: GridDragDropState, index: Int): Modifier =
    this
        .zIndex(if (gridDrop.draggingIndex == index) 1f else 0f)
        .graphicsLayer {
            if (gridDrop.draggingIndex == index) {
                translationX = gridDrop.draggingOffset.x
                translationY = gridDrop.draggingOffset.y
            }
        }
