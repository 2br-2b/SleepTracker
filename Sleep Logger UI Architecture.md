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
└─ [REM Sleep]      (ongoing - no duration shown)
[+ FAB to add timestamp]
```

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
    var showAddSegmentPicker by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = { FloatingActionButton(...) }
    ) { padding ->
        Column {
            BedtimeCard(...)
            LazyColumn { /* segments */ }
            Row { /* Cancel/Save buttons */ }
        }
    }

    // Time picker dialogs
}
```

**Features:**
- State management for bedtime and segments list
- Scaffold with FAB for adding segments
- Bedtime card at top
- LazyColumn for scrollable segments list
- Action buttons (Cancel/Save) at bottom
- Time picker dialogs for bedtime and new segments

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
    isLast: Boolean,
    onStageChanged: (Int) -> Unit,
    onRemove: () -> Unit
) {
    Card {
        Column {
            Row {
                ExposedDropdownMenuBox(...)  // Sleep stage selector
                Text(if (isLast) "ongoing" else duration)
            }
            Row {
                Text("→ until ${endTime}")
                if (!isLast) TextButton("Remove")
            }
        }
    }
}
```

**Features:**
- ExposedDropdownMenuBox for sleep stage selection
- Duration display (or "ongoing" for last segment)
- End time display
- Remove button (hidden for last segment)

### 4. TimePickerDialog Composable

Reusable time picker dialog:

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

## Sleep Stage Types

Health Connect sleep stage constants used in the app:

```kotlin
import androidx.health.connect.client.records.SleepSessionRecord

val sleepStages = listOf(
    "Awake" to SleepSessionRecord.STAGE_TYPE_AWAKE,
    "Sleeping" to SleepSessionRecord.STAGE_TYPE_SLEEPING,
    "Out of Bed" to SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    "Light Sleep" to SleepSessionRecord.STAGE_TYPE_LIGHT,
    "Deep Sleep" to SleepSessionRecord.STAGE_TYPE_DEEP,
    "REM Sleep" to SleepSessionRecord.STAGE_TYPE_REM,
    "Unknown" to SleepSessionRecord.STAGE_TYPE_UNKNOWN
)
```

## User Interactions

### Adding a New Timestamp

1. User clicks the FAB (`+`) button
2. TimePickerDialog appears with MaterialTimePicker
3. User selects a time (must be after the last segment's end time)
4. Validation: New timestamp must be chronologically after the previous one
5. The previous "last segment" now has a calculated duration
6. A new segment is created starting at this time with default sleep stage (Sleeping)
7. The new segment becomes the ongoing segment (shows "ongoing" instead of duration)

**State Update:**
```kotlin
segments = segments + SleepSegment(
    endTime = newTime,
    sleepStage = SleepSessionRecord.STAGE_TYPE_SLEEPING
)
```

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

## State Management

Compose's state system handles reactivity:

- `bedtime`: `MutableState<LocalDateTime>`
- `segments`: `MutableState<List<SleepSegment>>`
- Duration calculations happen on-the-fly in LazyColumn items
- State changes trigger automatic recomposition

## Key Design Principles

1. **Continuous Timeline**: No gaps between segments - every minute is accounted for
2. **Implicit Start Times**: Each segment's start is the previous segment's end
3. **Last Segment Special**: The final segment represents "ongoing" sleep with no defined end
4. **Remove Merges**: Removing a timestamp merges segments, doesn't create gaps
5. **Bedtime as Anchor**: Bedtime is separate from segments and serves as the timeline's starting point
6. **Chronological Validation**: All timestamps must be in chronological order
7. **Reactive UI**: Compose state system ensures UI stays in sync with data

## Material Design 3 Components

Uses Material 3 (Material You) Compose components:

- **Card**: For bedtime display and segment items
- **ExposedDropdownMenuBox**: For sleep stage selection
- **TimePicker** in **AlertDialog**: For time selection
- **FloatingActionButton**: For adding new segments
- **TextButton** and **Button**: For actions
- **LazyColumn**: For efficient scrolling list
- **Scaffold**: For overall layout with FAB

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
2. **Single Segment**: Test with just one ongoing segment
3. **Multiple Segments**: Test with 5+ segments
4. **Time Validation**: Ensure new timestamps must be after previous ones
5. **Midnight Crossing**: Test segments that cross midnight boundary
6. **Same-Day vs Multi-Day**: Handle sleep sessions that span multiple days
7. **Remove First Segment**: Test removing the first segment
8. **Duration Display**: Test various duration formats (minutes only, hours only, hours + minutes)
9. **State Updates**: Verify all state changes trigger proper recomposition

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
