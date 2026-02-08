package codegito.xyz.healthconnector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            
            scope.launch {
                try {
                    val prefs = codegito.xyz.healthconnector.data.UserPreferencesRepository.getInstance(context)
                    NotificationHelper.refreshServiceState(context, prefs)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
