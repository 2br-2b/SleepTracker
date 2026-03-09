package codegito.xyz.healthconnector.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "screen_events")
data class ScreenEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val type: String // "LOCK", "UNLOCK", "PRESENT"
)

@Entity(tableName = "recent_foods")
data class RecentFoodEntity(
    @PrimaryKey val foodKey: String,
    val displayName: String,
    val quantity: Double,
    val unit: String,
    val calories: Double,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val lastUsedAtMillis: Long,
    val sourceType: String,
    val nutrientsJson: String = "{}"
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

@Dao
interface RecentFoodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecentFoodEntity)

    @Query("SELECT * FROM recent_foods ORDER BY lastUsedAtMillis DESC LIMIT :limit")
    fun getRecents(limit: Int = 30): Flow<List<RecentFoodEntity>>

    @Query("DELETE FROM recent_foods")
    suspend fun clearAll()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE recent_foods ADD COLUMN nutrientsJson TEXT NOT NULL DEFAULT '{}'")
    }
}

@Database(entities = [ScreenEvent::class, RecentFoodEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun screenEventDao(): ScreenEventDao
    abstract fun recentFoodDao(): RecentFoodDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sleep_tracker_database"
                ).addMigrations(MIGRATION_1_2).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

@Deprecated("Use AppDatabase instead")
object SleepEventDatabase {
    fun getDatabase(context: Context): AppDatabase = AppDatabase.getDatabase(context)
}
