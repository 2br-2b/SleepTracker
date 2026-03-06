package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.SleepSegment
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.SleepDetectionMode
import codegito.xyz.healthconnector.data.model.SleepLogTemplate
import codegito.xyz.healthconnector.data.model.TemplateSegment
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import codegito.xyz.healthconnector.MainActivity
import codegito.xyz.healthconnector.NotificationHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoSleepSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit,
    onAdvancedSettings: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val detectionMode by userPreferencesRepository.sleepDetectionMode.collectAsState(initial = SleepDetectionMode.AUTO)
    val bedtimeStart by userPreferencesRepository.bedtimeWindowStart.collectAsState(initial = 21 * 60)
    val bedtimeEnd by userPreferencesRepository.bedtimeWindowEnd.collectAsState(initial = 2 * 60)
    val wakeupStart by userPreferencesRepository.wakeupWindowStart.collectAsState(initial = 5 * 60)
    val wakeupEnd by userPreferencesRepository.wakeupWindowEnd.collectAsState(initial = 12 * 60)
    val awakeningEnabled by userPreferencesRepository.awakeningLoggingEnabled.collectAsState(initial = true)
    val defaultAwakeToAsleep by userPreferencesRepository.defaultAwakeToAsleepMinutes.collectAsState(initial = 15)
    val manualTemplateState by userPreferencesRepository.manualSleepTemplate.collectAsState(initial = null)
    val sleepStages by userPreferencesRepository.sleepStages.collectAsState(initial = emptyList())
    
    val reminderFirstUnlock by userPreferencesRepository.reminderFirstUnlockEnabled.collectAsState(initial = true)
    val reminderDeadlineLoud by userPreferencesRepository.reminderDeadlineLoudEnabled.collectAsState(initial = true)
    val reminderDeadlineSilent by userPreferencesRepository.reminderDeadlineSilentEnabled.collectAsState(initial = true)

    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var hasOtherSensorsPermission by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val permissionState = androidx.core.content.ContextCompat.checkSelfPermission(context, "android.permission.OTHER_SENSORS")
                hasOtherSensorsPermission = permissionState == androidx.core.content.PermissionChecker.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showBedtimePicker by remember { mutableStateOf<Boolean?>(null) }
    var showWakeupPicker by remember { mutableStateOf<Boolean?>(null) }
    var showTemplateEditor by remember { mutableStateOf(false) }

    val currentTemplate = manualTemplateState
    if (showTemplateEditor && currentTemplate != null) {
        val baseDate = LocalDate.of(2000, 1, 1)
        val initialBedtime = baseDate.atTime(currentTemplate.bedtimeOffsetMinutes / 60, currentTemplate.bedtimeOffsetMinutes % 60)
        val initialSegments = currentTemplate.segments.map { ts ->
            SleepSegment(
                endTime = initialBedtime.plusMinutes(ts.endOffsetMinutes.toLong()),
                sleepStage = ts.sleepStage
            )
        }

        SleepLogEditor(
            title = "Edit Manual Sleep Template",
            initialBedtime = initialBedtime,
            initialSegments = initialSegments,
            sleepStages = sleepStages,
            onSave = { bedtime, segments ->
                val newBedtimeOffset = bedtime.hour * 60 + bedtime.minute
                val newTemplate = SleepLogTemplate(
                    bedtimeOffsetMinutes = newBedtimeOffset,
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
                    // Reschedule lifecycle and check if we should start/stop now
                    NotificationHelper.refreshServiceState(context, userPreferencesRepository)
                }
                showTemplateEditor = false
            },
            onCancel = { showTemplateEditor = false }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Auto-Detect Sleep") },
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
                // 1. Sleep Detection Mode
                SectionHeader("Default template")
                Text("When you start logging a new day, the app will provide a default template. This can either be automatically generated based on when you lock and unlock your phone or start as a template. Either way, you will be able to edit this before saving it to Health Connect.", style = MaterialTheme.typography.bodySmall)
                Column {
                    val context = LocalContext.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = detectionMode == SleepDetectionMode.AUTO,
                            onClick = { 
                                scope.launch { 
                                    userPreferencesRepository.setSleepDetectionMode(SleepDetectionMode.AUTO)
                                    // Reschedule lifecycle and check if we should start/stop now
                                    NotificationHelper.refreshServiceState(context, userPreferencesRepository)
                                } 
                            }
                        )
                        Text("Auto-detect")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = detectionMode == SleepDetectionMode.MANUAL,
                            onClick = { 
                                scope.launch { 
                                    userPreferencesRepository.setSleepDetectionMode(SleepDetectionMode.MANUAL)
                                    // Ensure service is stopped immediately
                                    NotificationHelper.refreshServiceState(context, userPreferencesRepository)
                                } 
                            }
                        )
                        Text("Template")
                    }
                }

                // 0. Permission
                SectionHeader("Sensors Permission")
                Text("GrapheneOS requires the 'Other Sensors' permission for screen state detection.", style = MaterialTheme.typography.bodySmall)
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
                            val activity = context as? android.app.Activity
                            activity?.requestPermissions(arrayOf("android.permission.OTHER_SENSORS"), 1001)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Request Sensors Permission")
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
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



                if (detectionMode == SleepDetectionMode.AUTO) {
                    // 2. Bedtime Window
                    SectionHeader("Bedtime Window")
                    Text("When you usually go to bed. The last phone lock in this range is your bedtime.", style = MaterialTheme.typography.bodySmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        WindowTimeButton(
                            label = "Start",
                            minutes = bedtimeStart,
                            modifier = Modifier.weight(1f),
                            onClick = { showBedtimePicker = true }
                        )
                        WindowTimeButton(
                            label = "End",
                            minutes = bedtimeEnd,
                            modifier = Modifier.weight(1f),
                            onClick = { showBedtimePicker = false }
                        )
                    }

                    // 3. Wakeup Window
                    SectionHeader("Wakeup Window")
                    Text("When you usually wake up. The first phone unlock in this range is your wakeup time.", style = MaterialTheme.typography.bodySmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        WindowTimeButton(
                            label = "Start",
                            minutes = wakeupStart,
                            modifier = Modifier.weight(1f),
                            onClick = { showWakeupPicker = true }
                        )
                        WindowTimeButton(
                            label = "End",
                            minutes = wakeupEnd,
                            modifier = Modifier.weight(1f),
                            onClick = { showWakeupPicker = false }
                        )
                    }

                    // 4. Awakenings
                    SectionHeader("Awakenings")
                    Text("Log when you briefly wake up and use your phone during the wakeup window.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = awakeningEnabled,
                            onCheckedChange = { scope.launch { userPreferencesRepository.setAwakeningLoggingEnabled(it) } }
                        )
                        Text("Log awakenings in bed")
                    }

                    // 5. Default Awake Period
                    SectionHeader("Before Falling Asleep")
                    Text("The default number of minutes from when you last lock your phone to when you fall asleep. You can change this day to day!", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = defaultAwakeToAsleep.toString(),
                        onValueChange = { val value = it.toIntOrNull() ?: 0
                            scope.launch { userPreferencesRepository.setDefaultAwakeToAsleepMinutes(value) }
                        },
                        label = { Text("Default awake in bed (minutes)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (detectionMode == SleepDetectionMode.MANUAL) {
                    // 6. Manual Template Editor
                    SectionHeader("Manual Sleep Template")
                    Text("Default sleep structure when no auto-data is available.", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { showTemplateEditor = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Configure Manual Template")
                    }
                }

                HorizontalDivider()

                // 7. Notifications
                SectionHeader("Reminders")
                Text("Get notified to log your sleep after waking up. You can manage channel-specific sounds and priority in System Settings.", style = MaterialTheme.typography.bodySmall)
                
                ListItem(
                    headlineContent = { Text("First Unlock Reminder") },
                    supportingContent = { Text("Fires after your awakening threshold has passed since you first pick up your phone in the morning.") },
                    trailingContent = {
                        Switch(
                            checked = reminderFirstUnlock,
                            onCheckedChange = { scope.launch { userPreferencesRepository.setReminderFirstUnlockEnabled(it) } }
                        )
                    }
                )

                ListItem(
                    headlineContent = { Text("Deadline Reminder (Loud)") },
                    supportingContent = { Text("Alerts you at the end of the wakeup window if you've been active.") },
                    trailingContent = {
                        Switch(
                            checked = reminderDeadlineLoud,
                            onCheckedChange = { scope.launch { userPreferencesRepository.setReminderDeadlineLoudEnabled(it) } }
                        )
                    }
                )

                ListItem(
                    headlineContent = { Text("Deadline Reminder (Silent)") },
                    supportingContent = { Text("Silent notification at the end of the window if you haven't unlocked your phone (assumes you might still be asleep).") },
                    trailingContent = {
                        Switch(
                            checked = reminderDeadlineSilent,
                            onCheckedChange = { scope.launch { userPreferencesRepository.setReminderDeadlineSilentEnabled(it) } }
                        )
                    }
                )

                OutlinedButton(
                    onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Notification Settings")
                }

                HorizontalDivider()

                SectionHeader("Advanced")
                OutlinedButton(
                    onClick = onAdvancedSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Advanced Settings")
                }
            }
        }
    }

    // Time Pickers
    showBedtimePicker?.let { isStart ->
        SimpleTimePickerDialog(
            initialMinutes = if (isStart) bedtimeStart else bedtimeEnd,
            onConfirm = { mins ->
                scope.launch {
                    if (isStart) userPreferencesRepository.setBedtimeWindow(mins, bedtimeEnd)
                    else userPreferencesRepository.setBedtimeWindow(bedtimeStart, mins)
                    
                    // Reschedule lifecycle and check if we should start/stop now
                    // Reschedule lifecycle and check if we should start/stop now
                    NotificationHelper.refreshServiceState(context, userPreferencesRepository)
                }
                showBedtimePicker = null
            },
            onDismiss = { showBedtimePicker = null }
        )
    }

    showWakeupPicker?.let { isStart ->
        SimpleTimePickerDialog(
            initialMinutes = if (isStart) wakeupStart else wakeupEnd,
            onConfirm = { mins ->
                scope.launch {
                    if (isStart) userPreferencesRepository.setWakeupWindow(mins, wakeupEnd)
                    else userPreferencesRepository.setWakeupWindow(wakeupStart, mins)
                }
                showWakeupPicker = null
            },
            onDismiss = { showWakeupPicker = null }
        )
    }
}

@Composable
fun WindowTimeButton(label: String, minutes: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val time = LocalTime.of(minutes / 60, minutes % 60)
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(time.format(DateTimeFormatter.ofPattern("h:mm a")), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleTimePickerDialog(initialMinutes: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initialMinutes / 60, initialMinute = initialMinutes % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Time") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
