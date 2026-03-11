package codegito.xyz.healthconnector.nutrition.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

        val inserter = nutritionDb.beginBulkInsert(mergeMode)
        var totalWritten = 0
        var bestSource = source

        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && isActive) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".tsv")) {
                        val reader = BufferedReader(InputStreamReader(NonClosingInputStream(zip)), 65536)
                        val written = writeFoodsFromTsv(reader, inserter, nutritionDb) { current ->
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
            nutritionDb.rebuildFtsFromFoods(inserter)
            nutritionDb.commitBulkInsert(inserter)
        } catch (e: Exception) {
            nutritionDb.rollbackBulkInsert(inserter)
            if (!mergeMode) {
                nutritionDb.deleteDatabase()
            }
            throw e
        } finally {
            inserter.close()
        }

        // Write metadata for Settings screen record count display
        writeMetadata(source = bestSource, recordCount = totalWritten)
        progressCallback(totalWritten, totalWritten) // final call: total = actual count

        BuildResult(recordCount = totalWritten, sourceLocation = bestSource)
    }

    private fun writeFoodsFromTsv(
        reader: BufferedReader,
        inserter: NutritionDatabase.BulkInserter,
        nutritionDb: NutritionDatabase,
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

        // Sorted list of all column indices we need, used for single-pass splitting
        val neededCols = intArrayOf(idIdx, nameIdx, altNamesIdx, typeIdx, servingIdx, nutritionIdx, labelsIdx)
        val maxNeededCol = neededCols.maxOrNull() ?: 0

        // Reusable array: colVals[i] holds the value for column i (up to maxNeededCol)
        val colVals = arrayOfNulls<String>(maxNeededCol + 1)

        val lineBuffer = StringBuilder(512)

        // Split only up to the highest needed column index, store in colVals
        fun splitCols(line: String) {
            var col = 0
            var start = 0
            while (col <= maxNeededCol) {
                val tab = line.indexOf('\t', start)
                val end = if (tab < 0) line.length else tab
                colVals[col] = line.substring(start, end)
                if (tab < 0) {
                    col++
                    while (col <= maxNeededCol) { colVals[col++] = "" }
                    break
                }
                start = tab + 1
                col++
            }
        }

        fun col(idx: Int) = if (idx < 0 || idx > maxNeededCol) "" else colVals[idx] ?: ""

        // Parse a flat JSON object {"key":val,...} into a Double map without JSONObject
        fun parseNumericJson(json: String): HashMap<String, Double>? {
            if (json.length < 3) return null
            val map = HashMap<String, Double>(64)
            var i = json.indexOf('"')
            while (i in 0 until json.length) {
                val keyEnd = json.indexOf('"', i + 1)
                if (keyEnd < 0) break
                val key = json.substring(i + 1, keyEnd)
                val colon = json.indexOf(':', keyEnd + 1)
                if (colon < 0) break
                var vs = colon + 1
                while (vs < json.length && json[vs] == ' ') vs++
                if (vs >= json.length) break
                val c = json[vs]
                if (c == '-' || c in '0'..'9') {
                    var ve = vs + 1
                    while (ve < json.length && json[ve] != ',' && json[ve] != '}') ve++
                    json.substring(vs, ve).toDoubleOrNull()?.let { map[key] = it }
                    i = json.indexOf('"', ve)
                } else {
                    i = json.indexOf('"', vs)
                }
            }
            return map.ifEmpty { null }
        }

        // Inline nutrient lookup -- avoids vararg String[] allocation per call
        fun HashMap<String, Double>?.n(k1: String): Double {
            val v = this?.get(k1); return if (v != null && !v.isNaN() && v != 0.0) v else 0.0
        }
        fun HashMap<String, Double>?.n(k1: String, k2: String): Double {
            var v = this?.get(k1); if (v != null && !v.isNaN() && v != 0.0) return v
            v = this?.get(k2); return if (v != null && !v.isNaN() && v != 0.0) v else 0.0
        }
        fun HashMap<String, Double>?.n(k1: String, k2: String, k3: String): Double {
            var v = this?.get(k1); if (v != null && !v.isNaN() && v != 0.0) return v
            v = this?.get(k2); if (v != null && !v.isNaN() && v != 0.0) return v
            v = this?.get(k3); return if (v != null && !v.isNaN() && v != 0.0) v else 0.0
        }
        fun HashMap<String, Double>?.n(k1: String, k2: String, k3: String, k4: String): Double {
            var v = this?.get(k1); if (v != null && !v.isNaN() && v != 0.0) return v
            v = this?.get(k2); if (v != null && !v.isNaN() && v != 0.0) return v
            v = this?.get(k3); if (v != null && !v.isNaN() && v != 0.0) return v
            v = this?.get(k4); return if (v != null && !v.isNaN() && v != 0.0) v else 0.0
        }
        fun HashMap<String, Double>?.mg1(k1: String): Double { val r = n(k1); return if (r > 10.0) r / 1000.0 else r }
        fun HashMap<String, Double>?.mg3(k1: String, k2: String, k3: String): Double { val r = n(k1, k2, k3); return if (r > 10.0) r / 1000.0 else r }

        // Inline serving JSON parser -- avoids JSONObject for the serving field
        // Format: {"common":{"unit":"oz","quantity":3},"metric":{"unit":"g","quantity":85}}
        var servingCommonUnit: String? = null
        var servingCommonQty: Double? = null
        var servingMetricUnit: String = "g"
        var servingMetricQty: Double = 100.0

        fun parseServing(json: String) {
            servingCommonUnit = null; servingCommonQty = null
            servingMetricUnit = "g"; servingMetricQty = 100.0
            if (json.length < 3) return
            // Find "common" and "metric" sub-objects by scanning for their start braces
            fun extractSubObject(tag: String): String? {
                val tagIdx = json.indexOf("\"$tag\"")
                if (tagIdx < 0) return null
                val brace = json.indexOf('{', tagIdx + tag.length + 2)
                if (brace < 0) return null
                var depth = 1; var k = brace + 1
                while (k < json.length && depth > 0) {
                    when (json[k]) { '{' -> depth++; '}' -> depth-- }
                    k++
                }
                return json.substring(brace, k)
            }
            fun getString(obj: String, key: String): String? {
                val ki = obj.indexOf("\"$key\""); if (ki < 0) return null
                val q1 = obj.indexOf('"', ki + key.length + 3); if (q1 < 0) return null
                val q2 = obj.indexOf('"', q1 + 1); if (q2 < 0) return null
                return obj.substring(q1 + 1, q2).takeIf { it.isNotEmpty() }
            }
            fun getDouble(obj: String, key: String): Double? {
                val ki = obj.indexOf("\"$key\""); if (ki < 0) return null
                val colon = obj.indexOf(':', ki + key.length + 2); if (colon < 0) return null
                var vs = colon + 1
                while (vs < obj.length && obj[vs] == ' ') vs++
                var ve = vs; while (ve < obj.length && obj[ve] != ',' && obj[ve] != '}') ve++
                return obj.substring(vs, ve).trim().toDoubleOrNull()
            }
            extractSubObject("common")?.let { c ->
                servingCommonUnit = getString(c, "unit")
                servingCommonQty = getDouble(c, "quantity")
            }
            extractSubObject("metric")?.let { m ->
                servingMetricUnit = getString(m, "unit") ?: "g"
                servingMetricQty = getDouble(m, "quantity") ?: 100.0
            }
        }

        var line = reader.readLine()
        while (line != null) {
            if (written >= MAX_RECORDS) break

            splitCols(line)

            val name = col(nameIdx).trim()
            if (name.isEmpty()) { rowIndex++; line = reader.readLine(); continue }

            val id = col(idIdx).takeIf { it.isNotBlank() } ?: "tsv-$rowIndex"

            parseServing(col(servingIdx).trim())

            val labelsRaw = col(labelsIdx).trim()
            val labelsJson = if (labelsRaw.startsWith('[')) labelsRaw else "[]"

            // Build FTS search text: name + alternate names
            lineBuffer.setLength(0)
            lineBuffer.append(name.lowercase())
            val altNamesRaw = col(altNamesIdx).trim()
            if (altNamesRaw.startsWith('[') && altNamesRaw.length > 2) {
                var ai = altNamesRaw.indexOf('"')
                while (ai >= 0) {
                    val ae = altNamesRaw.indexOf('"', ai + 1)
                    if (ae < 0) break
                    val alt = altNamesRaw.substring(ai + 1, ae).trim()
                    if (alt.isNotEmpty()) { lineBuffer.append(' '); lineBuffer.append(alt.lowercase()) }
                    ai = altNamesRaw.indexOf('"', ae + 1)
                }
            }
            val searchText = lineBuffer.toString()

            val foodType = col(typeIdx).trim().takeIf { it.isNotEmpty() }

            val nutritionRaw = col(nutritionIdx).trim()
            val n = if (nutritionRaw.startsWith('{')) parseNumericJson(nutritionRaw) else null

            try {
                nutritionDb.insertFood(
                    inserter = inserter,
                    id = id,
                    name = name,
                    servingCommonUnit = servingCommonUnit,
                    servingCommonQuantity = servingCommonQty,
                    servingMetricUnit = servingMetricUnit,
                    servingMetricQuantity = servingMetricQty,
                    labels = labelsJson,
                    foodType = foodType,
                    searchText = searchText,
                    calories = n.n("energy_kcal", "calories", "energy"),
                    protein = n.n("protein"),
                    carbs = n.n("carbohydrates", "carbs", "carbohydrate"),
                    fat = n.n("fat", "total_fat"),
                    saturatedFat = n.n("saturated_fats", "saturated_fat", "saturated_fatty_acids", "saturates"),
                    polyunsaturatedFat = n.n("polyunsaturated_fats", "polyunsaturated_fat", "polyunsaturated_fatty_acids"),
                    monounsaturatedFat = n.n("monounsaturated_fats", "monounsaturated_fat", "monounsaturated_fatty_acids"),
                    transFat = n.n("trans_fats", "trans_fat", "trans_fatty_acids"),
                    fiber = n.n("dietary_fiber", "fiber", "fibre", "dietary_fibre"),
                    sugar = n.n("total_sugars", "sugars", "sugar"),
                    sodium = n.mg1("sodium"),
                    cholesterol = n.mg1("cholesterol"),
                    potassium = n.mg1("potassium"),
                    calcium = n.mg1("calcium"),
                    iron = n.mg1("iron"),
                    magnesium = n.mg1("magnesium"),
                    phosphorus = n.mg1("phosphorus"),
                    zinc = n.mg1("zinc"),
                    vitaminA = n.mg3("vitamin_a", "vitamina", "retinol"),
                    vitaminC = n.mg3("vitamin_c", "vitaminc", "ascorbic_acid"),
                    vitaminD = n.n("vitamin_d", "vitamind").let { if (it > 10.0) it / 1000.0 else it },
                    vitaminE = n.n("vitamin_e", "vitamine").let { if (it > 10.0) it / 1000.0 else it },
                    vitaminK = n.n("vitamin_k", "vitamink").let { if (it > 10.0) it / 1000.0 else it },
                    vitaminB6 = n.mg3("vitamin_b6", "vitaminb6", "pyridoxine"),
                    vitaminB12 = n.mg3("vitamin_b12", "vitaminb12", "cobalamin"),
                    thiamin = n.mg3("thiamin", "thiamine", "vitamin_b1"),
                    riboflavin = n.n("riboflavin", "vitamin_b2").let { if (it > 10.0) it / 1000.0 else it },
                    niacin = n.n("niacin", "vitamin_b3").let { if (it > 10.0) it / 1000.0 else it },
                    folate = n.n("folate_dfe", "folate", "folic_acid", "vitamin_b9").let { if (it > 10.0) it / 1000.0 else it },
                    caffeine = n.mg1("caffeine"),
                )
                written++
                if (written % BATCH_SIZE == 0) {
                    nutritionDb.rotateBatch(inserter)
                    onProgress(written)
                }
            } catch (_: Exception) { }

            rowIndex++
            line = reader.readLine()
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
        private const val MAX_RECORDS = 500_000
        private const val BATCH_SIZE = 10_000
    }
}
