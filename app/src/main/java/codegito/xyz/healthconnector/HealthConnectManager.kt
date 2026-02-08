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

    suspend fun getSleepSessions(start: Instant, end: Instant): List<SleepSessionRecord> {
        return try {
            if (!hasPermissions()) return emptyList()
            
            val request = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            val response = healthConnectClient.readRecords(request)
            response.records
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun writeSleepSession(record: SleepSessionRecord) {
        if (hasPermissions()) {
            healthConnectClient.insertRecords(listOf(record))
        }
    }

    suspend fun deleteSleepSessions(start: Instant, end: Instant) {
        if (hasPermissions()) {
            healthConnectClient.deleteRecords(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        }
    }
}
