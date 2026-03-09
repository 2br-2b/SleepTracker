package codegito.xyz.healthconnector.nutrition.provider

import codegito.xyz.healthconnector.nutrition.domain.FoodCandidate
import codegito.xyz.healthconnector.nutrition.domain.NutritionAmount
import codegito.xyz.healthconnector.nutrition.domain.QuantityUnit

interface NutritionProvider {
    suspend fun searchFoods(query: String, limit: Int = 30): List<FoodCandidate>

    /**
     * Look up a food by its exact name (case-insensitive).
     * Returns the best match, or null if not found.
     * Intended for AI-driven logging: "I ate 5 carrots" → getFoodByName("carrot").
     */
    suspend fun getFoodByName(name: String): FoodCandidate?

    /**
     * Look up a food by its dataset ID.
     * Intended for AI-driven logging and deduplication.
     */
    suspend fun getFoodById(id: String): FoodCandidate?

    /**
     * Resolve a human-specified quantity to a [NutritionAmount] in grams.
     * Uses [FoodCandidate.servingInfo] to convert from natural units.
     *
     * Examples (for future AI integration):
     *   "I ate 5 carrots" → resolveAmount(carrot, 5.0, null) → ~250g
     *   "I ate 3 oz chicken" → resolveAmount(chicken, 3.0, "oz") → 85g
     *
     * Returns null if the unit cannot be resolved (caller should prompt for clarification).
     */
    suspend fun resolveAmount(
        food: FoodCandidate,
        humanQuantity: Double,
        humanUnit: String?
    ): NutritionAmount?

    /**
     * Invalidate any in-memory cache. Call after rebuilding the database.
     * No-op for providers that query directly from SQLite.
     */
    fun invalidateCache()
}

/**
 * Default implementation of [NutritionProvider.resolveAmount] using [FoodCandidate.servingInfo].
 * Exposed as a top-level function so it can be shared between provider implementations.
 */
fun resolveAmountFromServingInfo(
    food: FoodCandidate,
    humanQuantity: Double,
    humanUnit: String?
): NutritionAmount? {
    val si = food.servingInfo ?: return null
    val unit = humanUnit?.trim()?.lowercase()
    val servingUnit = si.commonUnit?.lowercase()
    return if (unit == null || unit == servingUnit || unit == "serving") {
        NutritionAmount(humanQuantity * si.gramsPerCommonUnit, QuantityUnit.GRAM)
    } else {
        null  // unknown unit — caller should prompt
    }
}
