package com.etio.ot.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * With cloning unavailable, every call creates a fresh session, prefills the whole
 * prefix and closes it. This asks the only question that matters about that: can the
 * backend actually do it repeatedly?
 *
 * Logs one line per iteration, so a hang shows up as the iteration it stopped on.
 */
@RunWith(AndroidJUnit4::class)
class SessionChurnProbeTest {

    private val prefix = buildString {
        appendLine("You are a theatre operations assistant.")
        repeat(30) { appendLine("Example $it: the set came back wet and is being reprocessed.") }
    }

    @Test
    fun repeatedSessionCreateGenerateClose() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val path = ModelLocator.SIDELOAD_PATH
        assumeTrue(File(path).exists())

        val engine = LlmInference.createFromOptions(
            ctx,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(2048)
                .setMaxTopK(64)
                .setPreferredBackend(LlmInference.Backend.GPU)
                .build(),
        )
        Log.i(TAG, "CHURN engine up")

        engine.use {
            repeat(6) { i ->
                val started = System.currentTimeMillis()
                Log.i(TAG, "CHURN $i creating session")
                val session = LlmInferenceSession.createFromOptions(
                    engine,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTemperature(0.1f)
                        .setTopK(1)
                        .setTopP(0.9f)
                        .setRandomSeed(42)
                        .build(),
                )
                Log.i(TAG, "CHURN $i session created in ${System.currentTimeMillis() - started}ms")

                session.addQueryChunk(prefix + "\n\nReply with one word: OK\nJSON:")
                Log.i(TAG, "CHURN $i chunk added at ${System.currentTimeMillis() - started}ms")

                val answer = session.generateResponse()
                Log.i(
                    TAG,
                    "CHURN $i generated in ${System.currentTimeMillis() - started}ms: '${answer.trim().take(20)}'",
                )

                session.close()
                Log.i(TAG, "CHURN $i closed at ${System.currentTimeMillis() - started}ms")
            }
        }
        Log.i(TAG, "CHURN all iterations survived")
    }

    private companion object { const val TAG = "ChurnProbe" }
}
