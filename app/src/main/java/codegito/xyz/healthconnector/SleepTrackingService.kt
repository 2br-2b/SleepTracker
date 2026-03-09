package codegito.xyz.healthconnector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.util.Log
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.SleepDetectionMode

class SleepTrackingService : Service() {

    private val screenStateReceiver = ScreenStateReceiver()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var settingsJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()

        // Create notification channels for reminders
        NotificationHelper.createNotificationChannels(this)

        // Start as foreground service immediately to satisfy Android requirements
        startForegroundServiceCompat()

        // Register the screen state receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)

        // Monitor settings for changes
        settingsJob = scope.launch {
            val prefs = UserPreferencesRepository.getInstance(this@SleepTrackingService)
            
            // Watch for mode or window changes
            kotlinx.coroutines.flow.combine(
                prefs.sleepDetectionMode,
                prefs.wakeupWindow,
                prefs.bedtimeWindow
            ) { mode, wakeupWindow, bedtimeWindow ->
                Triple(mode, wakeupWindow, bedtimeWindow)
            }.collect { (mode, wakeupWindow, bedtimeWindow) ->
                if (mode == SleepDetectionMode.MANUAL) {
                    Log.d("SleepTrackingService", "Manual mode enabled, stopping service")
                    stopSelf()
                } else {
                    // Reschedule deadline alarm if settings change
                    NotificationHelper.scheduleDeadlineAlarm(this@SleepTrackingService, wakeupWindow.endMinutes)

                    // Schedule next start/stop based on windows
                    NotificationHelper.scheduleServiceLifecycle(
                        this@SleepTrackingService,
                        bedtimeWindow.startMinutes,
                        wakeupWindow.endMinutes
                    )
                }
            }
        }

        // Periodic maintenance (database cleanup)
        scope.launch {
            try {
                val prefs = UserPreferencesRepository.getInstance(this@SleepTrackingService)
                val retentionDays = prefs.dataRetentionDays.first()
                val timestamp = System.currentTimeMillis()
                val db = codegito.xyz.healthconnector.data.db.SleepEventDatabase.getDatabase(this@SleepTrackingService)
                db.screenEventDao().deleteOldEvents(timestamp - retentionDays * 24 * 60 * 60 * 1000L)
                Log.d("SleepTrackingService", "Cleaned up events older than $retentionDays days")
            } catch (e: Exception) {
                Log.e("SleepTrackingService", "Cleanup failed", e)
            }
        }
    }

    private fun startForegroundServiceCompat() {
        if (Build.VERSION.SDK_INT >= 34) { // Android 14+
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle explicit stop intent if needed
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY // Restart if killed by system
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsJob?.cancel()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Might not be registered if crashed during onCreate
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // This is not a bound service
    }

    private fun createNotification(): Notification {
        val channelId = "sleep_tracking_channel"
        val channelName = "Sleep Tracking"

        // Create notification channel (required for Android 8.0+)
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Active while monitoring sleep patterns"
        }
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, 
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sleep Tracking Active")
            .setContentText("Tap to open Sleep Tracker and review logs")
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
