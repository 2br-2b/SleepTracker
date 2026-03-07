package codegito.xyz.healthconnector.nutrition.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

class NutritionIndexBuildManager(
    private val context: Context
) {

    data class BuildResult(
        val recordCount: Int,
        val sourceLocation: String,
        val debugLog: String,
        val debugLogLocation: String
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
        val diagnosticsFile = outputDir.resolve("build-log.txt")

        val diagnostics = StringBuilder()
        diagnostics.appendLine("Nutrition index build log")
        diagnostics.appendLine("timestamp=${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}")
        diagnostics.appendLine("source=$source")

        var bestResult: CsvBuildResult? = null
        var bestSource = source

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                diagnostics.appendLine("zip_entry=${entry.name} size=${entry.size} isDirectory=${entry.isDirectory}")
                if (!entry.isDirectory) {
                    val reader = BufferedReader(InputStreamReader(NonClosingInputStream(zip)))
                    val previewLines = mutableListOf<String>()
                    repeat(2) {
                        val line = reader.readLine() ?: return@repeat
                        previewLines.add(line)
                    }
                    previewLines.forEachIndexed { i, line ->
                        val truncated = if (line.length > 300) line.take(300) + "…" else line
                        diagnostics.appendLine("  line_${i + 1}=$truncated")
                    }

                    val entryNameLower = entry.name.lowercase()
                    val isCsv = entryNameLower.endsWith(".csv")
                    val isTsv = entryNameLower.endsWith(".tsv")
                    if (isCsv || isTsv) {
                        diagnostics.appendLine("  type=${if (isTsv) "tsv" else "csv"} (processing)")
                        val tmpIndex = outputDir.resolve("index-${bestResult?.recordCount ?: 0}.jsonl.tmp")
                        val result = runCatching {
                            if (isTsv) writeIndexFromTsv(reader, tmpIndex, previewLines)
                            else writeIndexFromCsv(reader, tmpIndex, previewLines)
                        }

                        result.onSuccess { parsed ->
                            diagnostics.appendLine("  header_columns=${parsed.headerColumns.joinToString(",")}")
                            diagnostics.appendLine("  rows_written=${parsed.recordCount}")
                            if (parsed.recordCount > 0 && (bestResult == null || parsed.recordCount > bestResult!!.recordCount)) {
                                bestResult = parsed
                                bestSource = "$source::${entry.name}"
                                tmpIndex.copyTo(indexFile, overwrite = true)
                                diagnostics.appendLine("  selected_for_index=true")
                            } else {
                                diagnostics.appendLine("  selected_for_index=false (count=${parsed.recordCount} best=${bestResult?.recordCount})")
                            }
                        }.onFailure { throwable ->
                            diagnostics.appendLine("  parse_error=${throwable.message ?: throwable::class.java.simpleName}")
                        }
                        tmpIndex.delete()
                    } else {
                        diagnostics.appendLine("  type=non-csv/tsv (skipped)")
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val finalCount = bestResult?.recordCount ?: 0
        if (finalCount == 0) {
            diagnostics.appendLine("result=failed")
            diagnostics.appendLine("reason=No usable CSV/TSV nutrition records found in ZIP")
            val diagnosticsText = diagnostics.toString()
            diagnosticsFile.writeText(diagnosticsText)
            val downloadsLocation = writeDiagnosticsToDownloads(diagnosticsText)
            val logLocation = downloadsLocation ?: diagnosticsFile.absolutePath
            error("No usable CSV/TSV nutrition records found in ZIP. See build log at $logLocation")
        }

        diagnostics.appendLine("result=success")
        diagnostics.appendLine("selected_source=$bestSource")
        diagnostics.appendLine("record_count=$finalCount")
        val diagnosticsText = diagnostics.toString()
        diagnosticsFile.writeText(diagnosticsText)
        val downloadsLocation = writeDiagnosticsToDownloads(diagnosticsText)
        val logLocation = downloadsLocation ?: diagnosticsFile.absolutePath

        val metadata = JSONObject().apply {
            put("source", "Open Nutrition Dataset")
            put("sourceLocation", bestSource)
            put("recordCount", finalCount)
            put("buildLogPath", diagnosticsFile.absolutePath)
            put("buildLogDownloadUri", downloadsLocation ?: JSONObject.NULL)
        }
        metadataFile.writeText(metadata.toString(2))

        return BuildResult(
            recordCount = finalCount,
            sourceLocation = bestSource,
            debugLog = diagnosticsText,
            debugLogLocation = logLocation
        )
    }

    private fun writeIndexFromCsv(
        reader: BufferedReader,
        indexFile: java.io.File,
        preReadLines: List<String> = emptyList()
    ): CsvBuildResult {
        val lineSource: Sequence<String> = sequence {
            preReadLines.forEach { yield(it) }
            yieldAll(reader.lineSequence())
        }
        val lineIterator = lineSource.iterator()
        val header = if (lineIterator.hasNext()) lineIterator.next() else return CsvBuildResult(recordCount = 0, headerColumns = emptyList())
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
            lineIterator.asSequence().forEachIndexed { rowIndex, rawLine ->
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

        return CsvBuildResult(recordCount = written, headerColumns = headerColumns)
    }

    private fun writeIndexFromTsv(
        reader: BufferedReader,
        indexFile: java.io.File,
        preReadLines: List<String> = emptyList()
    ): CsvBuildResult {
        val lineSource: Sequence<String> = sequence {
            preReadLines.forEach { yield(it) }
            yieldAll(reader.lineSequence())
        }
        val lineIterator = lineSource.iterator()
        val header = if (lineIterator.hasNext()) lineIterator.next() else return CsvBuildResult(recordCount = 0, headerColumns = emptyList())
        val headerColumns = header.split("\t").map { it.trim().lowercase() }

        fun col(vararg names: String): Int {
            names.forEach { name ->
                val i = headerColumns.indexOf(name)
                if (i >= 0) return i
            }
            return -1
        }

        val idIdx = col("id")
        val nameIdx = col("name", "food_name", "description")
        val nutritionIdx = col("nutrition_100g")

        if (nameIdx < 0) error("TSV is missing name/food_name/description column")

        var written = 0
        indexFile.bufferedWriter().use { out ->
            lineIterator.asSequence().forEachIndexed { rowIndex, rawLine ->
                if (written >= MAX_RECORDS) return@forEachIndexed
                val row = rawLine.split("\t")
                val name = row.getOrNull(nameIdx)?.trim().orEmpty()
                if (name.isEmpty()) return@forEachIndexed

                var calories = 0.0
                var protein = 0.0
                var carbs = 0.0
                var fat = 0.0
                val nutritionRaw = row.getOrNull(nutritionIdx)?.trim().orEmpty()
                if (nutritionRaw.isNotEmpty()) {
                    runCatching {
                        val n = JSONObject(nutritionRaw)
                        calories = n.optDouble("energy_kcal", n.optDouble("calories", 0.0))
                        protein = n.optDouble("protein", 0.0)
                        carbs = n.optDouble("carbohydrates", n.optDouble("carbs", 0.0))
                        fat = n.optDouble("fat", 0.0)
                    }
                }

                val json = JSONObject().apply {
                    put("id", row.getOrNull(idIdx)?.takeIf { it.isNotBlank() } ?: "tsv-$rowIndex")
                    put("name", name)
                    put("baseAmount", 100.0)
                    put("calories", calories)
                    put("protein", protein)
                    put("carbs", carbs)
                    put("fat", fat)
                }
                out.appendLine(json.toString())
                written += 1
            }
        }

        return CsvBuildResult(recordCount = written, headerColumns = headerColumns)
    }

    private data class CsvBuildResult(
        val recordCount: Int,
        val headerColumns: List<String>
    )

    private class NonClosingInputStream(
        private val delegate: InputStream
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() = Unit
    }

    private fun writeDiagnosticsToDownloads(diagnosticsText: String): String? {
        return runCatching {
            val fileName = "sleeptracker-nutrition-build-log-${System.currentTimeMillis()}.txt"
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                out.write(diagnosticsText)
            } ?: return null
            uri.toString()
        }.getOrNull()
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
