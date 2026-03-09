package codegito.xyz.healthconnector.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.navigation.NavController
import codegito.xyz.healthconnector.HealthConnectManager
import codegito.xyz.healthconnector.Screen
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.TimeRange
import codegito.xyz.healthconnector.data.db.AppDatabase
import codegito.xyz.healthconnector.nutrition.data.NutritionIndexBuildManager
import codegito.xyz.healthconnector.nutrition.data.NutritionRecentsRepository
import codegito.xyz.healthconnector.nutrition.domain.FoodCandidate
import codegito.xyz.healthconnector.nutrition.domain.NutrientDefaults
import codegito.xyz.healthconnector.nutrition.domain.NutrientKey
import codegito.xyz.healthconnector.nutrition.domain.NutrientVector
import codegito.xyz.healthconnector.nutrition.domain.NutritionAmount
import codegito.xyz.healthconnector.nutrition.domain.NutritionUnitSystem
import codegito.xyz.healthconnector.nutrition.domain.QuantityUnit
import codegito.xyz.healthconnector.nutrition.domain.amountUnitLabel
import codegito.xyz.healthconnector.nutrition.domain.formatAmount
import codegito.xyz.healthconnector.nutrition.domain.gramsToAmountText
import codegito.xyz.healthconnector.nutrition.domain.parseAmountToGrams
import codegito.xyz.healthconnector.nutrition.provider.AssetNutritionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────
// NutritionHomeScreen – date list with daily summaries
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionHomeScreen(
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    nutritionIndexBuildManager: NutritionIndexBuildManager,
    navController: NavController,
    onNavigateToPermissions: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val rangeDays by userPreferencesRepository.nutritionPastDateRangeDays.collectAsState(initial = 7)

    var nutritionByDay by remember { mutableStateOf<Map<LocalDate, List<NutritionRecord>>>(emptyMap()) }
    var indexRecordCount by remember { mutableIntStateOf(-1) }
    var hasNutritionWrite by remember { mutableStateOf<Boolean?>(null) }

    fun loadData() {
        scope.launch {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val startDate = today.minusDays(rangeDays.toLong())
            val dayStart = startDate.atStartOfDay(zone).toInstant()
            val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
            val request = ReadRecordsRequest(
                recordType = NutritionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(dayStart, dayEnd)
            )
            val records = runCatching {
                healthConnectManager.healthConnectClient.readRecords(request).records
            }.onFailure {
                Toast.makeText(context, "Unable to load nutrition entries", Toast.LENGTH_SHORT).show()
            }.getOrDefault(emptyList())

            nutritionByDay = records.groupBy { record ->
                LocalDateTime.ofInstant(record.endTime, zone).toLocalDate()
            }

            indexRecordCount = withContext(Dispatchers.IO) {
                nutritionIndexBuildManager.indexRecordCount()
            }
        }
    }

    // Reload on resume via lifecycle
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                loadData()
                scope.launch { hasNutritionWrite = healthConnectManager.hasNutritionWritePermission() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Food") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                navController.navigate(Screen.LogFood.route(LocalDate.now()))
            }) {
                Icon(Icons.Default.Add, contentDescription = "Log food")
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding)
        ) {
            // Write permission banner
            if (hasNutritionWrite == false) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Nutrition write permission not granted",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Food logs cannot be saved until Health Connect write access is granted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            if (onNavigateToPermissions != null) {
                                TextButton(onClick = onNavigateToPermissions) {
                                    Text("Open Permissions", color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
            }

            // Dataset banner – hide once index has > 10 foods
            if (indexRecordCount in 0..10) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate("settings") }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "No food database loaded",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "Tap to go to Settings → Nutrition to upload a dataset ZIP.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            val today = LocalDate.now()
            val days = (0 until rangeDays).map { today.minusDays(it.toLong()) }
            items(days) { date ->
                NutritionDayCard(
                    date = date,
                    entries = nutritionByDay[date] ?: emptyList(),
                    onClick = { navController.navigate(Screen.NutritionDay.route(date)) }
                )
            }
        }
    }
}

