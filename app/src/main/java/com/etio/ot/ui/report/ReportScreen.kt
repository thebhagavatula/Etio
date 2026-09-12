package com.etio.ot.ui.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.etio.ot.data.model.Avoidability
import com.etio.ot.domain.report.EndOfDayReport
import com.etio.ot.ui.report.ReportViewModel.Compliance
import com.etio.ot.ui.report.ReportViewModel.Provenance
import com.etio.ot.ui.report.ReportViewModel.UiState
import com.etio.ot.ui.theme.EtioStatus
import kotlinx.coroutines.launch

/**
 * PRD §7 F7. The screen that has to survive being read by the person who lost the
 * minutes, so every figure is followed by where it came from: a spoken estimate, a
 * clock, or an app-filled gap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    onBack: () -> Unit,
    viewModel: ReportViewModel = viewModel(factory = ReportViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ready = uiState is UiState.Success

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "End of day",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Recalculate")
                    }
                    IconButton(
                        onClick = {
                            copyToClipboard(context, viewModel.asPlainText())
                            // Confirm it, because a clipboard write is invisible and this
                            // one is the handover into the hospital's own systems.
                            scope.launch { snackbar.showSnackbar("Report copied") }
                        },
                        enabled = ready,
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy report")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is UiState.Loading -> Centered { CircularProgressIndicator() }

                is UiState.Error -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { viewModel.refresh() }) { Text("Try again") }
                    }
                }

                is UiState.Success -> ReportContent(
                    report = state.report,
                    headline = state.headline,
                    compliance = state.compliance,
                    provenance = state.provenance,
                )
            }
        }
    }
}

@Composable
private fun ReportContent(
    report: EndOfDayReport,
    headline: String,
    compliance: Compliance,
    provenance: Provenance,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HeadlineCard(report, headline) }

        item { ComplianceCard(compliance) }

        if (provenance.appFilled > 0 || provenance.corrected > 0) {
            item { ProvenanceNote(provenance) }
        }

        if (report.byCode.isNotEmpty()) {
            item { SectionTitle("Why the time went") }
            items(report.byCode, key = { it.code.name }) { row ->
                ShareRow(
                    label = row.code.display,
                    minutes = row.minutes,
                    occurrences = row.occurrences,
                    fraction = row.minutes.share(report.lostMinutes),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (report.byDept.isNotEmpty()) {
            item { SectionTitle("Where the time went") }
            items(report.byDept, key = { it.dept }) { row ->
                ShareRow(
                    label = row.dept,
                    minutes = row.minutes,
                    occurrences = row.occurrences,
                    fraction = row.minutes.share(report.lostMinutes),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        if (report.attributions.isEmpty()) {
            item {
                Panel {
                    Text(
                        "No delays logged yet. Every minute in this report will trace back " +
                            "to something someone said.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            item { SectionTitle("Every lost minute, attributed") }
            items(report.attributions, key = { it.delayId }) { a ->
                Panel(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Case ${a.caseNumber} · ${a.code.display}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${a.minutes} min",
                            style = MaterialTheme.typography.titleMedium.tabularFigures(),
                            fontWeight = FontWeight.Bold,
                            color = EtioStatus.late,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(a.dept)
                            append(" · ")
                            append(if (a.measured) "measured from the clock" else "as stated")
                            if (a.avoidable == Avoidability.AVOIDABLE) append(" · avoidable")
                            if (a.avoidable == Avoidability.UNCLEAR) append(" · avoidability unclear")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    // The transcript is the grounding evidence, not decoration: it is the
                    // answer to "who decided this was CSSD's fault".
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp)
                    ) {
                        Text(
                            "“${a.transcript}”",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadlineCard(report: EndOfDayReport, headline: String) {
    Panel(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), padding = 20.dp) {
        Text(
            report.theatreId,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            headline,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stat("Cases", "${report.casesCompleted}/${report.casesScheduled}")
                Stat("Planned", "${report.scheduledMinutes}m")
                Stat("Actual", "${report.actualMinutes}m")
                Stat("Lost", "${report.lostMinutes}m", EtioStatus.late)
            }
        }

        report.firstCaseStartDelayMin?.let { late ->
            Spacer(Modifier.height(12.dp))
            Caption(
                if (late > 0) "First case started $late min after its scheduled time."
                else "First case started on time.",
            )
        }
        if (report.lostMinutes > 0) {
            Spacer(Modifier.height(4.dp))
            Caption(
                "${report.avoidableMinutes} of ${report.lostMinutes} lost minutes were " +
                    "judged avoidable.",
            )
        }
    }
}

/**
 * The checklist half of the report. A day can finish on time and still have skipped a
 * Time Out, and that is precisely the day this card exists to make undeniable.
 */
