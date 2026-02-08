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
                // Cleanup old events (older than 7 days) once in a while
                if (Math.random() < 0.1) { // 10% chance to cleanup on each event
                    val db = SleepEventDatabase.getDatabase(context)
                    db.screenEventDao().deleteOldEvents(timestamp - 7 * 24 * 60 * 60 * 1000)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processEvent(context: Context, type: String, timestamp: Long) {
        val prefs = UserPreferencesRepository(context)
        val mode = prefs.sleepDetectionMode.first()
        if (mode != SleepDetectionMode.AUTO) return

        val now = LocalTime.now()
        val nowMins = now.hour * 60 + now.minute

        val bedtimeStart = prefs.bedtimeWindowStart.first()
        val bedtimeEnd = prefs.bedtimeWindowEnd.first()
        val wakeupStart = prefs.wakeupWindowStart.first()
        val wakeupEnd = prefs.wakeupWindowEnd.first()

        val inBedtimeWindow = isTimeInWindow(nowMins, bedtimeStart, bedtimeEnd)
        val inWakeupWindow = isTimeInWindow(nowMins, wakeupStart, wakeupEnd)

        if (inBedtimeWindow || inWakeupWindow) {
            val db = SleepEventDatabase.getDatabase(context)
            db.screenEventDao().insert(ScreenEvent(timestampMillis = timestamp, type = type))
            Log.d("ScreenStateReceiver", "Inserted event $type into DB (Window active)")
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
