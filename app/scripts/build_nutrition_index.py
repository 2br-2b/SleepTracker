#!/usr/bin/env python3
import csv
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
assets_dir = ROOT / "app" / "src" / "main" / "assets" / "nutrition"
zip_path = assets_dir / "opennutrition-dataset-2025.1.zip"
extracted_dir = assets_dir / "opennutrition-dataset-2025.1"
assets_dir.mkdir(parents=True, exist_ok=True)
index_path = assets_dir / "index.jsonl"
meta_path = assets_dir / "metadata.json"


def parse_rows(dict_rows):
    rows = []
    for i, r in enumerate(dict_rows):
        if i >= 50000:
            break
        name = (r.get("name") or r.get("food_name") or r.get("description") or "").strip()
        if not name:
            continue
        rows.append(
            {
                "id": (r.get("id") or f"csv-{i}"),
                "name": name,
                "baseAmount": float(r.get("base_amount") or 100),
                "calories": float(r.get("calories") or r.get("energy_kcal") or 0),
                "protein": float(r.get("protein") or 0),
                "carbs": float(r.get("carbohydrates") or r.get("carbs") or 0),
                "fat": float(r.get("fat") or 0),
            }
        )
    return rows


rows = []
source_location = "demo-fallback"

if extracted_dir.exists() and extracted_dir.is_dir():
    csv_files = sorted(extracted_dir.rglob("*.csv"))
    if csv_files:
        with csv_files[0].open("r", encoding="utf-8", errors="ignore", newline="") as f:
            rows = parse_rows(csv.DictReader(f))
            source_location = str(csv_files[0].relative_to(ROOT))
elif zip_path.exists():
    with zipfile.ZipFile(zip_path) as zf:
        csv_candidates = [n for n in zf.namelist() if n.endswith(".csv")]
        if csv_candidates:
            with zf.open(csv_candidates[0]) as f:
                dict_rows = csv.DictReader(
                    (line.decode("utf-8", errors="ignore") for line in f)
                )
                rows = parse_rows(dict_rows)
                source_location = f"{zip_path.relative_to(ROOT)}::{csv_candidates[0]}"

if not rows:
    rows = [
        {
            "id": "demo-apple",
            "name": "Apple, raw",
            "baseAmount": 100.0,
            "calories": 52.0,
            "protein": 0.3,
            "carbs": 14.0,
            "fat": 0.2,
        },
        {
            "id": "demo-banana",
            "name": "Banana, raw",
            "baseAmount": 100.0,
            "calories": 89.0,
            "protein": 1.1,
            "carbs": 22.8,
            "fat": 0.3,
        },
    ]

with index_path.open("w", encoding="utf-8") as f:
    for row in rows:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")

metadata = {
    "source": "Open Nutrition Dataset",
    "version": "2025.1",
    "sourceZip": zip_path.name,
    "sourceLocation": source_location,
    "licenseNotice": "Refer to Nutrition Dataset License in app settings and repository documentation.",
    "recordCount": len(rows),
}
meta_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
print(f"Wrote {len(rows)} records to {index_path}")
