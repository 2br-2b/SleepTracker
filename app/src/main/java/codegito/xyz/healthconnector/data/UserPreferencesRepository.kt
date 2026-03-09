package codegito.xyz.healthconnector.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.health.connect.client.records.SleepSessionRecord
import codegito.xyz.healthconnector.data.model.SleepDetectionMode
import codegito.xyz.healthconnector.data.model.SleepLogTemplate
import codegito.xyz.healthconnector.data.model.TemplateSegment
import codegito.xyz.healthconnector.data.model.TimeRange
import codegito.xyz.healthconnector.data.model.TrackingType
import codegito.xyz.healthconnector.data.SleepStageConfig
import codegito.xyz.healthconnector.nutrition.domain.NutrientConfig
import codegito.xyz.healthconnector.nutrition.domain.NutrientDefaults
import codegito.xyz.healthconnector.nutrition.domain.NutritionUnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: UserPreferencesRepository? = null

        fun getInstance(context: Context): UserPreferencesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserPreferencesRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ── Keys ──────────────────────────────────────────────────────────────

    private val SLEEP_STAGES_JSON_KEY           = stringPreferencesKey("sleep_stages_json")
    private val ROLLOVER_HOUR_KEY               = intPreferencesKey("rollover_hour")
    private val SLEEP_DETECTION_MODE_KEY        = stringPreferencesKey("sleep_detection_mode")
    private val BEDTIME_WINDOW_START_KEY        = intPreferencesKey("bedtime_window_start")
    private val BEDTIME_WINDOW_END_KEY          = intPreferencesKey("bedtime_window_end")
    private val WAKEUP_WINDOW_START_KEY         = intPreferencesKey("wakeup_window_start")
    private val WAKEUP_WINDOW_END_KEY           = intPreferencesKey("wakeup_window_end")
    private val AWAKENING_LOGGING_ENABLED_KEY   = booleanPreferencesKey("awakening_logging_enabled")
    private val AWAKENING_THRESHOLD_MINUTES_KEY = intPreferencesKey("awakening_threshold_minutes")
    private val DEFAULT_AWAKE_TO_ASLEEP_MINUTES_KEY = intPreferencesKey("default_awake_to_asleep_minutes")
    private val MANUAL_SLEEP_TEMPLATE_JSON_KEY  = stringPreferencesKey("manual_sleep_template_json")
    private val REMINDER_FIRST_UNLOCK_ENABLED_KEY   = booleanPreferencesKey("reminder_first_unlock_enabled")
    private val REMINDER_DEADLINE_LOUD_ENABLED_KEY  = booleanPreferencesKey("reminder_deadline_loud_enabled")
    private val REMINDER_DEADLINE_SILENT_ENABLED_KEY = booleanPreferencesKey("reminder_deadline_silent_enabled")
    private val DEVELOPER_MODE_ENABLED_KEY      = booleanPreferencesKey("developer_mode_enabled")
    // Consolidated: single setting for both retention and display (was two separate keys)
    private val HISTORY_DAYS_KEY                = intPreferencesKey("history_days")
    private val ONBOARDING_COMPLETED_KEY        = booleanPreferencesKey("onboarding_completed")
    private val ENABLED_TRACKING_TYPES_KEY      = stringPreferencesKey("enabled_tracking_types")
    private val SLEEP_TRACKING_ENABLED_KEY      = booleanPreferencesKey("sleep_tracking_enabled")
    private val NUTRITION_TRACKING_ENABLED_KEY  = booleanPreferencesKey("nutrition_tracking_enabled")
    private val AMOLED_PITCH_BLACK_KEY          = booleanPreferencesKey("amoled_pitch_black")
    private val SHOW_ADVANCED_SETTINGS_KEY      = booleanPreferencesKey("show_advanced_settings")
    private val NUTRITION_PAST_DATE_RANGE_DAYS_KEY  = intPreferencesKey("nutrition_past_date_range_days")
    private val NUTRITION_MEAL_DURATION_MINUTES_KEY = intPreferencesKey("nutrition_meal_duration_minutes")
    private val NUTRITION_SNACK_DURATION_MINUTES_KEY = intPreferencesKey("nutrition_snack_duration_minutes")
    private val NUTRITION_ASK_EATEN_TIME_KEY    = booleanPreferencesKey("nutrition_ask_eaten_time")
    // Meal window ranges stored as minutes from midnight
    private val NUTRITION_BREAKFAST_START_KEY   = intPreferencesKey("nutrition_breakfast_start_min")
    private val NUTRITION_BREAKFAST_END_KEY     = intPreferencesKey("nutrition_breakfast_end_min")
    private val NUTRITION_LUNCH_START_KEY       = intPreferencesKey("nutrition_lunch_start_min")
    private val NUTRITION_LUNCH_END_KEY         = intPreferencesKey("nutrition_lunch_end_min")
    private val NUTRITION_DINNER_START_KEY      = intPreferencesKey("nutrition_dinner_start_min")
    private val NUTRITION_DINNER_END_KEY        = intPreferencesKey("nutrition_dinner_end_min")
    private val NUTRIENT_CONFIG_JSON_KEY        = stringPreferencesKey("nutrient_config_json")
    private val NUTRITION_UNIT_SYSTEM_KEY       = stringPreferencesKey("nutrition_unit_system")
    private val NUTRITION_APPLY_FILTER_TO_SEARCH_KEY = booleanPreferencesKey("nutrition_apply_filter_to_search")

    // ── Sleep flows ───────────────────────────────────────────────────────

    val rolloverHour: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[ROLLOVER_HOUR_KEY] ?: 2 }

    val sleepDetectionMode: Flow<SleepDetectionMode> = context.dataStore.data
        .map { prefs ->
            try { SleepDetectionMode.valueOf(prefs[SLEEP_DETECTION_MODE_KEY] ?: SleepDetectionMode.AUTO.name) }
            catch (_: Exception) { SleepDetectionMode.AUTO }
        }

    private val bedtimeWindowStart: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[BEDTIME_WINDOW_START_KEY] ?: (21 * 60) }
    private val bedtimeWindowEnd: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[BEDTIME_WINDOW_END_KEY] ?: (2 * 60) }
    private val wakeupWindowStart: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[WAKEUP_WINDOW_START_KEY] ?: (5 * 60) }
    private val wakeupWindowEnd: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[WAKEUP_WINDOW_END_KEY] ?: (12 * 60) }

    val bedtimeWindow: Flow<TimeRange> = combine(bedtimeWindowStart, bedtimeWindowEnd, ::TimeRange)
    val wakeupWindow: Flow<TimeRange>  = combine(wakeupWindowStart, wakeupWindowEnd, ::TimeRange)

    val awakeningLoggingEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[AWAKENING_LOGGING_ENABLED_KEY] ?: true }

    val awakeningThresholdMinutes: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[AWAKENING_THRESHOLD_MINUTES_KEY] ?: 10 } // Default 10 minutes

    val defaultAwakeToAsleepMinutes: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[DEFAULT_AWAKE_TO_ASLEEP_MINUTES_KEY] ?: 15 }

    val manualSleepTemplate: Flow<SleepLogTemplate> = context.dataStore.data
        .map { prefs ->
            prefs[MANUAL_SLEEP_TEMPLATE_JSON_KEY]?.let {
                runCatching { json.decodeFromString<SleepLogTemplate>(it) }.getOrNull()
            } ?: getDefaultTemplate()
        }

    val sleepStages: Flow<List<SleepStageConfig>> = context.dataStore.data
        .map { prefs ->
            prefs[SLEEP_STAGES_JSON_KEY]?.let {
                runCatching { json.decodeFromString<List<SleepStageConfig>>(it) }.getOrNull()
            } ?: getDefaultSleepStages()
        }

    // Consolidated: historyDays drives both display and raw-event retention
    val historyDays: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[HISTORY_DAYS_KEY] ?: 7 }

    // Keep old names as aliases so call sites compile without churn
    val dataRetentionDays: Flow<Int> get() = historyDays
    val historyDisplayDays: Flow<Int> get() = historyDays

    // ── Reminder flows ────────────────────────────────────────────────────

    val reminderFirstUnlockEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[REMINDER_FIRST_UNLOCK_ENABLED_KEY] ?: true }

    val reminderDeadlineLoudEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[REMINDER_DEADLINE_LOUD_ENABLED_KEY] ?: true }

    val reminderDeadlineSilentEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[REMINDER_DEADLINE_SILENT_ENABLED_KEY] ?: true }

    // ── App / display flows ───────────────────────────────────────────────

    val developerModeEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[DEVELOPER_MODE_ENABLED_KEY] ?: false }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[ONBOARDING_COMPLETED_KEY] ?: false }

    val amoledPitchBlackEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[AMOLED_PITCH_BLACK_KEY] ?: false }

    val showAdvancedSettings: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[SHOW_ADVANCED_SETTINGS_KEY] ?: false }

    // ── Tracking type flows ───────────────────────────────────────────────

    val sleepEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[SLEEP_TRACKING_ENABLED_KEY] ?: true }

    val nutritionEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[NUTRITION_TRACKING_ENABLED_KEY] ?: true }

    // ── Nutrition flows ───────────────────────────────────────────────────

    // Consolidated: nutrition and sleep history use the same days setting
    val nutritionPastDateRangeDays: Flow<Int> get() = historyDays

    val nutritionMealDurationMinutes: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[NUTRITION_MEAL_DURATION_MINUTES_KEY] ?: 30 }

    val nutritionSnackDurationMinutes: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[NUTRITION_SNACK_DURATION_MINUTES_KEY] ?: 10 }

    val nutritionAskEatenTime: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[NUTRITION_ASK_EATEN_TIME_KEY] ?: false }

    val nutritionBreakfastRange: Flow<TimeRange> = combine(
        context.dataStore.data.map { it[NUTRITION_BREAKFAST_START_KEY] ?: (6 * 60) },
        context.dataStore.data.map { it[NUTRITION_BREAKFAST_END_KEY]   ?: (10 * 60) },
        ::TimeRange
    )

    val nutritionLunchRange: Flow<TimeRange> = combine(
        context.dataStore.data.map { it[NUTRITION_LUNCH_START_KEY] ?: (11 * 60) },
        context.dataStore.data.map { it[NUTRITION_LUNCH_END_KEY]   ?: (14 * 60) },
        ::TimeRange
    )

    val nutritionDinnerRange: Flow<TimeRange> = combine(
        context.dataStore.data.map { it[NUTRITION_DINNER_START_KEY] ?: (17 * 60) },
        context.dataStore.data.map { it[NUTRITION_DINNER_END_KEY]   ?: (21 * 60) },
        ::TimeRange
    )

    val nutrientConfig: Flow<List<NutrientConfig>> = context.dataStore.data
        .map { prefs ->
            prefs[NUTRIENT_CONFIG_JSON_KEY]?.let {
                runCatching { json.decodeFromString<List<NutrientConfig>>(it) }.getOrNull()
            } ?: NutrientDefaults.defaultConfig()
        }

    val nutritionUnitSystem: Flow<NutritionUnitSystem> = context.dataStore.data
        .map { prefs ->
            try { NutritionUnitSystem.valueOf(prefs[NUTRITION_UNIT_SYSTEM_KEY] ?: NutritionUnitSystem.US.name) }
            catch (_: Exception) { NutritionUnitSystem.US }
        }

    val nutritionApplyNutrientFilterToSearch: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[NUTRITION_APPLY_FILTER_TO_SEARCH_KEY] ?: false }

    // ── Sleep setters ─────────────────────────────────────────────────────

    suspend fun setRolloverHour(hour: Int) {
        context.dataStore.edit { prefs -> prefs[ROLLOVER_HOUR_KEY] = hour }
    }

    suspend fun setSleepDetectionMode(mode: SleepDetectionMode) {
        context.dataStore.edit { prefs -> prefs[SLEEP_DETECTION_MODE_KEY] = mode.name }
    }

    suspend fun setBedtimeWindow(range: TimeRange) {
        context.dataStore.edit { prefs ->
            prefs[BEDTIME_WINDOW_START_KEY] = range.startMinutes
            prefs[BEDTIME_WINDOW_END_KEY]   = range.endMinutes
        }
    }

    suspend fun setWakeupWindow(range: TimeRange) {
        context.dataStore.edit { prefs ->
            prefs[WAKEUP_WINDOW_START_KEY] = range.startMinutes
            prefs[WAKEUP_WINDOW_END_KEY]   = range.endMinutes
        }
    }

    suspend fun setAwakeningLoggingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AWAKENING_LOGGING_ENABLED_KEY] = enabled }
    }

    suspend fun setAwakeningThreshold(minutes: Int) {
        context.dataStore.edit { prefs -> prefs[AWAKENING_THRESHOLD_MINUTES_KEY] = minutes }
    }

    suspend fun setDefaultAwakeToAsleepMinutes(minutes: Int) {
        context.dataStore.edit { prefs -> prefs[DEFAULT_AWAKE_TO_ASLEEP_MINUTES_KEY] = minutes }
    }

    suspend fun saveManualTemplate(template: SleepLogTemplate) {
        context.dataStore.edit { prefs -> prefs[MANUAL_SLEEP_TEMPLATE_JSON_KEY] = json.encodeToString(template) }
    }

    suspend fun saveSleepStages(stages: List<SleepStageConfig>) {
        context.dataStore.edit { prefs -> prefs[SLEEP_STAGES_JSON_KEY] = json.encodeToString(stages) }
    }

    suspend fun setHistoryDays(days: Int) {
        context.dataStore.edit { prefs -> prefs[HISTORY_DAYS_KEY] = days }
    }

    // Alias setters so old call sites compile
    suspend fun setDataRetentionDays(days: Int) = setHistoryDays(days)
    suspend fun setHistoryDisplayDays(days: Int) = setHistoryDays(days)

    // ── Reminder setters ──────────────────────────────────────────────────

    suspend fun setReminderFirstUnlockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[REMINDER_FIRST_UNLOCK_ENABLED_KEY] = enabled }
    }

    suspend fun setReminderDeadlineLoudEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[REMINDER_DEADLINE_LOUD_ENABLED_KEY] = enabled }
    }

    suspend fun setReminderDeadlineSilentEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[REMINDER_DEADLINE_SILENT_ENABLED_KEY] = enabled }
    }

    // ── App / display setters ─────────────────────────────────────────────

    suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DEVELOPER_MODE_ENABLED_KEY] = enabled }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { prefs -> prefs[ONBOARDING_COMPLETED_KEY] = completed }
    }

    suspend fun setAmoledPitchBlackEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AMOLED_PITCH_BLACK_KEY] = enabled }
    }

    suspend fun setShowAdvancedSettings(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[SHOW_ADVANCED_SETTINGS_KEY] = show }
    }

    suspend fun setSleepEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SLEEP_TRACKING_ENABLED_KEY] = enabled }
    }

    suspend fun setNutritionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[NUTRITION_TRACKING_ENABLED_KEY] = enabled }
    }

    // ── Nutrition setters ─────────────────────────────────────────────────

    // Consolidated with historyDays
    suspend fun setNutritionPastDateRangeDays(days: Int) = setHistoryDays(days)

    suspend fun setNutritionMealDurationMinutes(minutes: Int) {
        context.dataStore.edit { prefs -> prefs[NUTRITION_MEAL_DURATION_MINUTES_KEY] = minutes }
    }

    suspend fun setNutritionSnackDurationMinutes(minutes: Int) {
        context.dataStore.edit { prefs -> prefs[NUTRITION_SNACK_DURATION_MINUTES_KEY] = minutes }
    }

    suspend fun setNutritionAskEatenTime(ask: Boolean) {
        context.dataStore.edit { prefs -> prefs[NUTRITION_ASK_EATEN_TIME_KEY] = ask }
    }

    suspend fun setNutritionBreakfastRange(range: TimeRange) {
        context.dataStore.edit { prefs ->
            prefs[NUTRITION_BREAKFAST_START_KEY] = range.startMinutes
            prefs[NUTRITION_BREAKFAST_END_KEY]   = range.endMinutes
        }
    }

    suspend fun setNutritionLunchRange(range: TimeRange) {
        context.dataStore.edit { prefs ->
            prefs[NUTRITION_LUNCH_START_KEY] = range.startMinutes
            prefs[NUTRITION_LUNCH_END_KEY]   = range.endMinutes
        }
    }

    suspend fun setNutritionDinnerRange(range: TimeRange) {
        context.dataStore.edit { prefs ->
            prefs[NUTRITION_DINNER_START_KEY] = range.startMinutes
            prefs[NUTRITION_DINNER_END_KEY]   = range.endMinutes
        }
    }

    suspend fun saveNutrientConfig(config: List<NutrientConfig>) {
        context.dataStore.edit { prefs -> prefs[NUTRIENT_CONFIG_JSON_KEY] = json.encodeToString(config) }
    }

    suspend fun setNutritionUnitSystem(system: NutritionUnitSystem) {
        context.dataStore.edit { prefs -> prefs[NUTRITION_UNIT_SYSTEM_KEY] = system.name }
    }

    suspend fun setNutritionApplyNutrientFilterToSearch(apply: Boolean) {
        context.dataStore.edit { prefs -> prefs[NUTRITION_APPLY_FILTER_TO_SEARCH_KEY] = apply }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    suspend fun isAutoSleepDetectionActive(): Boolean =
        sleepDetectionMode.first() == SleepDetectionMode.AUTO

    suspend fun isAnyReminderEnabled(): Boolean =
        reminderFirstUnlockEnabled.first() ||
        reminderDeadlineLoudEnabled.first() ||
        reminderDeadlineSilentEnabled.first()

    private fun getDefaultTemplate() = SleepLogTemplate(
        bedtimeOffsetMinutes = 22 * 60,
        segments = listOf(TemplateSegment(0, 8 * 60, SleepSessionRecord.STAGE_TYPE_SLEEPING))
    )
}
