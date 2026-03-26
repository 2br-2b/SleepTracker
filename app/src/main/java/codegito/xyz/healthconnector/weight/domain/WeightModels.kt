package codegito.xyz.healthconnector.weight.domain

import java.time.Instant

enum class WeightUnit { KG, LBS }

data class WeightEntry(
    val id: String,
    val timestamp: Instant,
    val weightKg: Double,
    val healthConnectId: String? = null
)
