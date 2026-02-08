package codegito.xyz.healthconnector.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.foundation.shape.CircleShape
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
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import android.widget.Toast
import android.app.ActivityManager
import codegito.xyz.healthconnector.NotificationHelper
import codegito.xyz.healthconnector.SleepTrackingService
import java.time.LocalDate

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

    var hasHealthConnectPermissions by remember { mutableStateOf(false) }

    val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)
    val developerModeEnabled by userPreferencesRepository.developerModeEnabled.collectAsState(initial = false)
    var showRolloverTimePicker by remember { mutableStateOf(false) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    
    // Check if service is running
    var isServiceActive by remember { mutableStateOf(false) }
    
    LaunchedEffect(developerModeEnabled) {
        if (!developerModeEnabled) return@LaunchedEffect
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
            
            if (!hasHealthConnectPermissions) {
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
