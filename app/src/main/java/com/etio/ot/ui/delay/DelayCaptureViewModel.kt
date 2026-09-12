package com.etio.ot.ui.delay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.etio.ot.ai.LlmEngine
import com.etio.ot.ai.SpeechCapture
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one screen where the model is on the critical path. Three states the UI must
 * never conflate: listening, classifying, reviewing.
 *
 * The transcript is shown the instant ASR returns and stays visible under the
 * structured card — it is the grounding evidence, and the answer to the
 * hallucination question.
 */
class DelayCaptureViewModel(
    private val caseId: String,
    private val speech: SpeechCapture = ServiceLocator.speechCapture,
    private val delays: DelayRepository = ServiceLocator.delayRepository,
    private val cases: CaseRepository = ServiceLocator.caseRepository,
    private val engine: LlmEngine = ServiceLocator.llmEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(DelayCaptureUiState())
    val state: StateFlow<DelayCaptureUiState> = _state.asStateFlow()

    val engineState = engine.state

    private var listenJob: Job? = null

    init {
        viewModelScope.launch {
            val case = cases.getCase(caseId)
            _state.value = _state.value.copy(
                caseNumber = case?.caseNumber.orEmpty(),
                procedureName = case?.procedureName.orEmpty(),
                micAvailable = speech.isAvailable(),
            )
        }
    }

    fun startListening() {
        listenJob?.cancel()
        _state.value = _state.value.copy(
            phase = CapturePhase.LISTENING,
            transcript = "",
            error = null,
            record = null,
        )
        listenJob = viewModelScope.launch {
            speech.listen().collect { event ->
                when (event) {
                    is SpeechCapture.Event.Ready ->
                        _state.value = _state.value.copy(phase = CapturePhase.LISTENING)
                    is SpeechCapture.Event.Rms ->
                        _state.value = _state.value.copy(level = event.db)
                    is SpeechCapture.Event.Partial ->
                        _state.value = _state.value.copy(transcript = event.text)
                    is SpeechCapture.Event.Final -> {
                        _state.value = _state.value.copy(transcript = event.text)
                        classify(event.text)
                    }
                    is SpeechCapture.Event.Error ->
                        _state.value = _state.value.copy(
                            phase = CapturePhase.IDLE,
                            error = event.message,
                        )
                }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        val text = _state.value.transcript
        if (text.isNotBlank() && _state.value.phase == CapturePhase.LISTENING) {
            classify(text)
        } else if (_state.value.phase == CapturePhase.LISTENING) {
            _state.value = _state.value.copy(phase = CapturePhase.IDLE)
        }
    }

    /** Typed fallback for when ASR mangles the audio, or the room is too loud. */
    fun classifyTyped(text: String) {
        if (text.isBlank()) return
        _state.value = _state.value.copy(transcript = text)
        classify(text)
    }

    private fun classify(transcript: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(phase = CapturePhase.CLASSIFYING)
            val record = delays.classify(caseId, transcript)
            _state.value = _state.value.copy(phase = CapturePhase.REVIEW, record = record)
        }
    }

    // --- one-tap edits on the review card; each one flips userEdited ---

    fun editCode(code: DelayCode) = edit { it.copy(code = code) }
    fun editDept(dept: String) = edit { it.copy(attributedDept = dept) }
    fun editAvoidable(value: Avoidability) = edit { it.copy(avoidable = value) }
    fun editEstimatedMin(min: Int?) = edit { it.copy(estimatedMin = min) }
    fun editNote(note: String) = edit { it.copy(note = note) }

    private fun edit(transform: (DelayRecordEntity) -> DelayRecordEntity) {
        val current = _state.value.record ?: return
        _state.value = _state.value.copy(record = transform(current).copy(userEdited = true))
    }

    fun discard() {
        _state.value = _state.value.copy(phase = CapturePhase.IDLE, record = null, transcript = "")
    }

    /** Persists and hands back the id so the caller can navigate to drafting. */
    fun confirm(onSaved: (String) -> Unit) {
        val record = _state.value.record ?: return
        viewModelScope.launch {
            delays.save(record)
            onSaved(record.id)
        }
    }

    companion object {
        fun factory(caseId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DelayCaptureViewModel(caseId) as T
        }
    }
}

enum class CapturePhase { IDLE, LISTENING, CLASSIFYING, REVIEW }

data class DelayCaptureUiState(
    val caseNumber: String = "",
    val procedureName: String = "",
    val phase: CapturePhase = CapturePhase.IDLE,
    val transcript: String = "",
    val level: Float = 0f,
    val record: DelayRecordEntity? = null,
    val error: String? = null,
    val micAvailable: Boolean = true,
)
