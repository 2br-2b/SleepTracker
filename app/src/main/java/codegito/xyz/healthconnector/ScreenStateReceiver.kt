package codegito.xyz.healthconnector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import codegito.xyz.healthconnector.data.db.ScreenEvent
import codegito.xyz.healthconnector.data.db.SleepEventDatabase
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.SleepDetectionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime

class ScreenStateReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val type = when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> "LOCK"
            Intent.ACTION_SCREEN_ON -> "UNLOCK"
            Intent.ACTION_USER_PRESENT -> "PRESENT"
            else -> return
        }
        val timestamp = System.currentTimeMillis()
        Log.d("ScreenStateReceiver", "Event: $type at $timestamp")

        val pendingResult = goAsync()
        scope.launch {
            try {
                processEvent(context, type, timestamp)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processEvent(context: Context, type: String, timestamp: Long) {
        val prefs = UserPreferencesRepository.getInstance(context)
        val mode = prefs.sleepDetectionMode.first()
        if (mode != SleepDetectionMode.AUTO) return

        val now = LocalTime.now()
        val nowMins = now.hour * 60 + now.minute

        val bedtimeWindow = prefs.bedtimeWindow.first()
        val wakeupWindow = prefs.wakeupWindow.first()
        val bedtimeStart = bedtimeWindow.startMinutes
        val bedtimeEnd = bedtimeWindow.endMinutes
        val wakeupStart = wakeupWindow.startMinutes
        val wakeupEnd = wakeupWindow.endMinutes

        val inBedtimeWindow = isTimeInWindow(nowMins, bedtimeStart, bedtimeEnd)
        val inWakeupWindow = isTimeInWindow(nowMins, wakeupStart, wakeupEnd)

        if (inBedtimeWindow || inWakeupWindow) {
            val db = SleepEventDatabase.getDatabase(context)
            db.screenEventDao().insert(ScreenEvent(timestampMillis = timestamp, type = type))
            Log.d("ScreenStateReceiver", "Inserted event $type into DB (Window active)")
            
            if (inWakeupWindow && (type == "UNLOCK" || type == "PRESENT")) {
                handleFirstUnlockInWakeup(context, prefs)
            }
        }
    }

    private suspend fun handleFirstUnlockInWakeup(context: Context, prefs: UserPreferencesRepository) {
        val sharedPrefs = context.getSharedPreferences("reminder_state", Context.MODE_PRIVATE)
        val todayStr = java.time.LocalDate.now().toString()
        val alreadyTracked = sharedPrefs.getString("last_unlock_tracked_date", "") == todayStr
        
        if (!alreadyTracked) {
            sharedPrefs.edit()
                .putString("last_unlock_tracked_date", todayStr)
                .putBoolean("unlocked_in_window", true)
                .apply()
            
            // Schedule reminder after the awakening threshold so brief wake-ups don't fire it prematurely
            val thresholdMinutes = prefs.awakeningThresholdMinutes.first()
            NotificationHelper.scheduleFirstUnlockReminder(context, java.time.LocalDate.now().minusDays(1), thresholdMinutes)
        }
    }

    private fun isTimeInWindow(time: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            time in start..end
        } else {
            // Over midnight
            time >= start || time <= end
        }
    }
}
