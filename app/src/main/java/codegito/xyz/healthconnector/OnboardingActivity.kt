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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.lifecycle.lifecycleScope
import codegito.xyz.healthconnector.ui.theme.SleepTrackerTheme
import kotlinx.coroutines.launch

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
                        val PERMISSIONS =
                            setOf(
                                HealthPermission.getReadPermission(SleepSessionRecord::class),
                                HealthPermission.getWritePermission(SleepSessionRecord::class)
                            )

                        // Create the permissions launcher
                        val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()

                        val requestPermissions = registerForActivityResult(requestPermissionActivityContract) { granted ->
                            if (granted.containsAll(PERMISSIONS)) {
                                Toast.makeText(this, "Permissions granted! Yay!", Toast.LENGTH_SHORT).show()
                                // Permissions successfully granted
                            } else {
                                Toast.makeText(this, "de-NIED!!!!! GET REKT LOL", Toast.LENGTH_SHORT).show()
                                // Lack of required permissions
                            }
                        }

//                        suspend fun checkPermissionsAndRun(healthConnectClient: HealthConnectClient) {
//                            val granted = healthConnectClient.permissionController.getGrantedPermissions()
//                            if (granted.containsAll(PERMISSIONS)) {
//                                // Permissions already granted; proceed with inserting or reading data
//                            } else {
//                                requestPermissions.launch(PERMISSIONS)
//                            }
//                        }
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
                text = "To track and visualize your sleep data, this app needs access to Health Connect. Please grant the necessary read and write permissions.",
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
