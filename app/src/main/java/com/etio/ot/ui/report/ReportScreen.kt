package com.etio.ot.ui.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.EtioStatus

/**
 * PRD §7 F7. Every lost minute carries an attributed cause, and the verbatim
 * utterance is one scroll away from the number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    onBack: () -> Unit,
    viewModel: ReportViewModel = viewModel(factory = ReportViewModel.Factory),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val headline by viewModel.headline.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("End of day", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { copy(context, viewModel.asPlainText()) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy report")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Etio.colors.surface),
                    shape = RoundedCornerShape(Etio.radius.card),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Etio.space.card)) {
                        // One number, larger than anything else in the app. Everything
                        // below exists to say where it came from.
                        Text(
                            "MINUTES LOST",
                            style = MaterialTheme.typography.labelLarge,
                            color = Etio.colors.textSecondary,
                        )
                        Text(
                            "${report.lostMinutes}",
                            style = MaterialTheme.typography.displaySmall,
                            color = Etio.colors.delay,
                        )
                        Spacer(Modifier.padding(top = 8.dp))
                        Text(headline, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.padding(top = 10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Stat("Cases", "${report.casesCompleted}/${report.casesScheduled}")
                            Stat("Scheduled", "${report.scheduledMinutes}m")
                            Stat("Actual", "${report.actualMinutes}m")
                        }
                        report.firstCaseStartDelayMin?.let {
                            Spacer(Modifier.padding(top = 8.dp))
                            Text(
                                "First case started $it min after its scheduled time.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (report.lostMinutes > 0) {
                            Spacer(Modifier.padding(top = 8.dp))
                            Text(
                                "${report.avoidableMinutes} of ${report.lostMinutes} lost minutes were judged avoidable.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (report.byCode.isNotEmpty()) {
                item { SectionTitle("Where the time went") }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Etio.colors.surface),
                        shape = RoundedCornerShape(Etio.radius.card),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(Etio.space.card)) {
                            StackedBar(report.byCode, report.lostMinutes)
                            Spacer(Modifier.padding(top = 14.dp))
                            report.byCode.forEachIndexed { index, row ->
                                LegendRow(
                                    swatch = codeColour(index),
                                    label = row.code.display,
                                    // Attribution belongs on the row, not in a separate
                                    // table someone has to cross-reference.
                                    dept = report.attributions
                                        .filter { it.code == row.code }
                                        .groupBy { it.dept }
                                        .maxByOrNull { (_, rows) -> rows.sumOf { it.minutes } }
                                        ?.key,
                                    minutes = row.minutes,
                                    occurrences = row.occurrences,
                                )
                            }
                        }
                    }
                }
            }

            if (report.attributions.isNotEmpty()) {
                item { SectionTitle("Every lost minute, attributed") }
                items(report.attributions, key = { it.delayId }) { a ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row {
                                Text(
                                    "Case ${a.caseNumber} · ${a.code.display}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.weight(1f))
                                Text("${a.minutes} min", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                "${a.dept} · ${if (a.measured) "measured from the clock" else "as stated"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.padding(top = 8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.padding(top = 8.dp))
                            Text("“${a.transcript}”", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (report.attributions.isEmpty()) {
                item {
                    Text(
                        "No delays logged yet. Every minute in this report will trace back to something someone said.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color? = null) {
    Column {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = Etio.colors.textSecondary,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = color ?: Etio.colors.textPrimary,
        )
    }
}

/**
 * The whole day's lost time in one bar, segmented by cause. Opaque, no animation —
 * this is a figure to read, and it is the last thing anyone sees.
 */
@Composable
private fun StackedBar(rows: List<com.etio.ot.domain.report.CodeTotal>, total: Int) {
    if (total <= 0) return
    Row(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        rows.forEachIndexed { index, row ->
            Box(
                Modifier
                    .weight(row.minutes.coerceAtLeast(1).toFloat())
                    .fillMaxHeight()
                    .background(codeColour(index)),
            )
        }
    }
}

@Composable
private fun LegendRow(
    swatch: androidx.compose.ui.graphics.Color,
    label: String,
    dept: String?,
    minutes: Int,
    occurrences: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(swatch),
        )
        Spacer(Modifier.padding(start = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    dept?.let { append(it.uppercase()).append(" · ") }
                    append("$occurrences OCCURRENCE")
                    if (occurrences != 1) append("S")
                },
                style = MaterialTheme.typography.labelLarge,
                color = Etio.colors.textSecondary,
            )
        }
        Text("$minutes min", style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Cause colours. Deliberately excludes the safety hue, which belongs to the WHO
 * checklist and appears nowhere else in the app.
 */
@Composable
private fun codeColour(index: Int): androidx.compose.ui.graphics.Color {
    val palette = listOf(
        Etio.colors.delay,
        Etio.colors.warning,
        Etio.colors.accent,
        Etio.colors.running,
        Etio.colors.textSecondary,
    )
    return palette[index % palette.size]
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
    )
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("End of day report", text))
}
