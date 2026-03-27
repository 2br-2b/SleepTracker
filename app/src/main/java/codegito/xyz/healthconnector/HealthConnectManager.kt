package codegito.xyz.healthconnector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.ZoneId

class HealthConnectManager(val context: Context) {
    val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val sleepWritePermission = HealthPermission.getWritePermission(SleepSessionRecord::class)
    val sleepReadPermission  = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val nutritionWritePermission = HealthPermission.getWritePermission(NutritionRecord::class)
    val nutritionReadPermission  = HealthPermission.getReadPermission(NutritionRecord::class)

    val permissions = setOf(
        sleepWritePermission,
        sleepReadPermission,
        nutritionWritePermission,
        nutritionReadPermission
    )

    val exerciseWritePermission = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    val exerciseReadPermission  = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    val weightWritePermission   = HealthPermission.getWritePermission(WeightRecord::class)
    val weightReadPermission    = HealthPermission.getReadPermission(WeightRecord::class)

    val sleepPermissions = setOf(sleepWritePermission, sleepReadPermission)
    val nutritionPermissions = setOf(nutritionWritePermission, nutritionReadPermission)
    val exercisePermissions = setOf(exerciseWritePermission, exerciseReadPermission)
    val weightPermissions = setOf(weightWritePermission, weightReadPermission)

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

    suspend fun hasExerciseWritePermission(): Boolean =
        getGrantedPermissions().contains(exerciseWritePermission)

    suspend fun hasExerciseReadPermission(): Boolean =
        getGrantedPermissions().contains(exerciseReadPermission)

    suspend fun hasWeightReadPermission(): Boolean =
        getGrantedPermissions().contains(weightReadPermission)

    suspend fun hasWeightWritePermission(): Boolean =
        getGrantedPermissions().contains(weightWritePermission)

    /** Write an exercise session to Health Connect. Returns the inserted record ID. */
    suspend fun writeExerciseSession(
        startTime: Instant,
        endTime: Instant,
        title: String,
        caloriesKcal: Double?
    ): Result<String> {
        return try {
            if (!hasExerciseWritePermission()) return Result.failure(Exception("Exercise write permission not granted"))
            val zoneId = ZoneId.systemDefault()
            val record = ExerciseSessionRecord(
                startTime = startTime,
                startZoneOffset = zoneId.rules.getOffset(startTime),
                endTime = endTime,
                endZoneOffset = zoneId.rules.getOffset(endTime),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
                title = title
            )
            val result = healthConnectClient.insertRecords(listOf(record))
            if (caloriesKcal != null && caloriesKcal > 0) {
                val calorieRecord = ActiveCaloriesBurnedRecord(
                    startTime = startTime,
                    startZoneOffset = zoneId.rules.getOffset(startTime),
                    endTime = endTime,
                    endZoneOffset = zoneId.rules.getOffset(endTime),
                    energy = Energy.kilocalories(caloriesKcal)
                )
                healthConnectClient.insertRecords(listOf(calorieRecord))
            }
            Result.success(result.recordIdsList.firstOrNull() ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getExerciseSessions(start: Instant, end: Instant): Result<List<ExerciseSessionRecord>> {
        return try {
            if (!hasExerciseReadPermission()) return Result.failure(Exception("Exercise read permission not granted"))
            val request = ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            Result.success(healthConnectClient.readRecords(request).records)
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

    /** Read the most recent weight record, or null if unavailable / no permission. */
    suspend fun getLatestWeightKg(): Double? {
        return try {
            if (!hasWeightReadPermission()) return null
            val end = Instant.now()
            val start = end.minus(java.time.Duration.ofDays(365))
            val request = ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            healthConnectClient.readRecords(request).records
                .maxByOrNull { it.time }
                ?.weight?.inKilograms
        } catch (_: Exception) { null }
    }

    suspend fun writeWeight(weightKg: Double): Result<Unit> {
        return try {
            if (!hasWeightWritePermission()) return Result.failure(Exception("Weight write permission not granted"))
            val now = Instant.now()
            val record = WeightRecord(
                time = now,
                zoneOffset = ZoneId.systemDefault().rules.getOffset(now),
                weight = Mass.kilograms(weightKg)
            )
            healthConnectClient.insertRecords(listOf(record))
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
}
