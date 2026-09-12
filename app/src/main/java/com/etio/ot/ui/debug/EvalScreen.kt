package com.etio.ot.ui.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.etio.ot.ai.EvalHarness
import com.etio.ot.data.model.DelayCode
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.EtioMonoStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The deliverable: every claim in this project as a number the phone produced, in
 * airplane mode, while someone watched.
 *
 * Debug-only and deliberately hard to reach — it is not part of the demo path, and
 * a judge finding it by accident mid-story is worse than a judge never seeing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvalScreen(
    onBack: () -> Unit,
    viewModel: EvalViewModel = viewModel(factory = EvalViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Etio.colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Eval harness", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Etio.space.gutter),
            verticalArrangement = Arrangement.spacedBy(Etio.space.m),
        ) {
            item {
                Text(
                    "${state.itemCount} gold-labelled utterances, none of them few-shot examples. " +
                        "Runs the real Job 1 path and the real grounding verifier.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Etio.colors.textSecondary,
                )
            }

            item {
                if (state.running) {
                    Column {
                        Text(state.progressLabel, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(Etio.space.s))
                        LinearProgressIndicator(
                            progress = { state.progressFraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = Etio.colors.accent,
                        )
                        Spacer(Modifier.height(Etio.space.s))
                        TextButton(onClick = viewModel::cancel) { Text("Cancel") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Etio.space.s)) {
                        Button(
                            onClick = { viewModel.start(EvalHarness.Mode.entries.toList()) },
                            shape = RoundedCornerShape(Etio.radius.pill),
                            colors = ButtonDefaults.buttonColors(containerColor = Etio.colors.accent),
                        ) { Text("Run A/B/C") }
                        TextButton(
                            onClick = { viewModel.start(listOf(EvalHarness.Mode.CACHED_PREFIX)) },
                        ) { Text("Single mode") }
                    }
                }
            }

            state.error?.let { err ->
                item {
                    Text(err, style = MaterialTheme.typography.bodyMedium, color = Etio.colors.delay)
                }
            }

            val run = state.run
            if (run == null) {
                item {
                    Text(
                        "No run yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Etio.colors.textSecondary,
                    )
                }
            } else {
                item { RunHeader(run) }
                item { Comparison(run) }

                run.modes.forEach { mode ->
                    item { ModeDetail(mode) }
                }

                run.modes.lastOrNull()?.let { mode ->
                    item { ConfusionMatrix(mode) }
                }
            }
        }
    }
}

@Composable
private fun RunHeader(run: EvalHarness.Run) {
    val fmt = remember0 { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }
    Text(
        "${fmt.format(Date(run.startedAtMs))} · ${run.backend} · " +
            if (run.prefixCached) "prefix caching active" else "prefix caching UNAVAILABLE on this backend",
        style = MaterialTheme.typography.labelLarge,
        color = Etio.colors.textSecondary,
    )
}

/** The three columns side by side. This is the artefact. */
@Composable
private fun Comparison(run: EvalHarness.Run) {
    Surface(
        color = Etio.colors.surface,
        shape = RoundedCornerShape(Etio.radius.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Etio.space.l)) {
            Row {
                Cell("", weight = 1.4f, header = true)
                run.modes.forEach { Cell(label(it.mode), header = true) }
            }
            Row {
                Cell("Accuracy", weight = 1.4f)
                run.modes.forEach { Cell("${(it.accuracy * 100).toInt()}%") }
            }
            Row {
                Cell("p50 latency", weight = 1.4f)
                run.modes.forEach { Cell("${it.p50Ms}ms") }
            }
            Row {
                Cell("p95 latency", weight = 1.4f)
                run.modes.forEach { Cell("${it.p95Ms}ms") }
            }
            Row {
                Cell("Parse failures", weight = 1.4f)
                run.modes.forEach { Cell("${(it.parseFailureRate * 100).toInt()}%") }
            }
            Row {
                Cell("Grounding refusals", weight = 1.4f)
                run.modes.forEach { Cell("${(it.groundingRejectionRate * 100).toInt()}%") }
            }
            Row {
                Cell("Invented durations", weight = 1.4f)
                run.modes.forEach { Cell("${it.hallucinatedMinutes}") }
            }
        }
    }
}

