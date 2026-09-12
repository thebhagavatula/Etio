package com.etio.ot.ui.report

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.EventSource
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.ChecklistRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.di.AiModule
import com.etio.ot.di.CoreModule
import com.etio.ot.di.SafetyModule
import com.etio.ot.domain.checklist.ChecklistStateMachine
import com.etio.ot.domain.report.EndOfDayReport
import com.etio.ot.domain.report.EndOfDayReportBuilder
import com.etio.ot.domain.timing.TimerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Pure aggregation over stored rows. No inference anywhere on this path — every
 * number here is either counted or subtracted, and the screen says which.
 *
 * Reactive rather than snapshot-on-open: the coordinator opens this mid-list, walks
 * away, marks two more events and comes back. A report frozen at open time would
 * quietly disagree with the case list behind it, and reconciling those two is the
 * entire claim of this screen. [refresh] exists only to retry after an error.
 *
 * Beyond the delay arithmetic in [EndOfDayReportBuilder], this adds the two things
 * the minute totals do not admit to on their own:
 *
 *  - [Compliance] — which WHO phases came due, were confirmed, or were skipped.
 *    ChecklistRepository.skip stores a reason specifically so that it surfaces here
 *    instead of nowhere.
 *  - [Provenance] — how many event timestamps the app filled in itself. The spine
 *    writes INFERRED rows when a later event is marked over a missing earlier one,
 *    so a measured duration can rest partly on assumed marks. Saying so is the
 *    difference between a report and a claim.
 */
