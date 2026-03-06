package codegito.xyz.healthconnector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.SleepDetectionMode
import codegito.xyz.healthconnector.data.model.SleepLogTemplate
import codegito.xyz.healthconnector.data.model.TemplateSegment
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SleepTrackerTheme {
                OnboardingFlow(
                    userPreferencesRepository = userPreferencesRepository,
                    healthConnectManager = healthConnectManager,
                    onInstallHealthConnect = { openHealthConnectInstall() },
                    onRequestPermissions = {
                        requestHealthPermissions.launch(healthConnectManager.permissions)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (shouldShowOtherSensorsPermission()) {
                            requestOtherSensorsPermission.launch(Manifest.permission.OTHER_SENSORS)
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
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
        )

        runCatching { startActivity(marketIntent) }
            .onFailure { startActivity(webIntent) }
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
    onRequestPermissions: () -> Unit,
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

    var showTemplateEditor by remember { mutableStateOf(false) }

    fun refreshHealthConnectStatus() {
        isHealthConnectInstalled = runCatching {
            context.packageManager.getPackageInfo("com.google.android.apps.healthdata", 0)
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
            ContextCompat.checkSelfPermission(context, Manifest.permission.OTHER_SENSORS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    LaunchedEffect(Unit) {
        refreshHealthConnectStatus()
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
                    Text("SleepTracker uses Health Connect to save and read your confirmed sleep sessions.")

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
                    Text("SleepTracker watches screen lock and unlock events in your sleep window.")
                    Text("The last phone lock in your bedtime range is treated as bedtime.")
                    Text("The first unlock before your wake-up limit is treated as wake-up.")

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

                    OutlinedTextField(
                        value = bedtimeStart.toString(),
                        onValueChange = { bedtimeStart = it.toIntOrNull() ?: bedtimeStart },
                        label = { Text("When do you usually go to sleep between? (start, minutes after midnight)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bedtimeEnd.toString(),
                        onValueChange = { bedtimeEnd = it.toIntOrNull() ?: bedtimeEnd },
                        label = { Text("When do you usually go to sleep between? (end, minutes after midnight)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = wakeupEnd.toString(),
                        onValueChange = { wakeupEnd = it.toIntOrNull() ?: wakeupEnd },
                        label = { Text("When's the latest you will wake up? (minutes after midnight)") },
                        modifier = Modifier.fillMaxWidth()
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
                    Text("Tap request permissions, then return here to confirm all permissions are granted.")

                    PermissionRow(
                        title = "Health Connect read/write",
                        reason = "Needed to read your sleep history and save confirmed sessions.",
                        granted = hasHealthPermissions
                    )

                    PermissionRow(
                        title = "Notifications",
                        reason = "Needed to run reliable background tracking reminders.",
                        granted = hasNotificationPermission
                    )

                    if (showSensorsPermission) {
                        PermissionRow(
                            title = "Sensors (GrapheneOS)",
                            reason = "Needed on this device profile for detection reliability.",
                            granted = hasSensorsPermission
                        )
                    }

                    Button(
                        onClick = {
                            onRequestPermissions()
                            refreshPermissionState()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Request permissions")
                    }

                    val allRequiredGranted = hasHealthPermissions && hasNotificationPermission && (!showSensorsPermission || hasSensorsPermission)
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

@Composable
private fun PermissionRow(
    title: String,
    reason: String,
    granted: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            Text(if (granted) "Granted" else "Not granted")
        }
    }
}
