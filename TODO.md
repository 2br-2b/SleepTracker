## Phase 1: Core Sleep Tracking and Health Connect Integration

1.  **Set up Health Connect**:
    *   Add Health Connect dependencies to `build.gradle`.
    *   Create a Health Connect manager class.
    *   Declare permissions in `AndroidManifest.xml`.

2.  **Track Sleep and Wake Times**:
    *   Implement a `BroadcastReceiver` for `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON`.
    *   Store timestamps and determine if they fall within the sleep window.

3.  **Create the User Interface**:
    *   Design a sleep/wake-up confirmation card.
    *   Create a screen to display sleep data from Health Connect.
    *   Add a settings screen for the sleep window.

## Phase 2: Refining the User Experience

1.  **Data Persistence**:
    *   Use Jetpack DataStore to save user settings.

2.  **Foreground Service**:
    *   Implement a foreground service for reliable background tracking.

3.  **UI/UX Polish**:
    *   Animate the sleep/wake-up card.
    *   Use a chart to visualize sleep data.
    *   Add onboarding screens for permissions.

## Future Expansion

*   **Log other health data**:
    *   Extend the Health Connect manager to support other data types like nutrition.
    *   Add UI for logging and viewing new data types.