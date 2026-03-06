package codegito.xyz.healthconnector.nutrition.domain

import java.time.Instant

enum class QuantityUnit { GRAM, MILLILITER, SERVING, COUNT }

data class NutritionAmount(
    val value: Double,
    val unit: QuantityUnit
)

data class NutrientVector(
    val calories: Double = 0.0,
    val proteinGrams: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0
)

data class FoodCandidate(
    val id: String,
    val name: String,
    val baseAmount: NutritionAmount,
    val nutrientsPerBase: NutrientVector
)

data class LoggedFoodEntry(
    val id: String,
    val name: String,
    val startTime: Instant,
    val endTime: Instant,
    val mealLabel: String,
    val amount: NutritionAmount,
    val nutrients: NutrientVector
)
