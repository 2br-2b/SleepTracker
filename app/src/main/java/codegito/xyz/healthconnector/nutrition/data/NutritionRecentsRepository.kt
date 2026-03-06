package codegito.xyz.healthconnector.nutrition.data

import codegito.xyz.healthconnector.data.db.RecentFoodDao
import codegito.xyz.healthconnector.data.db.RecentFoodEntity
import codegito.xyz.healthconnector.nutrition.domain.FoodCandidate
import codegito.xyz.healthconnector.nutrition.domain.NutritionAmount
import codegito.xyz.healthconnector.nutrition.domain.NutrientVector
import codegito.xyz.healthconnector.nutrition.domain.QuantityUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NutritionRecentsRepository(
    private val dao: RecentFoodDao
) {
    fun recents(): Flow<List<Pair<FoodCandidate, NutritionAmount>>> {
        return dao.getRecents().map { rows ->
            rows.map {
                FoodCandidate(
                    id = it.foodKey,
                    name = it.displayName,
                    baseAmount = NutritionAmount(100.0, QuantityUnit.GRAM),
                    nutrientsPerBase = NutrientVector(
                        calories = it.calories,
                        proteinGrams = it.proteinGrams,
                        carbsGrams = it.carbsGrams,
                        fatGrams = it.fatGrams
                    )
                ) to NutritionAmount(it.quantity, parseUnit(it.unit))
            }
        }
    }

    suspend fun saveRecent(candidate: FoodCandidate, amount: NutritionAmount, sourceType: String) {
        dao.upsert(
            RecentFoodEntity(
                foodKey = candidate.id,
                displayName = candidate.name,
                quantity = amount.value,
                unit = amount.unit.name,
                calories = candidate.nutrientsPerBase.calories,
                proteinGrams = candidate.nutrientsPerBase.proteinGrams,
                carbsGrams = candidate.nutrientsPerBase.carbsGrams,
                fatGrams = candidate.nutrientsPerBase.fatGrams,
                lastUsedAtMillis = System.currentTimeMillis(),
                sourceType = sourceType
            )
        )
    }

    private fun parseUnit(value: String): QuantityUnit {
        return runCatching { QuantityUnit.valueOf(value) }.getOrDefault(QuantityUnit.GRAM)
    }
}
