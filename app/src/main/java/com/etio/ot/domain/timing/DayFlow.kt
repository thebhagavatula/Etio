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

    /** A span that has run past the point where someone should say why. */
    data class Breach(
        val caseId: String,
        val caseNumber: String,
        val message: String,
        /** Stable across ticks so dismissing one does not re-fire a second later. */
        val key: String,
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
     * The first span in the day that has run long enough to be worth explaining.
     *
     * Arithmetic on spans the timers already computed — it asks a question, it never
     * answers one, and it never writes anything.
     */
    fun breach(cases: List<CaseEntity>, metrics: DayMetrics): Breach? {
        val ordered = cases.filter { it.status != CaseStatus.CANCELLED }.sortedBy { it.orderIndex }

        return ordered.firstNotNullOfOrNull { case ->
            val m = metrics.forCase(case.id) ?: return@firstNotNullOfOrNull null

            val inRoomNoKnife = m.isMarked(EventType.PATIENT_IN_ROOM) &&
                !m.isMarked(EventType.KNIFE_TO_SKIN) &&
                (m.anaesthesiaControlledMs ?: 0L) > IN_ROOM_TO_KNIFE_BREACH_MIN * 60_000L

            val slowTurnover = !m.isMarked(EventType.PATIENT_IN_ROOM) &&
                (m.turnoverMs ?: 0L) > TURNOVER_BREACH_MIN * 60_000L

            val notStarted = !m.hasStarted &&
                metrics.computedAtMs - case.scheduledStartMs > NOT_STARTED_BREACH_MIN * 60_000L

            when {
                inRoomNoKnife -> Breach(
                    caseId = case.id,
                    caseNumber = case.caseNumber,
                    message = "in room ${(m.anaesthesiaControlledMs ?: 0L).minutes()} min, no knife yet",
                    key = "${case.id}:knife",
                )
                slowTurnover -> Breach(
                    caseId = case.id,
                    caseNumber = case.caseNumber,
                    message = "turnover running ${(m.turnoverMs ?: 0L).minutes()} min",
                    key = "${case.id}:turnover",
                )
                notStarted -> Breach(
                    caseId = case.id,
                    caseNumber = case.caseNumber,
                    message = "${(metrics.computedAtMs - case.scheduledStartMs).minutes()} min past its scheduled start",
                    key = "${case.id}:unstarted",
                )
                else -> null
            }
        }
    }

    private fun Long.minutes(): Int = (this / 60_000L).toInt()

    /** Thresholds, in minutes. Deliberately blunt — they start a conversation, nothing more. */
    const val IN_ROOM_TO_KNIFE_BREACH_MIN = 30
    const val TURNOVER_BREACH_MIN = 25
    const val NOT_STARTED_BREACH_MIN = 15

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
