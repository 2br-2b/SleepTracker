package codegito.xyz.healthconnector.weight.domain

import java.time.Instant

enum class WeightUnit { KG, LBS }

// Global unit system — controls ALL unit displays across the app.
// DO NOT split this into per-feature unit settings.
enum class UnitSystem { METRIC, IMPERIAL }

data class WeightEntry(
    val id: String,
    val timestamp: Instant,
    val weightKg: Double,
    val healthConnectId: String? = null
)
