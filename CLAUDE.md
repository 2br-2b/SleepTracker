# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SleepTracker is an Android app for automated sleep tracking via Health Connect. It monitors screen lock/unlock events to detect sleep patterns and writes sleep sessions to Health Connect.

**Package:** `codegito.xyz.healthconnector`
**Min SDK:** 28 · **Target SDK:** 35 · **Java:** 11 · **Kotlin:** 2.1.0

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Install on device/emulator
./gradlew test                   # Unit tests
./gradlew testDebugUnitTest      # Debug variant unit tests
./gradlew connectedAndroidTest   # Instrumented tests (needs device)
./gradlew clean                  # Clean build
```

## Architecture

### Two Detection Modes

The app supports two sleep detection modes (`SleepDetectionMode` enum):
- **AUTO**: Service runs in background, `ScreenStateReceiver` records screen events to Room DB within configured bedtime/wakeup windows, `SleepDetectionEngine` analyzes events to auto-detect sleep sessions
- **MANUAL**: No background service; user creates sleep logs from a template (`SleepLogTemplate`)

### End-to-End Sleep Tracking Flow (AUTO mode)

1. **Boot/App open** → `BootReceiver` or `MainActivity.ensureServiceRunning()` starts `SleepTrackingService`
2. **Service** → Dynamically registers `ScreenStateReceiver` (must be dynamic, not manifest-declared, because `ACTION_SCREEN_OFF/ON` requires it on API 26+)
3. **Screen events** → `ScreenStateReceiver` records LOCK/UNLOCK/PRESENT events to Room DB (`SleepEventDatabase`), but only if current time falls within configured bedtime or wakeup windows
4. **Detection** → `SleepDetectionEngine` analyzes stored events to identify bedtime (last LOCK in bedtime window) and wakeup (first UNLOCK in wakeup window)
5. **Notification** → `NotificationHelper` sends reminder via `ReminderReceiver` (first-unlock or deadline alarm) prompting user to review
6. **Review** → `SleepDataLogger` activity shows auto-detected session (or existing Health Connect record); user edits via `SleepLogEditor`
7. **Save** → `HealthConnectManager.writeSleepLog()` writes `SleepSessionRecord` with stages to Health Connect
8. **Display** → `MainActivity` home screen shows 7-day sleep history with duration breakdown

### Service Lifecycle

- `SleepTrackingService` uses `START_STICKY` and `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
- `ServiceSchedulerReceiver` handles START_SERVICE/STOP_SERVICE actions for alarm-based lifecycle
- Service auto-stops in MANUAL mode; auto-starts/stops based on preference changes
- Periodic cleanup: deletes screen events older than 7 days from Room DB

### Sleep Logger Data Model

Sleep sessions use a **continuous timeline** — no gaps between segments:
- `SleepLog`: bedtime timestamp + ordered list of `SleepSegment`
- `SleepSegment`: end time + sleep stage (start time is implicit from previous segment's end or bedtime)
- `SleepStageConfig`: maps Health Connect stage types (SLEEPING, AWAKE, LIGHT, DEEP, REM, etc.) with custom emoji support

### Navigation

Uses sealed `Screen` class with `NavHost`:
- `Screen.Home` — 7-day sleep history with per-day cards
- `Screen.Settings` — App preferences, Health Connect permissions, notifications
- `Screen.AutoSleepSettings` — Bedtime/wakeup windows, awakening thresholds, reminders
- `Screen.EditSleepStages` — Drag-reorderable sleep stage list with emoji customization

Bottom `NavigationBar` shows Home and Settings tabs. Sub-screens navigate via `NavController`.

### Data Layer

- **Room** (`SleepEventDatabase`): Stores `ScreenEvent` entities (timestampMillis, type). Singleton via `getInstance()`.
- **DataStore** (`UserPreferencesRepository`): All user preferences as Kotlin Flows — rollover hour, detection mode, bedtime/wakeup windows, awakening thresholds, sleep stage config (JSON-serialized), manual template, reminder toggles, developer mode.
- **Health Connect** (`HealthConnectManager`): Read/write/delete `SleepSessionRecord`. Handles permission checks for READ_SLEEP and WRITE_SLEEP.

### Key Implementation Details

- **Rollover hour**: Day boundaries for sleep sessions roll over at a configurable hour (default 2 AM), so a session starting at 11 PM belongs to that calendar day, not the next
- `ScreenStateReceiver` is **never** in the manifest — dynamic registration only
- Health Connect permissions use `PermissionController.createRequestPermissionResultContract()` via Activity Result API
- `OnboardingActivity` handles Health Connect permission rationale with activity aliases for Android 13- and 14+ intent filter variants
- Notifications use three channels: REMINDER (default), DEADLINE (high importance), SILENT (low importance)
- Exact alarms (`SCHEDULE_EXACT_ALARM`) used for reminder scheduling; respects Android 12+ permission requirements

### Key Permissions

`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, `OTHER_SENSORS` (conditional), `health.READ_SLEEP`, `health.WRITE_SLEEP`

### Dependencies

Key libraries (managed via `gradle/libs.versions.toml`):
- Jetpack Compose with Material 3 + dynamic theming
- Health Connect client (`1.1.0-alpha11` — hardcoded in `app/build.gradle.kts`, not in version catalog)
- Room (`2.7.0-alpha11`) with KSP annotation processing
- DataStore Preferences
- Navigation Compose
- Kotlinx Serialization (for JSON-serialized preferences)
