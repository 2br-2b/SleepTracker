# SleepTracker

SleepTracker is an Android app that provides automated sleep detection and recording, with integration to Health Connect. It uses Jetpack Compose for the UI and follows modern Android architecture patterns.

## Features
- Automatic sleep detection via screen on/off events and time-window heuristics
- User confirmation UI for detected sleep sessions
- Health Connect integration for storing `SleepSessionRecord` data
- Jetpack Compose UI, DataStore for settings, and a foreground service for reliable background tracking

## Getting Started

Requirements
- Android Studio (latest stable) or Gradle + Android SDK
- JDK 11 or later

Open the project in Android Studio and let it sync Gradle. Alternatively, build from the command line:

```bash
# Linux / macOS
./gradlew assembleDebug

# Windows (PowerShell or CMD)
gradlew.bat assembleDebug
```

To install and run on a connected device or emulator:

```bash
./gradlew installDebug
```

## Health Connect
The app requests `READ_SLEEP` and `WRITE_SLEEP` permissions and writes confirmed sleep sessions to Health Connect. Look for the Health Connect integration code in the app module (HealthConnectManager and related classes).

## Project Structure
- `app/` — Android app module (source, resources, manifests)
- `AGENTS.md` — project goals and design notes

## Contributing
1. Open an issue describing the change.
2. Create a branch from `master` for your work.
3. Send a PR when ready.

## License
This repository does not include a license file. Add one if you intend to open-source the project.

----
Generated README briefly documents build/run and project purpose. For more details, see `AGENTS.md`.

## Nutrition dataset and indexing
- Upload the **unzipped** Open Nutrition dataset files into `app/src/main/assets/nutrition/opennutrition-dataset-2025.1/` (see `README_UPLOAD_HERE.md` in that folder).
- Build-time index generation is handled by `app/scripts/build_nutrition_index.py` and wired into Gradle `preBuild` via the `generateNutritionIndex` task. The script supports either the extracted folder or the legacy zip path if present.
- Generated assets:
  - `app/src/main/assets/nutrition/index.jsonl`
  - `app/src/main/assets/nutrition/metadata.json`

### Licensing notes
- Nutrition dataset usage may include redistribution/attribution restrictions.
- Keep dataset provenance and attribution visible in-app (Settings) and in repository docs.
- If redistribution rights change, replace the bundled archive with a compliant source artifact before release.
