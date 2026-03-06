package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSleepSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val dataRetentionDays by userPreferencesRepository.dataRetentionDays.collectAsState(initial = 7)
    val historyDisplayDays by userPreferencesRepository.historyDisplayDays.collectAsState(initial = 7)
    val awakeningThreshold by userPreferencesRepository.awakeningThresholdMinutes.collectAsState(initial = 60)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SectionHeader("History")
            Text(
                "Number of days shown on the home screen.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = historyDisplayDays.toString(),
                onValueChange = { raw ->
                    val value = raw.toIntOrNull()
                    if (value != null && value > 0) {
                        scope.launch { userPreferencesRepository.setHistoryDisplayDays(value) }
                    }
                },
                label = { Text("History display days") },
                modifier = Modifier.fillMaxWidth()
            )

            SectionHeader("Awakenings")
            Text(
                "If you unlock, lock, and unlock again in your wakeup window, this threshold helps decide whether you stayed awake or went back to sleep.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = awakeningThreshold.toString(),
                onValueChange = { raw ->
                    val value = raw.toIntOrNull()
                    if (value != null && value >= 0) {
                        scope.launch { userPreferencesRepository.setAwakeningThreshold(value) }
                    }
                },
                label = { Text("Awakening threshold (minutes)") },
                modifier = Modifier.fillMaxWidth()
            )

            SectionHeader("Data Retention")
            Text(
                "How many days of raw screen events to keep. Events older than this are deleted to save space. Increasing this lets you re-detect sleep from further back.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = dataRetentionDays.toString(),
                onValueChange = { raw ->
                    val value = raw.toIntOrNull()
                    if (value != null && value > 0) {
                        scope.launch { userPreferencesRepository.setDataRetentionDays(value) }
                    }
                },
                label = { Text("Data retention (days)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
