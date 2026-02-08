package codegito.xyz.healthconnector.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.health.connect.client.records.SleepSessionRecord
import codegito.xyz.healthconnector.data.model.SleepDetectionMode
import codegito.xyz.healthconnector.data.model.SleepLogTemplate
import codegito.xyz.healthconnector.data.model.TemplateSegment
import codegito.xyz.healthconnector.data.SleepStageConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) {
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    private val SLEEP_STAGES_JSON_KEY = stringPreferencesKey("sleep_stages_json")
    private val ROLLOVER_HOUR_KEY = intPreferencesKey("rollover_hour")
    
    // Auto Sleep Detection Settings
    private val SLEEP_DETECTION_MODE_KEY = stringPreferencesKey("sleep_detection_mode")
    private val BEDTIME_WINDOW_START_KEY = intPreferencesKey("bedtime_window_start")
    private val BEDTIME_WINDOW_END_KEY = intPreferencesKey("bedtime_window_end")
    private val WAKEUP_WINDOW_START_KEY = intPreferencesKey("wakeup_window_start")
    private val WAKEUP_WINDOW_END_KEY = intPreferencesKey("wakeup_window_end")
    private val AWAKENING_LOGGING_ENABLED_KEY = booleanPreferencesKey("awakening_logging_enabled")
    private val AWAKENING_THRESHOLD_MINUTES_KEY = intPreferencesKey("awakening_threshold_minutes")
    private val DEFAULT_AWAKE_TO_ASLEEP_MINUTES_KEY = intPreferencesKey("default_awake_to_asleep_minutes")
    private val MANUAL_SLEEP_TEMPLATE_JSON_KEY = stringPreferencesKey("manual_sleep_template_json")
    
    // Notifications
    private val REMINDER_FIRST_UNLOCK_ENABLED_KEY = booleanPreferencesKey("reminder_first_unlock_enabled")
    private val REMINDER_DEADLINE_LOUD_ENABLED_KEY = booleanPreferencesKey("reminder_deadline_loud_enabled")
    private val REMINDER_DEADLINE_SILENT_ENABLED_KEY = booleanPreferencesKey("reminder_deadline_silent_enabled")
    private val DEVELOPER_MODE_ENABLED_KEY = booleanPreferencesKey("developer_mode_enabled")

    val rolloverHour: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[ROLLOVER_HOUR_KEY] ?: 2 // Default to 2 AM
        }

    val sleepDetectionMode: Flow<SleepDetectionMode> = context.dataStore.data
        .map { preferences ->
            try {
                SleepDetectionMode.valueOf(preferences[SLEEP_DETECTION_MODE_KEY] ?: SleepDetectionMode.AUTO.name)
            } catch (e: Exception) {
                SleepDetectionMode.AUTO
            }
        }

    val bedtimeWindowStart: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[BEDTIME_WINDOW_START_KEY] ?: (21 * 60) } // Default 9 PM
    
    val bedtimeWindowEnd: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[BEDTIME_WINDOW_END_KEY] ?: (2 * 60) } // Default 2 AM (next day handle later)

    val wakeupWindowStart: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[WAKEUP_WINDOW_START_KEY] ?: (5 * 60) } // Default 5 AM
    
    val wakeupWindowEnd: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[WAKEUP_WINDOW_END_KEY] ?: (12 * 60) } // Default 12 PM

    val awakeningLoggingEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[AWAKENING_LOGGING_ENABLED_KEY] ?: true }

    val awakeningThresholdMinutes: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[AWAKENING_THRESHOLD_MINUTES_KEY] ?: 60 } // Default 1 hour

    val defaultAwakeToAsleepMinutes: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[DEFAULT_AWAKE_TO_ASLEEP_MINUTES_KEY] ?: 15 }

    val manualSleepTemplate: Flow<SleepLogTemplate> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[MANUAL_SLEEP_TEMPLATE_JSON_KEY]
            if (jsonString != null) {
                try {
                    json.decodeFromString<SleepLogTemplate>(jsonString)
                } catch (e: Exception) {
                    getDefaultTemplate()
                }
            } else {
                getDefaultTemplate()
            }
        }

    val sleepStages: Flow<List<SleepStageConfig>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[SLEEP_STAGES_JSON_KEY]
            if (jsonString != null) {
                try {
                    json.decodeFromString<List<SleepStageConfig>>(jsonString)
                } catch (e: Exception) {
                    codegito.xyz.healthconnector.data.getDefaultSleepStages()
                }
            } else {
                codegito.xyz.healthconnector.data.getDefaultSleepStages()
            }
        }

    val reminderFirstUnlockEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[REMINDER_FIRST_UNLOCK_ENABLED_KEY] ?: true }

    val reminderDeadlineLoudEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[REMINDER_DEADLINE_LOUD_ENABLED_KEY] ?: true }

    val reminderDeadlineSilentEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[REMINDER_DEADLINE_SILENT_ENABLED_KEY] ?: true }

    val developerModeEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[DEVELOPER_MODE_ENABLED_KEY] ?: false }

    suspend fun setRolloverHour(hour: Int) {
        context.dataStore.edit { preferences ->
            preferences[ROLLOVER_HOUR_KEY] = hour
        }
    }

    suspend fun setSleepDetectionMode(mode: SleepDetectionMode) {
        context.dataStore.edit { preferences ->
            preferences[SLEEP_DETECTION_MODE_KEY] = mode.name
        }
    }

    suspend fun setBedtimeWindow(startMinutes: Int, endMinutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[BEDTIME_WINDOW_START_KEY] = startMinutes
            preferences[BEDTIME_WINDOW_END_KEY] = endMinutes
        }
    }

    suspend fun setWakeupWindow(startMinutes: Int, endMinutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[WAKEUP_WINDOW_START_KEY] = startMinutes
            preferences[WAKEUP_WINDOW_END_KEY] = endMinutes
        }
    }

    suspend fun setAwakeningLoggingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AWAKENING_LOGGING_ENABLED_KEY] = enabled
        }
    }

    suspend fun setAwakeningThreshold(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[AWAKENING_THRESHOLD_MINUTES_KEY] = minutes
        }
    }

    suspend fun setDefaultAwakeToAsleepMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_AWAKE_TO_ASLEEP_MINUTES_KEY] = minutes
        }
    }

    suspend fun saveManualTemplate(template: SleepLogTemplate) {
        context.dataStore.edit { preferences ->
            preferences[MANUAL_SLEEP_TEMPLATE_JSON_KEY] = json.encodeToString(template)
        }
    }

    suspend fun saveSleepStages(stages: List<SleepStageConfig>) {
        context.dataStore.edit { preferences ->
            preferences[SLEEP_STAGES_JSON_KEY] = json.encodeToString(stages)
        }
    }


    suspend fun setReminderFirstUnlockEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[REMINDER_FIRST_UNLOCK_ENABLED_KEY] = enabled }
    }

    suspend fun setReminderDeadlineLoudEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[REMINDER_DEADLINE_LOUD_ENABLED_KEY] = enabled }
    }

    suspend fun setReminderDeadlineSilentEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[REMINDER_DEADLINE_SILENT_ENABLED_KEY] = enabled }
    }

    suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[DEVELOPER_MODE_ENABLED_KEY] = enabled }
    }

    suspend fun isAutoSleepDetectionActive(): Boolean {
        return sleepDetectionMode.first() == SleepDetectionMode.AUTO
    }

    suspend fun isAnyReminderEnabled(): Boolean {
        return reminderFirstUnlockEnabled.first() ||
               reminderDeadlineLoudEnabled.first() ||
               reminderDeadlineSilentEnabled.first()
    }

    private fun getDefaultTemplate(): SleepLogTemplate {
        return SleepLogTemplate(
            bedtimeOffsetMinutes = 22 * 60, // 10 PM
            segments = listOf(
                TemplateSegment(0, 8 * 60, SleepSessionRecord.STAGE_TYPE_SLEEPING) // 8 hours later
            )
        )
    }
}
