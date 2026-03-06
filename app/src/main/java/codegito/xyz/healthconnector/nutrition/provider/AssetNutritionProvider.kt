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
        val list = context.assets.open("nutrition/index.jsonl").bufferedReader().useLines { lines ->
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
                            fatGrams = obj.optDouble("fat", 0.0)
                        )
                    )
                }.getOrNull()
            }.toList()
        }
        cached = list
        list
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
