package com.etio.ot.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.etio.ot.ai.EvalHarness
import com.etio.ot.ai.InferenceTelemetry
import com.etio.ot.di.AiModule
import com.etio.ot.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Drives the eval run and keeps the last one on disk.
 *
 * The run itself is minutes of GPU work, so it lives on [Dispatchers.Default], reports
 * progress per item, and is cancellable at every item. Results survive a relaunch
 * because the interesting conversation about them usually happens after the phone has
 * been handed round and the app has been killed twice.
 */
class EvalViewModel(
    private val harness: EvalHarness = EvalHarness(AiModule.classifierForEval, AiModule.llmEngine),
) : ViewModel() {

    data class UiState(
        val running: Boolean = false,
        val progressLabel: String = "",
        val progressFraction: Float = 0f,
        val run: EvalHarness.Run? = null,
        val error: String? = null,
        val itemCount: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val lastRunFile: File
        get() = File(ServiceLocator.appContext.filesDir, "eval/last_run.json")

    init {
        viewModelScope.launch {
            val set = withContext(Dispatchers.Default) { runCatching { loadSet() }.getOrNull() }
            _state.value = _state.value.copy(
                itemCount = set?.items?.size ?: 0,
                run = withContext(Dispatchers.Default) { loadLastRun() },
            )
        }
    }

    fun start(modes: List<EvalHarness.Mode>) {
        if (_state.value.running) return
        job = viewModelScope.launch {
            _state.value = _state.value.copy(running = true, error = null, progressFraction = 0f)
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val set = loadSet()
                    val cached = InferenceTelemetry.snapshot.value.prefixCached
                    val modeResults = harness.run(set, modes, cached) { p ->
                        _state.value = _state.value.copy(
                            progressLabel = "${p.mode.label} · ${p.done}/${p.total}",
                            progressFraction = if (p.total == 0) 0f else p.done.toFloat() / p.total,
                        )
                    }
                    EvalHarness.Run(
                        startedAtMs = System.currentTimeMillis(),
                        backend = InferenceTelemetry.snapshot.value.backend ?: "unknown",
                        prefixCached = cached,
                        modes = modeResults,
                    )
                }
            }

            result
                .onSuccess { run ->
                    persist(run)
                    _state.value = _state.value.copy(running = false, run = run, progressLabel = "")
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(
                        running = false,
                        progressLabel = "",
                        // Cancellation is a choice, not a failure.
                        error = if (t is kotlinx.coroutines.CancellationException) null else t.message,
                    )
                }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false, progressLabel = "")
    }

    private fun loadSet(): EvalHarness.EvalSet {
        val text = ServiceLocator.appContext.assets.open("config/eval_set.json")
            .bufferedReader()
            .use { it.readText() }
        return json.decodeFromString(text)
    }

    private fun persist(run: EvalHarness.Run) {
        runCatching {
            lastRunFile.parentFile?.mkdirs()
            lastRunFile.writeText(json.encodeToString(EvalHarness.Run.serializer(), run))
        }
    }

    private fun loadLastRun(): EvalHarness.Run? = runCatching {
        if (!lastRunFile.exists()) return null
        json.decodeFromString(EvalHarness.Run.serializer(), lastRunFile.readText())
    }.getOrNull()

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = EvalViewModel() as T
        }
    }
}
