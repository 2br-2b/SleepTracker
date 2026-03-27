package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import codegito.xyz.healthconnector.HealthConnectManager
import codegito.xyz.healthconnector.Screen
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.db.AppDatabase
import codegito.xyz.healthconnector.data.db.RecentExerciseEntity
import codegito.xyz.healthconnector.nutrition.domain.NutritionUnitSystem
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// ExerciseHomeScreen – list of days with sessions
// ─────────────────────────────────────────────────────────────────────────────

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

    val rangeDays by userPreferencesRepository.historyDays.collectAsState(initial = 7)
    val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)

    var hasRead by remember { mutableStateOf<Boolean?>(null) }
    var hasWrite by remember { mutableStateOf<Boolean?>(null) }
    var sessionsByDay by remember { mutableStateOf<Map<LocalDate, List<ExerciseSessionRecord>>>(emptyMap()) }
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun loadData() {
        hasRead = healthConnectManager.hasExerciseReadPermission()
        hasWrite = healthConnectManager.hasExerciseWritePermission()
        if (hasRead == true) {
            val end = Instant.now()
            val start = end.minus(Duration.ofDays(rangeDays.toLong() + 1))
            val result = healthConnectManager.getExerciseSessions(start, end)
            if (result.isSuccess) {
                val zoneId = ZoneId.systemDefault()
                val rollover = rolloverHour
                sessionsByDay = result.getOrDefault(emptyList())
                    .groupBy { session ->
                        val localDateTime = session.startTime.atZone(zoneId).toLocalDateTime()
                        if (localDateTime.hour < rollover) localDateTime.toLocalDate().minusDays(1)
                        else localDateTime.toLocalDate()
                    }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { loadData() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val today = LocalDate.now()
    val days = (0 until rangeDays).map { today.minusDays(it.toLong()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Exercise") }) },
        floatingActionButton = {
            if (hasWrite == true) {
                FloatingActionButton(onClick = {
                    navController.navigate(Screen.LogExercise.route(today))
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Log exercise")
                }
            }
        }
    ) { innerPadding ->
        if (hasRead == false) {
            Column(
                modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Health Connect exercise read permission is required.")
                if (onNavigateToPermissions != null) {
                    Button(onClick = onNavigateToPermissions) { Text("Grant Permissions") }
                }
            }
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { scope.launch { isRefreshing = true; loadData(); isRefreshing = false } },
            modifier = Modifier.padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(days) { date ->
                    val sessions = sessionsByDay[date] ?: emptyList()
                    ExerciseDayCard(
                        date = date,
                        sessions = sessions,
                        onClick = { navController.navigate(Screen.ExerciseDay.route(date)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExerciseDayCard(
    date: LocalDate,
    sessions: List<ExerciseSessionRecord>,
    onClick: () -> Unit
) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    }
    val dateStr = date.format(DateTimeFormatter.ofPattern("MMM d"))

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("$label · $dateStr", style = MaterialTheme.typography.titleSmall)
                if (sessions.isEmpty()) {
                    Text("No exercises logged", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${sessions.size} session${if (sessions.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall)
                    val names = sessions.mapNotNull { it.title }.distinct().take(3)
                    if (names.isNotEmpty()) {
                        Text(names.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (sessions.isNotEmpty()) {
                val totalMinutes = sessions.sumOf {
                    Duration.between(it.startTime, it.endTime).toMinutes()
                }
                Text("${totalMinutes}m", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ExerciseDayDetailScreen – sessions for a specific day with delete + add
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDayDetailScreen(
    date: LocalDate,
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    navController: NavController
) {
    val scope = rememberCoroutineScope()
    val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)

    var sessions by remember { mutableStateOf<List<ExerciseSessionRecord>>(emptyList()) }
    var hasWrite by remember { mutableStateOf<Boolean?>(null) }

    suspend fun loadSessions() {
        hasWrite = healthConnectManager.hasExerciseWritePermission()
        val zoneId = ZoneId.systemDefault()
        val rollover = rolloverHour
        val dayStart = date.atTime(rollover, 0).atZone(zoneId).toInstant()
        val dayEnd = date.plusDays(1).atTime(rollover, 0).atZone(zoneId).toInstant()
        sessions = healthConnectManager.getExerciseSessions(dayStart, dayEnd).getOrDefault(emptyList())
    }

    LaunchedEffect(date) { loadSessions() }

    val today = LocalDate.now()
    val label = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (hasWrite == true) {
                FloatingActionButton(onClick = {
                    navController.navigate(Screen.LogExercise.route(date))
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Log exercise")
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (sessions.isEmpty()) {
                item {
                    Text("No exercises logged for this day.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(sessions, key = { it.metadata.id }) { session ->
                    ExerciseSessionCard(
                        session = session,
                        onDelete = if (hasWrite == true) ({
                            scope.launch {
                                healthConnectManager.deleteExerciseSession(session.metadata.id)
                                loadSessions()
                            }
                        }) else null
                    )
                }
            }
        }
    }
}

@Composable
private fun ExerciseSessionCard(
    session: ExerciseSessionRecord,
    onDelete: (() -> Unit)?
) {
    val durationMinutes = Duration.between(session.startTime, session.endTime).toMinutes()
    val startTime = session.startTime.atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofPattern("h:mm a"))

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(session.title ?: "Workout", style = MaterialTheme.typography.titleSmall)
                Text("$startTime · ${durationMinutes}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LogExerciseScreen – log a new exercise session
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogExerciseScreen(
    initialDate: LocalDate,
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    navController: NavController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val recentExerciseDao = remember { db.recentExerciseDao() }

    val unitSystem by userPreferencesRepository.globalUnitSystem.collectAsState(initial = NutritionUnitSystem.US)

    // Recents sorted by most recently used
    val recentExercises by recentExerciseDao.getRecents().collectAsState(initial = emptyList())

    // Date selection
    var selectedDate by remember { mutableStateOf(initialDate) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Form fields
    var exerciseName by remember { mutableStateOf("") }
    var durationMinutesText by remember { mutableStateOf("30") }
    var caloriesText by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val today = LocalDate.now()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log Exercise") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Date selector ──────────────────────────────────────────────
            item {
                val dateLabel = when (selectedDate) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> selectedDate.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
                }
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Date: $dateLabel")
                }
            }

            // ── Exercise name ──────────────────────────────────────────────
            item {
                OutlinedTextField(
                    value = exerciseName,
                    onValueChange = { exerciseName = it },
                    label = { Text("Exercise name") },
                    placeholder = { Text("e.g. Running, Cycling, Yoga") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Recents ────────────────────────────────────────────────────
            if (recentExercises.isNotEmpty()) {
                item {
                    Text("Recent", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        recentExercises.take(5).forEach { recent ->
                            FilterChip(
                                selected = exerciseName == recent.exerciseName,
                                onClick = { exerciseName = recent.exerciseName },
                                label = { Text(recent.exerciseName) }
                            )
                        }
                    }
                }
            }

            // ── Duration ───────────────────────────────────────────────────
            item {
                OutlinedTextField(
                    value = durationMinutesText,
                    onValueChange = { durationMinutesText = it },
                    label = { Text("Duration (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Calories burned ────────────────────────────────────────────
            item {
                OutlinedTextField(
                    value = caloriesText,
                    onValueChange = { caloriesText = it },
                    label = { Text("Calories burned (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Error ──────────────────────────────────────────────────────
            if (errorMessage != null) {
                item {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                }
            }

            // ── Save ───────────────────────────────────────────────────────
            item {
                Button(
                    onClick = {
                        val name = exerciseName.trim()
                        if (name.isBlank()) {
                            errorMessage = "Exercise name is required."
                            return@Button
                        }
                        val durationMinutes = durationMinutesText.trim().toLongOrNull()
                        if (durationMinutes == null || durationMinutes <= 0) {
                            errorMessage = "Enter a valid duration."
                            return@Button
                        }
                        val calories = caloriesText.trim().toDoubleOrNull()
                        errorMessage = null
                        isSaving = true
                        scope.launch {
                            val zoneId = ZoneId.systemDefault()
                            // Place exercise at 9 AM on the selected date (arbitrary, user doesn't pick time)
                            val startTime = selectedDate.atTime(9, 0).atZone(zoneId).toInstant()
                            val endTime = startTime.plusSeconds(durationMinutes * 60)
                            val result = healthConnectManager.writeExerciseSession(
                                startTime = startTime,
                                endTime = endTime,
                                title = name,
                                caloriesKcal = calories
                            )
                            if (result.isSuccess) {
                                // Update recents
                                recentExerciseDao.upsert(
                                    RecentExerciseEntity(
                                        exerciseName = name,
                                        lastUsedAtMillis = System.currentTimeMillis()
                                    )
                                )
                                navController.popBackStack()
                            } else {
                                errorMessage = result.exceptionOrNull()?.message ?: "Failed to save."
                            }
                            isSaving = false
                        }
                    },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    else Text("Save")
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        selectedDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
