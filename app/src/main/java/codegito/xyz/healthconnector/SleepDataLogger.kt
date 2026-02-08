package codegito.xyz.healthconnector

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.health.connect.client.records.SleepSessionRecord
import codegito.xyz.healthconnector.ui.theme.SleepTrackerTheme
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val SLEEP_STAGES = listOf(
    "Awake" to SleepSessionRecord.STAGE_TYPE_AWAKE,
    "Sleeping" to SleepSessionRecord.STAGE_TYPE_SLEEPING,
    "Out of Bed" to SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    "Light Sleep" to SleepSessionRecord.STAGE_TYPE_LIGHT,
    "Deep Sleep" to SleepSessionRecord.STAGE_TYPE_DEEP,
    "REM Sleep" to SleepSessionRecord.STAGE_TYPE_REM,
    "Unknown" to SleepSessionRecord.STAGE_TYPE_UNKNOWN
)

class DragReorderState(
    val lazyListState: androidx.compose.foundation.lazy.LazyListState
) {
    var draggedIndex by mutableIntStateOf(-1)
    var draggedOffset by mutableFloatStateOf(0f)

    fun onDragStart(index: Int) {
        draggedIndex = index
        draggedOffset = 0f
    }

    fun onDrag(deltaY: Float) {
        draggedOffset += deltaY
    }

    fun calculateTargetIndex(itemCount: Int): Int {
        if (draggedIndex < 0) return -1
        val itemHeight = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: return draggedIndex
        val offsetItems = (draggedOffset / itemHeight).toInt()
        return (draggedIndex + offsetItems).coerceIn(0, itemCount - 1)
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

class SleepDataLogger : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SleepTrackerTheme {
                SleepLogScreen(
                    onSave = { sleepLog ->
                        // TODO: Save to Health Connect
                        Toast.makeText(this, "Saved sleep data", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onCancel = {
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepLogScreen(
    onSave: (SleepLog) -> Unit,
    onCancel: () -> Unit
) {
    var bedtime by remember { mutableStateOf(LocalDateTime.now().minusHours(8)) }
    var segments by remember {
        mutableStateOf(
            listOf(
                SleepSegment(
                    endTime = LocalDateTime.now(),
                    sleepStage = SleepSessionRecord.STAGE_TYPE_SLEEPING
                )
            )
        )
    }
    val context = LocalContext.current
    var showBedtimePicker by remember { mutableStateOf(false) }
    var editingSegmentIndex by remember { mutableStateOf<Int?>(null) }
    var editingDurationIndex by remember { mutableStateOf<Int?>(null) }
    var durationInputText by remember { mutableStateOf("") }
    var showAddSegmentDialog by remember { mutableStateOf(false) }

    val dragReorderState = rememberDragReorderState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Bedtime Card
            BedtimeCard(
                bedtime = bedtime,
                onEditClick = { showBedtimePicker = true }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Segments List with Add button at bottom
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = dragReorderState.lazyListState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(segments) { index, segment ->
                    val startTime = if (index == 0) bedtime else segments[index - 1].endTime
                    val duration = Duration.between(startTime, segment.endTime).toMinutes()

                    val isDragged = dragReorderState.draggedIndex == index

                    SleepSegmentCard(
                        segment = segment,
                        durationMinutes = duration,
                        onStageChanged = { newStage ->
                            val updated = segments.toMutableList()
                            updated[index] = segment.copy(sleepStage = newStage)
                            segments = updated
                        },
                        onRemove = {
                            segments = segments.filterIndexed { i, _ -> i != index }
                        },
                        onDurationClick = {
                            editingDurationIndex = index
                            durationInputText = duration.toString()
                        },
                        onTimestampClick = {
                            editingSegmentIndex = index
                        },
                        modifier = if (isDragged) {
                            Modifier
                                .zIndex(1f)
                                .graphicsLayer {
                                    translationY = dragReorderState.draggedOffset
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                }
                        } else {
                            Modifier
                        },
                        onDragStart = { dragReorderState.onDragStart(index) },
                        onDrag = { deltaY -> dragReorderState.onDrag(deltaY) },
                        onDragEnd = {
                            val targetIndex = dragReorderState.calculateTargetIndex(segments.size)
                            if (targetIndex >= 0 && targetIndex != dragReorderState.draggedIndex) {
                                val fromIndex = dragReorderState.draggedIndex
                                // Swap only the sleep stages, keeping endTimes fixed
                                val updated = segments.toMutableList()
                                val fromStage = updated[fromIndex].sleepStage
                                val toStage = updated[targetIndex].sleepStage
                                updated[fromIndex] = updated[fromIndex].copy(sleepStage = toStage)
                                updated[targetIndex] = updated[targetIndex].copy(sleepStage = fromStage)
                                segments = updated
                            }
                            dragReorderState.reset()
                        }
                    )
                }

                // Add Segment Button at bottom of list
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAddSegmentDialog = true
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add segment")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Timestamp", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                // Sleep Summary
                if (segments.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        val totalTimeInBedMinutes = Duration.between(bedtime, segments.last().endTime).toMinutes()
                        val totalTimeAsleepMinutes = segments.mapIndexed { index, segment ->
                            val start = if (index == 0) bedtime else segments[index - 1].endTime
                            val mins = Duration.between(start, segment.endTime).toMinutes()
                            if (segment.sleepStage != SleepSessionRecord.STAGE_TYPE_AWAKE &&
                                segment.sleepStage != SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
                            ) mins else 0L
                        }.sum()

                        SleepSummaryRow(
                            totalTimeInBedMinutes = totalTimeInBedMinutes,
                            totalTimeAsleepMinutes = totalTimeAsleepMinutes
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onSave(SleepLog(bedtime, segments)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    }

    // Bedtime Picker Dialog
    if (showBedtimePicker) {
        TimePickerDialog(
            title = "Select Bedtime",
            initialTime = bedtime,
            onConfirm = { newTime ->
                bedtime = newTime
                showBedtimePicker = false
            },
            onDismiss = { showBedtimePicker = false }
        )
    }

    // Edit Timestamp Dialog
    editingSegmentIndex?.let { index ->
        TimePickerDialog(
            title = "Edit Timestamp",
            initialTime = segments[index].endTime,
            onConfirm = { newTime ->
                val startTime = if (index == 0) bedtime else segments[index - 1].endTime
                val nextTime = segments.getOrNull(index + 1)?.endTime

                // Validate: must be after start time and before next timestamp
                if (newTime.isAfter(startTime) && (nextTime == null || newTime.isBefore(nextTime))) {
                    val updated = segments.toMutableList()
                    updated[index] = segments[index].copy(endTime = newTime)
                    segments = updated
                    editingSegmentIndex = null
                } else {
                    Toast.makeText(
                        context,
                        "Timestamp must be between ${startTime.format(DateTimeFormatter.ofPattern("h:mm a"))} and ${nextTime?.format(DateTimeFormatter.ofPattern("h:mm a")) ?: "now"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { editingSegmentIndex = null }
        )
    }

    // Edit Duration Dialog
    editingDurationIndex?.let { index ->
        val startTime = if (index == 0) bedtime else segments[index - 1].endTime

        DurationInputDialog(
            title = "Edit Duration (minutes)",
            initialValue = durationInputText,
            onConfirm = { minutes ->
                try {
                    val durationMinutes = minutes.toLong()
                    if (durationMinutes > 0) {
                        val newEndTime = startTime.plusMinutes(durationMinutes)
                        val nextTime = segments.getOrNull(index + 1)?.endTime

                        // Validate: new end time must be before next timestamp
                        if (nextTime == null || newEndTime.isBefore(nextTime)) {
                            val updated = segments.toMutableList()
                            updated[index] = segments[index].copy(endTime = newEndTime)
                            segments = updated
                            editingDurationIndex = null
                        } else {
                            Toast.makeText(
                                context,
                                "Duration would exceed next timestamp",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: NumberFormatException) {
                    Toast.makeText(context, "Invalid number", Toast.LENGTH_SHORT).show()
                }
            },
            onValueChange = { durationInputText = it },
            onDismiss = { editingDurationIndex = null }
        )
    }

    // Sleep Stage Picker Dialog (for adding new segments)
    if (showAddSegmentDialog) {
        SleepStagePickerDialog(
            onStageSelected = { stage ->
                segments = segments + SleepSegment(
                    endTime = LocalDateTime.now(),
                    sleepStage = stage
                )
                showAddSegmentDialog = false
            },
            onDismiss = { showAddSegmentDialog = false }
        )
    }
}

@Composable
fun BedtimeCard(
    bedtime: LocalDateTime,
    onEditClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Bedtime:",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = bedtime.format(DateTimeFormatter.ofPattern("h:mm a")),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            TextButton(onClick = onEditClick) {
                Text("Edit")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSegmentCard(
    segment: SleepSegment,
    durationMinutes: Long,
    onStageChanged: (Int) -> Unit,
    onRemove: () -> Unit,
    onDurationClick: () -> Unit,
    onTimestampClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val currentStage = SLEEP_STAGES.find { it.second == segment.sleepStage }?.first ?: "Unknown"

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Sleep Stage Dropdown, Duration, and Drag Handle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = currentStage,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sleep stage") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        SLEEP_STAGES.forEach { (name, stage) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onStageChanged(stage)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Clickable Duration with bigger touch target
                Text(
                    text = durationMinutes.formatDuration(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDurationClick() }
                        .padding(vertical = 8.dp, horizontal = 8.dp)
                )

                // Drag Handle
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
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
            }

            Spacer(modifier = Modifier.height(8.dp))

            // End Time and Remove Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clickable Timestamp with bigger touch target and larger text
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onTimestampClick() }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                ) {
                    Text(
                        text = "\u2192 until ${segment.endTime.format(DateTimeFormatter.ofPattern("h:mm a"))}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(onClick = onRemove) {
                    Text("Remove")
                }
            }
        }
    }
}

@Composable
fun SleepStagePickerDialog(
    onStageSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Sleep Stage") },
        text = {
            Column {
                SLEEP_STAGES.forEach { (name, stage) ->
                    TextButton(
                        onClick = { onStageSelected(stage) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = name,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SleepSummaryRow(
    totalTimeInBedMinutes: Long,
    totalTimeAsleepMinutes: Long
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Time in bed",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = totalTimeInBedMinutes.formatDuration(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Time asleep",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = totalTimeAsleepMinutes.formatDuration(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    title: String,
    initialTime: LocalDateTime,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = false
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            TextButton(onClick = {
                val newTime = initialTime
                    .withHour(timePickerState.hour)
                    .withMinute(timePickerState.minute)
                onConfirm(newTime)
            }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DurationInputDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = initialValue,
                onValueChange = onValueChange,
                label = { Text("Minutes") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(initialValue) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
