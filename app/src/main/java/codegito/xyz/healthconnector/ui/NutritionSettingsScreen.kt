package codegito.xyz.healthconnector.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.TimeRange
import androidx.compose.ui.platform.LocalUriHandler
import codegito.xyz.healthconnector.nutrition.data.NutritionIndexBuildManager
import codegito.xyz.healthconnector.nutrition.domain.NutritionUnitSystem
import codegito.xyz.healthconnector.nutrition.provider.NutritionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    nutritionProvider: NutritionProvider,
    onBack: () -> Unit,
    onEditNutrients: () -> Unit,
    scrollToDataset: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val nutritionIndexBuildManager = remember(context) { NutritionIndexBuildManager(context) }

    val nutritionEnabled by userPreferencesRepository.nutritionEnabled.collectAsState(initial = true)
    val showAdvanced by userPreferencesRepository.showAdvancedSettings.collectAsState(initial = false)
    val askEatenTime by userPreferencesRepository.nutritionAskEatenTime.collectAsState(initial = false)
    val breakfastRange by userPreferencesRepository.nutritionBreakfastRange.collectAsState(initial = TimeRange.BREAKFAST)
    val lunchRange by userPreferencesRepository.nutritionLunchRange.collectAsState(initial = TimeRange.LUNCH)
    val dinnerRange by userPreferencesRepository.nutritionDinnerRange.collectAsState(initial = TimeRange.DINNER)
    val mealDuration by userPreferencesRepository.nutritionMealDurationMinutes.collectAsState(initial = 30)
    val snackDuration by userPreferencesRepository.nutritionSnackDurationMinutes.collectAsState(initial = 10)
    val unitSystem by userPreferencesRepository.nutritionUnitSystem.collectAsState(initial = NutritionUnitSystem.US)
    val applyFilterToSearch by userPreferencesRepository.nutritionApplyNutrientFilterToSearch.collectAsState(initial = false)

    var datasetRecordCount by remember { mutableIntStateOf(-1) }
    var isBuildingDataset by remember { mutableStateOf(false) }
    var datasetStatusMessage by remember { mutableStateOf<String?>(null) }
    var buildProgress by remember { mutableIntStateOf(0) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showMergeDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    var datasetSectionContentY by remember { mutableIntStateOf(0) }
    // Blink highlight for 3 seconds when scrollToDataset is requested
    var isHighlighting by remember(scrollToDataset) { mutableStateOf(scrollToDataset) }
    val infiniteTransition = rememberInfiniteTransition(label = "datasetHighlight")
    val highlightAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "highlightPulse"
    )

    LaunchedEffect(scrollToDataset) {
        if (scrollToDataset) {
            kotlinx.coroutines.delay(200) // let layout settle
            scrollState.animateScrollTo(maxOf(0, datasetSectionContentY - 32))
            kotlinx.coroutines.delay(3000)
            isHighlighting = false
        }
    }

    fun refreshDatasetCount() {
        scope.launch {
            datasetRecordCount = withContext(Dispatchers.IO) { nutritionIndexBuildManager.indexRecordCount() }
        }
    }

    LaunchedEffect(Unit) { refreshDatasetCount() }

    fun launchBuildFromUri(uri: Uri, mergeMode: Boolean) {
        scope.launch {
            isBuildingDataset = true
            buildProgress = 0
            datasetStatusMessage = if (mergeMode) "Merging dataset…" else "Building database…"
            nutritionIndexBuildManager.buildFromUri(
                uri = uri,
                progressCallback = { current, _ -> buildProgress = current },
                mergeMode = mergeMode
            )
                .onSuccess { build ->
                    nutritionProvider.invalidateCache()
                    datasetStatusMessage = "Ready: ${build.recordCount} foods loaded"
                    refreshDatasetCount()
                }
                .onFailure { datasetStatusMessage = "Failed: ${it.message}" }
            isBuildingDataset = false
        }
    }

    fun launchBuildFromBundled() {
        scope.launch {
            isBuildingDataset = true
            buildProgress = 0
            datasetStatusMessage = "Building database from bundled dataset…"
            nutritionIndexBuildManager.buildFromBundledZip(
                progressCallback = { current, _ -> buildProgress = current }
            )
                .onSuccess { build ->
                    nutritionProvider.invalidateCache()
                    datasetStatusMessage = "Ready: ${build.recordCount} foods loaded"
                    refreshDatasetCount()
                }
                .onFailure { datasetStatusMessage = "Failed: ${it.message}" }
            isBuildingDataset = false
        }
    }

    val zipPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImportUri = uri
        showMergeDialog = true
    }

    if (showMergeDialog) {
        val uri = pendingImportUri
        AlertDialog(
            onDismissRequest = { showMergeDialog = false; pendingImportUri = null },
            title = { Text("Import Dataset") },
            text = { Text("Merge with existing database, or replace it entirely?") },
            confirmButton = {
                TextButton(onClick = {
                    showMergeDialog = false
                    uri?.let { launchBuildFromUri(it, mergeMode = false) }
                    pendingImportUri = null
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMergeDialog = false
                    uri?.let { launchBuildFromUri(it, mergeMode = true) }
                    pendingImportUri = null
                }) { Text("Merge") }
            }
        )
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
                .verticalScroll(scrollState)
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

            // ── Food database ─────────────────────────────────────────────
            val datasetHighlightBorderColor = if (isHighlighting)
                MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha)
            else
                MaterialTheme.colorScheme.primary.copy(alpha = 0f)

            Column(
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        datasetSectionContentY = (coords.positionInRoot().y + scrollState.value).toInt()
                    }
                    .border(
                        width = 2.dp,
                        color = datasetHighlightBorderColor,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(if (isHighlighting) 8.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            SectionHeader("Food Database")

            val datasetLabel = when {
                datasetRecordCount > 10 -> "$datasetRecordCount foods loaded"
                datasetRecordCount == 0 -> "No dataset loaded"
                datasetRecordCount > 0  -> "$datasetRecordCount foods (too few — re-upload)"
                else -> "Checking…"
            }
            ListItem(headlineContent = { Text("Food database") }, supportingContent = { Text(datasetLabel) })

            if (isBuildingDataset) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (buildProgress > 0) {
                    Text(
                        "$buildProgress foods indexed…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            datasetStatusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { zipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    enabled = contentEnabled && !isBuildingDataset, modifier = Modifier.weight(1f)
                ) { Text("Import ZIP") }
                OutlinedButton(
                    onClick = { launchBuildFromBundled() },
                    enabled = contentEnabled && !isBuildingDataset, modifier = Modifier.weight(1f)
                ) { Text("Re-index") }
            }
            if (datasetRecordCount > 0) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { nutritionIndexBuildManager.clearIndex() }
                            nutritionProvider.invalidateCache()
                            datasetStatusMessage = "Database cleared"
                            refreshDatasetCount()
                        }
                    },
                    enabled = contentEnabled && !isBuildingDataset,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) { Text("Clear database") }
            }
            } // end dataset section highlight Column

            HorizontalDivider()

            // ── Nutrients ─────────────────────────────────────────────────
            SectionHeader("Nutrients")

            ListItem(
                headlineContent = { Text("Configure Nutrients") },
                supportingContent = { Text("Choose which nutrients to track, their display order, and whether to show core nutrients") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = if (contentEnabled) Modifier.clickable { onEditNutrients() } else Modifier
            )

            // ── Time Tracking ─────────────────────────────────────────────
            SectionHeader("Time Tracking")

            ListItem(
                headlineContent = { Text("Ask when food was eaten") },
                supportingContent = {
                    Text(if (askEatenTime) "Shows a time picker each time you log food."
                         else "Logs at current time without asking.")
                },
                trailingContent = {
                    Switch(
                        checked = askEatenTime,
                        onCheckedChange = { scope.launch { userPreferencesRepository.setNutritionAskEatenTime(it) } },
                        enabled = contentEnabled
                    )
                }
            )

            HorizontalDivider()

            // ── Unit system ───────────────────────────────────────────────
            SectionHeader("Units")

            Text(
                "Choose the unit system used when entering and displaying food amounts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NutritionUnitSystem.entries.forEach { system ->
                    val label = when (system) {
                        NutritionUnitSystem.US -> "US (oz)"
                        NutritionUnitSystem.METRIC -> "Metric (g)"
                    }
                    FilterChip(
                        selected = unitSystem == system,
                        onClick = { scope.launch { userPreferencesRepository.setNutritionUnitSystem(system) } },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val uriHandler = LocalUriHandler.current
            ListItem(
                headlineContent = { Text("Nutrition Dataset") },
                supportingContent = {
                    Text("Data from OpenNutrition \u00b7 Open Food Facts contributors \u00b7 ODbL license")
                },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                modifier = Modifier.clickable { uriHandler.openUri("https://opennutrition.app") }
            )

            // ── Advanced (inline) ─────────────────────────────────────────
            if (showAdvanced && contentEnabled) {
                HorizontalDivider()
                SectionHeader("Advanced")

                ListItem(
                    headlineContent = { Text("Apply nutrient filter to searched foods") },
                    supportingContent = {
                        Text(
                            if (applyFilterToSearch)
                                "Only enabled extra nutrients are stored when logging from search results."
                            else
                                "All available nutrient data is stored when logging from search results."
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = applyFilterToSearch,
                            onCheckedChange = { scope.launch { userPreferencesRepository.setNutritionApplyNutrientFilterToSearch(it) } }
                        )
                    }
                )

                HorizontalDivider()

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