@Composable
private fun NutritionDayCard(
    date: LocalDate,
    entries: List<NutritionRecord>,
    onClick: () -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
    val isToday = date == LocalDate.now()
    val totalCal = entries.sumOf { it.energy?.inCalories ?: 0.0 }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isToday) "Today, ${date.format(formatter)}" else date.format(formatter),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            if (entries.isEmpty()) {
                Text(
                    "No entries",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                Text(
                    "${totalCal.toInt()} kcal · ${entries.size} ${if (entries.size == 1) "entry" else "entries"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NutritionDayDetailScreen – full day with entry list
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionDayDetailScreen(
    date: LocalDate,
    healthConnectManager: HealthConnectManager,
    navController: NavController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()

    var entries by remember { mutableStateOf<List<NutritionRecord>>(emptyList()) }
    var entryToDelete by remember { mutableStateOf<NutritionRecord?>(null) }

    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    fun loadEntries() {
        scope.launch {
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            entries = runCatching {
                healthConnectManager.healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        recordType = NutritionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(dayStart, dayEnd)
                    )
                ).records
            }.onFailure {
                Toast.makeText(context, "Unable to load entries", Toast.LENGTH_SHORT).show()
            }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(date) { loadEntries() }

    val totalCal = entries.sumOf { it.energy?.inCalories ?: 0.0 }
    val totalProtein = entries.sumOf { it.protein?.inGrams ?: 0.0 }
    val totalCarbs = entries.sumOf { it.totalCarbohydrate?.inGrams ?: 0.0 }
    val totalFat = entries.sumOf { it.totalFat?.inGrams ?: 0.0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(date.format(formatter)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                navController.navigate(Screen.LogFood.route(date))
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add food")
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding)
        ) {
            // Summary card
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Daily summary", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${totalCal.toInt()} kcal",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("P: ${totalProtein.toInt()}g", style = MaterialTheme.typography.bodySmall)
                            Text("C: ${totalCarbs.toInt()}g", style = MaterialTheme.typography.bodySmall)
                            Text("F: ${totalFat.toInt()}g", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                item {
                    Text(
                        "No entries for this day. Tap + to log food.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(entries.sortedBy { it.endTime }) { record ->
                    val name = record.name ?: "Food entry"
                    val cal = record.energy?.inCalories ?: 0.0
                    val time = LocalDateTime.ofInstant(record.endTime, zone).format(timeFormatter)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { entryToDelete = record }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                                Text(time, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                            Text(
                                "${cal.toInt()} kcal",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation
    entryToDelete?.let { record ->
        val name = record.name ?: "this entry"
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text("Delete entry?") },
            text = { Text("Remove \"$name\" from Health Connect?") },
            confirmButton = {
                TextButton(onClick = {
                    entryToDelete = null
                    scope.launch {
                        runCatching {
                            healthConnectManager.healthConnectClient.deleteRecords(
                                NutritionRecord::class,
                                recordIdsList = listOf(record.metadata.id),
                                clientRecordIdsList = emptyList()
                            )
                        }.onSuccess { loadEntries() }
                            .onFailure { Toast.makeText(context, "Delete failed: ${it.message}", Toast.LENGTH_SHORT).show() }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LogFoodScreen – search + recents + manual entry
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogFoodScreen(
    date: LocalDate,
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    nutritionProvider: AssetNutritionProvider,
    navController: NavController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recentsRepo = remember(context) {
        NutritionRecentsRepository(AppDatabase.getDatabase(context).recentFoodDao())
    }
    val recents by recentsRepo.recents().collectAsState(initial = emptyList())

    // Time tracking prefs
    val askEatenTime by userPreferencesRepository.nutritionAskEatenTime.collectAsState(initial = false)
    val breakfastRange by userPreferencesRepository.nutritionBreakfastRange.collectAsState(initial = TimeRange.BREAKFAST)
    val lunchRange by userPreferencesRepository.nutritionLunchRange.collectAsState(initial = TimeRange.LUNCH)
    val dinnerRange by userPreferencesRepository.nutritionDinnerRange.collectAsState(initial = TimeRange.DINNER)
    val unitSystem by userPreferencesRepository.nutritionUnitSystem.collectAsState(initial = NutritionUnitSystem.US)
    val nutrientConfigList by userPreferencesRepository.nutrientConfig.collectAsState(initial = NutrientDefaults.defaultConfig())
    val enabledNutrients = remember(nutrientConfigList) {
        nutrientConfigList
            .filter { it.enabled }
            .mapNotNull { cfg -> runCatching { NutrientKey.valueOf(cfg.key) }.getOrNull() }
    }

    // Eaten time state — defaults to now (or noon for past dates)
    val defaultEatenTime = remember(date) {
        if (date == LocalDate.now()) LocalTime.now() else LocalTime.NOON
    }
    var eatenTime by remember { mutableStateOf(defaultEatenTime) }
    var showEatenTimePicker by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<FoodCandidate>>(emptyList()) }
    var selectedFood by remember { mutableStateOf<FoodCandidate?>(null) }
    var amountText by remember { mutableStateOf("") }
    var isLogging by remember { mutableStateOf(false) }

    // When a food is selected, default the amount text in the current unit system
    fun setDefaultAmount(candidate: FoodCandidate) {
        amountText = gramsToAmountText(candidate.baseAmount.value, unitSystem)
    }

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

    // Live search with debounce
    LaunchedEffect(query) {
        if (query.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        searchResults = withContext(Dispatchers.IO) {
            nutritionProvider.searchFoods(query.trim(), limit = 30)
        }
    }

    suspend fun logFood(candidate: FoodCandidate, grams: Double) {
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
        val multiplier = if (candidate.baseAmount.value > 0.0) grams / candidate.baseAmount.value else 1.0
        val n = candidate.nutrientsPerBase
        fun mass(v: Double) = if (v * multiplier > 0.0) Mass.grams(v * multiplier) else null

        val record = NutritionRecord(
            name = candidate.name,
            startTime = startTime,
            startZoneOffset = zoneOffset,
            endTime = endTime,
            endZoneOffset = zoneOffset,
            mealType = mealType,
            energy = if (n.calories * multiplier > 0.0) Energy.calories(n.calories * multiplier) else null,
            protein = mass(n.proteinGrams),
            totalCarbohydrate = mass(n.carbsGrams),
            totalFat = mass(n.fatGrams),
            saturatedFat = mass(n.saturatedFatGrams),
            polyunsaturatedFat = mass(n.polyunsaturatedFatGrams),
            monounsaturatedFat = mass(n.monounsaturatedFatGrams),
            transFat = mass(n.transFatGrams),
            dietaryFiber = mass(n.fiberGrams),
            sugar = mass(n.sugarGrams),
            sodium = mass(n.sodiumGrams),
            cholesterol = mass(n.cholesterolGrams),
            potassium = mass(n.potassiumGrams),
            calcium = mass(n.calciumGrams),
            iron = mass(n.ironGrams),
            magnesium = mass(n.magnesiumGrams),
            phosphorus = mass(n.phosphorusGrams),
            zinc = mass(n.zincGrams),
            vitaminA = mass(n.vitaminAGrams),
            vitaminC = mass(n.vitaminCGrams),
            vitaminD = mass(n.vitaminDGrams),
            vitaminE = mass(n.vitaminEGrams),
            vitaminK = mass(n.vitaminKGrams),
            vitaminB6 = mass(n.vitaminB6Grams),
            vitaminB12 = mass(n.vitaminB12Grams),
            thiamin = mass(n.thiaminGrams),
            riboflavin = mass(n.riboflavinGrams),
            niacin = mass(n.niacinGrams),
            folate = mass(n.folateGrams),
            caffeine = mass(n.caffeineGrams),
        )

        runCatching {
            healthConnectManager.healthConnectClient.insertRecords(listOf(record))
        }.onSuccess {
            recentsRepo.saveRecent(candidate, NutritionAmount(grams, QuantityUnit.GRAM), "search")
            navController.popBackStack()
        }.onFailure {
            Toast.makeText(context, "Could not log food: ${it.message}", Toast.LENGTH_SHORT).show()
        }
        isLogging = false
    }

    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log food") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            selectedFood?.let { candidate ->
                Surface(tonalElevation = 3.dp) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(candidate.name, style = MaterialTheme.typography.titleMedium)
                        val baseNutrition = candidate.nutrientsPerBase
                        val grams = parseAmountToGrams(amountText, unitSystem) ?: candidate.baseAmount.value
                        val multiplier = if (candidate.baseAmount.value > 0.0) grams / candidate.baseAmount.value else 1.0
                        // Show enabled nutrients in a brief summary
                        val summaryParts = enabledNutrients.mapNotNull { key ->
                            val displayVal = NutrientDefaults.getValueInDisplayUnit(baseNutrition, key) * multiplier
                            if (displayVal <= 0.0) return@mapNotNull null
                            val unit = NutrientDefaults.displayUnit[key] ?: ""
                            val short = NutrientDefaults.displayName[key]?.take(3) ?: key.name.take(3)
                            if (key == NutrientKey.CALORIES) "${displayVal.toInt()} kcal"
                            else "$short: ${if (displayVal < 10.0) "%.1f".format(displayVal) else displayVal.toInt().toString()}$unit"
                        }.take(5)
                        if (summaryParts.isNotEmpty()) {
                            Text(
                                summaryParts.joinToString("  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Eaten-time row — only shown when preference is enabled
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
                        OutlinedTextField(
                            value = amountText,
                            onValueChange = { amountText = it },
                            label = { Text("Amount (${amountUnitLabel(unitSystem)})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { selectedFood = null; amountText = "" },
                                modifier = Modifier.weight(1f)
                            ) { Text("Cancel") }
                            Button(
                                onClick = {
                                    val g = parseAmountToGrams(amountText, unitSystem) ?: return@Button
                                    scope.launch { logFood(candidate, g) }
                                },
                                enabled = !isLogging && parseAmountToGrams(amountText, unitSystem) != null,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (isLogging) "Logging…" else "Log") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Search bar
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; selectedFood = null },
                    label = { Text("Search foods") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Search results
            if (query.isNotBlank()) {
                if (searchResults.isEmpty()) {
                    item {
                        Text(
                            "No results found. Build a nutrition dataset in Settings first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    items(searchResults) { candidate ->
                        FoodCandidateRow(
                            candidate = candidate,
                            subtitle = formatAmount(candidate.baseAmount.value, unitSystem),
                            selected = selectedFood?.id == candidate.id,
                            onClick = {
                                selectedFood = candidate
                                setDefaultAmount(candidate)
                                eatenTime = defaultEatenTime
                            }
                        )
                    }
                }
            } else {
                // Recents
                if (recents.isNotEmpty()) {
                    item {
                        Text(
                            "Recent",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(recents) { (candidate, lastAmount) ->
                        FoodCandidateRow(
                            candidate = candidate,
                            subtitle = formatAmount(lastAmount.value, unitSystem),
                            selected = selectedFood?.id == candidate.id,
                            onClick = {
                                selectedFood = candidate
                                amountText = gramsToAmountText(lastAmount.value, unitSystem)
                                eatenTime = defaultEatenTime
                            }
                        )
                    }
                }

                // Enter manually
                item {
                    OutlinedButton(
                        onClick = { navController.navigate(Screen.ManualFoodEntry.route(date)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Enter manually") }
                }
            }
        }
    }

    // Eaten-time picker dialog
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

@Composable
private fun FoodCandidateRow(
    candidate: FoodCandidate,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(candidate.name, style = MaterialTheme.typography.bodyMedium)
                val sub = subtitle ?: "${candidate.baseAmount.value.toInt()}g"
                Text(
                    "$sub · ${candidate.nutrientsPerBase.calories.toInt()} kcal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

