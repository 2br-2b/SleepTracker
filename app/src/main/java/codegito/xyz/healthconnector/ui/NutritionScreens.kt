package codegito.xyz.healthconnector.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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
import codegito.xyz.healthconnector.data.db.AppDatabase
import codegito.xyz.healthconnector.data.model.TimeRange
import androidx.work.WorkInfo
import codegito.xyz.healthconnector.nutrition.data.NutritionIndexBuildManager
import codegito.xyz.healthconnector.nutrition.data.NutritionIndexWorker
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
import codegito.xyz.healthconnector.nutrition.provider.NutritionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class AiChatMessage(val fromUser: Boolean, val text: String)

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
    val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)
    val aiFeaturesDisabled by userPreferencesRepository.aiFeaturesDisabled.collectAsState(initial = false)
    val effectiveGlobalAiEnabled by userPreferencesRepository.effectiveGlobalAiEnabled.collectAsState(initial = true)

    var nutritionByDay by remember { mutableStateOf<Map<LocalDate, List<NutritionRecord>>>(emptyMap()) }
    var indexRecordCount by remember { mutableIntStateOf(-1) }
    var hasNutritionWrite by remember { mutableStateOf<Boolean?>(null) }
    var hasNutritionRead by remember { mutableStateOf<Boolean?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Observe WorkManager for in-progress database build
    val homeWorkInfos by NutritionIndexWorker.observeInfo(context).collectAsState(initial = emptyList())
    val homeWorkInfo = homeWorkInfos.firstOrNull()
    val isDbBuilding = homeWorkInfo?.state == WorkInfo.State.RUNNING ||
            homeWorkInfo?.state == WorkInfo.State.ENQUEUED
    val homeDbProgress = homeWorkInfo?.progress?.getInt(NutritionIndexWorker.KEY_PROGRESS, 0) ?: 0
    val homeDbTotal = homeWorkInfo?.progress?.getInt(NutritionIndexWorker.KEY_TOTAL, -1) ?: -1
    val homeDbProgressText = if (homeDbTotal > 0 && homeDbTotal != homeDbProgress)
        "$homeDbProgress / $homeDbTotal" else "$homeDbProgress"

    fun loadData() {
        scope.launch {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val startDate = today.minusDays(rangeDays.toLong())
            // Extend range by 1 day each side to capture rollover entries
            val dayStart = startDate.minusDays(1).atStartOfDay(zone).toInstant()
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
                val dt = LocalDateTime.ofInstant(record.endTime, zone)
                // Apply rollover: entries before rolloverHour belong to the previous calendar day
                if (dt.hour < rolloverHour) dt.toLocalDate().minusDays(1) else dt.toLocalDate()
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
                scope.launch {
                    hasNutritionWrite = healthConnectManager.hasNutritionWritePermission()
                    hasNutritionRead = healthConnectManager.hasNutritionReadPermission()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Food") }) },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!aiFeaturesDisabled && effectiveGlobalAiEnabled) {
                    FloatingActionButton(onClick = {
                        navController.navigate(Screen.LogFood.route(LocalDate.now(), ai = true))
                    }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "AI log food")
                    }
                }
                FloatingActionButton(onClick = {
                    navController.navigate(Screen.LogFood.route(LocalDate.now()))
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Log food")
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch {
                    hasNutritionWrite = healthConnectManager.hasNutritionWritePermission()
                    loadData()
                    isRefreshing = false
                }
            },
            modifier = Modifier.padding(padding)
        ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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

            // Dataset banner – show while building or when empty (< 10 foods)
            if (isDbBuilding || indexRecordCount in 0..10) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!isDbBuilding)
                                    navController.navigate("nutrition_settings?highlight=dataset")
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isDbBuilding) {
                                Text(
                                    "Building food database…",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    "Unzipping dataset…",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Text(
                                    "No food database loaded",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "Tap to open Nutrition Settings and build the food database.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
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
                    readPermissionGranted = hasNutritionRead != false,
                    onGrantReadPermission = onNavigateToPermissions,
                    onClick = { navController.navigate(Screen.NutritionDay.route(date)) }
                )
            }

            item { NutritionAttributionFooter() }
        }
        } // end PullToRefreshBox
    }
}

