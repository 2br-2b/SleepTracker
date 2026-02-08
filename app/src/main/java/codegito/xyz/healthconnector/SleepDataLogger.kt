package codegito.xyz.healthconnector

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import java.util.Collections

val SLEEP_STAGES = listOf(
    "Awake" to SleepSessionRecord.STAGE_TYPE_AWAKE,
    "Sleeping" to SleepSessionRecord.STAGE_TYPE_SLEEPING,
    "Out of Bed" to SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    "Light Sleep" to SleepSessionRecord.STAGE_TYPE_LIGHT,
    "Deep Sleep" to SleepSessionRecord.STAGE_TYPE_DEEP,
    "REM Sleep" to SleepSessionRecord.STAGE_TYPE_REM,
    "Unknown" to SleepSessionRecord.STAGE_TYPE_UNKNOWN
)

fun sleepStageIcon(stageType: Int): String = when (stageType) {
    SleepSessionRecord.STAGE_TYPE_AWAKE -> "☀️"
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> "😴"
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "🚶"
    SleepSessionRecord.STAGE_TYPE_LIGHT -> "🌙"
    SleepSessionRecord.STAGE_TYPE_DEEP -> "💤"
    SleepSessionRecord.STAGE_TYPE_REM -> "👁️"
    else -> "❓"
}

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

fun validateSleepLog(bedtime: LocalDateTime, segments: List<SleepSegment>): String? {
    val now = LocalDateTime.now()
    if (bedtime.isAfter(now)) {
        return "Bedtime cannot be in the future."
    }
    if (segments.isEmpty()) return "No sleep segments added"

    var previousTime = bedtime
    for ((index, segment) in segments.withIndex()) {
        if (segment.endTime.isAfter(now)) {
            return "Segment ${index + 1} timestamp cannot be in the future."
        }
        val duration = Duration.between(previousTime, segment.endTime).toMinutes()
        if (duration <= 0) {
            return "Segment ${index + 1} timestamp must be after ${
                previousTime.format(DateTimeFormatter.ofPattern("h:mm a"))
            }. Check for midnight/noon crossings."
        }
        previousTime = segment.endTime
    }

    val totalInBed = Duration.between(bedtime, segments.last().endTime).toMinutes()
    if (totalInBed <= 0) return "Total time in bed must be positive. Check that wake time is after bedtime."

    val totalAsleep = segments.mapIndexed { index, segment ->
        val start = if (index == 0) bedtime else segments[index - 1].endTime
        val mins = Duration.between(start, segment.endTime).toMinutes()
        if (segment.sleepStage != SleepSessionRecord.STAGE_TYPE_AWAKE &&
            segment.sleepStage != SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
        ) mins else 0L
    }.sum()
    if (totalAsleep <= 0) return "Total time asleep must be positive. Mark at least one segment as a sleep stage."

    return null
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var showBedtimeDatePicker by remember { mutableStateOf(false) }
    var showBedtimeTimePicker by remember { mutableStateOf(false) }
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
            // Bedtime Card — entire card is clickable
            BedtimeCard(
                bedtime = bedtime,
                onEditClick = { showBedtimeDatePicker = true }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Segments List with Add button at bottom
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = dragReorderState.lazyListState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(segments, key = { _, item -> item.id }) { index, segment ->
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
                            Modifier.animateItemPlacement()
                        },
                        onDragStart = { dragReorderState.onDragStart(index) },
                        onDrag = { deltaY ->
                            dragReorderState.onDrag(deltaY, segments.size) { from, to ->
                                val mutableSegments = segments.toMutableList()
                                Collections.swap(mutableSegments, from, to)
                                segments = mutableSegments
                            }
                        },
                        onDragEnd = { dragReorderState.reset() }
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
                    onClick = {
                        val error = validateSleepLog(bedtime, segments)
                        if (error != null) {
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        } else {
                            onSave(SleepLog(bedtime, segments))
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    }

    if (showBedtimeDatePicker) {
        DatePickerDialog(
            title = "Select Bedtime Date",
            initialDate = bedtime,
            onConfirm = { newDate ->
                bedtime = newDate
                showBedtimeDatePicker = false
                showBedtimeTimePicker = true
            },
            onDismiss = { showBedtimeDatePicker = false }
        )
    }
    if (showBedtimeTimePicker) {
        TimePickerDialog(
            title = "Select Bedtime",
            initialTime = bedtime,
            onConfirm = { newTime ->
                bedtime = newTime
                showBedtimeTimePicker = false
            },
            onDismiss = { showBedtimeTimePicker = false }
        )
    }

    // Edit Timestamp Dialog
    editingSegmentIndex?.let { index ->
        TimePickerDialog(
            title = "Edit Timestamp",
            initialTime = segments[index].endTime,
            onConfirm = { newTime ->
                val updated = segments.toMutableList()
                updated[index] = segments[index].copy(endTime = newTime)
                segments = updated
                editingSegmentIndex = null
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
        onClick = onEditClick,
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
                    text = bedtime.format(DateTimeFormatter.ofPattern("MMM d, h:mm a")),
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
    onDragStart: () -> Unit = { },
    onDrag: (Float) -> Unit = { },
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
                        value = "${sleepStageIcon(segment.sleepStage)} $currentStage",
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
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(sleepStageIcon(stage))
                                        Text(name)
                                    }
                                },
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

                // Drag Handle — immediate drag, no long press
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { onDragStart() },
                                onDrag = { change, offset ->
                                    change.consume()
                                    onDrag(offset.y)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                        text = "→ until ${segment.endTime.format(DateTimeFormatter.ofPattern("h:mm a"))}",
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(sleepStageIcon(stage), style = MaterialTheme.typography.titleMedium)
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialog(
    title: String,
    initialDate: LocalDateTime,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.toEpochSecond(java.time.ZoneOffset.UTC) * 1000
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let {
                    val newDate = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(it),
                        java.time.ZoneId.systemDefault()
                    )
                    onConfirm(
                        initialDate
                            .withYear(newDate.year)
                            .withMonth(newDate.monthValue)
                            .withDayOfMonth(newDate.dayOfMonth)
                    )
                }
            }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
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
