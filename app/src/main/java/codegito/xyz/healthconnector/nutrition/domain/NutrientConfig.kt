package codegito.xyz.healthconnector.nutrition.domain

import kotlinx.serialization.Serializable

enum class NutrientKey {
    CALORIES,
    PROTEIN,
    CARBS,
    FAT,
    SATURATED_FAT,
    POLYUNSATURATED_FAT,
    MONOUNSATURATED_FAT,
    TRANS_FAT,
    FIBER,
    SUGAR,
    SODIUM,
    CHOLESTEROL,
    POTASSIUM,
    CALCIUM,
    IRON,
    MAGNESIUM,
    PHOSPHORUS,
    ZINC,
    VITAMIN_A,
    VITAMIN_C,
    VITAMIN_D,
    VITAMIN_E,
    VITAMIN_K,
    VITAMIN_B6,
    VITAMIN_B12,
    THIAMIN,
    RIBOFLAVIN,
    NIACIN,
    FOLATE,
    CAFFEINE,
}

enum class NutritionUnitSystem { US, METRIC }

@Serializable
data class NutrientConfig(
    val key: String,
    val enabled: Boolean,
)

object NutrientDefaults {

    /**
     * Core "Nutrition Facts" panel nutrients — always tracked regardless of user config.
     * These are not shown in the configurable nutrient selection screen.
     */
    val nutritionalFactsKeys: Set<NutrientKey> = setOf(
        NutrientKey.CALORIES,
        NutrientKey.PROTEIN,
        NutrientKey.CARBS,
        NutrientKey.FAT,
        NutrientKey.SATURATED_FAT,
        NutrientKey.TRANS_FAT,
        NutrientKey.CHOLESTEROL,
        NutrientKey.SODIUM,
        NutrientKey.FIBER,
        NutrientKey.SUGAR,
    )

    private val defaultEnabledKeys = setOf(
        NutrientKey.CALORIES,
        NutrientKey.CARBS,
        NutrientKey.FAT,
        NutrientKey.SATURATED_FAT,
        NutrientKey.TRANS_FAT,
        NutrientKey.CHOLESTEROL,
        NutrientKey.SODIUM,
        NutrientKey.PROTEIN,
        NutrientKey.FIBER,
        NutrientKey.SUGAR,
        NutrientKey.POTASSIUM,
        NutrientKey.CAFFEINE,
    )

    val displayName: Map<NutrientKey, String> = mapOf(
        NutrientKey.CALORIES to "Calories",
        NutrientKey.PROTEIN to "Protein",
        NutrientKey.CARBS to "Carbohydrates",
        NutrientKey.FAT to "Total Fat",
        NutrientKey.SATURATED_FAT to "Saturated Fat",
        NutrientKey.POLYUNSATURATED_FAT to "Polyunsaturated Fat",
        NutrientKey.MONOUNSATURATED_FAT to "Monounsaturated Fat",
        NutrientKey.TRANS_FAT to "Trans Fat",
        NutrientKey.FIBER to "Dietary Fiber",
        NutrientKey.SUGAR to "Total Sugars",
        NutrientKey.SODIUM to "Sodium",
        NutrientKey.CHOLESTEROL to "Cholesterol",
        NutrientKey.POTASSIUM to "Potassium",
        NutrientKey.CALCIUM to "Calcium",
        NutrientKey.IRON to "Iron",
        NutrientKey.MAGNESIUM to "Magnesium",
        NutrientKey.PHOSPHORUS to "Phosphorus",
        NutrientKey.ZINC to "Zinc",
        NutrientKey.VITAMIN_A to "Vitamin A",
        NutrientKey.VITAMIN_C to "Vitamin C",
        NutrientKey.VITAMIN_D to "Vitamin D",
        NutrientKey.VITAMIN_E to "Vitamin E",
        NutrientKey.VITAMIN_K to "Vitamin K",
        NutrientKey.VITAMIN_B6 to "Vitamin B6",
        NutrientKey.VITAMIN_B12 to "Vitamin B12",
        NutrientKey.THIAMIN to "Thiamin (B1)",
        NutrientKey.RIBOFLAVIN to "Riboflavin (B2)",
        NutrientKey.NIACIN to "Niacin (B3)",
        NutrientKey.FOLATE to "Folate",
        NutrientKey.CAFFEINE to "Caffeine",
    )

