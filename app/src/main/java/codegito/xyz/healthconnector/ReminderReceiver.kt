package codegito.xyz.healthconnector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReminderReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("reminder_type") ?: return
        val targetDateMillis = intent.getLongExtra("target_date_millis", -1L)
        if (targetDateMillis == -1L) return
        
        val targetDate = Instant.ofEpochMilli(targetDateMillis).atZone(ZoneId.systemDefault()).toLocalDate()

        scope.launch {
            val prefs = UserPreferencesRepository.getInstance(context)
            val healthManager = HealthConnectManager(context)
            val rolloverHour = prefs.rolloverHour.first()
            
            val startBound = targetDate.atTime(rolloverHour, 0).atZone(ZoneId.systemDefault()).toInstant()
            val endBound = targetDate.plusDays(1).atTime(rolloverHour, 0).atZone(ZoneId.systemDefault()).toInstant()
            
            // Check if data is already logged
            val recordsResult = healthManager.getSleepSessions(startBound, endBound)
            if (recordsResult.isSuccess && recordsResult.getOrThrow().isNotEmpty()) {
                return@launch // Already logged
            }

            when (type) {
                "FIRST_UNLOCK" -> {
                    if (prefs.reminderFirstUnlockEnabled.first()) {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (pm.isInteractive) {
                            NotificationHelper.sendLogReminder(context, targetDate, NotificationHelper.REMINDER_CHANNEL_ID)
                        }
                    }
                }
                "DEADLINE" -> {
                    val sharedPrefs = context.getSharedPreferences("reminder_state", Context.MODE_PRIVATE)
                    val todayStr = java.time.LocalDate.now().toString()
                    val phoneUnlocked = sharedPrefs.getString("last_unlock_tracked_date", "") == todayStr && 
                                       sharedPrefs.getBoolean("unlocked_in_window", false)
                    
                    if (phoneUnlocked) {
                        if (prefs.reminderDeadlineLoudEnabled.first()) {
                            NotificationHelper.sendLogReminder(
                                context, 
                                targetDate, 
                                NotificationHelper.DEADLINE_CHANNEL_ID,
                                title = "Last call to log sleep",
                                text = "Your morning window is ending. Review and save your sleep now!"
                            )
                        }
                    } else {
                        if (prefs.reminderDeadlineSilentEnabled.first()) {
                            NotificationHelper.sendLogReminder(
                                context, 
                                targetDate, 
                                NotificationHelper.SILENT_CHANNEL_ID, 
                                isSilent = true,
                                title = "Log your sleep",
                                text = "Don't forget to review your sleep when you wake up."
                            )
                        }
                    }

                    // Reschedule for next day
                    val wakeupEnd = prefs.wakeupWindowEnd.first()
                    NotificationHelper.scheduleDeadlineAlarm(context, wakeupEnd)
                }
            }
        }
    }
}
