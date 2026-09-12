package com.etio.ot.ui.caselist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.etio.ot.core.formatMmSs
import com.etio.ot.core.formatSignedMinutes
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.EventSource
import com.etio.ot.data.model.EventType
import com.etio.ot.domain.timing.CaseMetrics
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.EtioStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The case she is on. Flat and opaque — it is lifted off the background by contrast,
 * not by a shadow, and there is no glass anywhere near it because it holds the one
 * number on this screen that is actually moving.
 */
@Composable
fun ActiveCaseCard(
    case: CaseEntity,
    metrics: CaseMetrics?,
    events: List<EventEntity>,
    delays: List<DelayRecordEntity>,
    onOpenDelay: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSetEventTime: (EventEntity) -> Unit = {},
    dismissedAssumptions: Set<String> = emptySet(),
    onDismissAssumption: (String) -> Unit = {},
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var showBreakdown by remember(case.id) { mutableStateOf(false) }

    Surface(
        color = Etio.colors.surface,
        shape = RoundedCornerShape(Etio.radius.card),
        modifier = modifier,
    ) {
        Column(Modifier.padding(Etio.space.card)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CASE ${case.caseNumber}".uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Etio.colors.accent,
                )
                Spacer(Modifier.weight(1f))
                metrics?.startVarianceMin?.let { variance ->
                    Text(
                        variance.formatSignedMinutes(),
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            variance <= 5 -> EtioStatus.onTime
                            variance <= 20 -> EtioStatus.warning
                            else -> EtioStatus.late
                        },
                    )
                }
            }

            Spacer(Modifier.height(Etio.space.s))
            Text(case.procedureName, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Etio.space.xs))
            Text(
                "${case.surgeon} · ${timeFmt.format(Date(case.scheduledStartMs))} · ${case.scheduledDurationMin} MIN PLANNED"
                    .uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = Etio.colors.textSecondary,
            )

            // One live number, large, and it ticks rather than animating.
            metrics?.let { m ->
                Spacer(Modifier.height(Etio.space.gutter))
                val open = openSpan(m)
                Text(
                    open?.second?.formatMmSs() ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    color = if (open != null) Etio.colors.running else Etio.colors.textSecondary,
                )
                Text(
                    (open?.first ?: "Not started").uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Etio.colors.textSecondary,
                )

                TextButton(
                    onClick = { showBreakdown = !showBreakdown },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 0.dp,
                        vertical = Etio.space.xs,
                    ),
                ) {
                    Text(
                        if (showBreakdown) "Hide breakdown" else "Timer breakdown",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (showBreakdown) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Etio.space.gutter)) {
                        m.turnoverMs?.let { Metric("Turnover", it.formatMmSs()) }
                        m.anaesthesiaControlledMs?.let { Metric("In room → knife", it.formatMmSs()) }
                        m.procedureMs?.let { Metric("Procedure", it.formatMmSs()) }
                    }
                }
            }

            // Every event the app filled in states itself until it is dismissed or set.
            events.filter { it.source == EventSource.INFERRED && it.id !in dismissedAssumptions }
                .sortedBy { it.type.ordinal }
                .forEach { assumed ->
                    Spacer(Modifier.height(Etio.space.s))
                    AssumedEventChip(
                        label = "${assumed.type.label} assumed at ${timeFmt.format(Date(assumed.timestampMs))}",
                        onSetTime = { onSetEventTime(assumed) },
                        onDismiss = { onDismissAssumption(assumed.id) },
                    )
                }

            metrics?.marks?.maxByOrNull { it.value }?.let { (type, at) ->
                Spacer(Modifier.height(Etio.space.s))
                Text(
                    "Last mark · ${type.label} ${timeFmt.format(Date(at))}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Etio.colors.textSecondary,
                )
            }
        }
    }

    // F9 — the pattern, visible live, as pills under the card rather than inside it.
    if (delays.isNotEmpty()) {
        Spacer(Modifier.height(Etio.space.s))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Etio.space.s),
        ) {
            delays.forEach { d -> DelayPill(d, onClick = { onOpenDelay(d.id) }) }
        }
    }
}

/** One line, 56dp, for a case that is not the one she is standing in. */
@Composable
fun CaseRow(
    case: CaseEntity,
    metrics: CaseMetrics?,
    modifier: Modifier = Modifier,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = Etio.space.xs),
    ) {
        Text(
            case.caseNumber,
            style = MaterialTheme.typography.labelLarge,
            color = Etio.colors.textSecondary,
            modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                case.procedureName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (metrics?.isComplete == true) Etio.colors.textSecondary else Etio.colors.textPrimary,
            )
        }
        Text(
            timeFmt.format(Date(case.scheduledStartMs)),
            style = MaterialTheme.typography.labelLarge,
            color = Etio.colors.textSecondary,
        )
    }
}

@Composable
private fun DelayPill(record: DelayRecordEntity, onClick: () -> Unit) {
    val tint = when {
        record.estimatedMin == null -> Etio.colors.warning
        record.estimatedMin >= 30 -> Etio.colors.delay
        else -> Etio.colors.warning
    }
    Surface(
        color = tint.copy(alpha = 0.16f),
        shape = RoundedCornerShape(Etio.radius.pill),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            record.estimatedMin?.let { "${record.code.display} · ${it}m" } ?: record.code.display,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            modifier = Modifier.padding(horizontal = Etio.space.m, vertical = Etio.space.s),
        )
    }
}

/**
 * An inferred write, said out loud. "Tap to set" is the correction path; dismissing
 * only hides the chip — the event itself stays in the timeline, italicised.
 */
@Composable
private fun AssumedEventChip(
    label: String,
    onSetTime: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = Etio.colors.warning.copy(alpha = 0.16f),
        shape = RoundedCornerShape(Etio.radius.pill),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = Etio.space.m),
        ) {
            Text(
                "$label — tap to set",
                style = MaterialTheme.typography.labelLarge,
                color = Etio.colors.warning,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSetTime)
                    .padding(vertical = Etio.space.m),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = Etio.colors.textSecondary,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * The one span still running, in clinical order. Spans open and close in sequence,
 * so at most one of these is live at a time across the whole day.
 */
private fun openSpan(m: CaseMetrics): Pair<String, Long>? = when {
    m.isMarked(EventType.KNIFE_TO_SKIN) && !m.isMarked(EventType.CLOSURE_COMPLETE) ->
        "Procedure" to (m.procedureMs ?: 0L)
    m.isMarked(EventType.PATIENT_IN_ROOM) && !m.isMarked(EventType.KNIFE_TO_SKIN) ->
        "In room → knife" to (m.anaesthesiaControlledMs ?: 0L)
    !m.isMarked(EventType.PATIENT_IN_ROOM) && m.turnoverMs != null ->
        "Turnover" to m.turnoverMs
    else -> null
}
