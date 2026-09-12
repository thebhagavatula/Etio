package com.etio.ot.domain.timing

import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.model.CaseStatus

/**
 * What a stated delay does to the rest of the day.
 *
 * Arithmetic, not inference. The model is never asked what time anything moves to —
 * it is only ever handed the answer this computes, so a message that carries a
 * revised time carries a real one.
 *
 * The rule is deliberately one sentence long, because it has to be explainable to a
 * surgeon in a corridor: every case from the delayed one onwards moves later by the
 * number of minutes that was actually said out loud. A case already past its
 * scheduled start is measured from now instead, so its revised time is not in the
 * past the moment it is written.
 */
object ScheduleProjector {

    data class RevisedCase(
        val caseId: String,
        val caseNumber: String,
        val scheduledStartMs: Long,
        val revisedStartMs: Long,
    )

    data class Shift(
        /** Minutes every downstream case moves by — the stated duration, nothing more. */
        val pushMin: Int,
        /** The delayed case itself, revised. */
        val delayed: RevisedCase,
        /** Every case after it, in list order. */
        val downstream: List<RevisedCase>,
    ) {
        /** "Cases 4–6 pushed ~40 min", or null when nothing follows. */
        val summary: String?
            get() = when {
                downstream.isEmpty() -> null
                downstream.size == 1 -> "Case ${downstream.first().caseNumber} pushed ~$pushMin min"
                else -> "Cases ${downstream.first().caseNumber}–${downstream.last().caseNumber} " +
                    "pushed ~$pushMin min"
            }
    }

    /**
     * Returns null when no duration was stated — [estimatedMin] is null unless the
     * coordinator actually said one, and a schedule must never be moved on a guess.
     */
    fun project(
        cases: List<CaseEntity>,
        delayedCaseId: String,
        estimatedMin: Int?,
        nowMs: Long,
    ): Shift? {
        if (estimatedMin == null || estimatedMin <= 0) return null

        val ordered = cases.filter { it.status != CaseStatus.CANCELLED }.sortedBy { it.orderIndex }
        val index = ordered.indexOfFirst { it.id == delayedCaseId }
        if (index < 0) return null

        val pushMs = estimatedMin * 60_000L
        val delayedCase = ordered[index]

        return Shift(
            pushMin = estimatedMin,
            delayed = RevisedCase(
                caseId = delayedCase.id,
                caseNumber = delayedCase.caseNumber,
                scheduledStartMs = delayedCase.scheduledStartMs,
                revisedStartMs = maxOf(delayedCase.scheduledStartMs, nowMs) + pushMs,
            ),
            downstream = ordered.drop(index + 1).map { case ->
                RevisedCase(
                    caseId = case.id,
                    caseNumber = case.caseNumber,
                    scheduledStartMs = case.scheduledStartMs,
                    revisedStartMs = case.scheduledStartMs + pushMs,
                )
            },
        )
    }
}
