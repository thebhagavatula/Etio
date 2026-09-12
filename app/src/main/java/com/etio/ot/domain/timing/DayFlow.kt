package com.etio.ot.domain.timing

import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.model.CaseStatus
import com.etio.ot.data.model.EventType

/**
 * Pure Kotlin, same contract as [TimerEngine]: a function of (cases, metrics), no
 * Android, no coroutines, no inference. It decides nothing clinical — it only reads
 * the list order and the events already marked.
 */
object DayFlow {

    /** The single event the day is waiting on, and which case it belongs to. */
    data class NextAction(
        val caseId: String,
        val caseNumber: String,
        val event: EventType,
    )

    /** Offer to send for the case after one whose room is ready. */
    data class SendFor(
        val caseId: String,
        val caseNumber: String,
        val afterCaseNumber: String,
    )

    /**
     * The earliest unmarked event in the day, walking cases in list order.
     *
     * This is deliberately not "the active case's next event": once PATIENT_OUT is
     * marked the active case advances to N+1, but the room for case N still has to be
     * cleaned and declared ready. Walking in order keeps those reachable on the
     * primary button instead of stranding them behind the out-of-order sheet.
     */
    fun nextAction(cases: List<CaseEntity>, metrics: DayMetrics): NextAction? =
        cases.asSequence()
            .filter { it.status != CaseStatus.CANCELLED }
            .sortedBy { it.orderIndex }
            .mapNotNull { case ->
                val marks = metrics.forCase(case.id)?.marks.orEmpty()
                TimerEngine.nextExpectedEvent(marks)?.let { NextAction(case.id, case.caseNumber, it) }
            }
            .firstOrNull()

    /**
     * After ROOM_READY on a case, the next one can be sent for. Returns the offer once
     * — it disappears as soon as PATIENT_SENT_FOR exists on that case.
     */
    fun sendForOffer(cases: List<CaseEntity>, metrics: DayMetrics): SendFor? {
        val ordered = cases.filter { it.status != CaseStatus.CANCELLED }.sortedBy { it.orderIndex }
        val readyIndex = ordered.indexOfLast { case ->
            metrics.forCase(case.id)?.isMarked(EventType.ROOM_READY) == true
        }
        if (readyIndex < 0) return null

        val next = ordered.getOrNull(readyIndex + 1) ?: return null
        val nextMarks = metrics.forCase(next.id)?.marks.orEmpty()
        if (EventType.PATIENT_SENT_FOR in nextMarks) return null

        return SendFor(
            caseId = next.id,
            caseNumber = next.caseNumber,
            afterCaseNumber = ordered[readyIndex].caseNumber,
        )
    }
}
