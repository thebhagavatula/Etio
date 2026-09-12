package com.etio.ot.ui.checklist

import android.util.Log
import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.ChecklistRepository
import com.etio.ot.di.SafetyModule
import com.etio.ot.domain.checklist.ChecklistStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * OWNER: safety branch.
 *
 * All WHO-checklist behaviour lives behind this one class so the safety branch never
 * has to edit CaseListViewModel. The spine branch's entire contact with the checklist
 * is three calls — [allows], [onEventMarked], and handing this object to
 * [ChecklistHost]. That surface is the contract; it is frozen.
 *
 * Still deterministic: every decision here comes from [ChecklistStateMachine] and the
 * JSON config. No model involvement, ever.
 *
 * Two properties this class has to guarantee that a plain `launch`-per-tap did not:
 *
 *  1. **Writes are serialised.** Every mutation reads the stored run, derives the next
 *     one, and writes it back. Two taps in the same frame — entirely normal when a
 *     scrub is working down a list at arm's length — used to interleave those
 *     read-modify-writes and silently drop one of the confirmations. A [Mutex]
 *     linearises them.
 *  2. **Stale writes are dropped.** Every mutation suspends on I/O, and the prompt can
 *     be replaced or dismissed while it does. Re-reading the prompt under the lock and
 *     checking it still points at the same case and phase stops a completed dialog
 *     from being resurrected by an in-flight toggle.
 */
