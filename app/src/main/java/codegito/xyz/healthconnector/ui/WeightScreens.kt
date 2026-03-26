package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import codegito.xyz.healthconnector.HealthConnectManager
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import codegito.xyz.healthconnector.weight.domain.WeightEntry
import codegito.xyz.healthconnector.weight.domain.WeightUnit
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightHomeScreen(
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    onNavigateToPermissions: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val weightUnit by userPreferencesRepository.weightUnit.collectAsState(initial = WeightUnit.LBS)
    val historyDays by userPreferencesRepository.historyDays.collectAsState(initial = 7)

    var entries by remember { mutableStateOf<List<WeightEntry>>(emptyList()) }
    var hasWritePermission by remember { mutableStateOf<Boolean?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    suspend fun loadEntries() {
        hasWritePermission = healthConnectManager.hasWeightWritePermission()
        if (healthConnectManager.hasWeightReadPermission()) {
            val end = Instant.now()
            val start = end.minusSeconds(historyDays * 86400L)
            val result = healthConnectManager.getWeightEntries(start, end)
            val records = result.getOrDefault(emptyList())
            entries = records
                .sortedByDescending { it.time }
                .map { r ->
                    WeightEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = r.time,
                        weightKg = r.weight.inKilograms,
                        healthConnectId = r.metadata.id
                    )
                }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { loadEntries() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Weight") }) },
        floatingActionButton = {
            if (hasWritePermission == true) {
                FloatingActionButton(onClick = { showLogDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Log Weight")
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch { loadEntries(); isRefreshing = false }
            },
            modifier = Modifier.padding(padding)
        ) {
            if (hasWritePermission == false) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Weight write permission not granted",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            if (onNavigateToPermissions != null) {
                                TextButton(onClick = onNavigateToPermissions) {
                                    Text("Open Permissions", color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (entries.isEmpty()) {
                        item {
                            Text(
                                "No weight entries yet. Tap + to log your first entry.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 24.dp)
                            )
                        }
                    } else {
                        // Latest weight summary
                        item {
                            val latest = entries.first()
                            val displayWeight = if (weightUnit == WeightUnit.KG)
                                "%.1f kg".format(latest.weightKg)
                            else
                                "%.1f lbs".format(latest.weightKg * 2.20462)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Current", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(displayWeight, style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                        items(entries, key = { it.id }) { entry ->
                            WeightEntryCard(
                                entry = entry,
                                weightUnit = weightUnit,
                                onDelete = {
                                    val hcId = entry.healthConnectId ?: return@WeightEntryCard
                                    scope.launch {
                                        healthConnectManager.deleteWeightEntry(hcId)
                                        loadEntries()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLogDialog) {
        LogWeightDialog(
            weightUnit = weightUnit,
            onConfirm = { kg ->
                scope.launch {
                    healthConnectManager.writeWeightEntry(kg, Instant.now())
                    loadEntries()
                    showLogDialog = false
                }
            },
            onDismiss = { showLogDialog = false }
        )
    }
}

@Composable
private fun WeightEntryCard(
    entry: WeightEntry,
    weightUnit: WeightUnit,
    onDelete: () -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")
    val zoneId = ZoneId.systemDefault()
    val timeStr = formatter.format(entry.timestamp.atZone(zoneId))
    val displayWeight = if (weightUnit == WeightUnit.KG)
        "%.1f kg".format(entry.weightKg)
    else
        "%.1f lbs".format(entry.weightKg * 2.20462)

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(displayWeight, style = MaterialTheme.typography.titleMedium)
                Text(timeStr, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun LogWeightDialog(
    weightUnit: WeightUnit,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lbs"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Weight") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Weight ($unitLabel)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = input.toDoubleOrNull() ?: return@TextButton
                    val kg = if (weightUnit == WeightUnit.KG) value else value / 2.20462
                    onConfirm(kg)
                },
                enabled = input.toDoubleOrNull() != null
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
