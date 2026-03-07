package codegito.xyz.healthconnector.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import codegito.xyz.healthconnector.HealthConnectManager
import codegito.xyz.healthconnector.NotificationHelper
import codegito.xyz.healthconnector.SleepTrackingService
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.nutrition.data.NutritionIndexBuildManager
import codegito.xyz.healthconnector.nutrition.provider.AssetNutritionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    onManagePermissions: () -> Unit,
    onEditSleepStages: () -> Unit,
    onAutoSleepSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val nutritionIndexBuildManager = remember(context) { NutritionIndexBuildManager(context) }
    val nutritionProvider = remember(context) { AssetNutritionProvider(context) }

    var hasHealthConnectPermissions by remember { mutableStateOf<Boolean?>(null) }

    val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)
    val developerModeEnabled by userPreferencesRepository.developerModeEnabled.collectAsState(initial = false)
    val amoledPitchBlackEnabled by userPreferencesRepository.amoledPitchBlackEnabled.collectAsState(initial = false)
    val nutritionRangeDays by userPreferencesRepository.nutritionPastDateRangeDays.collectAsState(initial = 7)
    val nutritionMealDuration by userPreferencesRepository.nutritionMealDurationMinutes.collectAsState(initial = 30)
    val nutritionSnackDuration by userPreferencesRepository.nutritionSnackDurationMinutes.collectAsState(initial = 10)
    var showRolloverTimePicker by remember { mutableStateOf(false) }
    var versionTapCount by remember { mutableIntStateOf(0) }

    // Dataset management state
    var datasetRecordCount by remember { mutableIntStateOf(-1) }
    var isBuildingDataset by remember { mutableStateOf(false) }
    var datasetStatusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshDatasetCount() {
        scope.launch {
            datasetRecordCount = withContext(Dispatchers.IO) { nutritionIndexBuildManager.indexRecordCount() }
        }
    }

    LaunchedEffect(Unit) { refreshDatasetCount() }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
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
    
    // Check if service is running
    var isServiceActive by remember { mutableStateOf(false) }
    var nutritionMetadataSummary by remember { mutableStateOf("Unavailable") }
    
    LaunchedEffect(developerModeEnabled) {
        if (!developerModeEnabled) return@LaunchedEffect
        nutritionMetadataSummary = runCatching {
            val metadataText = context.filesDir.resolve("nutrition/metadata.json").takeIf { it.exists() }?.readText()
                ?: context.assets.open("nutrition/metadata.json").bufferedReader().use { it.readText() }
            org.json.JSONObject(metadataText).let {
                "records=" + it.optInt("recordCount", 0) +
                    ", source=" + it.optString("sourceLocation", "unknown") +
                    ", log=" + it.optString("buildLogDownloadUri", it.optString("buildLogPath", context.filesDir.resolve("nutrition/build-log.txt").absolutePath))
            }
        }.getOrDefault("Unavailable")
        while (true) {
            isServiceActive = isServiceRunning(context, SleepTrackingService::class.java)
            kotlinx.coroutines.delay(2000)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                 scope.launch {
                    hasHealthConnectPermissions = healthConnectManager.hasPermissions()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Permissions Section
            SectionHeader("Permissions")
            
            if (hasHealthConnectPermissions == false) {
                Button(
                    onClick = onManagePermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Health Connect Permissions")
                }
            } else {
                OutlinedButton(
                    onClick = onManagePermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage Health Connect Permissions")
                }
            }

            OutlinedButton(
                onClick = {
                    val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                         val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(settingsIntent)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Health Connect App")
            }

            HorizontalDivider()

            // Automation Section
            SectionHeader("Automation")

            ListItem(
                headlineContent = { Text("Auto-Detect Sleep") },
                supportingContent = { Text("Configure windows, thresholds, and manual template") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = Modifier.clickable { onAutoSleepSettings() }
            )

            HorizontalDivider()

            // Appearance Section
            SectionHeader("Appearance")
            ListItem(
                headlineContent = { Text("Pitch black (AMOLED)") },
                supportingContent = { Text("Use pure black backgrounds in dark mode.") },
                trailingContent = {
                    Switch(
                        checked = amoledPitchBlackEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { userPreferencesRepository.setAmoledPitchBlackEnabled(enabled) }
                        }
                    )
                }
            )

            HorizontalDivider()

            // Data Section
            SectionHeader("Data")

            ListItem(
                headlineContent = { Text("Rollover Time") },
                supportingContent = { Text("Cutoff time for daily statistics. If a sleep starts before this time, it will be counted as part of the previous day's night.") },
                trailingContent = {
                    Text(
                        LocalTime.of(rolloverHour, 0).format(DateTimeFormatter.ofPattern("h:mm a")),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.clickable { showRolloverTimePicker = true }
            )


            HorizontalDivider()
            SectionHeader("Nutrition")

            // Dataset management
            val datasetLabel = when {
                datasetRecordCount > 10 -> "${datasetRecordCount} foods loaded"
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
                    onClick = {
                        zipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
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

            ListItem(
                headlineContent = { Text("Past date range") },
                supportingContent = { Text("$nutritionRangeDays days selectable in Food tab") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val next = (nutritionRangeDays - 1).coerceAtLeast(1)
                            scope.launch { userPreferencesRepository.setNutritionPastDateRangeDays(next) }
                        }) { Text("-") }
                        OutlinedButton(onClick = {
                            val next = (nutritionRangeDays + 1).coerceAtMost(365)
                            scope.launch { userPreferencesRepository.setNutritionPastDateRangeDays(next) }
                        }) { Text("+") }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Meal entry duration") },
                supportingContent = { Text("$nutritionMealDuration minutes backfilled from time eaten") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch { userPreferencesRepository.setNutritionMealDurationMinutes((nutritionMealDuration - 5).coerceAtLeast(5)) }
                        }) { Text("-") }
                        OutlinedButton(onClick = {
                            scope.launch { userPreferencesRepository.setNutritionMealDurationMinutes((nutritionMealDuration + 5).coerceAtMost(180)) }
                        }) { Text("+") }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Snack entry duration") },
                supportingContent = { Text("$nutritionSnackDuration minutes backfilled from time eaten") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch { userPreferencesRepository.setNutritionSnackDurationMinutes((nutritionSnackDuration - 5).coerceAtLeast(1)) }
                        }) { Text("-") }
                        OutlinedButton(onClick = {
                            scope.launch { userPreferencesRepository.setNutritionSnackDurationMinutes((nutritionSnackDuration + 5).coerceAtMost(120)) }
                        }) { Text("+") }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Nutrition Dataset License") },
                supportingContent = { Text("Open the bundled dataset attribution and licensing notes") },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                modifier = Modifier.clickable {
                    Toast.makeText(context, "See README + bundled metadata for nutrition dataset licensing.", Toast.LENGTH_LONG).show()
                }
            )

            HorizontalDivider()

            // Sleep Stages Section
            SectionHeader("Sleep Stages")

            ListItem(
                headlineContent = { Text("Customize Sleep Stages") },
                supportingContent = { Text("Reorder, enable/disable, and change emojis") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = Modifier.clickable { onEditSleepStages() }
            )

            if (developerModeEnabled) {
                HorizontalDivider()
                SectionHeader("Developer Tools")
                
                ListItem(
                    headlineContent = { Text("Service Status") },
                    supportingContent = { Text(if (isServiceActive) "Running (Foreground)" else "Stopped") },
                    trailingContent = {
                        val color = if (isServiceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        Surface(
                            modifier = Modifier.size(12.dp),
                            shape = CircleShape,
                            color = color
                        ) {}
                    }
                )

                ListItem(
                    headlineContent = { Text("Nutrition index") },
                    supportingContent = { Text(nutritionMetadataSummary) }
                )
                
                Button(
                    onClick = {
                        NotificationHelper.sendLogReminder(
                            context,
                            LocalDate.now().minusDays(1),
                            NotificationHelper.REMINDER_CHANNEL_ID,
                            title = "Test Notification",
                            text = "This is a manually triggered developer reminder."
                        )
                        Toast.makeText(context, "Test notification sent", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Trigger Test Notification")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch { userPreferencesRepository.setDeveloperModeEnabled(false) }
                        Toast.makeText(context, "Developer Mode Disabled", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disable Developer Mode")
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            
            val appVersion = "1.0" // TODO: Get from BuildConfig if available
            Text(
                text = "Version $appVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        versionTapCount++
                        if (versionTapCount == 7) {
                            scope.launch { userPreferencesRepository.setDeveloperModeEnabled(true) }
                            Toast.makeText(context, "Developer Mode Enabled", Toast.LENGTH_SHORT).show()
                            versionTapCount = 0
                        }
                    }
                    .padding(vertical = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    if (showRolloverTimePicker) {
         RolloverTimePickerDialog(
             initialHour = rolloverHour,
             onConfirm = { hour ->
                 scope.launch {
                     userPreferencesRepository.setRolloverHour(hour)
                 }
                 showRolloverTimePicker = false
             },
             onDismiss = { showRolloverTimePicker = false }
         )
    }
}

private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    return manager.getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == serviceClass.name }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RolloverTimePickerDialog(
    initialHour: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = 0,
        is24Hour = false
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Rollover Hour") },
        text = {
            TimePicker(state = state)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
