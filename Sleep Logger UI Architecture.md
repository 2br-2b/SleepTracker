# Sleep Logger UI Architecture (Jetpack Compose)

## Overview

This document describes the Jetpack Compose architecture for logging continuous sleep data into Health Connect. The key principle is that sleep segments are **continuous** with no gaps - every minute from bedtime to wake time is accounted for with a specific sleep stage.

## Core Concept: Continuous Timeline

The sleep log represents a continuous timeline from bedtime to wake time. Each timestamp creates a segment that runs until the next timestamp. There are no gaps between segments.

**Example Timeline:**
```
Bedtime: 10:00 PM
├─ [Awake]          30 min → until 10:30 PM  [Remove]
├─ [Light Sleep]    2h 0min → until 12:30 AM [Remove]
├─ [Deep Sleep]     1h 30min → until 2:00 AM [Remove]
└─ [REM Sleep]      5h 30min → until 7:30 AM [Remove]
[+ Add Timestamp] (button at bottom of list)
```

**Note:** All durations and timestamps are clickable and editable.

## Data Model

```kotlin
data class SleepLog(
    val bedtime: LocalDateTime,  // The initial "went to bed" time
    val segments: List<SleepSegment>
)

data class SleepSegment(
    val endTime: LocalDateTime,  // When this segment ends (next segment begins)
    val sleepStage: Int  // Health Connect sleep stage constant
)

data class SleepSegmentWithDuration(
    val sleepStage: Int,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val durationMinutes: Long
)

// Extension function for duration calculation
fun SleepLog.calculateDurations(): List<SleepSegmentWithDuration>

// Extension function for duration formatting
fun Long.formatDuration(): String  // e.g., "2h 30min", "45min", "3h"
```

**Key Insight**: Each segment's *start* time is implicit - it's either the bedtime (for first segment) or the previous segment's end time. Only the end time is stored.

## Compose UI Components

### 1. SleepLogScreen (Main Composable)

The main screen composable manages the state and layout:

```kotlin
@Composable
fun SleepLogScreen(
    onSave: (SleepLog) -> Unit,
    onCancel: () -> Unit
) {
    var bedtime by remember { mutableStateOf(...) }
    var segments by remember { mutableStateOf(...) }
    var showBedtimePicker by remember { mutableStateOf(false) }
    var editingSegmentIndex by remember { mutableStateOf<Int?>(null) }
    var editingDurationIndex by remember { mutableStateOf<Int?>(null) }
    var durationInputText by remember { mutableStateOf("") }

    Scaffold { padding ->
        Column {
            BedtimeCard(...)
            LazyColumn {
                /* segments */
                /* Add Timestamp button at bottom */
            }
            Row { /* Cancel/Save buttons */ }
        }
    }

    // Dialogs for editing time and duration
}
```

**Features:**
- State management for bedtime, segments, and editing state
- Bedtime card at top
- LazyColumn for scrollable segments list with Add button at bottom
- Action buttons (Cancel/Save) at bottom
- Dialogs for editing time, duration, and bedtime

### 2. BedtimeCard Composable

Displays and allows editing the bedtime:

```kotlin
@Composable
fun BedtimeCard(
    bedtime: LocalDateTime,
    onEditClick: () -> Unit
) {
    Card {
        Row {
            Text("Bedtime:")
            Text(bedtime.format(...), color = primary)
            TextButton("Edit")
        }
    }
}
```

### 3. SleepSegmentCard Composable

Each segment in the LazyColumn:

```kotlin
@Composable
fun SleepSegmentCard(
    segment: SleepSegment,
    durationMinutes: Long,
    onStageChanged: (Int) -> Unit,
    onRemove: () -> Unit,
    onDurationClick: () -> Unit,
    onTimestampClick: () -> Unit
) {
    Card {
        Column {
            Row {
                ExposedDropdownMenuBox(...)  // Sleep stage selector
                Text(duration, modifier = Modifier.clickable { onDurationClick() })
            }
            Row {
                Text("→ until ${endTime}", modifier = Modifier.clickable { onTimestampClick() })
                TextButton("Remove")
            }
        }
    }
}
```

**Features:**
- ExposedDropdownMenuBox for sleep stage selection
- Clickable duration display - tap to edit minutes directly
- Clickable timestamp display - tap to edit time
- Remove button on all segments

### 4. TimePickerDialog Composable

Reusable time picker dialog for bedtime and timestamp editing:

