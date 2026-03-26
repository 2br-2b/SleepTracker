package codegito.xyz.healthconnector.exercise

import codegito.xyz.healthconnector.exercise.domain.ExerciseCategory
import codegito.xyz.healthconnector.exercise.domain.ExerciseInputs
import codegito.xyz.healthconnector.exercise.domain.ExerciseMath
import codegito.xyz.healthconnector.exercise.domain.ExerciseMath.Config
import codegito.xyz.healthconnector.exercise.domain.ExerciseSet
import codegito.xyz.healthconnector.exercise.domain.ExerciseType
import codegito.xyz.healthconnector.exercise.domain.ExerciseUserPrefs
import codegito.xyz.healthconnector.exercise.domain.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseMathTest {

    private val treadmill = ExerciseType("TREADMILL", "Treadmill", ExerciseCategory.CARDIO, 8.0, "🏃", true, false)
    private val running   = ExerciseType("RUNNING",   "Running",   ExerciseCategory.CARDIO, 9.8, "🏃", true, false)
    private val walking   = ExerciseType("WALKING",   "Walking",   ExerciseCategory.CARDIO, 3.5, "🚶", true, false)
    private val cycling   = ExerciseType("CYCLING",   "Cycling",   ExerciseCategory.CARDIO, 7.5, "🚴", true, false)
    private val swimming  = ExerciseType("SWIMMING",  "Swimming",  ExerciseCategory.CARDIO, 6.0, "🏊", true, false)
    private val pushup    = ExerciseType("PUSHUP",    "Push-up",   ExerciseCategory.STRENGTH, 3.8, "💪", false, true, 2.5)

    private fun baseInputs(type: ExerciseType, duration: Double = 30.0) = ExerciseInputs(
        exerciseType = type, durationMinutes = duration, met = type.met,
        weightKg = 70.0, lbmKg = null, bodyFatFraction = null, measuredBmr = null,
        vo2maxMlKgMin = null, avgHeartRate = null, restingHeartRate = null,
        avgSpeedMps = null, distanceMeters = null, elevationGainedMeters = null,
        avgPowerWatts = null, steps = null, heightMeters = null,
        age = null, sex = null
    )

    // ── Tier 0: Standard MET ──────────────────────────────────────────────────

    @Test
    fun `tier 0 - basic MET formula`() {
        val result = ExerciseMath.estimate(baseInputs(treadmill, 60.0))
        // MET=8, weight=70kg, 1h → 560 kcal
        assertEquals(560.0, result.calories, 0.1)
        assertEquals(0, result.tier)
    }

    @Test
    fun `tier 0 - result is non-negative`() {
        val result = ExerciseMath.estimate(baseInputs(treadmill, 0.0))
        assertTrue(result.calories >= 0.0)
    }

    // ── Tier 1A: LBM-adjusted MET ─────────────────────────────────────────────

    @Test
    fun `tier 1A - LBM MET burns more than raw weight MET for lean person`() {
        // LBM = 70 * (1 - 0.10) = 63 kg body fat; avg fat = 0.20
        // effective weight = 63 / (1 - 0.20) = 78.75 → burns more than 70 kg
        val inputs = baseInputs(treadmill, 60.0).copy(bodyFatFraction = 0.10)
        val result = ExerciseMath.estimate(inputs)
        assertTrue("Lean person should burn more", result.calories > 560.0)
        assertEquals(-2, result.tier) // tier 1A
    }

    // ── Tier 1B: ACSM Pace ────────────────────────────────────────────────────

    @Test
    fun `tier 1B - ACSM running with distance`() {
        // 5 km in 30 min = 2.78 m/s
        val inputs = baseInputs(running, 30.0).copy(
            avgSpeedMps = 2.78,
            distanceMeters = 5000.0
        )
        val result = ExerciseMath.estimate(inputs)
        assertEquals(-1, result.tier) // tier 1B
        assertTrue("ACSM running should give reasonable calories", result.calories in 200.0..500.0)
    }

    @Test
    fun `tier 1B - ACSM walking`() {
        // 3 km in 30 min = 1.67 m/s (within valid range)
        val inputs = baseInputs(walking, 30.0).copy(
            avgSpeedMps = 1.67,
            distanceMeters = 3000.0
        )
        val result = ExerciseMath.estimate(inputs)
        assertEquals(-1, result.tier)
        assertTrue(result.calories > 0)
    }

    // ── Tier 2: HR Intensity Scaled MET ──────────────────────────────────────

    @Test
    fun `tier 2 - HR only scales MET`() {
        val inputs = baseInputs(running, 30.0).copy(avgHeartRate = 150.0)
        val result = ExerciseMath.estimate(inputs)
        assertEquals(2, result.tier)
        assertTrue(result.calories > 0)
    }

    // ── Tier 3: Keytel no VO2max ─────────────────────────────────────────────

    @Test
    fun `tier 3 - Keytel HR with age and sex male`() {
        val inputs = baseInputs(running, 30.0).copy(
            avgHeartRate = 155.0,
            age = 30,
            sex = Sex.MALE
        )
        val result = ExerciseMath.estimate(inputs)
        assertEquals(3, result.tier)
        assertTrue(result.calories > 0)
    }

    @Test
    fun `tier 3 - Keytel HR with age and sex female`() {
        val inputs = baseInputs(running, 30.0).copy(
            avgHeartRate = 155.0,
            age = 30,
            sex = Sex.FEMALE
        )
        val result = ExerciseMath.estimate(inputs)
        assertEquals(3, result.tier)
        assertTrue(result.calories > 0)
    }

    // ── Tier 4: Keytel + VO2max ───────────────────────────────────────────────

    @Test
    fun `tier 4 - Keytel with measured VO2max`() {
        val inputs = baseInputs(running, 30.0).copy(
            avgHeartRate = 155.0,
            vo2maxMlKgMin = 50.0,
            age = 30,
            sex = Sex.MALE
        )
        val result = ExerciseMath.estimate(inputs)
        assertEquals(4, result.tier)
        assertTrue(result.calories > 0)
    }

    @Test
    fun `tier 4 - estimated VO2max from resting HR`() {
        val inputs = baseInputs(running, 30.0).copy(
            avgHeartRate = 155.0,
            restingHeartRate = 55.0,
            age = 30,
            sex = Sex.MALE
        )
        val result = ExerciseMath.estimate(inputs)
        // VO2max estimated → still tier 4
        assertEquals(4, result.tier)
        assertTrue(result.calories > 0)
    }

    // ── Tier 5: Power (cycling) ───────────────────────────────────────────────

    @Test
    fun `tier 5 - power formula for cycling`() {
        // 200W for 60 min
        val inputs = baseInputs(cycling, 60.0).copy(avgPowerWatts = 200.0)
        val result = ExerciseMath.estimate(inputs)
        assertEquals(5, result.tier)
        // 200W * 3600s / (4184 * 0.24) ≈ 718 kcal
        assertEquals(718.0, result.calories, 5.0)
    }

    // ── Strength modifier ─────────────────────────────────────────────────────

    @Test
    fun `strength - with reps applies active rest split`() {
        val sets = listOf(ExerciseSet(10), ExerciseSet(10), ExerciseSet(10))
        val inputs = baseInputs(pushup, 30.0).copy(
            sets = sets,
            avgHeartRate = null
        )
        val result = ExerciseMath.estimate(inputs)
        assertTrue("Strength with reps should give plausible calories", result.calories in 50.0..300.0)
    }

    @Test
    fun `strength - without reps falls back to MET * EPOC`() {
        val inputs = baseInputs(pushup, 30.0)
        val prefs = ExerciseUserPrefs(defaultWeightKg = 70.0)
        val result = ExerciseMath.estimate(inputs, prefs)
        // MET=3.8, weight=70, 0.5h * EPOC 1.07 = ~142 kcal
        val expected = 3.8 * 70.0 * 0.5 * 1.07
        assertEquals(expected, result.calories, 1.0)
    }

    // ── Swimming ignores HR ───────────────────────────────────────────────────

    @Test
    fun `swimming - HR is ignored, falls back to MET`() {
        val inputs = baseInputs(swimming, 30.0).copy(
            avgHeartRate = 155.0,
            age = 30,
            sex = Sex.MALE
        )
        val result = ExerciseMath.estimate(inputs)
        // HR should be skipped → should use MET path, not tier 3
        assertTrue(result.tier <= 0)
    }

    // ── BMR helpers ───────────────────────────────────────────────────────────

    @Test
    fun `BMR Katch-McArdle`() {
        val bmr = ExerciseMath.bmrKatchMcardle(lbmKg = 60.0)
        // 370 + 21.6 * 60 = 1666
        assertEquals(1666.0, bmr, 0.1)
    }

    @Test
    fun `BMR Mifflin-St Jeor male`() {
        val bmr = ExerciseMath.bmrMifflinStJeor(80.0, 1.80, 30, Sex.MALE)
        // (10*80) + (6.25*180) - (5*30) + 5 = 800+1125-150+5 = 1780
        assertEquals(1780.0, bmr, 0.1)
    }

    @Test
    fun `VO2max estimate from resting HR`() {
        val vo2 = ExerciseMath.estimateVo2max(age = 30, restingHR = 60.0)
        // maxHR = 208 - 0.7*30 = 187; vo2 = 15 * (187/60) = 46.75
        assertEquals(46.75, vo2, 0.1)
    }

    // ── Tier label ────────────────────────────────────────────────────────────

    @Test
    fun `tier labels cover all expected values`() {
        listOf(5, 4, 3, 2, -1, -2, 0).forEach { tier ->
            assertTrue("Label for tier $tier should not be empty",
                ExerciseMath.tierLabel(tier).isNotEmpty())
        }
    }
}
