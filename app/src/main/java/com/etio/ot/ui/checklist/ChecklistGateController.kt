package com.etio.ot.ui.checklist

import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.ChecklistRepository
import com.etio.ot.di.SafetyModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * OWNER: safety branch.
 *
 * All WHO-checklist behaviour lives behind this one class so the safety branch
 * never has to edit CaseListViewModel. The spine branch's entire contact with the
 * checklist is three calls — [allows], [onEventMarked], and handing this object to
 * [ChecklistHost]. That surface is the contract; treat it as frozen once branched.
 *
 * Still deterministic: every decision here comes from ChecklistStateMachine and the
 * JSON config. No model involvement, ever.
 */
class ChecklistGateController(
    private val scope: CoroutineScope,
    private val checklists: ChecklistRepository = SafetyModule.checklistRepository,
) {

    data class Prompt(
        val caseId: String,
        val phase: ChecklistPhase,
        val items: List<ChecklistItem>,
        val run: ChecklistRunEntity?,
    )

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * The gate. Returns true when [next] may be marked; when it returns false it has
     * already opened the blocking phase and set a message, so the caller just stops.
     */
    suspend fun allows(caseId: String, next: EventType, markedEvents: Set<EventType>): Boolean {
        val blocking = checklists.gateFor(caseId, next, markedEvents) ?: return true
        _message.value = "${blocking.display} must be completed before marking ${next.label}."
        _prompt.value = Prompt(caseId, blocking, checklists.items(blocking), checklists.get(caseId, blocking))
        return false
    }

    /** Call immediately after a successful mark. Opens the phase that event triggers, if any. */
    fun onEventMarked(caseId: String, type: EventType) {
        val phase = ChecklistPhase.forEvent(type) ?: return
        scope.launch {
            _prompt.value = Prompt(caseId, phase, checklists.items(phase), checklists.openPhase(caseId, phase))
        }
    }

    /** Reopen a phase on demand — e.g. from a badge on the case card. */
    fun open(caseId: String, phase: ChecklistPhase) {
        scope.launch {
            _prompt.value = Prompt(caseId, phase, checklists.items(phase), checklists.openPhase(caseId, phase))
        }
    }

    fun toggle(itemId: String) {
        val current = _prompt.value ?: return
        scope.launch {
            checklists.toggleItem(current.caseId, current.phase, itemId)
            _prompt.value = current.copy(run = checklists.get(current.caseId, current.phase))
        }
    }

    fun complete() {
        val current = _prompt.value ?: return
        scope.launch {
            if (checklists.complete(current.caseId, current.phase)) {
                _prompt.value = null
                _message.value = null
            } else {
                _message.value = "Confirm every critical item, or skip with a reason."
            }
        }
    }

    fun skip(reason: String) {
        val current = _prompt.value ?: return
        scope.launch {
            checklists.skip(current.caseId, current.phase, reason)
            _prompt.value = null
            _message.value = null
        }
    }

    fun dismiss() {
        _prompt.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }
}
