package com.etio.ot.domain.report

import com.etio.ot.core.msToMinutes
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import com.etio.ot.domain.timing.DayMetrics

/**
 * PRD §7 F7 — every lost minute traces to a DelayRecord.
 *
 * "Lost minutes" for a delay = the explicitly spoken estimate when there is one,
 * otherwise the measured idle span the delay sits inside. We never invent a number:
 * [DelayAttribution.measured] records which of the two it was, and the UI says so.
 */
object EndOfDayReportBuilder {

    fun build(
        cases: List<CaseEntity>,
        delays: List<DelayRecordEntity>,
        metrics: DayMetrics,
    ): EndOfDayReport {
        val caseById = cases.associateBy { it.id }

        val attributions = delays.map { d ->
            val spoken = d.estimatedMin
            val measured = spoken == null
            val minutes = spoken ?: measuredIdleMinutes(d, metrics)
            DelayAttribution(
                delayId = d.id,
                caseNumber = caseById[d.caseId]?.caseNumber ?: "?",
                code = d.code,
                dept = d.attributedDept,
                avoidable = d.avoidable,
                minutes = minutes,
                measured = measured,
                note = d.note,
                transcript = d.transcriptRaw,
            )
        }

        val byCode = attributions
            .groupBy { it.code }
            .map { (code, rows) -> CodeTotal(code, rows.sumOf { it.minutes }, rows.size) }
            .sortedByDescending { it.minutes }

        val byDept = attributions
            .groupBy { it.dept }
            .map { (dept, rows) -> DeptTotal(dept, rows.sumOf { it.minutes }, rows.size) }
            .sortedByDescending { it.minutes }

        val completed = metrics.cases.count { it.isComplete }
        val scheduledMin = cases.sumOf { it.scheduledDurationMin }
        val actualMin = metrics.cases.sumOf { it.procedureMs?.msToMinutes() ?: 0 }

        return EndOfDayReport(
            theatreId = cases.firstOrNull()?.theatreId ?: "—",
            casesScheduled = cases.size,
            casesCompleted = completed,
            scheduledMinutes = scheduledMin,
            actualMinutes = actualMin,
            lostMinutes = attributions.sumOf { it.minutes },
            avoidableMinutes = attributions.filter { it.avoidable == Avoidability.AVOIDABLE }.sumOf { it.minutes },
            firstCaseStartDelayMin = metrics.firstCaseStartDelayMin,
            byCode = byCode,
            byDept = byDept,
            attributions = attributions,
        )
    }

    /**
     * Fallback when no duration was spoken: the anaesthesia-controlled or turnover span
     * on the case the delay was logged against. Conservative — returns 0 rather than a guess.
     */
    private fun measuredIdleMinutes(d: DelayRecordEntity, metrics: DayMetrics): Int {
        val cm = metrics.forCase(d.caseId) ?: return 0
        val span = cm.turnoverMs ?: cm.anaesthesiaControlledMs ?: return 0
        return span.msToMinutes()
    }

    /** One-line headline for the demo: "Theatre 2 lost 47 minutes today. 31 of them were CSSD." */
    fun headline(report: EndOfDayReport): String {
        if (report.lostMinutes == 0) return "${report.theatreId}: no attributed delay today."
        val worst = report.byDept.firstOrNull()
        val tail = worst?.let { " ${it.minutes} of them were ${it.dept}." }.orEmpty()
        return "${report.theatreId} lost ${report.lostMinutes} minutes today.$tail"
    }
}

data class EndOfDayReport(
    val theatreId: String,
    val casesScheduled: Int,
    val casesCompleted: Int,
    val scheduledMinutes: Int,
    val actualMinutes: Int,
    val lostMinutes: Int,
    val avoidableMinutes: Int,
    val firstCaseStartDelayMin: Int?,
    val byCode: List<CodeTotal>,
    val byDept: List<DeptTotal>,
    val attributions: List<DelayAttribution>,
) {
    companion object {
        val Empty = EndOfDayReport("—", 0, 0, 0, 0, 0, 0, null, emptyList(), emptyList(), emptyList())
    }
}

data class CodeTotal(val code: DelayCode, val minutes: Int, val occurrences: Int)
data class DeptTotal(val dept: String, val minutes: Int, val occurrences: Int)

data class DelayAttribution(
    val delayId: String,
    val caseNumber: String,
    val code: DelayCode,
    val dept: String,
    val avoidable: Avoidability,
    val minutes: Int,
    /** True when [minutes] came from the clock, not from something the coordinator said. */
    val measured: Boolean,
    val note: String,
    val transcript: String,
)
