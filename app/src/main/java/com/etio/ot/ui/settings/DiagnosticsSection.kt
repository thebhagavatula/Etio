package com.etio.ot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etio.ot.ai.DecodeProfile
import com.etio.ot.ai.InferenceTelemetry
import com.etio.ot.ui.theme.Etio
import kotlin.math.roundToInt

/**
 * The numbers you get asked for, measured rather than estimated.
 *
 * Everything here is read-only and process-scoped. It exists so that "is it fast
 * enough" and "how do you know it works" have answers taken from this run on this
 * phone, rather than from a slide. Nothing on this screen is on the demo path, and
 * nothing here writes to the record.
 */
@Composable
fun DiagnosticsSection() {
    val t by InferenceTelemetry.snapshot.collectAsStateWithLifecycle()

    Surface(
        color = Etio.colors.surface,
        shape = RoundedCornerShape(Etio.radius.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(Etio.space.card),
            verticalArrangement = Arrangement.spacedBy(Etio.space.s),
        ) {
            Stat("Backend", t.backend ?: "not loaded")
            Stat("Model load", t.engineLoadMs?.let { "$it ms" } ?: "—")
            Stat("Session prime", t.primeMs?.let { "$it ms" } ?: "—")

            t.prefixTokens.forEach { (profile, tokens) ->
                // Cached once at start-up. This many tokens is what each call no
                // longer re-decodes.
                Stat("  $profile prefix", if (tokens >= 0) "$tokens tok, cached" else "cached")
            }

            Spacer(Modifier.height(Etio.space.s))

            Latency("Classify (Job 1)", DecodeProfile.CLASSIFY, t)
            Latency("Draft (Job 2)", DecodeProfile.DRAFT, t)

            Spacer(Modifier.height(Etio.space.s))

            // The honest accuracy figure. Not a score the model gives itself — a count
            // of how often a person had to step in before confirming.
            Stat(
                "Correction rate",
                t.correctionRate?.let { rate ->
                    "${(rate * 100).roundToInt()}%  (${t.recordsEdited} of ${t.recordsConfirmed} edited)"
                } ?: "no records yet",
            )
            Stat("Parse retries", "${t.parseRetries}")
            Stat("Fell back to OTHER", "${t.parseFallbacks}")
        }
    }
}

@Composable
private fun Latency(label: String, profile: String, t: InferenceTelemetry.Snapshot) {
    val calls = t.callsFor(profile)
    Stat(
        label,
        if (calls.isEmpty()) {
            "—"
        } else {
            // Median rather than mean: one cold outlier should not be allowed to
            // describe the run, in either direction.
            "${t.medianMs(profile)} ms median · ${t.lastMs(profile)} ms last · ${calls.size} calls"
        },
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Etio.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
