package com.etio.ot.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs

/**
 * Tap-to-toggle speech capture. Offline only — [RecognizerIntent.EXTRA_PREFER_OFFLINE]
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

    /**
     * Finishes the in-flight session and lets the recogniser deliver what it heard
     * as [Event.Final]. This is the tap-to-stop path: cancelling the flow instead
     * would throw the transcript away.
     */
    fun stop()

    fun isAvailable(): Boolean
}

class AndroidSpeechCapture(private val context: Context) : SpeechCapture {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The session currently listening, if any. Written by the collector, read by [stop]. */
    @Volatile private var active: SpeechRecognizer? = null

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(): Flow<SpeechCapture.Event> = callbackFlow {
        if (!isAvailable()) {
            trySend(SpeechCapture.Event.Error("Speech recognition unavailable on this device", false))
            close(); return@callbackFlow
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        // onRmsChanged fires ~20x a second and the raw value wobbles by a couple of
        // dB even in a still room, which is what makes the level bar twitch. Smooth
        // it, rate-limit it, and only emit once it has actually moved (hysteresis).
        var smoothedDb = 0f
        var lastEmittedDb = Float.NEGATIVE_INFINITY
        var lastEmitMs = 0L

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechCapture.Event.Ready)
            }

            override fun onRmsChanged(rmsdB: Float) {
                smoothedDb += (rmsdB - smoothedDb) * RMS_SMOOTHING
                val now = SystemClock.uptimeMillis()
                if (now - lastEmitMs < RMS_MIN_INTERVAL_MS) return
                if (abs(smoothedDb - lastEmittedDb) < RMS_HYSTERESIS_DB) return
                lastEmitMs = now
                lastEmittedDb = smoothedDb
                trySend(SpeechCapture.Event.Rms(smoothedDb))
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
        active = recognizer

        awaitClose {
            active = null
            runCatching {
                recognizer.stopListening()
                recognizer.cancel()
                recognizer.destroy()
            }.onFailure { Log.w(TAG, "Recognizer teardown", it) }
        }
    }

    /**
     * [SpeechRecognizer.stopListening] ends the input and still delivers onResults;
     * [SpeechRecognizer.cancel] would not. The recogniser is main-thread only.
     */
    override fun stop() {
        val recognizer = active ?: return
        mainHandler.post {
            runCatching { recognizer.stopListening() }
                .onFailure { Log.w(TAG, "stopListening", it) }
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
        // The user decides when a capture ends, not the endpointer. A long silence
        // window lets a coordinator pause mid-sentence without being cut off, and the
        // minimum length stops the recogniser giving up the instant it starts.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_LENGTH_MS)
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
        private const val SILENCE_MS = 2800L
        private const val MINIMUM_LENGTH_MS = 2000L

        /** Level-meter conditioning: weight of each new reading, floor, and rate limit. */
        private const val RMS_SMOOTHING = 0.3f
        private const val RMS_HYSTERESIS_DB = 0.75f
        private const val RMS_MIN_INTERVAL_MS = 100L
    }
}