    /** Human-readable unit for display and manual entry fields */
    val displayUnit: Map<NutrientKey, String> = mapOf(
        NutrientKey.CALORIES to "kcal",
        NutrientKey.PROTEIN to "g",
        NutrientKey.CARBS to "g",
        NutrientKey.FAT to "g",
        NutrientKey.SATURATED_FAT to "g",
        NutrientKey.POLYUNSATURATED_FAT to "g",
        NutrientKey.MONOUNSATURATED_FAT to "g",
        NutrientKey.TRANS_FAT to "g",
        NutrientKey.FIBER to "g",
        NutrientKey.SUGAR to "g",
        NutrientKey.SODIUM to "mg",
        NutrientKey.CHOLESTEROL to "mg",
        NutrientKey.POTASSIUM to "mg",
        NutrientKey.CALCIUM to "mg",
        NutrientKey.IRON to "mg",
        NutrientKey.MAGNESIUM to "mg",
        NutrientKey.PHOSPHORUS to "mg",
        NutrientKey.ZINC to "mg",
        NutrientKey.VITAMIN_A to "mcg",
        NutrientKey.VITAMIN_C to "mg",
        NutrientKey.VITAMIN_D to "mcg",
        NutrientKey.VITAMIN_E to "mg",
        NutrientKey.VITAMIN_K to "mcg",
        NutrientKey.VITAMIN_B6 to "mg",
        NutrientKey.VITAMIN_B12 to "mcg",
        NutrientKey.THIAMIN to "mg",
        NutrientKey.RIBOFLAVIN to "mg",
        NutrientKey.NIACIN to "mg",
        NutrientKey.FOLATE to "mcg",
        NutrientKey.CAFFEINE to "mg",
    )

    /**
     * Factor to multiply the raw value from [NutrientVector] to get the display-unit value.
     * NutrientVector stores minerals/vitamins in grams; display in mg or mcg.
     * For CALORIES: the vector field is already in kcal, factor = 1.0.
     */
    val gramsToDisplayUnit: Map<NutrientKey, Double> = mapOf(
        NutrientKey.CALORIES to 1.0,
        NutrientKey.PROTEIN to 1.0,
        NutrientKey.CARBS to 1.0,
        NutrientKey.FAT to 1.0,
        NutrientKey.SATURATED_FAT to 1.0,
        NutrientKey.POLYUNSATURATED_FAT to 1.0,
        NutrientKey.MONOUNSATURATED_FAT to 1.0,
        NutrientKey.TRANS_FAT to 1.0,
        NutrientKey.FIBER to 1.0,
        NutrientKey.SUGAR to 1.0,
        NutrientKey.SODIUM to 1_000.0,
        NutrientKey.CHOLESTEROL to 1_000.0,
        NutrientKey.POTASSIUM to 1_000.0,
        NutrientKey.CALCIUM to 1_000.0,
        NutrientKey.IRON to 1_000.0,
        NutrientKey.MAGNESIUM to 1_000.0,
        NutrientKey.PHOSPHORUS to 1_000.0,
        NutrientKey.ZINC to 1_000.0,
        NutrientKey.VITAMIN_A to 1_000_000.0,
        NutrientKey.VITAMIN_C to 1_000.0,
        NutrientKey.VITAMIN_D to 1_000_000.0,
        NutrientKey.VITAMIN_E to 1_000.0,
        NutrientKey.VITAMIN_K to 1_000_000.0,
        NutrientKey.VITAMIN_B6 to 1_000.0,
        NutrientKey.VITAMIN_B12 to 1_000_000.0,
        NutrientKey.THIAMIN to 1_000.0,
        NutrientKey.RIBOFLAVIN to 1_000.0,
        NutrientKey.NIACIN to 1_000.0,
        NutrientKey.FOLATE to 1_000_000.0,
        NutrientKey.CAFFEINE to 1_000.0,
    )

    fun defaultConfig(): List<NutrientConfig> =
        NutrientKey.entries.map { key ->
            NutrientConfig(key = key.name, enabled = key in defaultEnabledKeys)
        }

