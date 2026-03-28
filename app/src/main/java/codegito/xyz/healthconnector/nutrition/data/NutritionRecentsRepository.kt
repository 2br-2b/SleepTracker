package codegito.xyz.healthconnector.nutrition.data

import codegito.xyz.healthconnector.data.db.RecentFoodDao
import codegito.xyz.healthconnector.data.db.RecentFoodEntity
import codegito.xyz.healthconnector.data.db.FoodServingHistoryDao
import codegito.xyz.healthconnector.data.db.FoodServingHistoryEntity
import codegito.xyz.healthconnector.nutrition.domain.FoodCandidate
import codegito.xyz.healthconnector.nutrition.domain.NutritionAmount
import codegito.xyz.healthconnector.nutrition.domain.NutrientVector
import codegito.xyz.healthconnector.nutrition.domain.QuantityUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class NutritionRecentsRepository(
    private val dao: RecentFoodDao,
    private val servingHistoryDao: FoodServingHistoryDao? = null
) {
    fun recents(): Flow<List<Pair<FoodCandidate, NutritionAmount>>> {
        return dao.getRecents().map { rows ->
            rows.map {
                FoodCandidate(
                    id = it.foodKey,
                    name = it.displayName,
                    servingInfo = null,
                    nutrientsPer100g = nutrientsFromJson(it.nutrientsJson, it),
                    baseAmount = NutritionAmount(100.0, QuantityUnit.GRAM),
                ) to NutritionAmount(it.quantity, parseUnit(it.unit))
            }
        }
    }

    suspend fun saveRecent(candidate: FoodCandidate, amount: NutritionAmount, sourceType: String) {
        val now = System.currentTimeMillis()
        dao.upsert(
            RecentFoodEntity(
                foodKey = candidate.id,
                displayName = candidate.name,
                quantity = amount.value,
                unit = amount.unit.name,
                calories = candidate.nutrientsPer100g.calories,
                proteinGrams = candidate.nutrientsPer100g.proteinGrams,
                carbsGrams = candidate.nutrientsPer100g.carbsGrams,
                fatGrams = candidate.nutrientsPer100g.fatGrams,
                lastUsedAtMillis = now,
                sourceType = sourceType,
                nutrientsJson = nutrientsToJson(candidate.nutrientsPer100g)
            )
        )

        // Also append to serving history
        servingHistoryDao?.insert(
            FoodServingHistoryEntity(
                foodKey = candidate.id,
                displayName = candidate.name,
                quantity = amount.value,
                unit = amount.unit.name,
                loggedAtMillis = now
            )
        )
    }

    private fun nutrientsToJson(n: NutrientVector): String {
        return JSONObject().apply {
            put("calories", n.calories)
            put("protein", n.proteinGrams)
            put("carbs", n.carbsGrams)
            put("fat", n.fatGrams)
            put("saturatedFat", n.saturatedFatGrams)
            put("polyunsaturatedFat", n.polyunsaturatedFatGrams)
            put("monounsaturatedFat", n.monounsaturatedFatGrams)
            put("transFat", n.transFatGrams)
            put("fiber", n.fiberGrams)
            put("sugar", n.sugarGrams)
            put("sodium", n.sodiumGrams)
            put("cholesterol", n.cholesterolGrams)
            put("potassium", n.potassiumGrams)
            put("calcium", n.calciumGrams)
            put("iron", n.ironGrams)
            put("magnesium", n.magnesiumGrams)
            put("phosphorus", n.phosphorusGrams)
            put("zinc", n.zincGrams)
            put("vitaminA", n.vitaminAGrams)
            put("vitaminC", n.vitaminCGrams)
            put("vitaminD", n.vitaminDGrams)
            put("vitaminE", n.vitaminEGrams)
            put("vitaminK", n.vitaminKGrams)
            put("vitaminB6", n.vitaminB6Grams)
            put("vitaminB12", n.vitaminB12Grams)
            put("thiamin", n.thiaminGrams)
            put("riboflavin", n.riboflavinGrams)
            put("niacin", n.niacinGrams)
            put("folate", n.folateGrams)
            put("caffeine", n.caffeineGrams)
        }.toString()
    }

    private fun nutrientsFromJson(json: String, fallback: RecentFoodEntity): NutrientVector {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: JSONObject()
        return NutrientVector(
            calories = obj.optDouble("calories", fallback.calories),
            proteinGrams = obj.optDouble("protein", fallback.proteinGrams),
            carbsGrams = obj.optDouble("carbs", fallback.carbsGrams),
            fatGrams = obj.optDouble("fat", fallback.fatGrams),
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
    }

    private fun parseUnit(value: String): QuantityUnit {
        return runCatching { QuantityUnit.valueOf(value) }.getOrDefault(QuantityUnit.GRAM)
    }
}
