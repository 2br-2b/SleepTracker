package codegito.xyz.healthconnector.ui

import android.app.ActivityManager
import android.content.Context
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
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.NotificationHelper
import codegito.xyz.healthconnector.SleepTrackingService
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onPermissions: () -> Unit,
    onSleepSettings: () -> Unit,
    onNutritionSettings: () -> Unit,
    // Kept for binary compat — not used in new flow
    healthConnectManager: codegito.xyz.healthconnector.HealthConnectManager? = null,
    onManagePermissions: () -> Unit = onPermissions,
    onEditSleepStages: () -> Unit = {},
    onAutoSleepSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val developerModeEnabled by userPreferencesRepository.developerModeEnabled.collectAsState(initial = false)
    val amoledPitchBlackEnabled by userPreferencesRepository.amoledPitchBlackEnabled.collectAsState(initial = false)
    val showAdvancedSettings by userPreferencesRepository.showAdvancedSettings.collectAsState(initial = false)
    val sleepEnabled by userPreferencesRepository.sleepEnabled.collectAsState(initial = true)
    val nutritionEnabled by userPreferencesRepository.nutritionEnabled.collectAsState(initial = true)
    val globalNetworkEnabled by userPreferencesRepository.globalNetworkEnabled.collectAsState(initial = true)
    val effectiveGlobalAiEnabled by userPreferencesRepository.effectiveGlobalAiEnabled.collectAsState(initial = true)
    val historyDays by userPreferencesRepository.historyDays.collectAsState(initial = 7)
    val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)

    var versionTapCount by remember { mutableIntStateOf(0) }
    var isServiceActive by remember { mutableStateOf(false) }
    var showRolloverPicker by remember { mutableStateOf(false) }

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
            // ── Permissions ───────────────────────────────────────────────
            SectionHeader("Permissions")

            ListItem(
                headlineContent = { Text("Permissions") },
                supportingContent = { Text("Health Connect, notifications, sensors, alarms") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = Modifier.clickable { onPermissions() }
            )

            HorizontalDivider()

            // ── Tracking categories ───────────────────────────────────────
            SectionHeader("Tracking")

            ListItem(
                headlineContent = { Text("Sleep") },
                supportingContent = {
                    Text(
                        if (sleepEnabled) "Detection, reminders, stages, rollover time"
                        else "Disabled — tap to configure"
                    )
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = Modifier.clickable { onSleepSettings() }
            )

            ListItem(
                headlineContent = { Text("Nutrition") },
                supportingContent = {
                    Text(
                        if (nutritionEnabled) "Food database, date range, meal windows"
                        else "Disabled — tap to configure"
                    )
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = Modifier.clickable { onNutritionSettings() }
            )

            HorizontalDivider()

            // ── Connectivity & AI ────────────────────────────────────────
            SectionHeader("Connectivity")

            ListItem(
                headlineContent = { Text("Enable network features") },
                supportingContent = { Text("Globally allow app features that require network access.") },
                trailingContent = {
                    Switch(
                        checked = globalNetworkEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { userPreferencesRepository.setGlobalNetworkEnabled(enabled) }
                        }
                    )
                }
            )

            if (globalNetworkEnabled) {
                ListItem(
                    headlineContent = { Text("Enable AI features") },
                    supportingContent = { Text("Master switch for AI-powered capabilities when they are added.") },
                    trailingContent = {
                        Switch(
                            checked = effectiveGlobalAiEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { userPreferencesRepository.setGlobalAiEnabled(enabled) }
                            }
                        )
                    }
                )
            }

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

            // ── General ───────────────────────────────────────────────────
            SectionHeader("General")

            ListItem(
                headlineContent = { Text("Day cutover time") },
                supportingContent = {
                    Text(
                        "You can't always get to bed before midnight. This controls when one day " +
                        "ends and the next begins for grouping sleep and food data. It only affects " +
                        "how this app displays entries — data already saved in Health Connect is not changed."
                    )
                },
                trailingContent = {
                    Text(
                        LocalTime.of(rolloverHour, 0).format(DateTimeFormatter.ofPattern("h:mm a")),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.clickable { showRolloverPicker = true }
            )

            ListItem(
                headlineContent = { Text("Days to show") },
                supportingContent = { Text("$historyDays days back — applies to sleep history, food log, and data retention") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { scope.launch { userPreferencesRepository.setHistoryDays((historyDays - 1).coerceAtLeast(1)) } }
                        ) { Text("-") }
                        OutlinedButton(
                            onClick = { scope.launch { userPreferencesRepository.setHistoryDays((historyDays + 1).coerceAtMost(30)) } }
                        ) { Text("+") }
                    }
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

    if (showRolloverPicker) {
        AppTimePickerDialog(
            initialHour = rolloverHour,
            initialMinute = 0,
            onConfirm = { hour, _ ->
                scope.launch { userPreferencesRepository.setRolloverHour(hour) }
                showRolloverPicker = false
            },
            onDismiss = { showRolloverPicker = false }
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
