package codegito.xyz.healthconnector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            
            scope.launch {
                try {
                    val prefs = codegito.xyz.healthconnector.data.UserPreferencesRepository(context)
                    if (prefs.isAutoSleepDetectionActive()) {
                        Log.d("BootReceiver", "Boot completed, starting SleepTrackingService")
                        val serviceIntent = Intent(context, SleepTrackingService::class.java)
                        context.startForegroundService(serviceIntent)
                    } else {
                        Log.d("BootReceiver", "Boot completed, but Auto mode is OFF")
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
