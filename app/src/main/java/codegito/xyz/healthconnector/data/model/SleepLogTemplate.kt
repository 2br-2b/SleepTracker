package codegito.xyz.healthconnector.data.model

import androidx.health.connect.client.records.SleepSessionRecord
import kotlinx.serialization.Serializable

@Serializable
data class SleepLogTemplate(
    val bedtimeOffsetMinutes: Int = 0, // Minutes from midnight (or relative day start) - Actually easier if we just say "Bedtime is at X"
    val segments: List<TemplateSegment>
)

@Serializable
data class TemplateSegment(
    val startOffsetMinutes: Int, // Minutes from bedtime
    val endOffsetMinutes: Int,   // Minutes from bedtime
    val sleepStage: Int          // Health Connect sleep stage constant
)

/**
 * Default template: Bedtime at 00:00, Wakeup at 08:00
 */
fun getDefaultTemplate(): SleepLogTemplate = SleepLogTemplate(
    bedtimeOffsetMinutes = 0, // Midnight
    segments = listOf(
        // 0 to 15 mins: Awake in bed
        TemplateSegment(0, 15, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
        // 15 to 480 mins (8 hours): Sleeping
        TemplateSegment(15, 480, SleepSessionRecord.STAGE_TYPE_SLEEPING)
    )
)
