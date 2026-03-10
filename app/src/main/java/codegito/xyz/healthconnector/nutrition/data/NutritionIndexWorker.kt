package codegito.xyz.healthconnector.nutrition.data

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * WorkManager worker that builds the nutrition food database from the bundled zip.
 * Runs as an expedited job so it continues even if the user closes the app.
 *
 * Progress is reported via [setProgress] with keys [KEY_PROGRESS] and [KEY_TOTAL].
 * On success, output data contains [KEY_RECORD_COUNT].
 * On failure, [WorkInfo.state] is FAILED and [WorkInfo.outputData] contains [KEY_ERROR].
 */
class NutritionIndexWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = NutritionIndexBuildManager(applicationContext)

        // Post the initial foreground notification
        setForeground(buildForegroundInfo(0, -1))

        data class ProgressUpdate(val current: Int, val total: Int)

        val progressChannel = Channel<ProgressUpdate>(capacity = Channel.CONFLATED)
        var lastReportedMs = 0L

        val buildResult = coroutineScope {
            // Drain progress updates: update WorkManager progress data and the notification
            val progressJob = launch {
                for (update in progressChannel) {
                    setProgress(workDataOf(KEY_PROGRESS to update.current, KEY_TOTAL to update.total))
                    setForeground(buildForegroundInfo(update.current, update.total))
                }
            }

            val result = manager.buildFromBundledZip(
                progressCallback = { current, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastReportedMs >= REPORT_INTERVAL_MS) {
                        lastReportedMs = now
                        progressChannel.trySend(ProgressUpdate(current, total))
                    }
                }
            )

            progressChannel.close()
            progressJob.join()
            result
        }

        return buildResult.fold(
            onSuccess = { build ->
                setProgress(workDataOf(KEY_PROGRESS to build.recordCount, KEY_TOTAL to build.recordCount))
                Result.success(workDataOf(KEY_RECORD_COUNT to build.recordCount))
            },
            onFailure = { e ->
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
            }
        )
    }

    override suspend fun getForegroundInfo() = buildForegroundInfo(0, -1)

    private fun buildForegroundInfo(current: Int, total: Int): ForegroundInfo {
        val channelId = "nutrition_index"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId,
                        "Food database setup",
                        android.app.NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val text = when {
            current <= 0 -> "Starting…"
            total > 0 && total != current -> "$current / $total foods indexed"
            else -> "$current foods indexed"
        }
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Building food database")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(0), current.coerceAtLeast(0), total <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "nutrition_index_build"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_RECORD_COUNT = "record_count"
        const val KEY_ERROR = "error"
        private const val NOTIFICATION_ID = 9901
        private const val REPORT_INTERVAL_MS = 2_000L

        fun enqueue(context: Context): androidx.work.Operation {
            val request = OneTimeWorkRequestBuilder<NutritionIndexWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(WORK_NAME)
                .build()
            return WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    androidx.work.ExistingWorkPolicy.KEEP,
                    request
                )
        }

        fun observeInfo(context: Context) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
    }
}
