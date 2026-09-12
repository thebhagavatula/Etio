package com.etio.ot.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.etio.ot.di.AiModule
import com.etio.ot.di.CoreModule
import com.etio.ot.di.ServiceLocator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolates one classification through the app's own engine, with a hard timeout. */
@RunWith(AndroidJUnit4::class)
class SingleClassifyProbeTest {

    @Test
    fun oneClassificationCompletes() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ServiceLocator.init(ctx)
        CoreModule.config.installIfNeeded()
        assumeTrue(File(AiModule.resolveModelPath()).exists())

        runBlocking {
            Log.i(TAG, "SINGLE warming")
            val warm = AiModule.llmEngine.warmUp()
            Log.i(TAG, "SINGLE warm=${warm.isSuccess}")

            val started = System.currentTimeMillis()
            val parsed = withTimeoutOrNull(90_000) {
                Log.i(TAG, "SINGLE calling classify")
                AiModule.classifierForEval.classify("no trolley, porter has not come")
            }
            Log.i(TAG, "SINGLE done in ${System.currentTimeMillis() - started}ms -> ${parsed?.code}")
            assertNotNull("classification timed out after 90s", parsed)
        }
    }

    private companion object { const val TAG = "SingleProbe" }
}
