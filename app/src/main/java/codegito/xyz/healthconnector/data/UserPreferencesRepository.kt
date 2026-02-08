package codegito.xyz.healthconnector.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) {
    
    private val ROLLOVER_HOUR_KEY = intPreferencesKey("rollover_hour")
    private val SLEEP_STAGES_JSON_KEY = stringPreferencesKey("sleep_stages_json")

    // Default rollover hour: 2 AM - Sleep sessions starting after 2 AM belong to the current day,
    // while sessions before 2 AM (late night) belong to the previous day's "Night of".
    // This allows for a more natural representation of sleep sessions.
    // Let's stick to a simple integer hour (0-23).
    
    val rolloverHour: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[ROLLOVER_HOUR_KEY] ?: 2 // Default to 2 AM
        }

    val sleepStages: Flow<List<SleepStageConfig>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[SLEEP_STAGES_JSON_KEY]
            if (jsonString != null) {
                try {
                    Json.decodeFromString<List<SleepStageConfig>>(jsonString)
                } catch (e: Exception) {
                    getDefaultSleepStages()
                }
            } else {
                getDefaultSleepStages()
            }
        }

    suspend fun setRolloverHour(hour: Int) {
        context.dataStore.edit { preferences ->
            preferences[ROLLOVER_HOUR_KEY] = hour
        }
    }

    suspend fun saveSleepStages(stages: List<SleepStageConfig>) {
        context.dataStore.edit { preferences ->
            preferences[SLEEP_STAGES_JSON_KEY] = Json.encodeToString(stages)
        }
    }
}
