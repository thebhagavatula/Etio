package com.etio.ot.ui.caselist

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.etio.ot.domain.timing.TimerEngine
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.EventSource
import com.etio.ot.data.model.EventType
import com.etio.ot.ui.checklist.ChecklistHost
import com.etio.ot.ui.events.EventGrid

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
    var showEventSheet by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<EventEntity?>(null) }
    val dismissedAssumptions = remember { mutableStateListOf<String>() }
    val sheetState = rememberModalBottomSheetState()

    // Everything the bottom bar needs, derived from the same metrics the timers use.
    val activeCase = state.cases.firstOrNull { it.id == state.metrics.activeCaseId }
    val activeMarks = activeCase?.let { state.metrics.forCase(it.id)?.marks }.orEmpty()
    val nextEvent = activeCase?.let { TimerEngine.nextExpectedEvent(activeMarks) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NextEventBar(
                caseNumber = activeCase?.caseNumber,
                nextEvent = nextEvent,
                onMark = { type -> activeCase?.let { viewModel.markEvent(it.id, type) } },
                onOtherEvent = { showEventSheet = true },
            )
        },
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
                    onCaptureDelay = { onCaptureDelay(case.id) },
                    onOpenDelay = onOpenMessages,
                    modifier = Modifier.fillMaxWidth(),
                    onSetEventTime = { editingEvent = it },
                    dismissedAssumptions = dismissedAssumptions.toSet(),
                    onDismissAssumption = { dismissedAssumptions.add(it) },
                )
            }
            item {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "The button at the bottom marks the clock. Tap the mic only when something has gone wrong.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // Out-of-order marking, one tap away. The grid is unchanged — it is simply no
    // longer the thing she has to read before she can mark the obvious next event.
    if (showEventSheet && activeCase != null) {
        ModalBottomSheet(
            onDismissRequest = { showEventSheet = false },
            sheetState = sheetState,
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    "Case ${activeCase.caseNumber} · mark any event",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "For marking out of order, or catching up after the fact.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 12.dp))
                EventGrid(
                    marked = activeMarks,
                    nextExpected = nextEvent ?: EventType.PATIENT_SENT_FOR,
                    onMark = { type ->
                        viewModel.markEvent(activeCase.id, type)
                        showEventSheet = false
                    },
                    onLongPress = { type ->
                        state.eventsByCase[activeCase.id].orEmpty()
                            .firstOrNull { it.type == type }
                            ?.let { editingEvent = it }
                    },
                    inferred = state.eventsByCase[activeCase.id].orEmpty()
                        .filter { it.source == EventSource.INFERRED }
                        .map { it.type }
                        .toSet(),
                )
            }
        }
    }

    editingEvent?.let { event ->
        EventTimeDialog(
            event = event,
            onDismiss = { editingEvent = null },
            onConfirm = { correctedMs ->
                viewModel.correctEvent(event, correctedMs)
                dismissedAssumptions.add(event.id)
                editingEvent = null
            },
        )
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
