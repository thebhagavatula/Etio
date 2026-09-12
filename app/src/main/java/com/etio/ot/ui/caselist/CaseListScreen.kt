package com.etio.ot.ui.caselist

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.itemsIndexed
import com.etio.ot.ui.common.EmptyState
import com.etio.ot.ui.common.HairlineSeparator
import com.etio.ot.ui.common.SectionLabel
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.etio.ot.domain.timing.DayFlow
import com.etio.ot.domain.timing.TimerEngine
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.EventSource
import com.etio.ot.data.model.EventType
import com.etio.ot.ui.checklist.ChecklistHost
import com.etio.ot.ui.events.EventGrid
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.EtioStatus
import com.etio.ot.ui.theme.backdropBlur
import com.etio.ot.ui.theme.glass
import com.etio.ot.ui.theme.rememberEtioHaptics

/** How far the day-standing chip hangs over the active card's top edge. */
private val VARIANCE_CHIP_OVERLAP = 8.dp

/** Whole minutes, plain words — it changes at most once a minute, so it sits still. */
private fun Int.asDayStanding(): String = when {
    this > 0 -> "$this min behind"
    this < 0 -> "${-this} min ahead"
    else -> "on time"
}

/** The day's standing, coloured by how far off it is. Chrome, so glass is fine behind it. */
@Composable
private fun VarianceChip(varianceMin: Int, modifier: Modifier = Modifier) {
    val tint = when {
        varianceMin <= 5 -> Etio.colors.running
        varianceMin <= 20 -> Etio.colors.warning
        else -> Etio.colors.delay
    }
    Surface(
        color = EtioStatus.tintFor(tint),
        shape = RoundedCornerShape(Etio.radius.pill),
        modifier = modifier,
    ) {
        Text(
            varianceMin.asDayStanding(),
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            modifier = Modifier.padding(horizontal = Etio.space.m, vertical = Etio.space.s),
        )
    }
}

