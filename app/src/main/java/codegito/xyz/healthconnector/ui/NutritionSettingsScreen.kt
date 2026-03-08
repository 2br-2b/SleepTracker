package codegito.xyz.healthconnector.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.nutrition.data.NutritionIndexBuildManager
import codegito.xyz.healthconnector.nutrition.provider.AssetNutritionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val nutritionIndexBuildManager = remember(context) { NutritionIndexBuildManager(context) }
    val nutritionProvider = remember(context) { AssetNutritionProvider(context) }

    // ── Preferences ───────────────────────────────────────────────────────
    val showAdvanced by userPreferencesRepository.showAdvancedSettings.collectAsState(initial = false)
    val nutritionRangeDays by userPreferencesRepository.nutritionPastDateRangeDays.collectAsState(initial = 7)
    // Advanced prefs
    val askEatenTime by userPreferencesRepository.nutritionAskEatenTime.collectAsState(initial = false)
    val breakfastStart by userPreferencesRepository.nutritionBreakfastStartHour.collectAsState(initial = 6)
    val breakfastEnd by userPreferencesRepository.nutritionBreakfastEndHour.collectAsState(initial = 10)
    val lunchStart by userPreferencesRepository.nutritionLunchStartHour.collectAsState(initial = 11)
    val lunchEnd by userPreferencesRepository.nutritionLunchEndHour.collectAsState(initial = 14)
    val dinnerStart by userPreferencesRepository.nutritionDinnerStartHour.collectAsState(initial = 17)
    val dinnerEnd by userPreferencesRepository.nutritionDinnerEndHour.collectAsState(initial = 21)
    val mealDuration by userPreferencesRepository.nutritionMealDurationMinutes.collectAsState(initial = 30)
    val snackDuration by userPreferencesRepository.nutritionSnackDurationMinutes.collectAsState(initial = 10)

    // ── Dataset state ─────────────────────────────────────────────────────
    var datasetRecordCount by remember { mutableIntStateOf(-1) }
    var isBuildingDataset by remember { mutableStateOf(false) }
    var datasetStatusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshDatasetCount() {
        scope.launch {
            datasetRecordCount = withContext(Dispatchers.IO) { nutritionIndexBuildManager.indexRecordCount() }
        }
    }

    LaunchedEffect(Unit) { refreshDatasetCount() }

    val zipPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBuildingDataset = true
            datasetStatusMessage = "Building index…"
            nutritionIndexBuildManager.buildFromUri(uri)
                .onSuccess { build ->
                    nutritionProvider.invalidateCache()
                    datasetStatusMessage = "Ready: ${build.recordCount} foods loaded"
                    refreshDatasetCount()
                }
                .onFailure { datasetStatusMessage = "Failed: ${it.message}" }
            isBuildingDataset = false
        }
    }

    // Hour-picker dialog state
    var pickerTitle by remember { mutableStateOf("") }
    var pickerInitialHour by remember { mutableIntStateOf(0) }
    var pickerCallback by remember { mutableStateOf<((Int) -> Unit)?>(null) }

    fun showHourPicker(title: String, initial: Int, onConfirm: (Int) -> Unit) {
        pickerTitle = title
        pickerInitialHour = initial
        pickerCallback = onConfirm
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nutrition Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Food database ─────────────────────────────────────────────
            SectionHeader("Food Database")

            val datasetLabel = when {
                datasetRecordCount > 10 -> "$datasetRecordCount foods loaded"
                datasetRecordCount == 0 -> "No dataset loaded"
                datasetRecordCount > 0 -> "$datasetRecordCount foods (too few — re-upload)"
                else -> "Checking…"
            }
            ListItem(
                headlineContent = { Text("Food database") },
                supportingContent = { Text(datasetLabel) }
            )
            if (isBuildingDataset) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            datasetStatusMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { zipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    enabled = !isBuildingDataset,
                    modifier = Modifier.weight(1f)
                ) { Text("Upload ZIP") }
                if (datasetRecordCount > 0) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { nutritionIndexBuildManager.clearIndex() }
                                nutritionProvider.invalidateCache()
                                datasetStatusMessage = "Dataset cleared"
                                refreshDatasetCount()
                            }
                        },
                        enabled = !isBuildingDataset,
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear") }
                }
            }

            HorizontalDivider()

            // ── Display ───────────────────────────────────────────────────
            SectionHeader("Display")

            ListItem(
                headlineContent = { Text("Past date range") },
                supportingContent = { Text("$nutritionRangeDays days selectable in Food tab") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                userPreferencesRepository.setNutritionPastDateRangeDays(
                                    (nutritionRangeDays - 1).coerceAtLeast(1)
                                )
                            }
                        }) { Text("-") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                userPreferencesRepository.setNutritionPastDateRangeDays(
                                    (nutritionRangeDays + 1).coerceAtMost(365)
                                )
                            }
                        }) { Text("+") }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Nutrition Dataset License") },
                supportingContent = { Text("See README + bundled metadata for dataset attribution and licensing.") },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                modifier = Modifier.clickable {
                    Toast.makeText(context, "See README + bundled metadata for nutrition dataset licensing.", Toast.LENGTH_LONG).show()
                }
            )

            // ── Advanced (inline) ─────────────────────────────────────────
            if (showAdvanced) {
                HorizontalDivider()
                SectionHeader("Advanced")

                // Time tracking
                Text("Time Tracking", style = MaterialTheme.typography.labelLarge)
                ListItem(
                    headlineContent = { Text("Ask when food was eaten") },
                    supportingContent = {
                        Text(
                            if (askEatenTime) "Shows a time picker each time you log food."
                            else "Logs at current time without asking."
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = askEatenTime,
                            onCheckedChange = { enabled ->
                                scope.launch { userPreferencesRepository.setNutritionAskEatenTime(enabled) }
                            }
                        )
                    }
                )

                // Meal windows
                Text("Meal Detection Windows", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Foods logged within a window are tagged as that meal. Everything else is tagged as a snack.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                NutritionMealWindowCard(
                    label = "Breakfast",
                    startHour = breakfastStart,
                    endHour = breakfastEnd,
                    onEditStart = { showHourPicker("Breakfast start", breakfastStart) { h ->
                        scope.launch { userPreferencesRepository.setNutritionBreakfastHours(h, breakfastEnd) }
                    }},
                    onEditEnd = { showHourPicker("Breakfast end", breakfastEnd) { h ->
                        scope.launch { userPreferencesRepository.setNutritionBreakfastHours(breakfastStart, h) }
                    }}
                )

                NutritionMealWindowCard(
                    label = "Lunch",
                    startHour = lunchStart,
                    endHour = lunchEnd,
                    onEditStart = { showHourPicker("Lunch start", lunchStart) { h ->
                        scope.launch { userPreferencesRepository.setNutritionLunchHours(h, lunchEnd) }
                    }},
                    onEditEnd = { showHourPicker("Lunch end", lunchEnd) { h ->
                        scope.launch { userPreferencesRepository.setNutritionLunchHours(lunchStart, h) }
                    }}
                )

                NutritionMealWindowCard(
                    label = "Dinner",
                    startHour = dinnerStart,
                    endHour = dinnerEnd,
                    onEditStart = { showHourPicker("Dinner start", dinnerStart) { h ->
                        scope.launch { userPreferencesRepository.setNutritionDinnerHours(h, dinnerEnd) }
                    }},
                    onEditEnd = { showHourPicker("Dinner end", dinnerEnd) { h ->
                        scope.launch { userPreferencesRepository.setNutritionDinnerHours(dinnerStart, h) }
                    }}
                )

                // Entry duration
                Text("Entry Duration", style = MaterialTheme.typography.labelLarge)
                ListItem(
                    headlineContent = { Text("Meal duration") },
                    supportingContent = { Text("$mealDuration min — backfilled from eaten time for breakfast, lunch, dinner") },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch { userPreferencesRepository.setNutritionMealDurationMinutes((mealDuration - 5).coerceAtLeast(5)) }
                            }) { Text("-") }
                            OutlinedButton(onClick = {
                                scope.launch { userPreferencesRepository.setNutritionMealDurationMinutes((mealDuration + 5).coerceAtMost(180)) }
                            }) { Text("+") }
                        }
                    }
                )
                ListItem(
                    headlineContent = { Text("Snack duration") },
                    supportingContent = { Text("$snackDuration min — used for snacks and foods outside meal windows") },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch { userPreferencesRepository.setNutritionSnackDurationMinutes((snackDuration - 5).coerceAtLeast(1)) }
                            }) { Text("-") }
                            OutlinedButton(onClick = {
                                scope.launch { userPreferencesRepository.setNutritionSnackDurationMinutes((snackDuration + 5).coerceAtMost(120)) }
                            }) { Text("+") }
                        }
                    }
                )
            }
        }
    }

    // Hour picker dialog
    pickerCallback?.let { callback ->
        RolloverTimePickerDialog(
            initialHour = pickerInitialHour,
            onConfirm = { hour ->
                callback(hour)
                pickerCallback = null
            },
            onDismiss = { pickerCallback = null }
        )
    }
}

@Composable
private fun NutritionMealWindowCard(
    label: String,
    startHour: Int,
    endHour: Int,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit
) {
    val fmt = DateTimeFormatter.ofPattern("h a")
    val startLabel = LocalTime.of(startHour, 0).format(fmt)
    val endLabel = LocalTime.of(endHour, 0).format(fmt)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Start", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(startLabel, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(onClick = onEditStart) { Text("Change") }
                Column(modifier = Modifier.weight(1f)) {
                    Text("End", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(endLabel, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(onClick = onEditEnd) { Text("Change") }
            }
        }
    }
}
