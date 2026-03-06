package codegito.xyz.healthconnector.nutrition.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

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

    suspend fun buildFromBundledZip(): Result<BuildResult> = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(BUNDLED_ZIP_ASSET_PATH).use { input ->
                buildFromZipStream(input, "asset:$BUNDLED_ZIP_ASSET_PATH")
            }
        }
    }

    suspend fun buildFromUri(uri: Uri): Result<BuildResult> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            resolver.openInputStream(uri)?.use { input ->
                buildFromZipStream(input, "uri:$uri")
            } ?: error("Unable to open selected ZIP")
        }
    }

    private fun buildFromZipStream(input: InputStream, source: String): BuildResult {
        val outputDir = context.filesDir.resolve("nutrition")
        if (!outputDir.exists()) outputDir.mkdirs()
        val indexFile = outputDir.resolve("index.jsonl")
        val metadataFile = outputDir.resolve("metadata.json")

        var count = 0
        var csvSource = source

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".csv")) {
                    csvSource = "$source::${entry.name}"
                    BufferedReader(InputStreamReader(zip)).use { reader ->
                        count = writeIndexFromCsv(reader, indexFile)
                    }
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (count == 0) {
            error("No usable CSV nutrition records found in ZIP")
        }

        val metadata = JSONObject().apply {
            put("source", "Open Nutrition Dataset")
            put("sourceLocation", csvSource)
            put("recordCount", count)
        }
        metadataFile.writeText(metadata.toString(2))

        return BuildResult(recordCount = count, sourceLocation = csvSource)
    }

    private fun writeIndexFromCsv(reader: BufferedReader, indexFile: java.io.File): Int {
        val header = reader.readLine() ?: return 0
        val headerColumns = parseCsvLine(header).map { it.trim().lowercase() }

        fun indexOfAny(vararg names: String): Int {
            names.forEach { candidate ->
                val i = headerColumns.indexOf(candidate)
                if (i >= 0) return i
            }
            return -1
        }

        val idIdx = indexOfAny("id")
        val nameIdx = indexOfAny("name", "food_name", "description")
        val baseAmountIdx = indexOfAny("base_amount")
        val caloriesIdx = indexOfAny("calories", "energy_kcal")
        val proteinIdx = indexOfAny("protein")
        val carbsIdx = indexOfAny("carbohydrates", "carbs")
        val fatIdx = indexOfAny("fat")

        if (nameIdx < 0) error("CSV is missing name/food_name/description column")

        var written = 0
        indexFile.bufferedWriter().use { out ->
            reader.lineSequence().forEachIndexed { rowIndex, rawLine ->
                if (written >= MAX_RECORDS) return@forEachIndexed
                val row = parseCsvLine(rawLine)
                val name = row.getOrNull(nameIdx)?.trim().orEmpty()
                if (name.isEmpty()) return@forEachIndexed

                val json = JSONObject().apply {
                    put("id", row.getOrNull(idIdx)?.takeIf { it.isNotBlank() } ?: "csv-$rowIndex")
                    put("name", name)
                    put("baseAmount", row.getOrNull(baseAmountIdx)?.toDoubleOrNull() ?: 100.0)
                    put("calories", row.getOrNull(caloriesIdx)?.toDoubleOrNull() ?: 0.0)
                    put("protein", row.getOrNull(proteinIdx)?.toDoubleOrNull() ?: 0.0)
                    put("carbs", row.getOrNull(carbsIdx)?.toDoubleOrNull() ?: 0.0)
                    put("fat", row.getOrNull(fatIdx)?.toDoubleOrNull() ?: 0.0)
                }
                out.appendLine(json.toString())
                written += 1
            }
        }

        return written
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i += 1
                    } else {
                        inQuotes = !inQuotes
                    }
                }

                c == ',' && !inQuotes -> {
                    values.add(current.toString())
                    current.clear()
                }

                else -> current.append(c)
            }
            i += 1
        }

        values.add(current.toString())
        return values
    }

    companion object {
        private const val BUNDLED_ZIP_ASSET_PATH = "nutrition/opennutrition-dataset-2025.1.zip"
        private const val MAX_RECORDS = 50000
    }
}
