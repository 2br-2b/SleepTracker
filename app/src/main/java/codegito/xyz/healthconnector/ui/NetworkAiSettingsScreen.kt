package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.data.model.AiProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkAiSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val networkEnabled by userPreferencesRepository.globalNetworkEnabled.collectAsState(true)
    val effectiveAiEnabled by userPreferencesRepository.effectiveGlobalAiEnabled.collectAsState(true)
    val aiProvider by userPreferencesRepository.aiProvider.collectAsState(AiProvider.OPENAI_COMPAT)
    val aiModel by userPreferencesRepository.aiModel.collectAsState(AiProvider.OPENAI_COMPAT.defaultModel)
    val aiApiKey by userPreferencesRepository.aiApiKey.collectAsState("")
    val aiBaseUrl by userPreferencesRepository.aiBaseUrl.collectAsState(AiProvider.OPENAI_COMPAT.defaultBaseUrl.orEmpty())
    val aiTemperature by userPreferencesRepository.aiTemperature.collectAsState(0.2f)
    val aiMaxTokens by userPreferencesRepository.aiMaxTokens.collectAsState(1024)
    val aiMemoryNotes by userPreferencesRepository.aiMemoryNotes.collectAsState("")
    val aiFollowupDefaultCount by userPreferencesRepository.aiFollowupDefaultCount.collectAsState(1)
    val aiMaxIterations by userPreferencesRepository.aiMaxIterations.collectAsState(50)
    val aiFeaturesDisabled by userPreferencesRepository.aiFeaturesDisabled.collectAsState(false)
    val aiReasoningEffort by userPreferencesRepository.aiReasoningEffort.collectAsState("none")
    val developerModeEnabled by userPreferencesRepository.developerModeEnabled.collectAsState(false)

    var draftProvider by remember { mutableStateOf(AiProvider.OPENAI_COMPAT) }
    var draftModel by remember { mutableStateOf(AiProvider.OPENAI_COMPAT.defaultModel) }
    var draftApiKey by remember { mutableStateOf("") }
    var draftBaseUrl by remember { mutableStateOf(AiProvider.OPENAI_COMPAT.defaultBaseUrl.orEmpty()) }
    var draftTemperature by remember { mutableStateOf(0.2f) }
    var draftMaxTokensText by remember { mutableStateOf("1024") }
    var draftMemoryNotes by remember { mutableStateOf("") }
    var draftFollowupCountText by remember { mutableStateOf("1") }
    var draftMaxIterationsText by remember { mutableStateOf("50") }
    var draftReasoningEffort by remember { mutableStateOf("none") }
    var testConsoleInput by remember { mutableStateOf("") }
    var testConsoleOutput by remember { mutableStateOf("Not tested yet") }

    LaunchedEffect(aiProvider, aiModel, aiApiKey, aiBaseUrl, aiTemperature, aiMaxTokens, aiMemoryNotes, aiFollowupDefaultCount, aiMaxIterations, aiReasoningEffort) {
        draftProvider = aiProvider
        draftModel = aiModel
        draftApiKey = aiApiKey
        draftBaseUrl = aiBaseUrl
        draftTemperature = aiTemperature
        draftMaxTokensText = aiMaxTokens.toString()
        draftMemoryNotes = aiMemoryNotes
        draftFollowupCountText = aiFollowupDefaultCount.toString()
        draftMaxIterationsText = aiMaxIterations.toString()
        draftReasoningEffort = aiReasoningEffort
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Network & AI Settings") }) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (aiFeaturesDisabled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("AI features are currently disabled", style = MaterialTheme.typography.titleMedium)
                        Text("Re-enable AI from Settings → General → Disable all AI features.")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingToggleRow(
                        label = "Enable network features",
                        description = "Master switch for all features that require network access.",
                        checked = networkEnabled,
                        enabled = true,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                userPreferencesRepository.setGlobalNetworkEnabled(enabled)
                                if (!enabled) userPreferencesRepository.setGlobalAiEnabled(false)
                            }
                        }
                    )
                    HorizontalDivider()
                    SettingToggleRow(
                        label = "Enable AI features",
                        description = "AI is automatically disabled when networking is off.",
                        checked = effectiveAiEnabled,
                        enabled = networkEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { userPreferencesRepository.setGlobalAiEnabled(enabled) }
                        }
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Koog provider configuration", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Configure providers used by future AI features. This screen stores credentials and routing settings only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text("Provider", style = MaterialTheme.typography.labelLarge)
                    AiProvider.entries.forEach { provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = draftProvider == provider,
                                    enabled = networkEnabled,
                                    onClick = {
                                        draftProvider = provider
                                        if (draftModel.isBlank() || draftModel == aiProvider.defaultModel) {
                                            draftModel = provider.defaultModel
                                        }
                                        if (provider.requiresBaseUrl) {
                                            draftBaseUrl = provider.defaultBaseUrl.orEmpty()
                                        }
                                    }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = draftProvider == provider,
                                onClick = {
                                    draftProvider = provider
                                    if (draftModel.isBlank() || draftModel == aiProvider.defaultModel) {
                                        draftModel = provider.defaultModel
                                    }
                                    if (provider.requiresBaseUrl) {
                                        draftBaseUrl = provider.defaultBaseUrl.orEmpty()
                                    }
                                },
                                enabled = networkEnabled
                            )
                            Text(provider.displayName)
                        }
                    }

                    Text(
                        draftProvider.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = draftModel,
                        onValueChange = { draftModel = it },
                        enabled = networkEnabled,
                        label = { Text("Model") },
                        placeholder = { Text(draftProvider.defaultModel) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (draftProvider.requiresApiKey) {
                        OutlinedTextField(
                            value = draftApiKey,
                            onValueChange = { draftApiKey = it },
                            enabled = networkEnabled,
                            label = { Text("API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (draftProvider.requiresBaseUrl) {
                        OutlinedTextField(
                            value = draftBaseUrl,
                            onValueChange = { draftBaseUrl = it },
                            enabled = networkEnabled,
                            label = { Text("Base URL") },
                            placeholder = { Text(draftProvider.defaultBaseUrl.orEmpty()) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Text("Temperature: ${"%.2f".format(draftTemperature)}")
                    Slider(
                        value = draftTemperature,
                        onValueChange = { draftTemperature = it },
                        valueRange = 0f..2f,
                        enabled = networkEnabled
                    )

                    Text("Thinking / reasoning effort", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Instructs the model to think before responding. Useful for reasoning models (e.g. o3, o4-mini, DeepSeek-R1). Has no effect on models that don't support it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf("none", "low", "medium", "high").forEach { level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = draftReasoningEffort == level,
                                    enabled = networkEnabled,
                                    onClick = { draftReasoningEffort = level }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = draftReasoningEffort == level,
                                onClick = { draftReasoningEffort = level },
                                enabled = networkEnabled
                            )
                            Text(level.replaceFirstChar { it.uppercase() })
                        }
                    }

                    OutlinedTextField(
                        value = draftMaxTokensText,
                        onValueChange = { input -> draftMaxTokensText = input.filter { it.isDigit() } },
                        enabled = networkEnabled,
                        label = { Text("Max output tokens") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = draftFollowupCountText,
                        onValueChange = { input -> draftFollowupCountText = input.filter { it.isDigit() } },
                        enabled = networkEnabled,
                        label = { Text("Default follow-up prompts") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = draftMaxIterationsText,
                        onValueChange = { input -> draftMaxIterationsText = input.filter { it.isDigit() } },
                        enabled = networkEnabled,
                        label = { Text("AI agent max iterations (5-200, default 50)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = draftMemoryNotes,
                        onValueChange = { draftMemoryNotes = it },
                        enabled = networkEnabled,
                        label = { Text("Persistent memory notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )

                    if (developerModeEnabled) {
                        OutlinedTextField(
                            value = testConsoleInput,
                            onValueChange = { testConsoleInput = it },
                            enabled = networkEnabled,
                            label = { Text("API test console input") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    if (testConsoleInput.isBlank()) {
                                        testConsoleOutput = "Enter a test message first."
                                    } else {
                                        testConsoleOutput = "Testing API connection…"
                                        testConsoleOutput = runAiConsoleTest(
                                            provider = draftProvider,
                                            baseUrl = draftBaseUrl.trim(),
                                            apiKey = draftApiKey.trim(),
                                            model = draftModel.trim(),
                                            message = testConsoleInput.trim()
                                        )
                                    }
                                }
                            },
                            enabled = networkEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Run config test") }
                        Text(testConsoleOutput, style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                userPreferencesRepository.setAiProvider(draftProvider)
                                userPreferencesRepository.setAiModel(draftModel.trim())
                                userPreferencesRepository.setAiApiKey(draftApiKey.trim())
                                userPreferencesRepository.setAiBaseUrl(draftBaseUrl.trim())
                                userPreferencesRepository.setAiTemperature((draftTemperature * 100).roundToInt() / 100f)
                                userPreferencesRepository.setAiMaxTokens(draftMaxTokensText.toIntOrNull() ?: 1024)
                                userPreferencesRepository.setAiMemoryNotes(draftMemoryNotes)
                                userPreferencesRepository.setAiFollowupDefaultCount(draftFollowupCountText.toIntOrNull() ?: 1)
                                userPreferencesRepository.setAiMaxIterations(draftMaxIterationsText.toIntOrNull() ?: 50)
                                userPreferencesRepository.setAiReasoningEffort(draftReasoningEffort)
                            }
                        },
                        enabled = networkEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save AI configuration")
                    }
                }
            }

            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}


private suspend fun runAiConsoleTest(
    provider: AiProvider,
    baseUrl: String,
    apiKey: String,
    model: String,
    message: String
): String = withContext(Dispatchers.IO) {
    if (provider == AiProvider.ANTHROPIC || provider == AiProvider.GEMINI) {
        return@withContext "Test console currently supports OpenAI-compatible endpoints (OpenAI-compatible, Ollama, OpenRouter, Groq)."
    }
    if (baseUrl.isBlank()) return@withContext "Missing base URL."
    if (provider.requiresApiKey && apiKey.isBlank()) return@withContext "Missing API key."
    return@withContext runCatching {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val modelName = model.ifBlank { provider.defaultModel }
        val payload = org.json.JSONObject()
            .put("model", modelName)
            .put("messages", org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", message)))
            .put("temperature", 0.2)
            .toString()
        conn.outputStream.use { it.write(payload.toByteArray()) }
        val code = conn.responseCode
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }
        if (code !in 200..299) {
            "Request failed ($code): ${body.take(400)}"
        } else {
            val msgRegex = Regex("\\\"content\\\"\\s*:\\s*\\\"(.*?)\\\"")
            val content = msgRegex.find(body)?.groupValues?.getOrNull(1)
            if (content.isNullOrBlank()) "Success ($code), but could not parse message: ${body.take(220)}"
            else "Success ($code): ${content.replace("\\n", " ").take(220)}"
        }
    }.getOrElse { "Connection error: ${it.message}" }
}

@Composable
private fun SettingToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
