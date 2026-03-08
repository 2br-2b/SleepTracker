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
        val sortedEvents = events.sortedBy { it.timestampMillis }
        
        // 1. Identify Bedtime: Last 'LOCK' in bedtime window
        val bedtimeEvent = sortedEvents.filter { it.type == "LOCK" }
            .lastOrNull { event ->
                val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.timestampMillis), zoneId).toLocalTime()
                val mins = time.hour * 60 + time.minute
                isTimeInWindow(mins, bedtimeWindowStart, bedtimeWindowEnd)
            } ?: return null

        val bedtime = LocalDateTime.ofInstant(Instant.ofEpochMilli(bedtimeEvent.timestampMillis), zoneId)

        // 2. Identify Wakeup: First 'UNLOCK' or 'PRESENT' in wakeup window after bedtime
        val wakeupEvent = findWakeupEvent(
            events = sortedEvents,
            bedtimeMillis = bedtimeEvent.timestampMillis,
            wakeupWindowStart = wakeupWindowStart,
            wakeupWindowEnd = wakeupWindowEnd,
            awakeningThresholdMinutes = awakeningThresholdMinutes,
            zoneId = zoneId
        ) ?: return null

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
            
            var segmentStart = sleepingStartTime
            val temporaryAwakenings = findTemporaryAwakenings(
                events = sortedEvents,
                startMillis = sleepingStartTime.atZone(zoneId).toInstant().toEpochMilli(),
                wakeupMillis = wakeupEvent.timestampMillis,
                awakeningThresholdMinutes = awakeningThresholdMinutes
            )

            for ((awakeStartMillis, asleepAgainMillis) in temporaryAwakenings) {
                val awakeStart = LocalDateTime.ofInstant(Instant.ofEpochMilli(awakeStartMillis), zoneId)
                val asleepAgain = LocalDateTime.ofInstant(Instant.ofEpochMilli(asleepAgainMillis), zoneId)

                if (segmentStart.isBefore(awakeStart)) {
                    segments.add(
                        SleepSegment(
                            endTime = awakeStart,
                            sleepStage = SleepSessionRecord.STAGE_TYPE_SLEEPING
                        )
                    )
                }

                if (awakeStart.isBefore(asleepAgain)) {
                    segments.add(
                        SleepSegment(
                            endTime = asleepAgain,
                            sleepStage = SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED
                        )
                    )
                }

                segmentStart = asleepAgain
            }

            if (segmentStart.isBefore(wakeupTime)) {
                segments.add(
                    SleepSegment(
                        endTime = wakeupTime,
                        sleepStage = SleepSessionRecord.STAGE_TYPE_SLEEPING
                    )
                )
            }
        } else {
            // Very short sleep? Just mark as awake then sleeping at wakeup? 
            // Or just return null to fallback to template.
            return null 
        }

        return Pair(bedtime, segments)
    }

    private fun findWakeupEvent(
        events: List<ScreenEvent>,
        bedtimeMillis: Long,
        wakeupWindowStart: Int,
        wakeupWindowEnd: Int,
        awakeningThresholdMinutes: Int,
        zoneId: ZoneId
    ): ScreenEvent? {
        val wakeCandidates = events.filter { event ->
            if (event.type != "UNLOCK" && event.type != "PRESENT") return@filter false

            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.timestampMillis), zoneId)
            val mins = dt.hour * 60 + dt.minute
            event.timestampMillis > bedtimeMillis && isTimeInWindow(mins, wakeupWindowStart, wakeupWindowEnd)
        }

        if (wakeCandidates.isEmpty()) return null

        for (candidate in wakeCandidates) {
            val nextLock = events.firstOrNull {
                it.type == "LOCK" && it.timestampMillis > candidate.timestampMillis
            }

            if (nextLock == null) return candidate

            val lockedAgainMinutes = Duration.between(
                Instant.ofEpochMilli(candidate.timestampMillis),
                Instant.ofEpochMilli(nextLock.timestampMillis)
            ).toMinutes()

            if (lockedAgainMinutes > awakeningThresholdMinutes) {
                return candidate
            }
        }

        return wakeCandidates.last()
    }

    /**
     * Finds temporary awakenings (UNLOCK→LOCK pairs) within the sleep period and merges
     * consecutive ones that are close together.
     *
     * **Merging rule:** If the gap between the end of one awakening (LOCK) and the start of the
     * next (UNLOCK) is ≤ [awakeningThresholdMinutes], the two awakenings are treated as one
     * continuous wake period (from the first UNLOCK to the last LOCK). If the gap is greater than
     * the threshold, they are kept as separate awakenings.
     *
     * This means the threshold controls how far apart two brief wake-ups must be before they are
     * counted individually rather than merged into a single longer one.
     */
    private fun findTemporaryAwakenings(
        events: List<ScreenEvent>,
        startMillis: Long,
        wakeupMillis: Long,
        awakeningThresholdMinutes: Int
    ): List<Pair<Long, Long>> {
        // Step 1: Collect all raw UNLOCK→LOCK pairs within the sleep window.
        val rawAwakenings = mutableListOf<Pair<Long, Long>>()

        for (i in events.indices) {
            val event = events[i]
            if (event.timestampMillis < startMillis || event.timestampMillis >= wakeupMillis) continue
            if (event.type != "UNLOCK" && event.type != "PRESENT") continue

            val nextLock = events.drop(i + 1).firstOrNull {
                it.type == "LOCK" && it.timestampMillis > event.timestampMillis && it.timestampMillis < wakeupMillis
            } ?: continue

            rawAwakenings.add(event.timestampMillis to nextLock.timestampMillis)
        }

        if (rawAwakenings.isEmpty()) return emptyList()

        // Step 2: Merge consecutive awakenings whose gap is within the threshold.
        val thresholdMillis = awakeningThresholdMinutes * 60_000L
        val merged = mutableListOf<Pair<Long, Long>>()
        var (mergeStart, mergeEnd) = rawAwakenings[0]

        for (i in 1 until rawAwakenings.size) {
            val (nextStart, nextEnd) = rawAwakenings[i]
            val gap = nextStart - mergeEnd
            if (gap <= thresholdMillis) {
                // Close enough — extend the current awakening window.
                mergeEnd = nextEnd
            } else {
                merged.add(mergeStart to mergeEnd)
                mergeStart = nextStart
                mergeEnd = nextEnd
            }
        }
        merged.add(mergeStart to mergeEnd)

        return merged
    }

    private fun isTimeInWindow(time: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            time in start..end
        } else {
            time >= start || time <= end
        }
    }
}
