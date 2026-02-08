package codegito.xyz.healthconnector.composables

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDateTime
import java.util.Calendar

@Composable
fun TimePickerDialog(
    title: String,
    initialTime: LocalDateTime,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, initialTime.hour)
        set(Calendar.MINUTE, initialTime.minute)
    }

    val timePickerDialog = TimePickerDialog(
        context,
        { _, hourOfDay, minute ->
            val newTime = initialTime
                .withHour(hourOfDay)
                .withMinute(minute)
            onConfirm(newTime)
        },
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
        false // 24-hour format
    )
    timePickerDialog.setOnDismissListener { onDismiss() }
    timePickerDialog.setTitle(title)
    timePickerDialog.show()
}

@Composable
fun DatePickerDialog(
    title: String,
    initialDate: LocalDateTime,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val calendar = Calendar.getInstance().apply {
        set(Calendar.YEAR, initialDate.year)
        set(Calendar.MONTH, initialDate.monthValue - 1)
        set(Calendar.DAY_OF_MONTH, initialDate.dayOfMonth)
    }

    val datePickerDialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val newDate = initialDate
                .withYear(year)
                .withMonth(month + 1)
                .withDayOfMonth(dayOfMonth)
            onConfirm(newDate)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )
    datePickerDialog.setOnDismissListener { onDismiss() }
    datePickerDialog.setTitle(title)
    datePickerDialog.show()
}