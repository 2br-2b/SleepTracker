package codegito.xyz.healthconnector

import android.content.Intent
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

    // ID of the specific existing session being edited (null if creating new)
    private var editingSessionId: String? = null
    private var initialBedtime: LocalDateTime? = null
    private var initialSegments: List<SleepSegment>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        userPreferencesRepository = UserPreferencesRepository.getInstance(this)
        healthConnectManager = HealthConnectManager(this)

        val targetDateMillis = intent.getLongExtra("target_date_millis", -1L)
        val targetDate = if (targetDateMillis != -1L) {
            LocalDate.ofInstant(Instant.ofEpochMilli(targetDateMillis), ZoneId.systemDefault())
        } else {
            LocalDate.now().minusDays(1)
        }

        val isNap = intent.getBooleanExtra("is_nap", false)
        val sessionId = intent.getStringExtra("session_id")

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

                LaunchedEffect(targetDate, sleepStages, rolloverHour, detectionMode) {
                    if (sleepStages.isEmpty()) return@LaunchedEffect

                    val zoneId = ZoneId.systemDefault()

                    if (sessionId != null) {
                        // Editing a specific existing session by ID
                        val result = healthConnectManager.getSleepSession(sessionId)
                        val session = result.getOrNull()
                        if (session != null) {
                            editingSessionId = session.metadata.id
                            initialBedtime = LocalDateTime.ofInstant(session.startTime, zoneId)
                            initialSegments = session.stages.map { stage ->
                                SleepSegment(
                                    endTime = LocalDateTime.ofInstant(stage.endTime, zoneId),
                                    sleepStage = stage.stage
                                )
                            }
                        }
                    } else if (isNap) {
                        // New nap: default to 1 hour ago → now
                        val now = LocalDateTime.now().withSecond(0).withNano(0)
                        val napStart = now.minusHours(1)
                        initialBedtime = napStart
                        initialSegments = listOf(
                            SleepSegment(
                                endTime = now,
                                sleepStage = androidx.health.connect.client.records.SleepSessionRecord.STAGE_TYPE_SLEEPING
                            )
                        )
                    } else {
                        // Regular overnight sleep: existing auto-detect or template logic
                        val startBound = targetDate.atTime(rolloverHour, 0).atZone(zoneId).toInstant()
                        val endBound = targetDate.plusDays(1).atTime(rolloverHour, 0).atZone(zoneId).toInstant()

                        val recordsResult = healthConnectManager.getSleepSessions(startBound, endBound)
                        val overnightRecords = recordsResult.getOrDefault(emptyList())
                            .filter { it.title != NAP_TITLE }

                        if (overnightRecords.isNotEmpty()) {
                            val firstSession = overnightRecords.first()
                            editingSessionId = firstSession.metadata.id
                            initialBedtime = LocalDateTime.ofInstant(firstSession.startTime, zoneId)
                            initialSegments = firstSession.stages.map { stage ->
                                SleepSegment(
                                    endTime = LocalDateTime.ofInstant(stage.endTime, zoneId),
                                    sleepStage = stage.stage
                                )
                            }
                        } else if (detectionMode == SleepDetectionMode.AUTO) {
                            val db = SleepEventDatabase.getDatabase(this@SleepDataLogger)
                            // Start at bedtime window start (on the previous calendar day for overnight windows)
                            // so early bedtimes aren't cut off by a hardcoded buffer.
                            val bedtimeDay = if (bedtimeStart > rolloverHour * 60) targetDate.minusDays(1) else targetDate
                            val bedtimeWindowStartInstant = bedtimeDay
                                .atTime(bedtimeStart / 60, bedtimeStart % 60)
                                .atZone(zoneId).toInstant()
                            val eventStart = bedtimeWindowStartInstant.toEpochMilli()
                            // Extend end to cover the full wakeup window so late wakeups aren't missed.
                            val wakeupWindowEndInstant = targetDate.plusDays(1)
                                .atTime(wakeupEnd / 60, wakeupEnd % 60)
                                .atZone(zoneId).toInstant()
                            val eventEnd = wakeupWindowEndInstant.toEpochMilli()
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
                                val baseBedtime = targetDate.atTime(
                                    template.bedtimeOffsetMinutes / 60,
                                    template.bedtimeOffsetMinutes % 60
                                )
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
                    }

                    isLoading = false
                }

                if (!isLoading && sleepStages.isNotEmpty()) {
                    val editorTitle = if (isNap) {
                        "Logging Nap for ${targetDate.format(DateTimeFormatter.ofPattern("MMM d"))}"
                    } else {
                        "Logging for Night of ${targetDate.format(DateTimeFormatter.ofPattern("MMM d"))}"
                    }

                    SleepLogEditor(
                        title = editorTitle,
                        initialBedtime = initialBedtime!!,
                        initialSegments = initialSegments!!,
                        sleepStages = sleepStages,
                        showNapBanner = isNap,
                        onSave = { bedtime, segments ->
                            saveSleepLog(bedtime, segments, isNap, rolloverHour)
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

    private fun saveSleepLog(
        bedtime: LocalDateTime,
        segments: List<SleepSegment>,
        isNap: Boolean,
        rolloverHour: Int
    ) {
        lifecycleScope.launch {
            try {
                // If editing an existing specific session, delete it first
                if (editingSessionId != null) {
                    val deleteResult = healthConnectManager.deleteSleepSession(editingSessionId!!)
                    if (deleteResult.isFailure) {
                        Toast.makeText(
                            this@SleepDataLogger,
                            "Error deleting existing session: ${deleteResult.exceptionOrNull()?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                }

                val title = if (isNap) NAP_TITLE else null
                val result = healthConnectManager.writeSleepLog(bedtime, segments, title)

                if (result.isSuccess) {
                    Toast.makeText(this@SleepDataLogger, "Saved sleep data", Toast.LENGTH_SHORT).show()
                    if (!isNap) {
                        val mode = userPreferencesRepository.sleepDetectionMode.first()
                        if (mode == SleepDetectionMode.AUTO) {
                            val stopIntent = Intent(this@SleepDataLogger, SleepTrackingService::class.java).apply {
                                action = "STOP_SERVICE"
                            }
                            startService(stopIntent)
                        }
                    }
                    finish()
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Toast.makeText(this@SleepDataLogger, "Error saving data: $error", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SleepDataLogger, "Unexpected error: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }
    }

    companion object {
        const val NAP_TITLE = "Nap"
    }
}
