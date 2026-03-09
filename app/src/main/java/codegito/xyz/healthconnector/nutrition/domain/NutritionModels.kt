package codegito.xyz.healthconnector.nutrition.domain

import java.time.Instant

enum class QuantityUnit { GRAM, MILLILITER, SERVING, COUNT }

data class NutritionAmount(
    val value: Double,
    val unit: QuantityUnit
)

/**
 * Serving size information extracted from the food database.
 * [commonUnit] is the human-friendly unit (e.g. "large egg", "oz", "cup").
 * [commonQuantity] is how many of that unit constitutes one serving.
 * [metricQuantity] is the weight in grams of that serving — used to scale nutrient values.
 *
 * Grams per common unit = metricQuantity / (commonQuantity ?: 1.0)
 */
data class ServingInfo(
    val commonUnit: String?,
    val commonQuantity: Double?,
    val metricUnit: String = "g",
    val metricQuantity: Double
) {
    /** Grams per one [commonUnit]. Guarded against divide-by-zero. */
    val gramsPerCommonUnit: Double
        get() = metricQuantity / (commonQuantity ?: 1.0).coerceAtLeast(0.001)
}

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

/**
 * A food item that can be searched for and selected for logging.
 *
 * [nutrientsPer100g] contains all nutrient values normalized to 100g.
 * [servingInfo] describes the natural serving size for this food (if available).
 * [labels] are decorative tags from the dataset (e.g. "cooked", "raw").
 *
 * The [baseAmount] field is kept for backward compatibility with [NutritionRecentsRepository]
 * and the multiplier logic in the logging UI (always 100g for database foods).
 * The [nutrientsPerBase] getter is a compatibility alias for [nutrientsPer100g].
 */
data class FoodCandidate(
    val id: String,
    val name: String,
    val servingInfo: ServingInfo?,
    val nutrientsPer100g: NutrientVector,
    val labels: List<String> = emptyList(),
    val foodType: String? = null,
    // Backward compatibility — always NutritionAmount(100.0, GRAM) for database foods
    val baseAmount: NutritionAmount = NutritionAmount(100.0, QuantityUnit.GRAM),
    @Deprecated("Use servingInfo instead") val servingSizeOz: Double? = null
) {
    /** Compatibility alias for [nutrientsPer100g]. */
    val nutrientsPerBase: NutrientVector get() = nutrientsPer100g
}

data class LoggedFoodEntry(
    val id: String,
    val name: String,
    val startTime: Instant,
    val endTime: Instant,
    val mealLabel: String,
    val amount: NutritionAmount,
    val nutrients: NutrientVector
)
