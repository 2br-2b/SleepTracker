package codegito.xyz.healthconnector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import codegito.xyz.healthconnector.exercise.domain.ExerciseCategory
import codegito.xyz.healthconnector.exercise.domain.ExerciseInputs
import codegito.xyz.healthconnector.exercise.domain.ExerciseType
import codegito.xyz.healthconnector.exercise.domain.ExerciseUserPrefs
import codegito.xyz.healthconnector.exercise.domain.LoggedExerciseEntry
import codegito.xyz.healthconnector.exercise.domain.Sex
import codegito.xyz.healthconnector.weight.domain.WeightEntry
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class HealthConnectManager(val context: Context) {
    val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val sleepWritePermission = HealthPermission.getWritePermission(SleepSessionRecord::class)
    val sleepReadPermission  = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val nutritionWritePermission = HealthPermission.getWritePermission(NutritionRecord::class)
    val nutritionReadPermission  = HealthPermission.getReadPermission(NutritionRecord::class)

    // Weight
    val weightWritePermission = HealthPermission.getWritePermission(WeightRecord::class)
    val weightReadPermission  = HealthPermission.getReadPermission(WeightRecord::class)

    // Exercise writes
    val exerciseWritePermission   = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    val exerciseReadPermission    = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    val distanceWritePermission   = HealthPermission.getWritePermission(DistanceRecord::class)
    val distanceReadPermission    = HealthPermission.getReadPermission(DistanceRecord::class)
    val caloriesWritePermission   = HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
    val caloriesReadPermission    = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)

    // Exercise enrichment reads (all optional)
    val heartRateReadPermission        = HealthPermission.getReadPermission(HeartRateRecord::class)
    val restingHrReadPermission        = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    val speedReadPermission            = HealthPermission.getReadPermission(SpeedRecord::class)
    val elevationReadPermission        = HealthPermission.getReadPermission(ElevationGainedRecord::class)
    val powerReadPermission            = HealthPermission.getReadPermission(PowerRecord::class)
    val stepsReadPermission            = HealthPermission.getReadPermission(StepsRecord::class)
    val heightReadPermission           = HealthPermission.getReadPermission(HeightRecord::class)
    val bodyFatReadPermission          = HealthPermission.getReadPermission(BodyFatRecord::class)
    val leanBodyMassReadPermission     = HealthPermission.getReadPermission(LeanBodyMassRecord::class)
    val bmrReadPermission              = HealthPermission.getReadPermission(BasalMetabolicRateRecord::class)
    val vo2maxReadPermission           = HealthPermission.getReadPermission(Vo2MaxRecord::class)

    val permissions = setOf(
        sleepWritePermission,
        sleepReadPermission,
        nutritionWritePermission,
        nutritionReadPermission
    )

    val sleepPermissions = setOf(sleepWritePermission, sleepReadPermission)
    val nutritionPermissions = setOf(nutritionWritePermission, nutritionReadPermission)
    val weightPermissions = setOf(weightWritePermission, weightReadPermission)
    val exercisePermissions = setOf(
        exerciseWritePermission, exerciseReadPermission,
        distanceWritePermission, distanceReadPermission,
        caloriesWritePermission, caloriesReadPermission
    )
    val exerciseEnrichmentReadPermissions = setOf(
        weightReadPermission,
        heartRateReadPermission,
        restingHrReadPermission,
        speedReadPermission,
        elevationReadPermission,
        powerReadPermission,
        stepsReadPermission,
        heightReadPermission,
        bodyFatReadPermission,
        leanBodyMassReadPermission,
        bmrReadPermission,
        vo2maxReadPermission
    )

    suspend fun getGrantedPermissions(): Set<String> =
        healthConnectClient.permissionController.getGrantedPermissions()

    suspend fun hasPermissions(): Boolean =
        getGrantedPermissions().containsAll(permissions)

    suspend fun hasSleepWritePermission(): Boolean =
        getGrantedPermissions().contains(sleepWritePermission)

    suspend fun hasSleepReadPermission(): Boolean =
        getGrantedPermissions().contains(sleepReadPermission)

    suspend fun hasNutritionWritePermission(): Boolean =
        getGrantedPermissions().contains(nutritionWritePermission)

    suspend fun hasNutritionReadPermission(): Boolean =
        getGrantedPermissions().contains(nutritionReadPermission)

    suspend fun getSleepSessions(start: Instant, end: Instant): Result<List<SleepSessionRecord>> {
        return try {
            if (!hasSleepReadPermission()) return Result.failure(Exception("Sleep read permission not granted"))
            
            val request = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            val response = healthConnectClient.readRecords(request)
            Result.success(response.records)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun writeSleepLog(
        bedtime: java.time.LocalDateTime,
        segments: List<SleepSegment>,
        title: String? = null
    ): Result<Unit> {
        return try {
            if (!hasSleepWritePermission()) return Result.failure(Exception("Sleep write permission not granted"))

            val zoneId = ZoneId.systemDefault()
            val startInstant = bedtime.atZone(zoneId).toInstant()
            val endInstant = segments.last().endTime.atZone(zoneId).toInstant()

            val stages = segments.map { segment ->
                val segmentStartInstant = (segments.getOrNull(segments.indexOf(segment) - 1)?.endTime ?: bedtime)
                    .atZone(zoneId).toInstant()
                val segmentEndInstant = segment.endTime.atZone(zoneId).toInstant()

                SleepSessionRecord.Stage(
                    startTime = segmentStartInstant,
                    endTime = segmentEndInstant,
                    stage = segment.sleepStage
                )
            }

            val record = SleepSessionRecord(
                startTime = startInstant,
                startZoneOffset = zoneId.rules.getOffset(startInstant),
                endTime = endInstant,
                endZoneOffset = zoneId.rules.getOffset(endInstant),
                title = title,
                stages = stages
            )

            healthConnectClient.insertRecords(listOf(record))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSleepSession(id: String): Result<SleepSessionRecord?> {
        return try {
            if (!hasSleepReadPermission()) return Result.failure(Exception("Sleep read permission not granted"))
            val response = healthConnectClient.readRecord(SleepSessionRecord::class, id)
            Result.success(response.record)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteSleepSession(id: String): Result<Unit> {
        return try {
            if (!hasSleepWritePermission()) return Result.failure(Exception("Sleep write permission not granted"))
            healthConnectClient.deleteRecords(
                recordType = SleepSessionRecord::class,
                recordIdsList = listOf(id),
                clientRecordIdsList = emptyList()
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteSleepSessions(start: Instant, end: Instant): Result<Unit> {
        return try {
            if (!hasSleepWritePermission()) return Result.failure(Exception("Sleep write permission not granted"))
            healthConnectClient.deleteRecords(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun hasWeightWritePermission(): Boolean =
        getGrantedPermissions().contains(weightWritePermission)

    suspend fun hasWeightReadPermission(): Boolean =
        getGrantedPermissions().contains(weightReadPermission)

    suspend fun hasExerciseWritePermission(): Boolean =
        getGrantedPermissions().contains(exerciseWritePermission)

    suspend fun hasExerciseReadPermission(): Boolean =
        getGrantedPermissions().contains(exerciseReadPermission)

    // ── Weight ────────────────────────────────────────────────────────────────

    suspend fun getWeightEntries(start: Instant, end: Instant): Result<List<WeightRecord>> {
        return try {
            if (!hasWeightReadPermission()) return Result.failure(Exception("Weight read permission not granted"))
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(start, end))
            )
            Result.success(response.records)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun writeWeightEntry(weightKg: Double, timestamp: Instant): Result<String> {
        return try {
            if (!hasWeightWritePermission()) return Result.failure(Exception("Weight write permission not granted"))
            val zoneId = ZoneId.systemDefault()
            val record = WeightRecord(
                time = timestamp,
                zoneOffset = zoneId.rules.getOffset(timestamp),
                weight = Mass.kilograms(weightKg)
            )
            val response = healthConnectClient.insertRecords(listOf(record))
            Result.success(response.recordIdsList.firstOrNull() ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteWeightEntry(id: String): Result<Unit> {
        return try {
            if (!hasWeightWritePermission()) return Result.failure(Exception("Weight write permission not granted"))
            healthConnectClient.deleteRecords(
                recordType = WeightRecord::class,
                recordIdsList = listOf(id),
                clientRecordIdsList = emptyList()
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Returns the most recent weight in kg, or null if unavailable / no permission. */
    suspend fun getLatestWeightKg(): Double? {
        return try {
            if (!hasWeightReadPermission()) return null
            val end = Instant.now()
            val start = end.minusSeconds(365L * 24 * 3600) // look back 1 year
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(start, end))
            )
            response.records.maxByOrNull { it.time }?.weight?.inKilograms
        } catch (_: Exception) {
            null
        }
    }

    // ── Exercise ──────────────────────────────────────────────────────────────

    suspend fun getExerciseSessions(start: Instant, end: Instant): Result<List<ExerciseSessionRecord>> {
        return try {
            if (!hasExerciseReadPermission()) return Result.failure(Exception("Exercise read permission not granted"))
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, end))
            )
            Result.success(response.records)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun writeExerciseSession(entry: LoggedExerciseEntry): Result<Unit> {
        return try {
            if (!hasExerciseWritePermission()) return Result.failure(Exception("Exercise write permission not granted"))
            val zoneId = ZoneId.systemDefault()
            val records = mutableListOf<androidx.health.connect.client.records.Record>()

            records.add(
                ExerciseSessionRecord(
                    startTime = entry.startTime,
                    startZoneOffset = zoneId.rules.getOffset(entry.startTime),
                    endTime = entry.endTime,
                    endZoneOffset = zoneId.rules.getOffset(entry.endTime),
                    exerciseType = mapExerciseType(entry.exerciseType),
                    title = entry.exerciseType.displayName,
                    notes = entry.notes
                )
            )

            if (entry.distanceMeters != null && entry.distanceMeters > 0) {
                records.add(
                    DistanceRecord(
                        startTime = entry.startTime,
                        startZoneOffset = zoneId.rules.getOffset(entry.startTime),
                        endTime = entry.endTime,
                        endZoneOffset = zoneId.rules.getOffset(entry.endTime),
                        distance = Length.meters(entry.distanceMeters)
                    )
                )
            }

            if (entry.estimatedCalories > 0) {
                records.add(
                    TotalCaloriesBurnedRecord(
                        startTime = entry.startTime,
                        startZoneOffset = zoneId.rules.getOffset(entry.startTime),
                        endTime = entry.endTime,
                        endZoneOffset = zoneId.rules.getOffset(entry.endTime),
                        energy = Energy.kilocalories(entry.estimatedCalories)
                    )
                )
            }

            healthConnectClient.insertRecords(records)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteExerciseSession(id: String): Result<Unit> {
        return try {
            if (!hasExerciseWritePermission()) return Result.failure(Exception("Exercise write permission not granted"))
            healthConnectClient.deleteRecords(
                recordType = ExerciseSessionRecord::class,
                recordIdsList = listOf(id),
                clientRecordIdsList = emptyList()
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gathers all available HC enrichment data for an exercise window.
     * Gracefully falls back when individual permissions are denied.
     */
    suspend fun enrichExerciseInputs(
        exerciseType: ExerciseType,
        start: Instant,
        end: Instant,
        userPrefs: ExerciseUserPrefs,
        userEnteredDistanceMeters: Double? = null
    ): ExerciseInputs {
        val granted = getGrantedPermissions()
        val filter = TimeRangeFilter.between(start, end)
        val durationMinutes = (end.epochSecond - start.epochSecond) / 60.0

        fun <T : androidx.health.connect.client.records.Record> safeRead(
            perm: String,
            block: suspend () -> List<T>
        ): List<T> = if (granted.contains(perm)) {
            try { kotlinx.coroutines.runBlocking { block() } } catch (_: Exception) { emptyList() }
        } else emptyList()

        // Latest bio records (lookback 1 year)
        val bioFilter = TimeRangeFilter.between(end.minusSeconds(365L * 24 * 3600), end)

        val weightKg = if (granted.contains(weightReadPermission)) {
            runCatching {
                healthConnectClient.readRecords(ReadRecordsRequest(WeightRecord::class, bioFilter))
                    .records.maxByOrNull { it.time }?.weight?.inKilograms
            }.getOrNull()
        } else null

        val lbm = if (granted.contains(leanBodyMassReadPermission)) {
            runCatching {
                healthConnectClient.readRecords(ReadRecordsRequest(LeanBodyMassRecord::class, bioFilter))
                    .records.maxByOrNull { it.time }?.mass?.inKilograms
            }.getOrNull()
        } else null

        val bodyFat = if (granted.contains(bodyFatReadPermission)) {
            runCatching {
                healthConnectClient.readRecords(ReadRecordsRequest(BodyFatRecord::class, bioFilter))
                    .records.maxByOrNull { it.time }?.percentage?.value?.div(100.0)
            }.getOrNull()
        } else null

        val measuredBmr = if (granted.contains(bmrReadPermission)) {
            runCatching {
                healthConnectClient.readRecords(ReadRecordsRequest(BasalMetabolicRateRecord::class, bioFilter))
                    .records.maxByOrNull { it.time }?.basalMetabolicRate?.inKilocaloriesPerDay
            }.getOrNull()
        } else null

        val vo2max = if (granted.contains(vo2maxReadPermission)) {
            runCatching {
                healthConnectClient.readRecords(ReadRecordsRequest(Vo2MaxRecord::class, bioFilter))
                    .records.maxByOrNull { it.time }?.vo2MillilitersPerMinuteKilogram
            }.getOrNull()
        } else null

        val restingHr = if (granted.contains(restingHrReadPermission)) {
            runCatching {
                healthConnectClient.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, bioFilter))
                    .records.maxByOrNull { it.time }?.beatsPerMinute?.toDouble()
            }.getOrNull()
        } else null

        val height = if (granted.contains(heightReadPermission)) {
            runCatching {
                healthConnectClient.readRecords(ReadRecordsRequest(HeightRecord::class, bioFilter))
                    .records.maxByOrNull { it.time }?.height?.inMeters
            }.getOrNull()
        } else null

        // Window-based records
        val avgHr = if (granted.contains(heartRateReadPermission)) {
            runCatching {
                val samples = healthConnectClient.readRecords(
                    ReadRecordsRequest(HeartRateRecord::class, filter)
                ).records.flatMap { it.samples }
                if (samples.isNotEmpty()) {
                    val maxHr = if (userPrefs.age != null) (220 - userPrefs.age).toDouble() else 220.0
                    val valid = samples.map { it.beatsPerMinute.toDouble() }
                        .filter { it >= 40.0 && it <= maxHr }
                    if (valid.isNotEmpty()) valid.average() else null
                } else null
            }.getOrNull()
        } else null

        val avgSpeed = if (granted.contains(speedReadPermission)) {
            runCatching {
                val samples = healthConnectClient.readRecords(
                    ReadRecordsRequest(SpeedRecord::class, filter)
                ).records.flatMap { it.samples }
                if (samples.isNotEmpty()) samples.map { it.speed.inMetersPerSecond }.average() else null
            }.getOrNull()
        } else null

        val distanceM = userEnteredDistanceMeters ?: if (granted.contains(distanceReadPermission)) {
            runCatching {
                healthConnectClient.readRecords(ReadRecordsRequest(DistanceRecord::class, filter))
                    .records.sumOf { it.distance.inMeters }.takeIf { it > 0 }
            }.getOrNull()
        } else null

        val elevationM = if (granted.contains(elevationReadPermission)) {
            runCatching {
                healthConnectClient.readRecords(ReadRecordsRequest(ElevationGainedRecord::class, filter))
                    .records.sumOf { it.elevation.inMeters }.takeIf { it > 0 }
            }.getOrNull()
        } else null

        val avgPower = if (granted.contains(powerReadPermission)) {
            runCatching {
                val samples = healthConnectClient.readRecords(
                    ReadRecordsRequest(PowerRecord::class, filter)
                ).records.flatMap { it.samples }
                if (samples.isNotEmpty()) samples.map { it.power.inWatts }.average() else null
            }.getOrNull()
        } else null

        val steps = if (granted.contains(stepsReadPermission)) {
            runCatching {
                healthConnectClient.readRecords(ReadRecordsRequest(StepsRecord::class, filter))
                    .records.sumOf { it.count }.takeIf { it > 0 }
            }.getOrNull()
        } else null

        return ExerciseInputs(
            exerciseType = exerciseType,
            durationMinutes = durationMinutes,
            met = exerciseType.met,
            weightKg = weightKg ?: userPrefs.defaultWeightKg,
            lbmKg = lbm,
            bodyFatFraction = bodyFat,
            measuredBmr = measuredBmr,
            vo2maxMlKgMin = vo2max,
            avgHeartRate = avgHr,
            restingHeartRate = restingHr,
            avgSpeedMps = avgSpeed,
            distanceMeters = distanceM,
            elevationGainedMeters = elevationM,
            avgPowerWatts = avgPower,
            steps = steps,
            heightMeters = height,
            age = userPrefs.age,
            sex = userPrefs.sex
        )
    }

    private fun mapExerciseType(type: ExerciseType): Int = when (type.id) {
        "RUNNING"        -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "WALKING"        -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        "CYCLING"        -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        "SWIMMING"       -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        "TREADMILL"      -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        "PUSHUP"         -> ExerciseSessionRecord.EXERCISE_TYPE_PUSH_UPS
        "SQUAT"          -> ExerciseSessionRecord.EXERCISE_TYPE_SQUATS
        "PULLUP"         -> ExerciseSessionRecord.EXERCISE_TYPE_PULL_UPS
        "WEIGHT_LIFTING" -> ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING
        else             -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }
}
