package codegito.xyz.healthconnector

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import codegito.xyz.healthconnector.ui.theme.SleepTrackerTheme

class OnboardingActivity : ComponentActivity() {

    private val healthConnectManager by lazy { HealthConnectManager(this) }

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(healthConnectManager.permissions)) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SleepTrackerTheme {
                OnboardingScreen(
                    onGrantClick = {
                        // 1. Health Connect
                        requestPermissions.launch(healthConnectManager.permissions)
                        
                        // 2. Notifications (Android 13+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
                        }

                        // 3. Exact Alarms (Android 12+)
                        val alarmManager = getSystemService(android.app.AlarmManager::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (!alarmManager.canScheduleExactAlarms()) {
                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                startActivity(intent)
                            }
                        }
                    },
                    onCancelClick = {
                        finish()
                    }
                )
                Text(
                    text = "Look, this is offline, I don't touch your data"
                )
            }
        }
    }
}

@Composable
fun OnboardingScreen(
    onGrantClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Connect to Health Connect",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "To track and visualize your sleep data, this app needs access to Health Connect, Notifications, and the ability to schedule alarms for reminders.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onGrantClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant Access")
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = onCancelClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Not Now")
            }
        }
    }
}
