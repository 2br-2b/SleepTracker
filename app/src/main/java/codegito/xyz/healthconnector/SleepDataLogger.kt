package codegito.xyz.healthconnector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.ui.theme.SleepTrackerTheme

class SleepDataLogger : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Retrieve the date passed from the notification's intent
        val sleepDate = intent.getStringExtra("SLEEP_DATE_KEY") ?: "today"

        setContent {
            SleepTrackerTheme {
                SleepDataScreen(
                    sleepDate = sleepDate,
                    onConfirm = {
                        // TODO: Handle confirm action (e.g., save to Health Connect)
                        finish() // Close the activity after confirmation
                    },
                    onCancel = {
                        finish() // Close the activity
                    }
                )
            }
        }
    }

    @Composable
    fun SleepDataScreen(
        sleepDate: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Confirm Sleep for $sleepDate",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(32.dp))
                // TODO: Add UI to show/edit sleep start and end times
                Text("Sleep Start: 23:15")
                Text("Sleep End: 07:30")
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(onClick = onCancel) {
                        Text("Cancel")
                    }
                    Button(onClick = onConfirm) {
                        Text("Confirm")
                    }
                }
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun SleepConfirmationScreenPreview() {
        SleepTrackerTheme {
            SleepDataScreen(sleepDate = "Yesterday", onConfirm = {}, onCancel = {})
        }
    }
}
