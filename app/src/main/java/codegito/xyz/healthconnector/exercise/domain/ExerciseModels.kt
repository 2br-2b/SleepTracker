package codegito.xyz.healthconnector.exercise.domain

import java.time.Instant

enum class ExerciseCategory { CARDIO, STRENGTH, OTHER }

enum class Sex { MALE, FEMALE }

data class ExerciseType(
    val id: String,
    val displayName: String,
    val category: ExerciseCategory,
    val met: Double,
    val icon: String,
    val usesDistance: Boolean,
    val usesReps: Boolean,
    val secondsPerRep: Double = 0.0
)

data class ExerciseSet(
    val reps: Int,
    val weightKg: Double? = null
)

data class LoggedExerciseEntry(
    val id: String,
    val exerciseType: ExerciseType,
    val startTime: Instant,
    val endTime: Instant,
    val distanceMeters: Double? = null,
    val sets: List<ExerciseSet>? = null,
    val estimatedCalories: Double,
    val caloriesTier: Int,
    val notes: String? = null,
    val healthConnectId: String? = null
)

object DefaultExerciseTypes {
    val all = listOf(
        ExerciseType("TREADMILL",      "Treadmill",       ExerciseCategory.CARDIO,   8.0,  "🏃", usesDistance = true,  usesReps = false),
        ExerciseType("RUNNING",        "Running",         ExerciseCategory.CARDIO,   9.8,  "🏃", usesDistance = true,  usesReps = false),
        ExerciseType("WALKING",        "Walking",         ExerciseCategory.CARDIO,   3.5,  "🚶", usesDistance = true,  usesReps = false),
        ExerciseType("CYCLING",        "Cycling",         ExerciseCategory.CARDIO,   7.5,  "🚴", usesDistance = true,  usesReps = false),
        ExerciseType("SWIMMING",       "Swimming",        ExerciseCategory.CARDIO,   6.0,  "🏊", usesDistance = true,  usesReps = false),
        ExerciseType("PUSHUP",         "Push-up",         ExerciseCategory.STRENGTH, 3.8,  "💪", usesDistance = false, usesReps = true, secondsPerRep = 2.5),
        ExerciseType("SQUAT",          "Squat",           ExerciseCategory.STRENGTH, 5.0,  "🏋️", usesDistance = false, usesReps = true, secondsPerRep = 3.0),
        ExerciseType("PULLUP",         "Pull-up",         ExerciseCategory.STRENGTH, 4.0,  "💪", usesDistance = false, usesReps = true, secondsPerRep = 3.5),
        ExerciseType("WEIGHT_LIFTING", "Weight Lifting",  ExerciseCategory.STRENGTH, 3.5,  "🏋️", usesDistance = false, usesReps = true, secondsPerRep = 3.0),
    )
}
