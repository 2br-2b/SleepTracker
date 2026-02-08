package codegito.xyz.healthconnector

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import codegito.xyz.healthconnector.ui.theme.SleepTrackerTheme
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private lateinit var healthConnectManager: HealthConnectManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        healthConnectManager = HealthConnectManager(this)

        // Ensure sleep tracking service is running
        ensureServiceRunning()

        setContent {
            SleepTrackerTheme {
                SleepTrackerApp(
                    healthConnectManager = healthConnectManager,
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
                 // Check permissions again / Refresh UI
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTrackerApp(
    healthConnectManager: HealthConnectManager,
    onManagePermissions: () -> Unit,
    onDayClick: (LocalDate) -> Unit
) {
    var sleepSessions by remember { mutableStateOf<List<SleepSessionRecord>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Request permissions on first launch
    LaunchedEffect(Unit) {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(healthConnectManager.permissions)) {
            onManagePermissions()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

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
                title = { Text("Sleep Tracker") },
                actions = {
                    IconButton(onClick = {
                        val intent = Intent(healthConnectManager.context, OnboardingActivity::class.java)
                        healthConnectManager.context.startActivity(intent)
                    }) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Permissions")
                    }
                }
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
                    
                    // Filter sessions that started on this date (or ended next morning)
                    // Heuristic: Session belongs to "Night of [Date]" if it starts between [Date 12:00] and [Date+1 12:00]
                    // Or simpler: Starts on [Date] PM or [Date+1] AM before noon.
                    val zoneId = ZoneId.systemDefault()
                    val sessionForDate = sleepSessions.find { session ->
                        val sessionDate = session.startTime.atZone(zoneId).toLocalDate()
                        val sessionTime = session.startTime.atZone(zoneId).toLocalTime()
                        
                        // Example logic: if session started on date (PM) or date+1 (AM < 12)
                        // Actually, simplified: check if session start is within [Date 12:00, Date+1 12:00]
                        val startBound = date.atTime(2, 0).atZone(zoneId).toInstant()
                        val endBound = date.plusDays(1).atTime(2, 0).atZone(zoneId).toInstant()
                        
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
