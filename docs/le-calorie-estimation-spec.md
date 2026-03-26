# Exercise Calorie Estimation — Design Specification

> **Purpose:** This document defines the complete calorie estimation logic for the exercise tracking module.
> It is intended as both implementation guidance for an LLM and living documentation for the codebase.
> All formulas are sourced from peer-reviewed research or validated reference material.

---

## Table of Contents

1. [Health Connect Data Types Available](#1-health-connect-data-types-available)
2. [User Preference Inputs Required](#2-user-preference-inputs-required)
3. [Input Enrichment Phase](#3-input-enrichment-phase)
4. [BMR Computation](#4-bmr-computation)
5. [Base Calorie Method Selection](#5-base-calorie-method-selection)
   - [Tier 5 — Power (Cycling)](#tier-5--power-cycling)
   - [Tier 4 — Heart Rate + VO2max](#tier-4--heart-rate--vo2max)
   - [Tier 3 — Heart Rate + Age + Sex](#tier-3--heart-rate--age--sex)
   - [Tier 2 — Heart Rate Only (Simplified)](#tier-2--heart-rate-only-simplified)
   - [Tier 1B — ACSM Pace (Running/Walking, no HR)](#tier-1b--acsm-pace-runningwalking-no-hr)
   - [Tier 1A — LBM-Adjusted MET](#tier-1a--lbm-adjusted-met)
   - [Tier 0 — Baseline MET](#tier-0--baseline-met)
6. [Strength Exercise Modifier](#6-strength-exercise-modifier)
7. [Complete Decision Tree](#7-complete-decision-tree)
8. [Per-Exercise-Type Logic](#8-per-exercise-type-logic)
9. [Accuracy Summary](#9-accuracy-summary)
10. [Configuration Constants](#10-configuration-constants)
11. [Sources](#11-sources)

---

## 1. Health Connect Data Types Available

The following Health Connect record types should be requested (where the user grants permission)
and used to improve calorie accuracy. All are real, confirmed HC record types as of the 1.1.0
stable Jetpack release.

| HC Record Type | Permission | Used For |
|---|---|---|
| `WeightRecord` | `READ_WEIGHT` | Base weight for all formulas |
| `HeightRecord` | `READ_HEIGHT` | Stride estimation fallback; Mifflin-St Jeor BMR |
| `BodyFatRecord` | `READ_BODY_FAT` | LBM calculation fallback |
| `LeanBodyMassRecord` | `READ_LEAN_BODY_MASS` | Direct LBM — preferred over calculated |
| `BasalMetabolicRateRecord` | `READ_BASAL_METABOLIC_RATE` | Device-measured BMR — most accurate baseline |
| `Vo2MaxRecord` | `READ_VO2_MAX` | Unlocks best HR-based formula (Keytel+VO2max) |
| `HeartRateRecord` | `READ_HEART_RATE` | Exercise-window average HR — biggest accuracy jump |
| `RestingHeartRateRecord` | `READ_RESTING_HEART_RATE` | HRR intensity calculation; VO2max estimation |
| `SpeedRecord` | `READ_SPEED` | Pace-based ACSM VO2 formula (preferred over distance/time) |
| `DistanceRecord` | `READ_DISTANCE` | Speed fallback; elevation grade calculation |
| `ElevationGainedRecord` | `READ_ELEVATION_GAINED` | Grade component for ACSM walking/running equations |
| `PowerRecord` | `READ_POWER` | Physics-based calorie calc for cycling (highest accuracy) |
| `StepsRecord` | `READ_STEPS` | Cadence; stride-based distance estimation |
| `BodyWaterMassRecord` | `READ_BODY_WATER_MASS` | Available but **not used** in calculation (insufficient research on calorie impact) |
| `BoneMassRecord` | `READ_BONE_MASS` | Available; not metabolically active, not used directly |
| `HeartRateVariabilityRecord` | `READ_HEART_RATE_VARIABILITY` | Available; recovery signal only, not used in calorie calc |

> **Note on BIA devices:** Samsung Galaxy Watch (Series 4+) writes `LeanBodyMassRecord`,
> `BodyFatRecord`, and `BasalMetabolicRateRecord` directly to Health Connect via Samsung Health
> after a BIA measurement. If these are present, always prefer them over any formula-derived values.
> See [Samsung Developer Blog](https://developer.samsung.com/health/blog/en/health/blog/reading-body-composition-data-with-galaxy-watch-via-health-connect-api).

---

## 2. User Preference Inputs Required

These cannot come from Health Connect and must be stored in app preferences:

| Preference | Type | Used In | Default |
|---|---|---|---|
| `age` | Int (years) | Keytel HR formula; max HR estimation | `null` |
| `sex` | Enum (MALE/FEMALE) | Keytel HR formula | `null` |
| `defaultWeightKg` | Double | Fallback when no WeightRecord exists | `70.0` |
| `acsmRunningCorrectionFactor` | Double | Corrects ACSM running overestimation | `0.90` |
| `epocMultiplierStrength` | Double | Post-exercise oxygen for strength | `1.07` |
| `secondsPerRep` | Map<ExerciseType, Double> | Active time modeling for strength | see §8 |

---

## 3. Input Enrichment Phase

Before selecting a formula, collect all available data from Health Connect for the exercise window
`[startTime, endTime]`:

```kotlin
data class ExerciseInputs(
    // Always available
    val exerciseType: ExerciseType,
    val durationMinutes: Double,
    val met: Double,

    // From HC or user prefs
    val weightKg: Double,                   // WeightRecord (latest) or defaultWeightKg
    val lbmKg: Double?,                     // LeanBodyMassRecord (latest) — preferred
    val bodyFatFraction: Double?,           // BodyFatRecord (latest) — LBM fallback
    val measuredBmr: Double?,               // BasalMetabolicRateRecord (kcal/day) (latest)
    val vo2maxMlKgMin: Double?,             // Vo2MaxRecord (latest)
    val avgHeartRate: Double?,              // HeartRateRecord avg over [start, end], clamped [40, 220-age]
    val restingHeartRate: Double?,          // RestingHeartRateRecord (latest)
    val avgSpeedMps: Double?,               // SpeedRecord avg over [start, end]
    val distanceMeters: Double?,            // DistanceRecord sum over [start, end]
    val elevationGainedMeters: Double?,     // ElevationGainedRecord sum over [start, end]
    val avgPowerWatts: Double?,             // PowerRecord avg over [start, end]
    val steps: Long?,                       // StepsRecord sum over [start, end]
    val heightMeters: Double?,              // HeightRecord (latest)

    // User preferences
    val age: Int?,
    val sex: Sex?,                          // MALE / FEMALE
)
```

**HR filtering rules:**
- Only use HR samples within `[startTime, endTime]`
- Discard samples where `HR < 40` or `HR > (220 - age)` (use 220 if age unknown)
- Compute the arithmetic mean of remaining samples

---

## 4. BMR Computation

BMR is used as a correction signal for LBM-adjusted MET (Tier 1A). If a measured BMR is available
from HC, skip all formulas.

### Priority order:

**1. Measured BMR (from HC `BasalMetabolicRateRecord`)**
Use directly. This is device-measured (e.g., BIA) and outperforms all predictive equations.

**2. Katch-McArdle (requires LBM)**
Best predictive formula when body composition is known. Especially accurate for athletes and
lean individuals. No sex term needed — LBM already accounts for this.

```
BMR (kcal/day) = 370 + (21.6 × LBM_kg)
```

Where `LBM_kg` comes from `LeanBodyMassRecord` directly, or computed as:
```
LBM_kg = weightKg × (1.0 - bodyFatFraction)
```

> Source: [Katch & McArdle, 1975](https://nutrium.com/blog/katch-mcardle-equation-for-nutrition-professionals/)
> Validation: Pearson r = 0.85–0.92 vs indirect calorimetry in athletic populations.
> Preferred over Harris-Benedict and Mifflin-St Jeor when LBM is known.
> See also: [BodySpec — BMR Calculator](https://www.bodyspec.com/blog/post/bmr_calculator_using_it_for_metabolic_insights)

**3. Mifflin-St Jeor (requires age, sex, height, weight)**
Most accurate general-population formula when body composition is unavailable.

```
Male:   BMR = (10 × weightKg) + (6.25 × heightCm) - (5 × age) + 5
Female: BMR = (10 × weightKg) + (6.25 × heightCm) - (5 × age) - 161
```

> Source: [Mifflin et al., 1990 — Calculator.net BMR reference](https://www.calculator.net/bmr-calculator.html)
> Accurate within 10% for most non-obese individuals.

**4. No BMR available**
Skip BMR correction; use raw `weightKg` in MET formula (Tier 0).

---

## 5. Base Calorie Method Selection

Formulas are ordered by accuracy. Always use the **highest tier for which all required inputs exist.**

---

### Tier 5 — Power (Cycling)

**Requires:** `avgPowerWatts`, `exerciseType == CYCLING`

Power-based calculation is physics-derived and the most accurate method available for cycling.
No biological estimation involved — only mechanical efficiency assumption.

```kotlin
fun estimateCaloriesPower(avgPowerWatts: Double, durationSeconds: Double): Double {
    val efficiencyFactor = 0.24   // Human cycling efficiency ~22–26%; 0.24 is conservative midpoint
    val joulesPerKcal = 4184.0
    return (avgPowerWatts * durationSeconds) / (joulesPerKcal * efficiencyFactor)
}
```

**Accuracy:** ±5% (limited by efficiency assumption variation between individuals)

> Source: [ACSM Metabolic Equations — Compendium of Physical Activities](https://pacompendium.com/unite-conversions/)

---

### Tier 4 — Heart Rate + VO2max

**Requires:** `avgHeartRate`, `vo2maxMlKgMin` (measured or estimated), `weightKg`, `age`, `sex`

This is the Keytel et al. (2005) formula with VO2max term included. The most accurate
non-power-based formula for steady-state cardio.

```kotlin
fun estimateCaloriesKeytelVO2max(
    avgHR: Double,
    vo2max: Double,
    weightKg: Double,
    age: Int,
    sex: Sex,
    durationHours: Double
): Double {
    val calPerMin = when (sex) {
        Sex.MALE ->   (-95.7735 + (0.634 * avgHR) + (0.404 * vo2max) + (0.394 * weightKg) + (0.271 * age)) / 4.184
        Sex.FEMALE -> (-59.3954 + (0.450 * avgHR) + (0.380 * vo2max) + (0.103 * weightKg) + (0.274 * age)) / 4.184
    }
    return calPerMin * 60.0 * durationHours
}
```

> Source: [Keytel et al. (2005) — ShapeSense HR-based calculator](https://www.shapesense.com/fitness-exercise/calculators/heart-rate-based-calorie-burn-calculator.shtml)
> *Keytel LR, Goedecke JH, Noakes TD, et al. "Prediction of energy expenditure from heart rate
> monitoring during submaximal exercise." J Sports Sci. 2005 Mar;23(3):289-97.*

**VO2max estimation fallback** — if `Vo2MaxRecord` is absent but `restingHeartRate` is present:
```kotlin
fun estimateVo2max(age: Int, restingHR: Double): Double {
    val maxHR = 208.0 - (0.7 * age)   // Tanaka formula
    return 15.0 * (maxHR / restingHR)  // Uth-Sørensen method
}
```
This estimate carries ~10% error of its own. Flag in UI that VO2max is estimated.

> Source: [ShapeSense VO2max Calculator](https://www.shapesense.com/fitness-exercise/calculators/vo2max-calculator.shtml)
> Tanaka formula: *Tanaka H, Monhan KD, Seals DG. Am Coll Cardiol. 2001;37:153-156.*

**Accuracy:** ±10–12% with measured VO2max; ±15–18% with estimated VO2max

---

### Tier 3 — Heart Rate + Age + Sex

**Requires:** `avgHeartRate`, `weightKg`, `age`, `sex`
**Does not have:** VO2max

Keytel formula without VO2max term. Meaningful accuracy improvement over MET, but noticeably
worse than Tier 4.

```kotlin
fun estimateCaloriesKeytelNoVO2max(
    avgHR: Double,
    weightKg: Double,
    age: Int,
    sex: Sex,
    durationHours: Double
): Double {
    val calPerMin = when (sex) {
        Sex.MALE ->   (-55.0969 + (0.6309 * avgHR) + (0.1988 * weightKg) + (0.2017 * age)) / 4.184
        Sex.FEMALE -> (-20.4022 + (0.4472 * avgHR) - (0.1263 * weightKg) + (0.0740 * age)) / 4.184
    }
    return calPerMin * 60.0 * durationHours
}
```

> Same source: Keytel et al. (2005)

**Note:** This formula is only valid for exercise intensities between ~41–80% of VO2max
(roughly 64–89% of max HR). Below 41% VO2max, the HR–calorie relationship is unreliable.
Clamp to `max(result, metFallback)`.

**Accuracy:** ±20%

---

### Tier 2 — Heart Rate Only (Simplified)

**Requires:** `avgHeartRate`
**Missing:** age, sex (or both)

Use Heart Rate Reserve (HRR) to derive a dynamic MET, then use that as the base formula.

```kotlin
fun estimateCaloriesHRIntensity(
    avgHR: Double,
    restingHR: Double?,   // null if unavailable
    weightKg: Double,
    met: Double,          // static MET for the exercise type
    durationHours: Double,
    age: Int?
): Double {
    val maxHR = if (age != null) (208.0 - 0.7 * age) else 190.0  // population average fallback
    val hrIntensity = if (restingHR != null) {
        (avgHR - restingHR) / (maxHR - restingHR)   // Karvonen HRR method
    } else {
        avgHR / maxHR                                 // simpler %HRmax
    }
    // Scale MET linearly by intensity relative to exercise-type baseline
    // A MET of X assumes ~X/maxMET intensity; scale accordingly
    val intensityScaledMet = met * hrIntensity.coerceIn(0.5, 1.5)
    return intensityScaledMet * weightKg * durationHours
}
```

> Karvonen HRR method: [Pike Fitness — VO2max/HRR Calculator](https://pikefitness.com/resources/calculators/calorie-burn-calculator-vo2max-hrr/)

**Accuracy:** ±25%

---

### Tier 1B — ACSM Pace (Running/Walking, no HR)

**Requires:** `avgSpeedMps` or (`distanceMeters` + `durationMinutes`); `exerciseType` in {RUNNING, WALKING, TREADMILL}

The ACSM metabolic equations estimate VO2 from speed and elevation grade, which is then
converted to calories.

```kotlin
fun estimateCaloriesACSM(
    speedMps: Double,
    elevationGainedMeters: Double?,
    distanceMeters: Double?,
    weightKg: Double,
    durationMinutes: Double,
    isRunning: Boolean,
    correctionFactor: Double = 0.90   // ACSM running tends to overestimate; see note
): Double {
    val speedMpm = speedMps * 60.0   // convert m/s → m/min

    val grade = if (elevationGainedMeters != null && distanceMeters != null && distanceMeters > 0) {
        elevationGainedMeters / distanceMeters   // fractional grade
    } else 0.0

    // VO2 in ml/kg/min
    val vo2 = if (isRunning) {
        // Valid for speeds > 3.0 mph (80.4 m/min) jogging or > 5.0 mph (134 m/min) running
        (0.2 * speedMpm) + (0.9 * speedMpm * grade) + 3.5
    } else {
        // Valid for 1.9–3.7 mph (50.8–99.2 m/min)
        (0.1 * speedMpm) + (1.8 * speedMpm * grade) + 3.5
    }

    // Convert VO2 → kcal: 1 L O2 ≈ 5 kcal; VO2 in ml/kg/min → L/min = VO2/1000 * weightKg
    val lO2perMin = (vo2 / 1000.0) * weightKg
    val kcalPerMin = lO2perMin * 5.0
    val rawCalories = kcalPerMin * durationMinutes

    return rawCalories * if (isRunning) correctionFactor else 1.0
}
```

**Important caveats:**
- <u>Running:</u> Research shows the ACSM running equation overestimated VO2 in 88% of subjects
  tested. Apply `correctionFactor = 0.90` by default. See [PubMed — Evaluation of ACSM Running Equation](https://journals.lww.com/nsca-jscr/abstract/1999/08000/an_evaluation_of_the_accuracy_of_the_american.7.aspx).
- <u>Walking:</u> Valid within the 1.9–3.7 mph range. Performance degrades outside this.
  See [PMC — Cadence-Based Metabolic Equations](https://pmc.ncbi.nlm.nih.gov/articles/PMC7896743/).
- Grade estimation from `ElevationGainedRecord` and `DistanceRecord` is average grade —
  actual terrain variation is not captured.

> ACSM equation reference: [Compendium of Physical Activities — Unit Conversions](https://pacompendium.com/unite-conversions/)

**Accuracy:** ±15% (walking in valid range); ±20% (running, after correction)

---

### Tier 1A — LBM-Adjusted MET

**Requires:** `lbmKg` (from HC or computed from body fat %)

Standard MET formula but using LBM-normalized effective weight rather than total body weight.
This accounts for the fact that fat mass has ~4% the metabolic activity of lean mass.

```kotlin
fun estimateCaloriesLbmMet(
    met: Double,
    lbmKg: Double,
    durationHours: Double,
    avgBodyFatFraction: Double = 0.20   // population average; adjusts LBM back to "equivalent total weight"
): Double {
    // Normalize: a person with average body comp at this LBM would weigh:
    val effectiveWeight = lbmKg / (1.0 - avgBodyFatFraction)
    return met * effectiveWeight * durationHours
}
```

This means a lean person (low body fat) burns *more* than their scale weight predicts,
and a higher-bodyfat person burns *less*. Both corrections are physiologically correct.

> Rationale: [Katch-McArdle LBM rationale — baye.com](https://baye.com/estimating-daily-calorie-expenditure/)
> Also: [ResearchGate — BMR formula comparison](https://www.researchgate.net/post/which_formula_are_recommended_by_nutritionists_and_scientists_to_measure_BASAL_METABOLIC_RATE)

**Accuracy:** ±20–25%

---

### Tier 0 — Baseline MET

**Requires:** only `weightKg` (or default weight)

```kotlin
fun estimateCaloriesMet(met: Double, weightKg: Double, durationMinutes: Double): Double =
    met * weightKg * (durationMinutes / 60.0)
```

**Accuracy:** ±25–30%

---

## 6. Strength Exercise Modifier

For strength exercises (PUSHUP, SQUAT, PULLUP, WEIGHT_LIFTING), the total session duration
includes significant rest periods. Applying MET to the full duration overestimates active burn.

Model the session as **active intervals** (reps in motion) + **rest intervals** (standing/sitting).

```kotlin
fun estimateCaloriesStrength(
    met: Double,
    weightKg: Double,
    durationMinutes: Double,
    reps: Int?,
    secondsPerRep: Double,          // exercise-type-specific; see §8
    epocMultiplier: Double = 1.07   // ~7% post-exercise oxygen consumption for strength
): Double {
    return if (reps != null) {
        val activeMinutes = (reps * secondsPerRep) / 60.0
        val restMinutes = (durationMinutes - activeMinutes).coerceAtLeast(0.0)

        val activeCalories = met * weightKg * (activeMinutes / 60.0)
        val restCalories = 1.0 * weightKg * (restMinutes / 60.0)   // 1.0 MET = resting
        (activeCalories + restCalories) * epocMultiplier
    } else {
        // No rep data — apply MET to full duration with EPOC
        met * weightKg * (durationMinutes / 60.0) * epocMultiplier
    }
}
```

**EPOC rationale:** Heavy strength training creates excess post-exercise oxygen consumption
(EPOC) equivalent to roughly 5–15% additional calorie burn over the next several hours.
The multiplier `1.07` (7%) is a conservative middle estimate. This can be made configurable.

---

## 7. Complete Decision Tree

```
Given: exerciseType, durationMinutes, met (always available)
Collected: all ExerciseInputs from HC + user prefs

┌─ Is exerciseType == CYCLING and avgPowerWatts != null?
│    YES → Tier 5: Power formula                                    [±5%]
│    NO  ↓
│
├─ Is avgHeartRate != null?
│    YES ─┬─ vo2max available (measured or estimated from restingHR)?
│         │    YES ─┬─ age != null AND sex != null?
│         │         │    YES → Tier 4: Keytel + VO2max              [±10–12%]
│         │         │    NO  → VO2max + HR% method (no sex term)    [±15%]
│         │         └─
│         │    NO  ─┬─ age != null AND sex != null?
│         │         │    YES → Tier 3: Keytel (no VO2max)           [±20%]
│         │         │    NO  → Tier 2: HR intensity scaled MET      [±25%]
│         │         └─
│    NO   ↓
│
├─ Is exerciseType in {RUNNING, WALKING, TREADMILL} AND (speed or distance) available?
│    YES → Tier 1B: ACSM Pace equation (with elevation if available) [±15–20%]
│    NO   ↓
│
├─ Is lbmKg available (direct or computed from bodyFat)?
│    YES → Tier 1A: LBM-adjusted MET                                [±20–25%]
│    NO   ↓
│
└─ Tier 0: Standard MET × weightKg                                  [±25–30%]

THEN, if exerciseType is STRENGTH:
   → Apply strength modifier (active/rest split + EPOC) on top of whichever tier was selected
   → (If HR tier was selected, use it for active portion; MET fallback for rest portion)
```

---

## 8. Per-Exercise-Type Logic

| ID | Category | Base MET | `secondsPerRep` | Notes |
|---|---|---|---|---|
| TREADMILL | CARDIO | 8.0 | — | Use ACSM if speed available; otherwise MET |
| RUNNING | CARDIO | 9.8 | — | ACSM pace preferred; apply 0.90 correction |
| WALKING | CARDIO | 3.5 | — | ACSM pace preferred; valid at 1.9–3.7 mph |
| CYCLING | CARDIO | 7.5 | — | Power formula if available; otherwise HR/MET |
| SWIMMING | CARDIO | 6.0 | — | HR from wrist optical is unreliable in water; MET + LBM correction only |
| PUSHUP | STRENGTH | 3.8 | 2.5s | Compound push; relatively consistent cadence |
| SQUAT | STRENGTH | 5.0 | 3.0s | Heavier compound; typically slower cadence |
| PULLUP | STRENGTH | 4.0 | 3.5s | High effort per rep; slower cadence |
| WEIGHT_LIFTING | STRENGTH | 3.5 | 3.0s | General; wide variance in actual effort |

**Swimming note:** Smartwatch optical HR sensors are unreliable during swimming due to water
interference. Fall back to LBM-adjusted MET regardless of HR data availability. If HR data
exists post-swim (cool-down window), do not retroactively apply it to the swim window.

---

## 9. Accuracy Summary

| Tier | Method | Typical Error | Key Dependencies |
|---|---|---|---|
| 5 | Power (cycling) | ±5% | PowerRecord, cycling exercise |
| 4 | Keytel + VO2max + HR | ±10–12% | HR, VO2max, age, sex |
| 4* | Keytel + estimated VO2max | ±15–18% | HR, restingHR, age, sex |
| 3 | Keytel HR (no VO2max) | ±20% | HR, age, sex |
| 2 | HR intensity scaling | ±25% | HR |
| 1B | ACSM pace (running) | ±20% | speed/distance |
| 1B | ACSM pace (walking) | ±15% | speed/distance, valid speed range |
| 1A | LBM-adjusted MET | ±20–25% | LBM or body fat % |
| 0 | Standard MET | ±25–30% | weight only |

All error estimates are vs. indirect calorimetry (metabolic cart), which is the gold standard.
No formula-based approach can reach <±5% without direct gas exchange measurement.

---

## 10. Configuration Constants

```kotlin
object CalorieEstimationConfig {
    const val DEFAULT_WEIGHT_KG = 70.0
    const val CYCLING_MECHANICAL_EFFICIENCY = 0.24      // range: 0.22–0.26
    const val KCAL_PER_LITER_O2 = 5.0
    const val JOULES_PER_KCAL = 4184.0
    const val AVG_BODY_FAT_FRACTION = 0.20              // used for LBM → effective weight normalization
    const val ACSM_RUNNING_CORRECTION = 0.90            // corrects systematic overestimation
    const val EPOC_MULTIPLIER_STRENGTH = 1.07           // 7% post-exercise oxygen for strength
    const val HR_MIN_VALID = 40
    const val MAX_HR_UNKNOWN_AGE = 190                  // population average fallback
    const val VO2_REST_ML_KG_MIN = 3.5                  // 1 MET, used in VO2R calculations

    val secondsPerRep = mapOf(
        ExerciseType.PUSHUP         to 2.5,
        ExerciseType.SQUAT          to 3.0,
        ExerciseType.PULLUP         to 3.5,
        ExerciseType.WEIGHT_LIFTING to 3.0,
    )
}
```

---

## 11. Sources

| Formula / Concept | Reference |
|---|---|
| Health Connect data types (full list) | [Android Developers — HC Data Types](https://developer.android.com/health-and-fitness/health-connect/data-types) |
| Samsung Galaxy Watch BIA → HC | [Samsung Developer Blog — Reading Body Composition via HC API](https://developer.samsung.com/health/blog/en/health/blog/reading-body-composition-data-with-galaxy-watch-via-health-connect-api) |
| HC data types list (consumer summary) | [MedM — What is Health Connect](https://www.medm.com/company/blog/2024/what-is-health-connect-and-how-is-it-different-from-google-fit.html) |
| Katch-McArdle BMR formula | [Nutrium — Katch-McArdle for nutrition professionals](https://nutrium.com/blog/katch-mcardle-equation-for-nutrition-professionals/) |
| Katch-McArdle vs Harris-Benedict comparison | [baye.com — Estimating Daily Calorie Expenditure](https://baye.com/estimating-daily-calorie-expenditure/) |
| BMR formula comparison (Mifflin, Harris-Benedict, Katch-McArdle) | [Calculator.net — BMR Calculator](https://www.calculator.net/bmr-calculator.html) |
| Katch-McArdle validation (r = 0.85–0.92) | [fitliferegime.com — Katch-McArdle Equation](https://fitliferegime.com/katch-mcardle-equation-calculator/) |
| BMR formula accuracy review (ResearchGate) | [ResearchGate — BMR formula recommendations](https://www.researchgate.net/post/which_formula_are_recommended_by_nutritionists_and_scientists_to_measure_BASAL_METABOLIC_RATE) |
| Keytel HR calorie formula (with and without VO2max) | [ShapeSense — HR-Based Calorie Burn Calculator](https://www.shapesense.com/fitness-exercise/calculators/heart-rate-based-calorie-burn-calculator.shtml) |
| Keytel formula explained | [sport-calculator.com — Heart Rate Calorie Calculator](https://sport-calculator.com/calculators/general-fitness/heart-rate-calorie-calculator) |
| *Keytel LR et al. (2005) — primary paper* | *J Sports Sci. 2005 Mar;23(3):289-97* |
| Karvonen HRR intensity method | [Pike Fitness — VO2max/HRR Calorie Burn](https://pikefitness.com/resources/calculators/calorie-burn-calculator-vo2max-hrr/) |
| VO2max estimation from resting HR (Uth-Sørensen) | [ShapeSense — VO2max Calculator](https://www.shapesense.com/fitness-exercise/calculators/vo2max-calculator.shtml) |
| Tanaka max HR formula | *Tanaka H, Monhan KD, Seals DG. Am Coll Cardiol. 2001;37:153-156* |
| ACSM metabolic equations (walking, running, cycling) | [Compendium of Physical Activities — Unit Conversions](https://pacompendium.com/unite-conversions/) |
| ACSM running equation overestimation (88% of subjects) | [NSCA-JSCR — Evaluation of ACSM Running Equation (1999)](https://journals.lww.com/nsca-jscr/abstract/1999/08000/an_evaluation_of_the_accuracy_of_the_american.7.aspx) |
| ACSM walking valid speed range (0.83–1.67 m/s) | [PMC — Cadence-Based Metabolic Equations](https://pmc.ncbi.nlm.nih.gov/articles/PMC7896743/) |
| ACSM walking accuracy at altitude/grade | [PubMed — ACSM walking at altitude](https://pubmed.ncbi.nlm.nih.gov/16095415/) |
| ACSM walking calorie accuracy in college-aged adults | [PubMed — ACSM prediction accuracy](https://pubmed.ncbi.nlm.nih.gov/32150498/) |
| VO2max estimation accuracy (ACSM equations vs metabolic cart) | [PMC — Comparison of VO2max estimations (2023)](https://pmc.ncbi.nlm.nih.gov/articles/PMC10747607/) |
| HR-VO2 relationship (on/off kinetics) | [Firstbeat — VO2 Estimation from HR](https://www.firstbeat.com/wp-content/uploads/2015/10/white_paper_vo2_estimation.pdf) |
| Cycling mechanical efficiency (~24%) | [ACSM Metabolic Equations slide reference](https://www.slideshare.net/slideshow/met-calnew/26098363) |
