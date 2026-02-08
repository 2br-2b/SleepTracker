package codegito.xyz.healthconnector

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*

class DragReorderState(
    val lazyListState: LazyListState
) {
    var draggedIndex by mutableIntStateOf(-1)
    var draggedOffset by mutableFloatStateOf(0f)

    val isDragging: Boolean get() = draggedIndex >= 0

    fun onDragStart(index: Int) {
        draggedIndex = index
        draggedOffset = 0f
    }

    fun onDrag(deltaY: Float, itemCount: Int, onSwap: (Int, Int) -> Unit) {
        draggedOffset += deltaY

        val itemHeight = lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull()?.size?.toFloat() ?: return

        if (draggedOffset > itemHeight * 0.5f && draggedIndex < itemCount - 1) {
            val target = draggedIndex + 1
            onSwap(draggedIndex, target)
            draggedIndex = target
            draggedOffset -= itemHeight
        } else if (draggedOffset < -itemHeight * 0.5f && draggedIndex > 0) {
            val target = draggedIndex - 1
            onSwap(draggedIndex, target)
            draggedIndex = target
            draggedOffset += itemHeight
        }
    }

    fun reset() {
        draggedIndex = -1
        draggedOffset = 0f
    }
}

@Composable
fun rememberDragReorderState(): DragReorderState {
    val lazyListState = rememberLazyListState()
    return remember { DragReorderState(lazyListState) }
}
