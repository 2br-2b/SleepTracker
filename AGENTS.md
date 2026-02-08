# SleepTracker Project Goals

This document outlines the goals and objectives for building a comprehensive Android application for tracking user sleep patterns, with a strong focus on automation, user privacy, and modern Android development practices.

**Note:** This describes the intended features and architecture goals, not necessarily the current implementation status.

### Core Goal: Automated Sleep Tracking

The primary objective is to create a seamless sleep tracking experience. The application will automatically detect and record the user'''s sleep and wake-up times by monitoring the phone'''s state. This is achieved by:

*   **Monitoring Screen Events:** A `BroadcastReceiver` will be implemented to listen for `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON` system broadcasts. These events will serve as the initial markers for when the user might be going to sleep or waking up.
*   **Intelligent Sleep Detection:** To differentiate between actual sleep and simple phone inactivity, the app will use a configurable time window (e.g., defaulting to 2 AM). If a screen-off event occurs within this window, it will be considered the start of a potential sleep session.
*   **User Confirmation:** After a potential sleep session, the user will be presented with a confirmation card in the UI, allowing them to verify or adjust the detected sleep and wake-up times.

### Health Connect Integration

A key feature of this application is its integration with Health Connect, ensuring that user data is stored securely and privately. The agent will:

*   **Implement a HealthConnectManager:** A dedicated manager class will be created to handle all interactions with the Health Connect API, including permission requests, data writing, and data reading.
*   **Request Necessary Permissions:** The application will request `READ_SLEEP` and `WRITE_SLEEP` permissions from the user, following best practices for permission handling.
*   **Store Sleep Data:** Once a sleep session is confirmed, the application will write a `SleepSessionRecord` to Health Connect, containing the start and end times of the sleep period.

### User Interface and Experience

The application will be built using Jetpack Compose, providing a modern and reactive UI. The key UI components will include:

*   **Main Screen:** A central hub displaying the `SleepConfirmationCard` and providing access to other features.
*   **Data Visualization:** A dedicated screen to display historical sleep data retrieved from Health Connect. This could initially be a simple list, with the potential for more advanced charts and graphs in the future.
*   **Settings Screen:** A screen allowing users to customize the sleep detection window and other app settings. User preferences will be persisted using Jetpack DataStore.

### Technical Architecture and Future Expansion

The agent will follow modern Android architecture principles to ensure the application is robust and maintainable.

*   **Foreground Service:** To ensure reliable background tracking, a foreground service will be implemented. This is crucial for long-running tasks on modern Android versions.
*   **Future-Proofing:** The architecture will be designed to be extensible. Future goals include:
    *   **Tracking Additional Health Data:** Expanding the application to track other data types, such as nutrition or exercise, by extending the `HealthConnectManager` and adding new UI components.
    *   **Advanced UI/UX:** Enhancing the user experience with animations, more sophisticated data visualizations, and a polished onboarding flow.