package com.etio.ot.ui.delay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etio.ot.ui.common.RevealAfter
import com.etio.ot.ui.theme.InferenceSignal
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.etio.ot.ai.ConfidenceBand
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
    val band = ConfidenceBand.of(record.modelConfidence)
    val needsAttention = ConfidenceBand.needsAttention(record.modelConfidence, record.fellBackToOther)

    // A record the model could not place opens on the cause, already editable. She
    // came here to fix it; making her find the edit button first is a tap spent on
    // nothing. A confident record opens closed, as before.
    var editing by remember(record.id) { mutableStateOf(if (needsAttention) Field.CODE else null) }
    var showRawConfidence by remember(record.id) { mutableStateOf(false) }

    // Fields land one after another over ~400ms rather than all at once. The
    // inference that produced them took seconds; arriving in sequence reads as that
    // work finishing, where a single instant swap reads as a freeze and then a jump.
    // Off while a model job is running, so the reveal never competes for the GPU.
    val inferenceActive by InferenceSignal.active.collectAsStateWithLifecycle(initialValue = false)
    val stagger = !inferenceActive
    var transcriptDraft by remember(record.id) { mutableStateOf(record.transcriptRaw) }
    var noteDraft by remember(record.id) { mutableStateOf(record.note) }
    var estimateDraft by remember(record.id) { mutableStateOf(record.estimatedMin?.toString().orEmpty()) }
    val depts = remember { CoreModule.config.taxonomy().departmentHints }

    Column(modifier) {

        if (record.fellBackToOther) {
            Surface(
                color = Etio.colors.delayTint,
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

        // The band marks the three fields the model judged. The expected delay is
        // gated by the transcript check rather than by the model's own certainty, and
        // the note is her words quoted back, so neither carries it.
        RevealAfter(0, stagger) {
            Field(
                label = "Cause",
                value = record.code.display,
                band = band,
                onEdit = { editing = if (editing == Field.CODE) null else Field.CODE },
            )
        }
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

        RevealAfter(100, stagger) {
            Field(
                label = "Department",
                value = record.attributedDept.ifBlank { "—" },
                band = band,
                grounded = record.deptGrounded,
                onEdit = { editing = if (editing == Field.DEPT) null else Field.DEPT },
            )
        }
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

        RevealAfter(200, stagger) {
            Field(
                label = "Avoidable",
                value = when (record.avoidable) {
                    Avoidability.AVOIDABLE -> "Yes"
                    Avoidability.UNAVOIDABLE -> "No"
                    Avoidability.UNCLEAR -> "Unclear"
                },
                band = band,
                onEdit = { editing = if (editing == Field.AVOIDABLE) null else Field.AVOIDABLE },
            )
        }
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

        RevealAfter(300, stagger) {
            Field(
                label = "Expected delay",
                // Nothing to verify when nothing was claimed, so no indicator either.
                grounded = record.estimatedMin?.let { record.estimatedMinGrounded },
                value = record.estimatedMin?.let { "$it min" } ?: "Not stated",
                onEdit = { editing = if (editing == Field.ETA) null else Field.ETA },
            )
        }
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
            grounded = record.noteGrounded,
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
            // Normally the evidence is subordinate: the fields are what she acts on.
            // When the model could not place the utterance that ordering is wrong —
            // her own words are now the most reliable thing on the card, so they get
            // the same weight and the same ink as the fields above.
            Text(
                record.transcriptRaw,
                style = if (needsAttention) EtioMonoStyle.copy(fontSize = 17.sp) else EtioMonoStyle,
                color = if (needsAttention) Etio.colors.textPrimary else Etio.colors.textSecondary,
                modifier = Modifier.spotlight(SpotlightTarget.TRANSCRIPT),
            )
        }

        Spacer(Modifier.height(Etio.space.s))
        // Always shown, never rounded up, never suppressed when it is bad. Long-press
        // gives the unrounded float — the two-decimal form is for reading, not a
        // softer version of the number.
        Text(
            text = buildString {
                append("CONFIDENCE ")
                append(if (showRawConfidence) record.modelConfidence.toString() else "%.2f".format(record.modelConfidence))
                append(" · ")
                append(
                    when (band) {
                        ConfidenceBand.CONFIDENT -> "CONFIDENT"
                        ConfidenceBand.UNCERTAIN -> "UNCERTAIN"
                        ConfidenceBand.LOW -> "LOW"
                    },
                )
                if (record.userEdited) append(" · EDITED")
            },
            style = MaterialTheme.typography.labelLarge,
            color = when (band) {
                ConfidenceBand.CONFIDENT -> Etio.colors.textSecondary
                ConfidenceBand.UNCERTAIN -> Etio.colors.warning
                ConfidenceBand.LOW -> Etio.colors.delay
            },
            modifier = Modifier.pointerInput(record.id) {
                detectTapGestures(onLongPress = { showRawConfidence = !showRawConfidence })
            },
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

/**
 * Label above, value large, and a 44dp edit target that never moves.
 *
 * [band] decorates rather than obstructs. An uncertain field gets a 2dp amber rail and
 * a four-character label; it is not disabled, not greyed, and not moved, because the
 * model being unsure is not a reason to make the field harder to read. A low-confidence
 * record is handled at the card level instead — it opens on the cause.
 */
@Composable
private fun Field(
    label: String,
    value: String,
    onEdit: () -> Unit,
    band: ConfidenceBand? = null,
    /**
     * Null where the question does not arise. True means a deterministic check found
     * this field's content in the transcript; false means the model introduced it and
     * the verifier has already replaced or dropped what it could.
     */
    grounded: Boolean? = null,
) {
    val flagged = band == ConfidenceBand.UNCERTAIN
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (flagged) {
            Spacer(
                Modifier
                    .width(2.dp)
                    .height(44.dp)
                    .background(Etio.colors.warning, RoundedCornerShape(1.dp)),
            )
            Spacer(Modifier.width(Etio.space.m))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Etio.colors.textSecondary,
                )
                if (flagged) {
                    Spacer(Modifier.width(Etio.space.s))
                    Text(
                        "CHECK THIS",
                        style = MaterialTheme.typography.labelSmall,
                        color = Etio.colors.warning,
                    )
                }
                // The visible artefact of the invariant: a tick means these words came
                // out of the transcript, not out of the model.
                grounded?.let { ok ->
                    Spacer(Modifier.width(Etio.space.s))
                    Text(
                        if (ok) "✓ HEARD" else "NOT HEARD",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ok) Etio.colors.running else Etio.colors.textSecondary,
                    )
                }
            }
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
