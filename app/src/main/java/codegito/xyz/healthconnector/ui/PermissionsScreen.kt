package codegito.xyz.healthconnector.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import codegito.xyz.healthconnector.HealthConnectManager
import codegito.xyz.healthconnector.data.UserPreferencesRepository
import kotlinx.coroutines.launch

// ── Shared permission state holder ───────────────────────────────────────────

data class PermissionState(
    val sleepWriteGranted: Boolean = true,
    val sleepReadGranted: Boolean = true,
    val nutritionWriteGranted: Boolean = true,
    val nutritionReadGranted: Boolean = true,
    val notificationsGranted: Boolean = true,
    val sensorsGranted: Boolean = true,
    val showSensors: Boolean = false,
    val exactAlarmGranted: Boolean = true,
    val isLoaded: Boolean = false
)

suspend fun loadPermissionState(
    context: android.content.Context,
    healthConnectManager: HealthConnectManager
): PermissionState {
    val granted = healthConnectManager.getGrantedPermissions()
    val showSensors = hasGrapheneOsPackage(context)
    return PermissionState(
        sleepWriteGranted = granted.contains(healthConnectManager.sleepWritePermission),
        sleepReadGranted = granted.contains(healthConnectManager.sleepReadPermission),
        nutritionWriteGranted = granted.contains(healthConnectManager.nutritionWritePermission),
        nutritionReadGranted = granted.contains(healthConnectManager.nutritionReadPermission),
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        sensorsGranted = if (showSensors) {
            ContextCompat.checkSelfPermission(context, "android.permission.OTHER_SENSORS") ==
                PackageManager.PERMISSION_GRANTED
        } else true,
        showSensors = showSensors,
        exactAlarmGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else true,
        isLoaded = true
    )
}

private fun hasGrapheneOsPackage(context: android.content.Context): Boolean =
    runCatching { context.packageManager.getPackageInfo("app.grapheneos.apps", 0); true }.getOrDefault(false) ||
    runCatching { context.packageManager.getPackageInfo("app.grapheneos.camera", 0); true }.getOrDefault(false)

// ── Full Permissions Screen (standalone Settings sub-page) ────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    healthConnectManager: HealthConnectManager,
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit,
    onRequestHealthPermissions: (Set<String>) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestSensorsPermission: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val sleepEnabled by userPreferencesRepository.sleepEnabled.collectAsState(initial = true)
    val nutritionEnabled by userPreferencesRepository.nutritionEnabled.collectAsState(initial = true)

    var permState by remember { mutableStateOf(PermissionState()) }

    fun refresh() {
        scope.launch { permState = loadPermissionState(context, healthConnectManager) }
    }

    // Auto-refresh on every resume (permission granted/denied in another activity)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── General ──────────────────────────────────────────────────
            SectionHeader("General")

            PermissionCard(
                title = "Notifications",
                reason = "Required to send sleep logging reminders.",
                granted = permState.notificationsGranted,
                onGrant = {
                    onRequestNotificationPermission()
                    refresh()
                }
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PermissionCard(
                    title = "Exact Alarms",
                    reason = "Required for precise service start/stop scheduling. App falls back gracefully if denied.",
                    granted = permState.exactAlarmGranted,
                    onGrant = {
                        onRequestExactAlarmPermission()
                        refresh()
                    }
                )
            }

            HorizontalDivider()

            // ── Sleep ─────────────────────────────────────────────────────
            SectionHeader("Sleep")

            val sleepSectionDisabled = !sleepEnabled

            PermissionCard(
                title = "Sleep — Write",
                reason = "Required to save confirmed sleep sessions to Health Connect.",
                granted = permState.sleepWriteGranted,
                disabled = sleepSectionDisabled,
                disabledNote = "Sleep tracking is disabled.",
                onGrant = {
                    onRequestHealthPermissions(setOf(
                        healthConnectManager.sleepWritePermission,
                        healthConnectManager.sleepReadPermission
                    ))
                    refresh()
                }
            )

            PermissionCard(
                title = "Sleep — Read",
                reason = "Optional. Allows reading existing sleep sessions from Health Connect.",
                granted = permState.sleepReadGranted,
                optional = true,
                disabled = sleepSectionDisabled,
                disabledNote = "Sleep tracking is disabled.",
                onGrant = {
                    onRequestHealthPermissions(setOf(healthConnectManager.sleepReadPermission))
                    refresh()
                }
            )

            if (permState.showSensors) {
                PermissionCard(
                    title = "Other Sensors (GrapheneOS)",
                    reason = "Required on GrapheneOS for screen state detection to work reliably.",
                    granted = permState.sensorsGranted,
                    disabled = sleepSectionDisabled,
                    disabledNote = "Sleep tracking is disabled.",
                    onGrant = {
                        onRequestSensorsPermission()
                        refresh()
                    },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }
                )
            }

            HorizontalDivider()

            // ── Nutrition ─────────────────────────────────────────────────
            SectionHeader("Nutrition")

            val nutritionSectionDisabled = !nutritionEnabled

            PermissionCard(
                title = "Nutrition — Write",
                reason = "Required to save food logs to Health Connect.",
                granted = permState.nutritionWriteGranted,
                disabled = nutritionSectionDisabled,
                disabledNote = "Nutrition tracking is disabled.",
                onGrant = {
                    onRequestHealthPermissions(setOf(
                        healthConnectManager.nutritionWritePermission,
                        healthConnectManager.nutritionReadPermission
                    ))
                    refresh()
                }
            )

            PermissionCard(
                title = "Nutrition — Read",
                reason = "Optional. Allows reading existing nutrition records from Health Connect.",
                granted = permState.nutritionReadGranted,
                optional = true,
                disabled = nutritionSectionDisabled,
                disabledNote = "Nutrition tracking is disabled.",
                onGrant = {
                    onRequestHealthPermissions(setOf(healthConnectManager.nutritionReadPermission))
                    refresh()
                }
            )

            OutlinedButton(
                onClick = { refresh() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Refresh Permission Status") }

            OutlinedButton(
                onClick = {
                    val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Health Connect App") }
        }
    }
}

// ── Reusable permission card for onboarding ───────────────────────────────────

@Composable
fun PermissionCard(
    title: String,
    reason: String,
    granted: Boolean,
    optional: Boolean = false,
    disabled: Boolean = false,
    disabledNote: String? = null,
    onGrant: () -> Unit,
    onOpenSettings: (() -> Unit)? = null
) {
    val alpha = if (disabled) 0.4f else 1f
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                )
                if (optional) {
                    Text(
                        "Optional",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = alpha)
                    )
                }
            }
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
            if (disabled && disabledNote != null) {
                Text(
                    disabledNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            } else {
                Text(
                    if (granted) "Granted" else "Not granted",
                    color = if (granted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                if (!granted && !disabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onGrant) { Text("Grant") }
                        if (onOpenSettings != null) {
                            OutlinedButton(onClick = onOpenSettings) { Text("Open Settings") }
                        }
                    }
                }
            }
        }
    }
}
