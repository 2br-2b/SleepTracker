package codegito.xyz.healthconnector

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.Manifest
import android.os.Build
import android.provider.Settings
import android.net.Uri
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
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
import codegito.xyz.healthconnector.nutrition.data.NutritionIndexBuildManager
import codegito.xyz.healthconnector.nutrition.provider.AssetNutritionProvider
import codegito.xyz.healthconnector.ui.EditSleepStagesScreen
import codegito.xyz.healthconnector.ui.LogFoodScreen
import codegito.xyz.healthconnector.ui.ManualFoodEntryScreen
import codegito.xyz.healthconnector.ui.NutritionDayDetailScreen
import codegito.xyz.healthconnector.ui.NutritionHomeScreen
import codegito.xyz.healthconnector.ui.NutritionSettingsScreen
import codegito.xyz.healthconnector.ui.PermissionsScreen
import codegito.xyz.healthconnector.ui.SettingsScreen
import codegito.xyz.healthconnector.ui.SleepSettingsScreen
import codegito.xyz.healthconnector.ui.theme.SleepTrackerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
                    onRequestHealthPermissions = { perms -> requestPermissions.launch(perms) },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestSensorsPermission = {
                        requestSensorsPermission.launch("android.permission.OTHER_SENSORS")
                    },
                    onRequestExactAlarmPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            requestExactAlarmPermission.launch(intent)
                        }
                    },
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
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val requestExactAlarmPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val requestSensorsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
    onRequestHealthPermissions: (Set<String>) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestSensorsPermission: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onOpenSession: (LocalDate, String?, Boolean) -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val sleepEnabled by userPreferencesRepository.sleepEnabled.collectAsState(initial = true)
    val nutritionEnabled by userPreferencesRepository.nutritionEnabled.collectAsState(initial = true)

    val nutritionIndexBuildManager = remember(context) { NutritionIndexBuildManager(context) }
    val nutritionProvider = remember(context) { AssetNutritionProvider(context) }

    // One-shot initial redirect: if the natural start (Sleep/Home) is disabled, go somewhere sensible.
    LaunchedEffect(Unit) {
        if (!userPreferencesRepository.sleepEnabled.first()) {
            val dest = if (userPreferencesRepository.nutritionEnabled.first())
                Screen.Nutrition.route else Screen.Settings.route
            navController.navigate(dest) {
                popUpTo(Screen.Home.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // When sleep gets toggled off while the user is on the Sleep screen, redirect away.
    LaunchedEffect(sleepEnabled) {
        if (!sleepEnabled && navController.currentDestination?.route == Screen.Home.route) {
            val dest = if (nutritionEnabled) Screen.Nutrition.route else Screen.Settings.route
            navController.navigate(dest) {
                popUpTo(Screen.Home.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // When nutrition gets toggled off while the user is on the Food screen, redirect away.
    // Do NOT touch nav state for any other condition — that's what caused the food button to break.
    LaunchedEffect(nutritionEnabled) {
        if (!nutritionEnabled && navController.currentDestination?.route == Screen.Nutrition.route) {
            navController.navigate(Screen.Settings.route) {
                popUpTo(Screen.Home.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // Only show bottom bar on top-level screens
            val topLevelRoutes = buildList {
                if (sleepEnabled) add("home")
                if (nutritionEnabled) add("nutrition")
                add("settings")
            }
            if (currentRoute in topLevelRoutes) {
                NavigationBar {
                    // Sleep tab: only when sleep tracking enabled
                    if (sleepEnabled) {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = "Sleep") },
                            label = { Text("Sleep") },
                            selected = currentRoute == "home",
                            onClick = {
                                navController.navigate("home") {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                    // Food tab: only if nutrition enabled
                    if (nutritionEnabled) {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Add, contentDescription = "Food") },
                            label = { Text("Food") },
                            selected = currentRoute == "nutrition",
                            onClick = {
                                navController.navigate("nutrition") {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentRoute == "settings",
                        onClick = {
                            navController.navigate("settings") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
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
                    onNavigateToPermissions = { navController.navigate(Screen.Permissions.route) },
                    onOpenSession = onOpenSession
                )
            }
            composable(Screen.Nutrition.route) {
                NutritionHomeScreen(
                    healthConnectManager = healthConnectManager,
                    userPreferencesRepository = userPreferencesRepository,
                    nutritionIndexBuildManager = nutritionIndexBuildManager,
                    navController = navController,
                    onNavigateToPermissions = { navController.navigate(Screen.Permissions.route) }
                )
            }
            composable(Screen.NutritionDay.route) { backStack ->
                val dateStr = backStack.arguments?.getString("date") ?: return@composable
                val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@composable
                NutritionDayDetailScreen(
                    date = date,
                    healthConnectManager = healthConnectManager,
                    navController = navController
                )
            }
            composable(Screen.LogFood.route) { backStack ->
                val dateStr = backStack.arguments?.getString("date") ?: return@composable
                val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@composable
                LogFoodScreen(
                    date = date,
                    healthConnectManager = healthConnectManager,
                    userPreferencesRepository = userPreferencesRepository,
                    nutritionProvider = nutritionProvider,
                    navController = navController
                )
            }
            composable(Screen.ManualFoodEntry.route) { backStack ->
                val dateStr = backStack.arguments?.getString("date") ?: return@composable
                val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@composable
                ManualFoodEntryScreen(
                    date = date,
                    healthConnectManager = healthConnectManager,
                    userPreferencesRepository = userPreferencesRepository,
                    navController = navController
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    userPreferencesRepository = userPreferencesRepository,
                    onPermissions = { navController.navigate(Screen.Permissions.route) },
                    onSleepSettings = { navController.navigate(Screen.SleepSettings.route) },
                    onNutritionSettings = { navController.navigate(Screen.NutritionSettings.route) }
                )
            }
            composable(Screen.Permissions.route) {
                PermissionsScreen(
                    healthConnectManager = healthConnectManager,
                    userPreferencesRepository = userPreferencesRepository,
                    onBack = { navController.popBackStack() },
                    onRequestHealthPermissions = onRequestHealthPermissions,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onRequestSensorsPermission = onRequestSensorsPermission,
                    onRequestExactAlarmPermission = onRequestExactAlarmPermission
                )
            }
            composable(Screen.SleepSettings.route) {
                SleepSettingsScreen(
                    userPreferencesRepository = userPreferencesRepository,
                    onBack = { navController.popBackStack() },
                    onEditSleepStages = { navController.navigate(Screen.EditSleepStages.route) }
                )
            }
            composable(Screen.NutritionSettings.route) {
                NutritionSettingsScreen(
                    userPreferencesRepository = userPreferencesRepository,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.EditSleepStages.route) {
                EditSleepStagesScreen(
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
    onNavigateToPermissions: () -> Unit,
    onOpenSession: (LocalDate, String?, Boolean) -> Unit
) {
    var sleepSessions by remember { mutableStateOf<List<SleepSessionRecord>>(emptyList()) }
    var hasSleepWrite by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)
    val developerModeEnabled by userPreferencesRepository.developerModeEnabled.collectAsState(initial = false)
    val historyDisplayDays by userPreferencesRepository.historyDisplayDays.collectAsState(initial = 7)

    var sessionPickerDate by remember { mutableStateOf<LocalDate?>(null) }
    var napOrOvernightDate by remember { mutableStateOf<LocalDate?>(null) }

    // Load data and check write permission on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    hasSleepWrite = healthConnectManager.hasSleepWritePermission()
                    if (hasSleepWrite == true) {
                        val endTime = Instant.now()
                        val startTime = endTime.minus(Duration.ofDays(historyDisplayDays.toLong()))
                        val result = healthConnectManager.getSleepSessions(startTime, endTime)
                        sleepSessions = result.getOrDefault(emptyList())
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Sleep Tracker") })
        },
        floatingActionButton = {
            if (hasSleepWrite == true) {
                ExtendedFloatingActionButton(
                    text = { Text("Add Nap") },
                    icon = { Icon(Icons.Default.Add, contentDescription = "Add Nap") },
                    onClick = { onOpenSession(LocalDate.now(), null, true) }
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Banner when write permission is missing
            if (hasSleepWrite == false) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Sleep write permission not granted",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Sleep logs cannot be saved until Health Connect write access is granted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = onNavigateToPermissions) {
                            Text("Open Permissions", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

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

                    val writeGranted = hasSleepWrite == true
                    SleepDayCard(
                        date = date,
                        isToday = isToday,
                        napSessions = napSessions,
                        overnightSession = overnightSessions.firstOrNull(),
                        developerModeEnabled = developerModeEnabled,
                        rolloverHour = rolloverHour,
                        writePermissionGranted = writeGranted,
                        onClick = {
                            if (!writeGranted) return@SleepDayCard
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
    writePermissionGranted: Boolean = true,
    onClick: () -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
    val dateString = date.format(formatter)
    val zoneId = ZoneId.systemDefault()

    val hasNaps = napSessions.isNotEmpty()
    val hasOvernight = overnightSession != null
    // Today's card is clickable only if naps exist; also requires write permission
    val clickEnabled = writePermissionGranted && (if (isToday) hasNaps else true)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = clickEnabled) { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (!writePermissionGranted || (isToday && !hasNaps))
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



sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Nutrition : Screen("nutrition")
    object Settings : Screen("settings")
    object Permissions : Screen("permissions")
    object SleepSettings : Screen("sleep_settings")
    object NutritionSettings : Screen("nutrition_settings")
    object EditSleepStages : Screen("edit_sleep_stages")
    object NutritionDay : Screen("nutrition/day/{date}") {
        fun route(date: LocalDate) = "nutrition/day/$date"
    }
    object LogFood : Screen("nutrition/log/{date}") {
        fun route(date: LocalDate) = "nutrition/log/$date"
    }
    object ManualFoodEntry : Screen("nutrition/manual/{date}") {
        fun route(date: LocalDate) = "nutrition/manual/$date"
    }
}
