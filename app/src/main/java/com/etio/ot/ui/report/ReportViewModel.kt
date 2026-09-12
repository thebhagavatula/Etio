package com.etio.ot.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.di.AiModule
import com.etio.ot.di.CoreModule
import com.etio.ot.domain.report.EndOfDayReport
import com.etio.ot.domain.report.EndOfDayReportBuilder
import com.etio.ot.domain.timing.TimerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Pure aggregation over stored rows. No inference anywhere on this path. */
class ReportViewModel(
    private val cases: CaseRepository = CoreModule.caseRepository,
    private val delays: DelayRepository = AiModule.delayRepository,
) : ViewModel() {

    private val _report = MutableStateFlow(EndOfDayReport.Empty)
    val report: StateFlow<EndOfDayReport> = _report.asStateFlow()

    private val _headline = MutableStateFlow("")
    val headline: StateFlow<String> = _headline.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val caseRows = cases.allCases()
            val eventRows = cases.liveEvents()
            val delayRows = delays.allDelays()
            val metrics = TimerEngine.compute(caseRows, eventRows)
            val built = EndOfDayReportBuilder.build(caseRows, delayRows, metrics)
            _report.value = built
            _headline.value = EndOfDayReportBuilder.headline(built)
        }
    }

    /** Plain-text export for the Office Kit bridge (F8) and for pasting into a message. */
    fun asPlainText(): String {
        val r = _report.value
        return buildString {
            appendLine(_headline.value)
            appendLine()
            appendLine("Cases: ${r.casesCompleted}/${r.casesScheduled} completed")
            appendLine("Scheduled: ${r.scheduledMinutes} min · Actual: ${r.actualMinutes} min")
            r.firstCaseStartDelayMin?.let { appendLine("First case start delay: $it min") }
            appendLine("Lost: ${r.lostMinutes} min (${r.avoidableMinutes} min avoidable)")
            appendLine()
            appendLine("By cause:")
            r.byCode.forEach { appendLine("  ${it.code.display}: ${it.minutes} min (${it.occurrences}x)") }
            appendLine()
            appendLine("By department:")
            r.byDept.forEach { appendLine("  ${it.dept}: ${it.minutes} min (${it.occurrences}x)") }
            appendLine()
            appendLine("Every minute above traces to a logged delay:")
            r.attributions.forEach {
                appendLine("  Case ${it.caseNumber} · ${it.code.display} · ${it.minutes} min${if (it.measured) " (measured)" else " (stated)"}")
                appendLine("    \"${it.transcript}\"")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReportViewModel() as T
        }
    }
}
