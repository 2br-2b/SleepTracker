package codegito.xyz.healthconnector.nutrition.data

import android.content.Context
import codegito.xyz.healthconnector.nutrition.domain.FoodCandidate
import codegito.xyz.healthconnector.nutrition.domain.NutritionAmount
import codegito.xyz.healthconnector.nutrition.provider.NutritionProvider
import codegito.xyz.healthconnector.nutrition.provider.resolveAmountFromServingInfo

/**
 * [NutritionProvider] backed by the runtime SQLite nutrition database.
 * Queries run on [kotlinx.coroutines.Dispatchers.IO] inside [NutritionDatabase].
 */
class NutritionDatabaseProvider(
    context: Context
) : NutritionProvider {

    private val db = NutritionDatabase.getInstance(context)

    override suspend fun searchFoods(query: String, limit: Int): List<FoodCandidate> =
        db.searchFoods(query.trim(), limit)

    override suspend fun getFoodByName(name: String): FoodCandidate? =
        db.getFoodByName(name)

    override suspend fun getFoodById(id: String): FoodCandidate? =
        db.getFoodById(id)

    override suspend fun getFoodByBarcode(barcode: String): FoodCandidate? =
        db.getFoodByBarcode(barcode)

    override suspend fun resolveAmount(
        food: FoodCandidate,
        humanQuantity: Double,
        humanUnit: String?
    ): NutritionAmount? = resolveAmountFromServingInfo(food, humanQuantity, humanUnit)

    /** No-op: SQLite queries are always fresh. */
    override fun invalidateCache() = Unit
}
