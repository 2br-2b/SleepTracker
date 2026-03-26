package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.exercise.domain.Sex
import codegito.xyz.healthconnector.weight.domain.WeightUnit
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
    val weightEnabled by userPreferencesRepository.weightEnabled.collectAsState(initial = false)
    val weightUnit by userPreferencesRepository.weightUnit.collectAsState(initial = WeightUnit.LBS)
    val exerciseAge by userPreferencesRepository.exerciseAge.collectAsState(initial = null)
    val exerciseSex by userPreferencesRepository.exerciseSex.collectAsState(initial = null)
    val defaultWeightKg by userPreferencesRepository.exerciseDefaultWeightKg.collectAsState(initial = 70.0)
    val developerMode by userPreferencesRepository.developerModeEnabled.collectAsState(initial = false)
    val epocMultiplier by userPreferencesRepository.exerciseEpocMultiplier.collectAsState(initial = 1.07)

    var ageInput by remember(exerciseAge) { mutableStateOf(exerciseAge?.toString() ?: "") }
    var weightInput by remember(defaultWeightKg, weightUnit) {
        val display = if (weightUnit == WeightUnit.KG) defaultWeightKg
                      else defaultWeightKg * 2.20462
        mutableStateOf("%.1f".format(display))
    }
    var epocInput by remember(epocMultiplier) { mutableStateOf("%.2f".format(epocMultiplier)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exercise & Weight Settings") },
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
            // ── Feature toggles ──────────────────────────────────────────
            SectionHeader("Features")

            ListItem(
                headlineContent = { Text("Weight tracking") },
                supportingContent = { Text("Show a weight tab and log body weight to Health Connect") },
                trailingContent = {
                    Switch(
                        checked = weightEnabled,
                        onCheckedChange = { scope.launch { userPreferencesRepository.setWeightEnabled(it) } }
                    )
                }
            )

            ListItem(
                headlineContent = { Text("Exercise tracking") },
                supportingContent = { Text("Show an exercise tab and log workouts to Health Connect") },
                trailingContent = {
                    Switch(
                        checked = exerciseEnabled,
                        onCheckedChange = { scope.launch { userPreferencesRepository.setExerciseEnabled(it) } }
                    )
                }
            )

            HorizontalDivider()

            // ── Units ────────────────────────────────────────────────────
            SectionHeader("Units")

            ListItem(
                headlineContent = { Text("Weight unit") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = weightUnit == WeightUnit.LBS,
                            onClick = { scope.launch { userPreferencesRepository.setWeightUnit(WeightUnit.LBS) } },
                            label = { Text("lbs") }
                        )
                        FilterChip(
                            selected = weightUnit == WeightUnit.KG,
                            onClick = { scope.launch { userPreferencesRepository.setWeightUnit(WeightUnit.KG) } },
                            label = { Text("kg") }
                        )
                    }
                }
            )

            HorizontalDivider()

            // ── Calorie estimation inputs ─────────────────────────────────
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
                        scope.launch {
                            userPreferencesRepository.setExerciseAge(ageInput.toIntOrNull())
                        }
                    }) { Text("Save") }
                }
            )

            // Sex selector
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

            OutlinedTextField(
                value = weightInput,
                onValueChange = { weightInput = it },
                label = { Text("Default body weight (${if (weightUnit == WeightUnit.KG) "kg" else "lbs"})") },
                supportingText = { Text("Used as fallback when no weight is logged in Health Connect") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        val value = weightInput.toDoubleOrNull() ?: return@TextButton
                        val kg = if (weightUnit == WeightUnit.KG) value else value / 2.20462
                        scope.launch { userPreferencesRepository.setExerciseDefaultWeightKg(kg) }
                    }) { Text("Save") }
                }
            )

            HorizontalDivider()

            // ── Permissions link ──────────────────────────────────────────
            SectionHeader("Permissions")

            ListItem(
                headlineContent = { Text("Permissions") },
                supportingContent = { Text("Grant Health Connect access for weight and exercise") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
                    .then(Modifier.clickable { onPermissions() })
            )

            // ── Developer / advanced ──────────────────────────────────────
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

private fun Modifier.clickable(onClick: () -> Unit) = this.then(
    androidx.compose.ui.Modifier.clickable { onClick() }
)