/**
 * OWNER: spine branch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseListScreen(
    onCaptureDelay: (String) -> Unit,
    onRecordDelay: (String) -> Unit,
    onOpenMessages: (String) -> Unit,
    onOpenReport: () -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: CaseListViewModel = viewModel(factory = CaseListViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberEtioHaptics()
    val dateFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    var confirmReset by remember { mutableStateOf(false) }
    var showEventSheet by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<EventEntity?>(null) }
    val dismissedAssumptions = remember { mutableStateListOf<String>() }
    val dismissedSendFor = remember { mutableStateListOf<String>() }
    val dismissedBreaches = remember { mutableStateListOf<String>() }
    var showAllCases by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Everything the bottom bar needs, derived from the same metrics the timers use.
    // The sheet stays scoped to the active case; the primary button follows the day,
    // so a finished case's room events are still one tap away after it completes.
    val activeCase = state.cases.firstOrNull { it.id == state.metrics.activeCaseId }
    // What the screen shows. Equal to the active case all day; falls back to the last
    // case once the day is fully marked, when nothing is active any more.
    val focusCase = DayFlow.focusCaseId(state.cases, state.metrics)
        ?.let { id -> state.cases.firstOrNull { it.id == id } }
    val focusMarks = focusCase?.let { state.metrics.forCase(it.id)?.marks }.orEmpty()
    val dayComplete = DayFlow.isDayComplete(state.cases, state.metrics)
    val nextAction = DayFlow.nextAction(state.cases, state.metrics)
    val sendFor = DayFlow.sendForOffer(state.cases, state.metrics)
        ?.takeIf { it.caseId !in dismissedSendFor }
    val breach = DayFlow.breach(state.cases, state.metrics)
        ?.takeIf { it.key !in dismissedBreaches }

    // Default surface: the case she is on, and nothing else expanded.
    val otherCases = state.cases.filter { it.id != focusCase?.id }
    val visibleCases = if (showAllCases) {
        listOfNotNull(focusCase) + otherCases
    } else {
        listOfNotNull(focusCase)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                breach?.let {
                    DelayBreachBanner(
                        message = it.message,
                        caseNumber = it.caseNumber,
                        onRecord = { onRecordDelay(it.caseId) },
                        onDismiss = { dismissedBreaches.add(it.key) },
                    )
                }
                NextEventBar(
                    caseNumber = nextAction?.caseNumber,
                    nextEvent = nextAction?.event,
                    onMark = { type -> nextAction?.let { viewModel.markEvent(it.caseId, type) } },
                    onOtherEvent = { showEventSheet = true },
                    sendForLabel = sendFor?.let { "Send for case ${it.caseNumber}?" },
                    onSendFor = {
                        sendFor?.let { viewModel.markEvent(it.caseId, EventType.PATIENT_SENT_FOR) }
                    },
                    onDismissSendFor = { sendFor?.let { dismissedSendFor.add(it.caseId) } },
                    onRecord = { focusCase?.let { onCaptureDelay(it.id) } },
                )
            }
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.glass(RoundedCornerShape(0.dp)),
                title = {
                    // Between-rehearsal reset, deliberately undiscoverable: a long press
                    // on the title, then a confirmation. There is no visible control for
                    // it — one that can be brushed on stage wipes the day mid-demo.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    haptics.medium()
                                    confirmReset = true
                                },
                            )
                        },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                state.cases.firstOrNull()?.theatreId ?: "—",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                buildString {
                                    append(dateFmt.format(Date(state.metrics.computedAtMs)).uppercase())
                                    if (dayComplete) append(" · DAY COMPLETE")
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = Etio.colors.textSecondary,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenReport) {
                        Icon(Icons.Default.Assessment, contentDescription = "End-of-day report")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            // The only backdrop blur in the app: content we own, behind a sheet,
            // API 31+, and off while either model job is running.
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .backdropBlur(active = showEventSheet),
            contentPadding = PaddingValues(
                start = Etio.space.gutter,
                end = Etio.space.gutter,
                top = Etio.space.m,
                bottom = Etio.space.xxl,
            ),
            // 12dp is the within-a-group gap. Anything that starts a new group adds
            // its own 32dp, rather than every gap on the screen meaning the same thing.
            verticalArrangement = Arrangement.spacedBy(Etio.space.within),
        ) {
            state.shift?.summary?.let { summary ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            focusCase?.let { case ->
                item(key = case.id) {
                    // The one deliberate off-grid element on this screen: the day's
                    // standing sits across the top edge of the active card rather than
                    // in a row of its own. One thing breaking the grid reads as
                    // designed; three read as a mistake.
                    Box(modifier = Modifier.padding(top = VARIANCE_CHIP_OVERLAP)) {
                        ActiveCaseCard(
                        case = case,
                        metrics = state.metrics.forCase(case.id),
                        events = state.eventsByCase[case.id].orEmpty(),
                        delays = state.delaysByCase[case.id].orEmpty(),
                        onOpenDelay = onOpenMessages,
                        modifier = Modifier.fillMaxWidth(),
                            onSetEventTime = { editingEvent = it },
                            dismissedAssumptions = dismissedAssumptions.toSet(),
                            onDismissAssumption = { dismissedAssumptions.add(it) },
                        )
                        VarianceChip(
                            state.metrics.runningVarianceMin,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = -Etio.space.l, y = -VARIANCE_CHIP_OVERLAP),
                        )
                    }
                }
            }

            if (showAllCases) {
                item { Spacer(Modifier.height(Etio.space.section - Etio.space.within)) }
                item { SectionLabel("The rest of the list") }
                itemsIndexed(otherCases, key = { _, c -> c.id }) { index, case ->
                    Column {
                        // Borderless. A hairline is all the separation a secondary row
                        // needs, and every card chrome removed here is weight the
                        // active case gets to keep.
                        if (index > 0) HairlineSeparator()
                        CaseRow(case = case, metrics = state.metrics.forCase(case.id))
                    }
                }
            }
            if (otherCases.isNotEmpty()) {
                item {
                    TextButton(onClick = { showAllCases = !showAllCases }) {
                        Text(
                            if (showAllCases) {
                                "Hide the rest of the list"
                            } else {
                                "All cases (${state.cases.size})"
                            },
                        )
                    }
                }
            }

            if (state.cases.isEmpty()) {
                item {
                    EmptyState(
                        line = "No cases on the list yet.",
                        actionLabel = "Load today's theatre day",
                        onAction = viewModel::resetDay,
                    )
                }
            } else {
                item { Spacer(Modifier.height(Etio.space.section - Etio.space.within)) }
                item {
                    Text(
                        "The button at the bottom marks the clock. Tap the mic only when something has gone wrong.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Etio.colors.textSecondary,
                    )
                }
            }
        }
    }

    // Out-of-order marking, one tap away. The grid is unchanged — it is simply no
    // longer the thing she has to read before she can mark the obvious next event.
    if (showEventSheet && focusCase != null) {
        ModalBottomSheet(
            onDismissRequest = { showEventSheet = false },
            sheetState = sheetState,
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    "Case ${focusCase.caseNumber} · mark any event",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "For marking out of order, or catching up after the fact.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 12.dp))
                EventGrid(
                    marked = focusMarks,
                    nextExpected = TimerEngine.nextExpectedEvent(focusMarks)
                        ?: EventType.PATIENT_SENT_FOR,
                    onMark = { type ->
                        viewModel.markEvent(focusCase.id, type)
                        showEventSheet = false
                    },
                    onLongPress = { type ->
                        state.eventsByCase[focusCase.id].orEmpty()
                            .firstOrNull { it.type == type }
                            ?.let { editingEvent = it }
                    },
                    inferred = state.eventsByCase[focusCase.id].orEmpty()
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
