package codegito.xyz.healthconnector.data.db

import android.content.Context
import android.database.Cursor
import androidx.room.Database
import androidx.room.Entity
import androidx.room.InvalidationTracker
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

@Entity(tableName = "screen_events")
data class ScreenEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val type: String // "LOCK", "UNLOCK", "PRESENT"
)

@Entity(tableName = "recent_exercises")
data class RecentExerciseEntity(
    @PrimaryKey val exerciseName: String,
    val lastUsedAtMillis: Long
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

class ScreenEventDao(private val db: AppDatabase) {
    suspend fun insert(event: ScreenEvent) = withContext(Dispatchers.IO) {
        val stmt = db.openHelper.writableDatabase.compileStatement(
            "INSERT INTO screen_events (timestampMillis, type) VALUES (?, ?)"
        )
        stmt.use {
            it.bindLong(1, event.timestampMillis)
            it.bindString(2, event.type)
            it.executeInsert()
        }
    }

    fun getEventsInRange(startTime: Long, endTime: Long): Flow<List<ScreenEvent>> =
        callbackFlow<List<ScreenEvent>> {
            fun doQuery(): List<ScreenEvent> {
                val list = mutableListOf<ScreenEvent>()
                val cursor: Cursor = db.openHelper.readableDatabase.query(
                    "SELECT id, timestampMillis, type FROM screen_events WHERE timestampMillis BETWEEN ? AND ? ORDER BY timestampMillis ASC",
                    arrayOf(startTime.toString(), endTime.toString())
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        list.add(ScreenEvent(c.getLong(0), c.getLong(1), c.getString(2)))
                    }
                }
                return list
            }

            trySend(doQuery())

            val observer = object : InvalidationTracker.Observer("screen_events") {
                override fun onInvalidated(tables: Set<String>) {
                    trySend(doQuery())
                }
            }
            db.invalidationTracker.addObserver(observer)
            awaitClose { db.invalidationTracker.removeObserver(observer) }
        }.flowOn(Dispatchers.IO)

    suspend fun deleteOldEvents(threshold: Long) = withContext(Dispatchers.IO) {
        val stmt = db.openHelper.writableDatabase.compileStatement(
            "DELETE FROM screen_events WHERE timestampMillis < ?"
        )
        stmt.use {
            it.bindLong(1, threshold)
            it.executeUpdateDelete()
        }
    }
}

class RecentFoodDao(private val db: AppDatabase) {
    suspend fun upsert(item: RecentFoodEntity) = withContext(Dispatchers.IO) {
        val stmt = db.openHelper.writableDatabase.compileStatement(
            "INSERT OR REPLACE INTO recent_foods (foodKey, displayName, quantity, unit, calories, proteinGrams, carbsGrams, fatGrams, lastUsedAtMillis, sourceType, nutrientsJson) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        stmt.use {
            it.bindString(1, item.foodKey)
            it.bindString(2, item.displayName)
            it.bindDouble(3, item.quantity)
            it.bindString(4, item.unit)
            it.bindDouble(5, item.calories)
            it.bindDouble(6, item.proteinGrams)
            it.bindDouble(7, item.carbsGrams)
            it.bindDouble(8, item.fatGrams)
            it.bindLong(9, item.lastUsedAtMillis)
            it.bindString(10, item.sourceType)
            it.bindString(11, item.nutrientsJson)
            it.executeInsert()
        }
    }

    fun getRecents(limit: Int = 30): Flow<List<RecentFoodEntity>> =
        callbackFlow<List<RecentFoodEntity>> {
            fun doQuery(): List<RecentFoodEntity> {
                val list = mutableListOf<RecentFoodEntity>()
                val cursor: Cursor = db.openHelper.readableDatabase.query(
                    "SELECT foodKey, displayName, quantity, unit, calories, proteinGrams, carbsGrams, fatGrams, lastUsedAtMillis, sourceType, nutrientsJson FROM recent_foods ORDER BY lastUsedAtMillis DESC LIMIT ?",
                    arrayOf(limit.toString())
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        list.add(RecentFoodEntity(
                            foodKey = c.getString(0),
                            displayName = c.getString(1),
                            quantity = c.getDouble(2),
                            unit = c.getString(3),
                            calories = c.getDouble(4),
                            proteinGrams = c.getDouble(5),
                            carbsGrams = c.getDouble(6),
                            fatGrams = c.getDouble(7),
                            lastUsedAtMillis = c.getLong(8),
                            sourceType = c.getString(9),
                            nutrientsJson = c.getString(10)
                        ))
                    }
                }
                return list
            }

            trySend(doQuery())

            val observer = object : InvalidationTracker.Observer("recent_foods") {
                override fun onInvalidated(tables: Set<String>) {
                    trySend(doQuery())
                }
            }
            db.invalidationTracker.addObserver(observer)
            awaitClose { db.invalidationTracker.removeObserver(observer) }
        }.flowOn(Dispatchers.IO)

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.compileStatement("DELETE FROM recent_foods").use {
            it.executeUpdateDelete()
        }
    }
}

class RecentExerciseDao(private val db: AppDatabase) {
    suspend fun upsert(item: RecentExerciseEntity) = withContext(Dispatchers.IO) {
        val stmt = db.openHelper.writableDatabase.compileStatement(
            "INSERT OR REPLACE INTO recent_exercises (exerciseName, lastUsedAtMillis) VALUES (?, ?)"
        )
        stmt.use {
            it.bindString(1, item.exerciseName)
            it.bindLong(2, item.lastUsedAtMillis)
            it.executeInsert()
        }
    }

    fun getRecents(limit: Int = 30): Flow<List<RecentExerciseEntity>> =
        callbackFlow<List<RecentExerciseEntity>> {
            fun doQuery(): List<RecentExerciseEntity> {
                val list = mutableListOf<RecentExerciseEntity>()
                val cursor: Cursor = db.openHelper.readableDatabase.query(
                    "SELECT exerciseName, lastUsedAtMillis FROM recent_exercises ORDER BY lastUsedAtMillis DESC LIMIT ?",
                    arrayOf(limit.toString())
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        list.add(RecentExerciseEntity(c.getString(0), c.getLong(1)))
                    }
                }
                return list
            }

            trySend(doQuery())

            val observer = object : InvalidationTracker.Observer("recent_exercises") {
                override fun onInvalidated(tables: Set<String>) { trySend(doQuery()) }
            }
            db.invalidationTracker.addObserver(observer)
            awaitClose { db.invalidationTracker.removeObserver(observer) }
        }.flowOn(Dispatchers.IO)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE recent_foods ADD COLUMN nutrientsJson TEXT NOT NULL DEFAULT '{}'")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS recent_exercises (exerciseName TEXT NOT NULL PRIMARY KEY, lastUsedAtMillis INTEGER NOT NULL)")
    }
}

@Database(entities = [ScreenEvent::class, RecentFoodEntity::class, RecentExerciseEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    fun screenEventDao(): ScreenEventDao = ScreenEventDao(this)
    fun recentFoodDao(): RecentFoodDao = RecentFoodDao(this)
    fun recentExerciseDao(): RecentExerciseDao = RecentExerciseDao(this)

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sleep_tracker_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
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
