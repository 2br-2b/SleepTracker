package codegito.xyz.healthconnector

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.db.SleepEventDatabase
import codegito.xyz.healthconnector.data.model.SleepDetectionMode
import codegito.xyz.healthconnector.data.model.SleepLogTemplate
import codegito.xyz.healthconnector.logic.SleepDetectionEngine
import codegito.xyz.healthconnector.ui.SleepLogEditor
import codegito.xyz.healthconnector.ui.theme.SleepTrackerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SleepDataLogger : ComponentActivity() {

    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private lateinit var healthConnectManager: HealthConnectManager

    private var overwriteExisting = false
    private var initialBedtime: LocalDateTime? = null
    private var initialSegments: List<SleepSegment>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        userPreferencesRepository = UserPreferencesRepository(this)
        healthConnectManager = HealthConnectManager(this)

        val targetDateMillis = intent.getLongExtra("target_date_millis", -1L)
        val targetDate = if (targetDateMillis != -1L) {
            LocalDate.ofInstant(Instant.ofEpochMilli(targetDateMillis), ZoneId.systemDefault())
        } else {
            LocalDate.now().minusDays(1)
        }

        setContent {
            SleepTrackerTheme {
                val sleepStages by userPreferencesRepository.sleepStages.collectAsState(initial = emptyList())
                val rolloverHour by userPreferencesRepository.rolloverHour.collectAsState(initial = 2)
                
                val detectionMode by userPreferencesRepository.sleepDetectionMode.collectAsState(initial = SleepDetectionMode.AUTO)
                val manualTemplate by userPreferencesRepository.manualSleepTemplate.collectAsState(initial = null)
                val bedtimeStart by userPreferencesRepository.bedtimeWindowStart.collectAsState(initial = 21 * 60)
                val bedtimeEnd by userPreferencesRepository.bedtimeWindowEnd.collectAsState(initial = 2 * 60)
                val wakeupStart by userPreferencesRepository.wakeupWindowStart.collectAsState(initial = 5 * 60)
                val wakeupEnd by userPreferencesRepository.wakeupWindowEnd.collectAsState(initial = 12 * 60)
                val awakeningThreshold by userPreferencesRepository.awakeningThresholdMinutes.collectAsState(initial = 60)
                val defaultAwakeToAsleep by userPreferencesRepository.defaultAwakeToAsleepMinutes.collectAsState(initial = 15)

                var isLoading by remember { mutableStateOf(true) }
                var hcRecordsForDay by remember { mutableStateOf<List<androidx.health.connect.client.records.SleepSessionRecord>>(emptyList()) }

                LaunchedEffect(targetDate, sleepStages, rolloverHour, detectionMode) {
                    if (sleepStages.isEmpty()) return@LaunchedEffect
                    
                    val zoneId = ZoneId.systemDefault()
                    val startBound = targetDate.atTime(rolloverHour, 0).atZone(zoneId).toInstant()
                    val endBound = targetDate.plusDays(1).atTime(rolloverHour, 0).atZone(zoneId).toInstant()
                    
                    val records = healthConnectManager.getSleepSessions(startBound, endBound)
                    hcRecordsForDay = records

                    if (records.isNotEmpty()) {
                        val firstSession = records.first()
                        initialBedtime = LocalDateTime.ofInstant(firstSession.startTime, zoneId)
                        initialSegments = firstSession.stages.map { stage ->
                            SleepSegment(
                                endTime = LocalDateTime.ofInstant(stage.endTime, zoneId),
                                sleepStage = stage.stage
                            )
                        }
                    } else if (detectionMode == SleepDetectionMode.AUTO) {
                        val db = SleepEventDatabase.getDatabase(this@SleepDataLogger)
                        val eventStart = startBound.toEpochMilli() - 4 * 60 * 60 * 1000
                        val eventEnd = endBound.toEpochMilli() + 4 * 60 * 60 * 1000
                        val events = db.screenEventDao().getEventsInRange(eventStart, eventEnd).first()
                        
                        val detected = SleepDetectionEngine.detectSleep(
                            events = events,
                            bedtimeWindowStart = bedtimeStart,
                            bedtimeWindowEnd = bedtimeEnd,
                            wakeupWindowStart = wakeupStart,
                            wakeupWindowEnd = wakeupEnd,
                            awakeningThresholdMinutes = awakeningThreshold,
                            defaultAwakeMinutes = defaultAwakeToAsleep,
                            targetDate = targetDate
                        )
                        
                        if (detected != null) {
                            initialBedtime = detected.first
                            initialSegments = detected.second
                        }
                    }
                    
                    if (initialBedtime == null) {
                        manualTemplate?.let { template: SleepLogTemplate ->
                            val baseBedtime = targetDate.atTime(template.bedtimeOffsetMinutes / 60, template.bedtimeOffsetMinutes % 60)
                            initialBedtime = baseBedtime
                            initialSegments = template.segments.map { ts ->
                                SleepSegment(
                                    endTime = baseBedtime.plusMinutes(ts.endOffsetMinutes.toLong()),
                                    sleepStage = ts.sleepStage
                                )
                            }
                        } ?: run {
                            initialBedtime = targetDate.atTime(0, 0)
                            initialSegments = listOf(
                                SleepSegment(
                                    endTime = targetDate.plusDays(1).atTime(8, 0),
                                    sleepStage = androidx.health.connect.client.records.SleepSessionRecord.STAGE_TYPE_SLEEPING
                                )
                            )
                        }
                    }
                    isLoading = false
                }

                if (!isLoading && sleepStages.isNotEmpty()) {
                    SleepLogEditor(
                        title = "Logging for Night of ${targetDate.format(DateTimeFormatter.ofPattern("MMM d"))}",
                        initialBedtime = initialBedtime!!,
                        initialSegments = initialSegments!!,
                        sleepStages = sleepStages,
                        showOverwriteOption = hcRecordsForDay.isNotEmpty(),
                        onOverwriteChanged = { overwriteExisting = it },
                        onSave = { bedtime, segments ->
                            saveSleepLog(targetDate, bedtime, segments, rolloverHour)
                        },
                        onCancel = { finish() }
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    private fun saveSleepLog(targetDate: LocalDate, bedtime: LocalDateTime, segments: List<SleepSegment>, rolloverHour: Int) {
        lifecycleScope.launch {
            try {
                if (overwriteExisting) {
                    val zoneId = ZoneId.systemDefault()
                    val startBound = targetDate.atTime(rolloverHour, 0).atZone(zoneId).toInstant()
                    val endBound = targetDate.plusDays(1).atTime(rolloverHour, 0).atZone(zoneId).toInstant()
                    healthConnectManager.deleteSleepSessions(startBound, endBound)
                }

                val zoneId = ZoneId.systemDefault()
                val startInstant = bedtime.atZone(zoneId).toInstant()
                val endInstant = segments.last().endTime.atZone(zoneId).toInstant()
                
                val stages = mutableListOf<androidx.health.connect.client.records.SleepSessionRecord.Stage>()
                var currentStartTime = bedtime
                
                segments.forEach { segment ->
                    val segmentStartInstant = currentStartTime.atZone(zoneId).toInstant()
                    val segmentEndInstant = segment.endTime.atZone(zoneId).toInstant()
                    
                    stages.add(
                        androidx.health.connect.client.records.SleepSessionRecord.Stage(
                            startTime = segmentStartInstant,
                            endTime = segmentEndInstant,
                            stage = segment.sleepStage
                        )
                    )
                    currentStartTime = segment.endTime
                }

                val record = androidx.health.connect.client.records.SleepSessionRecord(
                    startTime = startInstant,
                    startZoneOffset = zoneId.rules.getOffset(startInstant),
                    endTime = endInstant,
                    endZoneOffset = zoneId.rules.getOffset(endInstant),
                    stages = stages
                )

                healthConnectManager.writeSleepSession(record)
                
                Toast.makeText(this@SleepDataLogger, "Saved sleep data", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@SleepDataLogger, "Error saving data: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }
    }
}
