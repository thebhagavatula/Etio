package com.etio.ot.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Push-to-talk speech capture. Offline only — [RecognizerIntent.EXTRA_PREFER_OFFLINE]
 * is set, so the demo survives airplane mode.
 *
 * Emits partial results so the transcript appears while the coordinator is still
 * talking, which buys back most of the perceived classification latency.
 */
interface SpeechCapture {
    sealed interface Event {
        data object Ready : Event
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Error(val message: String, val isNoMatch: Boolean) : Event
        data class Rms(val db: Float) : Event
    }

    /** Cold flow. Collection starts listening; cancellation stops it. */
    fun listen(): Flow<Event>

    fun isAvailable(): Boolean
}

class AndroidSpeechCapture(private val context: Context) : SpeechCapture {

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(): Flow<SpeechCapture.Event> = callbackFlow {
        if (!isAvailable()) {
            trySend(SpeechCapture.Event.Error("Speech recognition unavailable on this device", false))
            close(); return@callbackFlow
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechCapture.Event.Ready)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechCapture.Event.Rms(rmsdB))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstResult()?.let { trySend(SpeechCapture.Event.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results.firstResult()
                if (text.isNullOrBlank()) {
                    trySend(SpeechCapture.Event.Error("Nothing was picked up", true))
                } else {
                    trySend(SpeechCapture.Event.Final(text))
                }
                close()
            }

            override fun onError(error: Int) {
                val noMatch = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                trySend(SpeechCapture.Event.Error(describe(error), noMatch))
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        recognizer.setRecognitionListener(listener)
        recognizer.startListening(intent())

        awaitClose {
            runCatching {
                recognizer.stopListening()
                recognizer.cancel()
                recognizer.destroy()
            }.onFailure { Log.w(TAG, "Recognizer teardown", it) }
        }
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // Indian English pack. Falls back to the device default if not installed.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem"
        SpeechRecognizer.ERROR_CLIENT -> "Recogniser stopped"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Offline language pack not installed — install Indian English in Settings"
        SpeechRecognizer.ERROR_NO_MATCH -> "Nothing was picked up"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy, try again"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
        else -> "Speech recognition error ($error)"
    }

    companion object {
        private const val TAG = "AndroidSpeechCapture"
        const val LANGUAGE = "en-IN"
    }
}
