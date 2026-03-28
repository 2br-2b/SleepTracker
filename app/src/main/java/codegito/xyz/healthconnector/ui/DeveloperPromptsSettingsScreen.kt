package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperPromptsSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val aiSystemPrompt by userPreferencesRepository.aiSystemPrompt.collectAsState("")
    val aiBaseSystemPrompt by userPreferencesRepository.aiBaseSystemPrompt.collectAsState(
        UserPreferencesRepository.DEFAULT_AI_BASE_SYSTEM_PROMPT
    )

    var draftSystemPrompt by remember { mutableStateOf("") }
    var draftBaseSystemPrompt by remember { mutableStateOf("") }

    LaunchedEffect(aiSystemPrompt, aiBaseSystemPrompt) {
        draftSystemPrompt = aiSystemPrompt
        draftBaseSystemPrompt = aiBaseSystemPrompt
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Developer AI Prompts") }) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    scope.launch { userPreferencesRepository.resetAiPromptsToDefault() }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset prompts")
            }

            OutlinedTextField(
                value = draftBaseSystemPrompt,
                onValueChange = { draftBaseSystemPrompt = it },
                label = { Text("Base system prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5
            )

            OutlinedTextField(
                value = draftSystemPrompt,
                onValueChange = { draftSystemPrompt = it },
                label = { Text("User system prompt add-on") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )

            Button(
                onClick = {
                    scope.launch {
                        userPreferencesRepository.setAiBaseSystemPrompt(draftBaseSystemPrompt)
                        userPreferencesRepository.setAiSystemPrompt(draftSystemPrompt)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save prompts")
            }

            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}
