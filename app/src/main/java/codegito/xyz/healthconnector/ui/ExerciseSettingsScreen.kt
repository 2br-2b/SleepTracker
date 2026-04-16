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
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.exercise.domain.Sex
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit,
    onPermissions: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val exerciseEnabled by userPreferencesRepository.exerciseEnabled.collectAsState(initial = false)
    val exerciseAge by userPreferencesRepository.exerciseAge.collectAsState(initial = null)
    val exerciseSex by userPreferencesRepository.exerciseSex.collectAsState(initial = null)
    val developerMode by userPreferencesRepository.developerModeEnabled.collectAsState(initial = false)
    val epocMultiplier by userPreferencesRepository.exerciseEpocMultiplier.collectAsState(initial = 1.07)

    var ageInput by remember(exerciseAge) { mutableStateOf(exerciseAge?.toString() ?: "") }
    var epocInput by remember(epocMultiplier) { mutableStateOf("%.2f".format(epocMultiplier)) }

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
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Feature toggle ────────────────────────────────────────────
            SettingsSwitchRow(
                title = "Exercise tracking",
                subtitle = "Show an exercise tab and log workouts to Health Connect",
                checked = exerciseEnabled,
                onCheckedChange = { scope.launch { userPreferencesRepository.setExerciseEnabled(it) } }
            )

            HorizontalDivider()

            // ── Calorie estimation ────────────────────────────────────────
            SectionHeader("Calorie Estimation")

            Text(
                "Age and sex improve calorie accuracy by enabling the Keytel HR formula (Tier 3/4). " +
                "Without them the app falls back to a simpler MET-based estimate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = ageInput,
                onValueChange = { ageInput = it },
                label = { Text("Age (years) — optional") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        scope.launch { userPreferencesRepository.setExerciseAge(ageInput.toIntOrNull()) }
                    }) { Text("Save") }
                }
            )

            ListItem(
                headlineContent = { Text("Sex — optional") },
                supportingContent = { Text("Used in HR-based calorie formula") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = exerciseSex == null,
                            onClick = { scope.launch { userPreferencesRepository.setExerciseSex(null) } },
                            label = { Text("—") }
                        )
                        FilterChip(
                            selected = exerciseSex == Sex.MALE,
                            onClick = { scope.launch { userPreferencesRepository.setExerciseSex(Sex.MALE) } },
                            label = { Text("M") }
                        )
                        FilterChip(
                            selected = exerciseSex == Sex.FEMALE,
                            onClick = { scope.launch { userPreferencesRepository.setExerciseSex(Sex.FEMALE) } },
                            label = { Text("F") }
                        )
                    }
                }
            )

            HorizontalDivider()

            // ── Permissions ───────────────────────────────────────────────
            SectionHeader("Permissions")

            SettingsNavRow(
                title = "Permissions",
                subtitle = "Grant Health Connect access for exercise",
                onClick = onPermissions
            )

            // ── Advanced (developer only) ─────────────────────────────────
            if (developerMode) {
                HorizontalDivider()
                SectionHeader("Advanced (Developer)")

                OutlinedTextField(
                    value = epocInput,
                    onValueChange = { epocInput = it },
                    label = { Text("EPOC multiplier for strength") },
                    supportingText = { Text("Default 1.07 = +7% post-exercise calorie burn") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = {
                            val v = epocInput.toDoubleOrNull() ?: return@TextButton
                            scope.launch { userPreferencesRepository.setExerciseEpocMultiplier(v) }
                        }) { Text("Save") }
                    }
                )
            }
        }
    }
}
