package com.etio.ot.domain.timing

import com.etio.ot.data.model.EventType

data class CaseMetrics(
    val caseId: String,
    val caseNumber: String,
    val marks: Map<EventType, Long>,
    /** Previous PATIENT_OUT -> this PATIENT_IN_ROOM. Live if the next mark is missing. */
    val turnoverMs: Long?,
    /** PATIENT_IN_ROOM -> KNIFE_TO_SKIN. */
    val anaesthesiaControlledMs: Long?,
    /** KNIFE_TO_SKIN -> CLOSURE_COMPLETE. */
    val procedureMs: Long?,
    /** This KNIFE_TO_SKIN -> next case's KNIFE_TO_SKIN. */
    val caseIntervalMs: Long?,
    /** ROOM_CLEAN_START -> ROOM_READY. */
    val cleanupMs: Long?,
    /** Actual knife vs scheduled start, in minutes. Positive = late. */
    val startVarianceMin: Int?,
    val isRunning: Boolean,
    val isComplete: Boolean,
) {
    val hasStarted: Boolean get() = marks.isNotEmpty()
    fun markedAt(type: EventType): Long? = marks[type]
    fun isMarked(type: EventType): Boolean = type in marks
}

data class DayMetrics(
    val computedAtMs: Long,
    val cases: List<CaseMetrics>,
    /** Scheduled start -> actual knife for case 1, in minutes. */
    val firstCaseStartDelayMin: Int?,
    /** Cumulative actual vs cumulative scheduled, in minutes. Positive = over. */
    val runningVarianceMin: Int,
    val activeCaseId: String?,
) {
    fun forCase(caseId: String): CaseMetrics? = cases.firstOrNull { it.caseId == caseId }

    companion object {
        val Empty = DayMetrics(0L, emptyList(), null, 0, null)
    }
}
