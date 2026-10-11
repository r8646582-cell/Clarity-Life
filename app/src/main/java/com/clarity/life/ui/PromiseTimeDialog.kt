package com.clarity.life.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clarity.life.ui.theme.clarity
import java.time.LocalDate
import java.time.LocalTime

private const val DAY_MS = 86_400_000L

/**
 * The time picker a promise card opens when its "when" cannot be read for certain (PromiseTimes): he picks
 * the day and the time himself, with the likeliest reading preselected when there is one. Set it stays
 * disabled until both are chosen and the moment is still ahead, so a promise is never saved in the past
 * and never at a moment he did not see.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromiseTimeDialog(
    title: String,
    hint: String?,
    initialDate: LocalDate?,
    initialTime: LocalTime?,
    today: LocalDate,
    show: (LocalDate, LocalTime) -> String,
    isAhead: (LocalDate, LocalTime) -> Boolean,
    confirmLabel: String,
    onConfirm: (LocalDate, LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    var date by remember { mutableStateOf(initialDate) }
    var time by remember { mutableStateOf(initialTime) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    val chosen = date?.let { d -> time?.let { t -> d to t } }
    val ahead = chosen?.let { isAhead(it.first, it.second) } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = clarity.surface,
        title = { Text(title, color = clarity.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = clarity.muted) }
                PickRow("Day", date?.let { dayLabel(it) } ?: "Not chosen") { pickingDate = true }
                PickRow("Time", time?.let { timeLabel(it) } ?: "Not chosen") { pickingTime = true }
                when {
                    chosen == null -> Unit
                    !ahead -> Text(
                        "That moment has passed. Pick a later one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = clarity.danger,
                    )
                    else -> Text(
                        show(chosen.first, chosen.second),
                        style = MaterialTheme.typography.titleMedium,
                        color = clarity.yellow,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let { onConfirm(it.first, it.second) } }, enabled = ahead) {
                Text(confirmLabel, color = if (ahead) clarity.yellow else clarity.muted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = clarity.text) } },
    )

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: today).toEpochDay() * DAY_MS,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    Math.floorDiv(utcTimeMillis, DAY_MS) in today.toEpochDay()..today.plusDays(365).toEpochDay()

                override fun isSelectableYear(year: Int): Boolean = year in today.year..today.year + 1
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = LocalDate.ofEpochDay(Math.floorDiv(it, DAY_MS)) }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    if (pickingTime) {
        val start = time ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            containerColor = clarity.surface,
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(state.hour, state.minute)
                    pickingTime = false
                }) { Text("OK", color = clarity.yellow) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel", color = clarity.text) } },
        )
    }
}

@Composable
private fun PickRow(label: String, value: String, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = clarity.muted)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = clarity.text)
        }
        TextButton(onClick = onChange) { Text("Change", color = clarity.yellow) }
    }
}

private val SHORT_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val SHORT_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun dayLabel(d: LocalDate) = "${SHORT_DAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${SHORT_MONTHS[d.monthValue - 1]}"

private fun timeLabel(t: LocalTime): String {
    val h = if (t.hour % 12 == 0) 12 else t.hour % 12
    return "%d:%02d %s".format(h, t.minute, if (t.hour < 12) "am" else "pm")
}
