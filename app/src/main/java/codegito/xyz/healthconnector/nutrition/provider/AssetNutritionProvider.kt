package codegito.xyz.healthconnector.nutrition.provider

import android.content.Context
import codegito.xyz.healthconnector.nutrition.domain.FoodCandidate
import codegito.xyz.healthconnector.nutrition.domain.NutritionAmount
import codegito.xyz.healthconnector.nutrition.domain.NutrientVector
import codegito.xyz.healthconnector.nutrition.domain.QuantityUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AssetNutritionProvider(
    private val context: Context
) : NutritionProvider {

    private var cached: List<FoodCandidate>? = null

    private suspend fun loadData(): List<FoodCandidate> = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }

        val list = openIndexReader()?.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching {
                    val obj = JSONObject(line)
                    FoodCandidate(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        baseAmount = NutritionAmount(obj.optDouble("baseAmount", 100.0), QuantityUnit.GRAM),
                        nutrientsPerBase = NutrientVector(
                            calories = obj.optDouble("calories", 0.0),
                            proteinGrams = obj.optDouble("protein", 0.0),
                            carbsGrams = obj.optDouble("carbs", 0.0),
                            fatGrams = obj.optDouble("fat", 0.0),
                            saturatedFatGrams = obj.optDouble("saturatedFat", 0.0),
                            polyunsaturatedFatGrams = obj.optDouble("polyunsaturatedFat", 0.0),
                            monounsaturatedFatGrams = obj.optDouble("monounsaturatedFat", 0.0),
                            transFatGrams = obj.optDouble("transFat", 0.0),
                            fiberGrams = obj.optDouble("fiber", 0.0),
                            sugarGrams = obj.optDouble("sugar", 0.0),
                            sodiumGrams = obj.optDouble("sodium", 0.0),
                            cholesterolGrams = obj.optDouble("cholesterol", 0.0),
                            potassiumGrams = obj.optDouble("potassium", 0.0),
                            calciumGrams = obj.optDouble("calcium", 0.0),
                            ironGrams = obj.optDouble("iron", 0.0),
                            magnesiumGrams = obj.optDouble("magnesium", 0.0),
                            phosphorusGrams = obj.optDouble("phosphorus", 0.0),
                            zincGrams = obj.optDouble("zinc", 0.0),
                            vitaminAGrams = obj.optDouble("vitaminA", 0.0),
                            vitaminCGrams = obj.optDouble("vitaminC", 0.0),
                            vitaminDGrams = obj.optDouble("vitaminD", 0.0),
                            vitaminEGrams = obj.optDouble("vitaminE", 0.0),
                            vitaminKGrams = obj.optDouble("vitaminK", 0.0),
                            vitaminB6Grams = obj.optDouble("vitaminB6", 0.0),
                            vitaminB12Grams = obj.optDouble("vitaminB12", 0.0),
                            thiaminGrams = obj.optDouble("thiamin", 0.0),
                            riboflavinGrams = obj.optDouble("riboflavin", 0.0),
                            niacinGrams = obj.optDouble("niacin", 0.0),
                            folateGrams = obj.optDouble("folate", 0.0),
                            caffeineGrams = obj.optDouble("caffeine", 0.0)
                        )
                    )
                }.getOrNull()
            }.toList()
        } ?: emptyList()

        cached = list
        list
    }

    private fun openIndexReader(): java.io.BufferedReader? {
        val runtimeIndex = context.filesDir.resolve("nutrition/index.jsonl")
        if (runtimeIndex.exists()) {
            return runtimeIndex.bufferedReader()
        }
        return runCatching { context.assets.open("nutrition/index.jsonl").bufferedReader() }.getOrNull()
    }

    fun invalidateCache() {
        cached = null
    }

    override suspend fun searchFoods(query: String, limit: Int): List<FoodCandidate> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        return loadData().asSequence()
            .filter { it.name.lowercase().contains(normalized) }
            .take(limit)
            .toList()
    }
}
