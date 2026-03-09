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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.TimeRange
import codegito.xyz.healthconnector.nutrition.data.NutritionIndexBuildManager
import codegito.xyz.healthconnector.nutrition.provider.AssetNutritionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val nutritionEnabled by userPreferencesRepository.nutritionEnabled.collectAsState(initial = true)
    val showAdvanced by userPreferencesRepository.showAdvancedSettings.collectAsState(initial = false)
    val nutritionRangeDays by userPreferencesRepository.nutritionPastDateRangeDays.collectAsState(initial = 7)
    val askEatenTime by userPreferencesRepository.nutritionAskEatenTime.collectAsState(initial = false)
    val breakfastRange by userPreferencesRepository.nutritionBreakfastRange.collectAsState(initial = TimeRange.BREAKFAST)
    val lunchRange by userPreferencesRepository.nutritionLunchRange.collectAsState(initial = TimeRange.LUNCH)
    val dinnerRange by userPreferencesRepository.nutritionDinnerRange.collectAsState(initial = TimeRange.DINNER)
    val mealDuration by userPreferencesRepository.nutritionMealDurationMinutes.collectAsState(initial = 30)
    val snackDuration by userPreferencesRepository.nutritionSnackDurationMinutes.collectAsState(initial = 10)

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
            // ── Enable / Disable toggle ────────────────────────────────────
            ListItem(
                headlineContent = { Text("Nutrition Tracking Enabled") },
                supportingContent = {
                    Text(if (nutritionEnabled) "Nutrition is active and visible in the app."
                         else "Nutrition is disabled. Food tab and settings below are hidden.")
                },
                trailingContent = {
                    Switch(
                        checked = nutritionEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { userPreferencesRepository.setNutritionEnabled(enabled) }
                        }
                    )
                }
            )

            HorizontalDivider()

            val contentEnabled = nutritionEnabled
            val contentAlpha = if (nutritionEnabled) 1f else 0.38f

            // ── Food database ─────────────────────────────────────────────
            SectionHeader("Food Database")

            val datasetLabel = when {
                datasetRecordCount > 10 -> "$datasetRecordCount foods loaded"
                datasetRecordCount == 0 -> "No dataset loaded"
                datasetRecordCount > 0  -> "$datasetRecordCount foods (too few — re-upload)"
                else -> "Checking…"
            }
            ListItem(headlineContent = { Text("Food database") }, supportingContent = { Text(datasetLabel) })

            if (isBuildingDataset) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            datasetStatusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { zipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    enabled = contentEnabled && !isBuildingDataset, modifier = Modifier.weight(1f)
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
                        enabled = contentEnabled && !isBuildingDataset, modifier = Modifier.weight(1f)
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
                        OutlinedButton(
                            onClick = { scope.launch { userPreferencesRepository.setNutritionPastDateRangeDays((nutritionRangeDays - 1).coerceAtLeast(1)) } },
                            enabled = contentEnabled
                        ) { Text("-") }
                        OutlinedButton(
                            onClick = { scope.launch { userPreferencesRepository.setNutritionPastDateRangeDays((nutritionRangeDays + 1).coerceAtMost(365)) } },
                            enabled = contentEnabled
                        ) { Text("+") }
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
            if (showAdvanced && contentEnabled) {
                HorizontalDivider()
                SectionHeader("Advanced")

                Text("Time Tracking", style = MaterialTheme.typography.labelLarge)
                ListItem(
                    headlineContent = { Text("Ask when food was eaten") },
                    supportingContent = {
                        Text(if (askEatenTime) "Shows a time picker each time you log food."
                             else "Logs at current time without asking.")
                    },
                    trailingContent = {
                        Switch(
                            checked = askEatenTime,
                            onCheckedChange = { scope.launch { userPreferencesRepository.setNutritionAskEatenTime(it) } }
                        )
                    }
                )

                Text("Meal Detection Windows", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Foods logged within a window are tagged as that meal. Everything else is tagged as a snack.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TimeRangeSetting(
                    label = "Breakfast",
                    range = breakfastRange,
                    onRangeChange = { scope.launch { userPreferencesRepository.setNutritionBreakfastRange(it) } }
                )
                TimeRangeSetting(
                    label = "Lunch",
                    range = lunchRange,
                    onRangeChange = { scope.launch { userPreferencesRepository.setNutritionLunchRange(it) } }
                )
                TimeRangeSetting(
                    label = "Dinner",
                    range = dinnerRange,
                    onRangeChange = { scope.launch { userPreferencesRepository.setNutritionDinnerRange(it) } }
                )

                Text("Entry Duration", style = MaterialTheme.typography.labelLarge)
                ListItem(
                    headlineContent = { Text("Meal duration") },
                    supportingContent = { Text("$mealDuration min — backfilled from eaten time for breakfast, lunch, dinner") },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { scope.launch { userPreferencesRepository.setNutritionMealDurationMinutes((mealDuration - 5).coerceAtLeast(5)) } }) { Text("-") }
                            OutlinedButton(onClick = { scope.launch { userPreferencesRepository.setNutritionMealDurationMinutes((mealDuration + 5).coerceAtMost(180)) } }) { Text("+") }
                        }
                    }
                )
                ListItem(
                    headlineContent = { Text("Snack duration") },
                    supportingContent = { Text("$snackDuration min — used for snacks and foods outside meal windows") },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { scope.launch { userPreferencesRepository.setNutritionSnackDurationMinutes((snackDuration - 5).coerceAtLeast(1)) } }) { Text("-") }
                            OutlinedButton(onClick = { scope.launch { userPreferencesRepository.setNutritionSnackDurationMinutes((snackDuration + 5).coerceAtMost(120)) } }) { Text("+") }
                        }
                    }
                )
            }
        }
    }
}
