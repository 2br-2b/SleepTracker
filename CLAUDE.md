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

**MainActivity.kt**
- Entry point with Jetpack Compose UI
- Uses `NavigationSuiteScaffold` for adaptive navigation
- Handles OTHER_SENSORS permission requests
- Launches `SleepDataLogger` activity for sleep confirmation

**ScreenStateReceiver.kt**
- `BroadcastReceiver` that monitors screen state changes
- Listens for `ACTION_USER_PRESENT` (unlock) and `ACTION_SCREEN_OFF` (lock)
- Currently logs events; intended to trigger sleep detection logic

**SleepDataLogger.kt**
- Activity for confirming detected sleep sessions
- Receives sleep date via intent extras (key: "SLEEP_DATE_KEY")
- Presents UI for confirming/editing sleep start and end times
- Will write confirmed data to Health Connect

### Sleep Detection Flow

1. `ScreenStateReceiver` monitors screen lock/unlock events
2. Events within configured time window (default 2 AM) trigger sleep detection
3. User is presented with `SleepDataLogger` confirmation screen
4. Upon confirmation, sleep data is written to Health Connect via `HealthConnectManager` (to be implemented)

### Technology Stack

- **UI:** Jetpack Compose with Material 3
- **Theme:** Adaptive navigation suite for multi-form factor support
- **Min SDK:** 28 (Android 9.0)
- **Target SDK:** 36
- **Java Version:** 11

### Planned Components (from AGENTS.md)

- **HealthConnectManager:** Manages Health Connect API interactions (permissions, read/write operations)
- **Foreground Service:** Ensures reliable background sleep tracking
- **Settings Screen:** User preferences for sleep detection window
- **Data Visualization Screen:** Display historical sleep data from Health Connect
- **DataStore Integration:** Persist user settings

## Key Permissions

- `android.permission.OTHER_SENSORS` - Currently requested in the app
- Health Connect permissions (to be added):
  - `READ_SLEEP`
  - `WRITE_SLEEP`

## Navigation Structure

The app uses `AppDestinations` enum for navigation with currently one destination:
- `HOME` - Main screen with sleep confirmation trigger

## Android Manifest Notes

The `ScreenStateReceiver` is registered as an exported receiver with intent filters for:
- `android.intent.action.USER_PRESENT`
- `android.intent.action.SCREEN_OFF`
