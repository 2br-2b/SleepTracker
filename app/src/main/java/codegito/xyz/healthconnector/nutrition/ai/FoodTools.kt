package codegito.xyz.healthconnector.nutrition.ai

import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet
import codegito.xyz.healthconnector.nutrition.domain.FoodCandidate
import codegito.xyz.healthconnector.nutrition.provider.NutritionProvider
import codegito.xyz.healthconnector.nutrition.data.NutritionRecentsRepository
import codegito.xyz.healthconnector.data.db.FoodServingHistoryDao
import kotlinx.coroutines.flow.first

@LLMDescription("Tools for searching and calculating food nutrition data from the local database")
class FoodTools(
    private val nutritionProvider: NutritionProvider,
    private val recentsRepository: NutritionRecentsRepository,
    private val servingHistoryDao: FoodServingHistoryDao,
    val candidateCache: MutableMap<String, FoodCandidate> = mutableMapOf(),
    var onToolCall: ((String) -> Unit)? = null  // Optional callback for logging tool calls
) : ToolSet {

    @Tool
    @LLMDescription("Search the local nutrition database. Prefer brand-name queries (e.g. 'McDonald's Quarter Pounder'). If no good match, retry with a generic name (e.g. 'hamburger').")
    suspend fun search_food(
        @LLMDescription("Search query string") query: String
    ): String {
        onToolCall?.invoke("🔧 **search_food**('$query')")
        val results = nutritionProvider.searchFoods(query, limit = 10)

        if (results.isEmpty()) {
            return "No results found."
        }

        results.forEach { candidateCache[it.id] = it }

        return results.mapIndexed { idx, food ->
            val serving = food.servingInfo?.let {
                "serving: ${it.commonQuantity ?: 1.0} ${it.commonUnit ?: "serving"} ≈ ${"%.1f".format(it.gramsPerCommonUnit)}g"
            } ?: "serving: unknown"
            val labels = if (food.labels.isNotEmpty()) "; labels: ${food.labels}" else ""
            "ID=${food.id} | ${food.name}; $serving$labels"
        }.joinToString("\n")
    }

    @Tool
    @LLMDescription("Get complete nutrition data per 100g and serving info for a food by its database ID.")
    suspend fun get_food_nutrition(
        @LLMDescription("Food ID from search results") food_id: String
    ): String {
        onToolCall?.invoke("🔧 **get_food_nutrition**('$food_id')")
        val food = candidateCache[food_id] ?: nutritionProvider.getFoodById(food_id)

        if (food == null) {
            return "Food not found (ID: $food_id)"
        }

        candidateCache[food_id] = food

        val serving = food.servingInfo?.let {
            "Serving: ${it.commonQuantity ?: 1.0} ${it.commonUnit ?: "serving"} = ${"%.1f".format(it.gramsPerCommonUnit)}g"
        } ?: "Serving: unknown"

        val n = food.nutrientsPer100g
        return buildString {
            appendLine("Name: ${food.name}")
            appendLine(serving)
            appendLine("Per 100g: calories=${"%.0f".format(n.calories)}, protein=${"%.1f".format(n.proteinGrams)}g, carbs=${"%.1f".format(n.carbsGrams)}g, fat=${"%.1f".format(n.fatGrams)}g, saturated_fat=${"%.1f".format(n.saturatedFatGrams)}g, fiber=${"%.1f".format(n.fiberGrams)}g, sugar=${"%.1f".format(n.sugarGrams)}g, sodium=${"%.2f".format(n.sodiumGrams)}g, cholesterol=${"%.2f".format(n.cholesterolGrams)}g, potassium=${"%.2f".format(n.potassiumGrams)}g, calcium=${"%.2f".format(n.calciumGrams)}g, iron=${"%.2f".format(n.ironGrams)}g")
        }
    }

    @Tool
    @LLMDescription("Calculate actual nutrition for a given amount. Use the unit from the database serving info (e.g. 'cup', 'slice', 'burger') or omit unit to pass grams directly. For partial servings (e.g. 'small burger'), reduce quantity accordingly — e.g. quantity=0.7, unit='burger'.")
    suspend fun calculate_nutrition(
        @LLMDescription("Food ID") food_id: String,
        @LLMDescription("Amount (in the given unit, or in grams if unit is omitted)") quantity: Double,
        @LLMDescription("Unit string from serving info, or omit/null to treat quantity as grams") unit: String? = null
    ): String {
        onToolCall?.invoke("🔧 **calculate_nutrition**('$food_id', $quantity${if (unit != null) ", '$unit'" else ""})")
        val food = candidateCache[food_id] ?: nutritionProvider.getFoodById(food_id)

        if (food == null) {
            return "Food not found (ID: $food_id)"
        }

        candidateCache[food_id] = food

        val grams = when {
            unit == null || unit.equals("g", ignoreCase = true) || unit.equals("grams", ignoreCase = true) -> quantity
            else -> {
                val resolved = nutritionProvider.resolveAmount(food, quantity, unit)
                resolved?.value ?: (quantity * food.baseAmount.value)
            }
        }

        val scale = grams / 100.0
        val n = food.nutrientsPer100g

        val calculatedNutrients = buildString {
            appendLine("For ${"%.1f".format(grams)}g (quantity=$quantity, unit=${unit ?: "grams"}):")
            appendLine("Calories: ${"%.0f".format(n.calories * scale)}")
            appendLine("Protein: ${"%.1f".format(n.proteinGrams * scale)}g")
            appendLine("Carbs: ${"%.1f".format(n.carbsGrams * scale)}g")
            appendLine("Fat: ${"%.1f".format(n.fatGrams * scale)}g")
            appendLine("Fiber: ${"%.1f".format(n.fiberGrams * scale)}g")
            appendLine("Sugar: ${"%.1f".format(n.sugarGrams * scale)}g")
            appendLine("Sodium: ${"%.2f".format(n.sodiumGrams * scale)}g")
            appendLine("Cholesterol: ${"%.2f".format(n.cholesterolGrams * scale)}g")
        }

        return calculatedNutrients
    }

    @Tool
    @LLMDescription("Returns recently logged foods with their last 10 serving sizes. Call this first when the user references habitual foods ('the usual', 'same as yesterday', 'my morning coffee'). Results are added to the candidate cache and can be used directly in the final output by food ID.")
    suspend fun get_recent_foods(): String {
        onToolCall?.invoke("🔧 **get_recent_foods**()")
        val recents = try {
            recentsRepository.recents().first()
        } catch (e: Exception) {
            emptyList()
        }

        if (recents.isEmpty()) {
            return "No recent foods found."
        }

        return recents.take(20).mapIndexed { _, (candidate, _) ->
            candidateCache[candidate.id] = candidate

            val history = try {
                servingHistoryDao.getHistory(candidate.id, limit = 10).first()
            } catch (e: Exception) {
                emptyList()
            }

            val servings = history.map { "${"%.0f".format(it.quantity)}" }.joinToString(", ")
            val servingDesc = if (servings.isNotEmpty()) {
                "Last ${history.size} servings (${history.firstOrNull()?.unit ?: "g"}): $servings"
            } else {
                "No serving history"
            }

            val n = candidate.nutrientsPer100g
            buildString {
                append("[${candidate.id}] ${candidate.name}; per100g: kcal=${"%.0f".format(n.calories)}, protein=${"%.1f".format(n.proteinGrams)}g, carbs=${"%.1f".format(n.carbsGrams)}g, fat=${"%.1f".format(n.fatGrams)}g")
                append("\n  $servingDesc")
            }
        }.joinToString("\n")
    }
}
