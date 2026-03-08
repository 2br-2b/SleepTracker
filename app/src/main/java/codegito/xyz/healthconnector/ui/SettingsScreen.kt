package codegito.xyz.healthconnector.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import codegito.xyz.healthconnector.HealthConnectManager
import codegito.xyz.healthconnector.NotificationHelper
import codegito.xyz.healthconnector.SleepTrackingService
import codegito.xyz.healthconnector.data.UserPreferencesRepository
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
    onSleepSettings: () -> Unit,
    onNutritionSettings: () -> Unit,
    // Deprecated callbacks kept for binary compat — not used in new flow
    onEditSleepStages: () -> Unit = {},
    onAutoSleepSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasHealthConnectPermissions by remember { mutableStateOf<Boolean?>(null) }

    val developerModeEnabled by userPreferencesRepository.developerModeEnabled.collectAsState(initial = false)
    val amoledPitchBlackEnabled by userPreferencesRepository.amoledPitchBlackEnabled.collectAsState(initial = false)
    val showAdvancedSettings by userPreferencesRepository.showAdvancedSettings.collectAsState(initial = false)

    var versionTapCount by remember { mutableIntStateOf(0) }
    var isServiceActive by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { hasHealthConnectPermissions = healthConnectManager.hasPermissions() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(developerModeEnabled) {
        if (!developerModeEnabled) return@LaunchedEffect
        while (true) {
            isServiceActive = isServiceRunning(context, SleepTrackingService::class.java)
            kotlinx.coroutines.delay(2000)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Health Connect ────────────────────────────────────────────
            SectionHeader("Health Connect")

            if (hasHealthConnectPermissions == false) {
                Button(onClick = onManagePermissions, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant Health Connect Permissions")
                }
            } else {
                OutlinedButton(onClick = onManagePermissions, modifier = Modifier.fillMaxWidth()) {
                    Text("Manage Health Connect Permissions")
                }
            }

            OutlinedButton(
                onClick = {
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
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Health Connect App")
            }

            HorizontalDivider()

            // ── Sub-pages ─────────────────────────────────────────────────
            SectionHeader("Settings")

            ListItem(
                headlineContent = { Text("Sleep") },
                supportingContent = { Text("Detection, reminders, stages, rollover time") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = Modifier.clickable { onSleepSettings() }
            )

            ListItem(
                headlineContent = { Text("Nutrition") },
                supportingContent = { Text("Food database, date range, meal windows") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = Modifier.clickable { onNutritionSettings() }
            )

            HorizontalDivider()

            // ── Appearance ────────────────────────────────────────────────
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

            // ── Advanced ──────────────────────────────────────────────────
            SectionHeader("Advanced")

            ListItem(
                headlineContent = { Text("Show advanced settings") },
                supportingContent = { Text("Reveal extra options on the Sleep and Nutrition settings pages.") },
                trailingContent = {
                    Checkbox(
                        checked = showAdvancedSettings,
                        onCheckedChange = { enabled ->
                            scope.launch { userPreferencesRepository.setShowAdvancedSettings(enabled) }
                        }
                    )
                },
                modifier = Modifier.clickable {
                    scope.launch { userPreferencesRepository.setShowAdvancedSettings(!showAdvancedSettings) }
                }
            )

            // ── Developer ─────────────────────────────────────────────────
            if (developerModeEnabled) {
                HorizontalDivider()
                SectionHeader("Developer Tools")

                ListItem(
                    headlineContent = { Text("Service Status") },
                    supportingContent = { Text(if (isServiceActive) "Running (Foreground)" else "Stopped") },
                    trailingContent = {
                        val color = if (isServiceActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                        Surface(modifier = Modifier.size(12.dp), shape = CircleShape, color = color) {}
                    }
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

            val appVersion = "1.0"
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
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = 0, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Hour") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
