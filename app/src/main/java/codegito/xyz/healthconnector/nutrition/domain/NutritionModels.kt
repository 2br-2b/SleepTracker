package codegito.xyz.healthconnector.nutrition.domain

import java.time.Instant

enum class QuantityUnit { GRAM, MILLILITER, SERVING, COUNT }

data class NutritionAmount(
    val value: Double,
    val unit: QuantityUnit
)

data class NutrientVector(
    // Macros
    val calories: Double = 0.0,
    val proteinGrams: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0,
    // Fat breakdown
    val saturatedFatGrams: Double = 0.0,
    val polyunsaturatedFatGrams: Double = 0.0,
    val monounsaturatedFatGrams: Double = 0.0,
    val transFatGrams: Double = 0.0,
    // Carb breakdown
    val fiberGrams: Double = 0.0,
    val sugarGrams: Double = 0.0,
    // Minerals (all in grams as used by Health Connect)
    val sodiumGrams: Double = 0.0,
    val cholesterolGrams: Double = 0.0,
    val potassiumGrams: Double = 0.0,
    val calciumGrams: Double = 0.0,
    val ironGrams: Double = 0.0,
    val magnesiumGrams: Double = 0.0,
    val phosphorusGrams: Double = 0.0,
    val zincGrams: Double = 0.0,
    // Vitamins (all in grams)
    val vitaminAGrams: Double = 0.0,
    val vitaminCGrams: Double = 0.0,
    val vitaminDGrams: Double = 0.0,
    val vitaminEGrams: Double = 0.0,
    val vitaminKGrams: Double = 0.0,
    val vitaminB6Grams: Double = 0.0,
    val vitaminB12Grams: Double = 0.0,
    val thiaminGrams: Double = 0.0,
    val riboflavinGrams: Double = 0.0,
    val niacinGrams: Double = 0.0,
    val folateGrams: Double = 0.0,
    // Other
    val caffeineGrams: Double = 0.0,
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
