package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.data.model.TimeRange
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Shows a labelled start/end time pair with inline picker dialogs.
 *
 * Both [TimeRange] endpoints are always editable; the dialogs include minute
 * precision so the same component works for sleep windows (21:30 – 02:00) and
 * nutrition meal windows (06:00 – 10:00).
 */
@Composable
fun TimeRangeSetting(
    label: String,
    range: TimeRange,
    onRangeChange: (TimeRange) -> Unit,
    modifier: Modifier = Modifier
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeRangeEndpointButton(
                label = "Start",
                minutes = range.startMinutes,
                modifier = Modifier.weight(1f),
                onClick = { showStartPicker = true }
            )
            TimeRangeEndpointButton(
                label = "End",
                minutes = range.endMinutes,
                modifier = Modifier.weight(1f),
                onClick = { showEndPicker = true }
            )
        }
    }

    if (showStartPicker) {
        AppTimePickerDialog(
            initialHour = range.startMinutes / 60,
            initialMinute = range.startMinutes % 60,
            onConfirm = { h, m ->
                onRangeChange(range.copy(startMinutes = h * 60 + m))
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }

    if (showEndPicker) {
        AppTimePickerDialog(
            initialHour = range.endMinutes / 60,
            initialMinute = range.endMinutes % 60,
            onConfirm = { h, m ->
                onRangeChange(range.copy(endMinutes = h * 60 + m))
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        )
    }
}

@Composable
private fun TimeRangeEndpointButton(
    label: String,
    minutes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val time = LocalTime.of((minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59))
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                time.format(DateTimeFormatter.ofPattern("h:mm a")),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

/**
 * A standalone time-picker dialog. Used by [TimeRangeSetting] internally and
 * also by callers that need a single-point time picker (e.g. rollover hour).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTimePickerDialog(
    initialHour: Int,
    initialMinute: Int = 0,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
