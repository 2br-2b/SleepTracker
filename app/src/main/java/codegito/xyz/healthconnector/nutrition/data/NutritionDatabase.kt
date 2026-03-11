package codegito.xyz.healthconnector.nutrition.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import codegito.xyz.healthconnector.nutrition.domain.FoodCandidate
import codegito.xyz.healthconnector.nutrition.domain.NutritionAmount
import codegito.xyz.healthconnector.nutrition.domain.NutrientVector
import codegito.xyz.healthconnector.nutrition.domain.QuantityUnit
import codegito.xyz.healthconnector.nutrition.domain.ServingInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Raw SQLite database for the nutrition food index.
 *
 * Generated at runtime from the bundled zip on first use. Located in
 * [Context.getFilesDir]/nutrition/foods.db -- NOT in app assets.
 *
 * Uses FTS4 for fast full-text search across food names and alternate names.
 * Because this DB is always regenerated from scratch (never migrated), we use
 * raw SQLiteDatabase directly rather than Room.
 */
class NutritionDatabase private constructor(private val context: Context) {

    @Volatile
    private var db: SQLiteDatabase? = null
    private val dbLock = Any()

    private fun dbFile(): File = context.filesDir.resolve("nutrition/$DB_FILE_NAME")

    private fun openDb(): SQLiteDatabase {
        db?.takeIf { it.isOpen }?.let { return it }
        return synchronized(dbLock) {
            db?.takeIf { it.isOpen } ?: run {
                val file = dbFile()
                file.parentFile?.mkdirs()
                val opened = SQLiteDatabase.openOrCreateDatabase(file, null)
                createTablesIfNeeded(opened)
                db = opened
                opened
            }
        }
    }

