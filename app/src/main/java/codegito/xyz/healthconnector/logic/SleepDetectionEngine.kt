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
                            sleepStage = SleepSessionRecord.STAGE_TYPE_AWAKE
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

    private fun findTemporaryAwakenings(
        events: List<ScreenEvent>,
        startMillis: Long,
        wakeupMillis: Long,
        awakeningThresholdMinutes: Int
    ): List<Pair<Long, Long>> {
        val awakenings = mutableListOf<Pair<Long, Long>>()
        var index = 0

        while (index < events.size) {
            val event = events[index]
            if (event.timestampMillis < startMillis || event.timestampMillis >= wakeupMillis) {
                index++
                continue
            }

            if (event.type == "UNLOCK" || event.type == "PRESENT") {
                val nextLock = events.drop(index + 1).firstOrNull {
                    it.type == "LOCK" && it.timestampMillis > event.timestampMillis && it.timestampMillis < wakeupMillis
                }

                if (nextLock != null) {
                    val awakeDuration = Duration.between(
                        Instant.ofEpochMilli(event.timestampMillis),
                        Instant.ofEpochMilli(nextLock.timestampMillis)
                    ).toMinutes()

                    if (awakeDuration in 1..awakeningThresholdMinutes.toLong()) {
                        awakenings.add(event.timestampMillis to nextLock.timestampMillis)
                    }
                }
            }

            index++
        }

        return awakenings
    }

    private fun isTimeInWindow(time: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            time in start..end
        } else {
            time >= start || time <= end
        }
    }
}
