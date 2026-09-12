package com.etio.ot.ui.caselist

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import com.etio.ot.data.local.entity.EventEntity
import java.util.Calendar

/**
 * The correction affordance behind every timestamp — the long-press on the grid and
 * the "tap to set" on an assumed-time chip both land here.
 *
 * It only ever hands back a new millisecond value; the append-only correction in
 * [com.etio.ot.data.repository.CaseRepository.correctEvent] is what actually writes,
 * so the original row survives as audit either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventTimeDialog(
    event: EventEntity,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val existing = Calendar.getInstance().apply { timeInMillis = event.timestampMs }
    val picker = rememberTimePickerState(
        initialHour = existing.get(Calendar.HOUR_OF_DAY),
        initialMinute = existing.get(Calendar.MINUTE),
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set ${event.type.label.lowercase()}") },
        text = { TimePicker(state = picker) },
        confirmButton = {
            TextButton(
                onClick = {
                    val corrected = Calendar.getInstance().apply {
                        timeInMillis = event.timestampMs
                        set(Calendar.HOUR_OF_DAY, picker.hour)
                        set(Calendar.MINUTE, picker.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    onConfirm(corrected)
                },
            ) { Text("Set time") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