@Composable
private fun NutritionDayCard(
    date: LocalDate,
    entries: List<NutritionRecord>,
    readPermissionGranted: Boolean = true,
    onGrantReadPermission: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
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
            if (!readPermissionGranted) {
                Text(
                    "-- kcal",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    "To view entered records, grant permission to read nutrition data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onGrantReadPermission != null) {
                        TextButton(onClick = onGrantReadPermission) { Text("Grant read permission") }
                    }
                    TextButton(onClick = {
                        val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            )
                        }
                    }) { Text("View in Health Connect") }
                }
            } else if (entries.isEmpty()) {
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
    navController: NavController,
    onNavigateToPermissions: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val userPreferencesRepository = UserPreferencesRepository.getInstance(context)
    val aiFeaturesDisabled by userPreferencesRepository.aiFeaturesDisabled.collectAsState(initial = false)
    val effectiveGlobalAiEnabled by userPreferencesRepository.effectiveGlobalAiEnabled.collectAsState(initial = true)

    var entries by remember { mutableStateOf<List<NutritionRecord>>(emptyList()) }
    var entryToDelete by remember { mutableStateOf<NutritionRecord?>(null) }
    var hasNutritionRead by remember { mutableStateOf<Boolean?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")

    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    fun loadEntries() {
        scope.launch {
            hasNutritionRead = healthConnectManager.hasNutritionReadPermission()
            if (hasNutritionRead == true) {
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
    }

    LaunchedEffect(date) { loadEntries() }

    val totalCal = entries.sumOf { it.energy?.inCalories ?: 0.0 }
    val totalProtein = entries.sumOf { it.protein?.inGrams ?: 0.0 }
    val totalCarbs = entries.sumOf { it.totalCarbohydrate?.inGrams ?: 0.0 }
    val totalFat = entries.sumOf { it.totalFat?.inGrams ?: 0.0 }

    val readGranted = hasNutritionRead != false

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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!aiFeaturesDisabled && effectiveGlobalAiEnabled) {
                    FloatingActionButton(onClick = {
                        navController.navigate(Screen.LogFood.route(date, ai = true))
                    }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "AI add food")
                    }
                }
                FloatingActionButton(onClick = {
                    navController.navigate(Screen.LogFood.route(date))
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add food")
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch { loadEntries(); isRefreshing = false }
            },
            modifier = Modifier.padding(padding)
        ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Summary card
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Daily summary", style = MaterialTheme.typography.titleMedium)
                        if (!readGranted) {
                            Text(
                                "-- kcal",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                "To view entered records, grant permission to read nutrition data.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (onNavigateToPermissions != null) {
                                    TextButton(onClick = onNavigateToPermissions) { Text("Grant read permission") }
                                }
                                TextButton(onClick = {
                                    val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                                    if (intent.resolveActivity(context.packageManager) != null) {
                                        context.startActivity(intent)
                                    } else {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", context.packageName, null)
                                            }
                                        )
                                    }
                                }) { Text("View in Health Connect") }
                            }
                        } else {
                            Text(
                                "${totalCal.toInt()} kcal",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Protein: ${totalProtein.toInt()}g", style = MaterialTheme.typography.bodySmall)
                                Text("Carbs: ${totalCarbs.toInt()}g", style = MaterialTheme.typography.bodySmall)
                                Text("Fat: ${totalFat.toInt()}g", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            if (!readGranted) {
                // No entry list when read not granted - summary card covers it
            } else if (entries.isEmpty()) {
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

            item { NutritionAttributionFooter() }
        }
        } // end PullToRefreshBox
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
    nutritionProvider: NutritionProvider,
    navController: NavController,
    startInAiMode: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = LocalClipboardManager.current

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
    val applyFilterToSearch by userPreferencesRepository.nutritionApplyNutrientFilterToSearch.collectAsState(initial = false)
    // Use the full ordered + enabled config list for display
    val enabledNutrients = remember(nutrientConfigList) {
        nutrientConfigList
            .filter { it.enabled }
            .mapNotNull { cfg -> runCatching { NutrientKey.valueOf(cfg.key) }.getOrNull() }
    }
    val enabledNutrientSet = remember(enabledNutrients) { enabledNutrients.toSet() }

    // Eaten time state — defaults to now (or noon for past dates)
    val defaultEatenTime = remember(date) {
        if (date == LocalDate.now()) LocalTime.now() else LocalTime.NOON
    }
    var eatenTime by remember { mutableStateOf(defaultEatenTime) }
    var showEatenTimePicker by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    var showAiChat by remember { mutableStateOf(startInAiMode) }
    var aiPrompt by remember { mutableStateOf("") }
    var askFollowupQuestions by remember { mutableStateOf(true) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiStatus by remember { mutableStateOf<String?>(null) }
    var followupRemaining by remember { mutableIntStateOf(1) }
    val aiMessages = remember { mutableStateListOf<AiChatMessage>() }
    val developerModeEnabled by userPreferencesRepository.developerModeEnabled.collectAsState(initial = false)
    var searchResults by remember { mutableStateOf<List<FoodCandidate>>(emptyList()) }
    var selectedFood by remember { mutableStateOf<FoodCandidate?>(null) }
    var amountText by remember { mutableStateOf(TextFieldValue("")) }
    // Serving mode: true = show "How many [commonUnit]?" input; false = raw gram/oz input
    var servingInputMode by remember { mutableStateOf(false) }
    var servingAmountText by remember { mutableStateOf("1.0") }
    var isLogging by remember { mutableStateOf(false) }

    // Observe WorkManager for in-progress database build
    val workInfos by NutritionIndexWorker.observeInfo(context).collectAsState(initial = emptyList())
    val buildWorkInfo = workInfos.firstOrNull()
    val isDbBuilding = buildWorkInfo?.state == WorkInfo.State.RUNNING ||
            buildWorkInfo?.state == WorkInfo.State.ENQUEUED
    val dbBuildProgress = buildWorkInfo?.progress?.getInt(NutritionIndexWorker.KEY_PROGRESS, 0) ?: 0
    val dbBuildTotal = buildWorkInfo?.progress?.getInt(NutritionIndexWorker.KEY_TOTAL, -1) ?: -1
    val dbBuildProgressText = if (dbBuildTotal > 0 && dbBuildTotal != dbBuildProgress)
        "$dbBuildProgress / $dbBuildTotal" else "$dbBuildProgress"

    // When a food is selected, set up the appropriate amount input mode.
    fun setDefaultAmount(candidate: FoodCandidate) {
        val si = candidate.servingInfo
        if (si?.commonUnit != null) {
            servingInputMode = true
            servingAmountText = "%.1f".format(si.commonQuantity ?: 1.0)
        } else {
            servingInputMode = false
            val text = gramsToAmountText(candidate.baseAmount.value, unitSystem)
            amountText = TextFieldValue(text, selection = TextRange(0, text.length))
        }
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

    fun appendAiTrace(section: String, body: String) {
        if (!developerModeEnabled) return
        aiMessages += AiChatMessage(
            fromUser = false,
            text = "[$section]\n${body.trim()}\n- - -"
        )
    }

    fun aiTranscriptText(): String = aiMessages.joinToString("\n- - -\n") { msg ->
        (if (msg.fromUser) "You" else "AI") + ": " + msg.text
    }

    LaunchedEffect(Unit) {
        followupRemaining = userPreferencesRepository.aiFollowupDefaultCount.first().coerceIn(0, 5)
        askFollowupQuestions = followupRemaining > 0
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

    suspend fun logFood(candidate: FoodCandidate, grams: Double, navigateBack: Boolean = true) {
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
        // Returns null for optional micronutrients when the filter is active and they're not enabled
        fun massFiltered(v: Double, key: NutrientKey): Mass? {
            if (applyFilterToSearch && key !in NutrientDefaults.nutritionalFactsKeys && key !in enabledNutrientSet) return null
            return mass(v)
        }

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
            polyunsaturatedFat = massFiltered(n.polyunsaturatedFatGrams, NutrientKey.POLYUNSATURATED_FAT),
            monounsaturatedFat = massFiltered(n.monounsaturatedFatGrams, NutrientKey.MONOUNSATURATED_FAT),
            transFat = mass(n.transFatGrams),
            dietaryFiber = mass(n.fiberGrams),
            sugar = mass(n.sugarGrams),
            sodium = mass(n.sodiumGrams),
            cholesterol = mass(n.cholesterolGrams),
            potassium = massFiltered(n.potassiumGrams, NutrientKey.POTASSIUM),
            calcium = massFiltered(n.calciumGrams, NutrientKey.CALCIUM),
            iron = massFiltered(n.ironGrams, NutrientKey.IRON),
            magnesium = massFiltered(n.magnesiumGrams, NutrientKey.MAGNESIUM),
            phosphorus = massFiltered(n.phosphorusGrams, NutrientKey.PHOSPHORUS),
            zinc = massFiltered(n.zincGrams, NutrientKey.ZINC),
            vitaminA = massFiltered(n.vitaminAGrams, NutrientKey.VITAMIN_A),
            vitaminC = massFiltered(n.vitaminCGrams, NutrientKey.VITAMIN_C),
            vitaminD = massFiltered(n.vitaminDGrams, NutrientKey.VITAMIN_D),
            vitaminE = massFiltered(n.vitaminEGrams, NutrientKey.VITAMIN_E),
            vitaminK = massFiltered(n.vitaminKGrams, NutrientKey.VITAMIN_K),
            vitaminB6 = massFiltered(n.vitaminB6Grams, NutrientKey.VITAMIN_B6),
            vitaminB12 = massFiltered(n.vitaminB12Grams, NutrientKey.VITAMIN_B12),
            thiamin = massFiltered(n.thiaminGrams, NutrientKey.THIAMIN),
            riboflavin = massFiltered(n.riboflavinGrams, NutrientKey.RIBOFLAVIN),
            niacin = massFiltered(n.niacinGrams, NutrientKey.NIACIN),
            folate = massFiltered(n.folateGrams, NutrientKey.FOLATE),
            caffeine = massFiltered(n.caffeineGrams, NutrientKey.CAFFEINE),
        )

        runCatching {
            healthConnectManager.healthConnectClient.insertRecords(listOf(record))
        }.onSuccess {
            recentsRepo.saveRecent(candidate, NutritionAmount(grams, QuantityUnit.GRAM), "search")
            if (navigateBack) navController.popBackStack()
        }.onFailure {
            Toast.makeText(context, "Could not log food: ${it.message}", Toast.LENGTH_SHORT).show()
        }
        isLogging = false
    }


    suspend fun handleAiFoodLogPrompt(prompt: String, allowFollowup: Boolean) {
        data class ParsedMention(val quantity: Double, val unit: String?, val foodPhrase: String)
        data class ModelDecision(
            val candidateIndex: Int,
            val quantity: Double?,
            val unit: String?,
            val multiplier: Double?
        )

        // ── Read AI config upfront ─────────────────────────────────────────────
        val aiEnabled = userPreferencesRepository.effectiveGlobalAiEnabled.first()
        val provider = userPreferencesRepository.aiProvider.first()
        val baseUrl = userPreferencesRepository.aiBaseUrl.first().trim()
        val apiKey = userPreferencesRepository.aiApiKey.first().trim()
        val model = userPreferencesRepository.aiModel.first().ifBlank { provider.defaultModel }
        val baseSystemPrompt = userPreferencesRepository.aiBaseSystemPrompt.first().trim()
        val userSystemPrompt = userPreferencesRepository.aiSystemPrompt.first().trim()
        val decisionTemplate = userPreferencesRepository.aiDecisionPromptTemplate.first().ifBlank {
            UserPreferencesRepository.DEFAULT_AI_DECISION_PROMPT_TEMPLATE
        }
        val repairTemplate = userPreferencesRepository.aiRepairPromptTemplate.first().ifBlank {
            UserPreferencesRepository.DEFAULT_AI_REPAIR_PROMPT_TEMPLATE
        }
        val reasoningEffort = userPreferencesRepository.aiReasoningEffort.first()
        val combinedSystemPrompt = listOf(baseSystemPrompt, userSystemPrompt)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        // ── Shared HTTP helper ──────────────────────────────────────────────────
        suspend fun callModel(userText: String, systemPrompt: String = combinedSystemPrompt): String? {
            if (developerModeEnabled) {
                if (systemPrompt.isNotBlank()) {
                    aiMessages += AiChatMessage(fromUser = false, text = "[MODEL SYSTEM]\n$systemPrompt\n- - -")
                }
                aiMessages += AiChatMessage(fromUser = false, text = "[MODEL REQUEST]\n$userText\n- - -")
            }
            val messages = org.json.JSONArray().apply {
                if (systemPrompt.isNotBlank()) put(org.json.JSONObject().put("role", "system").put("content", systemPrompt))
                put(org.json.JSONObject().put("role", "user").put("content", userText))
            }
            val payload = org.json.JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.1)
                if (reasoningEffort != "none") put("reasoning_effort", reasoningEffort)
            }.toString()

            // Blocking IO must run off the main thread
            val httpResult = withContext(Dispatchers.IO) {
                runCatching {
                    val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 30000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    conn.outputStream.use { it.write(payload.toByteArray()) }
                    val code = conn.responseCode
                    val body = try {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } catch (_: Exception) {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    }
                    Pair(code, body)
                }
            }

            if (httpResult.isFailure) {
                val err = httpResult.exceptionOrNull()
                appendAiTrace("MODEL ERROR", "Network error: ${err?.javaClass?.simpleName}: ${err?.message}")
                return null
            }

            val (code, body) = httpResult.getOrThrow()
            if (developerModeEnabled) {
                aiMessages += AiChatMessage(fromUser = false, text = "[MODEL HTTP]\nstatus=$code\nbody=$body\n- - -")
            }
            if (code !in 200..299) {
                appendAiTrace("MODEL ERROR", "HTTP $code: ${body.take(300)}")
                return null
            }
            val content = runCatching {
                val root = org.json.JSONObject(body)
                root.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
            if (content.isFailure) {
                appendAiTrace("MODEL ERROR", "Failed to parse response JSON: ${content.exceptionOrNull()?.message}\nbody=${body.take(300)}")
                return null
            }
            val text = content.getOrThrow().replace("\n", " ").trim()
            if (developerModeEnabled) {
                aiMessages += AiChatMessage(fromUser = false, text = "[MODEL RESPONSE]\n${text.ifBlank { "(empty)" }}\n- - -")
            }
            return text.ifBlank { null }
        }

        fun parseMentionsFromJson(raw: String): List<ParsedMention> = runCatching {
            val s = raw.indexOf('[')
            val e = raw.lastIndexOf(']')
            if (s == -1 || e == -1 || e <= s) return@runCatching emptyList()
            val arr = org.json.JSONArray(raw.substring(s, e + 1))
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val food = obj.optString("foodPhrase").trim()
                if (food.isBlank()) null
                else ParsedMention(
                    quantity = obj.optDouble("quantity", 1.0).takeIf { !it.isNaN() } ?: 1.0,
                    unit = obj.optString("unit", "").takeIf { it.isNotBlank() },
                    foodPhrase = food
                )
            }
        }.getOrElse { emptyList() }

        // ── AI-powered mention parsing ──────────────────────────────────────────
        suspend fun aiParseMentions(input: String): List<ParsedMention> {
            val parsePrompt = """Parse the following food log entry into a JSON array of food items.

Input: "$input"

Return ONLY a JSON array. Each element must have exactly these keys:
- "foodPhrase": string — the food item as described, preserving all descriptors, preparation style, and brand names. Do NOT split compound dishes (e.g. "ham hot honey egg and cheese sandwich on a bagel" is ONE item).
- "quantity": number — the numeric amount (1.0 if not specified; treat "a"/"an" as 1.0)
- "unit": string or null — explicit unit of measure such as "cup", "oz", "slice" (null if count-based or unspecified)

Rules:
- "a"/"an" = quantity 1.0, never a unit or part of the food name
- Strip only conversational filler: "I had", "I ate", "I drank", "for breakfast/lunch/dinner/snack", restaurant attribution ("from X"), time references
- Preserve all food descriptors: "hot honey", "a la mode", "extra crispy", "spicy", etc.
- If multiple distinct foods are mentioned, emit one object per food
- JSON array only — no markdown, no prose"""
            val raw = callModel(parsePrompt, systemPrompt = "") ?: return emptyList()
            appendAiTrace("AI PARSE RAW", raw)
            return parseMentionsFromJson(raw)
        }

        // ── AI-powered decomposition ────────────────────────────────────────────
        suspend fun aiDecomposeMention(foodPhrase: String): List<ParsedMention> {
            val decomposePrompt = """The food item "$foodPhrase" was not found in the nutrition database.
Break it down into individual ingredients that would appear in a standard nutrition database.

Return ONLY a JSON array (max 6 items). Each element must have:
- "foodPhrase": string — simple, generic ingredient name (e.g. "egg", "ham", "bagel", "american cheese")
- "quantity": number — realistic amount of this ingredient (1.0 if uncertain)
- "unit": string or null — unit if applicable (e.g. "slice"), otherwise null

Rules:
- Use generic ingredient names, not brand names or full dish names
- Only include trackable food ingredients
- JSON array only — no markdown, no prose"""
            val raw = callModel(decomposePrompt, systemPrompt = "") ?: return emptyList()
            appendAiTrace("AI DECOMPOSE RAW", raw)
            return parseMentionsFromJson(raw)
        }

        fun tokenize(text: String): Set<String> = text
            .lowercase()
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 2 }
            .toSet()

        fun similarityScore(query: String, candidateName: String): Int {
            val qTokens = tokenize(query)
            val cTokens = tokenize(candidateName)
            if (qTokens.isEmpty() || cTokens.isEmpty()) return 0
            val overlap = qTokens.intersect(cTokens).size
            val startsWithBoost = if (candidateName.lowercase().startsWith(query.lowercase())) 2 else 0
            val containsBoost = if (candidateName.lowercase().contains(query.lowercase())) 1 else 0
            return overlap * 3 + startsWithBoost + containsBoost
        }

        suspend fun findCandidates(foodPhrase: String): List<FoodCandidate> {
            val queryVariants = linkedSetOf<String>()
            // Full phrase — covers branded product searches
            queryVariants += foodPhrase
            // After "of" (e.g. "cup of milk" → "milk")
            if (" of " in foodPhrase.lowercase()) queryVariants += foodPhrase.substringAfterLast(" of ").trim()
            // Strip measurement/serving words (keeps brand + food name)
            queryVariants += foodPhrase.replace(Regex("""\b(slices?|bags?|pieces?|cups?|oz|ounces?|servings?)\b""", RegexOption.IGNORE_CASE), "").trim()
            // Strip size/descriptor words
            queryVariants += foodPhrase.replace(Regex("""\b(extra|large|small|big|medium|fresh|hot)\b""", RegexOption.IGNORE_CASE), "").trim()
            // Without first word — brand names often lead, so this finds the generic food name
            val words = foodPhrase.trim().split(Regex("""\s+"""))
            if (words.size > 1) queryVariants += words.drop(1).joinToString(" ")
            // Last two words — often the core food noun (e.g. "Greek yogurt")
            if (words.size > 2) queryVariants += words.takeLast(2).joinToString(" ")
            // Each significant individual word — catches brand-only or descriptor-only matches
            words.filter { it.length >= 3 }.forEach { queryVariants += it }

            val scored = mutableMapOf<String, Pair<FoodCandidate, Int>>()
            val normalizedQueries = queryVariants.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotBlank() }
            appendAiTrace("TOOL QUERY PLAN", "Food phrase '$foodPhrase' -> queries: ${normalizedQueries.joinToString()} ")
            for (query in normalizedQueries) {
                aiStatus = "Searching nutrition DB for '$query'…"
                val results = nutritionProvider.searchFoods(query, limit = 20)
                appendAiTrace("TOOL QUERY", "query='$query' returned ${results.size} rows")
                for (candidate in results) {
                    val score = similarityScore(query, candidate.name)
                    val existing = scored[candidate.id]
                    if (existing == null || score > existing.second) {
                        scored[candidate.id] = candidate to score
                    }
                }
            }
            val ranked = scored.values.sortedByDescending { it.second }.map { it.first }.take(100)
            appendAiTrace(
                "TOOL QUERY RESULT",
                ranked.mapIndexed { idx, c -> "$idx) ${c.name}" }.joinToString("\n").ifBlank { "No candidates" }
            )
            return ranked
        }

        suspend fun askModelForDecision(mention: ParsedMention, candidates: List<FoodCandidate>, originalPrompt: String): Result<ModelDecision> {
            if (candidates.isEmpty()) return Result.failure(IllegalStateException("No candidates"))

            val candidateText = candidates.mapIndexed { idx, c ->
                val si = c.servingInfo
                val servingDesc = if (si != null) {
                    "serving: ${si.commonQuantity ?: 1.0} ${si.commonUnit ?: "serving"} ≈ ${"%.1f".format(si.gramsPerCommonUnit)}g"
                } else "serving: unknown"
                "[$idx] ${c.name}; $servingDesc; per100g: kcal=${c.nutrientsPer100g.calories}, carbs=${c.nutrientsPer100g.carbsGrams}g, protein=${c.nutrientsPer100g.proteinGrams}g, fat=${c.nutrientsPer100g.fatGrams}g"
            }.joinToString("\n")

            fun parseDecision(raw: String): ModelDecision? {
                val idx = Regex("""\"candidateIndex\"\s*:\s*(-?\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
                val qty = Regex("""\"quantity\"\s*:\s*([0-9]+(?:\.[0-9]+)?)""").find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                val unit = Regex("""\"unit\"\s*:\s*\"(.*?)\"""").find(raw)?.groupValues?.getOrNull(1)
                val mult = Regex("""\"multiplier\"\s*:\s*([0-9]+(?:\.[0-9]+)?)""").find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                return ModelDecision(candidateIndex = idx, quantity = qty, unit = unit?.takeIf { it.isNotBlank() }, multiplier = mult)
            }

            fun renderTemplate(template: String, values: Map<String, String>): String {
                var output = template
                values.forEach { (key, value) -> output = output.replace("{{$key}}", value) }
                return output
            }

            val basePrompt = renderTemplate(
                template = decisionTemplate,
                values = mapOf(
                    "originalInput" to originalPrompt,
                    "mention" to mention.foodPhrase,
                    "quantity" to mention.quantity.toString(),
                    "unit" to (mention.unit ?: "(none)"),
                    "candidates" to candidateText
                )
            )

            val first = callModel(basePrompt)
            val firstDecision = first?.let(::parseDecision)
            if (firstDecision != null) return Result.success(firstDecision)

            val repairPrompt = renderTemplate(
                template = repairTemplate,
                values = mapOf(
                    "error" to "Could not parse strict JSON keys candidateIndex/quantity/unit/multiplier.",
                    "previous_output" to (first ?: "(empty)")
                )
            )
            val second = callModel(repairPrompt)
            val secondDecision = second?.let(::parseDecision)
            if (secondDecision != null) return Result.success(secondDecision)

            return Result.failure(IllegalStateException("Model returned unparsable output twice"))
        }

        suspend fun logFallbackEstimate(mention: ParsedMention) {
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
            val durationMinutes = if (mealType == MealType.MEAL_TYPE_SNACK) snackDurationMinutes else mealDurationMinutes
            val startTime = endTime.minusSeconds(durationMinutes * 60L)
            val zoneOffset = zone.rules.getOffset(endTime)

            val estimatedCalories = (150.0 * mention.quantity).coerceAtLeast(50.0)
            val estimatedCarbsG = (18.0 * mention.quantity).coerceAtLeast(1.0)
            val estimatedFatG = (7.0 * mention.quantity).coerceAtLeast(0.5)
            val estimatedProteinG = (4.0 * mention.quantity).coerceAtLeast(0.5)
            val estimatedSodiumG = (0.35 * mention.quantity).coerceAtLeast(0.05)

            val record = NutritionRecord(
                name = "${mention.foodPhrase} (estimated)",
                startTime = startTime,
                startZoneOffset = zoneOffset,
                endTime = endTime,
                endZoneOffset = zoneOffset,
                mealType = mealType,
                energy = Energy.calories(estimatedCalories),
                totalCarbohydrate = Mass.grams(estimatedCarbsG),
                totalFat = Mass.grams(estimatedFatG),
                protein = Mass.grams(estimatedProteinG),
                sodium = Mass.grams(estimatedSodiumG)
            )
            healthConnectManager.healthConnectClient.insertRecords(listOf(record))
        }

        aiBusy = true
        aiMessages += AiChatMessage(fromUser = true, text = prompt)
        aiStatus = "Understanding your food log…"

        if (!aiEnabled || provider == codegito.xyz.healthconnector.data.model.AiProvider.ANTHROPIC ||
            provider == codegito.xyz.healthconnector.data.model.AiProvider.GEMINI ||
            baseUrl.isBlank() || (provider.requiresApiKey && apiKey.isBlank())
        ) {
            val err = when {
                !aiEnabled -> "AI is not enabled. Enable it in Network & AI Settings."
                provider == codegito.xyz.healthconnector.data.model.AiProvider.ANTHROPIC ||
                    provider == codegito.xyz.healthconnector.data.model.AiProvider.GEMINI ->
                    "Selected AI provider is not supported for this feature. Use an OpenAI-compatible provider."
                else -> "AI configuration is incomplete. Check Network & AI Settings."
            }
            aiStatus = err
            aiMessages += AiChatMessage(fromUser = false, text = err)
            aiBusy = false
            return
        }

        aiStatus = "Parsing your food log…"
        val mentions = aiParseMentions(prompt)
        appendAiTrace(
            section = "PARSE",
            body = buildString {
                appendLine("Input: $prompt")
                appendLine("Detected mentions (${mentions.size}):")
                mentions.forEachIndexed { idx, m -> appendLine("$idx) qty=${m.quantity}, unit=${m.unit ?: "(none)"}, food='${m.foodPhrase}'") }
            }
        )
        if (mentions.isEmpty()) {
            val msg = "Could not identify any food items in your message. Please try again."
            aiStatus = msg
            aiMessages += AiChatMessage(fromUser = false, text = msg)
            aiBusy = false
            return
        }

        var loggedCount = 0
        for (mention in mentions) {
            appendAiTrace("ITEM START", "mention='${mention.foodPhrase}', qty=${mention.quantity}, unit=${mention.unit ?: "(none)"}")
            val candidates = findCandidates(mention.foodPhrase)
            if (candidates.isNotEmpty()) {
                val decisionResult = askModelForDecision(mention, candidates, prompt)
                val decision = decisionResult.getOrNull()
                if (decision == null) {
                    val err = "AI response could not be parsed after retry. Please try again or adjust model settings."
                    aiStatus = err
                    aiMessages += AiChatMessage(fromUser = false, text = err)
                    aiBusy = false
                    return
                }

                val chosen = if (decision.candidateIndex in candidates.indices) candidates[decision.candidateIndex] else candidates.first()
                val qty = (decision.quantity ?: mention.quantity).coerceAtLeast(0.1)
                val unit = decision.unit ?: mention.unit
                val multiplier = (decision.multiplier ?: 1.0).coerceIn(0.5, 3.0)

                appendAiTrace(
                    "MODEL DECISION",
                    "candidateIndex=${decision.candidateIndex}, qty=${decision.quantity}, unit=${decision.unit}, multiplier=${decision.multiplier}"
                )
                val resolved = nutritionProvider.resolveAmount(chosen, qty, unit)
                val grams = (resolved?.value ?: (qty * chosen.baseAmount.value)) * multiplier
                appendAiTrace(
                    "TOOL RESOLVE_AMOUNT",
                    "chosen='${chosen.name}', requestedQty=$qty, requestedUnit=${unit ?: "(none)"}, resolvedGrams=${resolved?.value}, finalGrams=$grams"
                )
                aiStatus = "Logging ${chosen.name}…"
                logFood(chosen, grams, navigateBack = false)
                appendAiTrace("TOOL LOG_RECORD", "Logged '${chosen.name}' at $grams g")
                loggedCount += 1
                continue
            }

            aiStatus = "No direct match for '${mention.foodPhrase}', decomposing…"
            val decomposed = aiDecomposeMention(mention.foodPhrase)
            appendAiTrace(
                "TOOL DECOMPOSE",
                if (decomposed.isEmpty()) {
                    "No decomposition produced for '${mention.foodPhrase}'"
                } else {
                    "No direct DB match for '${mention.foodPhrase}'. Decomposed into: " +
                        decomposed.joinToString { "'${it.foodPhrase}'" }
                }
            )

            var subLogged = 0
            for (sub in decomposed) {
                val subCandidates = findCandidates(sub.foodPhrase)
                if (subCandidates.isEmpty()) continue
                val subDecision = askModelForDecision(sub, subCandidates, prompt).getOrNull() ?: continue
                val subChosen = if (subDecision.candidateIndex in subCandidates.indices) subCandidates[subDecision.candidateIndex] else subCandidates.first()
                val subQty = (subDecision.quantity ?: sub.quantity).coerceAtLeast(0.1)
                val subUnit = subDecision.unit ?: sub.unit
                val subMultiplier = (subDecision.multiplier ?: 1.0).coerceIn(0.5, 3.0)
                val subResolved = nutritionProvider.resolveAmount(subChosen, subQty, subUnit)
                val subGrams = (subResolved?.value ?: (subQty * subChosen.baseAmount.value)) * subMultiplier
                appendAiTrace("TOOL RESOLVE_AMOUNT", "decomposed='${sub.foodPhrase}' -> '${subChosen.name}', grams=$subGrams")
                logFood(subChosen, subGrams, navigateBack = false)
                appendAiTrace("TOOL LOG_RECORD", "Logged decomposed '${subChosen.name}' at $subGrams g")
                subLogged += 1
            }
            if (subLogged > 0) {
                loggedCount += subLogged
                continue
            }

            runCatching {
                aiStatus = "No strong match for '${mention.foodPhrase}'. Logging estimate…"
                appendAiTrace("TOOL ESTIMATE", "No DB/decomposed match for '${mention.foodPhrase}', writing estimated nutrition record")
                logFallbackEstimate(mention)
                loggedCount += 1
            }.onFailure {
                if (allowFollowup && followupRemaining > 0) {
                    followupRemaining -= 1
                    askFollowupQuestions = false
                    val msg = "I couldn't match '${mention.foodPhrase}'. Please clarify that item (the DB has duplicates and odd serving names)."
                    aiStatus = msg
                    aiMessages += AiChatMessage(fromUser = false, text = msg)
                }
            }
        }

        val finalMsg = if (loggedCount > 0) {
            "Logged $loggedCount food item(s). I queried DB candidates per item, let AI choose from serving/grams/per100g context, and applied quantity math."
        } else {
            "No items were logged."
        }
        aiStatus = finalMsg
        appendAiTrace("RUN SUMMARY", "loggedCount=$loggedCount, followupRemaining=$followupRemaining")
        aiMessages += AiChatMessage(fromUser = false, text = finalMsg)
        aiBusy = false
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
                },
                actions = {
                    val aiFeaturesDisabled by userPreferencesRepository.aiFeaturesDisabled.collectAsState(initial = false)
                    val effectiveGlobalAiEnabled by userPreferencesRepository.effectiveGlobalAiEnabled.collectAsState(initial = true)
                    if (!aiFeaturesDisabled && effectiveGlobalAiEnabled) {
                        IconButton(onClick = { showAiChat = !showAiChat }, enabled = !aiBusy) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "AI log foods")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (aiStatus != null) {
                item {
                    AssistChip(onClick = {}, label = { Text(aiStatus!!) })
                }
            }

            if (showAiChat) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("AI food chat", style = MaterialTheme.typography.titleMedium)
                            if (aiBusy) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            if (developerModeEnabled && aiMessages.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(aiTranscriptText()))
                                        Toast.makeText(context, "Copied AI transcript", Toast.LENGTH_SHORT).show()
                                    },
                                    enabled = !aiBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Copy all") }
                            }
                            if (aiMessages.isNotEmpty()) {
                                SelectionContainer {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        aiMessages.forEach { msg ->
                                            Text(
                                                text = (if (msg.fromUser) "You: " else "AI: ") + msg.text,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (msg.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = aiPrompt,
                                onValueChange = { aiPrompt = it },
                                enabled = !aiBusy,
                                label = { Text("What did you eat, and when?") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = askFollowupQuestions,
                                    onCheckedChange = { askFollowupQuestions = it },
                                    enabled = !aiBusy
                                )
                                Text("Ask follow-up questions if needed (${followupRemaining} left)")
                            }
                            Button(
                                onClick = { scope.launch { handleAiFoodLogPrompt(aiPrompt, askFollowupQuestions) } },
                                enabled = !aiBusy && aiPrompt.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Send") }
                        }
                    }
                }
            }

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

            // Build-in-progress banner
            if (isDbBuilding) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Unzipping dataset…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // Search results
            if (query.isNotBlank()) {
                if (searchResults.isEmpty()) {
                    item {
                        Text(
                            if (isDbBuilding)
                                "No results yet — database is still building (unzipping dataset…)"
                            else
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
                                keyboardController?.hide()
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
                                // Recents have no serving info — always raw gram/oz mode
                                servingInputMode = false
                                val t = gramsToAmountText(lastAmount.value, unitSystem)
                                amountText = TextFieldValue(t, selection = TextRange(0, t.length))
                                eatenTime = defaultEatenTime
                                keyboardController?.hide()
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

            item { NutritionAttributionFooter() }
        }
    }

    // Food detail bottom sheet – replaces old Scaffold bottomBar so keyboard only pushes the sheet up
    selectedFood?.let { candidate ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val focusRequester = remember { FocusRequester() }
        val si = candidate.servingInfo

        // Derived grams from whichever input mode is active
        val gramsFromInput: Double? = if (servingInputMode && si != null) {
            servingAmountText.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { qty ->
                qty * si.gramsPerCommonUnit
            }
        } else {
            parseAmountToGrams(amountText.text, unitSystem)
        }

        // When unit system changes while in raw-gram mode, re-sync the displayed text
        LaunchedEffect(unitSystem) {
            if (!servingInputMode) {
                val currentGrams = parseAmountToGrams(amountText.text, unitSystem)
                    ?: candidate.baseAmount.value
                val text = gramsToAmountText(currentGrams, unitSystem)
                amountText = TextFieldValue(text, selection = TextRange(0, text.length))
            }
        }

        ModalBottomSheet(
            onDismissRequest = { selectedFood = null; amountText = TextFieldValue("") },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(candidate.name, style = MaterialTheme.typography.titleMedium)

                // Label chips
                if (candidate.labels.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        candidate.labels.take(4).forEach { label ->
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        label.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }

                val baseNutrition = candidate.nutrientsPerBase
                val grams = gramsFromInput ?: candidate.baseAmount.value
                val multiplier = if (candidate.baseAmount.value > 0.0) grams / candidate.baseAmount.value else 1.0
                val summaryParts = enabledNutrients.mapNotNull { key ->
                    val displayVal = NutrientDefaults.getValueInDisplayUnit(baseNutrition, key) * multiplier
                    if (displayVal <= 0.0) return@mapNotNull null
                    val unit = NutrientDefaults.displayUnit[key] ?: ""
                    val name = NutrientDefaults.displayName[key] ?: key.name
                    if (key == NutrientKey.CALORIES) "${displayVal.toInt()} kcal"
                    else "$name: ${if (displayVal < 10.0) "%.1f".format(displayVal) else displayVal.toInt().toString()}$unit"
                }.take(5)
                if (summaryParts.isNotEmpty()) {
                    Text(
                        summaryParts.joinToString("  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

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

                // Amount input — serving mode or raw gram/oz mode
                if (servingInputMode && si?.commonUnit != null) {
                    OutlinedTextField(
                        value = servingAmountText,
                        onValueChange = { servingAmountText = it },
                        label = { Text("How many ${si.commonUnit}?") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val g = gramsFromInput ?: return@KeyboardActions
                                scope.launch { logFood(candidate, g) }
                            }
                        ),
                        singleLine = true,
                        supportingText = gramsFromInput?.let {
                            { Text("≈ ${it.toInt()}g", style = MaterialTheme.typography.labelSmall) }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                    )
                    TextButton(
                        onClick = {
                            servingInputMode = false
                            val text = gramsToAmountText(gramsFromInput ?: candidate.baseAmount.value, unitSystem)
                            amountText = TextFieldValue(text, selection = TextRange(0, text.length))
                        }
                    ) { Text("Switch to ${amountUnitLabel(unitSystem)}") }
                } else {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (${amountUnitLabel(unitSystem)})") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val g = gramsFromInput ?: return@KeyboardActions
                                scope.launch { logFood(candidate, g) }
                            }
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                    )
                    if (si?.commonUnit != null) {
                        TextButton(
                            onClick = {
                                servingInputMode = true
                                servingAmountText = "%.1f".format(si.commonQuantity ?: 1.0)
                            }
                        ) { Text("Switch to ${si.commonUnit}") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { selectedFood = null; amountText = TextFieldValue("") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel") }
                    Button(
                        onClick = {
                            val g = gramsFromInput ?: return@Button
                            scope.launch { logFood(candidate, g) }
                        },
                        enabled = !isLogging && gramsFromInput != null,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (isLogging) "Logging…" else "Log") }
                }
            }

            LaunchedEffect(Unit) {
                delay(200) // allow sheet animation to settle
                focusRequester.requestFocus()
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
        Column(modifier = Modifier.padding(12.dp)) {
            Text(candidate.name, style = MaterialTheme.typography.bodyMedium)
            val sub = subtitle ?: "${candidate.baseAmount.value.toInt()}g"
            Text(
                "$sub · ${candidate.nutrientsPerBase.calories.toInt()} kcal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            if (candidate.labels.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    candidate.labels.take(3).forEach { label ->
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    label.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.height(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NutritionAttributionFooter() {
    val uriHandler = LocalUriHandler.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Nutrition data: OpenNutrition",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.clickable { uriHandler.openUri("https://opennutrition.app") }
        )
    }
}

