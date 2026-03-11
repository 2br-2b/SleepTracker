package codegito.xyz.healthconnector.nutrition.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * Extracts and indexes the OpenNutrition food dataset from a zip file into
 * [NutritionDatabase] (SQLite + FTS5), for fast full-text food search.
 *
 * The bundled zip lives in `assets/nutrition/opennutrition-dataset-2025.1.zip`.
 * The generated database is stored in `filesDir/nutrition/foods.db` at runtime.
 *
 * Nutrition data from OpenNutrition (https://www.opennutrition.app).
 * Licensed under ODbL v1.0. Data from Open Food Facts contributors.
 */
class NutritionIndexBuildManager(
    private val context: Context
) {

    data class BuildResult(
        val recordCount: Int,
        val sourceLocation: String
    )

    suspend fun hasBundledZip(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(BUNDLED_ZIP_ASSET_PATH).close()
            true
        }.getOrDefault(false)
    }

    /**
     * Build the food database from the bundled zip asset.
     * @param progressCallback Called on the IO thread every 1000 records with
     *   (current, total) where total = -1 (unknown).
     * @param mergeMode If true, new records are inserted with INSERT OR IGNORE
     *   (existing records kept). If false, DB is wiped before insert (default).
     */
    suspend fun buildFromBundledZip(
        progressCallback: (current: Int, total: Int) -> Unit = { _, _ -> },
        mergeMode: Boolean = false
    ): Result<BuildResult> = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(BUNDLED_ZIP_ASSET_PATH).use { input ->
                buildFromZipStream(input, "asset:$BUNDLED_ZIP_ASSET_PATH", progressCallback, mergeMode)
            }
        }
    }

    /**
     * Build the food database from a user-provided zip file.
     * @param mergeMode If true, appends to existing data (INSERT OR IGNORE).
     *   If false (default), replaces the entire database.
     */
    suspend fun buildFromUri(
        uri: Uri,
        progressCallback: (current: Int, total: Int) -> Unit = { _, _ -> },
        mergeMode: Boolean = false
    ): Result<BuildResult> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            resolver.openInputStream(uri)?.use { input ->
                buildFromZipStream(input, "uri:$uri", progressCallback, mergeMode)
            } ?: error("Unable to open selected ZIP")
        }
    }

    private suspend fun buildFromZipStream(
        input: InputStream,
        source: String,
        progressCallback: (Int, Int) -> Unit,
        mergeMode: Boolean
    ): BuildResult = withContext(Dispatchers.IO) {
        val nutritionDb = NutritionDatabase.getInstance(context)

        if (!mergeMode) {
            nutritionDb.deleteDatabase()
        }

        val sqliteDb = nutritionDb.beginBulkInsert()
        var totalWritten = 0
        var bestSource = source

        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && isActive) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".tsv")) {
                        val reader = BufferedReader(InputStreamReader(NonClosingInputStream(zip)))
                        val written = writeFoodsFromTsv(reader, sqliteDb, nutritionDb, mergeMode) { current ->
                            progressCallback(totalWritten + current, 326760)
                        }
                        if (written > 0) {
                            totalWritten += written
                            bestSource = "$source::${entry.name}"
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            nutritionDb.commitBulkInsert(sqliteDb)
        } catch (e: Exception) {
            nutritionDb.rollbackBulkInsert(sqliteDb)
            if (!mergeMode) {
                nutritionDb.deleteDatabase()
            }
            throw e
        }

        // Write metadata for Settings screen record count display
        writeMetadata(source = bestSource, recordCount = totalWritten)
        progressCallback(totalWritten, totalWritten) // final call: total = actual count

        BuildResult(recordCount = totalWritten, sourceLocation = bestSource)
    }

    private fun writeFoodsFromTsv(
        reader: BufferedReader,
        sqliteDb: android.database.sqlite.SQLiteDatabase,
        nutritionDb: NutritionDatabase,
        mergeMode: Boolean,
        onProgress: (Int) -> Unit
    ): Int {
        val header = reader.readLine() ?: return 0
        val cols = header.split("\t").map { it.trim().lowercase() }

        fun col(vararg names: String): Int {
            names.forEach { name ->
                val i = cols.indexOf(name)
                if (i >= 0) return i
            }
            return -1
        }

        val idIdx = col("id")
        val nameIdx = col("name", "food_name", "description")
        val altNamesIdx = col("alternate_names")
        val typeIdx = col("type")
        val servingIdx = col("serving")
        val nutritionIdx = col("nutrition_100g")
        val labelsIdx = col("labels")

        if (nameIdx < 0) return 0

        var rowIndex = 0
        var written = 0

        reader.forEachLine { rawLine ->
            if (written >= MAX_RECORDS) return@forEachLine
            val row = rawLine.split("\t")
            val name = row.getOrNull(nameIdx)?.trim().orEmpty()
            if (name.isEmpty()) { rowIndex++; return@forEachLine }

            val id = row.getOrNull(idIdx)?.takeIf { it.isNotBlank() } ?: "tsv-$rowIndex"

            // Parse serving JSON: {"common":{"unit":"oz","quantity":3},"metric":{"unit":"g","quantity":85}}
            val servingRaw = if (servingIdx >= 0) row.getOrNull(servingIdx)?.trim().orEmpty() else ""
            val serving = runCatching { JSONObject(servingRaw) }.getOrNull()
            val commonServing = serving?.optJSONObject("common")
            val metricServing = serving?.optJSONObject("metric")
            val servingCommonUnit = commonServing?.optString("unit")?.takeIf { it.isNotEmpty() }
            val servingCommonQty = commonServing?.let { if (it.has("quantity")) it.optDouble("quantity") else null }
            val servingMetricUnit = metricServing?.optString("unit")?.takeIf { it.isNotEmpty() } ?: "g"
            val servingMetricQty = metricServing?.optDouble("quantity", 100.0) ?: 100.0

            // Parse labels: ["cooked"] → stored as JSON array string
            val labelsRaw = if (labelsIdx >= 0) row.getOrNull(labelsIdx)?.trim().orEmpty() else ""
            val labelsJson = runCatching { JSONArray(labelsRaw).toString() }.getOrDefault("[]")

            // Parse alternate_names for FTS search_text
            val altNamesRaw = if (altNamesIdx >= 0) row.getOrNull(altNamesIdx)?.trim().orEmpty() else ""
            val altNamesArray = runCatching { JSONArray(altNamesRaw) }.getOrNull()
            val altNames = (0 until (altNamesArray?.length() ?: 0))
                .mapNotNull { altNamesArray?.optString(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            val searchText = (listOf(name) + altNames).joinToString(" ").lowercase()

            val foodType = if (typeIdx >= 0) row.getOrNull(typeIdx)?.trim()?.takeIf { it.isNotEmpty() } else null

            // Parse nutrition_100g JSON
            val nutritionRaw = if (nutritionIdx >= 0) row.getOrNull(nutritionIdx)?.trim().orEmpty() else ""
            val n = if (nutritionRaw.isNotEmpty()) runCatching { JSONObject(nutritionRaw) }.getOrNull() else null

            fun d(vararg keys: String): Double = n?.let { obj ->
                keys.firstNotNullOfOrNull { k -> obj.optDouble(k).takeIf { !it.isNaN() && it != 0.0 } }
            } ?: 0.0

            // Heuristic: minerals/vitamins > 10 in a per-100g context are likely in mg → convert to g
            fun mg(vararg keys: String): Double {
                val raw = d(*keys)
                return if (raw > 10.0) raw / 1000.0 else raw
            }

            runCatching {
                nutritionDb.insertFood(
                    database = sqliteDb,
                    id = id,
                    name = name,
                    servingCommonUnit = servingCommonUnit,
                    servingCommonQuantity = servingCommonQty,
                    servingMetricUnit = servingMetricUnit,
                    servingMetricQuantity = servingMetricQty,
                    labels = labelsJson,
                    foodType = foodType,
                    calories = d("energy_kcal", "calories", "energy"),
                    protein = d("protein"),
                    carbs = d("carbohydrates", "carbs", "carbohydrate"),
                    fat = d("fat", "total_fat"),
                    saturatedFat = d("saturated_fats", "saturated_fat", "saturated_fatty_acids", "saturates"),
                    polyunsaturatedFat = d("polyunsaturated_fats", "polyunsaturated_fat", "polyunsaturated_fatty_acids"),
                    monounsaturatedFat = d("monounsaturated_fats", "monounsaturated_fat", "monounsaturated_fatty_acids"),
                    transFat = d("trans_fats", "trans_fat", "trans_fatty_acids"),
                    fiber = d("dietary_fiber", "fiber", "fibre", "dietary_fibre"),
                    sugar = d("total_sugars", "sugars", "sugar"),
                    sodium = mg("sodium"),
                    cholesterol = mg("cholesterol"),
                    potassium = mg("potassium"),
                    calcium = mg("calcium"),
                    iron = mg("iron"),
                    magnesium = mg("magnesium"),
                    phosphorus = mg("phosphorus"),
                    zinc = mg("zinc"),
                    vitaminA = mg("vitamin_a", "vitamina", "retinol"),
                    vitaminC = mg("vitamin_c", "vitaminc", "ascorbic_acid"),
                    vitaminD = mg("vitamin_d", "vitamind"),
                    vitaminE = mg("vitamin_e", "vitamine"),
                    vitaminK = mg("vitamin_k", "vitamink"),
                    vitaminB6 = mg("vitamin_b6", "vitaminb6", "pyridoxine"),
                    vitaminB12 = mg("vitamin_b12", "vitaminb12", "cobalamin"),
                    thiamin = mg("thiamin", "thiamine", "vitamin_b1"),
                    riboflavin = mg("riboflavin", "vitamin_b2"),
                    niacin = mg("niacin", "vitamin_b3"),
                    folate = mg("folate_dfe", "folate", "folic_acid", "vitamin_b9"),
                    caffeine = mg("caffeine"),
                    mergeMode = mergeMode
                )
                nutritionDb.insertFts(sqliteDb, id, searchText, mergeMode)
                written++
            }

            rowIndex++
            if (written % 1000 == 0) onProgress(written)
        }

        onProgress(written)
        return written
    }

    private fun writeMetadata(source: String, recordCount: Int) {
        runCatching {
            val outputDir = context.filesDir.resolve("nutrition")
            outputDir.mkdirs()
            val metadata = JSONObject().apply {
                put("source", "Open Nutrition Dataset")
                put("attribution", "https://www.opennutrition.app")
                put("sourceLocation", source)
                put("recordCount", recordCount)
                put("buildTimestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            }
            outputDir.resolve("metadata.json").writeText(metadata.toString(2))
        }
    }

    fun indexRecordCount(): Int =
        NutritionDatabase.getInstance(context).getRecordCount()

    fun clearIndex() {
        NutritionDatabase.getInstance(context).deleteDatabase()
        // Clean up legacy JSONL files if present
        val dir = context.filesDir.resolve("nutrition")
        dir.resolve("index.jsonl").delete()
        dir.resolve("metadata.json").delete()
        dir.resolve("build-log.txt").delete()
    }

    private class NonClosingInputStream(
        private val delegate: InputStream
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() = Unit
    }

    companion object {
        private const val BUNDLED_ZIP_ASSET_PATH = "nutrition/opennutrition-dataset-2025.1.zip"
        private const val MAX_RECORDS = 400_000
    }
}
