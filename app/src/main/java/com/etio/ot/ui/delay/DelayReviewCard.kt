package com.etio.ot.ui.delay

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import com.etio.ot.di.CoreModule
import com.etio.ot.ui.tutorial.SpotlightTarget
import com.etio.ot.ui.tutorial.spotlight
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.EtioMonoStyle

/**
 * The structured record, large and first; the words it came from, underneath in mono.
 *
 * That order is the argument: the fields are what you act on, the transcript is what
 * proves them. Subordinate in weight, never hidden, never behind a disclosure.
 * Every field carries its own edit affordance, so correcting one thing never means
 * re-reading the rest.
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
    onTranscriptCorrected: (String) -> Unit,
    onDiscard: () -> Unit,
    onNotify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember(record.id) { mutableStateOf<Field?>(null) }
    var transcriptDraft by remember(record.id) { mutableStateOf(record.transcriptRaw) }
    var noteDraft by remember(record.id) { mutableStateOf(record.note) }
    var estimateDraft by remember(record.id) { mutableStateOf(record.estimatedMin?.toString().orEmpty()) }
    val depts = remember { CoreModule.config.taxonomy().departmentHints }

    Column(modifier) {

        if (record.fellBackToOther) {
            Surface(
                color = Etio.colors.delay.copy(alpha = 0.16f),
                shape = RoundedCornerShape(Etio.radius.pill),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Couldn't place this one — pick the cause",
                    style = MaterialTheme.typography.labelLarge,
                    color = Etio.colors.delay,
                    modifier = Modifier.padding(horizontal = Etio.space.m, vertical = Etio.space.s),
                )
            }
            Spacer(Modifier.height(Etio.space.m))
        }

        Field(
            label = "Cause",
            value = record.code.display,
            onEdit = { editing = if (editing == Field.CODE) null else Field.CODE },
        )
        if (editing == Field.CODE) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Etio.space.s)) {
                DelayCode.entries.forEach { code ->
                    FilterChip(
                        selected = code == record.code,
                        onClick = { onCodeChange(code); editing = null },
                        label = { Text(code.display, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        }

        Field(
            label = "Department",
            value = record.attributedDept.ifBlank { "—" },
            onEdit = { editing = if (editing == Field.DEPT) null else Field.DEPT },
        )
        if (editing == Field.DEPT) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Etio.space.s),
            ) {
                depts.forEach { dept ->
                    FilterChip(
                        selected = dept.equals(record.attributedDept, ignoreCase = true),
                        onClick = { onDeptChange(dept); editing = null },
                        label = { Text(dept, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        }

        Field(
            label = "Avoidable",
            value = when (record.avoidable) {
                Avoidability.AVOIDABLE -> "Yes"
                Avoidability.UNAVOIDABLE -> "No"
                Avoidability.UNCLEAR -> "Unclear"
            },
            onEdit = { editing = if (editing == Field.AVOIDABLE) null else Field.AVOIDABLE },
        )
        if (editing == Field.AVOIDABLE) {
            Row(horizontalArrangement = Arrangement.spacedBy(Etio.space.s)) {
                Avoidability.entries.forEach { value ->
                    FilterChip(
                        selected = value == record.avoidable,
                        onClick = { onAvoidableChange(value); editing = null },
                        label = {
                            Text(
                                when (value) {
                                    Avoidability.AVOIDABLE -> "Yes"
                                    Avoidability.UNAVOIDABLE -> "No"
                                    Avoidability.UNCLEAR -> "Unclear"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                }
            }
        }

        Field(
            label = "Expected delay",
            value = record.estimatedMin?.let { "$it min" } ?: "Not stated",
            onEdit = { editing = if (editing == Field.ETA) null else Field.ETA },
        )
        if (editing == Field.ETA) {
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
        }

        Field(
            label = "Note",
            value = record.note.ifBlank { "—" },
            onEdit = { editing = if (editing == Field.NOTE) null else Field.NOTE },
        )
        if (editing == Field.NOTE) {
            OutlinedTextField(
                value = noteDraft,
                onValueChange = { noteDraft = it; onNoteChange(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(Etio.space.gutter))

        // The evidence. Mono, secondary, and always on screen.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "WHAT WAS SAID",
                style = MaterialTheme.typography.labelLarge,
                color = Etio.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { editing = if (editing == Field.TRANSCRIPT) null else Field.TRANSCRIPT }) {
                Text(if (editing == Field.TRANSCRIPT) "Cancel" else "Fix wording")
            }
        }
        if (editing == Field.TRANSCRIPT) {
            OutlinedTextField(
                value = transcriptDraft,
                onValueChange = { transcriptDraft = it },
                textStyle = EtioMonoStyle,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { editing = null; onTranscriptCorrected(transcriptDraft) },
                enabled = transcriptDraft.isNotBlank() && transcriptDraft != record.transcriptRaw,
            ) { Text("Re-read that") }
        } else {
            Text(
                record.transcriptRaw,
                style = EtioMonoStyle,
                color = Etio.colors.textSecondary,
                modifier = Modifier.spotlight(SpotlightTarget.TRANSCRIPT),
            )
        }

        Spacer(Modifier.height(Etio.space.s))
        Text(
            "CONFIDENCE ${"%.2f".format(record.modelConfidence)}" +
                if (record.userEdited) " · EDITED" else "",
            style = MaterialTheme.typography.labelLarge,
            color = Etio.colors.textSecondary,
        )

        Spacer(Modifier.height(Etio.space.gutter))

        Row(
            horizontalArrangement = Arrangement.spacedBy(Etio.space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDiscard) { Text("Discard") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onNotify,
                shape = RoundedCornerShape(Etio.radius.pill),
                colors = ButtonDefaults.buttonColors(containerColor = Etio.colors.accent),
                modifier = Modifier.height(56.dp),
            ) { Text("Notify", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

private enum class Field { CODE, DEPT, AVOIDABLE, ETA, NOTE, TRANSCRIPT }

/** Label above, value large, and a 44dp edit target that never moves. */
@Composable
private fun Field(label: String, value: String, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = Etio.colors.textSecondary,
            )
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit $label",
                tint = Etio.colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    Spacer(Modifier.height(Etio.space.m))
}
