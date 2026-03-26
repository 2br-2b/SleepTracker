package codegito.xyz.healthconnector.exercise.domain

/**
 * Tiered calorie estimation engine.
 *
 * Tiers (highest accuracy first):
 *   5 — Power formula (cycling only, requires avgPowerWatts)            ±5%
 *   4 — Keytel + VO2max + HR (requires avgHR, vo2max, age, sex)        ±10–12%
 *   3 — Keytel HR no VO2max (requires avgHR, age, sex)                 ±20%
 *   2 — HR intensity scaled MET (requires avgHR only)                   ±25%
 *   1B— ACSM pace (running/walking/treadmill, requires speed/distance)  ±15–20%
 *   1A— LBM-adjusted MET (requires lbmKg)                              ±20–25%
 *   0 — Standard MET × weightKg                                        ±25–30%
 *
 * Strength exercises apply active/rest split + EPOC on top of the selected tier.
 *
 * Sources: Keytel et al. (2005) J Sports Sci 23(3):289-97;
 *          ACSM metabolic equations; Katch-McArdle BMR; Tanaka/Uth-Sørensen VO2max.
 */
object ExerciseMath {

    object Config {
        const val DEFAULT_WEIGHT_KG = 70.0
        const val CYCLING_EFFICIENCY = 0.24          // human cycling mechanical efficiency ~22–26%
        const val KCAL_PER_LITER_O2 = 5.0
        const val JOULES_PER_KCAL = 4184.0
        const val AVG_BODY_FAT_FRACTION = 0.20       // population average for LBM normalization
        const val ACSM_RUNNING_CORRECTION = 0.90     // corrects systematic ACSM overestimation
        const val EPOC_MULTIPLIER_STRENGTH = 1.07    // 7% post-exercise oxygen for strength
        const val HR_MIN_VALID = 40.0
        const val MAX_HR_UNKNOWN_AGE = 190.0
        const val VO2_REST_ML_KG_MIN = 3.5           // 1 MET
    }

    data class CalorieResult(val calories: Double, val tier: Int)

    /** Entry point: select the best available tier and return calories + tier label. */
    fun estimate(inputs: ExerciseInputs, prefs: ExerciseUserPrefs = ExerciseUserPrefs()): CalorieResult {
        val durationHours = inputs.durationMinutes / 60.0
        val isStrength = inputs.exerciseType.category == ExerciseCategory.STRENGTH
        val isSwimming = inputs.exerciseType.id == "SWIMMING"
        val isPaceEligible = inputs.exerciseType.id in setOf("RUNNING", "WALKING", "TREADMILL")
        val isCycling = inputs.exerciseType.id == "CYCLING"

        // --- Tier 5: Power (cycling only) ---
        if (isCycling && inputs.avgPowerWatts != null) {
            val durationSeconds = inputs.durationMinutes * 60.0
            val cal = (inputs.avgPowerWatts * durationSeconds) / (Config.JOULES_PER_KCAL * Config.CYCLING_EFFICIENCY)
            return if (isStrength) applyStrength(cal, inputs, prefs) else CalorieResult(cal.coerceAtLeast(0.0), 5)
        }

        // --- Tier 4/3/2: Heart rate based (skip for swimming — wrist optical HR unreliable in water) ---
        val avgHr = if (!isSwimming) inputs.avgHeartRate else null
        if (avgHr != null && avgHr >= Config.HR_MIN_VALID) {
            val maxHr = if (inputs.age != null) (208.0 - 0.7 * inputs.age) else Config.MAX_HR_UNKNOWN_AGE
            val clampedHr = avgHr.coerceAtMost(maxHr)

            // Try to get VO2max (measured or estimated)
            val vo2max: Double? = inputs.vo2maxMlKgMin
                ?: if (inputs.restingHeartRate != null && inputs.age != null)
                    estimateVo2max(inputs.age, inputs.restingHeartRate)
                else null

            if (vo2max != null && inputs.age != null && inputs.sex != null) {
                // Tier 4: Keytel + VO2max
                val isEstimatedVo2 = inputs.vo2maxMlKgMin == null
                val cal = keytelWithVo2max(clampedHr, vo2max, inputs.weightKg, inputs.age, inputs.sex, durationHours)
                    .coerceAtLeast(metFallback(inputs.met, inputs.weightKg, inputs.durationMinutes))
                val tier = if (isEstimatedVo2) 4 else 4 // both are tier 4; estimated gets same label
                return if (isStrength) applyStrength(cal, inputs, prefs) else CalorieResult(cal, tier)
            }

            if (inputs.age != null && inputs.sex != null) {
                // Tier 3: Keytel no VO2max
                val cal = keytelNoVo2max(clampedHr, inputs.weightKg, inputs.age, inputs.sex, durationHours)
                    .coerceAtLeast(metFallback(inputs.met, inputs.weightKg, inputs.durationMinutes))
                return if (isStrength) applyStrength(cal, inputs, prefs) else CalorieResult(cal, 3)
            }

            // Tier 2: HR intensity scaled MET
            val cal = hrIntensityScaledMet(
                clampedHr, inputs.restingHeartRate, inputs.weightKg, inputs.met, durationHours, inputs.age
            )
            return if (isStrength) applyStrength(cal, inputs, prefs) else CalorieResult(cal, 2)
        }

        // --- Tier 1B: ACSM pace (running/walking/treadmill) ---
        if (isPaceEligible) {
            val speedMps = inputs.avgSpeedMps
                ?: if (inputs.distanceMeters != null && inputs.durationMinutes > 0)
                    inputs.distanceMeters / (inputs.durationMinutes * 60.0)
                else null
            if (speedMps != null && speedMps > 0.0) {
                val isRunning = inputs.exerciseType.id in setOf("RUNNING", "TREADMILL")
                val cal = acsmPace(
                    speedMps, inputs.elevationGainedMeters, inputs.distanceMeters,
                    inputs.weightKg, inputs.durationMinutes, isRunning,
                    prefs.acsmRunningCorrectionFactor
                )
                return if (isStrength) applyStrength(cal, inputs, prefs) else CalorieResult(cal, -1) // 1B
            }
        }

        // --- Tier 1A: LBM-adjusted MET ---
        val lbm = inputs.lbmKg
            ?: if (inputs.bodyFatFraction != null) inputs.weightKg * (1.0 - inputs.bodyFatFraction) else null
        if (lbm != null && lbm > 0) {
            val cal = lbmMet(inputs.met, lbm, durationHours)
            return if (isStrength) applyStrength(cal, inputs, prefs) else CalorieResult(cal, -2) // 1A
        }

        // --- Tier 0: Standard MET ---
        val cal = metFallback(inputs.met, inputs.weightKg, inputs.durationMinutes)
        return if (isStrength) applyStrength(cal, inputs, prefs) else CalorieResult(cal, 0)
    }

