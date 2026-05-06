package codegito.xyz.healthconnector.exercise.domain

import kotlinx.serialization.Serializable

@Serializable
data class ExerciseTypeConfig(
    val typeId: String,
    val isEnabled: Boolean = true
)

fun getDefaultExerciseTypeConfig(): List<ExerciseTypeConfig> =
    DefaultExerciseTypes.all.map { ExerciseTypeConfig(typeId = it.id, isEnabled = true) }
