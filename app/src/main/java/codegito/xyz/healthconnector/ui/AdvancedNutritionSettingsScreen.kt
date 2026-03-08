package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedNutritionSettingsScreen(
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val askEatenTime by userPreferencesRepository.nutritionAskEatenTime.collectAsState(initial = false)
    val breakfastStart by userPreferencesRepository.nutritionBreakfastStartHour.collectAsState(initial = 6)
    val breakfastEnd by userPreferencesRepository.nutritionBreakfastEndHour.collectAsState(initial = 10)
    val lunchStart by userPreferencesRepository.nutritionLunchStartHour.collectAsState(initial = 11)
    val lunchEnd by userPreferencesRepository.nutritionLunchEndHour.collectAsState(initial = 14)
    val dinnerStart by userPreferencesRepository.nutritionDinnerStartHour.collectAsState(initial = 17)
    val dinnerEnd by userPreferencesRepository.nutritionDinnerEndHour.collectAsState(initial = 21)
    val mealDuration by userPreferencesRepository.nutritionMealDurationMinutes.collectAsState(initial = 30)
    val snackDuration by userPreferencesRepository.nutritionSnackDurationMinutes.collectAsState(initial = 10)

    // Picker dialog state: which hour are we picking (label, current value, callback)
    var pickerTitle by remember { mutableStateOf("") }
    var pickerInitialHour by remember { mutableIntStateOf(0) }
    var pickerCallback by remember { mutableStateOf<((Int) -> Unit)?>(null) }

    fun showPicker(title: String, initial: Int, onConfirm: (Int) -> Unit) {
        pickerTitle = title
        pickerInitialHour = initial
        pickerCallback = onConfirm
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced Nutrition") },
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
            // ── Time tracking ─────────────────────────────────────────────
            SectionHeader("Time Tracking")

            ListItem(
                headlineContent = { Text("Ask when food was eaten") },
                supportingContent = {
                    Text(
                        if (askEatenTime)
                            "A time picker appears each time you log food."
                        else
                            "Logs at current time without asking."
                    )
                },
                trailingContent = {
                    Switch(
                        checked = askEatenTime,
                        onCheckedChange = { enabled ->
                            scope.launch { userPreferencesRepository.setNutritionAskEatenTime(enabled) }
                        }
                    )
                }
            )

            HorizontalDivider()

            // ── Meal detection windows ─────────────────────────────────────
            SectionHeader("Meal Detection Windows")

            Text(
                "Foods logged within a window are tagged as that meal. Everything else is tagged as a snack.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            MealWindowRow(
                label = "Breakfast",
                startHour = breakfastStart,
                endHour = breakfastEnd,
                onEditStart = {
                    showPicker("Breakfast start", breakfastStart) { h ->
                        scope.launch { userPreferencesRepository.setNutritionBreakfastHours(h, breakfastEnd) }
                    }
                },
                onEditEnd = {
                    showPicker("Breakfast end", breakfastEnd) { h ->
                        scope.launch { userPreferencesRepository.setNutritionBreakfastHours(breakfastStart, h) }
                    }
                }
            )

            MealWindowRow(
                label = "Lunch",
                startHour = lunchStart,
                endHour = lunchEnd,
                onEditStart = {
                    showPicker("Lunch start", lunchStart) { h ->
                        scope.launch { userPreferencesRepository.setNutritionLunchHours(h, lunchEnd) }
                    }
                },
                onEditEnd = {
                    showPicker("Lunch end", lunchEnd) { h ->
                        scope.launch { userPreferencesRepository.setNutritionLunchHours(lunchStart, h) }
                    }
                }
            )

            MealWindowRow(
                label = "Dinner",
                startHour = dinnerStart,
                endHour = dinnerEnd,
                onEditStart = {
                    showPicker("Dinner start", dinnerStart) { h ->
                        scope.launch { userPreferencesRepository.setNutritionDinnerHours(h, dinnerEnd) }
                    }
                },
                onEditEnd = {
                    showPicker("Dinner end", dinnerEnd) { h ->
                        scope.launch { userPreferencesRepository.setNutritionDinnerHours(dinnerStart, h) }
                    }
                }
            )

            HorizontalDivider()

            // ── Entry duration ─────────────────────────────────────────────
            SectionHeader("Entry Duration")

            ListItem(
                headlineContent = { Text("Meal duration") },
                supportingContent = { Text("$mealDuration min — backfilled from the eaten time for breakfast, lunch, and dinner") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                userPreferencesRepository.setNutritionMealDurationMinutes(
                                    (mealDuration - 5).coerceAtLeast(5)
                                )
                            }
                        }) { Text("-") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                userPreferencesRepository.setNutritionMealDurationMinutes(
                                    (mealDuration + 5).coerceAtMost(180)
                                )
                            }
                        }) { Text("+") }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Snack duration") },
                supportingContent = { Text("$snackDuration min — used for snacks and foods logged outside meal windows") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                userPreferencesRepository.setNutritionSnackDurationMinutes(
                                    (snackDuration - 5).coerceAtLeast(1)
                                )
                            }
                        }) { Text("-") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                userPreferencesRepository.setNutritionSnackDurationMinutes(
                                    (snackDuration + 5).coerceAtMost(120)
                                )
                            }
                        }) { Text("+") }
                    }
                }
            )
        }
    }

    // Hour picker dialog
    pickerCallback?.let { callback ->
        RolloverTimePickerDialog(
            initialHour = pickerInitialHour,
            onConfirm = { hour ->
                callback(hour)
                pickerCallback = null
            },
            onDismiss = { pickerCallback = null }
        )
    }
}

@Composable
private fun MealWindowRow(
    label: String,
    startHour: Int,
    endHour: Int,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit
) {
    val fmt = DateTimeFormatter.ofPattern("h a")
    val startLabel = LocalTime.of(startHour, 0).format(fmt)
    val endLabel = LocalTime.of(endHour, 0).format(fmt)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Start", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(startLabel, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(onClick = onEditStart) { Text("Change") }

                Column(modifier = Modifier.weight(1f)) {
                    Text("End", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(endLabel, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(onClick = onEditEnd) { Text("Change") }
            }
        }
    }
}
