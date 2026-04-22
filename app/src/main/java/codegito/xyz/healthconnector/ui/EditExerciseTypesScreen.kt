package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.exercise.domain.DefaultExerciseTypes
import codegito.xyz.healthconnector.exercise.domain.ExerciseTypeConfig
import codegito.xyz.healthconnector.rememberDragReorderState
import kotlinx.coroutines.launch
import java.util.Collections

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditExerciseTypesScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val configFlow = userPreferencesRepository.exerciseTypeConfig.collectAsState(initial = emptyList())
    val showRecentAtTop by userPreferencesRepository.exerciseShowRecentAtTop.collectAsState(initial = false)

    var config by remember { mutableStateOf<List<ExerciseTypeConfig>>(emptyList()) }

    LaunchedEffect(configFlow.value) {
        if (config.isEmpty() && configFlow.value.isNotEmpty()) {
            config = configFlow.value
        }
    }

    val dragReorderState = rememberDragReorderState()

    fun save() {
        scope.launch { userPreferencesRepository.saveExerciseTypeConfig(config) }
    }

    val typeById = remember { DefaultExerciseTypes.all.associateBy { it.id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Exercise Types") },
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
            item(key = "recent_at_top_toggle") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Show 5 most recent at top") },
                        supportingContent = { Text("Pin your last 5 used exercise types to the top of the picker") },
                        trailingContent = {
                            Switch(
                                checked = showRecentAtTop,
                                onCheckedChange = {
                                    scope.launch { userPreferencesRepository.setExerciseShowRecentAtTop(it) }
                                }
                            )
                        }
                    )
                }
            }

            item(key = "order_header") {
                Text(
                    "Order & Visibility",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            itemsIndexed(config, key = { _, item -> item.typeId }) { index, typeConfig ->
                val exerciseType = typeById[typeConfig.typeId] ?: return@itemsIndexed
                val isDragged = dragReorderState.draggedIndex == index

                DraggableEditRow(
                    name = exerciseType.displayName,
                    icon = exerciseType.icon,
                    isEnabled = typeConfig.isEnabled,
                    isDragged = isDragged,
                    dragReorderState = dragReorderState,
                    onToggleEnabled = { isEnabled ->
                        config = config.toMutableList().apply {
                            this[index] = typeConfig.copy(isEnabled = isEnabled)
                        }
                        save()
                    },
                    onDragStart = { dragReorderState.onDragStart(index) },
                    onDrag = { deltaY ->
                        dragReorderState.onDrag(deltaY, config.size) { from, to ->
                            val mutable = config.toMutableList()
                            Collections.swap(mutable, from, to)
                            config = mutable
                        }
                    },
                    onDragEnd = {
                        dragReorderState.reset()
                        save()
                    }
                )
            }
        }
    }
}