```kotlin
@Composable
fun TimePickerDialog(
    title: String,
    initialTime: LocalDateTime,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    val timePickerState = rememberTimePickerState(...)
    AlertDialog(
        title = { Text(title) },
        text = { TimePicker(state = timePickerState) },
        confirmButton = { ... },
        dismissButton = { ... }
    )
}
```

### 5. DurationInputDialog Composable

Dialog for editing segment duration in minutes:

```kotlin
@Composable
fun DurationInputDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = initialValue,
                onValueChange = onValueChange,
                label = { Text("Minutes") },
                singleLine = true
            )
        },
        confirmButton = { ... },
        dismissButton = { ... }
    )
}
```

**Features:**
- Text field for numeric input (minutes)
- Validates and recalculates end time on confirm
- Shows error if duration exceeds next segment

## Sleep Stage Types

Health Connect sleep stage constants used in the app:

```kotlin
import androidx.health.connect.client.records.SleepSessionRecord

val sleepStages = listOf(
    "Sleeping" to SleepSessionRecord.STAGE_TYPE_SLEEPING,
    "Awake in bed" to SleepSessionRecord.STAGE_TYPE_AWAKE,
    "Awake out of bed" to SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    "Unknown" to SleepSessionRecord.STAGE_TYPE_UNKNOWN
    "Light sleep" to SleepSessionRecord.STAGE_TYPE_LIGHT,
    "Deep sleep" to SleepSessionRecord.STAGE_TYPE_DEEP,
    "REM sleep" to SleepSessionRecord.STAGE_TYPE_REM,
)
```

## User Interactions

### Adding a New Timestamp

1. User clicks the "Add Timestamp" button at the bottom of the list
2. A new segment is immediately created with:
   - End time: Current time (`LocalDateTime.now()`)
   - Sleep stage: "Sleeping" (default)
3. Duration is automatically calculated from the previous segment's end time
4. User can then edit the timestamp, duration, or sleep stage as needed

**State Update:**
```kotlin
segments = segments + SleepSegment(
    endTime = LocalDateTime.now(),
    sleepStage = SleepSessionRecord.STAGE_TYPE_SLEEPING
)
```

**Benefits:**
- No time picker dialog interruption
- Faster workflow for real-time logging
- Current time is usually the desired value
- Easy to adjust afterward if needed

### Removing a Timestamp

1. User clicks `Remove` button on a segment (not available for last segment)
2. That segment is removed from the list
3. The segment merges with the next segment
4. The next segment's sleep stage is retained
5. Duration is recalculated for the merged segment

**State Update:**
```kotlin
segments = segments.filterIndexed { i, _ -> i != index }
```

**Example:**
```
Before:
├─ [Awake] 30 min → until 10:30 PM [Remove]
├─ [Light] 1h 0min → until 11:30 PM [Remove]

User removes 10:30 PM timestamp:

After:
├─ [Light] 1h 30min → until 11:30 PM [Remove]
```

The "Awake" stage is lost, and the "Light Sleep" stage now extends from bedtime to 11:30 PM.

### Editing Bedtime

1. User clicks "Edit" button on BedtimeCard
2. TimePickerDialog appears
3. User selects new bedtime
4. All segment durations are automatically recalculated (reactive state)
5. LazyColumn updates to show new durations

**State Update:**
```kotlin
bedtime = newTime
// Compose automatically recalculates durations in LazyColumn items
```

### Changing Sleep Stage

1. User clicks on the ExposedDropdownMenuBox
2. Dropdown menu shows all available sleep stages
3. User selects a new stage
4. Segment is updated with new sleep stage

**State Update:**
```kotlin
val updated = segments.toMutableList()
updated[index] = segment.copy(sleepStage = newStage)
segments = updated
```

### Editing Duration

1. User clicks on the duration text (e.g., "2h 30min")
2. DurationInputDialog appears with text field
3. User enters new duration in minutes (e.g., "150")
4. System recalculates end time by adding minutes to start time
5. Validation: New end time must not exceed next segment's start time
6. Duration and timestamp update automatically

**State Update:**
```kotlin
val newEndTime = startTime.plusMinutes(durationMinutes)
val updated = segments.toMutableList()
updated[index] = segments[index].copy(endTime = newEndTime)
segments = updated
```

**Use Case:** Quickly adjust sleep stage duration without calculating exact times.

### Editing Timestamp

1. User clicks on the timestamp text (e.g., "→ until 11:30 PM")
2. TimePickerDialog appears with current time pre-selected
3. User selects a new time
4. Validation: Must be after previous segment and before next segment
5. Duration recalculates automatically based on new end time

