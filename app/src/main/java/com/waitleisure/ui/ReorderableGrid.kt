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
 * ## 拖到顶部删除
 *
 * 网格上方可以留一条"删除区"（[deleteZoneBottom]）。指针进到那条区域内时
 * [overDeleteZone] 变 true，松手就调 [onDrop] 并带上 `overDelete = true`，
 * 由调用方决定删不删 —— 组件本身不碰数据。
 *
 * ## 用法
 * ```
 * val dragState = rememberGridDragDropState(
 *     gridState = gridState,
 *     onMove = { from, to -> ... },
 *     onDrop = { index, overDelete -> if (overDelete) delete(index) }
 * )
 * val layout = gridState.layoutInfo
 * dragState.deleteZoneBottom = layout.viewportStartOffset + 96.dp.toPx()
 *
 * LazyVerticalGrid(state = gridState, modifier = Modifier.gridDragContainer(dragState)) {
 *     itemsIndexed(items, key = { _, it -> it }) { index, item ->
 *         Box(Modifier.gridDraggableItem(dragState, index)) { ... }
 *     }
 * }
 * ```
 *
 * 和竖列表版一样：**必须用 itemsIndexed**，且前面不能有非数据 item。
 */
class GridDragDropState(
    private val gridState: LazyGridState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: (index: Int, overDelete: Boolean) -> Unit
) {
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    /** 拖动中的二维位移，渲染时用 graphicsLayer 平移。 */
    var draggingOffset by mutableStateOf(Offset.Zero)
        private set

    /**
     * 指针是否落在顶部删除区里。
     *
     * 删除区的高度由调用方设置 —— 组件不知道 UI 长什么样，
     * 只知道"这条线以上算删除区"。
     */
    var overDeleteZone by mutableStateOf(false)
        private set

    /** 删除区的下边界（网格坐标系里的像素 Y）。<= 0 表示没有删除区。 */
    var deleteZoneBottom: Float = 0f

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
        overDeleteZone = false
        draggingIndex = item.index
    }

    fun onDrag(pos: Offset) {
        val current = draggingIndex ?: return
        val topLeft = topLeftOf(current) ?: return

        // 绝对定位：每帧都从指针位置反推，误差不会累积
        val targetTopLeft = pos - grabOffset
        draggingOffset = targetTopLeft - topLeft

        // 指针进了删除区就只标状态，不再跟别的格子换位 ——
        // 否则一边换位一边删除，松手时下标早就变了，容易删错东西。
        val inDelete = deleteZoneBottom > 0f && pos.y < deleteZoneBottom
        overDeleteZone = inDelete
        if (inDelete) return

        val self = gridState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == current } ?: return
        val center = targetTopLeft + Offset(
            self.size.width / 2f,
            self.size.height / 2f
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
        val index = draggingIndex
        val wasOverDelete = overDeleteZone
        draggingIndex = null
        draggingOffset = Offset.Zero
        grabOffset = Offset.Zero
        overDeleteZone = false
        if (index != null) onDrop(index, wasOverDelete)
    }
}

@Composable
fun rememberGridDragDropState(
    gridState: LazyGridState,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: (index: Int, overDelete: Boolean) -> Unit
): GridDragDropState {
    val scope = rememberCoroutineScope()
    return remember(gridState) { GridDragDropState(gridState, scope, onMove, onDrop) }
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
                // 拖到删除区时缩小一点，暗示"要没了"
                val scale = if (gridDrop.overDeleteZone) 0.7f else 1f
                scaleX = scale
                scaleY = scale
                alpha = if (gridDrop.overDeleteZone) 0.5f else 1f
                translationX = gridDrop.draggingOffset.x
                translationY = gridDrop.draggingOffset.y
            }
        }
