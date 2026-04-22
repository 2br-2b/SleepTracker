package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import codegito.xyz.healthconnector.DragReorderState

/**
 * Reusable drag-reorderable row with enable/disable toggle.
 * Used for sleep stages, exercise types, nutrients, etc.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LazyItemScope.DraggableEditRow(
    name: String,
    isEnabled: Boolean,
    isDragged: Boolean,
    dragReorderState: DragReorderState,
    onToggleEnabled: (Boolean) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    icon: String? = null,
    onIconClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isDragged) {
                    Modifier
                        .zIndex(1f)
                        .graphicsLayer {
                            translationY = dragReorderState.draggedOffset
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                } else {
                    Modifier.animateItem()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) MaterialTheme.colorScheme.surfaceVariant
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Drag to reorder",
                    modifier = Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDrag = { change, offset ->
                                change.consume()
                                onDrag(offset.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() }
                        )
                    }
                )

                if (icon != null) {
                    Text(
                        text = icon,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = if (onIconClick != null) {
                            Modifier.clickable { onIconClick() }.padding(4.dp)
                        } else {
                            Modifier.padding(4.dp)
                        }
                    )
                }

                Column {
                    Text(text = name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (isEnabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(checked = isEnabled, onCheckedChange = onToggleEnabled)
        }
    }
}
