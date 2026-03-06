# Upload Open Nutrition Dataset files here

`opennutrition-dataset-2025.1.zip` is not tracked in git to avoid binary-file review issues.

Place the **unzipped dataset CSV files** in this directory:

- `app/src/main/assets/nutrition/opennutrition-dataset-2025.1/`

Then run:

```bash
python3 app/scripts/build_nutrition_index.py
```

or build the app (Gradle runs the indexer during `preBuild`).

The indexer reads the first `*.csv` found in this folder (recursively).
