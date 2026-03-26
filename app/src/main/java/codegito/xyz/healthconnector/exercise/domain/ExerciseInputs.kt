package codegito.xyz.healthconnector.exercise.domain

/**
 * All inputs gathered for calorie estimation, combining mandatory fields with
 * optional enrichment sourced from Health Connect records and user preferences.
 */
data class ExerciseInputs(
    // Always available
    val exerciseType: ExerciseType,
    val durationMinutes: Double,
    val met: Double,

    // From HC (latest available) or user prefs fallback
    val weightKg: Double,                   // WeightRecord (latest) or defaultWeightKg
    val lbmKg: Double?,                     // LeanBodyMassRecord (preferred) or computed from bodyFat
    val bodyFatFraction: Double?,           // BodyFatRecord (latest)
    val measuredBmr: Double?,               // BasalMetabolicRateRecord (kcal/day)
    val vo2maxMlKgMin: Double?,             // Vo2MaxRecord (latest)
    val avgHeartRate: Double?,              // HeartRateRecord avg over [start, end], clamped [40, 220-age]
    val restingHeartRate: Double?,          // RestingHeartRateRecord (latest)
    val avgSpeedMps: Double?,               // SpeedRecord avg over window
    val distanceMeters: Double?,            // DistanceRecord sum over window or user-entered
    val elevationGainedMeters: Double?,     // ElevationGainedRecord sum over window
    val avgPowerWatts: Double?,             // PowerRecord avg over window
    val steps: Long?,                       // StepsRecord sum over window
    val heightMeters: Double?,              // HeightRecord (latest)

    // User preferences
    val age: Int?,
    val sex: Sex?,
)

data class ExerciseUserPrefs(
    val defaultWeightKg: Double = 70.0,
    val age: Int? = null,
    val sex: Sex? = null,
    val acsmRunningCorrectionFactor: Double = 0.90,
    val epocMultiplierStrength: Double = 1.07,
)
