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
import codegito.xyz.healthconnector.data.model.AiProvider
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
        const val DEFAULT_AI_BASE_SYSTEM_PROMPT =
            """You are SleepTracker's nutrition logging copilot. Your job is to transform natural-language food descriptions into high-quality logging decisions using candidate rows from the local nutrition database.

Behavior requirements:
1) Never treat meal labels or time phrases as foods (examples: breakfast, lunch, dinner, at 9, an hour ago, today).
2) Prioritize semantic food matching over token overlap.
3) If there is no strong direct match, decompose the mention into realistic edible sub-items (example: 'ham hot honey egg and cheese sandwich on a bagel' -> ham, egg, cheese, honey, bagel, sandwich) and resolve those.
4) Use serving-size math carefully. Prefer provided serving units/grams; if uncertain, make a conservative best estimate.
5) Do not hallucinate impossible foods from non-food context words.
6) Keep all timestamps in the past.
7) When follow-up questions are allowed, ask concise clarifying questions only when ambiguity materially changes nutrition. Otherwise proceed with best effort.
8) Be robust to duplicate/near-duplicate products in the DB and pick the most plausible candidate by food identity and serving context.

Structured output discipline:
- Always return strict JSON only when asked for structured output.
- No markdown, no prose outside required JSON."""
        const val DEFAULT_AI_SYSTEM_PROMPT = ""
        const val DEFAULT_AI_DECISION_PROMPT_TEMPLATE = """Task: choose the best nutrition candidate for a single food mention and provide normalization math.

Food mention details:
- mention text: "{{mention}}"
- user quantity: {{quantity}}
- user unit: {{unit}}

Candidate rows (indexed):
{{candidates}}

Decision procedure (follow in order):
1) Identify whether the mention is an actual food item, not a meal label/time phrase.
2) Compare identity fit (food type, preparation, ingredients, brand clues), then serving plausibility.
3) Prefer candidate rows whose serving/unit context is compatible with the mention.
4) If nothing is a real match, return candidateIndex=-1.
5) If a candidate matches, choose quantity/unit/multiplier so nutrition best reflects the user statement.

Return EXACTLY one JSON object with these keys only:
{"candidateIndex":int,"quantity":number|null,"unit":string|null,"multiplier":number|null}

Key semantics:
- candidateIndex: index of chosen candidate, or -1 if no valid match
- quantity: normalized quantity to use (null = keep caller quantity)
- unit: normalized unit string to use (null = keep caller unit)
- multiplier: global nutrient multiplier (null = 1.0)

Hard constraints:
- Output must be valid JSON object (no trailing text).
- Never output markdown/code fences."""
        const val DEFAULT_AI_REPAIR_PROMPT_TEMPLATE = """Your previous output could not be parsed by the app.

Error detail:
{{error}}

Previous output:
{{previous_output}}

You must now return EXACTLY one valid JSON object with this exact schema and key names:
{"candidateIndex":int,"quantity":number|null,"unit":string|null,"multiplier":number|null}

Rules:
- No markdown
- No commentary
- No extra keys
- No code fences
- JSON only"""

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
    private val GLOBAL_NETWORK_ENABLED_KEY      = booleanPreferencesKey("global_network_enabled")
    private val GLOBAL_AI_ENABLED_KEY           = booleanPreferencesKey("global_ai_enabled")
    private val AI_PROVIDER_KEY                 = stringPreferencesKey("ai_provider")
    private val AI_MODEL_KEY                    = stringPreferencesKey("ai_model")
    private val AI_API_KEY_KEY                  = stringPreferencesKey("ai_api_key")
    private val AI_BASE_URL_KEY                 = stringPreferencesKey("ai_base_url")
    private val AI_TEMPERATURE_KEY              = floatPreferencesKey("ai_temperature")
    private val AI_MAX_TOKENS_KEY               = intPreferencesKey("ai_max_tokens")
    private val AI_SYSTEM_PROMPT_KEY            = stringPreferencesKey("ai_system_prompt")
    private val AI_BASE_SYSTEM_PROMPT_KEY       = stringPreferencesKey("ai_base_system_prompt")
    private val AI_MEMORY_NOTES_KEY             = stringPreferencesKey("ai_memory_notes")
    private val AI_DECISION_PROMPT_TEMPLATE_KEY = stringPreferencesKey("ai_decision_prompt_template")
    private val AI_REPAIR_PROMPT_TEMPLATE_KEY   = stringPreferencesKey("ai_repair_prompt_template")
    private val AI_FOLLOWUP_DEFAULT_COUNT_KEY   = intPreferencesKey("ai_followup_default_count")
    private val AI_FEATURES_DISABLED_KEY        = booleanPreferencesKey("ai_features_disabled")
    private val AI_REASONING_EFFORT_KEY         = stringPreferencesKey("ai_reasoning_effort")
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
    private val WEIGHT_TRACKING_ENABLED_KEY         = booleanPreferencesKey("weight_tracking_enabled")
    private val WEIGHT_UNIT_KEY                     = stringPreferencesKey("weight_unit")
    private val EXERCISE_TRACKING_ENABLED_KEY       = booleanPreferencesKey("exercise_tracking_enabled")
    private val EXERCISE_AGE_KEY                    = intPreferencesKey("exercise_age")
    private val EXERCISE_SEX_KEY                    = stringPreferencesKey("exercise_sex")
    private val EXERCISE_DEFAULT_WEIGHT_KG_KEY      = floatPreferencesKey("exercise_default_weight_kg")
    private val EXERCISE_ACSM_CORRECTION_KEY        = floatPreferencesKey("exercise_acsm_correction")
    private val EXERCISE_EPOC_MULTIPLIER_KEY        = floatPreferencesKey("exercise_epoc_multiplier")

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

    val globalNetworkEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[GLOBAL_NETWORK_ENABLED_KEY] ?: true }

    val globalAiEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[GLOBAL_AI_ENABLED_KEY] ?: true }

    val effectiveGlobalAiEnabled: Flow<Boolean> = combine(
        globalNetworkEnabled,
        globalAiEnabled
    ) { networkEnabled, aiEnabled -> networkEnabled && aiEnabled }

    val aiProvider: Flow<AiProvider> = context.dataStore.data
        .map { prefs -> AiProvider.fromStored(prefs[AI_PROVIDER_KEY]) }

    val aiModel: Flow<String> = combine(
        context.dataStore.data.map { prefs -> prefs[AI_MODEL_KEY] },
        aiProvider
    ) { storedModel, provider ->
        storedModel?.takeIf { it.isNotBlank() } ?: provider.defaultModel
    }

    val aiApiKey: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[AI_API_KEY_KEY] ?: "" }

    val aiBaseUrl: Flow<String> = combine(
        context.dataStore.data.map { prefs -> prefs[AI_BASE_URL_KEY] },
        aiProvider
    ) { storedUrl, provider ->
        storedUrl?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl.orEmpty()
    }

    val aiTemperature: Flow<Float> = context.dataStore.data
        .map { prefs -> prefs[AI_TEMPERATURE_KEY] ?: 0.2f }

    val aiMaxTokens: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[AI_MAX_TOKENS_KEY] ?: 1024 }

    val aiSystemPrompt: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[AI_SYSTEM_PROMPT_KEY] ?: "" }

    val aiBaseSystemPrompt: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[AI_BASE_SYSTEM_PROMPT_KEY] ?: DEFAULT_AI_BASE_SYSTEM_PROMPT }

    val aiMemoryNotes: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[AI_MEMORY_NOTES_KEY] ?: "" }

    val aiDecisionPromptTemplate: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[AI_DECISION_PROMPT_TEMPLATE_KEY] ?: DEFAULT_AI_DECISION_PROMPT_TEMPLATE }

    val aiRepairPromptTemplate: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[AI_REPAIR_PROMPT_TEMPLATE_KEY] ?: DEFAULT_AI_REPAIR_PROMPT_TEMPLATE }

    val aiFollowupDefaultCount: Flow<Int> = context.dataStore.data
        .map { prefs -> (prefs[AI_FOLLOWUP_DEFAULT_COUNT_KEY] ?: 1).coerceIn(0, 5) }

    val aiFeaturesDisabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[AI_FEATURES_DISABLED_KEY] ?: false }

    /** Reasoning effort level sent to the model. One of: "none", "low", "medium", "high". */
    val aiReasoningEffort: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[AI_REASONING_EFFORT_KEY] ?: "none" }

    // ── Tracking type flows ───────────────────────────────────────────────

    val sleepEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[SLEEP_TRACKING_ENABLED_KEY] ?: true }

    val nutritionEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[NUTRITION_TRACKING_ENABLED_KEY] ?: true }

    val weightEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[WEIGHT_TRACKING_ENABLED_KEY] ?: false }

    val exerciseEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[EXERCISE_TRACKING_ENABLED_KEY] ?: false }

    // ── Nutrition flows ───────────────────────────────────────────────────

    // Consolidated: nutrition and sleep history use the same days setting
    val nutritionPastDateRangeDays: Flow<Int> get() = historyDays

    val nutritionMealDurationMinutes: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[NUTRITION_MEAL_DURATION_MINUTES_KEY] ?: 30 }

    val nutritionSnackDurationMinutes: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[NUTRITION_SNACK_DURATION_MINUTES_KEY] ?: 10 }

    val nutritionAskEatenTime: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[NUTRITION_ASK_EATEN_TIME_KEY] ?: true }

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

    // ── Weight flows ──────────────────────────────────────────────────────────

    val weightUnit: Flow<codegito.xyz.healthconnector.weight.domain.WeightUnit> = context.dataStore.data
        .map { prefs ->
            try { codegito.xyz.healthconnector.weight.domain.WeightUnit.valueOf(
                prefs[WEIGHT_UNIT_KEY] ?: codegito.xyz.healthconnector.weight.domain.WeightUnit.LBS.name
            ) } catch (_: Exception) { codegito.xyz.healthconnector.weight.domain.WeightUnit.LBS }
        }

    // ── Exercise flows ────────────────────────────────────────────────────────

    val exerciseAge: Flow<Int?> = context.dataStore.data
        .map { prefs -> prefs[EXERCISE_AGE_KEY] }

    val exerciseSex: Flow<codegito.xyz.healthconnector.exercise.domain.Sex?> = context.dataStore.data
        .map { prefs ->
            prefs[EXERCISE_SEX_KEY]?.let {
                runCatching { codegito.xyz.healthconnector.exercise.domain.Sex.valueOf(it) }.getOrNull()
            }
        }

    val exerciseDefaultWeightKg: Flow<Double> = context.dataStore.data
        .map { prefs -> (prefs[EXERCISE_DEFAULT_WEIGHT_KG_KEY] ?: 70.0f).toDouble() }

    val exerciseAcsmRunningCorrection: Flow<Double> = context.dataStore.data
        .map { prefs -> (prefs[EXERCISE_ACSM_CORRECTION_KEY] ?: 0.90f).toDouble() }

    val exerciseEpocMultiplier: Flow<Double> = context.dataStore.data
        .map { prefs -> (prefs[EXERCISE_EPOC_MULTIPLIER_KEY] ?: 1.07f).toDouble() }

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

    suspend fun setGlobalNetworkEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[GLOBAL_NETWORK_ENABLED_KEY] = enabled }
    }

    suspend fun setGlobalAiEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[GLOBAL_AI_ENABLED_KEY] = enabled }
    }

    suspend fun setAiProvider(provider: AiProvider) {
        context.dataStore.edit { prefs -> prefs[AI_PROVIDER_KEY] = provider.name }
    }

    suspend fun setAiModel(model: String) {
        context.dataStore.edit { prefs -> prefs[AI_MODEL_KEY] = model }
    }

    suspend fun setAiApiKey(apiKey: String) {
        context.dataStore.edit { prefs -> prefs[AI_API_KEY_KEY] = apiKey }
    }

    suspend fun setAiBaseUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[AI_BASE_URL_KEY] = url }
    }

    suspend fun setAiTemperature(temperature: Float) {
        context.dataStore.edit { prefs -> prefs[AI_TEMPERATURE_KEY] = temperature.coerceIn(0f, 2f) }
    }

    suspend fun setAiMaxTokens(maxTokens: Int) {
        context.dataStore.edit { prefs -> prefs[AI_MAX_TOKENS_KEY] = maxTokens.coerceIn(1, 32768) }
    }

    suspend fun setAiSystemPrompt(prompt: String) {
        context.dataStore.edit { prefs -> prefs[AI_SYSTEM_PROMPT_KEY] = prompt }
    }

    suspend fun setAiBaseSystemPrompt(prompt: String) {
        context.dataStore.edit { prefs -> prefs[AI_BASE_SYSTEM_PROMPT_KEY] = prompt }
    }

    suspend fun setAiMemoryNotes(notes: String) {
        context.dataStore.edit { prefs -> prefs[AI_MEMORY_NOTES_KEY] = notes }
    }

    suspend fun setAiDecisionPromptTemplate(template: String) {
        context.dataStore.edit { prefs -> prefs[AI_DECISION_PROMPT_TEMPLATE_KEY] = template }
    }

    suspend fun setAiRepairPromptTemplate(template: String) {
        context.dataStore.edit { prefs -> prefs[AI_REPAIR_PROMPT_TEMPLATE_KEY] = template }
    }

    suspend fun setAiFollowupDefaultCount(count: Int) {
        context.dataStore.edit { prefs -> prefs[AI_FOLLOWUP_DEFAULT_COUNT_KEY] = count.coerceIn(0, 5) }
    }

    suspend fun setAiFeaturesDisabled(disabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AI_FEATURES_DISABLED_KEY] = disabled }
    }

    suspend fun setAiReasoningEffort(effort: String) {
        context.dataStore.edit { prefs -> prefs[AI_REASONING_EFFORT_KEY] = effort }
    }

    suspend fun resetAiPromptsToDefault() {
        context.dataStore.edit { prefs ->
            prefs[AI_BASE_SYSTEM_PROMPT_KEY] = DEFAULT_AI_BASE_SYSTEM_PROMPT
            prefs[AI_SYSTEM_PROMPT_KEY] = DEFAULT_AI_SYSTEM_PROMPT
            prefs[AI_DECISION_PROMPT_TEMPLATE_KEY] = DEFAULT_AI_DECISION_PROMPT_TEMPLATE
            prefs[AI_REPAIR_PROMPT_TEMPLATE_KEY] = DEFAULT_AI_REPAIR_PROMPT_TEMPLATE
        }
    }

    suspend fun setSleepEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SLEEP_TRACKING_ENABLED_KEY] = enabled }
    }

    suspend fun setNutritionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[NUTRITION_TRACKING_ENABLED_KEY] = enabled }
    }

    suspend fun setWeightEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[WEIGHT_TRACKING_ENABLED_KEY] = enabled }
    }

    suspend fun setExerciseEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[EXERCISE_TRACKING_ENABLED_KEY] = enabled }
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

    // ── Weight setters ────────────────────────────────────────────────────────

    suspend fun setWeightUnit(unit: codegito.xyz.healthconnector.weight.domain.WeightUnit) {
        context.dataStore.edit { prefs -> prefs[WEIGHT_UNIT_KEY] = unit.name }
    }

    // ── Exercise setters ──────────────────────────────────────────────────────

    suspend fun setExerciseAge(age: Int?) {
        context.dataStore.edit { prefs ->
            if (age != null) prefs[EXERCISE_AGE_KEY] = age else prefs.remove(EXERCISE_AGE_KEY)
        }
    }

    suspend fun setExerciseSex(sex: codegito.xyz.healthconnector.exercise.domain.Sex?) {
        context.dataStore.edit { prefs ->
            if (sex != null) prefs[EXERCISE_SEX_KEY] = sex.name else prefs.remove(EXERCISE_SEX_KEY)
        }
    }

    suspend fun setExerciseDefaultWeightKg(kg: Double) {
        context.dataStore.edit { prefs -> prefs[EXERCISE_DEFAULT_WEIGHT_KG_KEY] = kg.toFloat() }
    }

    suspend fun setExerciseAcsmRunningCorrection(factor: Double) {
        context.dataStore.edit { prefs -> prefs[EXERCISE_ACSM_CORRECTION_KEY] = factor.toFloat() }
    }

    suspend fun setExerciseEpocMultiplier(multiplier: Double) {
        context.dataStore.edit { prefs -> prefs[EXERCISE_EPOC_MULTIPLIER_KEY] = multiplier.toFloat() }
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
