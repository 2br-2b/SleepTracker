package codegito.xyz.healthconnector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlarmManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.SleepDetectionMode
import codegito.xyz.healthconnector.data.model.SleepLogTemplate
import codegito.xyz.healthconnector.data.model.TemplateSegment
import codegito.xyz.healthconnector.data.model.TimeRange
import codegito.xyz.healthconnector.data.model.TrackingType
import codegito.xyz.healthconnector.nutrition.data.NutritionIndexWorker
import androidx.work.WorkInfo
import codegito.xyz.healthconnector.ui.AppTimePickerDialog
import codegito.xyz.healthconnector.ui.PermissionCard
import codegito.xyz.healthconnector.ui.PermissionState
import codegito.xyz.healthconnector.ui.SleepLogEditor
import codegito.xyz.healthconnector.ui.TimeRangeSetting
import codegito.xyz.healthconnector.ui.loadPermissionState
import codegito.xyz.healthconnector.ui.theme.SleepTrackerTheme
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate

class OnboardingActivity : ComponentActivity() {

    private val healthConnectManager by lazy { HealthConnectManager(this) }
    private val userPreferencesRepository by lazy { UserPreferencesRepository.getInstance(this) }

    private val requestHealthPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val requestOtherSensorsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val requestExactAlarmPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Prevent the user from backing out of onboarding before completion
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* block back */ }
        })

        setContent {
            SleepTrackerTheme {
                OnboardingFlow(
                    userPreferencesRepository = userPreferencesRepository,
                    healthConnectManager = healthConnectManager,
                    onInstallHealthConnect = { openHealthConnectInstall() },
                    onRequestHealthPermissions = { perms ->
                        requestHealthPermissions.launch(perms)
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestOtherSensorsPermission = {
                        if (shouldShowOtherSensorsPermission()) {
                            requestOtherSensorsPermission.launch("android.permission.OTHER_SENSORS")
                        }
                    },
                    onRequestExactAlarmPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            requestExactAlarmPermission.launch(intent)
                        }
                    },
                    onFinish = {
                        Toast.makeText(this, "Onboarding complete", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
        }
    }

    private fun openHealthConnectInstall() {
        val isPlayStoreInstalled = runCatching {
            packageManager.getPackageInfo("com.android.vending", 0)
            true
        }.getOrDefault(false)

        if (isPlayStoreInstalled) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
        }
    }

    private fun shouldShowOtherSensorsPermission(): Boolean {
        return hasGrapheneOsPackage("app.grapheneos.apps") || hasGrapheneOsPackage("app.grapheneos.camera")
    }

    private fun hasGrapheneOsPackage(packageName: String): Boolean {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
    }
}

private enum class OnboardingStep {
    TrackingTypeSelection,
    HealthConnect,
    AutoDetectionExplanation,
    LoggingMode,
    AutoConfig,
    ManualInfo,
    Permissions,
    NutritionIndex,
    Completion
}

