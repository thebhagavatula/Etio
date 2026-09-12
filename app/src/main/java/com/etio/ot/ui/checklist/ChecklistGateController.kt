package com.etio.ot.ui.checklist

import android.util.Log
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
    private val tag = "ChecklistGateController"

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
        return runCatching {
            val blocking = checklists.gateFor(caseId, next, markedEvents) ?: return@runCatching true
            _message.value = "${blocking.display} must be completed before marking ${next.label}."
            _prompt.value = Prompt(caseId, blocking, checklists.items(blocking), checklists.get(caseId, blocking))
            false
        }.getOrElse { e ->
            Log.e(tag, "Failed to evaluate gate for event $next", e)
            true // Allow on failure to prevent hard blocking the user
        }
    }

    /** Call immediately after a successful mark. Opens the phase that event triggers, if any. */
    fun onEventMarked(caseId: String, type: EventType) {
        val phase = ChecklistPhase.forEvent(type) ?: return
        scope.launch {
            runCatching {
                _prompt.value = Prompt(caseId, phase, checklists.items(phase), checklists.openPhase(caseId, phase))
            }.onFailure { e -> Log.e(tag, "Failed to open phase $phase", e) }
        }
    }

    /** Reopen a phase on demand — e.g. from a badge on the case card. */
    fun open(caseId: String, phase: ChecklistPhase) {
        scope.launch {
            runCatching {
                _prompt.value = Prompt(caseId, phase, checklists.items(phase), checklists.openPhase(caseId, phase))
            }.onFailure { e -> Log.e(tag, "Failed to open phase $phase on demand", e) }
        }
    }

    fun toggle(itemId: String) {
        val current = _prompt.value ?: return
        scope.launch {
            runCatching {
                checklists.toggleItem(current.caseId, current.phase, itemId)
                checklists.get(current.caseId, current.phase)
            }.onSuccess { run ->
                _prompt.value = current.copy(run = run)
            }.onFailure { e ->
                Log.e(tag, "Failed to toggle item $itemId", e)
            }
        }
    }

    fun complete() {
        val current = _prompt.value ?: return
        scope.launch {
            runCatching {
                checklists.complete(current.caseId, current.phase)
            }.onSuccess { success ->
                if (success) {
                    _prompt.value = null
                    _message.value = null
                } else {
                    _message.value = "Confirm every critical item, or skip with a reason."
                }
            }.onFailure { e ->
                Log.e(tag, "Failed to complete phase ${current.phase}", e)
                _message.value = "An error occurred while saving."
            }
        }
    }

    fun skip(reason: String) {
        val current = _prompt.value ?: return
        scope.launch {
            runCatching {
                checklists.skip(current.caseId, current.phase, reason)
            }.onSuccess {
                _prompt.value = null
                _message.value = null
            }.onFailure { e ->
                Log.e(tag, "Failed to skip phase ${current.phase}", e)
                _message.value = "An error occurred while saving."
            }
        }
    }

    fun dismiss() {
        _prompt.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }
}
