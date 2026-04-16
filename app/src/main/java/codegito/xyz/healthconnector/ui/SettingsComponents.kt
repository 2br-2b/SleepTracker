package codegito.xyz.healthconnector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A settings row that navigates to a sub-screen when tapped.
 */
@Composable
fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier
    )
}

/**
 * A settings row with a Switch trailing control.
 * Tapping the whole row toggles the switch.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier
    )
}

/**
 * A settings row with a Checkbox trailing control.
 * Tapping the whole row toggles the checkbox.
 */
@Composable
fun SettingsCheckRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier
    )
}

/**
 * A settings row with +/- stepper buttons for an integer value.
 */
@Composable
fun SettingsStepperRow(
    title: String,
    value: Int,
    subtitle: String? = null,
    min: Int = 1,
    max: Int = Int.MAX_VALUE,
    step: Int = 1,
    onValueChange: (Int) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onValueChange((value - step).coerceAtLeast(min)) },
                    enabled = value > min
                ) { Text("-") }
                OutlinedButton(
                    onClick = { onValueChange((value + step).coerceAtMost(max)) },
                    enabled = value < max
                ) { Text("+") }
            }
        }
    )
}
