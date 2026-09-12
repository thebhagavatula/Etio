package com.etio.ot.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.etio.ot.di.AiModule
import com.etio.ot.di.CoreModule
import com.etio.ot.di.ServiceLocator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the eval harness on the device, through the same objects the debug screen
 * uses, and prints the table to logcat.
 *
 * The screen is the demo surface; this is the one that can be run from a laptop and
 * pasted into a commit message. Both call the same [EvalHarness].
 */
@RunWith(AndroidJUnit4::class)
class EvalHarnessInstrumentedTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun runsTheFullSetAndReportsNumbers() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ServiceLocator.init(ctx)
        CoreModule.config.installIfNeeded()

        assumeTrue("Model not on device", File(AiModule.resolveModelPath()).exists())

        runBlocking {
            val warm = AiModule.llmEngine.warmUp()
            assertTrue("engine failed to warm: ${warm.exceptionOrNull()?.message}", warm.isSuccess)

            val set: EvalHarness.EvalSet = json.decodeFromString(
                ctx.assets.open("config/eval_set.json").bufferedReader().use { it.readText() },
            )
            val cached = InferenceTelemetry.snapshot.value.prefixCached
            Log.i(TAG, "EVAL start: ${set.items.size} items, prefixCached=$cached")

            val harness = EvalHarness(AiModule.classifierForEval, AiModule.llmEngine)
            val results = harness.run(set, EvalHarness.Mode.entries.toList(), cached) { p ->
                if (p.done % 5 == 0) Log.i(TAG, "EVAL ${p.mode.label} ${p.done}/${p.total}")
            }

            results.forEach { mode ->
                Log.i(
                    TAG,
                    "EVAL RESULT ${mode.mode}: acc=${(mode.accuracy * 100).toInt()}% " +
                        "(${mode.correct}/${mode.total}) mean=${mode.meanMs}ms p50=${mode.p50Ms}ms " +
                        "p95=${mode.p95Ms}ms parseFail=${(mode.parseFailureRate * 100).toInt()}% " +
                        "groundRefuse=${(mode.groundingRejectionRate * 100).toInt()}% " +
                        "inventedMin=${mode.hallucinatedMinutes} agreement=${mode.agreementCounts}",
                )
                mode.caveat?.let { Log.i(TAG, "EVAL CAVEAT ${mode.mode}: $it") }
                mode.items.filterNot { it.correct }.forEach {
                    Log.i(TAG, "EVAL MISS ${mode.mode} ${it.id}: ${it.expected} -> ${it.actual}")
                }
            }

            assertTrue("no results", results.isNotEmpty())
            assertTrue("every mode ran every item", results.all { it.total == set.items.size })
        }
    }

    private companion object { const val TAG = "EvalHarness" }
}
