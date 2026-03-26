package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import codegito.xyz.healthconnector.HealthConnectManager
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.exercise.domain.DefaultExerciseTypes
import codegito.xyz.healthconnector.exercise.domain.ExerciseCategory
import codegito.xyz.healthconnector.exercise.domain.ExerciseMath
import codegito.xyz.healthconnector.exercise.domain.ExerciseSet
import codegito.xyz.healthconnector.exercise.domain.ExerciseType
import codegito.xyz.healthconnector.exercise.domain.ExerciseUserPrefs
import codegito.xyz.healthconnector.exercise.domain.LoggedExerciseEntry
import codegito.xyz.healthconnector.exercise.domain.Sex
import codegito.xyz.healthconnector.weight.domain.WeightUnit
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// ── Home Screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseHomeScreen(
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    navController: NavController,
    onNavigateToPermissions: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val historyDays by userPreferencesRepository.historyDays.collectAsState(initial = 7)

    var sessions by remember { mutableStateOf<List<androidx.health.connect.client.records.ExerciseSessionRecord>>(emptyList()) }
    var calorieSessions by remember { mutableStateOf<List<androidx.health.connect.client.records.TotalCaloriesBurnedRecord>>(emptyList()) }
    var hasWritePermission by remember { mutableStateOf<Boolean?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showLogSheet by remember { mutableStateOf(false) }

    suspend fun loadSessions() {
        hasWritePermission = healthConnectManager.hasExerciseWritePermission()
        if (healthConnectManager.hasExerciseReadPermission()) {
            val end = Instant.now()
            val start = end.minusSeconds(historyDays * 86400L)
            sessions = healthConnectManager.getExerciseSessions(start, end).getOrDefault(emptyList())
                .sortedByDescending { it.startTime }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { loadSessions() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Exercise") }) },
        floatingActionButton = {
            if (hasWritePermission == true) {
                FloatingActionButton(onClick = { showLogSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Log Exercise")
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch { loadSessions(); isRefreshing = false }
            },
            modifier = Modifier.padding(padding)
        ) {
            if (hasWritePermission == false) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Exercise write permission not granted",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            if (onNavigateToPermissions != null) {
                                TextButton(onClick = onNavigateToPermissions) {
                                    Text("Open Permissions", color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
            } else {
                val zoneId = ZoneId.systemDefault()
                val today = LocalDate.now()
                val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)
                val weekDays = (0 until historyDays).map { today.minusDays(it.toLong()) }

                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (sessions.isEmpty()) {
                        item {
                            Text("No exercise logged yet. Tap + to log a session.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 24.dp))
                        }
                    } else {
                        items(weekDays) { date ->
                            val dayStart = date.atTime(rolloverHour, 0).atZone(zoneId).toInstant()
                            val dayEnd = date.plusDays(1).atTime(rolloverHour, 0).atZone(zoneId).toInstant()
                            val daySessions = sessions.filter {
                                it.startTime.isAfter(dayStart) && it.startTime.isBefore(dayEnd)
                            }
                            if (daySessions.isNotEmpty()) {
                                ExerciseDayCard(date = date, sessions = daySessions, zoneId = zoneId,
                                    onDelete = { id ->
                                        scope.launch {
                                            healthConnectManager.deleteExerciseSession(id)
                                            loadSessions()
                                        }
                                    })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLogSheet) {
        LogExerciseSheet(
            healthConnectManager = healthConnectManager,
            userPreferencesRepository = userPreferencesRepository,
            onSaved = {
                showLogSheet = false
                scope.launch { loadSessions() }
            },
            onDismiss = { showLogSheet = false }
        )
    }
}

@Composable
private fun ExerciseDayCard(
    date: LocalDate,
    sessions: List<androidx.health.connect.client.records.ExerciseSessionRecord>,
    zoneId: ZoneId,
    onDelete: (String) -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(date.format(formatter), style = MaterialTheme.typography.titleMedium)
            sessions.forEach { session ->
                val startLocal = session.startTime.atZone(zoneId).toLocalDateTime()
                val duration = Duration.between(session.startTime, session.endTime)
                val durationStr = "${duration.toMinutes()}m"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${session.title ?: "Workout"} · $durationStr",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(startLocal.format(timeFormatter),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDelete(session.metadata.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// ── Log Exercise Bottom Sheet ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogExerciseSheet(
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val zoneId = ZoneId.systemDefault()

    val exerciseAge by userPreferencesRepository.exerciseAge.collectAsState(initial = null)
    val exerciseSex by userPreferencesRepository.exerciseSex.collectAsState(initial = null)
    val defaultWeightKg by userPreferencesRepository.exerciseDefaultWeightKg.collectAsState(initial = 70.0)
    val acsmCorrection by userPreferencesRepository.exerciseAcsmRunningCorrection.collectAsState(initial = 0.90)
    val epocMultiplier by userPreferencesRepository.exerciseEpocMultiplier.collectAsState(initial = 1.07)

    var selectedType by remember { mutableStateOf(DefaultExerciseTypes.all.first()) }
    var startTime by remember { mutableStateOf(LocalTime.now().withSecond(0).withNano(0)) }
    var endTime by remember { mutableStateOf(LocalTime.now().plusHours(1).withSecond(0).withNano(0)) }
    var distanceInput by remember { mutableStateOf("") }
    var sets by remember { mutableStateOf(listOf(ExerciseSet(reps = 10))) }
    var isSaving by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    val today = LocalDate.now()

    fun buildEntry(): LoggedExerciseEntry {
        val startInstant = today.atTime(startTime).atZone(zoneId).toInstant()
        val endInstant = today.atTime(endTime).atZone(zoneId).toInstant()
            .let { if (it <= startInstant) startInstant.plusSeconds(60) else it }
        val durationMin = Duration.between(startInstant, endInstant).toMinutes().toDouble()
        val prefs = ExerciseUserPrefs(
            defaultWeightKg = defaultWeightKg,
            age = exerciseAge,
            sex = exerciseSex,
            acsmRunningCorrectionFactor = acsmCorrection,
            epocMultiplierStrength = epocMultiplier
        )
        val distanceM = distanceInput.toDoubleOrNull()?.let { it * 1000 } // user inputs km
        val inputs = codegito.xyz.healthconnector.exercise.domain.ExerciseInputs(
            exerciseType = selectedType,
            durationMinutes = durationMin,
            met = selectedType.met,
            weightKg = defaultWeightKg,
            lbmKg = null, bodyFatFraction = null, measuredBmr = null,
            vo2maxMlKgMin = null, avgHeartRate = null, restingHeartRate = null,
            avgSpeedMps = if (distanceM != null && durationMin > 0) distanceM / (durationMin * 60) else null,
            distanceMeters = distanceM,
            elevationGainedMeters = null, avgPowerWatts = null, steps = null, heightMeters = null,
            age = exerciseAge, sex = exerciseSex
        )
        val result = ExerciseMath.estimate(inputs, prefs)
        return LoggedExerciseEntry(
            id = UUID.randomUUID().toString(),
            exerciseType = selectedType,
            startTime = startInstant,
            endTime = endInstant,
            distanceMeters = distanceM,
            sets = if (selectedType.usesReps) sets else null,
            estimatedCalories = result.calories,
            caloriesTier = result.tier
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Log Exercise", style = MaterialTheme.typography.titleLarge)

            // Exercise type chips
            Text("Exercise Type", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DefaultExerciseTypes.all) { type ->
                    FilterChip(
                        selected = selectedType.id == type.id,
                        onClick = { selectedType = type; distanceInput = ""; sets = listOf(ExerciseSet(10)) },
                        label = { Text("${type.icon} ${type.displayName}") }
                    )
                }
            }

            // Time row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showStartPicker = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start: ${startTime.format(DateTimeFormatter.ofPattern("h:mm a"))}")
                }
                OutlinedButton(
                    onClick = { showEndPicker = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("End: ${endTime.format(DateTimeFormatter.ofPattern("h:mm a"))}")
                }
            }

            // Optional: distance (cardio) or sets/reps (strength)
            if (selectedType.usesDistance) {
                OutlinedTextField(
                    value = distanceInput,
                    onValueChange = { distanceInput = it },
                    label = { Text("Distance (km) — optional") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (selectedType.usesReps) {
                Text("Sets & Reps (optional)", style = MaterialTheme.typography.labelLarge)
                sets.forEachIndexed { idx, set ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Set ${idx + 1}", modifier = Modifier.width(48.dp))
                        OutlinedTextField(
                            value = set.reps.toString(),
                            onValueChange = { v ->
                                val reps = v.toIntOrNull() ?: return@OutlinedTextField
                                sets = sets.toMutableList().also { it[idx] = it[idx].copy(reps = reps) }
                            },
                            label = { Text("Reps") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        if (sets.size > 1) {
                            IconButton(onClick = { sets = sets.toMutableList().also { it.removeAt(idx) } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove set",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                TextButton(onClick = { sets = sets + ExerciseSet(reps = 10) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add Set")
                }
            }

            // Preview calorie estimate
            val previewEntry = remember(selectedType, startTime, endTime, distanceInput, sets,
                defaultWeightKg, exerciseAge, exerciseSex) {
                runCatching { buildEntry() }.getOrNull()
            }
            if (previewEntry != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Estimated calories", style = MaterialTheme.typography.bodyMedium)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "~${previewEntry.estimatedCalories.toInt()} kcal",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(ExerciseMath.tierLabel(previewEntry.caloriesTier),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            Button(
                onClick = {
                    isSaving = true
                    scope.launch {
                        val entry = buildEntry()
                        // Enrich with real HC data then recalculate
                        val prefs = ExerciseUserPrefs(defaultWeightKg, exerciseAge, exerciseSex, acsmCorrection, epocMultiplier)
                        val enriched = healthConnectManager.enrichExerciseInputs(
                            entry.exerciseType, entry.startTime, entry.endTime, prefs,
                            entry.distanceMeters
                        )
                        val result = ExerciseMath.estimate(enriched, prefs)
                        val finalEntry = entry.copy(
                            estimatedCalories = result.calories,
                            caloriesTier = result.tier
                        )
                        healthConnectManager.writeExerciseSession(finalEntry)
                        isSaving = false
                        onSaved()
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Save Exercise")
            }
        }
    }

    if (showStartPicker) {
        AppTimePickerDialog(
            initialHour = startTime.hour,
            initialMinute = startTime.minute,
            onConfirm = { h, m -> startTime = LocalTime.of(h, m); showStartPicker = false },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        AppTimePickerDialog(
            initialHour = endTime.hour,
            initialMinute = endTime.minute,
            onConfirm = { h, m -> endTime = LocalTime.of(h, m); showEndPicker = false },
            onDismiss = { showEndPicker = false }
        )
    }
}
