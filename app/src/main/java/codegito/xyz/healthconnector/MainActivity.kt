package codegito.xyz.healthconnector

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.SleepLogTemplate
import codegito.xyz.healthconnector.data.model.TemplateSegment
import codegito.xyz.healthconnector.nutrition.data.NutritionIndexBuildManager
import codegito.xyz.healthconnector.ui.AdvancedSleepSettingsScreen
import codegito.xyz.healthconnector.ui.AutoSleepSettingsScreen
import codegito.xyz.healthconnector.ui.EditSleepStagesScreen
import codegito.xyz.healthconnector.ui.SettingsScreen
import codegito.xyz.healthconnector.ui.theme.SleepTrackerTheme
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {

    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var userPreferencesRepository: UserPreferencesRepository

    private val launchOnboarding =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            ensureServiceRunning()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        healthConnectManager = HealthConnectManager(this)
        userPreferencesRepository = UserPreferencesRepository.getInstance(this)

        lifecycleScope.launch {
            val onboardingDone = userPreferencesRepository.onboardingCompleted.first()
            if (!onboardingDone) {
                launchOnboarding.launch(Intent(this@MainActivity, OnboardingActivity::class.java))
            }
            ensureServiceRunning()
        }

        setContent {
            val amoledPitchBlackEnabled by userPreferencesRepository.amoledPitchBlackEnabled.collectAsState(initial = false)
            SleepTrackerTheme(amoledPitchBlack = amoledPitchBlackEnabled) {
                MainApp(
                    healthConnectManager = healthConnectManager,
                    userPreferencesRepository = userPreferencesRepository,
                    onManagePermissions = { requestPermissions.launch(healthConnectManager.permissions) },
                    onOpenSession = { date, sessionId, isNap ->
                        val intent = Intent(this, SleepDataLogger::class.java).apply {
                            putExtra("target_date_millis", date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                            if (sessionId != null) putExtra("session_id", sessionId)
                            putExtra("is_nap", isNap)
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(healthConnectManager.permissions)) {
                 // Check permissions again / Refresh UI logic handled by state
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
            }
        }

    private fun ensureServiceRunning() {
        lifecycleScope.launch {
            NotificationHelper.refreshServiceState(this@MainActivity, userPreferencesRepository)
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }
}

@Composable
fun MainApp(
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    onManagePermissions: () -> Unit,
    onOpenSession: (LocalDate, String?, Boolean) -> Unit
) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

             // Only show bottom bar on top-level screens
            val topLevelRoutes = listOf("home", "nutrition", "settings")
            if (currentDestination?.route in topLevelRoutes) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        selected = currentDestination?.route == "home",
                        onClick = {
                            navController.navigate("home") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Add, contentDescription = "Food") },
                        label = { Text("Food") },
                        selected = currentDestination?.route == "nutrition",
                        onClick = {
                            navController.navigate("nutrition") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentDestination?.route == "settings",
                        onClick = {
                            navController.navigate("settings") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(navController, startDestination = Screen.Home.route, modifier = Modifier.padding(paddingValues)) {
            composable(Screen.Home.route) {
                HomeScreen(
                    healthConnectManager = healthConnectManager,
                    userPreferencesRepository = userPreferencesRepository,
                    onManagePermissions = onManagePermissions,
                    onOpenSession = onOpenSession
                )
            }
            composable(Screen.Nutrition.route) {
                NutritionDayRouter(
                    healthConnectManager = healthConnectManager,
                    userPreferencesRepository = userPreferencesRepository
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    healthConnectManager = healthConnectManager,
                    userPreferencesRepository = userPreferencesRepository,
                    onManagePermissions = onManagePermissions,
                    onEditSleepStages = { navController.navigate(Screen.EditSleepStages.route) },
                    onAutoSleepSettings = { navController.navigate(Screen.AutoSleepSettings.route) }
                )
            }
            composable(Screen.EditSleepStages.route) {
                EditSleepStagesScreen(
                    userPreferencesRepository = userPreferencesRepository,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.AutoSleepSettings.route) {
                AutoSleepSettingsScreen(
                    userPreferencesRepository = userPreferencesRepository,
                    onBack = { navController.popBackStack() },
                    onAdvancedSettings = { navController.navigate(Screen.AdvancedSleepSettings.route) }
                )
            }
            composable(Screen.AdvancedSleepSettings.route) {
                AdvancedSleepSettingsScreen(
                    userPreferencesRepository = userPreferencesRepository,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    onManagePermissions: () -> Unit,
    onOpenSession: (LocalDate, String?, Boolean) -> Unit
) {
    var sleepSessions by remember { mutableStateOf<List<SleepSessionRecord>>(emptyList()) }
    var hasPermissions by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe rollover hour, developer mode, and history display days
    val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)
    val developerModeEnabled by userPreferencesRepository.developerModeEnabled.collectAsState(initial = false)
    val historyDisplayDays by userPreferencesRepository.historyDisplayDays.collectAsState(initial = 7)

    // Dialog state for multi-session days
    var sessionPickerDate by remember { mutableStateOf<LocalDate?>(null) }
    var napOrOvernightDate by remember { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(Unit) {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        hasPermissions = granted.containsAll(healthConnectManager.permissions)
    }

    // Load data on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    val endTime = Instant.now()
                    val startTime = endTime.minus(Duration.ofDays(historyDisplayDays.toLong()))
                    val result = healthConnectManager.getSleepSessions(startTime, endTime)
                    sleepSessions = result.getOrDefault(emptyList())

                    if (result.isFailure) {
                        Toast.makeText(context, "Data error: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
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
            TopAppBar(
                title = { Text("Sleep Tracker") }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Add Nap") },
                icon = { Icon(Icons.Default.Add, contentDescription = "Add Nap") },
                onClick = { onOpenSession(LocalDate.now(), null, true) }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            val today = LocalDate.now()
            val weekDays = (0 until historyDisplayDays).map { today.minusDays(it.toLong()) }
            val zoneId = ZoneId.systemDefault()

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(weekDays) { date ->
                    val isToday = date == today

                    val sessionsForDate = sleepSessions.filter { session ->
                        val startBound = date.atTime(rolloverHour, 0).atZone(zoneId).toInstant()
                        val endBound = date.plusDays(1).atTime(rolloverHour, 0).atZone(zoneId).toInstant()
                        session.startTime.isAfter(startBound) && session.startTime.isBefore(endBound)
                    }

                    val napSessions = sessionsForDate.filter { it.title == SleepDataLogger.NAP_TITLE }
                    val overnightSessions = sessionsForDate.filter { it.title != SleepDataLogger.NAP_TITLE }

                    SleepDayCard(
                        date = date,
                        isToday = isToday,
                        napSessions = napSessions,
                        overnightSession = overnightSessions.firstOrNull(),
                        developerModeEnabled = developerModeEnabled,
                        rolloverHour = rolloverHour,
                        onClick = {
                            when {
                                isToday -> {
                                    // Today: only naps can exist; pick from them
                                    when (napSessions.size) {
                                        1 -> onOpenSession(date, napSessions.first().metadata.id, true)
                                        else -> sessionPickerDate = date
                                    }
                                }
                                sessionsForDate.isEmpty() -> {
                                    // No sessions: open overnight editor
                                    onOpenSession(date, null, false)
                                }
                                overnightSessions.isEmpty() -> {
                                    // Only naps (1 or more): offer to edit a nap or add overnight sleep
                                    napOrOvernightDate = date
                                }
                                sessionsForDate.size == 1 -> {
                                    val session = sessionsForDate.first()
                                    onOpenSession(date, session.metadata.id, session.title == SleepDataLogger.NAP_TITLE)
                                }
                                else -> {
                                    // Mixed sessions or multiple overnights: show picker
                                    sessionPickerDate = date
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // Session picker dialog (for days with multiple sessions)
    sessionPickerDate?.let { date ->
        val zoneId = ZoneId.systemDefault()
        val sessionsForDate = sleepSessions.filter { session ->
            val startBound = date.atTime(rolloverHour, 0).atZone(zoneId).toInstant()
            val endBound = date.plusDays(1).atTime(rolloverHour, 0).atZone(zoneId).toInstant()
            session.startTime.isAfter(startBound) && session.startTime.isBefore(endBound)
        }
        SessionPickerDialog(
            sessions = sessionsForDate,
            onSessionSelected = { session ->
                sessionPickerDate = null
                onOpenSession(date, session.metadata.id, session.title == SleepDataLogger.NAP_TITLE)
            },
            onDismiss = { sessionPickerDate = null }
        )
    }

    // "Edit nap or add overnight sleep" dialog
    napOrOvernightDate?.let { date ->
        val zoneId = ZoneId.systemDefault()
        val napSessionsForDate = sleepSessions.filter { session ->
            val startBound = date.atTime(rolloverHour, 0).atZone(zoneId).toInstant()
            val endBound = date.plusDays(1).atTime(rolloverHour, 0).atZone(zoneId).toInstant()
            session.startTime.isAfter(startBound) && session.startTime.isBefore(endBound)
                    && session.title == SleepDataLogger.NAP_TITLE
        }
        NapOrOvernightDialog(
            napCount = napSessionsForDate.size,
            onEditNap = {
                napOrOvernightDate = null
                if (napSessionsForDate.size == 1) {
                    onOpenSession(date, napSessionsForDate.first().metadata.id, true)
                } else {
                    sessionPickerDate = date
                }
            },
            onAddOvernight = {
                napOrOvernightDate = null
                onOpenSession(date, null, false)
            },
            onDismiss = { napOrOvernightDate = null }
        )
    }
}

@Composable
fun SessionPickerDialog(
    sessions: List<SleepSessionRecord>,
    onSessionSelected: (SleepSessionRecord) -> Unit,
    onDismiss: () -> Unit
) {
    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    val zoneId = ZoneId.systemDefault()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Sleep Session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sessions.forEach { session ->
                    val isNap = session.title == SleepDataLogger.NAP_TITLE
                    val startTime = LocalDateTime.ofInstant(session.startTime, zoneId)
                    val endTime = LocalDateTime.ofInstant(session.endTime, zoneId)
                    val label = if (isNap) {
                        "💤 Nap: ${startTime.format(timeFormatter)} – ${endTime.format(timeFormatter)}"
                    } else {
                        "🌙 Sleep: ${startTime.format(timeFormatter)} – ${endTime.format(timeFormatter)}"
                    }
                    TextButton(
                        onClick = { onSessionSelected(session) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun NapOrOvernightDialog(
    napCount: Int,
    onEditNap: () -> Unit,
    onAddOvernight: () -> Unit,
    onDismiss: () -> Unit
) {
    val napLabel = if (napCount == 1) "Edit Nap" else "Edit a Nap"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What would you like to do?") },
        text = {
            Text("This day has ${if (napCount == 1) "a nap" else "$napCount naps"} but no overnight sleep logged.")
        },
        confirmButton = {
            TextButton(onClick = onAddOvernight) { Text("Add Overnight Sleep") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onEditNap) { Text(napLabel) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun SleepDayCard(
    date: LocalDate,
    isToday: Boolean,
    napSessions: List<SleepSessionRecord>,
    overnightSession: SleepSessionRecord?,
    developerModeEnabled: Boolean = false,
    rolloverHour: Int = 2,
    onClick: () -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
    val dateString = date.format(formatter)
    val zoneId = ZoneId.systemDefault()

    val hasNaps = napSessions.isNotEmpty()
    val hasOvernight = overnightSession != null
    // Today's card is clickable only if naps exist (overnight not possible until tomorrow)
    val clickEnabled = if (isToday) hasNaps else true

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = clickEnabled) { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isToday && !hasNaps)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Night of $dateString",
                style = MaterialTheme.typography.titleMedium,
                color = if (isToday && !hasNaps)
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (developerModeEnabled) {
                val debugStart = date.atTime(rolloverHour, 0).atZone(zoneId)
                val debugEnd = date.plusDays(1).atTime(rolloverHour, 0).atZone(zoneId)
                val timeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")

                Text(
                    text = "Bounds: ${debugStart.format(timeFormatter)} - ${debugEnd.format(timeFormatter)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (hasOvernight) {
                Spacer(modifier = Modifier.height(4.dp))
                val duration = Duration.between(overnightSession!!.startTime, overnightSession.endTime)
                val hours = duration.toHours()
                val minutes = duration.toMinutesPart()

                val asleepDuration = overnightSession.stages.sumOf { stage ->
                    if (stage.stage != SleepSessionRecord.STAGE_TYPE_AWAKE &&
                        stage.stage != SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED &&
                        stage.stage != SleepSessionRecord.STAGE_TYPE_OUT_OF_BED &&
                        stage.stage != SleepSessionRecord.STAGE_TYPE_UNKNOWN
                    ) {
                        Duration.between(stage.startTime, stage.endTime).toMillis()
                    } else {
                        0L
                    }
                }
                val asleepHours = Duration.ofMillis(asleepDuration).toHours()
                val asleepMinutes = Duration.ofMillis(asleepDuration).toMinutesPart()

                Text(
                    text = "${hours}h ${minutes}m in bed • ${asleepHours}h ${asleepMinutes}m asleep",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                if (hasNaps) {
                    val totalNapMillis = napSessions.sumOf {
                        Duration.between(it.startTime, it.endTime).toMillis()
                    }
                    val napDuration = Duration.ofMillis(totalNapMillis)
                    val napText = if (napSessions.size == 1) {
                        "+ 1 nap (${napDuration.toHours()}h ${napDuration.toMinutesPart()}m)"
                    } else {
                        "+ ${napSessions.size} naps (${napDuration.toHours()}h ${napDuration.toMinutesPart()}m total)"
                    }
                    Text(
                        text = "💤 $napText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else if (hasNaps) {
                Spacer(modifier = Modifier.height(4.dp))
                val totalNapMillis = napSessions.sumOf {
                    Duration.between(it.startTime, it.endTime).toMillis()
                }
                val napDuration = Duration.ofMillis(totalNapMillis)
                val napLabel = if (napSessions.size == 1) "1 nap" else "${napSessions.size} naps"
                Text(
                    text = "💤 $napLabel • ${napDuration.toHours()}h ${napDuration.toMinutesPart()}m",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (isToday) {
                    Text(
                        text = "Tap to edit • Log overnight sleep tomorrow",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                if (!isToday) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "No data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionDayRouter(
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nutritionIndexBuildManager = remember(context) {
        NutritionIndexBuildManager(context)
    }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var entries by remember { mutableStateOf<List<NutritionRecord>>(emptyList()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isBuildingIndex by remember { mutableStateOf(false) }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBuildingIndex = true
            statusMessage = "Building nutrition index from selected ZIP..."
            val result = nutritionIndexBuildManager.buildFromUri(uri)
            result.onSuccess { build ->
                statusMessage = "Nutrition index ready (${build.recordCount} foods)"
            }.onFailure {
                statusMessage = "Index build failed: ${it.message ?: "unknown error"}"
            }
            isBuildingIndex = false
        }
    }

    val hasBundledZip by produceState<Boolean?>(initialValue = null) {
        value = nutritionIndexBuildManager.hasBundledZip()
    }
    val rangeDays by userPreferencesRepository.nutritionPastDateRangeDays.collectAsState(initial = 7)

    LaunchedEffect(selectedDate) {
        scope.launch {
            val zone = ZoneId.systemDefault()
            val dayStart = selectedDate.atStartOfDay(zone).toInstant()
            val dayEnd = selectedDate.plusDays(1).atStartOfDay(zone).toInstant()
            val request = androidx.health.connect.client.request.ReadRecordsRequest(
                recordType = NutritionRecord::class,
                timeRangeFilter = androidx.health.connect.client.time.TimeRangeFilter.between(dayStart, dayEnd)
            )
            entries = runCatching {
                healthConnectManager.healthConnectClient.readRecords(request).records
            }.getOrElse {
                Toast.makeText(context, "Unable to load nutrition entries", Toast.LENGTH_SHORT).show()
                emptyList()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Food tracking") },
                actions = {
                    TextButton(
                        enabled = !isBuildingIndex && hasBundledZip != null,
                        onClick = {
                            val bundled = hasBundledZip == true
                            if (bundled) {
                                scope.launch {
                                    isBuildingIndex = true
                                    statusMessage = "Building nutrition index from bundled ZIP..."
                                    val result = nutritionIndexBuildManager.buildFromBundledZip()
                                    result.onSuccess { build ->
                                        statusMessage = "Nutrition index ready (${build.recordCount} foods)"
                                    }.onFailure {
                                        statusMessage = "Index build failed: ${it.message ?: "unknown error"}"
                                    }
                                    isBuildingIndex = false
                                }
                            } else {
                                downloadLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            }
                        }
                    ) {
                        Text(if (hasBundledZip == true) "Build index" else "Select ZIP")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                Toast.makeText(context, "Food search/manual entry coming next", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add food")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isBuildingIndex) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (hasBundledZip == false) {
                Text(
                    text = "Dataset ZIP is not bundled in this build. Select a ZIP from your phone.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = {
                    val linkIntent = Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://downloads.opennutrition.app/opennutrition-dataset-2025.1.zip")
                    )
                    context.startActivity(linkIntent)
                }) {
                    Text("Download Open Nutrition dataset")
                }
            }
            statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text("Selected day: $selectedDate")
            Text("Past date range from settings: $rangeDays days")
            OutlinedButton(onClick = {
                selectedDate = selectedDate.minusDays(1)
                val oldest = LocalDate.now().minusDays(rangeDays.toLong())
                if (selectedDate.isBefore(oldest)) selectedDate = oldest
            }) { Text("Previous day") }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Daily summary", style = MaterialTheme.typography.titleMedium)
                    Text("Nutrition entries tracked: ${entries.size}")
                    Text("Configure summary fields in settings (planned)")
                }
            }
            Text("Entries (${entries.size})", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries.sortedBy { it.endTime }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Logged food")
                            Text(item.endTime.toString(), style = MaterialTheme.typography.bodySmall)
                            Text(item.metadata.id, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}


sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Nutrition : Screen("nutrition")
    object Settings : Screen("settings")
    object EditSleepStages : Screen("edit_sleep_stages")
    object AutoSleepSettings : Screen("auto_sleep_settings")
    object AdvancedSleepSettings : Screen("advanced_sleep_settings")
}