@Composable
private fun ModeDetail(mode: EvalHarness.ModeResult) {
    Surface(
        color = Etio.colors.surface,
        shape = RoundedCornerShape(Etio.radius.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Etio.space.l)) {
            Text(label(mode.mode), style = MaterialTheme.typography.titleMedium)
            Text(
                "${mode.correct}/${mode.total} correct · mean ${mode.meanMs}ms",
                style = MaterialTheme.typography.labelLarge,
                color = Etio.colors.textSecondary,
            )

            mode.caveat?.let {
                Spacer(Modifier.height(Etio.space.s))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Etio.colors.warning)
            }

            if (mode.agreementCounts.isNotEmpty()) {
                Spacer(Modifier.height(Etio.space.s))
                Text(
                    "Agreement " + mode.agreementCounts.entries.joinToString("  ") { "${it.key} ${it.value}" },
                    style = MaterialTheme.typography.labelLarge,
                    color = Etio.colors.textSecondary,
                )
            }

            Spacer(Modifier.height(Etio.space.m))
            mode.perClass().filter { it.value.support > 0 }.forEach { (code, m) ->
                Text(
                    "${code.padEnd(26).take(26)} P ${pct(m.precision)}  R ${pct(m.recall)}  n=${m.support}",
                    style = EtioMonoStyle.copy(fontSize = MaterialTheme.typography.labelLarge.fontSize),
                    color = Etio.colors.textPrimary,
                )
            }

            val misses = mode.items.filterNot { it.correct }
            if (misses.isNotEmpty()) {
                Spacer(Modifier.height(Etio.space.m))
                Text(
                    "MISSES",
                    style = MaterialTheme.typography.labelLarge,
                    color = Etio.colors.textSecondary,
                )
                misses.forEach {
                    Text(
                        "${it.id}  ${it.expected} → ${it.actual}",
                        style = EtioMonoStyle.copy(fontSize = MaterialTheme.typography.labelLarge.fontSize),
                        color = Etio.colors.delay,
                    )
                }
            }
        }
    }
}

/** The full square, so the gaps are as visible as the hits. */
@Composable
private fun ConfusionMatrix(mode: EvalHarness.ModeResult) {
    val codes = DelayCode.entries.map { it.name }
    val matrix = mode.confusion()

    Surface(
        color = Etio.colors.surface,
        shape = RoundedCornerShape(Etio.radius.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Etio.space.l)) {
            Text("Confusion · ${label(mode.mode)}", style = MaterialTheme.typography.titleMedium)
            Text(
                "rows = gold, columns = predicted",
                style = MaterialTheme.typography.labelLarge,
                color = Etio.colors.textSecondary,
            )
            Spacer(Modifier.height(Etio.space.s))

            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    Row {
                        Box6("")
                        codes.forEach { Box6(it.take(3)) }
                    }
                    codes.forEach { expected ->
                        Row {
                            Box6(expected.take(3))
                            codes.forEach { actual ->
                                val n = matrix[expected]?.get(actual) ?: 0
                                Box6(
                                    text = if (n == 0) "·" else "$n",
                                    highlight = n > 0 && expected == actual,
                                    wrong = n > 0 && expected != actual,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Box6(text: String, highlight: Boolean = false, wrong: Boolean = false) {
    Text(
        text,
        style = EtioMonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
        color = when {
            highlight -> Etio.colors.running
            wrong -> Etio.colors.delay
            else -> Etio.colors.textSecondary
        },
        textAlign = TextAlign.Center,
        modifier = Modifier.width(30.dp).padding(vertical = 2.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String,
    weight: Float = 1f,
    header: Boolean = false,
) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
        color = if (header) Etio.colors.accent else Etio.colors.textPrimary,
        modifier = Modifier.weight(weight).padding(vertical = 4.dp),
    )
}

private fun label(mode: String): String =
    EvalHarness.Mode.entries.firstOrNull { it.name == mode }?.label ?: mode

private fun pct(v: Float): String = "${(v * 100).toInt()}%".padStart(4)

/** remember without importing the whole runtime surface twice. */
@Composable
private fun <T> remember0(factory: () -> T): T = androidx.compose.runtime.remember(calculation = factory)
