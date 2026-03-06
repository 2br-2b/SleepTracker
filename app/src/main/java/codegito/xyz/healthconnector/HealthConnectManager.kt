package codegito.xyz.healthconnector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId

class HealthConnectManager(val context: Context) {
    val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val permissions = setOf(
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    suspend fun hasPermissions(): Boolean {
        return healthConnectClient.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    suspend fun getSleepSessions(start: Instant, end: Instant): Result<List<SleepSessionRecord>> {
        return try {
            if (!hasPermissions()) return Result.failure(Exception("Permissions not granted"))
            
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
            if (!hasPermissions()) return Result.failure(Exception("Permissions not granted"))

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
            if (!hasPermissions()) return Result.failure(Exception("Permissions not granted"))
            val response = healthConnectClient.readRecord(SleepSessionRecord::class, id)
            Result.success(response.record)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteSleepSession(id: String): Result<Unit> {
        return try {
            if (!hasPermissions()) return Result.failure(Exception("Permissions not granted"))
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
            if (!hasPermissions()) return Result.failure(Exception("Permissions not granted"))
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
