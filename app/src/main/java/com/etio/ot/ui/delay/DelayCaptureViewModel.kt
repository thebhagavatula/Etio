package com.etio.ot.ui.delay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.etio.ot.ai.LlmEngine
import com.etio.ot.ai.MessageDraftCoordinator
import com.etio.ot.ai.SpeechCapture
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.di.AiModule
import com.etio.ot.di.CoreModule
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val speech: SpeechCapture = AiModule.speechCapture,
    private val delays: DelayRepository = AiModule.delayRepository,
    private val cases: CaseRepository = CoreModule.caseRepository,
    private val engine: LlmEngine = AiModule.llmEngine,
    private val drafts: MessageDraftCoordinator = AiModule.draftCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(DelayCaptureUiState())
    val state: StateFlow<DelayCaptureUiState> = _state.asStateFlow()

    val engineState = engine.state

    private var listenJob: Job? = null
    private var timerJob: Job? = null

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

    /** Model load failed at startup — let the operator ask for it again on the spot. */
    fun retryModel() {
        viewModelScope.launch { engine.warmUp() }
    }

    /** Re-reads recogniser availability, e.g. after installing a language pack. */
    fun recheckMic() {
        _state.value = _state.value.copy(micAvailable = speech.isAvailable(), error = null)
    }

    /** The mic button's only entry point: tap to start, tap again to finish. */
    fun toggleListening() {
        if (_state.value.phase == CapturePhase.LISTENING) stopListening() else startListening()
    }

    fun startListening() {
        listenJob?.cancel()
        _state.value = _state.value.copy(
            phase = CapturePhase.LISTENING,
            transcript = "",
            error = null,
            record = null,
            elapsedSec = 0,
            level = 0f,
        )
        startTimer()
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
                        stopTimer()
                        _state.value = _state.value.copy(transcript = event.text)
                        classify(event.text)
                    }
                    is SpeechCapture.Event.Error -> {
                        stopTimer()
                        _state.value = _state.value.copy(
                            phase = CapturePhase.IDLE,
                            error = event.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Deliberately does NOT cancel [listenJob]: the flow has to stay collected until
     * the recogniser hands back its final transcript. [SpeechCapture.stop] asks for
     * that; cancelling here would leave us classifying the last partial instead.
     */
    fun stopListening() {
        if (_state.value.phase != CapturePhase.LISTENING) return
        stopTimer()
        if (listenJob?.isActive == true) {
            speech.stop()
        } else {
            _state.value = _state.value.copy(phase = CapturePhase.IDLE)
        }
    }

    /** Drives the elapsed counter, and hard-stops at [MAX_RECORDING_SEC]. */
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var seconds = 0
            while (seconds < MAX_RECORDING_SEC) {
                delay(1_000)
                seconds++
                _state.value = _state.value.copy(elapsedSec = seconds)
            }
            // A mic left open because someone forgot to tap again is the failure
            // mode toggle invites. Finish the session for them.
            speech.stop()
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    /** Typed fallback for when ASR mangles the audio, or the room is too loud. */
    fun classifyTyped(text: String) {
        if (text.isBlank()) return
        _state.value = _state.value.copy(transcript = text)
        classify(text, typed = true)
    }

    /**
     * A corrected transcript on the review card re-runs Job 1 against the correction.
     * Relabelling the card without re-reading would leave a record whose fields no
     * longer follow from the words underneath them.
     */
    fun reclassify(corrected: String) {
        val text = corrected.trim()
        if (text.isBlank() || text == _state.value.transcript) return
        _state.value = _state.value.copy(transcript = text)
        classify(text, typed = true)
    }

    private fun classify(transcript: String, typed: Boolean = false) {
        // The gate, immediately before Job 1. Room noise reaching the model produces
        // a confident-looking DelayRecord, which is worse than no record at all.
        if (transcript.trim().length < MIN_TRANSCRIPT_CHARS) {
            _state.value = _state.value.copy(
                phase = CapturePhase.IDLE,
                record = null,
                error = if (typed) TOO_SHORT_TYPED else TOO_SHORT_HEARD,
            )
            return
        }
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

    override fun onCleared() {
        stopTimer()
        speech.stop()
        super.onCleared()
    }

    /**
     * Persists, starts Job 2 immediately, and hands back the id.
     *
     * Drafting runs on the app-scoped coordinator rather than here, so the four
     * messages are already being written while she is still deciding whether to
     * notify — Notify reveals them instead of starting a wait.
     */
    fun confirm(onSaved: (String) -> Unit) {
        val record = _state.value.record ?: return
        viewModelScope.launch {
            delays.save(record)
            drafts.start(record)
            onSaved(record.id)
        }
    }

    companion object {
        /** Hard cap on one capture. Also the denominator the UI counts towards. */
        const val MAX_RECORDING_SEC = 30

        /** Below this, there is not enough there to classify — an OT noise burst, a cough. */
        const val MIN_TRANSCRIPT_CHARS = 15

        private const val TOO_SHORT_HEARD = "Didn't catch that — tap to try again"
        private const val TOO_SHORT_TYPED = "That's too short to place — a few more words, please"

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
    val elapsedSec: Int = 0,
    val record: DelayRecordEntity? = null,
    val error: String? = null,
    val micAvailable: Boolean = true,
)
