package com.etio.ot.ui.caselist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.etio.ot.core.formatMmSs
import com.etio.ot.core.formatSignedMinutes
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.EventType
import com.etio.ot.domain.timing.CaseMetrics
import com.etio.ot.domain.timing.TimerEngine
import com.etio.ot.ui.events.EventGrid
import com.etio.ot.ui.theme.EtioStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CaseCard(
    case: CaseEntity,
    metrics: CaseMetrics?,
    events: List<EventEntity>,
    delays: List<DelayRecordEntity>,
    isActive: Boolean,
    onMarkEvent: (EventType) -> Unit,
    onCorrectEvent: (EventEntity, Long) -> Unit,
    onCaptureDelay: () -> Unit,
    onOpenDelay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Case ${case.caseNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    timeFmt.format(Date(case.scheduledStartMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            Text(case.procedureName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${case.surgeon} · ${case.scheduledDurationMin} min planned",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Live spans. These update every second, from the system clock only.
            metrics?.let { m ->
                Spacer(Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    m.turnoverMs?.let { Metric("Turnover", it.formatMmSs()) }
                    m.anaesthesiaControlledMs?.let { Metric("In room → knife", it.formatMmSs()) }
                    m.procedureMs?.let { Metric("Procedure", it.formatMmSs()) }
                }
            }

            Spacer(Modifier.padding(top = 10.dp))

            EventGrid(
                marked = metrics?.marks.orEmpty(),
                nextExpected = metrics?.marks?.let { TimerEngine.nextExpectedEvent(it) }
                    ?: EventType.PATIENT_SENT_FOR,
                onMark = onMarkEvent,
                // TODO(build): long-press opens a time picker and passes the chosen
                // millis. Until then it round-trips the existing timestamp, which is a
                // no-op the append-only store handles safely.
                onLongPress = { type ->
                    events.firstOrNull { it.type == type }?.let { onCorrectEvent(it, it.timestampMs) }
                },
            )

            // F9 — delay history strip. Cheap, and it makes the pattern visible live.
            if (delays.isNotEmpty()) {
                Spacer(Modifier.padding(top = 8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    delays.forEach { d ->
                        AssistChip(
                            onClick = { onOpenDelay(d.id) },
                            label = {
                                Text(
                                    d.estimatedMin?.let { "${d.code.display} · ${it}m" } ?: d.code.display,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.padding(top = 10.dp))

            FilledTonalButton(
                onClick = onCaptureDelay,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("What's holding it up?")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
