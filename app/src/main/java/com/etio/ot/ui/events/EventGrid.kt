package com.etio.ot.ui.events

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.etio.ot.data.model.EventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PRD §7 F2. Large tap targets — 48dp minimum, gloved-hand sized.
 *
 * Tap marks. Long-press opens correction (the affordance you will need on stage).
 * The next expected event is emphasised; already-marked events show their timestamp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventGrid(
    marked: Map<EventType, Long>,
    nextExpected: EventType?,
    onMark: (EventType) -> Unit,
    onLongPress: (EventType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EventType.ordered.forEach { type ->
            val at = marked[type]
            val isNext = type == nextExpected
            val container = when {
                at != null -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                isNext -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val contentColor = when {
                at != null -> MaterialTheme.colorScheme.onSurface
                isNext -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Surface(
                color = container,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .sizeIn(minWidth = 84.dp, minHeight = 52.dp)
                    .combinedClickable(
                        onClick = { if (at == null) onMark(type) },
                        onLongClick = { if (at != null) onLongPress(type) },
                    ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = type.shortLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = contentColor,
                            fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (at != null) {
                            Text(
                                text = timeFmt.format(Date(at)),
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            }
        }
    }
}
