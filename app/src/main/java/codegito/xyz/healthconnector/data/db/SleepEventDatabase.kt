package codegito.xyz.healthconnector.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "screen_events")
data class ScreenEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val type: String // "LOCK", "UNLOCK", "PRESENT"
)

@Dao
interface ScreenEventDao {
    @Insert
    suspend fun insert(event: ScreenEvent)

    @Query("SELECT * FROM screen_events WHERE timestampMillis BETWEEN :startTime AND :endTime ORDER BY timestampMillis ASC")
    fun getEventsInRange(startTime: Long, endTime: Long): Flow<List<ScreenEvent>>

    @Query("DELETE FROM screen_events WHERE timestampMillis < :threshold")
    suspend fun deleteOldEvents(threshold: Long)
}

@Database(entities = [ScreenEvent::class], version = 1, exportSchema = false)
abstract class SleepEventDatabase : RoomDatabase() {
    abstract fun screenEventDao(): ScreenEventDao

    companion object {
        @Volatile
        private var INSTANCE: SleepEventDatabase? = null

        fun getDatabase(context: android.content.Context): SleepEventDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SleepEventDatabase::class.java,
                    "sleep_event_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
