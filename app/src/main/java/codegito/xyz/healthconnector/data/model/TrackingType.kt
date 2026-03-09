package codegito.xyz.healthconnector.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class TrackingType {
    SLEEP,
    NUTRITION;

    val displayName: String get() = when (this) {
        SLEEP -> "Sleep"
        NUTRITION -> "Nutrition"
    }

    val description: String get() = when (this) {
        SLEEP -> "Track sleep sessions using screen lock/unlock events or manual logging."
        NUTRITION -> "Log meals and track nutritional intake via Health Connect."
    }
}
