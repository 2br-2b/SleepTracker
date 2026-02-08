# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SleepTracker is an Android application for automated sleep tracking that integrates with Health Connect. The app monitors screen events (lock/unlock) to detect sleep patterns and stores sleep data securely via the Health Connect API.

**Package name:** `codegito.xyz.healthconnector`

## Build and Development Commands

### Building the project
```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install on connected device/emulator
./gradlew installDebug
```

### Testing
```bash
# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run tests for a specific variant
./gradlew testDebugUnitTest
```

### Cleaning
```bash
./gradlew clean
```

## Architecture

### Core Components

**SleepTrackingService.kt**
- Foreground service that runs continuously in the background
- Dynamically registers `ScreenStateReceiver` to monitor screen events
- Uses `START_STICKY` to restart if killed by system
- Shows persistent notification "Sleep Tracking Active"
- Requires `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` for Android 14+
- Auto-started on boot via `BootReceiver` and whenever MainActivity opens

**BootReceiver.kt**
- `BroadcastReceiver` that listens for `ACTION_BOOT_COMPLETED`
- Automatically starts `SleepTrackingService` when device boots
- Enables seamless sleep tracking without user interaction

**MainActivity.kt**
- Entry point with Jetpack Compose UI
- Uses `NavigationSuiteScaffold` for adaptive navigation
- Automatically ensures `SleepTrackingService` is running via `ensureServiceRunning()`
- Conditionally displays OTHER_SENSORS permission button (only if permission exists but not granted)
- Launches `SleepDataLogger` activity for sleep confirmation

**ScreenStateReceiver.kt**
- `BroadcastReceiver` that monitors screen state changes
- Dynamically registered in `SleepTrackingService` (not in manifest)
- Listens for `ACTION_SCREEN_OFF`, `ACTION_SCREEN_ON`, and `ACTION_USER_PRESENT`
- Currently logs events with timestamps; intended to trigger sleep detection logic

**SleepDataLogger.kt**
- Jetpack Compose activity for logging detailed sleep sessions to Health Connect
- Implements continuous timeline architecture (bedtime + sleep segments with no gaps)
- Features:
  - BedtimeCard for editing sleep start time
  - LazyColumn of SleepSegmentCards for managing sleep stages
  - FloatingActionButton for adding new timestamps
  - TimePickerDialog for time selection
  - Sleep stage dropdown (Awake, Light, Deep, REM, etc.)
  - Duration calculations for each segment
  - Last segment shows "ongoing" until wake time added
- See "Sleep Logger UI Architecture.md" for detailed architecture documentation

**SleepLog.kt**
- Data models for sleep logging:
  - `SleepLog`: Contains bedtime + list of segments
  - `SleepSegment`: End time + sleep stage (start time is implicit)
  - `SleepSegmentWithDuration`: Display model with calculated duration
- Extension functions:
  - `SleepLog.calculateDurations()`: Calculates duration for each segment
  - `Long.formatDuration()`: Formats minutes as "2h 30min", "45min", etc.

### Sleep Detection Flow

1. Service starts automatically (on boot or when app opens)
2. `ScreenStateReceiver` monitors screen lock/unlock events continuously
3. Events within configured time window (default 2 AM) will trigger sleep detection
4. User will be presented with `SleepDataLogger` confirmation screen
5. Upon confirmation, sleep data will be written to Health Connect via `HealthConnectManager` (to be implemented)

### Sleep Logger Architecture

The Sleep Logger uses a **continuous timeline** approach where:
- Sleep sessions consist of a bedtime and multiple segments
- Each segment has an end time and sleep stage (start time is implicit)
- No gaps exist between segments - every minute is accounted for
- The last segment is "ongoing" until a wake time is added

**Key Features:**
- Add timestamps to create new segments
- Remove timestamps to merge segments
- Edit bedtime to adjust entire timeline
- Select sleep stages from Health Connect types (Awake, Light, Deep, REM, etc.)
- Automatic duration calculation and formatting

See "Sleep Logger UI Architecture.md" for complete architecture documentation.

### Technology Stack

- **UI:** Jetpack Compose with Material 3
- **Theme:** Adaptive navigation suite for multi-form factor support
- **Health Data:** Health Connect client library (androidx.health.connect:connect-client:1.1.0-alpha10)
- **Min SDK:** 28 (Android 9.0)
- **Target SDK:** 36
- **Java Version:** 11

### Planned Components (from AGENTS.md)

- **HealthConnectManager:** Manages Health Connect API interactions (permissions, read/write operations) - to be implemented
- **Settings Screen:** User preferences for sleep detection window - to be implemented
- **Data Visualization Screen:** Display historical sleep data from Health Connect - to be implemented
- **DataStore Integration:** Persist user settings - to be implemented

## Key Permissions

**Currently Implemented:**
- `android.permission.OTHER_SENSORS` - Conditionally requested in UI if available on device
- `android.permission.FOREGROUND_SERVICE` - Required for SleepTrackingService
- `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` - Required for Android 14+ (API 34+)
- `android.permission.POST_NOTIFICATIONS` - Required for foreground service notification
- `android.permission.RECEIVE_BOOT_COMPLETED` - Allows BootReceiver to auto-start service
- `android.permission.health.READ_SLEEP` - Read sleep data from Health Connect
- `android.permission.health.WRITE_SLEEP` - Write sleep data to Health Connect

## Navigation Structure

The app uses `AppDestinations` enum for navigation with currently one destination:
- `HOME` - Main screen with sleep confirmation trigger

## Android Manifest Notes

**Important Implementation Details:**

- `ScreenStateReceiver` is **NOT** declared in the manifest - it's dynamically registered in `SleepTrackingService` because `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON` cannot be received via manifest-declared receivers on Android 8.0+ (API 26+)
- `BootReceiver` is declared in manifest with `ACTION_BOOT_COMPLETED` intent filter
- `SleepTrackingService` declares `foregroundServiceType="specialUse"` with a property explaining its purpose for Play Store compliance
- Service uses `START_STICKY` flag to ensure it restarts if killed by the system

**Auto-Start Behavior:**
1. On device boot → `BootReceiver` starts `SleepTrackingService`
2. When app opens → `MainActivity.ensureServiceRunning()` verifies service is running and starts it if not
3. Result: Seamless, always-on sleep tracking without user intervention
