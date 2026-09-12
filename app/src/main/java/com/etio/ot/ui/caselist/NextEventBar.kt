package com.etio.ot.ui.caselist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.etio.ot.data.model.EventType

/**
 * The one decision the coordinator should have to make while walking.
 *
 * The case state machine already knows which event comes next, so the primary
 * control is not a grid to choose from — it is that single event, full width, at
 * the bottom of the screen where a thumb already is. The grid still exists behind
 * "Other event" for marking out of order.
 *
 * No confirmation dialog: the mark is append-only and correctable, so the cost of
 * a mistap is a long-press, not a lost minute.
 */
@Composable
fun NextEventBar(
    caseNumber: String?,
    nextEvent: EventType?,
    onMark: (EventType) -> Unit,
    onOtherEvent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (nextEvent != null) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMark(nextEvent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PRIMARY_HEIGHT),
                ) {
                    Text(
                        nextEvent.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PRIMARY_HEIGHT),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            caseNumber?.let { "Case $it fully marked" } ?: "Nothing to mark",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                caseNumber?.let {
                    Text(
                        "Case $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onOtherEvent) { Text("Other event") }
                }
            }
        }
    }
}

/** Gloved thumb, walking. Well above the 48dp minimum on purpose. */
private val PRIMARY_HEIGHT = 64.dp
