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
import codegito.xyz.healthconnector.nutrition.domain.NutrientVector
import codegito.xyz.healthconnector.nutrition.domain.NutritionAmount
import codegito.xyz.healthconnector.nutrition.domain.QuantityUnit
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
    navController: NavController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val rangeDays by userPreferencesRepository.nutritionPastDateRangeDays.collectAsState(initial = 7)

    var nutritionByDay by remember { mutableStateOf<Map<LocalDate, List<NutritionRecord>>>(emptyMap()) }
    var indexRecordCount by remember { mutableIntStateOf(-1) }

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
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) loadData()
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

    // Eaten time state — defaults to now (or noon for past dates)
    val defaultEatenTime = remember(date) {
        if (date == LocalDate.now()) LocalTime.now() else LocalTime.NOON
    }
    var eatenTime by remember { mutableStateOf(defaultEatenTime) }
    var showEatenTimePicker by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<FoodCandidate>>(emptyList()) }
    var selectedFood by remember { mutableStateOf<FoodCandidate?>(null) }
    var amountText by remember { mutableStateOf("100") }
    var isLogging by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }

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

        val record = NutritionRecord(
            name = candidate.name,
            startTime = startTime,
            startZoneOffset = zoneOffset,
            endTime = endTime,
            endZoneOffset = zoneOffset,
            mealType = mealType,
            energy = Energy.calories(candidate.nutrientsPerBase.calories * multiplier),
            protein = Mass.grams(candidate.nutrientsPerBase.proteinGrams * multiplier),
            totalCarbohydrate = Mass.grams(candidate.nutrientsPerBase.carbsGrams * multiplier),
            totalFat = Mass.grams(candidate.nutrientsPerBase.fatGrams * multiplier)
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
                        val grams = amountText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: candidate.baseAmount.value
                        val multiplier = if (candidate.baseAmount.value > 0.0) grams / candidate.baseAmount.value else 1.0
                        Text(
                            "${(baseNutrition.calories * multiplier).toInt()} kcal  " +
                            "P: ${(baseNutrition.proteinGrams * multiplier).toInt()}g  " +
                            "C: ${(baseNutrition.carbsGrams * multiplier).toInt()}g  " +
                            "F: ${(baseNutrition.fatGrams * multiplier).toInt()}g",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                            label = { Text("Amount (grams)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { selectedFood = null; amountText = "100" },
                                modifier = Modifier.weight(1f)
                            ) { Text("Cancel") }
                            Button(
                                onClick = {
                                    val g = amountText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return@Button
                                    scope.launch { logFood(candidate, g) }
                                },
                                enabled = !isLogging && amountText.toDoubleOrNull()?.let { it > 0.0 } == true,
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
                            selected = selectedFood?.id == candidate.id,
                            onClick = {
                                selectedFood = candidate
                                amountText = candidate.baseAmount.value.toInt().toString()
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
                            subtitle = "${lastAmount.value.toInt()} ${lastAmount.unit.name.lowercase()}",
                            selected = selectedFood?.id == candidate.id,
                            onClick = {
                                selectedFood = candidate
                                amountText = lastAmount.value.toInt().toString()
                                eatenTime = defaultEatenTime
                            }
                        )
                    }
                }

                // Enter manually
                item {
                    OutlinedButton(
                        onClick = { showManualEntry = true },
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

    // Manual entry dialog
    if (showManualEntry) {
        ManualFoodEntryDialog(
            onDismiss = { showManualEntry = false },
            onLog = { candidate, grams ->
                showManualEntry = false
                scope.launch { logFood(candidate, grams) }
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

@Composable
private fun ManualFoodEntryDialog(
    onDismiss: () -> Unit,
    onLog: (FoodCandidate, Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("100") }
    var calText by remember { mutableStateOf("") }
    var proteinText by remember { mutableStateOf("") }
    var carbsText by remember { mutableStateOf("") }
    var fatText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Food name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (g)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Nutrition values for the amount above:",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = calText,
                        onValueChange = { calText = it },
                        label = { Text("kcal") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = proteinText,
                        onValueChange = { proteinText = it },
                        label = { Text("P (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = carbsText,
                        onValueChange = { carbsText = it },
                        label = { Text("C (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = fatText,
                        onValueChange = { fatText = it },
                        label = { Text("F (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedName = name.trim()
                    val grams = amountText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return@TextButton
                    if (trimmedName.isEmpty()) return@TextButton
                    // Scale to per-100g for storage in recents
                    val scale = if (grams > 0.0) 100.0 / grams else 1.0
                    val cal = (calText.toDoubleOrNull() ?: 0.0) * scale
                    val protein = (proteinText.toDoubleOrNull() ?: 0.0) * scale
                    val carbs = (carbsText.toDoubleOrNull() ?: 0.0) * scale
                    val fat = (fatText.toDoubleOrNull() ?: 0.0) * scale
                    val candidate = FoodCandidate(
                        id = "manual-${trimmedName.lowercase().replace(" ", "-")}",
                        name = trimmedName,
                        baseAmount = NutritionAmount(100.0, QuantityUnit.GRAM),
                        nutrientsPerBase = NutrientVector(
                            calories = cal,
                            proteinGrams = protein,
                            carbsGrams = carbs,
                            fatGrams = fat
                        )
                    )
                    onLog(candidate, grams)
                },
                enabled = name.isNotBlank() && amountText.toDoubleOrNull()?.let { it > 0.0 } == true
            ) { Text("Log") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
