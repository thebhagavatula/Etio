package com.etio.ot.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
fun DiagnosticsSection(
    splashDurationMs: Long? = null,
    onSplashDurationChange: (Long?) -> Unit = {},
) {
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
                // Just the size here. Whether it is cached is one fact about the
                // backend, not a property of each profile, so it is said once below
                // instead of repeated on every row — which is what made these values
                // long enough to crush the labels.
                Stat(
                    label = "$profile prefix",
                    value = if (tokens >= 0) "$tokens tok" else "size unknown",
                    indented = true,
                )
            }
            if (t.prefixTokens.isNotEmpty()) {
                // Full width, because it is a sentence. Claiming a cache hit on a
                // backend that cannot clone would leave the timings below unexplainable.
                Note(
                    if (t.prefixCached) {
                        "Prefixes are primed once and cloned per call."
                    } else {
                        "Prefix caching unavailable — this backend cannot clone sessions, " +
                            "so each prefix is re-sent on every call."
                    },
                )
            }

            Spacer(Modifier.height(Etio.space.within))

            Latency("Classify (Job 1)", DecodeProfile.CLASSIFY, t)
            Latency("Draft (Job 2)", DecodeProfile.DRAFT, t)

            Spacer(Modifier.height(Etio.space.within))

            // The honest accuracy figure. Not a score the model gives itself — a count
            // of how often a person had to step in before confirming.
            Stat(
                "Correction rate",
                t.correctionRate?.let { "${(it * 100).roundToInt()}%" } ?: "no records yet",
            )
            t.correctionRate?.let {
                Note("${t.recordsEdited} of ${t.recordsConfirmed} confirmed records were edited first.")
            }
            Stat("Parse retries", "${t.parseRetries}")
            Stat("Fell back to OTHER", "${t.parseFallbacks}")

            Spacer(Modifier.height(Etio.space.within))

            // The one moment you want the splash shorter is the one moment you cannot
            // rebuild: standing at the podium about to present.
            Text(
                "Splash duration",
                style = MaterialTheme.typography.bodyMedium,
                color = Etio.colors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Etio.space.s)) {
                SplashOption("Default", splashDurationMs == null) { onSplashDurationChange(null) }
                listOf(0L, 1500L, 2800L, 5000L).forEach { ms ->
                    SplashOption(
                        label = if (ms == 0L) "Off" else "${ms / 1000.0}s",
                        selected = splashDurationMs == ms,
                    ) { onSplashDurationChange(ms) }
                }
            }
            Note("Applies on next launch. The splash is also tap-to-skip.")
        }
    }
}

@Composable
private fun SplashOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Etio.colors.accentTint else Etio.colors.background,
        shape = RoundedCornerShape(Etio.radius.pill),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Etio.colors.accent else Etio.colors.textSecondary,
            modifier = Modifier.padding(horizontal = Etio.space.m, vertical = Etio.space.s),
        )
    }
}

@Composable
private fun Latency(label: String, profile: String, t: InferenceTelemetry.Snapshot) {
    val calls = t.callsFor(profile)
    if (calls.isEmpty()) {
        Stat(label, "—")
        return
    }
    // Median rather than mean: one cold outlier should not be allowed to describe the
    // run, in either direction.
    Stat(label, "${t.medianMs(profile)} ms")
    Note("median of ${calls.size} calls · last ${t.lastMs(profile)} ms")
}

/**
 * One measurement, label left and value right.
 *
 * Both columns are weighted. Giving the label the only weight and letting the value
 * size itself is what broke this screen: a value long enough to fill the row squeezed
 * the label to a single character and set it one letter per line. Fixed shares mean
 * neither column can collapse, and a long value wraps within its own half instead of
 * taking the other one's space.
 */
@Composable
private fun Stat(label: String, value: String, indented: Boolean = false) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Etio.colors.textSecondary,
            // A real indent, not leading spaces in the string — those collapse
            // differently depending on the font and disappear entirely when wrapped.
            modifier = Modifier
                .weight(LABEL_SHARE)
                .padding(start = if (indented) Etio.space.m else 0.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(VALUE_SHARE),
        )
    }
}

/** A sentence rather than a measurement: full width, quiet, and allowed to wrap. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Etio.colors.textSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val LABEL_SHARE = 0.45f
private const val VALUE_SHARE = 0.55f