@Composable
private fun ComplianceCard(compliance: Compliance) {
    val open = compliance.outstanding > 0
    val accent = when {
        open -> EtioStatus.late
        compliance.skipped > 0 -> EtioStatus.warning
        compliance.due > 0 -> EtioStatus.onTime
        else -> EtioStatus.idle
    }

    Panel(color = accent.copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (open) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "WHO checklist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (compliance.due == 0) "—" else "${compliance.confirmed}/${compliance.due}",
                style = MaterialTheme.typography.titleMedium.tabularFigures(),
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }

        Spacer(Modifier.height(10.dp))

        if (compliance.due == 0) {
            Caption("No phase came due today — no case reached a trigger event.")
            return@Panel
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            compliance.phases.forEach { p ->
                val tint = when {
                    p.due == 0 -> EtioStatus.idle
                    p.outstanding > 0 -> EtioStatus.late
                    p.skipped > 0 -> EtioStatus.warning
                    else -> EtioStatus.onTime
                }
                Surface(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            p.phase.display,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            if (p.due == 0) "—" else "${p.confirmed}/${p.due}",
                            style = MaterialTheme.typography.titleMedium.tabularFigures(),
                            fontWeight = FontWeight.Bold,
                            color = tint,
                        )
                    }
                }
            }
        }

        if (open) {
            Spacer(Modifier.height(10.dp))
            Text(
                "${compliance.outstanding} phase${if (compliance.outstanding == 1) "" else "s"} " +
                    "came due and was never completed or skipped.",
                style = MaterialTheme.typography.bodyMedium,
                color = EtioStatus.late,
            )
        }

        if (compliance.skips.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Skipped with a reason",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            compliance.skips.forEach { s ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "Case ${s.caseNumber} · ${s.phase.display} — ${s.reason}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (compliance.clean) {
            Spacer(Modifier.height(8.dp))
            Caption("Every phase that came due was confirmed in full.")
        }
    }
}

/**
 * Says out loud how much of the timing the app supplied. Without this the report reads
 * as if a human stood there with a stopwatch for all of it.
 */
@Composable
private fun ProvenanceNote(provenance: Provenance) {
    Panel(color = EtioStatus.warning.copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = EtioStatus.warning,
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    "How these times were recorded",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("${provenance.total} event timestamps. ")
                        if (provenance.appFilled > 0) {
                            append(
                                "${provenance.appFilled} were filled in by the app when a " +
                                    "later event was marked over a missing one, so durations " +
                                    "built on them are approximate. "
                            )
                        }
                        if (provenance.corrected > 0) {
                            append("${provenance.corrected} were corrected after the fact.")
                        }
                    }.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShareRow(
    label: String,
    minutes: Int,
    occurrences: Int,
    fraction: Float,
    color: Color,
) {
    Panel(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$minutes min",
                style = MaterialTheme.typography.titleMedium.tabularFigures(),
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
        Spacer(Modifier.height(8.dp))
        ShareBar(fraction, color)
        Spacer(Modifier.height(6.dp))
        Caption("$occurrences occurrence${if (occurrences == 1) "" else "s"}")
    }
}

/**
 * Hand-rolled rather than a LinearProgressIndicator: this is a share of a total, not
 * progress toward one, and the stop indicator a determinate M3 bar draws at its end
 * reads as a target that does not exist here.
 */
@Composable
private fun ShareBar(fraction: Float, color: Color) {
    var play by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { play = true }
    val width by animateFloatAsState(
        targetValue = if (play) fraction.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "share",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // fillMaxWidth rejects 0f, and an empty share is a real state here.
        if (width > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(width)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.tabularFigures(),
            fontWeight = FontWeight.Bold,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Panel(
    color: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)),
        color = color,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun Int.share(total: Int): Float = if (total <= 0) 0f else this.toFloat() / total

/**
 * Tabular figures, so a column of minute totals lines up on its digits and a changing
 * value does not reflow its own row.
 *
 * Kept private to this file on purpose. The theme is spine-owned and may or may not
 * offer an equivalent depending on how far this branch has caught up; a local
 * definition means the report renders identically either way and adds nothing to the
 * merge.
 */
private fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("End of day report", text))
}
