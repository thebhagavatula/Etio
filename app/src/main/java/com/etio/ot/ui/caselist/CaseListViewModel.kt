package com.etio.ot.ui.caselist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.ChecklistRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.di.ServiceLocator
import com.etio.ot.domain.timing.DayMetrics
import com.etio.ot.domain.timing.TimerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Timers tick here, from a 1-second flow that is completely independent of the model.
 * Nothing in this ViewModel awaits inference.
 */
class CaseListViewModel(
    private val cases: CaseRepository = ServiceLocator.caseRepository,
    private val delays: DelayRepository = ServiceLocator.delayRepository,
    private val checklists: ChecklistRepository = ServiceLocator.checklistRepository,
) : ViewModel() {

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    private val _openChecklist = MutableStateFlow<ChecklistPrompt?>(null)
    val openChecklist: StateFlow<ChecklistPrompt?> = _openChecklist.asStateFlow()

    private val _blockedMessage = MutableStateFlow<String?>(null)
    val blockedMessage: StateFlow<String?> = _blockedMessage.asStateFlow()

    val uiState: StateFlow<CaseListUiState> = combine(
        cases.cases,
        cases.events,
        delays.delays,
        ticker,
    ) { caseRows, eventRows, delayRows, _ ->
        val metrics = TimerEngine.compute(caseRows, eventRows)
        CaseListUiState(
            cases = caseRows,
            eventsByCase = eventRows.groupBy { it.caseId },
            delaysByCase = delayRows.groupBy { it.caseId },
            metrics = metrics,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CaseListUiState())

    fun markEvent(caseId: String, type: EventType) {
        viewModelScope.launch {
            val marked = cases.liveEvents()
                .filter { it.caseId == caseId }
                .map { it.type }
                .toSet()

            // Safety gate first. A blocked phase is not an error — it is the product.
            checklists.gateFor(caseId, type, marked)?.let { phase ->
                _blockedMessage.value = "${phase.display} must be completed before marking ${type.label}."
                _openChecklist.value = ChecklistPrompt(caseId, phase, checklists.items(phase), checklists.get(caseId, phase))
                return@launch
            }

            cases.markEvent(caseId, type)

            // Marking the trigger event opens the corresponding phase immediately.
            ChecklistPhase.forEvent(type)?.let { phase ->
                val run = checklists.openPhase(caseId, phase)
                _openChecklist.value = ChecklistPrompt(caseId, phase, checklists.items(phase), run)
            }
        }
    }

    fun correctEvent(event: EventEntity, newTimestampMs: Long) {
        viewModelScope.launch { cases.correctEvent(event, newTimestampMs) }
    }

    fun openChecklist(caseId: String, phase: ChecklistPhase) {
        viewModelScope.launch {
            _openChecklist.value = ChecklistPrompt(caseId, phase, checklists.items(phase), checklists.openPhase(caseId, phase))
        }
    }

    fun toggleChecklistItem(itemId: String) {
        val prompt = _openChecklist.value ?: return
        viewModelScope.launch {
            checklists.toggleItem(prompt.caseId, prompt.phase, itemId)
            _openChecklist.value = prompt.copy(run = checklists.get(prompt.caseId, prompt.phase))
        }
    }

    fun completeChecklist() {
        val prompt = _openChecklist.value ?: return
        viewModelScope.launch {
            if (checklists.complete(prompt.caseId, prompt.phase)) {
                _openChecklist.value = null
                _blockedMessage.value = null
            } else {
                _blockedMessage.value = "Confirm every critical item, or skip with a reason."
            }
        }
    }

    fun skipChecklist(reason: String) {
        val prompt = _openChecklist.value ?: return
        viewModelScope.launch {
            checklists.skip(prompt.caseId, prompt.phase, reason)
            _openChecklist.value = null
            _blockedMessage.value = null
        }
    }

    fun dismissChecklist() { _openChecklist.value = null }

    fun clearBlockedMessage() { _blockedMessage.value = null }

    fun resetDay() {
        viewModelScope.launch { cases.resetDay() }
    }

    data class ChecklistPrompt(
        val caseId: String,
        val phase: ChecklistPhase,
        val items: List<ChecklistItem>,
        val run: ChecklistRunEntity?,
    )

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
    val loading: Boolean = true,
)
