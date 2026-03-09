package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.NotificationHelper
import codegito.xyz.healthconnector.SleepSegment
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.SleepDetectionMode
import codegito.xyz.healthconnector.data.model.SleepLogTemplate
import codegito.xyz.healthconnector.data.model.TemplateSegment
import codegito.xyz.healthconnector.data.model.TimeRange
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit,
    onEditSleepStages: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val sleepEnabled by userPreferencesRepository.sleepEnabled.collectAsState(initial = true)
    val showAdvanced by userPreferencesRepository.showAdvancedSettings.collectAsState(initial = false)
    val detectionMode by userPreferencesRepository.sleepDetectionMode.collectAsState(initial = SleepDetectionMode.AUTO)
    val bedtimeWindow by userPreferencesRepository.bedtimeWindow.collectAsState(initial = TimeRange.BEDTIME)
    val wakeupWindow by userPreferencesRepository.wakeupWindow.collectAsState(initial = TimeRange.WAKEUP)
    val awakeningEnabled by userPreferencesRepository.awakeningLoggingEnabled.collectAsState(initial = true)
    val defaultAwakeToAsleep by userPreferencesRepository.defaultAwakeToAsleepMinutes.collectAsState(initial = 15)
    val manualTemplateState by userPreferencesRepository.manualSleepTemplate.collectAsState(initial = null)
    val sleepStages by userPreferencesRepository.sleepStages.collectAsState(initial = emptyList())
    val reminderFirstUnlock by userPreferencesRepository.reminderFirstUnlockEnabled.collectAsState(initial = true)
    val reminderDeadlineLoud by userPreferencesRepository.reminderDeadlineLoudEnabled.collectAsState(initial = true)
    val reminderDeadlineSilent by userPreferencesRepository.reminderDeadlineSilentEnabled.collectAsState(initial = true)
    val awakeningThreshold by userPreferencesRepository.awakeningThresholdMinutes.collectAsState(initial = 10)

    var showTemplateEditor by remember { mutableStateOf(false) }

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
            // ── Enable / Disable toggle ────────────────────────────────────
            ListItem(
                headlineContent = { Text("Sleep Tracking Enabled") },
                supportingContent = {
                    Text(if (sleepEnabled) "Sleep is active and visible in the app."
                         else "Sleep is disabled. Home tab and settings below are hidden.")
                },
                trailingContent = {
                    Switch(
                        checked = sleepEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                userPreferencesRepository.setSleepEnabled(enabled)
                                NotificationHelper.refreshServiceState(context, userPreferencesRepository)
                            }
                        }
                    )
                }
            )

            HorizontalDivider()

            // Gray-out alpha for all settings below when disabled
            val contentAlpha = if (sleepEnabled) 1f else 0.38f

            // ── Detection Mode ─────────────────────────────────────────────
            SectionHeader("Default Template")
            Text(
                "When you start logging a new day, the app provides a default template — either " +
                "auto-generated from phone lock/unlock events or from a fixed template you configure. " +
                "You can edit it before saving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
            )
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = detectionMode == SleepDetectionMode.AUTO,
                        enabled = sleepEnabled,
                        onClick = {
                            scope.launch {
                                userPreferencesRepository.setSleepDetectionMode(SleepDetectionMode.AUTO)
                                NotificationHelper.refreshServiceState(context, userPreferencesRepository)
                            }
                        }
                    )
                    Text("Auto-detect",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = detectionMode == SleepDetectionMode.MANUAL,
                        enabled = sleepEnabled,
                        onClick = {
                            scope.launch {
                                userPreferencesRepository.setSleepDetectionMode(SleepDetectionMode.MANUAL)
                                NotificationHelper.refreshServiceState(context, userPreferencesRepository)
                            }
                        }
                    )
                    Text("Template",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha))
                }
            }

            if (detectionMode == SleepDetectionMode.AUTO && sleepEnabled) {
                HorizontalDivider()

                SectionHeader("Bedtime Window")
                Text(
                    "When you usually go to bed. The last phone lock in this range is your bedtime.",
                    style = MaterialTheme.typography.bodySmall
                )
                TimeRangeSetting(
                    label = "",
                    range = bedtimeWindow,
                    onRangeChange = { range ->
                        scope.launch {
                            userPreferencesRepository.setBedtimeWindow(range)
                            NotificationHelper.refreshServiceState(context, userPreferencesRepository)
                        }
                    }
                )

                SectionHeader("Wakeup Window")
                Text(
                    "When you usually wake up. The first phone unlock in this range is your wakeup time.",
                    style = MaterialTheme.typography.bodySmall
                )
                TimeRangeSetting(
                    label = "",
                    range = wakeupWindow,
                    onRangeChange = { range ->
                        scope.launch { userPreferencesRepository.setWakeupWindow(range) }
                    }
                )

                SectionHeader("Awakenings")
                Text("Log when you briefly wake up during the wakeup window.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = awakeningEnabled,
                        onCheckedChange = { scope.launch { userPreferencesRepository.setAwakeningLoggingEnabled(it) } }
                    )
                    Text("Log awakenings in bed")
                }

                SectionHeader("Before Falling Asleep")
                Text("Default minutes from your last phone lock to when you fall asleep.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = defaultAwakeToAsleep.toString(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.let { scope.launch { userPreferencesRepository.setDefaultAwakeToAsleepMinutes(it) } }
                    },
                    label = { Text("Default awake in bed (minutes)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (detectionMode == SleepDetectionMode.MANUAL && sleepEnabled) {
                HorizontalDivider()
                SectionHeader("Manual Sleep Template")
                Text("Default sleep structure when logging a new day.", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { showTemplateEditor = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Configure Manual Template") }
            }

            HorizontalDivider()

            // ── Reminders ──────────────────────────────────────────────────
            SectionHeader("Reminders")
            Text(
                "Get notified to log your sleep after waking up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
            )
            ListItem(
                headlineContent = { Text("First Unlock Reminder",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)) },
                supportingContent = { Text("Fires after your awakening threshold passes since you first pick up your phone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)) },
                trailingContent = {
                    Switch(
                        checked = reminderFirstUnlock,
                        enabled = sleepEnabled,
                        onCheckedChange = { scope.launch { userPreferencesRepository.setReminderFirstUnlockEnabled(it) } }
                    )
                }
            )
            ListItem(
                headlineContent = { Text("Deadline Reminder (Loud)",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)) },
                supportingContent = { Text("Alerts you at the end of the wakeup window if you've been active.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)) },
                trailingContent = {
                    Switch(
                        checked = reminderDeadlineLoud,
                        enabled = sleepEnabled,
                        onCheckedChange = { scope.launch { userPreferencesRepository.setReminderDeadlineLoudEnabled(it) } }
                    )
                }
            )
            ListItem(
                headlineContent = { Text("Deadline Reminder (Silent)",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)) },
                supportingContent = { Text("Silent notification at the end of the window if you haven't unlocked your phone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)) },
                trailingContent = {
                    Switch(
                        checked = reminderDeadlineSilent,
                        enabled = sleepEnabled,
                        onCheckedChange = { scope.launch { userPreferencesRepository.setReminderDeadlineSilentEnabled(it) } }
                    )
                }
            )

            HorizontalDivider()

            // ── Sleep Stages ───────────────────────────────────────────────
            SectionHeader("Sleep Stages")
            ListItem(
                headlineContent = { Text("Customize Sleep Stages",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)) },
                supportingContent = { Text("Reorder, enable/disable, and change emojis",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = if (sleepEnabled) Modifier.clickable { onEditSleepStages() } else Modifier
            )

            if (showAdvanced && sleepEnabled) {
                HorizontalDivider()
                SectionHeader("Advanced")

                Text("Awakening Threshold", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Controls how brief wake-ups during sleep are grouped. Two wake-ups separated by a gap of this duration or less are merged into one continuous awake period. If the gap exceeds the threshold they are recorded as separate events. The same threshold is used in your wakeup window to distinguish a real morning wakeup from a brief check of the phone.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = awakeningThreshold.toString(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.takeIf { it >= 0 }?.let {
                            scope.launch { userPreferencesRepository.setAwakeningThreshold(it) }
                        }
                    },
                    label = { Text("Awakening threshold (minutes)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

}