    // ── BMR helpers (used internally for LBM derivation) ──────────────────────

    /** Katch-McArdle BMR from lean body mass. */
    fun bmrKatchMcardle(lbmKg: Double): Double = 370.0 + (21.6 * lbmKg)

    /** Mifflin-St Jeor BMR. */
    fun bmrMifflinStJeor(weightKg: Double, heightM: Double, age: Int, sex: Sex): Double {
        val heightCm = heightM * 100.0
        return (10.0 * weightKg) + (6.25 * heightCm) - (5.0 * age) + if (sex == Sex.MALE) 5.0 else -161.0
    }

    // ── Tier 5 ────────────────────────────────────────────────────────────────

    /** Physics-based cycling calorie estimate from average power. */
    fun estimateCaloriesPower(avgPowerWatts: Double, durationSeconds: Double): Double =
        (avgPowerWatts * durationSeconds) / (Config.JOULES_PER_KCAL * Config.CYCLING_EFFICIENCY)

    // ── Tier 4 ────────────────────────────────────────────────────────────────

    /** Keytel et al. (2005) with VO2max term. */
    fun keytelWithVo2max(
        avgHR: Double,
        vo2max: Double,
        weightKg: Double,
        age: Int,
        sex: Sex,
        durationHours: Double
    ): Double {
        val calPerMin = when (sex) {
            Sex.MALE   -> (-95.7735 + (0.634 * avgHR) + (0.404 * vo2max) + (0.394 * weightKg) + (0.271 * age)) / 4.184
            Sex.FEMALE -> (-59.3954 + (0.450 * avgHR) + (0.380 * vo2max) + (0.103 * weightKg) + (0.274 * age)) / 4.184
        }
        return (calPerMin * 60.0 * durationHours).coerceAtLeast(0.0)
    }

    /** Uth-Sørensen VO2max estimate from resting HR. Uses Tanaka max HR formula. */
    fun estimateVo2max(age: Int, restingHR: Double): Double {
        val maxHR = 208.0 - (0.7 * age)
        return 15.0 * (maxHR / restingHR)
    }

    // ── Tier 3 ────────────────────────────────────────────────────────────────

    /** Keytel et al. (2005) without VO2max. Clamp to MET fallback since formula is unreliable < 41% VO2max. */
    fun keytelNoVo2max(
        avgHR: Double,
        weightKg: Double,
        age: Int,
        sex: Sex,
        durationHours: Double
    ): Double {
        val calPerMin = when (sex) {
            Sex.MALE   -> (-55.0969 + (0.6309 * avgHR) + (0.1988 * weightKg) + (0.2017 * age)) / 4.184
            Sex.FEMALE -> (-20.4022 + (0.4472 * avgHR) - (0.1263 * weightKg) + (0.0740 * age)) / 4.184
        }
        return (calPerMin * 60.0 * durationHours).coerceAtLeast(0.0)
    }

