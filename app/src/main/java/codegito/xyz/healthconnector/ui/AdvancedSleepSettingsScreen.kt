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
    val awakeningThreshold by userPreferencesRepository.awakeningThresholdMinutes.collectAsState(initial = 10)

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
                "Controls how brief wake-ups during sleep are grouped. Two wake-ups separated by a gap of this duration or less are merged into one continuous awake period. If the gap exceeds the threshold they are recorded as separate events. The same threshold is used in your wakeup window to distinguish a real morning wakeup from a brief check of the phone.",
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