    private fun createTablesIfNeeded(database: SQLiteDatabase) {
        // WAL mode allows concurrent reads while the build transaction writes
        database.rawQuery("PRAGMA journal_mode=WAL", null).close()
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS foods (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                serving_common_unit TEXT,
                serving_common_quantity REAL,
                serving_metric_unit TEXT DEFAULT 'g',
                serving_metric_quantity REAL DEFAULT 100.0,
                labels TEXT DEFAULT '[]',
                food_type TEXT,
                search_text TEXT,
                calories REAL DEFAULT 0,
                protein REAL DEFAULT 0,
                carbs REAL DEFAULT 0,
                fat REAL DEFAULT 0,
                saturated_fat REAL DEFAULT 0,
                polyunsaturated_fat REAL DEFAULT 0,
                monounsaturated_fat REAL DEFAULT 0,
                trans_fat REAL DEFAULT 0,
                fiber REAL DEFAULT 0,
                sugar REAL DEFAULT 0,
                sodium REAL DEFAULT 0,
                cholesterol REAL DEFAULT 0,
                potassium REAL DEFAULT 0,
                calcium REAL DEFAULT 0,
                iron REAL DEFAULT 0,
                magnesium REAL DEFAULT 0,
                phosphorus REAL DEFAULT 0,
                zinc REAL DEFAULT 0,
                vitamin_a REAL DEFAULT 0,
                vitamin_c REAL DEFAULT 0,
                vitamin_d REAL DEFAULT 0,
                vitamin_e REAL DEFAULT 0,
                vitamin_k REAL DEFAULT 0,
                vitamin_b6 REAL DEFAULT 0,
                vitamin_b12 REAL DEFAULT 0,
                thiamin REAL DEFAULT 0,
                riboflavin REAL DEFAULT 0,
                niacin REAL DEFAULT 0,
                folate REAL DEFAULT 0,
                caffeine REAL DEFAULT 0
                -- Future fields (uncomment + re-index to enable):
                -- water REAL DEFAULT 0,
                -- alcohol REAL DEFAULT 0,
                -- tryptophan REAL DEFAULT 0,
                -- threonine REAL DEFAULT 0,
                -- isoleucine REAL DEFAULT 0,
                -- leucine REAL DEFAULT 0,
                -- lysine REAL DEFAULT 0,
                -- methionine REAL DEFAULT 0,
                -- phenylalanine REAL DEFAULT 0,
                -- valine REAL DEFAULT 0
            )
        """.trimIndent())

        ensureColumn(database, "foods", "search_text", "TEXT")
    }

    private fun ensureColumn(
        database: SQLiteDatabase,
        table: String,
        column: String,
        type: String
    ) {
        val cursor = database.rawQuery("PRAGMA table_info($table)", null)
        val hasColumn = cursor.use {
            var found = false
            while (it.moveToNext()) {
                if (it.getString(it.getColumnIndexOrThrow("name")) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!hasColumn) {
            database.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }

    fun isPopulated(): Boolean = runCatching {
        val cursor = openDb().rawQuery("SELECT COUNT(*) FROM foods LIMIT 1", null)
        cursor.use { it.moveToFirst() && it.getInt(0) > 0 }
    }.getOrDefault(false)

    fun getRecordCount(): Int = runCatching {
        val cursor = openDb().rawQuery("SELECT COUNT(*) FROM foods", null)
        cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }.getOrDefault(0)

    /**
     * Close the current DB connection and delete the database file.
     * Call before rebuilding. Returns true if successful.
     */
    fun deleteDatabase(): Boolean {
        synchronized(dbLock) {
            db?.close()
            db = null
            INSTANCE = null
        }
        return dbFile().delete()
    }

    // -------------------------------------------------------------------------
    // Bulk insert API (used by NutritionIndexBuildManager)
    // -------------------------------------------------------------------------

    /**
     * Holds pre-compiled insert statements for the duration of a bulk insert.
     * Avoids recompiling SQL on every row (326K+ rows -> significant overhead).
     * Must be closed after use.
     */
    inner class BulkInserter(
        val database: SQLiteDatabase,
        mergeMode: Boolean,
        private val prevSynchronous: Int,
        private val prevTempStore: Int,
        private val prevCacheSize: Int
    ) : AutoCloseable {

        private val insertFoodSql = """
            INSERT OR REPLACE INTO foods (
                id, name,
                serving_common_unit, serving_common_quantity,
                serving_metric_unit, serving_metric_quantity,
                labels, food_type, search_text,
                calories, protein, carbs, fat,
                saturated_fat, polyunsaturated_fat, monounsaturated_fat, trans_fat,
                fiber, sugar,
                sodium, cholesterol, potassium, calcium, iron, magnesium, phosphorus, zinc,
                vitamin_a, vitamin_c, vitamin_d, vitamin_e, vitamin_k,
                vitamin_b6, vitamin_b12, thiamin, riboflavin, niacin, folate, caffeine
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent()

        private val insertFoodOrIgnoreSql = insertFoodSql.replace(
            "INSERT OR REPLACE INTO foods",
            "INSERT OR IGNORE INTO foods"
        )

        private val insertFtsSql = "INSERT INTO foods_fts(food_id, search_text) VALUES (?, ?)"

        val foodStmt: SQLiteStatement = database.compileStatement(
            if (mergeMode) insertFoodOrIgnoreSql else insertFoodSql
        )
        val ftsStmt: SQLiteStatement = database.compileStatement(insertFtsSql)

        override fun close() {
            foodStmt.close()
            ftsStmt.close()
            database.execSQL("PRAGMA synchronous=$prevSynchronous")
            database.execSQL("PRAGMA temp_store=$prevTempStore")
            database.execSQL("PRAGMA cache_size=$prevCacheSize")
        }
    }

    private fun readPragmaInt(database: SQLiteDatabase, name: String): Int {
        val cursor = database.rawQuery("PRAGMA $name", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * Open the database, begin a transaction, and pre-compile insert statements.
     * Call [commitBulkInsert] or [rollbackBulkInsert] when done, then close the inserter.
     */
    fun beginBulkInsert(mergeMode: Boolean = false): BulkInserter {
        val database = openDb()
        val prevSynchronous = readPragmaInt(database, "synchronous")
        val prevTempStore = readPragmaInt(database, "temp_store")
        val prevCacheSize = readPragmaInt(database, "cache_size")
        // Optimize bulk load speed; restored in BulkInserter.close()
        database.execSQL("PRAGMA synchronous=OFF")
        database.execSQL("PRAGMA temp_store=MEMORY")
        database.execSQL("PRAGMA cache_size=-32768")
        database.beginTransactionNonExclusive()
        return BulkInserter(database, mergeMode, prevSynchronous, prevTempStore, prevCacheSize)
    }

    fun commitBulkInsert(inserter: BulkInserter) {
        inserter.database.setTransactionSuccessful()
        inserter.database.endTransaction()
    }

    fun rollbackBulkInsert(inserter: BulkInserter) {
        runCatching { inserter.database.endTransaction() }
    }

    /** Commit the current batch and immediately begin the next one. */
    fun rotateBatch(inserter: BulkInserter) {
        inserter.database.setTransactionSuccessful()
        inserter.database.endTransaction()
        inserter.database.beginTransactionNonExclusive()
    }

    fun insertFood(
        inserter: BulkInserter,
        id: String,
        name: String,
        servingCommonUnit: String?,
        servingCommonQuantity: Double?,
        servingMetricUnit: String,
        servingMetricQuantity: Double,
        labels: String,
        foodType: String?,
        searchText: String,
        calories: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        saturatedFat: Double,
        polyunsaturatedFat: Double,
        monounsaturatedFat: Double,
        transFat: Double,
        fiber: Double,
        sugar: Double,
        sodium: Double,
        cholesterol: Double,
        potassium: Double,
        calcium: Double,
        iron: Double,
        magnesium: Double,
        phosphorus: Double,
        zinc: Double,
        vitaminA: Double,
        vitaminC: Double,
        vitaminD: Double,
        vitaminE: Double,
        vitaminK: Double,
        vitaminB6: Double,
        vitaminB12: Double,
        thiamin: Double,
        riboflavin: Double,
        niacin: Double,
        folate: Double,
        caffeine: Double,
    ) {
        inserter.foodStmt.run {
            bindString(1, id)
            bindString(2, name)
            if (servingCommonUnit != null) bindString(3, servingCommonUnit) else bindNull(3)
            if (servingCommonQuantity != null) bindDouble(4, servingCommonQuantity) else bindNull(4)
            bindString(5, servingMetricUnit)
            bindDouble(6, servingMetricQuantity)
            bindString(7, labels)
            if (foodType != null) bindString(8, foodType) else bindNull(8)
            bindString(9, searchText)
            bindDouble(10, calories)
            bindDouble(11, protein)
            bindDouble(12, carbs)
            bindDouble(13, fat)
            bindDouble(14, saturatedFat)
            bindDouble(15, polyunsaturatedFat)
            bindDouble(16, monounsaturatedFat)
            bindDouble(17, transFat)
            bindDouble(18, fiber)
            bindDouble(19, sugar)
            bindDouble(20, sodium)
            bindDouble(21, cholesterol)
            bindDouble(22, potassium)
            bindDouble(23, calcium)
            bindDouble(24, iron)
            bindDouble(25, magnesium)
            bindDouble(26, phosphorus)
            bindDouble(27, zinc)
            bindDouble(28, vitaminA)
            bindDouble(29, vitaminC)
            bindDouble(30, vitaminD)
            bindDouble(31, vitaminE)
            bindDouble(32, vitaminK)
            bindDouble(33, vitaminB6)
            bindDouble(34, vitaminB12)
            bindDouble(35, thiamin)
            bindDouble(36, riboflavin)
            bindDouble(37, niacin)
            bindDouble(38, folate)
            bindDouble(39, caffeine)
            executeInsert()
        }
    }

    fun insertFts(inserter: BulkInserter, foodId: String, searchText: String) {
        inserter.ftsStmt.run {
            bindString(1, foodId)
            bindString(2, searchText)
            executeInsert()
        }
    }

    fun rebuildFtsFromFoods(inserter: BulkInserter) {
        createFtsTableIfNeeded(inserter.database)
        inserter.database.execSQL("DELETE FROM foods_fts")
        inserter.database.execSQL(
            "INSERT INTO foods_fts(food_id, search_text) SELECT id, search_text FROM foods"
        )
    }

    fun insertFoodsBatchRaw(inserter: BulkInserter, valuesSql: String) {
        if (valuesSql.isBlank()) return
        inserter.database.execSQL(
            "INSERT OR REPLACE INTO foods (" +
                "id, name, " +
                "serving_common_unit, serving_common_quantity, " +
                "serving_metric_unit, serving_metric_quantity, " +
                "labels, food_type, search_text, " +
                "calories, protein, carbs, fat, " +
                "saturated_fat, polyunsaturated_fat, monounsaturated_fat, trans_fat, " +
                "fiber, sugar, " +
                "sodium, cholesterol, potassium, calcium, iron, magnesium, phosphorus, zinc, " +
                "vitamin_a, vitamin_c, vitamin_d, vitamin_e, vitamin_k, " +
                "vitamin_b6, vitamin_b12, thiamin, riboflavin, niacin, folate, caffeine" +
            ") VALUES $valuesSql"
        )
    }

    // -------------------------------------------------------------------------
    // Search queries
    // -------------------------------------------------------------------------

    /**
     * Search foods using FTS4 for full-text matching across name and alternate names.
     * Falls back to LIKE if FTS returns no results (handles special-character edge cases).
     */
    suspend fun searchFoods(query: String, limit: Int): List<FoodCandidate> =
        withContext(Dispatchers.IO) {
            val normalized = query.trim()
            if (normalized.isEmpty()) return@withContext emptyList()

            // Try FTS4 first
            val ftsResults = runCatching {
                val matchExpr = buildFtsMatchExpr(normalized)
                val cursor = openDb().rawQuery(
                    """
                    SELECT f.* FROM foods f
                    JOIN foods_fts ON foods_fts.food_id = f.id
                    WHERE foods_fts MATCH ?
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(matchExpr, limit.toString())
                )
                cursor.use { parseFoodCandidates(it) }
            }.getOrElse { emptyList() }

            if (ftsResults.isNotEmpty()) return@withContext ftsResults

            // Fallback: LIKE on name column
            runCatching {
                val cursor = openDb().rawQuery(
                    "SELECT * FROM foods WHERE name LIKE ? LIMIT ?",
                    arrayOf("%$normalized%", limit.toString())
                )
                cursor.use { parseFoodCandidates(it) }
            }.getOrElse { emptyList() }
        }

    suspend fun getFoodByName(name: String): FoodCandidate? = withContext(Dispatchers.IO) {
        // Exact match first (case-insensitive via COLLATE NOCASE)
        runCatching {
            val cursor = openDb().rawQuery(
                "SELECT * FROM foods WHERE name = ? COLLATE NOCASE LIMIT 1",
                arrayOf(name)
            )
            cursor.use { if (it.moveToFirst()) cursorToFoodCandidate(it) else null }
        }.getOrNull()
            ?: runCatching {
                // Broad match fallback
                val cursor = openDb().rawQuery(
                    "SELECT * FROM foods WHERE name LIKE ? LIMIT 1",
                    arrayOf("%$name%")
                )
                cursor.use { if (it.moveToFirst()) cursorToFoodCandidate(it) else null }
            }.getOrNull()
    }

    suspend fun getFoodById(id: String): FoodCandidate? = withContext(Dispatchers.IO) {
        runCatching {
            val cursor = openDb().rawQuery(
                "SELECT * FROM foods WHERE id = ? LIMIT 1",
                arrayOf(id)
            )
            cursor.use { if (it.moveToFirst()) cursorToFoodCandidate(it) else null }
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Build an FTS5 MATCH expression from a plain text query.
     * Each word is quoted (exact token) and the last word gets a prefix wildcard.
     * Special FTS5 characters are escaped.
     */
    private fun buildFtsMatchExpr(query: String): String {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        return tokens.mapIndexed { index, token ->
            val escaped = token.replace("\"", "\"\"")
            if (index == tokens.lastIndex) "\"$escaped\"*" else "\"$escaped\""
        }.joinToString(" ")
    }

    private fun parseFoodCandidates(cursor: Cursor): List<FoodCandidate> {
        val results = mutableListOf<FoodCandidate>()
        while (cursor.moveToNext()) {
            runCatching { cursorToFoodCandidate(cursor) }.getOrNull()?.let { results.add(it) }
        }
        return results
    }

    private fun cursorToFoodCandidate(cursor: Cursor): FoodCandidate {
        fun col(name: String) = cursor.getColumnIndexOrThrow(name)
        fun double(name: String) = cursor.getDouble(col(name))
        fun stringOrNull(name: String): String? {
            val idx = col(name)
            return if (cursor.isNull(idx)) null else cursor.getString(idx)
        }

        val id = cursor.getString(col("id"))
        val name = cursor.getString(col("name"))

        val servingCommonUnit = stringOrNull("serving_common_unit")
        val servingCommonQty = if (cursor.isNull(col("serving_common_quantity"))) null
                               else cursor.getDouble(col("serving_common_quantity"))
        val servingMetricUnit = cursor.getString(col("serving_metric_unit")) ?: "g"
        val servingMetricQty = cursor.getDouble(col("serving_metric_quantity"))

        val servingInfo = ServingInfo(
            commonUnit = servingCommonUnit,
            commonQuantity = servingCommonQty,
            metricUnit = servingMetricUnit,
            metricQuantity = servingMetricQty
        )

        val labelsJson = cursor.getString(col("labels")) ?: "[]"
        val labelsArray = runCatching { JSONArray(labelsJson) }.getOrNull()
        val labels = (0 until (labelsArray?.length() ?: 0))
            .mapNotNull { labelsArray?.optString(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }

        val foodType = stringOrNull("food_type")

        val nutrients = NutrientVector(
            calories = double("calories"),
            proteinGrams = double("protein"),
            carbsGrams = double("carbs"),
            fatGrams = double("fat"),
            saturatedFatGrams = double("saturated_fat"),
            polyunsaturatedFatGrams = double("polyunsaturated_fat"),
            monounsaturatedFatGrams = double("monounsaturated_fat"),
            transFatGrams = double("trans_fat"),
            fiberGrams = double("fiber"),
            sugarGrams = double("sugar"),
            sodiumGrams = double("sodium"),
            cholesterolGrams = double("cholesterol"),
            potassiumGrams = double("potassium"),
            calciumGrams = double("calcium"),
            ironGrams = double("iron"),
            magnesiumGrams = double("magnesium"),
            phosphorusGrams = double("phosphorus"),
            zincGrams = double("zinc"),
            vitaminAGrams = double("vitamin_a"),
            vitaminCGrams = double("vitamin_c"),
            vitaminDGrams = double("vitamin_d"),
            vitaminEGrams = double("vitamin_e"),
            vitaminKGrams = double("vitamin_k"),
            vitaminB6Grams = double("vitamin_b6"),
            vitaminB12Grams = double("vitamin_b12"),
            thiaminGrams = double("thiamin"),
            riboflavinGrams = double("riboflavin"),
            niacinGrams = double("niacin"),
            folateGrams = double("folate"),
            caffeineGrams = double("caffeine")
        )

        return FoodCandidate(
            id = id,
            name = name,
            servingInfo = servingInfo,
            nutrientsPer100g = nutrients,
            labels = labels,
            foodType = foodType,
            baseAmount = NutritionAmount(100.0, QuantityUnit.GRAM)
        )
    }

    companion object {
        const val DB_FILE_NAME = "foods.db"

        @Volatile
        private var INSTANCE: NutritionDatabase? = null

        fun getInstance(context: Context): NutritionDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NutritionDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private fun createFtsTableIfNeeded(database: SQLiteDatabase) {
        database.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS foods_fts USING fts4(
                food_id,
                search_text
            )
        """.trimIndent())
    }
}
