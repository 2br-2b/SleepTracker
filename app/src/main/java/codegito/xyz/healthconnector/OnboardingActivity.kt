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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import codegito.xyz.healthconnector.ui.SleepLogEditor
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

        setContent {
            SleepTrackerTheme {
                OnboardingFlow(
                    userPreferencesRepository = userPreferencesRepository,
                    healthConnectManager = healthConnectManager,
                    onInstallHealthConnect = { openHealthConnectInstall() },
                    onRequestHealthPermissions = { requestHealthPermissions.launch(healthConnectManager.permissions) },
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
    HealthConnect,
    AutoDetectionExplanation,
    LoggingMode,
    AutoConfig,
    ManualInfo,
    Permissions,
    Completion
}

@Composable
private fun OnboardingFlow(
    userPreferencesRepository: UserPreferencesRepository,
    healthConnectManager: HealthConnectManager,
    onInstallHealthConnect: () -> Unit,
    onRequestHealthPermissions: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestOtherSensorsPermission: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sleepStages by userPreferencesRepository.sleepStages.collectAsState(initial = emptyList())
    val manualTemplate by userPreferencesRepository.manualSleepTemplate.collectAsState(initial = null)

    var step by remember { mutableStateOf(OnboardingStep.HealthConnect) }
    var isHealthConnectInstalled by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf(SleepDetectionMode.AUTO) }

    var bedtimeStart by remember { mutableIntStateOf(21 * 60) }
    var bedtimeEnd by remember { mutableIntStateOf(2 * 60) }
    var wakeupEnd by remember { mutableIntStateOf(12 * 60) }
    var awakeningsEnabled by remember { mutableStateOf(true) }
    var awakeningThresholdMinutes by remember { mutableIntStateOf(60) }
    var defaultAwakeMinutes by remember { mutableIntStateOf(15) }

    var hasHealthPermissions by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) }
    var showSensorsPermission by remember { mutableStateOf(false) }
    var hasSensorsPermission by remember { mutableStateOf(true) }
    var hasExactAlarmPermission by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.S) }

    var showTemplateEditor by remember { mutableStateOf(false) }

    fun refreshHealthConnectStatus() {
        isHealthConnectInstalled = runCatching {
            context.packageManager.getPackageInfo("com.google.android.apps.healthdata", 0)
        }.isSuccess || runCatching {
            context.packageManager.getPackageInfo("com.google.android.healthconnect.controller", 0)
        }.isSuccess
    }

    fun refreshPermissionState() {
        scope.launch {
            hasHealthPermissions = healthConnectManager.hasPermissions()
        }
        hasNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        showSensorsPermission = runCatching {
            context.packageManager.getPackageInfo("app.grapheneos.apps", 0)
            true
        }.getOrElse { false }

        hasSensorsPermission = if (showSensorsPermission) {
            ContextCompat.checkSelfPermission(context, "android.permission.OTHER_SENSORS") == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        hasExactAlarmPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }
    }

    LaunchedEffect(Unit) {
        refreshHealthConnectStatus()
        if (isHealthConnectInstalled) {
            step = OnboardingStep.AutoDetectionExplanation
        }
        refreshPermissionState()
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

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (step) {
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

                    Button(
                        onClick = { step = OnboardingStep.AutoDetectionExplanation },
                        enabled = isHealthConnectInstalled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue")
                    }
                }

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

                OnboardingStep.LoggingMode -> {
                    Text("How do you want to log sleep?", style = MaterialTheme.typography.headlineMedium)

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(
                                    checked = selectedMode == SleepDetectionMode.AUTO,
                                    onCheckedChange = { selectedMode = SleepDetectionMode.AUTO }
                                )
                                Text("Auto detection")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(
                                    checked = selectedMode == SleepDetectionMode.MANUAL,
                                    onCheckedChange = { selectedMode = SleepDetectionMode.MANUAL }
                                )
                                Text("Manual logging")
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

                OnboardingStep.AutoConfig -> {
                    Text("Set up automatic detection", style = MaterialTheme.typography.headlineMedium)

                    TimeConfigRow(
                        label = "Bedtime window start",
                        minutes = bedtimeStart,
                        onMinutesSelected = { bedtimeStart = it }
                    )
                    TimeConfigRow(
                        label = "Bedtime window end",
                        minutes = bedtimeEnd,
                        onMinutesSelected = { bedtimeEnd = it }
                    )
                    TimeConfigRow(
                        label = "Latest wake-up time",
                        minutes = wakeupEnd,
                        onMinutesSelected = { wakeupEnd = it }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = awakeningsEnabled, onCheckedChange = { awakeningsEnabled = it })
                        Text("Track waking up + falling asleep")
                    }

                    if (awakeningsEnabled) {
                        OutlinedTextField(
                            value = awakeningThresholdMinutes.toString(),
                            onValueChange = { awakeningThresholdMinutes = it.toIntOrNull() ?: awakeningThresholdMinutes },
                            label = { Text("Wake/fall-asleep threshold (minutes)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = defaultAwakeMinutes.toString(),
                        onValueChange = { defaultAwakeMinutes = it.toIntOrNull() ?: defaultAwakeMinutes },
                        label = { Text("Default time from lock to sleep (minutes)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Track naps is disabled.", style = MaterialTheme.typography.bodyMedium)

                    Button(
                        onClick = {
                            scope.launch {
                                userPreferencesRepository.setSleepDetectionMode(SleepDetectionMode.AUTO)
                                userPreferencesRepository.setBedtimeWindow(bedtimeStart, bedtimeEnd)
                                userPreferencesRepository.setWakeupWindow(5 * 60, wakeupEnd)
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

                OnboardingStep.Permissions -> {
                    Text("Permissions", style = MaterialTheme.typography.headlineMedium)
                    Text("Grant only the permissions you need. You can request each one below.")

                    PermissionRow(
                        title = "Health Connect read/write",
                        reason = "Needed to read/write your sleep sessions and nutrition records.",
                        granted = hasHealthPermissions,
                        onRequest = {
                            onRequestHealthPermissions()
                            refreshPermissionState()
                        }
                    )

                    PermissionRow(
                        title = "Notifications",
                        reason = "Needed to run reliable background tracking reminders.",
                        granted = hasNotificationPermission,
                        onRequest = {
                            onRequestNotificationPermission()
                            refreshPermissionState()
                        }
                    )

                    if (showSensorsPermission) {
                        PermissionRow(
                            title = "Sensors (GrapheneOS)",
                            reason = "Needed on this device profile for detection reliability.",
                            granted = hasSensorsPermission,
                            onRequest = {
                                onRequestOtherSensorsPermission()
                                refreshPermissionState()
                            }
                        )
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PermissionRow(
                            title = "Exact alarms",
                            reason = "Needed for precise service start/stop scheduling. App will fall back if denied.",
                            granted = hasExactAlarmPermission,
                            onRequest = {
                                onRequestExactAlarmPermission()
                                refreshPermissionState()
                            }
                        )
                    }

                    val allRequiredGranted = hasHealthPermissions && hasNotificationPermission && (!showSensorsPermission || hasSensorsPermission) && hasExactAlarmPermission

                    OutlinedButton(
                        onClick = { refreshPermissionState() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Refresh permission status")
                    }

                    Button(
                        onClick = { step = OnboardingStep.Completion },
                        enabled = allRequiredGranted,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue")
                    }
                }

                OnboardingStep.Completion -> {
                    Text("You're all set", style = MaterialTheme.typography.headlineMedium)
                    Text("Health Connect is ready, your logging mode is configured, and permissions are complete.")

                    Button(
                        onClick = {
                            scope.launch {
                                userPreferencesRepository.setOnboardingCompleted(true)
                            }
                            onFinish()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("let's go!")
                    }
                }
            }

            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeConfigRow(
    label: String,
    minutes: Int,
    onMinutesSelected: (Int) -> Unit
) {
    val hour = (minutes / 60).coerceIn(0, 23)
    val minute = (minutes % 60).coerceIn(0, 59)
    var showTimePicker by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(String.format("%02d:%02d", hour, minute), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = { showTimePicker = true }) {
                Text("Pick time")
            }
        }
    }

    if (showTimePicker) {
        OnboardingTimePickerDialog(
            title = label,
            initialHour = hour,
            initialMinute = minute,
            onConfirm = { selectedHour, selectedMinute ->
                onMinutesSelected(selectedHour * 60 + selectedMinute)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select $title") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
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

@Composable
private fun PermissionRow(
    title: String,
    reason: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            Text(if (granted) "Granted" else "Not granted")
            if (!granted) {
                Button(
                    onClick = onRequest,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Grant")
                }
            }
        }
    }
}
