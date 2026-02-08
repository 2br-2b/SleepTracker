package codegito.xyz.healthconnector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    fun scheduleFirstUnlockReminder(context: Context, targetDate: LocalDate) {
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

        // 5 minutes from now
        val triggerTime = Instant.now().plusSeconds(5 * 60).toEpochMilli()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                pendingIntent
            )
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP, 
                deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 
                pendingIntent
            )
        }
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
