package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import codegito.xyz.healthconnector.DragReorderState
import codegito.xyz.healthconnector.data.SleepStageConfig
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.rememberDragReorderState
import kotlinx.coroutines.launch
import java.util.Collections

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSleepStagesScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val stagesFlow = userPreferencesRepository.sleepStages.collectAsState(initial = emptyList())
    
    // Local state for reordering to feel responsive before saving
    var stages by remember { mutableStateOf<List<SleepStageConfig>>(emptyList()) }
    
    // Update local state when flow emits new data (only if not already editing/dragging presumably, 
    // but simplest is to sync initially and then manage locally until save/back)
    // Actually, we should probably save on every change or have a "Save" button. 
    // Let's autosave on reorder end or toggle.
    LaunchedEffect(stagesFlow.value) {
        if (stages.isEmpty() && stagesFlow.value.isNotEmpty()) {
            stages = stagesFlow.value
        }
    }

    val dragReorderState = rememberDragReorderState()
    
    var showingEmojiDialogForStageIndex by remember { mutableStateOf<Int?>(null) }
    var emojiInput by remember { mutableStateOf("") }

    // Helper to save current state
    fun save() {
        scope.launch {
            userPreferencesRepository.saveSleepStages(stages)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Sleep Stages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            state = dragReorderState.lazyListState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(stages, key = { _, item -> item.healthConnectType }) { index, stage ->
                val isDragged = dragReorderState.draggedIndex == index
                
                SleepStageEditRow(
                    stage = stage,
                    isDragged = isDragged,
                    dragReorderState = dragReorderState,
                    index = index,
                    onToggleEnabled = { isEnabled ->
                        stages = stages.toMutableList().apply {
                            this[index] = stage.copy(isEnabled = isEnabled)
                        }
                        save()
                    },
                    onEmojiClick = {
                        emojiInput = stage.emoji
                        showingEmojiDialogForStageIndex = index
                    },
                    onDragStart = { dragReorderState.onDragStart(index) },
                    onDrag = { deltaY ->
                        dragReorderState.onDrag(deltaY, stages.size) { from, to ->
                            val mutable = stages.toMutableList()
                            Collections.swap(mutable, from, to)
                            stages = mutable
                        }
                    },
                    onDragEnd = {
                        dragReorderState.reset()
                        save() // Save after reorder
                    }
                )
            }
        }
    }

    showingEmojiDialogForStageIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { showingEmojiDialogForStageIndex = null },
            title = { Text("Edit Emoji") },
            text = {
                OutlinedTextField(
                    value = emojiInput,
                    onValueChange = { emojiInput = it },
                    label = { Text("Emoji") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    stages = stages.toMutableList().apply {
                        val currentStage = stages[index]
                        val finalEmoji = if (emojiInput == SleepStageConfig.getStageEmoji(currentStage.healthConnectType)) null else emojiInput
                        this[index] = currentStage.copy(customEmoji = finalEmoji)
                    }
                    save()
                    showingEmojiDialogForStageIndex = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showingEmojiDialogForStageIndex = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LazyItemScope.SleepStageEditRow(
    stage: SleepStageConfig,
    isDragged: Boolean,
    dragReorderState: DragReorderState,
    index: Int,
    onToggleEnabled: (Boolean) -> Unit,
    onEmojiClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
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
                    Modifier.animateItemPlacement() // Requires ExperimentalFoundationApi in strict mode but usually fine
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (stage.isEnabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                // Drag Handle
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

                // Emoji
                Text(
                    text = stage.emoji,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .clickable { onEmojiClick() }
                        .padding(4.dp)
                )

                // Name
                Column {
                    Text(
                        text = stage.name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (stage.isEnabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Toggle
            Switch(
                checked = stage.isEnabled,
                onCheckedChange = onToggleEnabled
            )
        }
    }
}
