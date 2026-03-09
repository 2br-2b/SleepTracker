package codegito.xyz.healthconnector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
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

    val sleepPermissions = setOf(sleepWritePermission, sleepReadPermission)
    val nutritionPermissions = setOf(nutritionWritePermission, nutritionReadPermission)

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
}