    // ── Tier 2 ────────────────────────────────────────────────────────────────

    /** Karvonen HRR intensity to scale MET when age/sex unavailable. */
    fun hrIntensityScaledMet(
        avgHR: Double,
        restingHR: Double?,
        weightKg: Double,
        met: Double,
        durationHours: Double,
        age: Int?
    ): Double {
        val maxHR = if (age != null) (208.0 - 0.7 * age) else Config.MAX_HR_UNKNOWN_AGE
        val hrIntensity = if (restingHR != null) {
            (avgHR - restingHR) / (maxHR - restingHR)
        } else {
            avgHR / maxHR
        }
        val scaledMet = met * hrIntensity.coerceIn(0.5, 1.5)
        return scaledMet * weightKg * durationHours
    }

    // ── Tier 1B ───────────────────────────────────────────────────────────────

    /** ACSM metabolic equations for running and walking. */
    fun acsmPace(
        speedMps: Double,
        elevationGainedMeters: Double?,
        distanceMeters: Double?,
        weightKg: Double,
        durationMinutes: Double,
        isRunning: Boolean,
        correctionFactor: Double = Config.ACSM_RUNNING_CORRECTION
    ): Double {
        val speedMpm = speedMps * 60.0
        val grade = if (elevationGainedMeters != null && distanceMeters != null && distanceMeters > 0)
            elevationGainedMeters / distanceMeters else 0.0

        val vo2 = if (isRunning) {
            (0.2 * speedMpm) + (0.9 * speedMpm * grade) + Config.VO2_REST_ML_KG_MIN
        } else {
            (0.1 * speedMpm) + (1.8 * speedMpm * grade) + Config.VO2_REST_ML_KG_MIN
        }

        val lO2perMin = (vo2 / 1000.0) * weightKg
        val kcalPerMin = lO2perMin * Config.KCAL_PER_LITER_O2
        val raw = kcalPerMin * durationMinutes
        return (raw * if (isRunning) correctionFactor else 1.0).coerceAtLeast(0.0)
    }

    // ── Tier 1A ───────────────────────────────────────────────────────────────

    /** LBM-normalized MET: lean person burns more than scale weight predicts, higher-fat less. */
    fun lbmMet(
        met: Double,
        lbmKg: Double,
        durationHours: Double,
        avgBodyFatFraction: Double = Config.AVG_BODY_FAT_FRACTION
    ): Double {
        val effectiveWeight = lbmKg / (1.0 - avgBodyFatFraction)
        return (met * effectiveWeight * durationHours).coerceAtLeast(0.0)
    }

    // ── Tier 0 ────────────────────────────────────────────────────────────────

    /** Baseline MET formula. */
    fun metFallback(met: Double, weightKg: Double, durationMinutes: Double): Double =
        (met * weightKg * (durationMinutes / 60.0)).coerceAtLeast(0.0)

    // ── Strength modifier ─────────────────────────────────────────────────────

    /**
     * For strength exercises, models active rep time vs rest time,
     * then applies EPOC multiplier (~7%).
     */
    fun strengthCalories(
        met: Double,
        weightKg: Double,
        durationMinutes: Double,
        reps: Int?,
        secondsPerRep: Double,
        epocMultiplier: Double = Config.EPOC_MULTIPLIER_STRENGTH
    ): Double {
        return if (reps != null && secondsPerRep > 0.0) {
            val activeMinutes = (reps * secondsPerRep) / 60.0
            val restMinutes = (durationMinutes - activeMinutes).coerceAtLeast(0.0)
            val activeCalories = met * weightKg * (activeMinutes / 60.0)
            val restCalories = 1.0 * weightKg * (restMinutes / 60.0) // 1.0 MET = resting
            ((activeCalories + restCalories) * epocMultiplier).coerceAtLeast(0.0)
        } else {
            (met * weightKg * (durationMinutes / 60.0) * epocMultiplier).coerceAtLeast(0.0)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun applyStrength(baseCalories: Double, inputs: ExerciseInputs, prefs: ExerciseUserPrefs): CalorieResult {
        val totalReps = inputs.sets?.sumOf { it.reps }
        val epocCal = strengthCalories(
            inputs.met, inputs.weightKg, inputs.durationMinutes,
            totalReps, inputs.exerciseType.secondsPerRep,
            prefs.epocMultiplierStrength
        )
        // Use the larger of the two estimates to be safe
        val cal = maxOf(baseCalories, epocCal)
        return CalorieResult(cal, 0)
    }

    /** Human-readable tier label for UI display. */
    fun tierLabel(tier: Int): String = when (tier) {
        5    -> "Power (±5%)"
        4    -> "HR+VO₂max (±10–12%)"
        3    -> "HR (±20%)"
        2    -> "HR Intensity (±25%)"
        -1   -> "ACSM Pace (±15–20%)"
        -2   -> "LBM-MET (±20–25%)"
        else -> "MET (±25–30%)"
    }
}
