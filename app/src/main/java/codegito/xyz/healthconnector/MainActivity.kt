package codegito.xyz.healthconnector

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
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
import codegito.xyz.healthconnector.ui.EditSleepStagesScreen
import codegito.xyz.healthconnector.ui.SettingsScreen
import codegito.xyz.healthconnector.ui.theme.SleepTrackerTheme
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var userPreferencesRepository: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        healthConnectManager = HealthConnectManager(this)
        userPreferencesRepository = UserPreferencesRepository(this)

        // Ensure sleep tracking service is running
        ensureServiceRunning()

        setContent {
            SleepTrackerTheme {
                MainApp(
                    healthConnectManager = healthConnectManager,
                    userPreferencesRepository = userPreferencesRepository,
                    onManagePermissions = { requestPermissions.launch(healthConnectManager.permissions) },
                    onDayClick = { date ->
                        val intent = Intent(this, SleepDataLogger::class.java).apply {
                            putExtra("target_date_millis", date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
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
        if (!isServiceRunning(SleepTrackingService::class.java)) {
            val serviceIntent = Intent(this, SleepTrackingService::class.java)
            startForegroundService(serviceIntent)
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
    onDayClick: (LocalDate) -> Unit
) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

             // Only show bottom bar on top-level screens
            val topLevelRoutes = listOf("home", "settings")
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    healthConnectManager = healthConnectManager,
                    userPreferencesRepository = userPreferencesRepository,
                    onManagePermissions = onManagePermissions,
                    onDayClick = onDayClick
                )
            }
            composable("settings") {
                SettingsScreen(
                    healthConnectManager = healthConnectManager,
                    userPreferencesRepository = userPreferencesRepository,
                    onManagePermissions = onManagePermissions,
                    onEditSleepStages = { navController.navigate("edit_sleep_stages") }
                )
            }
            composable("edit_sleep_stages") {
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
    onManagePermissions: () -> Unit,
    onDayClick: (LocalDate) -> Unit
) {
    var sleepSessions by remember { mutableStateOf<List<SleepSessionRecord>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe rollover hour
    val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)

    // Request permissions on first launch
    LaunchedEffect(Unit) {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(healthConnectManager.permissions)) {
            onManagePermissions()
        }
    }

    // Load data on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    val endTime = Instant.now()
                    val startTime = endTime.minus(Duration.ofDays(7))
                    sleepSessions = healthConnectManager.getSleepSessions(startTime, endTime)
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
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            val today = LocalDate.now()
            val weekDays = (0..6).map { today.minusDays(it.toLong()) }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(weekDays) { date ->
                    val isToday = date == today
                    
                    val zoneId = ZoneId.systemDefault()
                    val sessionForDate = sleepSessions.find { session ->
                        // Rollover logic:
                        // A session belongs to "Night of [Date]" if it starts between:
                        // [Date at rolloverHour] and [Date+1 at rolloverHour]
                        // e.g., if rollover is 12:00 (noon), night of 8th is 8th 12:00 -> 9th 12:00
                        // e.g., if rollover is 04:00 (4 AM), night of 8th is 8th 04:00 -> 9th 04:00 (roughly covering the night)
                        // Actually, standard logic for "Night of X" usually means the night *following* day X.
                        // So if I go to bed on Feb 8 at 11 PM, it belongs to night of Feb 8.
                        // If I go to bed on Feb 9 at 1 AM, it belongs to night of Feb 8.
                        // So the "day boundary" is effectively shifted.
                        // If rollover is 4 AM, then anything before 4 AM Feb 9 belongs to Feb 8.
                         
                        val startBound = date.atTime(rolloverHour, 0).atZone(zoneId).toInstant()
                        val endBound = date.plusDays(1).atTime(rolloverHour, 0).atZone(zoneId).toInstant()
                        
                        session.startTime.isAfter(startBound) && session.startTime.isBefore(endBound)
                    }

                    SleepDayCard(
                        date = date,
                        isToday = isToday,
                        session = sessionForDate,
                        onClick = { onDayClick(date) }
                    )
                }
            }
        }
    }
}

@Composable
fun SleepDayCard(
    date: LocalDate,
    isToday: Boolean,
    session: SleepSessionRecord?,
    onClick: () -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
    val dateString = date.format(formatter)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isToday) { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isToday) MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f) 
                             else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
        ) {
            Text(
                text = "Night of $dateString",
                style = MaterialTheme.typography.titleMedium,
                color = if (isToday) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.5f) 
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (session != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val duration = Duration.between(session.startTime, session.endTime)
                val hours = duration.toHours()
                val minutes = duration.toMinutesPart()
                
                // Calculate asleep time
                val asleepDuration = session.stages.sumOf { stage ->
                    if (stage.stage != SleepSessionRecord.STAGE_TYPE_AWAKE && 
                        stage.stage != SleepSessionRecord.STAGE_TYPE_OUT_OF_BED &&
                        stage.stage != SleepSessionRecord.STAGE_TYPE_UNKNOWN) {
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
            } else {
                 if (!isToday) {
                     Spacer(modifier = Modifier.height(4.dp))
                     Text(
                        text = "No data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f)
                     )
                 }
            }
        }
    }
}
