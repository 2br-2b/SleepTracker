# Open Nutrition Dataset bundle

To ship the full dataset without committing extracted CSVs (~300MB), add the ZIP file here:

- `app/src/main/assets/nutrition/opennutrition-dataset-2025.1.zip`

Then build the app. During `preBuild`, Gradle runs:

```bash
python3 app/scripts/build_nutrition_index.py
```

This produces `app/src/main/assets/nutrition/index.jsonl` for bundled-search use.

If you intentionally omit the ZIP from app assets to reduce APK/AAB size, users can still pick the ZIP from device storage in the Food screen and build the runtime index on-device.

Download source:

- https://downloads.opennutrition.app/opennutrition-dataset-2025.1.zip
