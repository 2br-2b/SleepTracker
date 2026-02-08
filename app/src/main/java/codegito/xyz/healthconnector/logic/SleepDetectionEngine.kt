package codegito.xyz.healthconnector.logic

import androidx.health.connect.client.records.SleepSessionRecord
import codegito.xyz.healthconnector.SleepSegment
import codegito.xyz.healthconnector.data.db.ScreenEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object SleepDetectionEngine {

    /**
     * Calculates sleep segments based on raw events and user settings.
     */
    fun detectSleep(
        events: List<ScreenEvent>,
        bedtimeWindowStart: Int, // minutes from midnight
        bedtimeWindowEnd: Int,
        wakeupWindowStart: Int,
        wakeupWindowEnd: Int,
        awakeningThresholdMinutes: Int,
        defaultAwakeMinutes: Int,
        targetDate: java.time.LocalDate
    ): Pair<LocalDateTime, List<SleepSegment>>? {
        val zoneId = ZoneId.systemDefault()
        
        // 1. Identify Bedtime: Last 'LOCK' in bedtime window
        val bedtimeEvent = events.filter { it.type == "LOCK" }
            .lastOrNull { event ->
                val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.timestampMillis), zoneId).toLocalTime()
                val mins = time.hour * 60 + time.minute
                isTimeInWindow(mins, bedtimeWindowStart, bedtimeWindowEnd)
            } ?: return null

        val bedtime = LocalDateTime.ofInstant(Instant.ofEpochMilli(bedtimeEvent.timestampMillis), zoneId)

        // 2. Identify Wakeup: First 'UNLOCK' or 'PRESENT' in wakeup window after bedtime
        val wakeupEvent = events.filter { it.type == "UNLOCK" || it.type == "PRESENT" }
            .firstOrNull { event ->
                val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.timestampMillis), zoneId)
                val mins = dt.hour * 60 + dt.minute
                dt.isAfter(bedtime) && isTimeInWindow(mins, wakeupWindowStart, wakeupWindowEnd)
            } ?: return null

        val wakeupTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(wakeupEvent.timestampMillis), zoneId)

        // 3. Build Segments
        val segments = mutableListOf<SleepSegment>()
        
        // Mandatory awake period at start
        val sleepingStartTime = bedtime.plusMinutes(defaultAwakeMinutes.toLong())
        if (sleepingStartTime.isBefore(wakeupTime)) {
            segments.add(
                SleepSegment(
                    endTime = sleepingStartTime,
                    sleepStage = SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED
                )
            )
            
            // Check for intervening awakenings
            var lastSegmentTime = sleepingStartTime
            val interveningEvents = events.filter { 
                it.timestampMillis > bedtimeEvent.timestampMillis + defaultAwakeMinutes * 60 * 1000 &&
                it.timestampMillis < wakeupEvent.timestampMillis
            }.sortedBy { it.timestampMillis }

            // Logic for awakenings: If we see an UNLOCK followed by a LOCK, and the gap is significant...
            // For now, let's just do a simple "Sleeping" segment and identify any "Present" time as Awake?
            // Actually, keep it simple for MVP: One big "Sleeping" block unless we find specific gaps.
            
            segments.add(
                SleepSegment(
                    endTime = wakeupTime,
                    sleepStage = SleepSessionRecord.STAGE_TYPE_SLEEPING
                )
            )
        } else {
            // Very short sleep? Just mark as awake then sleeping at wakeup? 
            // Or just return null to fallback to template.
            return null 
        }

        return Pair(bedtime, segments)
    }

    private fun isTimeInWindow(time: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            time in start..end
        } else {
            time >= start || time <= end
        }
    }
}
