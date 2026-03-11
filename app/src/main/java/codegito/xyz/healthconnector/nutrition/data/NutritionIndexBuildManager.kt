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
        // NOTE: This build step is still slow on large datasets (~300k+ rows).
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

        // Parse a flat JSON object {"key":val,...} into a DoubleArray keyed by index.
        // Avoids HashMap allocations and repeated string hashing per lookup.
        val N = 0
        val PROTEIN = 1
        val CARBS = 2
        val FAT = 3
        val SAT_FAT = 4
        val POLY_FAT = 5
        val MONO_FAT = 6
        val TRANS_FAT = 7
        val FIBER = 8
        val SUGAR = 9
        val SODIUM = 10
        val CHOLESTEROL = 11
        val POTASSIUM = 12
        val CALCIUM = 13
        val IRON = 14
        val MAGNESIUM = 15
        val PHOSPHORUS = 16
        val ZINC = 17
        val VITA = 18
        val VITC = 19
        val VITD = 20
        val VITE = 21
        val VITK = 22
        val VITB6 = 23
        val VITB12 = 24
        val THIAMIN = 25
        val RIBOFLAVIN = 26
        val NIACIN = 27
        val FOLATE = 28
        val CAFFEINE = 29

        fun parseDoubleFast(s: String, start: Int, end: Int): Double? {
            if (start >= end) return null
            var i = start
            var neg = false
            if (s[i] == '-') { neg = true; i++ }
            var intPart = 0L
            var hasInt = false
            while (i < end) {
                val c = s[i]
                if (c in '0'..'9') {
                    intPart = intPart * 10 + (c - '0')
                    hasInt = true
                    i++
                } else {
                    break
                }
            }
            var frac = 0.0
            var scale = 1.0
            if (i < end && s[i] == '.') {
                i++
                while (i < end) {
                    val c = s[i]
                    if (c in '0'..'9') {
                        frac = frac * 10 + (c - '0')
                        scale *= 10
                        i++
                    } else {
                        break
                    }
                }
            }
            if (!hasInt && scale == 1.0) return null
            val v = intPart.toDouble() + (if (scale == 1.0) 0.0 else frac / scale)
            return if (neg) -v else v
        }

        fun keyIndex(key: String): Int = when (key) {
            "energy_kcal", "calories", "energy" -> N
            "protein" -> PROTEIN
            "carbohydrates", "carbs", "carbohydrate" -> CARBS
            "fat", "total_fat" -> FAT
            "saturated_fats", "saturated_fat", "saturated_fatty_acids", "saturates" -> SAT_FAT
            "polyunsaturated_fats", "polyunsaturated_fat", "polyunsaturated_fatty_acids" -> POLY_FAT
            "monounsaturated_fats", "monounsaturated_fat", "monounsaturated_fatty_acids" -> MONO_FAT
            "trans_fats", "trans_fat", "trans_fatty_acids" -> TRANS_FAT
            "dietary_fiber", "fiber", "fibre", "dietary_fibre" -> FIBER
            "total_sugars", "sugars", "sugar" -> SUGAR
            "sodium" -> SODIUM
            "cholesterol" -> CHOLESTEROL
            "potassium" -> POTASSIUM
            "calcium" -> CALCIUM
            "iron" -> IRON
            "magnesium" -> MAGNESIUM
            "phosphorus" -> PHOSPHORUS
            "zinc" -> ZINC
            "vitamin_a", "vitamina", "retinol" -> VITA
            "vitamin_c", "vitaminc", "ascorbic_acid" -> VITC
            "vitamin_d", "vitamind" -> VITD
            "vitamin_e", "vitamine" -> VITE
            "vitamin_k", "vitamink" -> VITK
            "vitamin_b6", "vitaminb6", "pyridoxine" -> VITB6
            "vitamin_b12", "vitaminb12", "cobalamin" -> VITB12
            "thiamin", "thiamine", "vitamin_b1" -> THIAMIN
            "riboflavin", "vitamin_b2" -> RIBOFLAVIN
            "niacin", "vitamin_b3" -> NIACIN
            "folate_dfe", "folate", "folic_acid", "vitamin_b9" -> FOLATE
            "caffeine" -> CAFFEINE
            else -> -1
        }

        fun parseNumericJson(json: String): DoubleArray? {
            if (json.length < 3) return null
            val arr = DoubleArray(30)
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
                    val idx = keyIndex(key)
                    if (idx >= 0) {
                        parseDoubleFast(json, vs, ve)?.let { arr[idx] = it }
                    }
                    i = json.indexOf('"', ve)
                } else {
                    i = json.indexOf('"', vs)
                }
            }
            return arr
        }

        fun DoubleArray?.n(idx: Int): Double {
            val v = this?.get(idx) ?: 0.0
            return if (!v.isNaN() && v != 0.0) v else 0.0
        }
        fun DoubleArray?.mg(idx: Int): Double {
            val r = n(idx)
            return if (r > 10.0) r / 1000.0 else r
        }

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

        val batchSize = 500
        val values = StringBuilder(1024 * 16)
        var pending = 0
        fun sqlQuote(s: String): String = "'" + s.replace("'", "''") + "'"
        fun sqlNull(s: String?): String = if (s == null) "NULL" else sqlQuote(s)
        fun sqlNum(d: Double?): String = if (d == null) "NULL" else d.toString()
        fun flushBatch() {
            if (pending == 0) return
            nutritionDb.insertFoodsBatchRaw(inserter, values.toString())
            values.setLength(0)
            pending = 0
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
                val calories = n.n(N)
                val protein = n.n(PROTEIN)
                val carbs = n.n(CARBS)
                val fat = n.n(FAT)
                val saturatedFat = n.n(SAT_FAT)
                val polyunsaturatedFat = n.n(POLY_FAT)
                val monounsaturatedFat = n.n(MONO_FAT)
                val transFat = n.n(TRANS_FAT)
                val fiber = n.n(FIBER)
                val sugar = n.n(SUGAR)
                val sodium = n.mg(SODIUM)
                val cholesterol = n.mg(CHOLESTEROL)
                val potassium = n.mg(POTASSIUM)
                val calcium = n.mg(CALCIUM)
                val iron = n.mg(IRON)
                val magnesium = n.mg(MAGNESIUM)
                val phosphorus = n.mg(PHOSPHORUS)
                val zinc = n.mg(ZINC)
                val vitaminA = n.mg(VITA)
                val vitaminC = n.mg(VITC)
                val vitaminD = n.mg(VITD)
                val vitaminE = n.mg(VITE)
                val vitaminK = n.mg(VITK)
                val vitaminB6 = n.mg(VITB6)
                val vitaminB12 = n.mg(VITB12)
                val thiamin = n.mg(THIAMIN)
                val riboflavin = n.mg(RIBOFLAVIN)
                val niacin = n.mg(NIACIN)
                val folate = n.mg(FOLATE)
                val caffeine = n.mg(CAFFEINE)

                if (pending > 0) values.append(',')
                values.append('(')
                values.append(sqlQuote(id)).append(',')
                values.append(sqlQuote(name)).append(',')
                values.append(sqlNull(servingCommonUnit)).append(',')
                values.append(sqlNum(servingCommonQty)).append(',')
                values.append(sqlQuote(servingMetricUnit)).append(',')
                values.append(servingMetricQty).append(',')
                values.append(sqlQuote(labelsJson)).append(',')
                values.append(sqlNull(foodType)).append(',')
                values.append(sqlQuote(searchText)).append(',')
                values.append(calories).append(',')
                values.append(protein).append(',')
                values.append(carbs).append(',')
                values.append(fat).append(',')
                values.append(saturatedFat).append(',')
                values.append(polyunsaturatedFat).append(',')
                values.append(monounsaturatedFat).append(',')
                values.append(transFat).append(',')
                values.append(fiber).append(',')
                values.append(sugar).append(',')
                values.append(sodium).append(',')
                values.append(cholesterol).append(',')
                values.append(potassium).append(',')
                values.append(calcium).append(',')
                values.append(iron).append(',')
                values.append(magnesium).append(',')
                values.append(phosphorus).append(',')
                values.append(zinc).append(',')
                values.append(vitaminA).append(',')
                values.append(vitaminC).append(',')
                values.append(vitaminD).append(',')
                values.append(vitaminE).append(',')
                values.append(vitaminK).append(',')
                values.append(vitaminB6).append(',')
                values.append(vitaminB12).append(',')
                values.append(thiamin).append(',')
                values.append(riboflavin).append(',')
                values.append(niacin).append(',')
                values.append(folate).append(',')
                values.append(caffeine)
                values.append(')')
                pending++
                if (pending >= batchSize) {
                    flushBatch()
                }
                written++
                if (written % BATCH_SIZE == 0) {
                    nutritionDb.rotateBatch(inserter)
                    onProgress(written)
                }
            } catch (_: Exception) { }

            rowIndex++
            line = reader.readLine()
        }

        flushBatch()
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
