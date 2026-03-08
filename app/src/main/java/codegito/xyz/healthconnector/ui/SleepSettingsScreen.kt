package codegito.xyz.healthconnector.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import codegito.xyz.healthconnector.NotificationHelper
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit,
    onEditSleepStages: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Preferences ───────────────────────────────────────────────────────
    val showAdvanced by userPreferencesRepository.showAdvancedSettings.collectAsState(initial = false)
    val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)
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
    // Advanced prefs
    val historyDisplayDays by userPreferencesRepository.historyDisplayDays.collectAsState(initial = 7)
    val awakeningThreshold by userPreferencesRepository.awakeningThresholdMinutes.collectAsState(initial = 60)
    val dataRetentionDays by userPreferencesRepository.dataRetentionDays.collectAsState(initial = 7)

    // ── Local UI state ────────────────────────────────────────────────────
    var hasOtherSensorsPermission by remember { mutableStateOf(false) }
    var showRolloverPicker by remember { mutableStateOf(false) }
    var showBedtimePicker by remember { mutableStateOf<Boolean?>(null) }  // true=start, false=end
    var showWakeupPicker by remember { mutableStateOf<Boolean?>(null) }
    var showTemplateEditor by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val state = ContextCompat.checkSelfPermission(context, "android.permission.OTHER_SENSORS")
                hasOtherSensorsPermission = state == PermissionChecker.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Template editor takes over the whole screen
    val currentTemplate = manualTemplateState
    if (showTemplateEditor && currentTemplate != null) {
        val baseDate = LocalDate.of(2000, 1, 1)
        val initialBedtime = baseDate.atTime(
            currentTemplate.bedtimeOffsetMinutes / 60,
            currentTemplate.bedtimeOffsetMinutes % 60
        )
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
                    NotificationHelper.refreshServiceState(context, userPreferencesRepository)
                }
                showTemplateEditor = false
            },
            onCancel = { showTemplateEditor = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sleep Settings") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Rollover time ─────────────────────────────────────────────
            SectionHeader("Rollover Time")
            ListItem(
                headlineContent = { Text("Daily cutoff") },
                supportingContent = { Text("Sleep sessions starting before this hour count toward the previous calendar day.") },
                trailingContent = {
                    Text(
                        LocalTime.of(rolloverHour, 0).format(DateTimeFormatter.ofPattern("h:mm a")),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.clickable { showRolloverPicker = true }
            )

            HorizontalDivider()

            // ── Default template ──────────────────────────────────────────
            SectionHeader("Default template")
            Text(
                "When you start logging a new day, the app will provide a default template. " +
                "Either auto-generated from phone lock/unlock events or from a fixed template — " +
                "you can edit both before saving.",
                style = MaterialTheme.typography.bodySmall
            )
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = detectionMode == SleepDetectionMode.AUTO,
                        onClick = {
                            scope.launch {
                                userPreferencesRepository.setSleepDetectionMode(SleepDetectionMode.AUTO)
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
                                NotificationHelper.refreshServiceState(context, userPreferencesRepository)
                            }
                        }
                    )
                    Text("Template")
                }
            }

            // ── Sensors permission ────────────────────────────────────────
            SectionHeader("Sensors Permission")
            Text(
                "GrapheneOS requires the 'Other Sensors' permission for screen state detection.",
                style = MaterialTheme.typography.bodySmall
            )
            if (hasOtherSensorsPermission) {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Sensors Permission Granted")
                }
            } else {
                Button(
                    onClick = { (context as? Activity)?.requestPermissions(arrayOf("android.permission.OTHER_SENSORS"), 1001) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Request Sensors Permission") }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open App Info (Toggle Sensors)")
                }
            }

            if (detectionMode == SleepDetectionMode.AUTO) {
                HorizontalDivider()

                // ── Bedtime window ────────────────────────────────────────
                SectionHeader("Bedtime Window")
                Text(
                    "When you usually go to bed. The last phone lock in this range is your bedtime.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WindowTimeButton("Start", bedtimeStart, Modifier.weight(1f)) { showBedtimePicker = true }
                    WindowTimeButton("End", bedtimeEnd, Modifier.weight(1f)) { showBedtimePicker = false }
                }

                // ── Wakeup window ─────────────────────────────────────────
                SectionHeader("Wakeup Window")
                Text(
                    "When you usually wake up. The first phone unlock in this range is your wakeup time.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WindowTimeButton("Start", wakeupStart, Modifier.weight(1f)) { showWakeupPicker = true }
                    WindowTimeButton("End", wakeupEnd, Modifier.weight(1f)) { showWakeupPicker = false }
                }

                // ── Awakenings ────────────────────────────────────────────
                SectionHeader("Awakenings")
                Text(
                    "Log when you briefly wake up during the wakeup window.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = awakeningEnabled,
                        onCheckedChange = { scope.launch { userPreferencesRepository.setAwakeningLoggingEnabled(it) } }
                    )
                    Text("Log awakenings in bed")
                }

                // ── Before falling asleep ─────────────────────────────────
                SectionHeader("Before Falling Asleep")
                Text(
                    "Default minutes from your last phone lock to when you fall asleep.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = defaultAwakeToAsleep.toString(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.let { value ->
                            scope.launch { userPreferencesRepository.setDefaultAwakeToAsleepMinutes(value) }
                        }
                    },
                    label = { Text("Default awake in bed (minutes)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (detectionMode == SleepDetectionMode.MANUAL) {
                HorizontalDivider()
                SectionHeader("Manual Sleep Template")
                Text("Default sleep structure when logging a new day.", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { showTemplateEditor = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Configure Manual Template") }
            }

            HorizontalDivider()

            // ── Reminders ─────────────────────────────────────────────────
            SectionHeader("Reminders")
            Text(
                "Get notified to log your sleep after waking up.",
                style = MaterialTheme.typography.bodySmall
            )
            ListItem(
                headlineContent = { Text("First Unlock Reminder") },
                supportingContent = { Text("Fires after your awakening threshold passes since you first pick up your phone.") },
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
                supportingContent = { Text("Silent notification at the end of the window if you haven't unlocked your phone.") },
                trailingContent = {
                    Switch(
                        checked = reminderDeadlineSilent,
                        onCheckedChange = { scope.launch { userPreferencesRepository.setReminderDeadlineSilentEnabled(it) } }
                    )
                }
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Notification Settings") }

            HorizontalDivider()

            // ── Sleep stages ──────────────────────────────────────────────
            SectionHeader("Sleep Stages")
            ListItem(
                headlineContent = { Text("Customize Sleep Stages") },
                supportingContent = { Text("Reorder, enable/disable, and change emojis") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = Modifier.clickable { onEditSleepStages() }
            )

            // ── Advanced (inline, hidden by default) ──────────────────────
            if (showAdvanced) {
                HorizontalDivider()
                SectionHeader("Advanced")

                Text("History", style = MaterialTheme.typography.labelLarge)
                Text("Number of days shown on the home screen.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = historyDisplayDays.toString(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.takeIf { it > 0 }?.let { value ->
                            scope.launch { userPreferencesRepository.setHistoryDisplayDays(value) }
                        }
                    },
                    label = { Text("History display days") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Awakening Threshold", style = MaterialTheme.typography.labelLarge)
                Text(
                    "If you unlock, lock, and unlock again in your wakeup window, this threshold determines whether you stayed awake or went back to sleep.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = awakeningThreshold.toString(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.takeIf { it >= 0 }?.let { value ->
                            scope.launch { userPreferencesRepository.setAwakeningThreshold(value) }
                        }
                    },
                    label = { Text("Awakening threshold (minutes)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Data Retention", style = MaterialTheme.typography.labelLarge)
                Text(
                    "How many days of raw screen events to keep. Increasing this lets you re-detect sleep from further back.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = dataRetentionDays.toString(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.takeIf { it > 0 }?.let { value ->
                            scope.launch { userPreferencesRepository.setDataRetentionDays(value) }
                        }
                    },
                    label = { Text("Data retention (days)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────
    if (showRolloverPicker) {
        RolloverTimePickerDialog(
            initialHour = rolloverHour,
            onConfirm = { hour ->
                scope.launch { userPreferencesRepository.setRolloverHour(hour) }
                showRolloverPicker = false
            },
            onDismiss = { showRolloverPicker = false }
        )
    }

    showBedtimePicker?.let { isStart ->
        SimpleTimePickerDialog(
            initialMinutes = if (isStart) bedtimeStart else bedtimeEnd,
            onConfirm = { mins ->
                scope.launch {
                    if (isStart) userPreferencesRepository.setBedtimeWindow(mins, bedtimeEnd)
                    else userPreferencesRepository.setBedtimeWindow(bedtimeStart, mins)
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
