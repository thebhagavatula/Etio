package com.etio.ot.ui.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.etio.ot.ui.theme.EtioStatus
import com.etio.ot.domain.report.EndOfDayReport
import com.etio.ot.ui.report.ReportViewModel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    onBack: () -> Unit,
    viewModel: ReportViewModel = viewModel(factory = ReportViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("End of Day Report", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.asPlainText().let { copy(context, it) } },
                        enabled = uiState is UiState.Success
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy report")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Crossfade(targetState = uiState, label = "ReportState") { state ->
                when (state) {
                    is UiState.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is UiState.Error -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.padding(8.dp))
                                Text(state.message, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    is UiState.Success -> {
                        ReportContent(report = state.report, headline = state.headline)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportContent(report: EndOfDayReport, headline: String) {
    var animateProgress by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        animateProgress = true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                tonalElevation = 0.dp
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(headline, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.padding(top = 16.dp))
                    
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Stat("Cases", "${report.casesCompleted}/${report.casesScheduled}")
                            Stat("Scheduled", "${report.scheduledMinutes}m")
                            Stat("Actual", "${report.actualMinutes}m")
                            Stat("Lost", "${report.lostMinutes}m", EtioStatus.late)
                        }
                    }

                    report.firstCaseStartDelayMin?.let {
                        Spacer(Modifier.padding(top = 12.dp))
                        Text(
                            "First case started $it min after its scheduled time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (report.lostMinutes > 0) {
                        Spacer(Modifier.padding(top = 4.dp))
                        Text(
                            "${report.avoidableMinutes} of ${report.lostMinutes} lost minutes were judged avoidable.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (report.byDept.isNotEmpty()) {
            item { SectionTitle("Where the time went") }
            items(report.byDept, key = { it.dept }) { row ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.dept, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${row.minutes} min",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.padding(top = 8.dp))
                        
                        val targetProgress = if (report.lostMinutes == 0) 0f else row.minutes.toFloat() / report.lostMinutes
                        val animatedProgress by animateFloatAsState(
                            targetValue = if (animateProgress) targetProgress else 0f,
                            animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
                            label = "Progress"
                        )
                        
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        Spacer(Modifier.padding(top = 6.dp))
                        Text(
                            "${row.occurrences} occurrence${if (row.occurrences == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (report.attributions.isNotEmpty()) {
            item { SectionTitle("Every lost minute, attributed") }
            items(report.attributions, key = { it.delayId }) { a ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row {
                            Text(
                                "Case ${a.caseNumber} · ${a.code.display}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Spacer(Modifier.weight(1f))
                            Text("${a.minutes} min", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            "${a.dept} · ${if (a.measured) "measured from the clock" else "as stated"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.padding(top = 12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(12.dp)
                        ) {
                            Text("“${a.transcript}”", style = MaterialTheme.typography.bodyMedium, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        }
                    }
                }
            }
        }

        if (report.attributions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No delays logged yet. Every minute in this report will trace back to something someone said.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
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
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("End of day report", text))
}
