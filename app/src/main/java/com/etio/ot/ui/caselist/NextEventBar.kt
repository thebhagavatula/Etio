package com.etio.ot.ui.caselist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.etio.ot.data.model.EventType
import com.etio.ot.ui.tutorial.SpotlightTarget
import com.etio.ot.ui.tutorial.spotlight
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.glass
import com.etio.ot.ui.theme.rememberEtioHaptics

/**
 * The only decision she should have to make while walking, and the mic beside it.
 *
 * Glass is allowed here — this is chrome, it holds no timer, and the translucency is
 * what tells you the list continues underneath. The button itself is opaque accent:
 * a primary action reading through to whatever scrolls behind it is not a style, it
 * is a mistake.
 */
@Composable
fun NextEventBar(
    caseNumber: String?,
    nextEvent: EventType?,
    onMark: (EventType) -> Unit,
    onOtherEvent: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
    sendForLabel: String? = null,
    onSendFor: () -> Unit = {},
    onDismissSendFor: () -> Unit = {},
) {
    val haptics = rememberEtioHaptics()

    Surface(color = Color.Transparent, modifier = modifier.fillMaxWidth().glass(RoundedCornerShape(0.dp))) {
        Column(Modifier.padding(horizontal = Etio.space.gutter, vertical = Etio.space.m)) {

            // The room is ready, so the only question left is whether to send for the
            // next patient. One tap answers it; dismissing leaves the list untouched.
            sendForLabel?.let { label ->
                Surface(
                    color = Etio.colors.warning.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(Etio.radius.pill),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = Etio.space.l),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = Etio.colors.warning,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissSendFor) { Text("Not yet") }
                        TextButton(
                            onClick = {
                                haptics.tick()
                                onSendFor()
                            },
                        ) { Text("Send for") }
                    }
                }
                Spacer(Modifier.height(Etio.space.m))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Etio.space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (nextEvent != null) {
                    Button(
                        onClick = {
                            haptics.tick()
                            onMark(nextEvent)
                        },
                        shape = RoundedCornerShape(Etio.radius.pill),
                        colors = ButtonDefaults.buttonColors(containerColor = Etio.colors.accent),
                        modifier = Modifier
                            .weight(1f)
                            .height(PRIMARY_HEIGHT)
                            .spotlight(SpotlightTarget.NEXT_EVENT),
                    ) {
                        Text(nextEvent.label, style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Surface(
                        color = Etio.colors.surface,
                        shape = RoundedCornerShape(Etio.radius.pill),
                        modifier = Modifier
                            .weight(1f)
                            .height(PRIMARY_HEIGHT),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                caseNumber?.let { "Case $it fully marked" } ?: "Nothing to mark",
                                style = MaterialTheme.typography.titleMedium,
                                color = Etio.colors.textSecondary,
                            )
                        }
                    }
                }

                // Same height as the primary action, same thumb arc.
                Surface(
                    color = Etio.colors.accent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(Etio.radius.pill),
                    modifier = Modifier
                        .size(PRIMARY_HEIGHT)
                        .spotlight(SpotlightTarget.MIC)
                        .clickableRecord(onRecord),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Say what's holding it up",
                            tint = Etio.colors.accent,
                            modifier = Modifier.size(28.dp),
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
                        "CASE $it".uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = Etio.colors.textSecondary,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOtherEvent) { Text("Other event") }
                }
            }
        }
    }
}

@Composable
private fun Modifier.clickableRecord(onRecord: () -> Unit): Modifier {
    val haptics = rememberEtioHaptics()
    return this.clickable {
        haptics.medium()
        onRecord()
    }
}

/** Gloved thumb, walking. Well above the 44dp minimum on purpose. */
private val PRIMARY_HEIGHT = 64.dp