**State Update:**
```kotlin
val updated = segments.toMutableList()
updated[index] = segments[index].copy(endTime = newTime)
segments = updated
```

**Use Case:** Set exact wake-up time or adjust segment boundary.

## State Management

Compose's state system handles reactivity:

- `bedtime`: `MutableState<LocalDateTime>`
- `segments`: `MutableState<List<SleepSegment>>`
- Duration calculations happen on-the-fly in LazyColumn items
- State changes trigger automatic recomposition

## Key Design Principles

1. **Continuous Timeline**: No gaps between segments - every minute is accounted for
2. **Implicit Start Times**: Each segment's start is the previous segment's end
3. **Editable Durations**: Click duration to enter minutes, system recalculates timestamp
4. **Editable Timestamps**: Click timestamp to change time, system recalculates duration
5. **Remove Merges**: Removing a timestamp merges segments, doesn't create gaps
6. **Bedtime as Anchor**: Bedtime is separate from segments and serves as the timeline's starting point
7. **Chronological Validation**: All timestamps must be in chronological order
8. **Instant Add**: New timestamps default to current time for fast logging
9. **Reactive UI**: Compose state system ensures UI stays in sync with data

## Material Design 3 Components

Uses Material 3 (Material You) Compose components:

- **Card**: For bedtime display, segment items, and Add Timestamp button
- **ExposedDropdownMenuBox**: For sleep stage selection
- **TimePicker** in **AlertDialog**: For time selection and timestamp editing
- **OutlinedTextField** in **AlertDialog**: For duration editing (minutes input)
- **TextButton** and **Button**: For actions
- **LazyColumn**: For efficient scrolling list
- **Scaffold**: For overall layout
- **Clickable modifiers**: For interactive duration and timestamp editing

## Dependencies

```gradle
dependencies {
    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0-alpha10")

    // Compose (already included in project)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
}
```

## Permissions

```xml
<uses-permission android:name="android.permission.health.READ_SLEEP" />
<uses-permission android:name="android.permission.health.WRITE_SLEEP" />
```

## Converting to Health Connect Record

```kotlin
fun SleepLog.toHealthConnectRecord(): SleepSessionRecord {
    val stages = mutableListOf<SleepSessionRecord.Stage>()
    var currentTime = bedtime

    segments.forEach { segment ->
        stages.add(
            SleepSessionRecord.Stage(
                startTime = currentTime.toInstant(...),
                endTime = segment.endTime.toInstant(...),
                stage = segment.sleepStage
            )
        )
        currentTime = segment.endTime
    }

    return SleepSessionRecord(
        startTime = bedtime.toInstant(...),
        endTime = segments.last().endTime.toInstant(...),
        stages = stages
    )
}
```

## Testing Considerations

1. **Empty State**: Handle when no segments exist yet (initialize with one)
2. **Single Segment**: Test with just one segment
3. **Multiple Segments**: Test with 5+ segments
4. **Time Validation**: Ensure edited timestamps stay between adjacent segments
5. **Duration Editing**: Test that entering minutes correctly recalculates end time
6. **Duration Validation**: Verify duration doesn't exceed next segment boundary
7. **Timestamp Editing**: Test that changing time correctly recalculates duration
8. **Midnight Crossing**: Test segments that cross midnight boundary
9. **Same-Day vs Multi-Day**: Handle sleep sessions that span multiple days
10. **Remove First Segment**: Test removing the first segment
11. **Remove Last Segment**: Test removing the last segment
12. **Duration Display**: Test various duration formats (minutes only, hours only, hours + minutes)
13. **State Updates**: Verify all state changes trigger proper recomposition
14. **Add Timestamp**: Verify new timestamps default to current time
15. **Invalid Input**: Test non-numeric duration input handling

## Implementation Benefits (Compose vs XML)

- **Less Boilerplate**: No RecyclerView adapter, ViewHolder, or XML layouts needed
- **Better State Management**: Compose state system handles reactivity automatically
- **Consistent with App**: Matches the existing Compose-based architecture
- **Easier Maintenance**: Single-file component definitions
- **Modern Patterns**: Declarative UI with functional composition
- **Type Safety**: Compile-time checking for UI structure

## Future Enhancements

- Edit existing timestamps (time picker with validation)
- Drag to reorder segments
- Visual timeline graph representation
- Statistics and trends
- Import/export sleep data
- Smart suggestions based on past sleep patterns