    /** Returns the raw stored value from [vector] for the given [key]. */
    fun getValueGrams(vector: NutrientVector, key: NutrientKey): Double = when (key) {
        NutrientKey.CALORIES -> vector.calories
        NutrientKey.PROTEIN -> vector.proteinGrams
        NutrientKey.CARBS -> vector.carbsGrams
        NutrientKey.FAT -> vector.fatGrams
        NutrientKey.SATURATED_FAT -> vector.saturatedFatGrams
        NutrientKey.POLYUNSATURATED_FAT -> vector.polyunsaturatedFatGrams
        NutrientKey.MONOUNSATURATED_FAT -> vector.monounsaturatedFatGrams
        NutrientKey.TRANS_FAT -> vector.transFatGrams
        NutrientKey.FIBER -> vector.fiberGrams
        NutrientKey.SUGAR -> vector.sugarGrams
        NutrientKey.SODIUM -> vector.sodiumGrams
        NutrientKey.CHOLESTEROL -> vector.cholesterolGrams
        NutrientKey.POTASSIUM -> vector.potassiumGrams
        NutrientKey.CALCIUM -> vector.calciumGrams
        NutrientKey.IRON -> vector.ironGrams
        NutrientKey.MAGNESIUM -> vector.magnesiumGrams
        NutrientKey.PHOSPHORUS -> vector.phosphorusGrams
        NutrientKey.ZINC -> vector.zincGrams
        NutrientKey.VITAMIN_A -> vector.vitaminAGrams
        NutrientKey.VITAMIN_C -> vector.vitaminCGrams
        NutrientKey.VITAMIN_D -> vector.vitaminDGrams
        NutrientKey.VITAMIN_E -> vector.vitaminEGrams
        NutrientKey.VITAMIN_K -> vector.vitaminKGrams
        NutrientKey.VITAMIN_B6 -> vector.vitaminB6Grams
        NutrientKey.VITAMIN_B12 -> vector.vitaminB12Grams
        NutrientKey.THIAMIN -> vector.thiaminGrams
        NutrientKey.RIBOFLAVIN -> vector.riboflavinGrams
        NutrientKey.NIACIN -> vector.niacinGrams
        NutrientKey.FOLATE -> vector.folateGrams
        NutrientKey.CAFFEINE -> vector.caffeineGrams
    }

    /** Returns the value in human-readable display units (e.g. mg for sodium). */
    fun getValueInDisplayUnit(vector: NutrientVector, key: NutrientKey): Double =
        getValueGrams(vector, key) * (gramsToDisplayUnit[key] ?: 1.0)

    /**
     * Format a display-unit value for showing in UI.
     * Uses 1 decimal place for small values, 0 decimals for large ones.
     */
    fun formatValue(displayValue: Double, key: NutrientKey): String {
        val unit = displayUnit[key] ?: ""
        return if (displayValue < 10.0) "%.1f %s".format(displayValue, unit)
        else "${displayValue.toInt()} $unit"
    }
}

/** Kilograms per pound. */
const val KG_PER_LB = 0.453592

fun Double.kgToLbs(): Double = this / KG_PER_LB
fun Double.lbsToKg(): Double = this * KG_PER_LB

/** Format a body weight for display given the unit system. */
fun formatWeight(kg: Double, unitSystem: NutritionUnitSystem): String =
    if (unitSystem == NutritionUnitSystem.US) "%.1f lbs".format(kg.kgToLbs())
    else "%.1f kg".format(kg)

/** Label for weight input field given the unit system. */
fun weightUnitLabel(unitSystem: NutritionUnitSystem): String =
    if (unitSystem == NutritionUnitSystem.US) "lbs" else "kg"

/** Parse a weight string to kg given the unit system. */
fun parseWeightToKg(text: String, unitSystem: NutritionUnitSystem): Double? {
    val v = text.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
    return if (unitSystem == NutritionUnitSystem.US) v.lbsToKg() else v
}

/** Convert kg to display string for weight input field. */
fun kgToWeightText(kg: Double, unitSystem: NutritionUnitSystem): String =
    if (unitSystem == NutritionUnitSystem.US) "%.1f".format(kg.kgToLbs())
    else "%.1f".format(kg)

/** Grams per US fluid ounce (weight ounce for solids). */
const val GRAMS_PER_OZ = 28.3495

fun Double.gramsToOz(): Double = this / GRAMS_PER_OZ
fun Double.ozToGrams(): Double = this * GRAMS_PER_OZ

/** Format an amount for display given the unit system. */
fun formatAmount(grams: Double, unitSystem: NutritionUnitSystem): String =
    if (unitSystem == NutritionUnitSystem.US) {
        val oz = grams.gramsToOz()
        if (oz < 10) "%.1f oz".format(oz) else "${oz.toInt()} oz"
    } else {
        "${grams.toInt()} g"
    }

/** Label for the amount input field given the unit system. */
fun amountUnitLabel(unitSystem: NutritionUnitSystem): String =
    if (unitSystem == NutritionUnitSystem.US) "oz" else "g"

/** Parse an amount string to grams given the unit system. */
fun parseAmountToGrams(text: String, unitSystem: NutritionUnitSystem): Double? {
    val v = text.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
    return if (unitSystem == NutritionUnitSystem.US) v.ozToGrams() else v
}

/** Convert grams to the display string for the amount input field. */
fun gramsToAmountText(grams: Double, unitSystem: NutritionUnitSystem): String =
    if (unitSystem == NutritionUnitSystem.US) "%.1f".format(grams.gramsToOz())
    else grams.toInt().toString()