@Composable
private fun OnboardingFlow(
    userPreferencesRepository: UserPreferencesRepository,
    healthConnectManager: HealthConnectManager,
    onInstallHealthConnect: () -> Unit,
    onRequestHealthPermissions: (Set<String>) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestOtherSensorsPermission: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sleepStages by userPreferencesRepository.sleepStages.collectAsState(initial = emptyList())
    val manualTemplate by userPreferencesRepository.manualSleepTemplate.collectAsState(initial = null)

    var step by remember { mutableStateOf(OnboardingStep.TrackingTypeSelection) }
    var isHealthConnectInstalled by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf(SleepDetectionMode.AUTO) }

    // Which tracking types the user wants
    var sleepSelected by remember { mutableStateOf(true) }
    var nutritionSelected by remember { mutableStateOf(true) }
    var globalNetworkEnabled by remember { mutableStateOf(true) }
    var globalAiEnabled by remember { mutableStateOf(true) }

    var bedtimeRange by remember { mutableStateOf(TimeRange.BEDTIME) }
    var wakeupRange by remember { mutableStateOf(TimeRange.WAKEUP) }
    var awakeningsEnabled by remember { mutableStateOf(true) }
    var awakeningThresholdMinutes by remember { mutableIntStateOf(60) }
    var defaultAwakeMinutes by remember { mutableIntStateOf(15) }

    var permState by remember { mutableStateOf(PermissionState()) }
    var showTemplateEditor by remember { mutableStateOf(false) }

    // Nutrition index extraction state — driven by WorkManager
    val workInfos by NutritionIndexWorker.observeInfo(context).collectAsState(initial = emptyList())
    val workInfo = workInfos.firstOrNull()
    val extractionRunning = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED
    val extractionDone = workInfo?.state == WorkInfo.State.SUCCEEDED
    val extractionError = if (workInfo?.state == WorkInfo.State.FAILED)
        workInfo.outputData.getString(NutritionIndexWorker.KEY_ERROR) ?: "Unknown error"
    else null
    val extractionProgress = when {
        extractionDone -> workInfo?.outputData?.getInt(NutritionIndexWorker.KEY_RECORD_COUNT, 0) ?: 0
        extractionRunning -> workInfo?.progress?.getInt(NutritionIndexWorker.KEY_PROGRESS, 0) ?: 0
        else -> 0
    }
    val extractionTotal = when {
        extractionDone -> extractionProgress
        extractionRunning -> workInfo?.progress?.getInt(NutritionIndexWorker.KEY_TOTAL, -1) ?: -1
        else -> -1
    }
    val extractionProgressText = if (extractionTotal > 0 && extractionTotal != extractionProgress)
        "$extractionProgress / $extractionTotal" else "$extractionProgress"
    var showCancelExtractionDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current

    fun startExtraction() {
        NutritionIndexWorker.enqueue(context)
    }

    // Determine the previous step for back navigation
    val previousStep: OnboardingStep? = when (step) {
        OnboardingStep.TrackingTypeSelection -> null // first step — back blocked by activity
        OnboardingStep.HealthConnect -> OnboardingStep.TrackingTypeSelection
        OnboardingStep.AutoDetectionExplanation -> OnboardingStep.HealthConnect
        OnboardingStep.LoggingMode -> OnboardingStep.AutoDetectionExplanation
        OnboardingStep.AutoConfig -> OnboardingStep.LoggingMode
        OnboardingStep.ManualInfo -> OnboardingStep.LoggingMode
        OnboardingStep.Permissions -> when {
            sleepSelected && selectedMode == SleepDetectionMode.AUTO -> OnboardingStep.AutoConfig
            sleepSelected -> OnboardingStep.ManualInfo
            else -> OnboardingStep.HealthConnect
        }
        OnboardingStep.NutritionIndex -> OnboardingStep.Permissions
        OnboardingStep.Completion -> if (nutritionSelected) OnboardingStep.NutritionIndex else OnboardingStep.Permissions
    }

    // Handle back: go to previous step (activity-level callback blocks exit on first step)
    // Extraction runs as a WorkManager job — back navigation never cancels it
    BackHandler(enabled = previousStep != null) {
        step = previousStep!!
    }

    fun refreshHealthConnectStatus() {
        isHealthConnectInstalled = runCatching {
            context.packageManager.getPackageInfo("com.google.android.apps.healthdata", 0)
        }.isSuccess || runCatching {
            context.packageManager.getPackageInfo("com.google.android.healthconnect.controller", 0)
        }.isSuccess
    }

    fun refreshPermissions() {
        scope.launch { permState = loadPermissionState(context, healthConnectManager) }
    }

    LaunchedEffect(Unit) {
        refreshHealthConnectStatus()
        refreshPermissions()
    }

    // Auto-refresh permissions when returning from a permission dialog
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showTemplateEditor && manualTemplate != null) {
        val baseDate = LocalDate.of(2000, 1, 1)
        val initialTemplate = manualTemplate!!
        val initialBedtime = baseDate.atTime(
            initialTemplate.bedtimeOffsetMinutes / 60,
            initialTemplate.bedtimeOffsetMinutes % 60
        )

        val initialSegments = initialTemplate.segments.map { templateSegment ->
            SleepSegment(
                endTime = initialBedtime.plusMinutes(templateSegment.endOffsetMinutes.toLong()),
                sleepStage = templateSegment.sleepStage
            )
        }

        SleepLogEditor(
            title = "Set up your manual template",
            initialBedtime = initialBedtime,
            initialSegments = initialSegments,
            sleepStages = sleepStages,
            onSave = { bedtime, segments ->
                val newTemplate = SleepLogTemplate(
                    bedtimeOffsetMinutes = bedtime.hour * 60 + bedtime.minute,
                    segments = segments.map { segment ->
                        TemplateSegment(
                            startOffsetMinutes = 0,
                            endOffsetMinutes = Duration.between(bedtime, segment.endTime).toMinutes().toInt(),
                            sleepStage = segment.sleepStage
                        )
                    }
                )
                scope.launch {
                    userPreferencesRepository.saveManualTemplate(newTemplate)
                }
                showTemplateEditor = false
                step = OnboardingStep.Permissions
            },
            onCancel = { showTemplateEditor = false }
        )
        return
    }

    if (showCancelExtractionDialog) {
        AlertDialog(
            onDismissRequest = { showCancelExtractionDialog = false },
            title = { Text("Stop building food database?") },
            text = { Text("The database will stop building. You can rebuild it later from Settings.") },
            confirmButton = {
                TextButton(onClick = {
                    androidx.work.WorkManager.getInstance(context)
                        .cancelUniqueWork(NutritionIndexWorker.WORK_NAME)
                    showCancelExtractionDialog = false
                }) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelExtractionDialog = false }) { Text("Keep going") }
            }
        )
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Top progress banner — shown on all steps except NutritionIndex (which shows inline progress)
            // Clickable to show the stop/cancel dialog
            if (nutritionSelected && extractionRunning && step != OnboardingStep.NutritionIndex) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Building food database\u2026 $extractionProgressText foods  \u2014 tap to stop",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCancelExtractionDialog = true }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (step) {

                // ── Step 0: What do you want to track? ───────────────────
                OnboardingStep.TrackingTypeSelection -> {
                    Text("What do you want to track?", style = MaterialTheme.typography.headlineMedium)
                    Text("Select at least one category. You can change this later in Settings.")

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TrackingTypeRow(
                                label = TrackingType.SLEEP.displayName,
                                description = TrackingType.SLEEP.description,
                                checked = sleepSelected,
                                onCheckedChange = { sleepSelected = it }
                            )
                            HorizontalDivider()
                            TrackingTypeRow(
                                label = TrackingType.NUTRITION.displayName,
                                description = TrackingType.NUTRITION.description,
                                checked = nutritionSelected,
                                onCheckedChange = { nutritionSelected = it }
                            )
                            HorizontalDivider()
                            ToggleRow(
                                label = "Enable network features",
                                description = "Allow features that require network access across the app.",
                                checked = globalNetworkEnabled,
                                onCheckedChange = { enabled ->
                                    globalNetworkEnabled = enabled
                                    if (!enabled) globalAiEnabled = false
                                }
                            )
                            HorizontalDivider()
                            ToggleRow(
                                label = "Enable AI features",
                                description = "Master switch for AI-powered capabilities when they are added.",
                                checked = globalNetworkEnabled && globalAiEnabled,
                                enabled = globalNetworkEnabled,
                                onCheckedChange = { globalAiEnabled = it }
                            )
                        }
                    }

                    if (!sleepSelected && !nutritionSelected) {
                        Text(
                            "At least one tracking type is required.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                userPreferencesRepository.setSleepEnabled(sleepSelected)
                                userPreferencesRepository.setNutritionEnabled(nutritionSelected)
                                userPreferencesRepository.setGlobalNetworkEnabled(globalNetworkEnabled)
                                userPreferencesRepository.setGlobalAiEnabled(globalAiEnabled)
                            }
                            // Start extraction in background as soon as nutrition is selected
                            if (nutritionSelected) startExtraction()
                            // Skip Health Connect install screen if it's already installed
                            step = if (isHealthConnectInstalled) {
                                if (sleepSelected) OnboardingStep.AutoDetectionExplanation
                                else OnboardingStep.Permissions
                            } else {
                                OnboardingStep.HealthConnect
                            }
                        },
                        enabled = sleepSelected || nutritionSelected,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue")
                    }
                }

                // ── Step 1: Health Connect ────────────────────────────────
                OnboardingStep.HealthConnect -> {
                    Text("Welcome to SleepTracker", style = MaterialTheme.typography.headlineMedium)
                    Text("SleepTracker uses Health Connect to save and read your confirmed sleep and nutrition logs.")

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (isHealthConnectInstalled) "Health Connect is installed."
                                else "Health Connect is not installed yet.",
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!isHealthConnectInstalled) {
                                Button(onClick = onInstallHealthConnect, modifier = Modifier.fillMaxWidth()) {
                                    Text("Install Health Connect")
                                }
                                OutlinedButton(
                                    onClick = { refreshHealthConnectStatus() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("I installed it")
                                }
                            }
                        }
                    }

                    // Skip sleep-specific step if only nutrition selected
                    Button(
                        onClick = {
                            step = if (sleepSelected) OnboardingStep.AutoDetectionExplanation
                                   else OnboardingStep.Permissions
                        },
                        enabled = isHealthConnectInstalled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue")
                    }
                }

                // ── Step 2: Auto Detection Explanation ────────────────────
                OnboardingStep.AutoDetectionExplanation -> {
                    Text("How auto sleep detection works", style = MaterialTheme.typography.headlineMedium)

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "When Auto Tracking is on, SleepTracker watches for screen lock/unlock patterns " +
                                    "during your sleep window and builds a suggested sleep session for that day."
                            )
                            Text(
                                "It keeps tracking until you log sleep for that day, so if you wake up, " +
                                    "go back to sleep, or have a rough night, it can still help build a useful draft."
                            )
                            Text(
                                "Nothing is written to Health Connect automatically. You review and confirm " +
                                    "the times first, then we save exactly what you approve."
                            )
                        }
                    }

                    Text("In short: helpful automation, but you're always in control.")

                    Button(onClick = { step = OnboardingStep.LoggingMode }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue")
                    }
                }

                // ── Step 3: Logging Mode ──────────────────────────────────
                OnboardingStep.LoggingMode -> {
                    Text("How do you want to log sleep?", style = MaterialTheme.typography.headlineMedium)

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { selectedMode = SleepDetectionMode.AUTO }
                            ) {
                                RadioButton(
                                    selected = selectedMode == SleepDetectionMode.AUTO,
                                    onClick = { selectedMode = SleepDetectionMode.AUTO }
                                )
                                Column(modifier = Modifier.padding(start = 4.dp)) {
                                    Text("Auto detection", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "SleepTracker watches your screen to build a suggested sleep draft.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { selectedMode = SleepDetectionMode.MANUAL }
                            ) {
                                RadioButton(
                                    selected = selectedMode == SleepDetectionMode.MANUAL,
                                    onClick = { selectedMode = SleepDetectionMode.MANUAL }
                                )
                                Column(modifier = Modifier.padding(start = 4.dp)) {
                                    Text("Manual logging", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Fill in a sleep template yourself each day.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            step = if (selectedMode == SleepDetectionMode.AUTO) {
                                OnboardingStep.AutoConfig
                            } else {
                                OnboardingStep.ManualInfo
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue")
                    }
                }

                // ── Step 4: Auto Config ───────────────────────────────────
                OnboardingStep.AutoConfig -> {
                    Text("Set up automatic detection", style = MaterialTheme.typography.headlineMedium)
                    Text("Tell SleepTracker when you typically go to bed and wake up. It will only record screen events during these windows.")

                    Text("Bedtime window", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "The time range when you usually go to bed. The last time you lock your phone inside this window becomes your detected bedtime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TimeRangeSetting(
                        label = "",
                        range = bedtimeRange,
                        onRangeChange = { bedtimeRange = it }
                    )
                    Text("Wake-up window", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "The time range when you usually wake up. The first time you unlock your phone inside this window becomes your detected wake-up time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TimeRangeSetting(
                        label = "",
                        range = wakeupRange,
                        onRangeChange = { wakeupRange = it }
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Switch(checked = awakeningsEnabled, onCheckedChange = { awakeningsEnabled = it })
                        Column {
                            Text("Track brief night-time wake-ups", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text(
                                "Records short periods when you check your phone during the night, adding awake segments to your sleep session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (awakeningsEnabled) {
                        OutlinedTextField(
                            value = awakeningThresholdMinutes.toString(),
                            onValueChange = { awakeningThresholdMinutes = it.toIntOrNull() ?: awakeningThresholdMinutes },
                            label = { Text("Min wake duration to record (minutes)") },
                            supportingText = { Text("Wake-ups shorter than this are ignored; longer ones are added as awake segments.") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = defaultAwakeMinutes.toString(),
                        onValueChange = { defaultAwakeMinutes = it.toIntOrNull() ?: defaultAwakeMinutes },
                        label = { Text("Time awake before falling asleep (minutes)") },
                        supportingText = { Text("Estimated time between locking your phone and actually falling asleep.") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                userPreferencesRepository.setSleepDetectionMode(SleepDetectionMode.AUTO)
                                userPreferencesRepository.setBedtimeWindow(bedtimeRange)
                                userPreferencesRepository.setWakeupWindow(wakeupRange)
                                userPreferencesRepository.setAwakeningLoggingEnabled(awakeningsEnabled)
                                userPreferencesRepository.setAwakeningThreshold(awakeningThresholdMinutes)
                                userPreferencesRepository.setDefaultAwakeToAsleepMinutes(defaultAwakeMinutes)
                            }
                            step = OnboardingStep.Permissions
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save and continue")
                    }
                }

                // ── Step 5: Manual Info ───────────────────────────────────
                OnboardingStep.ManualInfo -> {
                    Text("Manual logging setup", style = MaterialTheme.typography.headlineMedium)
                    Text("You'll first configure a template for future days, then you can adjust each day as needed.")

                    Button(
                        onClick = {
                            scope.launch { userPreferencesRepository.setSleepDetectionMode(SleepDetectionMode.MANUAL) }
                            showTemplateEditor = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Configure template")
                    }
                }

                // ── Step 6: Permissions ───────────────────────────────────
                OnboardingStep.Permissions -> {
                    Text("Permissions", style = MaterialTheme.typography.headlineMedium)
                    Text("Grant only the permissions you need. You can update these later in Settings → Permissions.")

                    // General permissions
                    PermissionCard(
                        title = "Notifications",
                        reason = "Needed to run reliable background tracking reminders.",
                        granted = permState.notificationsGranted,
                        onGrant = { onRequestNotificationPermission() }
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PermissionCard(
                            title = "Exact Alarms",
                            reason = "Needed for precise service start/stop scheduling. App falls back if denied.",
                            granted = permState.exactAlarmGranted,
                            onGrant = { onRequestExactAlarmPermission() }
                        )
                    }

                    // Build combined HC permission set — if both trackings are on, request all at once
                    val combinedHcPermissions = buildSet {
                        if (sleepSelected) {
                            addAll(healthConnectManager.sleepPermissions)
                        }
                        if (nutritionSelected) {
                            addAll(healthConnectManager.nutritionPermissions)
                        }
                    }

                    // Sleep-specific permissions
                    if (sleepSelected) {
                        HorizontalDivider()
                        Text("Sleep", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

                        PermissionCard(
                            title = "Sleep — Read & Write",
                            reason = "Required to save and read sleep sessions in Health Connect.",
                            granted = permState.sleepWriteGranted && permState.sleepReadGranted,
                            onGrant = {
                                onRequestHealthPermissions(combinedHcPermissions)
                                refreshPermissions()
                            }
                        )

                        if (permState.showSensors) {
                            PermissionCard(
                                title = "Other Sensors (GrapheneOS)",
                                reason = "Required on this device for screen state detection.",
                                granted = permState.sensorsGranted,
                                onGrant = { onRequestOtherSensorsPermission() }
                            )
                        }
                    }

                    // Nutrition-specific permissions
                    if (nutritionSelected) {
                        HorizontalDivider()
                        Text("Nutrition", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

                        PermissionCard(
                            title = "Nutrition — Read & Write",
                            reason = "Required to save and read nutrition records in Health Connect.",
                            granted = permState.nutritionWriteGranted && permState.nutritionReadGranted,
                            onGrant = {
                                onRequestHealthPermissions(combinedHcPermissions)
                                refreshPermissions()
                            }
                        )
                    }

                    val allRequired = permState.isLoaded &&
                        permState.notificationsGranted &&
                        permState.exactAlarmGranted &&
                        (!sleepSelected || (permState.sleepWriteGranted && (!permState.showSensors || permState.sensorsGranted))) &&
                        (!nutritionSelected || permState.nutritionWriteGranted)

                    OutlinedButton(
                        onClick = { refreshPermissions() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Refresh permission status")
                    }

                    Button(
                        onClick = {
                            step = if (nutritionSelected) OnboardingStep.NutritionIndex
                                   else OnboardingStep.Completion
                        },
                        enabled = allRequired,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue")
                    }
                }

                // ── Step 7: Nutrition Index ───────────────────────────────
                OnboardingStep.NutritionIndex -> {
                    Text("Building food database", style = MaterialTheme.typography.headlineMedium)
                    Text("We're indexing the bundled food dataset for fast search. This only happens once.")

                    when {
                        extractionRunning -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("$extractionProgressText foods indexed so far…")
                            TextButton(
                                onClick = { showCancelExtractionDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Stop building") }
                        }
                        extractionDone -> {
                            Text(
                                "$extractionProgress foods ready.",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        extractionError != null -> {
                            Text(
                                "Error: $extractionError",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(onClick = { startExtraction() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Retry")
                            }
                        }
                        else -> {
                            Button(onClick = { startExtraction() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Start indexing")
                            }
                        }
                    }

                    Text(
                        "Data from OpenNutrition (opennutrition.app)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = { step = OnboardingStep.Completion },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (extractionDone) "Continue" else if (extractionRunning) "Continue in background" else "Skip for now")
                    }
                }

                // ── Step 8: Done ──────────────────────────────────────────
                OnboardingStep.Completion -> {
                    Text("You're all set", style = MaterialTheme.typography.headlineMedium)
                    Text("Health Connect is ready, your preferences are configured, and permissions are complete.")

                    Button(
                        onClick = {
                            scope.launch {
                                userPreferencesRepository.setOnboardingCompleted(true)
                            }
                            onFinish()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Let's go!")
                    }
                }
            }

            HorizontalDivider()
        }
        } // end inner scrollable Column
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun TrackingTypeRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(top = 2.dp)
        )
        Column {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
