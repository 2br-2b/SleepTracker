package codegito.xyz.healthconnector

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.SleepSessionRecord
import codegito.xyz.healthconnector.ui.theme.SleepTrackerTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
    var showBedtimePicker by remember { mutableStateOf(false) }
    var showAddSegmentPicker by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddSegmentPicker = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add segment")
            }
        }
    ) { padding ->
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

            // Segments List
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(segments) { index, segment ->
                    val startTime = if (index == 0) bedtime else segments[index - 1].endTime
                    val duration = java.time.Duration.between(startTime, segment.endTime).toMinutes()
                    val isLast = index == segments.size - 1

                    SleepSegmentCard(
                        segment = segment,
                        durationMinutes = duration,
                        isLast = isLast,
                        onStageChanged = { newStage ->
                            val updated = segments.toMutableList()
                            updated[index] = segment.copy(sleepStage = newStage)
                            segments = updated
                        },
                        onRemove = {
                            segments = segments.filterIndexed { i, _ -> i != index }
                        }
                    )
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

    // Add Segment Picker Dialog
    if (showAddSegmentPicker) {
        val lastTime = segments.lastOrNull()?.endTime ?: bedtime
        val context = LocalContext.current

        TimePickerDialog(
            title = "Add Timestamp",
            initialTime = lastTime,
            onConfirm = { newTime ->
                if (newTime.isAfter(lastTime)) {
                    segments = segments + SleepSegment(
                        endTime = newTime,
                        sleepStage = SleepSessionRecord.STAGE_TYPE_SLEEPING
                    )
                    showAddSegmentPicker = false
                } else {
                    Toast.makeText(
                        context,
                        "New timestamp must be after ${lastTime.format(DateTimeFormatter.ofPattern("h:mm a"))}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { showAddSegmentPicker = false }
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
    isLast: Boolean,
    onStageChanged: (Int) -> Unit,
    onRemove: () -> Unit
) {
    val sleepStages = remember {
        listOf(
            "Awake" to SleepSessionRecord.STAGE_TYPE_AWAKE,
            "Sleeping" to SleepSessionRecord.STAGE_TYPE_SLEEPING,
            "Out of Bed" to SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
            "Light Sleep" to SleepSessionRecord.STAGE_TYPE_LIGHT,
            "Deep Sleep" to SleepSessionRecord.STAGE_TYPE_DEEP,
            "REM Sleep" to SleepSessionRecord.STAGE_TYPE_REM,
            "Unknown" to SleepSessionRecord.STAGE_TYPE_UNKNOWN
        )
    }

    var expanded by remember { mutableStateOf(false) }
    val currentStage = sleepStages.find { it.second == segment.sleepStage }?.first ?: "Unknown"

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Sleep Stage Dropdown and Duration
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
                        sleepStages.forEach { (name, stage) ->
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

                Text(
                    text = if (isLast) "ongoing" else durationMinutes.formatDuration(),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isLast) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // End Time and Remove Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "→ until ${segment.endTime.format(DateTimeFormatter.ofPattern("h:mm a"))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!isLast) {
                    TextButton(onClick = onRemove) {
                        Text("Remove")
                    }
                }
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