class ReportViewModel(
    private val cases: CaseRepository = CoreModule.caseRepository,
    private val delays: DelayRepository = AiModule.delayRepository,
    private val checklists: ChecklistRepository = SafetyModule.checklistRepository,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Success(
            val report: EndOfDayReport,
            val headline: String,
            val compliance: Compliance,
            val provenance: Provenance,
        ) : UiState
        data class Error(val message: String) : UiState
    }

    /** One WHO phase across the whole day. [due] is a fact about the clock, not the checklist. */
    data class PhaseCompliance(
        val phase: ChecklistPhase,
        val due: Int,
        val confirmed: Int,
        val skipped: Int,
    ) {
        /** Due, but neither confirmed nor skipped with a reason. The number that matters. */
        val outstanding: Int get() = (due - confirmed - skipped).coerceAtLeast(0)
    }

    data class SkipNote(val caseNumber: String, val phase: ChecklistPhase, val reason: String)

    data class Compliance(
        val phases: List<PhaseCompliance>,
        val skips: List<SkipNote>,
    ) {
        val due: Int get() = phases.sumOf { it.due }
        val confirmed: Int get() = phases.sumOf { it.confirmed }
        val skipped: Int get() = phases.sumOf { it.skipped }
        val outstanding: Int get() = phases.sumOf { it.outstanding }

        /** Every phase that came due was confirmed in full: nothing skipped, nothing open. */
        val clean: Boolean get() = due > 0 && outstanding == 0 && skipped == 0
    }

    /**
     * Where the day's timestamps came from.
     *
     * [appFilled] is deliberately a subtraction rather than a count of a named
     * EventSource value: a human either marked the event ([tapped] or [voice]) or did
     * not, and everything in the second group needs the same caveat on the report.
     * EventSource lives in a frozen file that the spine grows as it learns to fill
     * gaps — it gained INFERRED for exactly this — so counting the complement keeps
     * this correct across a value this branch cannot yet see, instead of silently
     * reporting new app-written rows as if someone had stood there and marked them.
     */
    data class Provenance(
        val tapped: Int,
        val voice: Int,
        val appFilled: Int,
        val corrected: Int,
    ) {
        val total: Int get() = tapped + voice + appFilled
    }

    private data class Inputs(
        val cases: List<CaseEntity>,
        val events: List<EventEntity>,
        val delays: List<DelayRecordEntity>,
    )

    /** Bumped by [refresh] to re-run the pipeline after a transient failure. */
    private val retries = MutableStateFlow(0)

    val uiState: StateFlow<UiState> =
        combine(cases.cases, cases.events, delays.delays, retries) { c, e, d, _ -> Inputs(c, e, d) }
            .map { assemble(it) }
            .catch { e ->
                Log.e(TAG, "Failed to build the end of day report", e)
                emit(UiState.Error("Could not build the end of day report."))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /** Retry after [UiState.Error]. The happy path needs no refresh: the flows push. */
    fun refresh() {
        retries.update { it + 1 }
    }

    private suspend fun assemble(input: Inputs): UiState {
        val metrics = TimerEngine.compute(input.cases, input.events)
        val report = EndOfDayReportBuilder.build(input.cases, input.delays, metrics)
        return UiState.Success(
            report = report,
            headline = EndOfDayReportBuilder.headline(report),
            compliance = compliance(input.cases, input.events, checklists.allRuns()),
            provenance = provenance(input.events),
        )
    }

    private fun compliance(
        cases: List<CaseEntity>,
        events: List<EventEntity>,
        runs: List<ChecklistRunEntity>,
    ): Compliance {
        val markedByCase: Map<String, Set<EventType>> = events
            .groupBy { it.caseId }
            .mapValues { (_, rows) -> rows.mapTo(mutableSetOf()) { it.type } }
        val runsByCase = runs.groupBy { it.caseId }
        val itemsByPhase = checklists.itemsByPhase()

        val phases = ChecklistPhase.entries.map { phase ->
            var due = 0
            var confirmed = 0
            var skipped = 0
            for (case in cases) {
                // A phase came due the moment its trigger event was marked. Cases that
                // never got that far are not counted against anyone.
                if (phase.triggerEvent !in markedByCase[case.id].orEmpty()) continue
                due++
                val run = runsByCase[case.id]?.firstOrNull { it.phase == phase }
                // Order matters. The state machine counts skipped-with-reason as
                // satisfied, and a skip is precisely what must not vanish into a
                // "confirmed" total.
                when {
                    run != null && run.skipped && !run.skipReason.isNullOrBlank() -> skipped++
                    ChecklistStateMachine.isSatisfied(run, itemsByPhase[phase].orEmpty()) -> confirmed++
                }
            }
            PhaseCompliance(phase, due, confirmed, skipped)
        }

        val caseById = cases.associateBy { it.id }
        val skips = runs
            .filter { it.skipped && !it.skipReason.isNullOrBlank() }
            .sortedWith(
                compareBy(
                    { caseById[it.caseId]?.orderIndex ?: Int.MAX_VALUE },
                    { it.phase.ordinal },
                )
            )
            .map { run ->
                SkipNote(
                    caseNumber = caseById[run.caseId]?.caseNumber ?: "?",
                    phase = run.phase,
                    reason = run.skipReason.orEmpty().trim(),
                )
            }

        return Compliance(phases, skips)
    }

    private fun provenance(events: List<EventEntity>): Provenance {
        val tapped = events.count { it.source == EventSource.TAP }
        val voice = events.count { it.source == EventSource.VOICE }
        return Provenance(
            tapped = tapped,
            voice = voice,
            appFilled = events.size - tapped - voice,
            corrected = events.count { it.correctedFromEventId != null },
        )
    }

    /** Plain-text export for the Office Kit bridge (F8) and for pasting into a message. */
    fun asPlainText(): String {
        val state = uiState.value as? UiState.Success ?: return "Report not ready."
        val r = state.report
        return buildString {
            appendLine(state.headline)
            appendLine()
            appendLine("Cases: ${r.casesCompleted}/${r.casesScheduled} completed")
            appendLine("Scheduled: ${r.scheduledMinutes} min / Actual: ${r.actualMinutes} min")
            r.firstCaseStartDelayMin?.let { appendLine("First case start delay: $it min") }
            appendLine("Lost: ${r.lostMinutes} min (${r.avoidableMinutes} min avoidable)")

            if (r.byCode.isNotEmpty()) {
                section("By cause") {
                    r.byCode.forEach {
                        appendLine("  ${it.code.display}: ${it.minutes} min (${it.occurrences}x)")
                    }
                }
            }
            if (r.byDept.isNotEmpty()) {
                section("By department") {
                    r.byDept.forEach {
                        appendLine("  ${it.dept}: ${it.minutes} min (${it.occurrences}x)")
                    }
                }
            }

            val c = state.compliance
            section("WHO checklist") {
                if (c.due == 0) {
                    appendLine("  No phase came due today.")
                } else {
                    c.phases.filter { it.due > 0 }.forEach { p ->
                        val skipped = if (p.skipped > 0) ", ${p.skipped} skipped" else ""
                        val open = if (p.outstanding > 0) ", ${p.outstanding} OUTSTANDING" else ""
                        appendLine("  ${p.phase.display}: ${p.confirmed}/${p.due} confirmed$skipped$open")
                    }
                    c.skips.forEach {
                        appendLine("  Skipped, case ${it.caseNumber} ${it.phase.display}: ${it.reason}")
                    }
                }
            }

            if (r.attributions.isEmpty()) {
                section("Attribution") { appendLine("  No delays logged.") }
            } else {
                section("Every minute above traces to a logged delay") {
                    r.attributions.forEach {
                        val basis = if (it.measured) "measured" else "stated"
                        val avoidable = if (it.avoidable == Avoidability.AVOIDABLE) ", avoidable" else ""
                        appendLine(
                            "  Case ${it.caseNumber} / ${it.code.display} / " +
                                "${it.minutes} min ($basis$avoidable)"
                        )
                        appendLine("    ${it.transcript}")
                    }
                }
            }

            // The caveat travels with the export. A number pasted into an email
            // outlives the screen that qualified it.
            val p = state.provenance
            if (p.appFilled > 0 || p.corrected > 0) {
                section("Provenance") {
                    appendLine("  ${p.total} event timestamps recorded.")
                    if (p.appFilled > 0) {
                        appendLine("  ${p.appFilled} were filled in by the app, not marked at the time.")
                    }
                    if (p.corrected > 0) {
                        appendLine("  ${p.corrected} were corrected after the fact.")
                    }
                }
            }
        }
    }

    private inline fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
        appendLine()
        appendLine("$title:")
        body()
    }

    companion object {
        private const val TAG = "ReportViewModel"

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReportViewModel() as T
        }
    }
}
