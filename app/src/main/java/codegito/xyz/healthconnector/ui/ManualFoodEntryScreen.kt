package codegito.xyz.healthconnector.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.navigation.NavController
import codegito.xyz.healthconnector.HealthConnectManager
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.TimeRange
import codegito.xyz.healthconnector.data.db.AppDatabase
import codegito.xyz.healthconnector.nutrition.data.NutritionRecentsRepository
import codegito.xyz.healthconnector.nutrition.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualFoodEntryScreen(
    date: LocalDate,
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    navController: NavController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recentsRepo = remember(context) {
        NutritionRecentsRepository(AppDatabase.getDatabase(context).recentFoodDao())
    }

    val nutrientConfigList by userPreferencesRepository.nutrientConfig.collectAsState(initial = NutrientDefaults.defaultConfig())
    val unitSystem by userPreferencesRepository.nutritionUnitSystem.collectAsState(initial = NutritionUnitSystem.US)
    val askEatenTime by userPreferencesRepository.nutritionAskEatenTime.collectAsState(initial = false)
    val breakfastRange by userPreferencesRepository.nutritionBreakfastRange.collectAsState(initial = TimeRange.BREAKFAST)
    val lunchRange by userPreferencesRepository.nutritionLunchRange.collectAsState(initial = TimeRange.LUNCH)
    val dinnerRange by userPreferencesRepository.nutritionDinnerRange.collectAsState(initial = TimeRange.DINNER)

    // Nutritional facts are always shown; extra micronutrients come from config
    val enabledNutrients = remember(nutrientConfigList) {
        val configEnabled = nutrientConfigList
            .filter { it.enabled }
            .mapNotNull { cfg -> runCatching { NutrientKey.valueOf(cfg.key) }.getOrNull() }
            .toSet()
        val micronutrientsEnabled = configEnabled.filter { it !in NutrientDefaults.nutritionalFactsKeys }
        NutrientDefaults.nutritionalFactsKeys.toList() + micronutrientsEnabled
    }

    var foodName by remember { mutableStateOf("") }
    // Amount text is in the currently selected unit system
    var amountText by remember { mutableStateOf(if (unitSystem == NutritionUnitSystem.US) "3.5" else "100") }
    // Track the previous unit system to re-convert when it changes
    var prevUnitSystem by remember { mutableStateOf(unitSystem) }

    // When unit system changes, convert the current amountText
    LaunchedEffect(unitSystem) {
        if (prevUnitSystem != unitSystem) {
            val prevGrams = parseAmountToGrams(amountText, prevUnitSystem)
            if (prevGrams != null) {
                amountText = gramsToAmountText(prevGrams, unitSystem)
            }
            prevUnitSystem = unitSystem
        }
    }

    // Per-nutrient text field values, keyed by NutrientKey name
    val nutrientValues = remember { mutableStateMapOf<String, String>() }

    val defaultEatenTime = remember(date) {
        if (date == LocalDate.now()) LocalTime.now() else LocalTime.NOON
    }
    var eatenTime by remember { mutableStateOf(defaultEatenTime) }
    var showEatenTimePicker by remember { mutableStateOf(false) }
    var isLogging by remember { mutableStateOf(false) }

    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    fun mealLabel(minuteOfDay: Int): String = when {
        minuteOfDay in breakfastRange -> "Breakfast"
        minuteOfDay in lunchRange -> "Lunch"
        minuteOfDay in dinnerRange -> "Dinner"
        else -> "Snack"
    }

    fun mealTypeInt(minuteOfDay: Int): Int = when {
        minuteOfDay in breakfastRange -> MealType.MEAL_TYPE_BREAKFAST
        minuteOfDay in lunchRange -> MealType.MEAL_TYPE_LUNCH
        minuteOfDay in dinnerRange -> MealType.MEAL_TYPE_DINNER
        else -> MealType.MEAL_TYPE_SNACK
    }

    fun buildNutrientVector(): NutrientVector {
        // Helper: read display-unit value and convert to grams stored in NutrientVector
        fun gramsFor(key: NutrientKey): Double {
            val displayVal = nutrientValues[key.name]?.toDoubleOrNull() ?: 0.0
            val factor = NutrientDefaults.gramsToDisplayUnit[key] ?: 1.0
            return if (factor > 0.0) displayVal / factor else displayVal
        }
        return NutrientVector(
            calories = nutrientValues[NutrientKey.CALORIES.name]?.toDoubleOrNull() ?: 0.0,
            proteinGrams = gramsFor(NutrientKey.PROTEIN),
            carbsGrams = gramsFor(NutrientKey.CARBS),
            fatGrams = gramsFor(NutrientKey.FAT),
            saturatedFatGrams = gramsFor(NutrientKey.SATURATED_FAT),
            polyunsaturatedFatGrams = gramsFor(NutrientKey.POLYUNSATURATED_FAT),
            monounsaturatedFatGrams = gramsFor(NutrientKey.MONOUNSATURATED_FAT),
            transFatGrams = gramsFor(NutrientKey.TRANS_FAT),
            fiberGrams = gramsFor(NutrientKey.FIBER),
            sugarGrams = gramsFor(NutrientKey.SUGAR),
            sodiumGrams = gramsFor(NutrientKey.SODIUM),
            cholesterolGrams = gramsFor(NutrientKey.CHOLESTEROL),
            potassiumGrams = gramsFor(NutrientKey.POTASSIUM),
            calciumGrams = gramsFor(NutrientKey.CALCIUM),
            ironGrams = gramsFor(NutrientKey.IRON),
            magnesiumGrams = gramsFor(NutrientKey.MAGNESIUM),
            phosphorusGrams = gramsFor(NutrientKey.PHOSPHORUS),
            zincGrams = gramsFor(NutrientKey.ZINC),
            vitaminAGrams = gramsFor(NutrientKey.VITAMIN_A),
            vitaminCGrams = gramsFor(NutrientKey.VITAMIN_C),
            vitaminDGrams = gramsFor(NutrientKey.VITAMIN_D),
            vitaminEGrams = gramsFor(NutrientKey.VITAMIN_E),
            vitaminKGrams = gramsFor(NutrientKey.VITAMIN_K),
            vitaminB6Grams = gramsFor(NutrientKey.VITAMIN_B6),
            vitaminB12Grams = gramsFor(NutrientKey.VITAMIN_B12),
            thiaminGrams = gramsFor(NutrientKey.THIAMIN),
            riboflavinGrams = gramsFor(NutrientKey.RIBOFLAVIN),
            niacinGrams = gramsFor(NutrientKey.NIACIN),
            folateGrams = gramsFor(NutrientKey.FOLATE),
            caffeineGrams = gramsFor(NutrientKey.CAFFEINE),
        )
    }

    fun logFood() {
        val trimmedName = foodName.trim()
        if (trimmedName.isEmpty()) {
            Toast.makeText(context, "Please enter a food name", Toast.LENGTH_SHORT).show()
            return
        }
        val grams = parseAmountToGrams(amountText, unitSystem)
        if (grams == null || grams <= 0.0) {
            Toast.makeText(context, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            isLogging = true
            val zone = ZoneId.systemDefault()
            val mealDurationMinutes = userPreferencesRepository.nutritionMealDurationMinutes.first().coerceAtLeast(1)
            val snackDurationMinutes = userPreferencesRepository.nutritionSnackDurationMinutes.first().coerceAtLeast(1)

            val endTime = if (askEatenTime) {
                date.atTime(eatenTime).atZone(zone).toInstant()
            } else if (date == LocalDate.now()) {
                Instant.now()
            } else {
                date.atTime(12, 0).atZone(zone).toInstant()
            }

            val effectiveTime = if (askEatenTime) eatenTime else LocalTime.ofInstant(endTime, zone)
            val mealType = mealTypeInt(effectiveTime.hour * 60 + effectiveTime.minute)
            val durationMinutes = if (mealType == MealType.MEAL_TYPE_SNACK) snackDurationMinutes
                                  else mealDurationMinutes
            val startTime = endTime.minusSeconds(durationMinutes * 60L)
            val zoneOffset = zone.rules.getOffset(endTime)

            val nutrients = buildNutrientVector()
            fun mass(v: Double) = if (v > 0.0) Mass.grams(v) else null

            val record = NutritionRecord(
                name = trimmedName,
                startTime = startTime,
                startZoneOffset = zoneOffset,
                endTime = endTime,
                endZoneOffset = zoneOffset,
                mealType = mealType,
                energy = if (nutrients.calories > 0.0) Energy.calories(nutrients.calories) else null,
                protein = mass(nutrients.proteinGrams),
                totalCarbohydrate = mass(nutrients.carbsGrams),
                totalFat = mass(nutrients.fatGrams),
                saturatedFat = mass(nutrients.saturatedFatGrams),
                polyunsaturatedFat = mass(nutrients.polyunsaturatedFatGrams),
                monounsaturatedFat = mass(nutrients.monounsaturatedFatGrams),
                transFat = mass(nutrients.transFatGrams),
                dietaryFiber = mass(nutrients.fiberGrams),
                sugar = mass(nutrients.sugarGrams),
                sodium = mass(nutrients.sodiumGrams),
                cholesterol = mass(nutrients.cholesterolGrams),
                potassium = mass(nutrients.potassiumGrams),
                calcium = mass(nutrients.calciumGrams),
                iron = mass(nutrients.ironGrams),
                magnesium = mass(nutrients.magnesiumGrams),
                phosphorus = mass(nutrients.phosphorusGrams),
                zinc = mass(nutrients.zincGrams),
                vitaminA = mass(nutrients.vitaminAGrams),
                vitaminC = mass(nutrients.vitaminCGrams),
                vitaminD = mass(nutrients.vitaminDGrams),
                vitaminE = mass(nutrients.vitaminEGrams),
                vitaminK = mass(nutrients.vitaminKGrams),
                vitaminB6 = mass(nutrients.vitaminB6Grams),
                vitaminB12 = mass(nutrients.vitaminB12Grams),
                thiamin = mass(nutrients.thiaminGrams),
                riboflavin = mass(nutrients.riboflavinGrams),
                niacin = mass(nutrients.niacinGrams),
                folate = mass(nutrients.folateGrams),
                caffeine = mass(nutrients.caffeineGrams),
            )

            runCatching {
                healthConnectManager.healthConnectClient.insertRecords(listOf(record))
            }.onSuccess {
                // Save to recents using a FoodCandidate with per-100g normalization
                val scale = if (grams > 0.0) 100.0 / grams else 1.0
                val candidate = FoodCandidate(
                    id = "manual-${trimmedName.lowercase().replace(" ", "-")}",
                    name = trimmedName,
                    servingInfo = null,
                    baseAmount = NutritionAmount(100.0, QuantityUnit.GRAM),
                    nutrientsPer100g = NutrientVector(
                        calories = nutrients.calories * scale,
                        proteinGrams = nutrients.proteinGrams * scale,
                        carbsGrams = nutrients.carbsGrams * scale,
                        fatGrams = nutrients.fatGrams * scale,
                        saturatedFatGrams = nutrients.saturatedFatGrams * scale,
                        polyunsaturatedFatGrams = nutrients.polyunsaturatedFatGrams * scale,
                        monounsaturatedFatGrams = nutrients.monounsaturatedFatGrams * scale,
                        transFatGrams = nutrients.transFatGrams * scale,
                        fiberGrams = nutrients.fiberGrams * scale,
                        sugarGrams = nutrients.sugarGrams * scale,
                        sodiumGrams = nutrients.sodiumGrams * scale,
                        cholesterolGrams = nutrients.cholesterolGrams * scale,
                        potassiumGrams = nutrients.potassiumGrams * scale,
                        calciumGrams = nutrients.calciumGrams * scale,
                        ironGrams = nutrients.ironGrams * scale,
                        magnesiumGrams = nutrients.magnesiumGrams * scale,
                        phosphorusGrams = nutrients.phosphorusGrams * scale,
                        zincGrams = nutrients.zincGrams * scale,
                        vitaminAGrams = nutrients.vitaminAGrams * scale,
                        vitaminCGrams = nutrients.vitaminCGrams * scale,
                        vitaminDGrams = nutrients.vitaminDGrams * scale,
                        vitaminEGrams = nutrients.vitaminEGrams * scale,
                        vitaminKGrams = nutrients.vitaminKGrams * scale,
                        vitaminB6Grams = nutrients.vitaminB6Grams * scale,
                        vitaminB12Grams = nutrients.vitaminB12Grams * scale,
                        thiaminGrams = nutrients.thiaminGrams * scale,
                        riboflavinGrams = nutrients.riboflavinGrams * scale,
                        niacinGrams = nutrients.niacinGrams * scale,
                        folateGrams = nutrients.folateGrams * scale,
                        caffeineGrams = nutrients.caffeineGrams * scale,
                    )
                )
                recentsRepo.saveRecent(candidate, NutritionAmount(grams, QuantityUnit.GRAM), "manual")
                navController.popBackStack()
            }.onFailure {
                Toast.makeText(context, "Could not log food: ${it.message}", Toast.LENGTH_SHORT).show()
            }
            isLogging = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log manually") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Food name
            OutlinedTextField(
                value = foodName,
                onValueChange = { foodName = it },
                label = { Text("Food name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Amount
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount (${amountUnitLabel(unitSystem)})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Eaten time (if enabled)
            if (askEatenTime) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Eaten at: ${eatenTime.format(timeFormatter)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    AssistChip(
                        onClick = { showEatenTimePicker = true },
                        label = { Text(mealLabel(eatenTime.hour * 60 + eatenTime.minute)) }
                    )
                }
            }

            HorizontalDivider()

            Text(
                "Nutrition values for the amount entered above:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (enabledNutrients.isEmpty()) {
                Text(
                    "No extra nutrients enabled. Configure in Settings → Nutrition → Extra Nutrients.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Nutrient fields, shown in configured order
            enabledNutrients.forEach { key ->
                val label = NutrientDefaults.displayName[key] ?: key.name
                val unit = NutrientDefaults.displayUnit[key] ?: ""
                OutlinedTextField(
                    value = nutrientValues[key.name] ?: "",
                    onValueChange = { nutrientValues[key.name] = it },
                    label = { Text("$label ($unit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { logFood() },
                enabled = !isLogging && foodName.isNotBlank() &&
                    parseAmountToGrams(amountText, unitSystem) != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isLogging) "Logging…" else "Log food")
            }
        }
    }

    if (showEatenTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = eatenTime.hour,
            initialMinute = eatenTime.minute,
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { showEatenTimePicker = false },
            title = { Text("When did you eat this?") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    eatenTime = LocalTime.of(pickerState.hour, pickerState.minute)
                    showEatenTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showEatenTimePicker = false }) { Text("Cancel") }
            }
        )
    }
}
