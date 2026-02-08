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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    healthConnectManager: HealthConnectManager, // Pass healthConnectManager
    userPreferencesRepository: UserPreferencesRepository, // Pass UserPreferencesRepository
    onManagePermissions: () -> Unit,
    onEditSleepStages: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // State for permissions
    var hasHealthConnectPermissions by remember { mutableStateOf(false) }
    var hasOtherSensorsPermission by remember { mutableStateOf(false) }

    // State for rollover time
    val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)
    var showRolloverTimePicker by remember { mutableStateOf(false) }

    // Check permissions on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                 scope.launch {
                    hasHealthConnectPermissions = healthConnectManager.hasPermissions()
                }
                val permissionState = ContextCompat.checkSelfPermission(context, "android.permission.OTHER_SENSORS")
                hasOtherSensorsPermission = permissionState == PermissionChecker.PERMISSION_GRANTED
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
                        // Fallback to open app store or simpler settings
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

            // GrapheneOS OTHER_SENSORS
            if (hasOtherSensorsPermission) {
               Button(
                   onClick = {}, 
                   enabled = false,
                   modifier = Modifier.fillMaxWidth()
               ) {
                   Text("Sensors Permission Granted")
               }
            } else {
                Button(
                    onClick = {
                        val activity = context as? Activity
                        activity?.requestPermissions(arrayOf("android.permission.OTHER_SENSORS"), 1001)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Request Sensors Permission")
                }
                
                // Fallback for GrapheneOS manual toggle or if request doesn't work
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open App Info (Toggle Sensors)")
                }
            }

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
