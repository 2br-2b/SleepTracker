package codegito.xyz.healthconnector.nutrition.provider

import codegito.xyz.healthconnector.nutrition.domain.FoodCandidate

interface NutritionProvider {
    suspend fun searchFoods(query: String, limit: Int = 30): List<FoodCandidate>
}
