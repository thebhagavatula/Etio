package com.etio.ot.ui.report

import android.util.Log
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

    sealed interface UiState {
        data object Loading : UiState
        data class Success(val report: EndOfDayReport, val headline: String) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            runCatching {
                val caseRows = cases.allCases()
                val eventRows = cases.liveEvents()
                val delayRows = delays.allDelays()
                val metrics = TimerEngine.compute(caseRows, eventRows)
                val built = EndOfDayReportBuilder.build(caseRows, delayRows, metrics)
                val headline = EndOfDayReportBuilder.headline(built)
                UiState.Success(built, headline)
            }.onSuccess { state ->
                _uiState.value = state
            }.onFailure { e ->
                Log.e("ReportViewModel", "Failed to build report", e)
                _uiState.value = UiState.Error("Failed to build the end of day report.")
            }
        }
    }

    /** Plain-text export for the Office Kit bridge (F8) and for pasting into a message. */
    fun asPlainText(): String {
        val state = _uiState.value as? UiState.Success ?: return "Report not ready."
        val r = state.report
        return buildString {
            appendLine(state.headline)
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
