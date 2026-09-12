package com.etio.ot.domain.checklist

import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.EventType

/**
 * PRD §7 F6. A safety artefact — deterministic, no model involvement, ever.
 *
 * Contract:
 *  - Marking [ChecklistPhase.triggerEvent] opens that phase's checklist.
 *  - Until the phase is completed or explicitly skipped-with-reason, no further
 *    event may be marked on that case. [gate] is the single authority on this.
 */
object ChecklistStateMachine {

    /** A phase is satisfied when every critical item is confirmed, or it was skipped with a reason. */
    fun isSatisfied(run: ChecklistRunEntity?, items: List<ChecklistItem>): Boolean {
        if (run == null) return false
        if (run.skipped) return !run.skipReason.isNullOrBlank()
        val criticalIds = items.filter { it.critical }.map { it.id }.toSet()
        return run.completedAtMs != null && run.itemsConfirmed.containsAll(criticalIds)
    }

    /** The phase opened by marking [event], if any. */
    fun phaseTriggeredBy(event: EventType): ChecklistPhase? = ChecklistPhase.forEvent(event)

    /**
     * Can [next] be marked right now?
     *
     * Blocked when an earlier phase has been triggered by an already-marked event but
     * is not yet satisfied. Returns the blocking phase, or null when clear.
     */
    fun gate(
        next: EventType,
        markedEvents: Set<EventType>,
        runs: Map<ChecklistPhase, ChecklistRunEntity?>,
        itemsByPhase: Map<ChecklistPhase, List<ChecklistItem>>,
    ): ChecklistPhase? = ChecklistPhase.entries
        .filter { phase ->
            // The phase is due: its trigger event is already marked...
            phase.triggerEvent in markedEvents &&
                // ...and the event we are about to mark comes strictly after that trigger.
                next.ordinal > phase.triggerEvent.ordinal
        }
        .firstOrNull { phase ->
            !isSatisfied(runs[phase], itemsByPhase[phase].orEmpty())
        }

    /** Progress for the UI: confirmed critical items over total critical items. */
    fun progress(run: ChecklistRunEntity?, items: List<ChecklistItem>): Pair<Int, Int> {
        val critical = items.filter { it.critical }
        val confirmed = run?.itemsConfirmed.orEmpty().toSet()
        return critical.count { it.id in confirmed } to critical.size
    }
}
