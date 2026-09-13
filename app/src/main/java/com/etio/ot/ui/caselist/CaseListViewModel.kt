package com.etio.ot.ui.caselist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.ChecklistRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.di.AiModule
import com.etio.ot.di.CoreModule
import com.etio.ot.di.SafetyModule
import com.etio.ot.domain.timing.DayMetrics
import com.etio.ot.domain.timing.ScheduleProjector
import com.etio.ot.domain.timing.TimerEngine
import com.etio.ot.ui.checklist.ChecklistGateController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * OWNER: spine branch.
 *
 * Timers tick from a 1-second flow that is completely independent of the model.
 * Nothing in this ViewModel awaits inference.
 *
 * Checklist behaviour is delegated wholesale to [checklistGate] (safety branch).
 * The only contact is the two calls inside [markEvent] — keep it that way, or the
 * two branches start fighting over this file.
 */
class CaseListViewModel(
    private val cases: CaseRepository = CoreModule.caseRepository,
    private val delays: DelayRepository = AiModule.delayRepository,
    /**
     * Injectable so the screen's state can be tested at all: the default reaches
     * SafetyModule, which reaches the Room database, which needs an Android Context —
     * one default argument was the difference between this ViewModel being testable
     * and not. The default is unchanged for the app.
     */
    checklists: ChecklistRepository = SafetyModule.checklistRepository,
) : ViewModel() {

    val checklistGate = ChecklistGateController(scope = viewModelScope, checklists = checklists)

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val uiState: StateFlow<CaseListUiState> = combine(
        cases.cases,
        cases.events,
        delays.delays,
        ticker,
    ) { caseRows, eventRows, delayRows, now ->
        CaseListUiState(
            cases = caseRows,
            eventsByCase = eventRows.groupBy { it.caseId },
            delaysByCase = delayRows.groupBy { it.caseId },
            metrics = TimerEngine.compute(caseRows, eventRows),
            // Pure arithmetic off the most recent delay that actually stated a
            // duration. No model involvement, recomputed with the rest of the state.
            shift = delayRows
                .filter { it.estimatedMin != null }
                .maxByOrNull { it.createdAtMs }
                ?.let {
                    ScheduleProjector.project(caseRows, it.caseId, it.estimatedMin, now)
                },
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CaseListUiState())

    fun markEvent(caseId: String, type: EventType) {
        viewModelScope.launch {
            val marked = cases.liveEvents()
                .filter { it.caseId == caseId }
                .map { it.type }
                .toSet()

            // Safety gate first. A blocked mark is not an error — it is the product.
            if (!checklistGate.allows(caseId, type, marked)) return@launch

            cases.markEvent(caseId, type)
            checklistGate.onEventMarked(caseId, type)
        }
    }

    fun correctEvent(event: EventEntity, newTimestampMs: Long) {
        viewModelScope.launch { cases.correctEvent(event, newTimestampMs) }
    }

    fun resetDay() {
        viewModelScope.launch { cases.resetDay() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CaseListViewModel() as T
        }
    }
}

data class CaseListUiState(
    val cases: List<CaseEntity> = emptyList(),
    val eventsByCase: Map<String, List<EventEntity>> = emptyMap(),
    val delaysByCase: Map<String, List<DelayRecordEntity>> = emptyMap(),
    val metrics: DayMetrics = DayMetrics.Empty,
    /** Non-null only while a delay with a stated duration is pushing the rest of the day. */
    val shift: ScheduleProjector.Shift? = null,
    val loading: Boolean = true,
)
