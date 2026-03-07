package codegito.xyz.healthconnector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.AlarmManager
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import android.util.Log
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import kotlinx.coroutines.flow.first

object NotificationHelper {

    const val REMINDER_CHANNEL_ID = "sleep_reminder_channel"
    const val DEADLINE_CHANNEL_ID = "sleep_deadline_channel"
    const val SILENT_CHANNEL_ID = "sleep_silent_channel"
    
    const val REMINDER_NOTIFICATION_ID = 1001

    fun createNotificationChannels(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        // General Reminder Channel
        val reminderChannel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            "Sleep Logging Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Reminders to log your sleep after waking up"
        }

        // Deadline Channel (High importance)
        val deadlineChannel = NotificationChannel(
            DEADLINE_CHANNEL_ID,
            "Logging Deadline",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Last-minute reminders before the logging window ends"
        }

        // Silent Channel (Low importance)
        val silentChannel = NotificationChannel(
            SILENT_CHANNEL_ID,
            "Silent Reminders",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Silent reminders when you might still be asleep"
        }

        notificationManager.createNotificationChannels(listOf(reminderChannel, deadlineChannel, silentChannel))
    }

    fun scheduleFirstUnlockReminder(context: Context, targetDate: LocalDate, delayMinutes: Int = 5) {
        val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("reminder_type", "FIRST_UNLOCK")
            putExtra("target_date_millis", targetDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = Instant.now().plusSeconds(delayMinutes * 60L).toEpochMilli()
        
        scheduleWakeupAlarm(alarmManager, triggerTime, pendingIntent)
    }

    fun scheduleDeadlineAlarm(context: Context, wakeupEndMinutes: Int) {
        val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("reminder_type", "DEADLINE")
            // Target date is yesterday (the night we are logging for)
            val targetDate = LocalDate.now().minusDays(1)
            putExtra("target_date_millis", targetDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            200,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val now = java.time.LocalDateTime.now()
        var deadline = LocalDate.now().atTime(wakeupEndMinutes / 60, wakeupEndMinutes % 60)
        
        if (now.isAfter(deadline)) {
            // If already passed today, schedule for tomorrow (unlikely to be useful for *last* night, 
            // but keeps the cycle going)
            deadline = deadline.plusDays(1)
        }

        scheduleWakeupAlarm(
            alarmManager,
            deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            pendingIntent
        )
    }

    fun scheduleServiceLifecycle(context: Context, startTimeMinutes: Int, endTimeMinutes: Int) {
        val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
        
        // Schedule Start
        val startIntent = Intent(context, ServiceSchedulerReceiver::class.java).apply { action = "START_SERVICE" }
        val startPending = PendingIntent.getBroadcast(context, 300, startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        val startTrigger = getNextOccurrence(startTimeMinutes)
        scheduleWakeupAlarm(alarmManager, startTrigger, startPending)

        // Schedule Stop
        val stopIntent = Intent(context, ServiceSchedulerReceiver::class.java).apply { action = "STOP_SERVICE" }
        val stopPending = PendingIntent.getBroadcast(context, 400, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        val stopTrigger = getNextOccurrence(endTimeMinutes)
        scheduleWakeupAlarm(alarmManager, stopTrigger, stopPending)
    }

    suspend fun refreshServiceState(context: Context, prefs: UserPreferencesRepository) {
        if (!prefs.isAutoSleepDetectionActive()) {
            Log.d("NotificationHelper", "Auto mode inactive, stopping service")
            val stopIntent = Intent(context, SleepTrackingService::class.java)
            context.stopService(stopIntent)
            return
        }

        val bedtimeStart = prefs.bedtimeWindowStart.first()
        val wakeupEnd = prefs.wakeupWindowEnd.first()

        // Always ensure alarms are scheduled
        scheduleServiceLifecycle(context, bedtimeStart, wakeupEnd)

        // Then check if we should be running right now
        val now = LocalTime.now()
        val nowMins = now.hour * 60 + now.minute

        val isCurrentlyActive = if (bedtimeStart <= wakeupEnd) {
            nowMins in bedtimeStart..wakeupEnd
        } else {
            nowMins >= bedtimeStart || nowMins <= wakeupEnd
        }

        val serviceRunning = isServiceRunning(context, SleepTrackingService::class.java)

        if (isCurrentlyActive) {
            if (!serviceRunning) {
                Log.d("NotificationHelper", "Inside window, starting service")
                val serviceIntent = Intent(context, SleepTrackingService::class.java)
                context.startForegroundService(serviceIntent)
            }
        } else {
            if (serviceRunning) {
                Log.d("NotificationHelper", "Outside window, stopping service")
                val stopIntent = Intent(context, SleepTrackingService::class.java)
                context.stopService(stopIntent)
            }
        }
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    private fun getNextOccurrence(minutesOfDay: Int): Long {
        val now = java.time.LocalDateTime.now()
        var target = LocalDate.now().atTime(minutesOfDay / 60, minutesOfDay % 60)
        if (now.isAfter(target)) {
            target = target.plusDays(1)
        }
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun scheduleWakeupAlarm(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent
    ) {
        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

        if (canUseExact) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                return
            } catch (e: SecurityException) {
                Log.w("NotificationHelper", "Exact alarm denied, falling back to inexact alarm", e)
            }
        }

        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    fun sendLogReminder(
        context: Context, 
        targetDate: LocalDate, 
        channelId: String, 
        isSilent: Boolean = false,
        title: String = "Log your sleep",
        text: String = "Don't forget to review and save your sleep for last night."
    ) {
        val intent = Intent(context, SleepDataLogger::class.java).apply {
            putExtra("target_date_millis", targetDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 
            targetDate.hashCode(), 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(if (isSilent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(REMINDER_NOTIFICATION_ID, notification)
    }
}
