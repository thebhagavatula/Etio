package com.etio.ot.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T0 capability probe. Throwaway by design — it answers one question and is not part
 * of the app's behaviour.
 *
 * `cloneSession()` is present in the 0.10.24 API, which tells us nothing useful: the
 * question is whether the backend this phone actually loads implements it. The
 * OpenCL GPU executor on several Adreno parts answers UNIMPLEMENTED, and that is a
 * runtime fact, not a compile-time one.
 *
 * Run it on the device, read the numbers out of logcat:
 *   adb shell am instrument -w -e class com.etio.ot.ai.SessionCloneProbeTest \
 *     com.etio.ot.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SessionCloneProbeTest {

    private val modelPath: String
        get() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val private = ModelLocator.appPrivatePath(ctx.filesDir.absolutePath)
            return if (File(private).exists()) private else ModelLocator.SIDELOAD_PATH
        }

    /** ~500 tokens of plausible filler, in the shape of a real Job 1 prefix. */
    private val filler: String = buildString {
        appendLine("You are a theatre operations assistant. You convert what a coordinator says into structured data.")
        repeat(40) { i ->
            appendLine(
                "Example $i: the coordinator said the set came back wet and the department " +
                    "is reprocessing it, so the case is held until it returns.",
            )
        }
    }

    @Test
    fun cloneSession_isSupported_andCheaperThanPrefill() {
        val file = File(modelPath)
        assumeTrue("Model not on device at $modelPath — sideload it first", file.exists())

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LlmInference.createFromOptions(
            ctx,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1280)
                .setMaxTopK(40)
                .setPreferredBackend(LlmInference.Backend.GPU)
                .build(),
        )

        engine.use {
            val base = LlmInferenceSession.createFromOptions(engine, options())
            val fillerTokens = runCatching { base.sizeInTokens(filler) }.getOrDefault(-1)

            val prefillStart = System.nanoTime()
            base.addQueryChunk(filler)
            val prefillMs = (System.nanoTime() - prefillStart) / 1_000_000

            Log.i(TAG, "PROBE filler=$fillerTokens tokens, addQueryChunk=${prefillMs}ms")

            // 1. Does the backend clone at all?
            val cloneStart = System.nanoTime()
            val clone = runCatching { base.cloneSession() }
                .onFailure { Log.e(TAG, "PROBE cloneSession REFUSED: ${it.message}") }
                .getOrNull()
            val cloneMs = (System.nanoTime() - cloneStart) / 1_000_000

            if (clone == null) {
                base.close()
                Log.e(TAG, "PROBE RESULT: clone UNSUPPORTED on this backend. T1 must abort.")
                assertTrue("cloneSession() refused on this backend — see logcat", false)
                return
            }

            // 2. Does the clone answer correctly from the inherited cache?
            val genStart = System.nanoTime()
            val answer = clone.use {
                it.addQueryChunk("\nNow reply with exactly one word: READY")
                it.generateResponse()
            }
            val cloneAnswerMs = (System.nanoTime() - genStart) / 1_000_000
            Log.i(TAG, "PROBE clone=${cloneMs}ms answer=${cloneAnswerMs}ms reply='${answer.trim().take(40)}'")

            // 3. Is the ORIGINAL still usable after being cloned from?
            val second = runCatching { base.cloneSession() }
                .onFailure { Log.e(TAG, "PROBE second clone failed: ${it.message}") }
                .getOrNull()
            val secondOk = second?.use {
                it.addQueryChunk("\nNow reply with exactly one word: AGAIN")
                it.generateResponse()
            } != null
            Log.i(TAG, "PROBE base reusable for a second clone: $secondOk")

            // 4. The comparison that justifies T1: full prefill, from cold.
            val fullStart = System.nanoTime()
            val fullAnswer = LlmInferenceSession.createFromOptions(engine, options()).use {
                it.addQueryChunk(filler + "\nNow reply with exactly one word: READY")
                it.generateResponse()
            }
            val fullMs = (System.nanoTime() - fullStart) / 1_000_000

            base.close()

            Log.i(
                TAG,
                "PROBE RESULT: clone+query=${cloneMs + cloneAnswerMs}ms vs full prefill+query=${fullMs}ms " +
                    "(filler $fillerTokens tokens); full reply='${fullAnswer.trim().take(40)}'",
            )

            assertTrue("clone produced an empty answer", answer.isNotBlank())
            assertTrue("base session was not reusable after cloning", secondOk)
        }
    }

    private fun options() = LlmInferenceSession.LlmInferenceSessionOptions.builder()
        .setTemperature(0.1f)
        .setTopK(1)
        .setTopP(0.9f)
        .setRandomSeed(42)
        .build()

    private companion object { const val TAG = "CloneProbe" }
}
