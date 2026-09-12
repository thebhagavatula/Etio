package com.etio.ot.ui.caselist

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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
    val haptics = LocalHapticFeedback.current
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    // Between-rehearsal reset, deliberately undiscoverable: a long press
                    // on the title, then a confirmation. There is no visible control for
                    // it — one that can be brushed on stage wipes the day mid-demo.
                    Column(
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    confirmReset = true
                                },
                            )
                        },
                    ) {
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
                        "Tap marks the clock. Tap the mic only when something has gone wrong.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the day?") },
            text = {
                Text(
                    "Clears every event, delay, checklist and drafted message, then " +
                        "reloads the seeded list. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        viewModel.resetDay()
                    },
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }

    // The spine branch's entire contact with the WHO checklist.
    ChecklistHost(viewModel.checklistGate, snackbar)
}
