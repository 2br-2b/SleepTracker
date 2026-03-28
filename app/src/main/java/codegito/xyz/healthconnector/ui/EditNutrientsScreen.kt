package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.nutrition.domain.NutrientConfig
import codegito.xyz.healthconnector.nutrition.domain.NutrientDefaults
import codegito.xyz.healthconnector.nutrition.domain.NutrientKey
import codegito.xyz.healthconnector.rememberDragReorderState
import kotlinx.coroutines.launch
import java.util.Collections

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditNutrientsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val nutrientConfigFlow = userPreferencesRepository.nutrientConfig.collectAsState(initial = emptyList())

    // All nutrients are shown and configurable (both core and extra)
    var displayItems by remember { mutableStateOf<List<NutrientConfig>>(emptyList()) }

    LaunchedEffect(nutrientConfigFlow.value) {
        if (displayItems.isEmpty() && nutrientConfigFlow.value.isNotEmpty()) {
            displayItems = nutrientConfigFlow.value
        }
    }

    val dragReorderState = rememberDragReorderState()

    // Save the full ordered list
    fun save() {
        scope.launch {
            userPreferencesRepository.saveNutrientConfig(displayItems)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nutrients") },
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
            item {
                Text(
                    "Drag to reorder nutrients and toggle which ones are tracked and shown in summaries.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            itemsIndexed(displayItems, key = { _, item -> item.key }) { index, cfg ->
                val isDragged = dragReorderState.draggedIndex == index
                val key = runCatching { NutrientKey.valueOf(cfg.key) }.getOrNull()
                val name = key?.let { NutrientDefaults.displayName[it] } ?: cfg.key
                val unit = key?.let { NutrientDefaults.displayUnit[it] }?.let { " ($it)" } ?: ""

                NutrientEditRow(
                    name = "$name$unit",
                    isEnabled = cfg.enabled,
                    isDragged = isDragged,
                    dragReorderState = dragReorderState,
                    index = index,
                    onToggleEnabled = { isEnabled ->
                        displayItems = displayItems.toMutableList().apply {
                            this[index] = cfg.copy(enabled = isEnabled)
                        }
                        save()
                    },
                    onDragStart = { dragReorderState.onDragStart(index) },
                    onDrag = { deltaY ->
                        dragReorderState.onDrag(deltaY, displayItems.size) { from, to ->
                            val mutable = displayItems.toMutableList()
                            Collections.swap(mutable, from, to)
                            displayItems = mutable
                        }
                    },
                    onDragEnd = {
                        dragReorderState.reset()
                        save()
                    }
                )
            }

            item {
                OutlinedButton(
                    onClick = {
                        displayItems = emptyList() // Will reload from default via LaunchedEffect
                        scope.launch {
                            userPreferencesRepository.saveNutrientConfig(NutrientDefaults.defaultConfig())
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reset to defaults") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LazyItemScope.NutrientEditRow(
    name: String,
    isEnabled: Boolean,
    isDragged: Boolean,
    dragReorderState: DragReorderState,
    index: Int,
    onToggleEnabled: (Boolean) -> Unit,
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
                Column {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (isEnabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggleEnabled
            )
        }
    }
}
