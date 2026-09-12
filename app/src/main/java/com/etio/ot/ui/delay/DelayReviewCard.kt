package com.etio.ot.ui.delay

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import com.etio.ot.di.ServiceLocator

/**
 * PRD §7 F4. Every model-assigned field is one tap from being corrected, and the
 * verbatim transcript sits underneath the whole card — not in a tooltip, not behind
 * a disclosure. That placement is the argument.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DelayReviewCard(
    record: DelayRecordEntity,
    onCodeChange: (DelayCode) -> Unit,
    onDeptChange: (String) -> Unit,
    onAvoidableChange: (Avoidability) -> Unit,
    onEstimateChange: (Int?) -> Unit,
    onNoteChange: (String) -> Unit,
    onDiscard: () -> Unit,
    onNotify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandCodes by remember { mutableStateOf(false) }
    var noteDraft by remember(record.id) { mutableStateOf(record.note) }
    var estimateDraft by remember(record.id) { mutableStateOf(record.estimatedMin?.toString().orEmpty()) }
    val depts = remember { ServiceLocator.config.taxonomy().departmentHints }

    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {

            if (record.fellBackToOther) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Couldn't place this one — please pick the cause.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.padding(top = 10.dp))
            }

            // --- cause ---
            FieldLabel("Cause")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.code.display,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { expandCodes = !expandCodes }) {
                    Text(if (expandCodes) "Close" else "Change")
                }
            }
            if (expandCodes) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DelayCode.entries.forEach { code ->
                        FilterChip(
                            selected = code == record.code,
                            onClick = { onCodeChange(code); expandCodes = false },
                            label = { Text(code.display, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }

            Spacer(Modifier.padding(top = 12.dp))

            // --- department ---
            FieldLabel("Department")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                depts.forEach { dept ->
                    FilterChip(
                        selected = dept.equals(record.attributedDept, ignoreCase = true),
                        onClick = { onDeptChange(dept) },
                        label = { Text(dept, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Spacer(Modifier.padding(top = 12.dp))

            // --- avoidability ---
            FieldLabel("Avoidable?")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Avoidability.entries.forEach { value ->
                    FilterChip(
                        selected = value == record.avoidable,
                        onClick = { onAvoidableChange(value) },
                        label = {
                            Text(
                                when (value) {
                                    Avoidability.AVOIDABLE -> "Avoidable"
                                    Avoidability.UNAVOIDABLE -> "Unavoidable"
                                    Avoidability.UNCLEAR -> "Unclear"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.padding(top = 12.dp))

            // --- ETA ---
            FieldLabel("Expected delay (only if it was said)")
            OutlinedTextField(
                value = estimateDraft,
                onValueChange = {
                    estimateDraft = it.filter(Char::isDigit).take(3)
                    onEstimateChange(estimateDraft.toIntOrNull())
                },
                suffix = { Text("min") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.padding(top = 12.dp))

            // --- note ---
            FieldLabel("Note")
            OutlinedTextField(
                value = noteDraft,
                onValueChange = { noteDraft = it; onNoteChange(it) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.padding(top = 14.dp))

            // --- grounding evidence, always visible ---
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "What was actually said",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(top = 4.dp))
                    Text("“${record.transcriptRaw}”", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.padding(top = 6.dp))
                    Text(
                        "confidence ${"%.2f".format(record.modelConfidence)}" +
                            if (record.userEdited) " · edited" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.padding(top = 16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onDiscard) { Text("Discard") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onNotify) { Text("Notify") }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