class ChecklistGateController(
    private val scope: CoroutineScope,
    private val checklists: ChecklistRepository = SafetyModule.checklistRepository,
) {
    /**
     * The open checklist. [confirmedCritical] of [totalCritical] is derived here rather
     * than in the dialog so the count and the enablement of "Complete" can never
     * disagree about the same run.
     */
    data class Prompt(
        val caseId: String,
        val phase: ChecklistPhase,
        val items: List<ChecklistItem>,
        val run: ChecklistRunEntity?,
        /** True when this phase is blocking an event the coordinator just tried to mark. */
        val blocking: Boolean = false,
        val confirmedCritical: Int = 0,
        val totalCritical: Int = 0,
    ) {
        val remainingCritical: Int get() = (totalCritical - confirmedCritical).coerceAtLeast(0)
        val canComplete: Boolean get() = remainingCritical == 0

        /** Identity of the thing on screen, independent of its contents. */
        internal val target: Pair<String, ChecklistPhase> get() = caseId to phase
    }

    private val mutex = Mutex()
    private val inFlight = AtomicInteger(0)

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * True while a mutation is in flight. Additive, for a host that wants to disable
     * its buttons; ignoring it costs nothing because the mutex already protects the data.
     */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /**
     * The gate. Returns true when [next] may be marked; when it returns false it has
     * already opened the blocking phase and set a message, so the caller just stops.
     */
    suspend fun allows(caseId: String, next: EventType, markedEvents: Set<EventType>): Boolean {
        return runCatching {
            val blocking = checklists.gateFor(caseId, next, markedEvents)
                ?: return@runCatching true
            mutex.withLock {
                _prompt.value = build(caseId, blocking, checklists.get(caseId, blocking), blocking = true)
            }
            _message.value = "${blocking.display} must be completed before marking ${next.label}."
            false
        }.getOrElse { e ->
            // Fail open, because a gate that cannot read its own storage must not trap a
            // coordinator mid-case — but never fail open quietly. An unenforced safety
            // gate that says nothing is indistinguishable from a satisfied one, and the
            // whole value of this class is that the distinction is visible.
            Log.e(TAG, "Failed to evaluate the gate for $next on case $caseId", e)
            _message.value = "Checklist could not be checked. ${next.label} was marked unverified."
            true
        }
    }

    /** Call immediately after a successful mark. Opens the phase that event triggers, if any. */
    fun onEventMarked(caseId: String, type: EventType) {
        val phase = ChecklistPhase.forEvent(type) ?: return
        mutate("open phase $phase after $type") {
            // Do not shoulder aside a phase that is already blocking something. That
            // dialog is answering a question the coordinator asked first.
            val current = _prompt.value
            if (current != null && current.blocking && current.target != (caseId to phase)) return@mutate
            _prompt.value = build(caseId, phase, checklists.openPhase(caseId, phase), blocking = false)
        }
    }

    /** Reopen a phase on demand — e.g. from a badge on the case card. */
    fun open(caseId: String, phase: ChecklistPhase) {
        mutate("open phase $phase on demand") {
            _prompt.value = build(caseId, phase, checklists.openPhase(caseId, phase), blocking = false)
        }
    }

    fun toggle(itemId: String) {
        mutate("toggle item $itemId") {
            val current = _prompt.value ?: return@mutate
            checklists.toggleItem(current.caseId, current.phase, itemId)
            refresh(current)
        }
    }

    fun complete() {
        mutate("complete phase") {
            val current = _prompt.value ?: return@mutate
            if (checklists.complete(current.caseId, current.phase)) {
                clear(current)
            } else {
                // Say how many, not just that there are some. "Confirm every critical
                // item" reads as a scolding; "2 critical items left" reads as a list.
                val reloaded = refresh(current)
                val left = reloaded?.remainingCritical ?: 0
                _message.value = when (left) {
                    0 -> "Could not save the checklist. Try again."
                    1 -> "1 critical item left to confirm, or skip with a reason."
                    else -> "$left critical items left to confirm, or skip with a reason."
                }
            }
        }
    }

    fun skip(reason: String) {
        val trimmed = reason.trim()
        // A blank reason is the one thing a skip may never be: the reason is the entire
        // audit trail, and it is what the end-of-day report prints.
        if (trimmed.isEmpty()) {
            _message.value = "A skipped checklist needs a reason."
            return
        }
        mutate("skip phase") {
            val current = _prompt.value ?: return@mutate
            checklists.skip(current.caseId, current.phase, trimmed)
            clear(current)
        }
    }

    fun dismiss() {
        _prompt.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    // --- internals -----------------------------------------------------------------

    /**
     * Runs [block] under the mutex with the saving flag set, logging rather than
     * crashing. [label] names the operation in the log so a failure is traceable to a
     * tap rather than to "something in the checklist".
     */
    private fun mutate(label: String, block: suspend () -> Unit) {
        scope.launch {
            // Counted, not a bare flag: taps queue on the mutex, so the first one to
            // finish would otherwise report the controller idle while the rest are
            // still waiting to write.
            _saving.value = inFlight.incrementAndGet() > 0
            try {
                mutex.withLock { block() }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to $label", e)
                _message.value = "An error occurred while saving."
            } finally {
                _saving.value = inFlight.decrementAndGet() > 0
            }
        }
    }

    /** Re-read the stored run, but only write it back if the prompt still points at it. */
    private suspend fun refresh(expected: Prompt): Prompt? {
        val run = checklists.get(expected.caseId, expected.phase)
        if (_prompt.value?.target != expected.target) return null
        return build(expected.caseId, expected.phase, run, expected.blocking)
            .also { _prompt.value = it }
    }

    /** Close the prompt, unless it has already moved on to something else. */
    private fun clear(expected: Prompt) {
        if (_prompt.value?.target != expected.target) return
        _prompt.value = null
        _message.value = null
    }

    private fun build(
        caseId: String,
        phase: ChecklistPhase,
        run: ChecklistRunEntity?,
        blocking: Boolean,
    ): Prompt {
        val items = checklists.items(phase)
        val (confirmed, total) = ChecklistStateMachine.progress(run, items)
        return Prompt(
            caseId = caseId,
            phase = phase,
            items = items,
            run = run,
            blocking = blocking,
            confirmedCritical = confirmed,
            totalCritical = total,
        )
    }

    private companion object {
        const val TAG = "ChecklistGateController"
    }
}
