package codegito.xyz.healthconnector.data

import kotlinx.serialization.Serializable
import androidx.health.connect.client.records.SleepSessionRecord

@Serializable
data class SleepStageConfig(
    val healthConnectType: Int, // Maps to SleepSessionRecord.STAGE_TYPE_*
    val customEmoji: String? = null,
    val isEnabled: Boolean = true
) {
    val name: String
        get() = getStageName(healthConnectType)

    val emoji: String
        get() = customEmoji ?: getStageEmoji(healthConnectType)

    companion object {
        fun getStageName(type: Int): String = when (type) {
            SleepSessionRecord.STAGE_TYPE_AWAKE -> "Awake"
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> "Sleeping"
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "Awake out of bed"
            SleepSessionRecord.STAGE_TYPE_LIGHT -> "Light sleep"
            SleepSessionRecord.STAGE_TYPE_DEEP -> "Deep sleep"
            SleepSessionRecord.STAGE_TYPE_REM -> "REM sleep"
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "Awake in bed"
            else -> "Unknown"
        }

        fun getStageEmoji(type: Int): String = when (type) {
            SleepSessionRecord.STAGE_TYPE_AWAKE -> "☀️"
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "🥱"
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> "🛌"
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "🚶"
            SleepSessionRecord.STAGE_TYPE_LIGHT -> "🌙"
            SleepSessionRecord.STAGE_TYPE_DEEP -> "💤"
            SleepSessionRecord.STAGE_TYPE_REM -> "🌈"
            else -> "❓"
        }
    }
}

// Default Configuration: Sleeping -> Awake -> Awake in Bed -> Out of Bed -> Unknown -> Light -> Deep -> REM
fun getDefaultSleepStages(): List<SleepStageConfig> = listOf(
    SleepStageConfig(SleepSessionRecord.STAGE_TYPE_SLEEPING, isEnabled = true),
    SleepStageConfig(SleepSessionRecord.STAGE_TYPE_AWAKE, isEnabled = false),
    SleepStageConfig(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, isEnabled = true),
    SleepStageConfig(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED, isEnabled = true),
    SleepStageConfig(SleepSessionRecord.STAGE_TYPE_UNKNOWN, isEnabled = true),
    SleepStageConfig(SleepSessionRecord.STAGE_TYPE_LIGHT, isEnabled = false),
    SleepStageConfig(SleepSessionRecord.STAGE_TYPE_DEEP, isEnabled = false),
    SleepStageConfig(SleepSessionRecord.STAGE_TYPE_REM, isEnabled = false),
)
