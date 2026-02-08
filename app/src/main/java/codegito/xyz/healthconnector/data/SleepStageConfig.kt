package codegito.xyz.healthconnector.data

import kotlinx.serialization.Serializable
import androidx.health.connect.client.records.SleepSessionRecord

@Serializable
data class SleepStageConfig(
    val id: String, // Unique ID to track reordering
    val name: String,
    val healthConnectType: Int, // Maps to SleepSessionRecord.STAGE_TYPE_*
    val emoji: String,
    val isEnabled: Boolean = true
)

// Default Configuration
fun getDefaultSleepStages(): List<SleepStageConfig> = listOf(
    SleepStageConfig("awake", "Awake", SleepSessionRecord.STAGE_TYPE_AWAKE, "☀️"),
    SleepStageConfig("sleeping", "Sleeping", SleepSessionRecord.STAGE_TYPE_SLEEPING, "😴"),
    SleepStageConfig("out_of_bed", "Out of Bed", SleepSessionRecord.STAGE_TYPE_OUT_OF_BED, "🚶"),
    SleepStageConfig("light", "Light Sleep", SleepSessionRecord.STAGE_TYPE_LIGHT, "🌙"),
    SleepStageConfig("deep", "Deep Sleep", SleepSessionRecord.STAGE_TYPE_DEEP, "💤"),
    SleepStageConfig("rem", "REM Sleep", SleepSessionRecord.STAGE_TYPE_REM, "👁️"),
    SleepStageConfig("unknown", "Unknown", SleepSessionRecord.STAGE_TYPE_UNKNOWN, "❓")
)
