package com.etio.ot.ui.checklist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.domain.checklist.ChecklistStateMachine

/**
 * PRD §7 F6. Deterministic safety artefact.
 *
 * Not dismissible by tapping outside — the coordinator either confirms the critical
 * items or explicitly skips with a reason that is stored and reported.
 */
@Composable
fun ChecklistDialog(
    phase: ChecklistPhase,
    items: List<ChecklistItem>,
    run: ChecklistRunEntity?,
    onToggle: (String) -> Unit,
    onComplete: () -> Unit,
    onSkip: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var skipping by remember { mutableStateOf(false) }
    var skipReason by remember { mutableStateOf("") }

    val confirmed = run?.itemsConfirmed.orEmpty().toSet()
    val (done, total) = ChecklistStateMachine.progress(run, items)

    AlertDialog(
        onDismissRequest = { /* deliberately non-dismissible */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = {
            Column {
                Text("WHO ${phase.display}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "$done of $total critical items confirmed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(item.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = item.id in confirmed,
                            onCheckedChange = { onToggle(item.id) },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (item.critical) FontWeight.Medium else FontWeight.Normal,
                            color = if (item.critical) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                if (skipping) {
                    Spacer(Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = skipReason,
                        onValueChange = { skipReason = it },
                        label = { Text("Reason for skipping") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (skipping) {
                TextButton(
                    onClick = { onSkip(skipReason) },
                    enabled = skipReason.isNotBlank(),
                ) { Text("Confirm skip") }
            } else {
                TextButton(onClick = onComplete) { Text("Complete") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (skipping) skipping = false else skipping = true }) {
                Text(if (skipping) "Back" else "Skip…")
            }
        },
    )
}
