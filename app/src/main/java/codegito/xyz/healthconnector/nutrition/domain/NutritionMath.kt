package codegito.xyz.healthconnector.nutrition.domain

object NutritionMath {
    fun scaleNutrients(base: NutrientVector, amount: Double, baseAmount: Double): NutrientVector {
        val factor = if (baseAmount == 0.0) 0.0 else amount / baseAmount
        return NutrientVector(
            calories = base.calories * factor,
            proteinGrams = base.proteinGrams * factor,
            carbsGrams = base.carbsGrams * factor,
            fatGrams = base.fatGrams * factor
        )
    }

    fun sumNutrients(values: List<NutrientVector>): NutrientVector {
        return values.fold(NutrientVector()) { acc, item ->
            NutrientVector(
                calories = acc.calories + item.calories,
                proteinGrams = acc.proteinGrams + item.proteinGrams,
                carbsGrams = acc.carbsGrams + item.carbsGrams,
                fatGrams = acc.fatGrams + item.fatGrams
            )
        }
    }
}
