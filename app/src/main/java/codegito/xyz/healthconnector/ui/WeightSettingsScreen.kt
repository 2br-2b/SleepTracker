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
import codegito.xyz.healthconnector.weight.domain.WeightUnit
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit,
    onPermissions: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val weightEnabled by userPreferencesRepository.weightEnabled.collectAsState(initial = false)
    val weightUnit by userPreferencesRepository.weightUnit.collectAsState(initial = WeightUnit.LBS)
    val defaultWeightKg by userPreferencesRepository.exerciseDefaultWeightKg.collectAsState(initial = 70.0)

    var weightInput by remember(defaultWeightKg, weightUnit) {
        val display = if (weightUnit == WeightUnit.KG) defaultWeightKg else defaultWeightKg * 2.20462
        mutableStateOf("%.1f".format(display))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weight Settings") },
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
                title = "Weight tracking",
                subtitle = "Show a weight tab and log body weight to Health Connect",
                checked = weightEnabled,
                onCheckedChange = { scope.launch { userPreferencesRepository.setWeightEnabled(it) } }
            )

            HorizontalDivider()

            // ── Default body weight ───────────────────────────────────────
            SectionHeader("Default Body Weight")

            Text(
                "Used as a fallback for calorie estimation when no weight is logged in Health Connect. " +
                "Weight data is always read from Health Connect when available, even when weight tracking is off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = weightInput,
                onValueChange = { weightInput = it },
                label = { Text("Default body weight (${if (weightUnit == WeightUnit.KG) "kg" else "lbs"})") },
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

            // ── Permissions ───────────────────────────────────────────────
            SectionHeader("Permissions")

            SettingsNavRow(
                title = "Permissions",
                subtitle = "Grant Health Connect access for weight",
                onClick = onPermissions
            )
        }
    }
}
