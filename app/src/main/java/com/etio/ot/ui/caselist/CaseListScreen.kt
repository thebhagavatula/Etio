package com.etio.ot.ui.caselist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.etio.ot.core.formatSignedMinutes
import com.etio.ot.ui.checklist.ChecklistHost

/**
 * OWNER: spine branch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseListScreen(
    onCaptureDelay: (String) -> Unit,
    onOpenMessages: (String) -> Unit,
    onOpenReport: () -> Unit,
    viewModel: CaseListViewModel = viewModel(factory = CaseListViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Today's list", style = MaterialTheme.typography.titleLarge)
                        val variance = state.metrics.runningVarianceMin
                        Text(
                            text = buildString {
                                append(state.cases.firstOrNull()?.theatreId ?: "—")
                                append(" · running ")
                                append(variance.formatSignedMinutes())
                                state.metrics.firstCaseStartDelayMin?.let {
                                    append(" · first case ${it.formatSignedMinutes()}")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenReport) {
                        Icon(Icons.Default.Assessment, contentDescription = "End-of-day report")
                    }
                    IconButton(onClick = viewModel::resetDay) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset day")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.cases, key = { it.id }) { case ->
                CaseCard(
                    case = case,
                    metrics = state.metrics.forCase(case.id),
                    events = state.eventsByCase[case.id].orEmpty(),
                    delays = state.delaysByCase[case.id].orEmpty(),
                    isActive = case.id == state.metrics.activeCaseId,
                    onMarkEvent = { type -> viewModel.markEvent(case.id, type) },
                    onCorrectEvent = viewModel::correctEvent,
                    onCaptureDelay = { onCaptureDelay(case.id) },
                    onOpenDelay = onOpenMessages,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "Tap marks the clock. Hold the mic only when something has gone wrong.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // The spine branch's entire contact with the WHO checklist.
    ChecklistHost(viewModel.checklistGate, snackbar)
}
