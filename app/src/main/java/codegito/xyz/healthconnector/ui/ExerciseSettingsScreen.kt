package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.HealthConnectManager
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.nutrition.domain.NutritionUnitSystem
import codegito.xyz.healthconnector.nutrition.domain.kgToWeightText
import codegito.xyz.healthconnector.nutrition.domain.parseWeightToKg
import codegito.xyz.healthconnector.nutrition.domain.weightUnitLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    healthConnectManager: HealthConnectManager,
    onBack: () -> Unit,
    onPermissions: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val exerciseEnabled by userPreferencesRepository.exerciseEnabled.collectAsState(initial = false)
    val weightLoggingEnabled by userPreferencesRepository.weightLoggingEnabled.collectAsState(initial = false)
    val weightSourceMode by userPreferencesRepository.weightSourceMode.collectAsState(initial = "HEALTH_CONNECT")
    val staticWeightKg by userPreferencesRepository.staticWeightKg.collectAsState(initial = 70f)
    val unitSystem by userPreferencesRepository.globalUnitSystem.collectAsState(initial = NutritionUnitSystem.US)

    var staticWeightText by remember(staticWeightKg, unitSystem) {
        mutableStateOf(kgToWeightText(staticWeightKg.toDouble(), unitSystem))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exercise Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Exercise tracking ──────────────────────────────────────────
            SectionHeader("Exercise Tracking")

            ListItem(
                headlineContent = { Text("Enable exercise tracking") },
                supportingContent = { Text("Log workouts and view exercise history.") },
                trailingContent = {
                    Switch(
                        checked = exerciseEnabled,
                        onCheckedChange = { scope.launch { userPreferencesRepository.setExerciseEnabled(it) } }
                    )
                }
            )

            HorizontalDivider()

            // ── Weight ─────────────────────────────────────────────────────
            SectionHeader("Weight")

            Text(
                "Weight is used to estimate calorie burn. Even if logging is off, the app can still read your weight from Health Connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ListItem(
                headlineContent = { Text("Log weight to Health Connect") },
                supportingContent = { Text("Saves each weigh-in to Health Connect.") },
                trailingContent = {
                    Switch(
                        checked = weightLoggingEnabled,
                        onCheckedChange = { scope.launch { userPreferencesRepository.setWeightLoggingEnabled(it) } }
                    )
                }
            )

            // When logging is off, show the weight source switch
            if (!weightLoggingEnabled) {
                ListItem(
                    headlineContent = { Text("Weight source") },
                    supportingContent = {
                        Text(
                            if (weightSourceMode == "HEALTH_CONNECT")
                                "Read most recent weight from Health Connect"
                            else
                                "Use a fixed static value"
                        )
                    },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = weightSourceMode == "HEALTH_CONNECT",
                                onClick = { scope.launch { userPreferencesRepository.setWeightSourceMode("HEALTH_CONNECT") } },
                                label = { Text("Health Connect") }
                            )
                            FilterChip(
                                selected = weightSourceMode == "STATIC",
                                onClick = { scope.launch { userPreferencesRepository.setWeightSourceMode("STATIC") } },
                                label = { Text("Static") }
                            )
                        }
                    }
                )

                if (weightSourceMode == "STATIC") {
                    OutlinedTextField(
                        value = staticWeightText,
                        onValueChange = { staticWeightText = it },
                        label = { Text("My weight (${weightUnitLabel(unitSystem)})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        supportingText = {
                            TextButton(onClick = {
                                val kg = parseWeightToKg(staticWeightText, unitSystem)
                                if (kg != null) {
                                    scope.launch { userPreferencesRepository.setStaticWeightKg(kg.toFloat()) }
                                }
                            }) { Text("Save") }
                        }
                    )
                }
            }

            HorizontalDivider()

            // ── Permissions ────────────────────────────────────────────────
            SectionHeader("Permissions")

            ListItem(
                headlineContent = { Text("Health Connect permissions") },
                supportingContent = { Text("Manage exercise and weight permissions.") },
                trailingContent = {
                    TextButton(onClick = onPermissions) { Text("Manage") }
                }
            )
        }
    }
}
