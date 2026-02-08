package codegito.xyz.healthconnector

import java.time.Duration
import java.time.LocalDateTime

/**
 * Represents a complete sleep log with a bedtime and continuous segments.
 * Each segment runs from its implicit start time to its end time with no gaps.
 */
data class SleepLog(
    val bedtime: LocalDateTime,
    val segments: List<SleepSegment>
)

/**
 * Represents a single sleep segment.
 * The start time is implicit (either bedtime or previous segment's end time).
 * Only the end time is stored.
 */
data class SleepSegment(
    val endTime: LocalDateTime,
    val sleepStage: Int  // Health Connect sleep stage constant
)

/**
 * Sleep segment with calculated duration for display purposes.
 */
data class SleepSegmentWithDuration(
    val sleepStage: Int,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val durationMinutes: Long
)

/**
 * Calculate durations for all segments in a sleep log.
 */
fun SleepLog.calculateDurations(): List<SleepSegmentWithDuration> {
    val times = listOf(bedtime) + segments.map { it.endTime }

    return segments.mapIndexed { index, segment ->
        val startTime = times[index]
        val endTime = times[index + 1]
        val duration = Duration.between(startTime, endTime).toMinutes()

        SleepSegmentWithDuration(
            sleepStage = segment.sleepStage,
            startTime = startTime,
            endTime = endTime,
            durationMinutes = duration
        )
    }
}

/**
 * Format duration in minutes to human-readable string.
 */
fun Long.formatDuration(): String {
    val hours = this / 60
    val minutes = this % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}min"
        hours > 0 -> "${hours}h"
        else -> "${minutes}min"
    }
}
