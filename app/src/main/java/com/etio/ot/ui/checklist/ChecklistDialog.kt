package com.etio.ot.ui.checklist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.domain.checklist.ChecklistStateMachine
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.rememberEtioHaptics

/**
 * Deliberately unlike the rest of the app.
 *
 * Flat, opaque, no glass, no blur, no motion, and the only place the safety colour
 * is ever used. It should feel like a stop, because it is one — everything else in
 * Etio is designed to get out of the way, and this is the one surface that is not.
 *
 * Nothing here is inferred or pre-filled. Every item is a tap, progress is stated as
 * a count rather than drawn as a bar, and skipping demands a reason in writing.
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
    val haptics = rememberEtioHaptics()

    val confirmed = run?.itemsConfirmed.orEmpty().toSet()
    val (done, total) = ChecklistStateMachine.progress(run, items)
    val isComplete = done == total

    Dialog(
        onDismissRequest = { /* deliberately non-dismissible */ },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(Etio.radius.sheet)),
            color = Etio.colors.surface,
        ) {
            Column(Modifier.padding(Etio.space.xl)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Etio.colors.safety),
                    )
                    Spacer(Modifier.width(Etio.space.m))
                    Text(
                        "WHO ${phase.display}".uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = Etio.colors.safety,
                    )
                }

                Spacer(Modifier.height(Etio.space.s))

                // A count, not a bar. "5 of 7" is a fact; a filled track is a mood.
                Text(
                    "$done of $total confirmed",
                    style = MaterialTheme.typography.titleLarge,
                    color = Etio.colors.textPrimary,
                )
                Text(
                    "EVERY ITEM IS CONFIRMED BY YOU. NOTHING HERE IS AUTOMATED.",
                    style = MaterialTheme.typography.labelLarge,
                    color = Etio.colors.textSecondary,
                )

                Spacer(Modifier.height(Etio.space.gutter))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Etio.space.s),
                ) {
                    items.forEach { item ->
                        val isChecked = item.id in confirmed
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .clip(RoundedCornerShape(Etio.radius.pill))
                                .background(
                                    if (isChecked) {
                                        Etio.colors.safety.copy(alpha = 0.14f)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable {
                                    haptics.tick()
                                    onToggle(item.id)
                                }
                                .padding(horizontal = Etio.space.l, vertical = Etio.space.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (isChecked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                contentDescription = null,
                                tint = if (isChecked) Etio.colors.safety else Etio.colors.textSecondary,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.width(Etio.space.l))
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isChecked || item.critical) {
                                    Etio.colors.textPrimary
                                } else {
                                    Etio.colors.textSecondary
                                },
                            )
                        }
                    }

                    if (skipping) {
                        OutlinedTextField(
                            value = skipReason,
                            onValueChange = { skipReason = it },
                            label = { Text("Reason for skipping") },
                            singleLine = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Etio.space.l),
                            shape = RoundedCornerShape(Etio.radius.pill),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Etio.colors.delay,
                                focusedLabelColor = Etio.colors.delay,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(Etio.space.xl))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { skipping = !skipping }) {
                        Text(
                            text = if (skipping) "Cancel skip" else "Skip phase",
                            color = if (skipping) Etio.colors.textSecondary else Etio.colors.delay,
                        )
                    }

                    if (skipping) {
                        Button(
                            onClick = { onSkip(skipReason) },
                            enabled = skipReason.isNotBlank(),
                            shape = RoundedCornerShape(Etio.radius.pill),
                            colors = ButtonDefaults.buttonColors(containerColor = Etio.colors.delay),
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) { Text("Confirm skip") }
                    } else {
                        Button(
                            onClick = {
                                haptics.double()
                                onComplete()
                            },
                            enabled = isComplete,
                            shape = RoundedCornerShape(Etio.radius.pill),
                            colors = ButtonDefaults.buttonColors(containerColor = Etio.colors.safety),
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) { Text("Complete phase") }
                    }
                }
            }
        }
    }
}
