package com.etio.ot.domain.timing

import com.etio.ot.core.Clock
import com.etio.ot.core.msToMinutes
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.EventType

/**
 * PRD §7 F3. Pure system-clock arithmetic — ZERO dependency on inference.
 *
 * This object has no Android imports, no coroutines, no I/O. It is a pure function
 * of (cases, events, now). That is the whole point: if the model hangs, the clocks
 * on the projector keep moving.
 */
object TimerEngine {

    fun compute(
        cases: List<CaseEntity>,
        events: List<EventEntity>,
        clock: Clock = Clock.System,
    ): DayMetrics {
        val now = clock.nowMs()
        val ordered = cases.sortedBy { it.orderIndex }
        val byCase: Map<String, Map<EventType, Long>> = events
            .filter { it.supersededByEventId == null }
            .groupBy { it.caseId }
            .mapValues { (_, rows) ->
                // If a type somehow appears twice, the latest row wins.
                rows.sortedBy { it.timestampMs }.associate { it.type to it.timestampMs }
            }

        val caseMetrics = ordered.mapIndexed { index, case ->
            val e = byCase[case.id].orEmpty()
            val prev = ordered.getOrNull(index - 1)
            val prevE = prev?.let { byCase[it.id].orEmpty() }.orEmpty()

            CaseMetrics(
                caseId = case.id,
                caseNumber = case.caseNumber,
                marks = e,
                // Turnover: previous case out -> this case in.
                turnoverMs = span(prevE[EventType.PATIENT_OUT], e[EventType.PATIENT_IN_ROOM], now),
                // Anaesthesia-controlled: in room -> knife.
                anaesthesiaControlledMs = span(e[EventType.PATIENT_IN_ROOM], e[EventType.KNIFE_TO_SKIN], now),
                // Procedure: knife -> closure.
                procedureMs = span(e[EventType.KNIFE_TO_SKIN], e[EventType.CLOSURE_COMPLETE], now),
                // Case interval: this knife -> next knife (filled in below).
                caseIntervalMs = null,
                cleanupMs = span(e[EventType.ROOM_CLEAN_START], e[EventType.ROOM_READY], now),
                startVarianceMin = e[EventType.KNIFE_TO_SKIN]
                    ?.let { ((it - case.scheduledStartMs).msToMinutes()) },
                isRunning = e.containsKey(EventType.PATIENT_IN_ROOM) && !e.containsKey(EventType.PATIENT_OUT),
                isComplete = e.containsKey(EventType.PATIENT_OUT),
            )
        }

        // Second pass for case interval, which needs the next case's knife time.
        val withIntervals = caseMetrics.mapIndexed { index, cm ->
            val nextKnife = caseMetrics.getOrNull(index + 1)?.marks?.get(EventType.KNIFE_TO_SKIN)
            cm.copy(caseIntervalMs = span(cm.marks[EventType.KNIFE_TO_SKIN], nextKnife, now))
        }

        val firstCase = ordered.firstOrNull()
        val firstCaseKnife = firstCase?.let { byCase[it.id]?.get(EventType.KNIFE_TO_SKIN) }
        val firstCaseStartDelayMin = if (firstCase != null && firstCaseKnife != null) {
            (firstCaseKnife - firstCase.scheduledStartMs).msToMinutes()
        } else null

        val scheduledSoFar = ordered
            .filter { byCase[it.id]?.containsKey(EventType.KNIFE_TO_SKIN) == true }
            .sumOf { it.scheduledDurationMin }
        val actualSoFar = withIntervals.sumOf { it.procedureMs?.msToMinutes() ?: 0 }

        return DayMetrics(
            computedAtMs = now,
            cases = withIntervals,
            firstCaseStartDelayMin = firstCaseStartDelayMin,
            runningVarianceMin = actualSoFar - scheduledSoFar,
            activeCaseId = withIntervals.firstOrNull { it.isRunning }?.caseId
                ?: withIntervals.firstOrNull { !it.isComplete }?.caseId,
        )
    }

    /**
     * Span from [start] to [end]; if [end] is missing but [start] exists, the span is
     * live and measured to [now]. Missing start means no span at all.
     */
    private fun span(start: Long?, end: Long?, now: Long): Long? = when {
        start == null -> null
        end == null -> (now - start).coerceAtLeast(0L)
        else -> (end - start).coerceAtLeast(0L)
    }

    /**
     * Which event should be offered next for a case. Used to size and order the tap grid.
     * Returns null once the case is fully marked.
     */
    fun nextExpectedEvent(marks: Map<EventType, Long>): EventType? =
        EventType.ordered.firstOrNull { it !in marks }
}
