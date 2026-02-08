package codegito.xyz.healthconnector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ServiceSchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "START_SERVICE" -> {
                Log.d("ServiceScheduler", "Received START_SERVICE alarm")
                val serviceIntent = Intent(context, SleepTrackingService::class.java)
                context.startForegroundService(serviceIntent)
            }
            "STOP_SERVICE" -> {
                Log.d("ServiceScheduler", "Received STOP_SERVICE alarm")
                val serviceIntent = Intent(context, SleepTrackingService::class.java).apply {
                    action = "STOP_SERVICE"
                }
                context.startService(serviceIntent)
            }
        }
    }
}
